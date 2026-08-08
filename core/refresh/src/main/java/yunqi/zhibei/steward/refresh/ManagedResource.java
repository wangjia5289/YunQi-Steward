package yunqi.zhibei.steward.refresh;

import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.HealthCheck;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import yunqi.zhibei.steward.lifecycle.ResourceCloser;
import yunqi.zhibei.steward.lifecycle.ResourceFactory;
import yunqi.zhibei.steward.observation.LifecycleEvent;
import yunqi.zhibei.steward.observation.LifecycleEventBuffer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps one configured resource active while at most one other resource is created or drained.
 *
 * <p>Startup reconciliation is synchronous. Later configuration signals only mark refresh work as
 * pending and return; one virtual-thread worker reads the latest snapshot and coalesces intermediate
 * changes. While an older generation is draining, no additional candidate is created.
 *
 * <p>A lease pins exactly one generation. Operations must not return the resource, a child object
 * tied to it, or asynchronous work which still uses it. Use {@link #executeAsync(ResourceOperation)}
 * for a completion-stage lifetime, or retain an explicit {@link Lease}.
 *
 * @param <T> resource type; a managed instance can never change this type
 * @param <C> immutable configuration type
 */
public final class ManagedResource<T, C> implements AutoCloseable {

    private static final Duration DEFAULT_CLOSE_WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_CLOSE_FAILURES = 100;

    private final ConfigurationSource<C> configurationSource;
    private final ResourceFactory<? super C, ? extends T> factory;
    private final HealthCheck<? super T> healthCheck;
    private final ResourceCloser<? super T> closer;
    private final Duration closeWaitTimeout;
    private final LifecycleRuntime runtime;
    private final LifecycleEventBuffer lifecycleEvents;
    private final AtomicReference<Generation<T>> active = new AtomicReference<>();
    private final AtomicReference<ConfigurationSnapshot<C>> latestObserved = new AtomicReference<>();
    private final Set<Generation<T>> retiring = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Queue<FailureSnapshot> closeFailures = new ArrayDeque<>();
    private final AtomicReference<WorkerRun> worker = new AtomicReference<>();
    private final Set<ManualRefreshRun> manualRefreshes =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final StatusTracker statusTracker = new StatusTracker();
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final Object refreshAdmission = new Object();
    private final CountDownLatch subscriptionClosed = new CountDownLatch(1);
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong nextGeneration = new AtomicLong();
    private final ConfigurationSource.Subscription subscription;

    private ManagedResource(Builder<T, C> builder) {
        configurationSource = builder.configurationSource;
        factory = builder.binding;
        healthCheck = builder.healthCheck;
        closer = builder.binding;
        closeWaitTimeout = builder.closeWaitTimeout;
        runtime = builder.runtime;
        lifecycleEvents = builder.lifecycleEvents;

        subscription = subscribe();
        try {
            reconcileObserved(true);
            statusTracker.setLifecycle(ManagedResourceStatus.Lifecycle.RUNNING);
            initialized.set(true);
            if (statusTracker.refreshPending()) {
                scheduleWorker();
            }
        } catch (RuntimeException | Error initializationFailure) {
            try {
                cleanupFailedInitialization();
            } catch (RuntimeException | Error cleanupFailure) {
                initializationFailure.addSuppressed(cleanupFailure);
            }
            throw initializationFailure;
        }
    }

    /**
     * Returns a builder for one configuration source and refresh-safe binding.
     *
     * <p>The required binding is the proof that old and candidate resources may overlap. The
     * builder therefore does not accept independent factory or closer callbacks which could bypass
     * that lifecycle contract.
     */
    public static <T, C> Builder<T, C> builder(
            ConfigurationSource<C> configurationSource,
            ResourceBinding<C, T> binding) {
        return new Builder<>(configurationSource, binding);
    }

    /** Creates a managed resource from one refresh-safe binding. */
    public static <T, C> ManagedResource<T, C> bind(
            ConfigurationSource<C> configurationSource,
            ResourceBinding<C, T> binding) {
        Objects.requireNonNull(binding, "binding");
        return ManagedResource.<T, C>builder(configurationSource, binding).build();
    }

    /** Returns a lease which pins the currently active generation until the lease closes. */
    public Lease<T> acquire() {
        int attempts = 0;
        while (true) {
            ensureUsable();
            Generation<T> generation = active.get();
            if (generation == null) {
                throw new IllegalStateException("No managed resource is active");
            }
            if (generation.tryAcquire()) {
                if (!closed.get() && active.get() == generation) {
                    return new Lease<>(generation);
                }
                generation.release();
            }

            if (++attempts % 64 == 0) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
    }

    /** Executes one synchronous operation against a leased active generation. */
    public <R, E extends Exception> R execute(
            ResourceOperation<? super T, R, E> operation) throws E {
        Objects.requireNonNull(operation, "operation");
        try (Lease<T> lease = acquire()) {
            return lease.execute(operation);
        }
    }

    /**
     * Executes an asynchronous operation while retaining its generation until the returned stage
     * completes.
     */
    public <R, E extends Exception> CompletionStage<R> executeAsync(
            ResourceOperation<? super T, CompletionStage<R>, E> operation) throws E {
        Objects.requireNonNull(operation, "operation");
        Lease<T> lease = acquire();
        boolean completionAttached = false;
        try {
            CompletionStage<R> stage = Objects.requireNonNull(
                    lease.execute(operation),
                    "operation returned null");
            CompletionStage<R> managedStage = Objects.requireNonNull(
                    stage.whenComplete((ignored, failure) -> lease.close()),
                    "operation stage returned null from whenComplete()");
            completionAttached = true;
            return managedStage;
        } finally {
            if (!completionAttached) {
                lease.close();
            }
        }
    }

    /**
     * Reconciles the latest desired configuration on the calling thread.
     *
     * <p>If another generation is still draining, the latest desired snapshot remains pending and
     * is reconciled automatically after retirement completes.
     */
    public void refresh() {
        ManualRefreshRun run = admitManualRefresh();
        try {
            refreshLock.lock();
            try {
                if (closed.get()) {
                    return;
                }
                if (statusTracker.lifecycle()
                        == ManagedResourceStatus.Lifecycle.REFRESH_DISABLED) {
                    statusTracker.setRefreshPending(false);
                    throw new IllegalStateException(
                            "Managed refresh is disabled after a fatal failure");
                }
                reconcileObserved(false);
            } catch (RuntimeException | Error failure) {
                disableRefresh(FailureSnapshot.Stage.REFRESH_ENGINE, failure);
                throw failure;
            } finally {
                refreshLock.unlock();
            }
        } finally {
            finishManualRefresh(run);
        }
    }

    /**
     * Checks the active generation while holding a lease against concurrent retirement.
     *
     * <p>This is a demand-time probe. Its result or exception is returned directly and is not
     * retained as refresh lifecycle state. An interrupted probe restores the calling thread's
     * interrupt status before propagating the exception.
     */
    public Health health() throws Exception {
        try {
            return execute(resource -> Objects.requireNonNull(
                    healthCheck.check(resource),
                    "healthCheck.check() returned null"));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    /**
     * Returns one internally consistent, secret-free snapshot of lifecycle and refresh state.
     * The snapshot can become stale immediately after this method returns.
     */
    public ManagedResourceStatus status() {
        isTerminated();
        return statusTracker.snapshot();
    }

    /** Returns the latest redacted refresh failure, if the latest reconciliation failed. */
    public Optional<FailureSnapshot> lastRefreshFailure() {
        return statusTracker.lastRefreshFailure();
    }

    /** Returns a bounded snapshot of redacted candidate and generation close failures. */
    public List<FailureSnapshot> closeFailures() {
        synchronized (closeFailures) {
            return List.copyOf(closeFailures);
        }
    }

    /** Returns whether subscription shutdown and all generation retirement have completed. */
    public boolean isTerminated() {
        boolean terminated = closed.get()
                && subscriptionClosed.getCount() == 0
                && worker.get() == null
                && manualRefreshes.isEmpty()
                && !refreshLock.isLocked()
                && active.get() == null
                && retiring.isEmpty()
                && !statusTracker.replacementInProgress();
        if (terminated) {
            statusTracker.compareAndSetLifecycle(
                    ManagedResourceStatus.Lifecycle.CLOSING,
                    ManagedResourceStatus.Lifecycle.TERMINATED);
        }
        return terminated;
    }

    /**
     * Waits for reconciliation to reach a stable idle point up to the supplied timeout.
     *
     * <p>This includes refresh work which was pending behind a draining generation and becomes
     * runnable when that generation closes. A configuration change which occurs after the stable
     * idle point is a subsequent operation. A {@code true} result means no lifecycle work remains;
     * it does not mean the desired revision was applied. Compare the active and desired revisions
     * and inspect the last refresh failure in {@link #status()} when convergence matters.
     */
    public boolean awaitIdle(Duration timeout) {
        TimeoutBudget budget = TimeoutBudget.start(timeout);
        return awaitQuiescence(budget);
    }

    /**
     * Waits for complete shutdown, including admitted manual refreshes and subscription closure.
     * Returns false when not closed or when the timeout expires.
     */
    public boolean awaitTermination(Duration timeout) {
        if (!closed.get()) {
            return false;
        }
        TimeoutBudget budget = TimeoutBudget.start(timeout);
        return await(subscriptionClosed, budget.remainingNanos())
                && awaitQuiescence(budget)
                && isTerminated();
    }

    /**
     * Prevents new operations and refreshes, starts shutdown, and waits up to the configured
     * close-wait timeout. Already-admitted refreshes may finish cleanup but cannot publish after
     * shutdown starts. If a cleanup thread cannot be started, cleanup runs on the calling thread
     * and may therefore exceed that wait timeout.
     */
    @Override
    public void close() {
        boolean startClosing;
        synchronized (refreshAdmission) {
            startClosing = closed.compareAndSet(false, true);
            if (startClosing) {
                statusTracker.startClosing();
            }
        }
        Throwable shutdownFailure = startClosing ? startShutdownTasks() : null;
        awaitTermination(closeWaitTimeout);
        rethrowUnchecked(shutdownFailure);
    }

    private void reconcile(boolean failIfUnavailable) {
        while (!closed.get()) {
            if (!retiring.isEmpty()) {
                statusTracker.setRefreshPending(true);
                return;
            }

            statusTracker.setRefreshPending(false);
            ConfigurationSnapshot<C> desired = readSnapshot(failIfUnavailable);
            if (desired == null) {
                return;
            }

            Generation<T> current = active.get();
            if (current != null && current.revision() >= desired.revision()) {
                statusTracker.clearRefreshFailure();
                return;
            }

            Candidate<T> result = createCandidate(desired);
            if (result.failure() != null) {
                ConfigurationSnapshot<C> latest = readSnapshot(false);
                if (isNewerThan(latest, desired)) {
                    statusTracker.setRefreshPending(true);
                    continue;
                }
                recordRefreshFailure(result.failure());
                failInitializationIfNeeded(failIfUnavailable, result.failure());
                return;
            }

            Generation<T> candidate = result.generation();
            try {
                if (closed.get()) {
                    closeUnpublishedCandidate(candidate);
                    return;
                }

                ConfigurationSnapshot<C> latest = readSnapshot(failIfUnavailable);
                if (latest == null) {
                    closeUnpublishedCandidate(candidate);
                    return;
                }
                if (isNewerThan(latest, desired)) {
                    closeUnpublishedCandidate(candidate);
                    statusTracker.setRefreshPending(true);
                    continue;
                }

                Publication publication = publish(candidate);
                if (publication != Publication.PUBLISHED) {
                    return;
                }
                statusTracker.recordRefreshSuccess(candidate, runtime.now());

                ConfigurationSnapshot<C> afterPublication = readSnapshot(false);
                if (isNewerThan(afterPublication, desired)) {
                    statusTracker.setRefreshPending(true);
                }
                return;
            } finally {
                statusTracker.setCandidateInProgress(false);
            }
        }
    }

    private void reconcileObserved(boolean initialization) {
        if (!lifecycleEvents.isEnabled()) {
            reconcile(initialization);
            return;
        }
        EventTimer timer = EventTimer.start(lifecycleEvents, runtime);
        ManagedResourceStatus before = statusTracker.snapshot();
        try {
            reconcile(initialization);
        } catch (RuntimeException | Error failure) {
            ManagedResourceStatus after = statusTracker.snapshot();
            if (after.refreshFailures() > before.refreshFailures()
                    && after.lastRefreshFailure().isPresent()) {
                timer.failure(
                        initialization ? LifecycleEvent.Stage.START : LifecycleEvent.Stage.REFRESH,
                        after.lastRefreshFailure().orElseThrow());
            } else {
                timer.failure(
                        initialization ? LifecycleEvent.Stage.START : LifecycleEvent.Stage.REFRESH,
                        0,
                        after.desiredRevision(),
                        failure.getClass().getName());
            }
            throw failure;
        }

        ManagedResourceStatus after = statusTracker.snapshot();
        if (after.refreshSuccesses() > before.refreshSuccesses()) {
            timer.success(
                    initialization ? LifecycleEvent.Stage.START : LifecycleEvent.Stage.REFRESH,
                    after.activeGeneration(),
                    after.activeRevision());
        } else if (after.refreshFailures() > before.refreshFailures()
                && after.lastRefreshFailure().isPresent()) {
            timer.failure(
                    initialization ? LifecycleEvent.Stage.START : LifecycleEvent.Stage.REFRESH,
                    after.lastRefreshFailure().orElseThrow());
        }
    }

    private ConfigurationSnapshot<C> readSnapshot(boolean failIfUnavailable) {
        try {
            ConfigurationSnapshot<C> observed = Objects.requireNonNull(
                    configurationSource.snapshot(),
                    "configurationSource.snapshot() returned null");
            Objects.requireNonNull(
                    observed.configuration(),
                    "configurationSource.snapshot().configuration() returned null");
            return acceptObservation(observed);
        } catch (RuntimeException failure) {
            FailureSnapshot snapshot = FailureSnapshot.from(
                    FailureSnapshot.Stage.CONFIGURATION_SOURCE,
                    failure,
                    runtime.now());
            recordRefreshFailure(snapshot);
            failInitializationIfNeeded(failIfUnavailable, snapshot);
            return null;
        } catch (Error failure) {
            disableRefresh(FailureSnapshot.Stage.CONFIGURATION_SOURCE, failure);
            throw failure;
        }
    }

    private ConfigurationSnapshot<C> acceptObservation(ConfigurationSnapshot<C> observed) {
        while (true) {
            ConfigurationSnapshot<C> previous = latestObserved.get();
            if (previous != null && observed.revision() <= previous.revision()) {
                return previous;
            }
            if (latestObserved.compareAndSet(previous, observed)) {
                statusTracker.observeRevision(observed.revision());
                return observed;
            }
        }
    }

    private static boolean isNewerThan(
            ConfigurationSnapshot<?> candidate,
            ConfigurationSnapshot<?> reference) {
        return candidate != null && candidate.revision() > reference.revision();
    }

    private Candidate<T> createCandidate(ConfigurationSnapshot<C> snapshot) {
        statusTracker.setCandidateInProgress(true);
        T resource;
        try {
            resource = Objects.requireNonNull(
                    factory.create(snapshot.configuration()),
                    "resourceFactory.create() returned null");
        } catch (Exception failure) {
            restoreInterrupt(failure);
            statusTracker.setCandidateInProgress(false);
            return Candidate.failure(FailureSnapshot.from(
                    FailureSnapshot.Stage.RESOURCE_CREATION,
                    failure,
                    0,
                    snapshot.revision(),
                    runtime.now()));
        } catch (Error failure) {
            statusTracker.setCandidateInProgress(false);
            disableRefresh(
                    FailureSnapshot.Stage.RESOURCE_CREATION,
                    failure,
                    0,
                    snapshot.revision());
            throw failure;
        }

        Generation<T> candidate = new Generation<>(
                nextGeneration.incrementAndGet(),
                snapshot.revision(),
                resource,
                this::closeGeneration,
                this::leaseCountChanged,
                runtime);
        Health health;
        try {
            health = Objects.requireNonNull(
                    healthCheck.check(resource),
                    "healthCheck.check() returned null");
        } catch (Exception failure) {
            restoreInterrupt(failure);
            FailureSnapshot snapshotFailure = FailureSnapshot.from(
                    FailureSnapshot.Stage.CANDIDATE_HEALTH_CHECK,
                    failure,
                    candidate.id(),
                    candidate.revision(),
                    runtime.now());
            try {
                closeUnpublishedCandidate(candidate);
            } catch (RuntimeException | Error closeFailure) {
                closeFailure.addSuppressed(failure);
                throw closeFailure;
            }
            return Candidate.failure(snapshotFailure);
        } catch (Error failure) {
            disableRefresh(
                    FailureSnapshot.Stage.CANDIDATE_HEALTH_CHECK,
                    failure,
                    candidate.id(),
                    candidate.revision());
            try {
                closeUnpublishedCandidate(candidate);
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }

        if (!health.isHealthy()) {
            FailureSnapshot unhealthy = FailureSnapshot.unhealthy(
                    candidate.id(), candidate.revision(), runtime.now());
            closeUnpublishedCandidate(candidate);
            return Candidate.failure(unhealthy);
        }
        return Candidate.success(candidate);
    }

    private Publication publish(Generation<T> candidate) {
        while (true) {
            if (closed.get()) {
                closeUnpublishedCandidate(candidate);
                return Publication.CLOSED;
            }
            Generation<T> previous = active.get();
            candidate.markPublished();
            if (!active.compareAndSet(previous, candidate)) {
                candidate.cancelPublication();
                continue;
            }
            if (previous != null) {
                retire(previous);
            }
            if (!closed.get()) {
                return Publication.PUBLISHED;
            }
            Generation<T> published = active.getAndSet(null);
            statusTracker.clearActiveGeneration();
            if (published != null) {
                retire(published);
            }
            return Publication.CLOSED;
        }
    }

    private void closeUnpublishedCandidate(Generation<T> candidate) {
        statusTracker.setCandidateInProgress(true);
        try {
            candidate.closeUnpublished();
        } finally {
            statusTracker.setCandidateInProgress(false);
        }
    }

    private void retire(Generation<T> generation) {
        if (retiring.add(generation)) {
            statusTracker.retirementStarted();
        }
        generation.beginDraining();
    }

    private void closeGeneration(Generation<T> generation) {
        Throwable error = null;
        LifecycleEvent.Stage eventStage = generation.wasPublished()
                ? LifecycleEvent.Stage.CLOSE
                : LifecycleEvent.Stage.ROLLBACK;
        EventTimer timer = EventTimer.start(lifecycleEvents, runtime);
        try {
            T resource = generation.resourceForClose();
            try {
                closer.close(resource);
                timer.success(eventStage, generation.id(), generation.revision());
            } catch (Exception closeFailure) {
                restoreInterrupt(closeFailure);
                FailureSnapshot.Stage stage = generation.wasPublished()
                        ? FailureSnapshot.Stage.RESOURCE_CLOSE
                        : FailureSnapshot.Stage.CANDIDATE_CLOSE;
                recordCloseFailure(FailureSnapshot.from(
                        stage,
                        closeFailure,
                        generation.id(),
                        generation.revision(),
                        runtime.now()));
                timer.failure(
                        eventStage,
                        generation.id(),
                        generation.revision(),
                        closeFailure.getClass().getName());
            } catch (Error closeFailure) {
                FailureSnapshot.Stage stage = generation.wasPublished()
                        ? FailureSnapshot.Stage.RESOURCE_CLOSE
                        : FailureSnapshot.Stage.CANDIDATE_CLOSE;
                recordCloseFailure(FailureSnapshot.from(
                        stage,
                        closeFailure,
                        generation.id(),
                        generation.revision(),
                        runtime.now()));
                timer.failure(
                        eventStage,
                        generation.id(),
                        generation.revision(),
                        closeFailure.getClass().getName());
                error = closeFailure;
                if (!closed.get()) {
                    disableRefresh(
                            stage,
                            closeFailure,
                            generation.id(),
                            generation.revision());
                }
            } finally {
                generation.clearResource();
            }
        } finally {
            generation.finishClosing();
            if (retiring.contains(generation)) {
                statusTracker.retirementFinished();
                retiring.remove(generation);
            }
            if (statusTracker.refreshPending() && !closed.get() && statusTracker.lifecycle()
                    == ManagedResourceStatus.Lifecycle.RUNNING) {
                scheduleWorker();
            }
        }
        if (error instanceof Error failure) {
            throw failure;
        }
    }

    private ConfigurationSource.Subscription subscribe() {
        EventTimer timer = EventTimer.start(lifecycleEvents, runtime);
        try {
            return Objects.requireNonNull(
                    configurationSource.subscribe(this::configurationChanged),
                    "configurationSource.subscribe() returned null");
        } catch (RuntimeException failure) {
            closed.set(true);
            statusTracker.setLifecycle(ManagedResourceStatus.Lifecycle.TERMINATED);
            subscriptionClosed.countDown();
            timer.failure(
                    LifecycleEvent.Stage.START, 0, 0, failure.getClass().getName());
            throw initializationFailure(
                    "Failed to subscribe to the configuration source",
                    FailureSnapshot.from(
                            FailureSnapshot.Stage.CONFIGURATION_SOURCE,
                            failure,
                            runtime.now()));
        } catch (Error failure) {
            closed.set(true);
            statusTracker.setLifecycle(ManagedResourceStatus.Lifecycle.TERMINATED);
            subscriptionClosed.countDown();
            timer.failure(
                    LifecycleEvent.Stage.START, 0, 0, failure.getClass().getName());
            throw failure;
        }
    }

    private void configurationChanged() {
        if (closed.get() || statusTracker.lifecycle()
                == ManagedResourceStatus.Lifecycle.REFRESH_DISABLED) {
            return;
        }
        statusTracker.setRefreshPending(true);
        if (initialized.get()) {
            scheduleWorker();
        }
    }

    private void scheduleWorker() {
        if (closed.get()
                || !initialized.get()
                || statusTracker.lifecycle() != ManagedResourceStatus.Lifecycle.RUNNING
                || !retiring.isEmpty()) {
            return;
        }
        WorkerRun run = new WorkerRun();
        if (!worker.compareAndSet(null, run)) {
            return;
        }
        try {
            runtime.start("managed-resource-refresh", () -> runWorker(run));
        } catch (RuntimeException | Error failure) {
            worker.compareAndSet(run, null);
            run.finished().countDown();
            disableRefresh(FailureSnapshot.Stage.REFRESH_ENGINE, failure);
            throw failure;
        }
    }

    private void runWorker(WorkerRun run) {
        try {
            refreshLock.lock();
            try {
                if (!closed.get()
                        && statusTracker.lifecycle() == ManagedResourceStatus.Lifecycle.RUNNING
                        && statusTracker.refreshPending()) {
                    reconcileObserved(false);
                }
            } finally {
                refreshLock.unlock();
            }
        } catch (RuntimeException | Error failure) {
            disableRefresh(FailureSnapshot.Stage.REFRESH_ENGINE, failure);
            throw failure;
        } finally {
            worker.compareAndSet(run, null);
            run.finished().countDown();
            if (statusTracker.refreshPending()
                    && !closed.get()
                    && statusTracker.lifecycle() == ManagedResourceStatus.Lifecycle.RUNNING
                    && retiring.isEmpty()) {
                scheduleWorker();
            }
        }
    }

    private void disableRefresh(FailureSnapshot.Stage stage, Throwable failure) {
        disableRefresh(stage, failure, 0, 0);
    }

    private void disableRefresh(
            FailureSnapshot.Stage stage,
            Throwable failure,
            long generation,
            long revision) {
        statusTracker.disableRefresh(
                FailureSnapshot.from(
                        stage,
                        failure,
                        generation,
                        revision,
                        runtime.now()));
    }

    private void recordRefreshFailure(FailureSnapshot failure) {
        statusTracker.recordRefreshFailure(failure);
    }

    private void leaseCountChanged(Generation<T> generation) {
        statusTracker.updateActiveLeases(generation.id(), generation.inFlight());
    }

    private void failInitializationIfNeeded(
            boolean failIfUnavailable,
            FailureSnapshot failure) {
        if (failIfUnavailable && active.get() == null) {
            throw initializationFailure("Initial managed resource refresh failed", failure);
        }
    }

    private static IllegalStateException initializationFailure(
            String message,
            FailureSnapshot failure) {
        return new IllegalStateException(
                message + " at " + failure.stage()
                        + " (" + failure.failureType() + ")"
                        + ", generation=" + failure.generation()
                        + ", revision=" + failure.revision());
    }

    private void startSubscriptionClose() {
        startCleanupTask(
                runtime,
                "managed-resource-subscription-close",
                this::closeSubscription);
    }

    private void closeSubscription() {
        try {
            subscription.close();
        } catch (RuntimeException failure) {
            recordCloseFailure(FailureSnapshot.from(
                    FailureSnapshot.Stage.SUBSCRIPTION_CLOSE,
                    failure,
                    runtime.now()));
        } catch (Error failure) {
            recordCloseFailure(FailureSnapshot.from(
                    FailureSnapshot.Stage.SUBSCRIPTION_CLOSE,
                    failure,
                    runtime.now()));
            throw failure;
        } finally {
            subscriptionClosed.countDown();
        }
    }

    private void cleanupFailedInitialization() {
        initialized.set(true);
        closed.set(true);
        statusTracker.startClosing();
        Throwable shutdownFailure = startShutdownTasks();
        awaitTermination(closeWaitTimeout);
        rethrowUnchecked(shutdownFailure);
    }

    private Throwable startShutdownTasks() {
        statusTracker.setRefreshPending(false);
        Throwable failure = null;
        try {
            retireActiveGeneration();
        } catch (RuntimeException | Error retirementFailure) {
            failure = retirementFailure;
        }
        try {
            startSubscriptionClose();
        } catch (RuntimeException | Error subscriptionFailure) {
            if (failure == null) {
                failure = subscriptionFailure;
            } else {
                failure.addSuppressed(subscriptionFailure);
            }
        }
        return failure;
    }

    private void retireActiveGeneration() {
        statusTracker.retirementTransferStarted();
        try {
            Generation<T> generation = active.getAndSet(null);
            statusTracker.clearActiveGeneration();
            if (generation != null) {
                retire(generation);
            }
        } finally {
            statusTracker.retirementTransferFinished();
        }
    }

    private void recordCloseFailure(FailureSnapshot failure) {
        synchronized (closeFailures) {
            closeFailures.add(failure);
            while (closeFailures.size() > MAX_CLOSE_FAILURES) {
                closeFailures.remove();
            }
            statusTracker.setCloseFailures(closeFailures.size());
        }
    }

    private void ensureUsable() {
        if (closed.get()) {
            throw new IllegalStateException("The managed resource is closed");
        }
    }

    private void ensureRefreshEnabled() {
        ensureUsable();
        if (statusTracker.lifecycle() == ManagedResourceStatus.Lifecycle.REFRESH_DISABLED) {
            throw new IllegalStateException("Managed refresh is disabled after a fatal failure");
        }
    }

    private ManualRefreshRun admitManualRefresh() {
        synchronized (refreshAdmission) {
            ensureRefreshEnabled();
            ManualRefreshRun run = new ManualRefreshRun();
            manualRefreshes.add(run);
            statusTracker.setRefreshPending(true);
            return run;
        }
    }

    private void finishManualRefresh(ManualRefreshRun run) {
        manualRefreshes.remove(run);
        run.finished().countDown();
    }

    private boolean awaitQuiescence(TimeoutBudget timeout) {
        if (refreshLock.isHeldByCurrentThread()) {
            return false;
        }
        while (true) {
            if (!awaitWorkerCompletion(timeout)
                    || !awaitManualRefreshCompletion(timeout)
                    || !awaitReconciliationCompletion(timeout)
                    || !awaitGenerationRetirements(timeout)) {
                return false;
            }

            if (statusTracker.refreshPending()
                    && !closed.get()
                    && statusTracker.lifecycle() == ManagedResourceStatus.Lifecycle.RUNNING) {
                scheduleWorker();
                continue;
            }
            if (worker.get() == null
                    && manualRefreshes.isEmpty()
                    && !refreshLock.isLocked()
                    && retiring.isEmpty()
                    && !statusTracker.replacementInProgress()
                    && !statusTracker.refreshPending()) {
                return true;
            }
            if (timeout.remainingNanos() == 0) {
                return false;
            }
        }
    }

    private boolean awaitWorkerCompletion(TimeoutBudget timeout) {
        while (true) {
            WorkerRun run = worker.get();
            if (run == null) {
                return true;
            }
            if (!await(run.finished(), timeout.remainingNanos())) {
                return false;
            }
        }
    }

    private boolean awaitManualRefreshCompletion(TimeoutBudget timeout) {
        while (true) {
            List<ManualRefreshRun> snapshot = List.copyOf(manualRefreshes);
            if (snapshot.isEmpty()) {
                return true;
            }
            for (ManualRefreshRun run : snapshot) {
                if (!await(run.finished(), timeout.remainingNanos())) {
                    return false;
                }
            }
        }
    }

    private boolean awaitReconciliationCompletion(TimeoutBudget timeout) {
        if (refreshLock.isHeldByCurrentThread()) {
            return false;
        }
        try {
            if (!refreshLock.tryLock(timeout.remainingNanos(), TimeUnit.NANOSECONDS)) {
                return false;
            }
            refreshLock.unlock();
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean awaitGenerationRetirements(TimeoutBudget timeout) {
        while (true) {
            List<Generation<T>> snapshot = List.copyOf(retiring);
            if (snapshot.isEmpty()) {
                return true;
            }
            if (timeout.remainingNanos() == 0) {
                return false;
            }
            for (Generation<T> generation : snapshot) {
                if (!generation.awaitClosed(timeout.remainingNanos())) {
                    return false;
                }
            }
        }
    }

    private static boolean await(CountDownLatch latch, long timeoutNanos) {
        try {
            return latch.await(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(name + " is too large", failure);
        }
        return duration;
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(name + " is too large", failure);
        }
        return duration;
    }

    private static void restoreInterrupt(Throwable failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static void startCleanupTask(
            LifecycleRuntime runtime,
            String name,
            Runnable cleanup) {
        try {
            runtime.start(name, cleanup);
        } catch (RuntimeException launchFailure) {
            try {
                cleanup.run();
            } catch (RuntimeException | Error cleanupFailure) {
                cleanupFailure.addSuppressed(launchFailure);
                throw cleanupFailure;
            }
        } catch (Error launchFailure) {
            try {
                cleanup.run();
            } catch (RuntimeException | Error cleanupFailure) {
                launchFailure.addSuppressed(cleanupFailure);
            }
            throw launchFailure;
        }
    }

    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }
    }

    /** Configures one managed same-type resource owner. */
    public static final class Builder<T, C> {

        private final ConfigurationSource<C> configurationSource;
        private final ResourceBinding<C, T> binding;
        private HealthCheck<? super T> healthCheck;
        private Duration closeWaitTimeout = DEFAULT_CLOSE_WAIT_TIMEOUT;
        private LifecycleRuntime runtime = LifecycleRuntime.system();
        private LifecycleEventBuffer lifecycleEvents = LifecycleEventBuffer.noop();

        private Builder(
                ConfigurationSource<C> configurationSource,
                ResourceBinding<C, T> binding) {
            this.configurationSource = Objects.requireNonNull(
                    configurationSource, "configurationSource");
            this.binding = Objects.requireNonNull(binding, "binding");
            healthCheck = binding;
        }

        /** Overrides the binding probe without changing resource creation or ownership. */
        public Builder<T, C> healthCheck(HealthCheck<? super T> healthCheck) {
            this.healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
            return this;
        }

        /**
         * Sets how long {@link ManagedResource#close()} waits for complete termination after
         * cleanup tasks have started.
         */
        public Builder<T, C> closeWaitTimeout(Duration closeWaitTimeout) {
            this.closeWaitTimeout = requirePositive(closeWaitTimeout, "closeWaitTimeout");
            return this;
        }

        /**
         * Publishes low-frequency, secret-free lifecycle facts to a caller-owned event buffer.
         * The managed resource does not close the buffer.
         */
        public Builder<T, C> lifecycleEvents(LifecycleEventBuffer lifecycleEvents) {
            this.lifecycleEvents = Objects.requireNonNull(lifecycleEvents, "lifecycleEvents");
            return this;
        }

        Builder<T, C> runtime(LifecycleRuntime runtime) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            return this;
        }

        /** Subscribes, performs initial reconciliation, and returns the managed owner. */
        public ManagedResource<T, C> build() {
            return new ManagedResource<>(this);
        }
    }

    /** Pins one resource generation without exposing its native reference to escape. */
    public static final class Lease<T> implements AutoCloseable {

        private Generation<T> generation;

        private Lease(Generation<T> generation) {
            this.generation = generation;
        }

        /** Executes one operation against this lease's pinned generation. */
        public synchronized <R, E extends Exception> R execute(
                ResourceOperation<? super T, R, E> operation) throws E {
            Objects.requireNonNull(operation, "operation");
            if (generation == null) {
                throw new IllegalStateException("The resource lease is closed");
            }
            return generation.execute(operation);
        }

        @Override
        public synchronized void close() {
            Generation<T> current = generation;
            if (current != null) {
                generation = null;
                current.release();
            }
        }
    }

    private record Candidate<T>(
            Generation<T> generation,
            FailureSnapshot failure) {

        private static <T> Candidate<T> success(Generation<T> generation) {
            return new Candidate<>(Objects.requireNonNull(generation, "generation"), null);
        }

        private static <T> Candidate<T> failure(FailureSnapshot failure) {
            return new Candidate<>(null, Objects.requireNonNull(failure, "failure"));
        }
    }

    private record WorkerRun(CountDownLatch finished) {
        private WorkerRun() {
            this(new CountDownLatch(1));
        }
    }

    private record ManualRefreshRun(CountDownLatch finished) {
        private ManualRefreshRun() {
            this(new CountDownLatch(1));
        }
    }

    private enum Publication {
        PUBLISHED,
        CLOSED
    }

    private static final class EventTimer {

        private static final EventTimer DISABLED = new EventTimer(
                LifecycleEventBuffer.noop(), null, 0);

        private final LifecycleEventBuffer events;
        private final Instant startedAt;
        private final long startedNanos;

        private EventTimer(
                LifecycleEventBuffer events,
                Instant startedAt,
                long startedNanos) {
            this.events = events;
            this.startedAt = startedAt;
            this.startedNanos = startedNanos;
        }

        private static EventTimer start(
                LifecycleEventBuffer events,
                LifecycleRuntime runtime) {
            if (!events.isEnabled()) {
                return DISABLED;
            }
            return new EventTimer(events, runtime.now(), System.nanoTime());
        }

        private void success(LifecycleEvent.Stage stage, long generation, long revision) {
            publish(stage, LifecycleEvent.Outcome.SUCCESS, generation, revision, null);
        }

        private void failure(LifecycleEvent.Stage stage, FailureSnapshot failure) {
            failure(
                    stage,
                    failure.generation(),
                    failure.revision(),
                    failure.failureType());
        }

        private void failure(
                LifecycleEvent.Stage stage,
                long generation,
                long revision,
                String failureType) {
            publish(
                    stage,
                    LifecycleEvent.Outcome.FAILURE,
                    generation,
                    revision,
                    failureType);
        }

        private void publish(
                LifecycleEvent.Stage stage,
                LifecycleEvent.Outcome outcome,
                long generation,
                long revision,
                String failureType) {
            if (startedAt == null) {
                return;
            }
            events.publish(
                    stage,
                    outcome,
                    generation,
                    revision,
                    startedAt,
                    Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)),
                    failureType);
        }
    }

    private record TimeoutBudget(long startedAt, long timeoutNanos) {

        private static TimeoutBudget start(Duration timeout) {
            Duration validated = requireNonNegative(timeout, "timeout");
            return new TimeoutBudget(System.nanoTime(), validated.toNanos());
        }

        private long remainingNanos() {
            long elapsed = System.nanoTime() - startedAt;
            if (elapsed <= 0) {
                return timeoutNanos;
            }
            return elapsed >= timeoutNanos ? 0 : timeoutNanos - elapsed;
        }
    }

    /**
     * Publishes control-plane state as one short, internally synchronized transaction. Resource
     * creation, health checks, operations, and closure never run while this monitor is held.
     */
    private static final class StatusTracker {

        private ManagedResourceStatus.Lifecycle lifecycle =
                ManagedResourceStatus.Lifecycle.INITIALIZING;
        private long activeGeneration;
        private long activeRevision;
        private long desiredRevision;
        private int activeLeases;
        private boolean candidateInProgress;
        private int retiringGenerations;
        private int retirementTransfers;
        private boolean refreshPending;
        private long refreshSuccesses;
        private long refreshFailures;
        private Instant lastSuccessfulRefreshAt;
        private FailureSnapshot lastRefreshFailure;
        private int closeFailures;

        private synchronized ManagedResourceStatus.Lifecycle lifecycle() {
            return lifecycle;
        }

        private synchronized void setLifecycle(ManagedResourceStatus.Lifecycle lifecycle) {
            this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        }

        private synchronized void startClosing() {
            lifecycle = ManagedResourceStatus.Lifecycle.CLOSING;
            refreshPending = false;
        }

        private synchronized void compareAndSetLifecycle(
                ManagedResourceStatus.Lifecycle expected,
                ManagedResourceStatus.Lifecycle update) {
            if (lifecycle == expected) {
                lifecycle = update;
            }
        }

        private synchronized boolean refreshPending() {
            return refreshPending;
        }

        private synchronized void setRefreshPending(boolean refreshPending) {
            if (!refreshPending
                    || lifecycle == ManagedResourceStatus.Lifecycle.INITIALIZING
                    || lifecycle == ManagedResourceStatus.Lifecycle.RUNNING) {
                this.refreshPending = refreshPending;
            }
        }

        private synchronized boolean replacementInProgress() {
            return candidateInProgress
                    || retiringGenerations != 0
                    || retirementTransfers != 0;
        }

        private synchronized void setCandidateInProgress(boolean candidateInProgress) {
            this.candidateInProgress = candidateInProgress;
        }

        private synchronized void observeRevision(long revision) {
            desiredRevision = Math.max(desiredRevision, revision);
        }

        private synchronized void retirementStarted() {
            retiringGenerations++;
        }

        private synchronized void retirementFinished() {
            if (retiringGenerations == 0) {
                throw new IllegalStateException("Managed retirement count became negative");
            }
            retiringGenerations--;
        }

        private synchronized void retirementTransferStarted() {
            retirementTransfers++;
        }

        private synchronized void retirementTransferFinished() {
            if (retirementTransfers == 0) {
                throw new IllegalStateException("Managed retirement transfer count became negative");
            }
            retirementTransfers--;
        }

        private synchronized void recordRefreshSuccess(
                Generation<?> generation,
                Instant completedAt) {
            refreshSuccesses++;
            lastSuccessfulRefreshAt = Objects.requireNonNull(completedAt, "completedAt");
            if (lifecycle != ManagedResourceStatus.Lifecycle.REFRESH_DISABLED) {
                lastRefreshFailure = null;
            }
            if (lifecycle != ManagedResourceStatus.Lifecycle.CLOSING
                    && lifecycle != ManagedResourceStatus.Lifecycle.TERMINATED) {
                activeGeneration = generation.id();
                activeRevision = generation.revision();
                activeLeases = generation.inFlight();
            }
        }

        private synchronized void clearRefreshFailure() {
            if (lifecycle != ManagedResourceStatus.Lifecycle.REFRESH_DISABLED) {
                lastRefreshFailure = null;
            }
        }

        private synchronized void recordRefreshFailure(FailureSnapshot failure) {
            lastRefreshFailure = Objects.requireNonNull(failure, "failure");
            refreshFailures++;
        }

        private synchronized boolean disableRefresh(FailureSnapshot failure) {
            if (lifecycle != ManagedResourceStatus.Lifecycle.RUNNING) {
                return false;
            }
            lifecycle = ManagedResourceStatus.Lifecycle.REFRESH_DISABLED;
            lastRefreshFailure = Objects.requireNonNull(failure, "failure");
            refreshFailures++;
            refreshPending = false;
            return true;
        }

        private synchronized void clearActiveGeneration() {
            activeGeneration = 0;
            activeRevision = 0;
            activeLeases = 0;
        }

        private synchronized void updateActiveLeases(long generation, int leases) {
            if (activeGeneration == generation) {
                activeLeases = leases;
            }
        }

        private synchronized void setCloseFailures(int closeFailures) {
            this.closeFailures = closeFailures;
        }

        private synchronized Optional<FailureSnapshot> lastRefreshFailure() {
            return Optional.ofNullable(lastRefreshFailure);
        }

        private synchronized ManagedResourceStatus snapshot() {
            return new ManagedResourceStatus(
                    lifecycle,
                    activeGeneration,
                    activeRevision,
                    desiredRevision,
                    activeLeases,
                    candidateInProgress || retiringGenerations != 0 || retirementTransfers != 0,
                    refreshPending,
                    refreshSuccesses,
                    refreshFailures,
                    Optional.ofNullable(lastSuccessfulRefreshAt),
                    Optional.ofNullable(lastRefreshFailure),
                    closeFailures);
        }
    }

    private static final class Generation<T> {

        private final long id;
        private final long revision;
        private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
        private final AtomicInteger inFlight = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final java.util.function.Consumer<Generation<T>> closeAction;
        private final java.util.function.Consumer<Generation<T>> leaseCountChanged;
        private final LifecycleRuntime runtime;
        private volatile T resource;
        private volatile boolean published;

        private Generation(
                long id,
                long revision,
                T resource,
                java.util.function.Consumer<Generation<T>> closeAction,
                java.util.function.Consumer<Generation<T>> leaseCountChanged,
                LifecycleRuntime runtime) {
            this.id = id;
            this.revision = revision;
            this.resource = resource;
            this.closeAction = closeAction;
            this.leaseCountChanged = leaseCountChanged;
            this.runtime = runtime;
        }

        private long id() {
            return id;
        }

        private long revision() {
            return revision;
        }

        private int inFlight() {
            return inFlight.get();
        }

        private void markPublished() {
            published = true;
        }

        private void cancelPublication() {
            published = false;
        }

        private boolean wasPublished() {
            return published;
        }

        private boolean tryAcquire() {
            if (state.get() != State.ACTIVE) {
                return false;
            }
            inFlight.incrementAndGet();
            leaseCountChanged.accept(this);
            if (state.get() == State.ACTIVE) {
                return true;
            }
            release();
            return false;
        }

        private <R, E extends Exception> R execute(
                ResourceOperation<? super T, R, E> operation) throws E {
            return operation.execute(resource);
        }

        private void release() {
            int remaining = inFlight.decrementAndGet();
            if (remaining < 0) {
                throw new IllegalStateException("Resource lease count became negative");
            }
            leaseCountChanged.accept(this);
            if (remaining == 0) {
                closeIfDrained();
            }
        }

        private void beginDraining() {
            state.compareAndSet(State.ACTIVE, State.DRAINING);
            closeIfDrained();
        }

        private void closeUnpublished() {
            if (!state.compareAndSet(State.ACTIVE, State.CLOSING)) {
                throw new IllegalStateException("Candidate is not available for close");
            }
            closeAction.accept(this);
        }

        private void closeIfDrained() {
            if (inFlight.get() != 0 || !state.compareAndSet(State.DRAINING, State.CLOSING)) {
                return;
            }
            startCleanupTask(
                    runtime,
                    "managed-resource-close",
                    () -> closeAction.accept(this));
        }

        private T resourceForClose() {
            return Objects.requireNonNull(resource, "resource");
        }

        private void clearResource() {
            resource = null;
        }

        private void finishClosing() {
            state.set(State.CLOSED);
            closed.countDown();
        }

        private boolean awaitClosed(long timeoutNanos) {
            return await(closed, timeoutNanos);
        }

        private enum State {
            ACTIVE,
            DRAINING,
            CLOSING,
            CLOSED
        }
    }
}
