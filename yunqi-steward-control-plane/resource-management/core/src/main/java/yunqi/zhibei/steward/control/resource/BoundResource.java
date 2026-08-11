package yunqi.zhibei.steward.control.resource;

import yunqi.zhibei.steward.telemetry.LifecycleEvent;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Owns one native SDK resource created by a binding at application startup.
 *
 * <p>This type never replaces the resource. Applications may therefore use the native SDK object
 * directly. Switching libraries or incompatible versions requires rebuilding and restarting the
 * application.
 *
 * @param <T> native SDK resource type
 */
public final class BoundResource<T> implements AutoCloseable {

    /** Lifecycle state of the one owned resource. */
    public enum State {
        /** The resource is available for use and health checking. */
        OPEN,
        /** The single native close attempt is in progress. */
        CLOSING,
        /** Native close completed successfully. */
        CLOSED,
        /** Native close failed; the owner will not retry it. */
        CLOSE_FAILED
    }

    private final ResourceCloser<? super T> closer;
    private final HealthCheck<? super T> healthCheck;
    private final LifecycleEventBuffer lifecycleEvents;
    private volatile State state = State.OPEN;
    private T resource;

    private BoundResource(
            T resource,
            HealthCheck<? super T> healthCheck,
            ResourceCloser<? super T> closer,
            LifecycleEventBuffer lifecycleEvents) {
        this.resource = resource;
        this.healthCheck = healthCheck;
        this.closer = closer;
        this.lifecycleEvents = lifecycleEvents;
    }

    /**
     * Creates and checks one resource, closing the candidate if startup does not complete.
     *
     * @param configuration complete startup configuration
     * @param binding native resource lifecycle
     * @param <C> configuration type
     * @param <T> native resource type
     * @return owner of the healthy native resource
     * @throws Exception when creation or health checking fails
     */
    public static <C, T> BoundResource<T> start(
            C configuration,
            StartupBinding<C, T> binding) throws Exception {
        return start(configuration, binding, LifecycleEventBuffer.noop());
    }

    /**
     * Creates and checks one resource while publishing low-frequency lifecycle facts.
     *
     * <p>The supplied buffer is not owned or closed by the resource and may be shared with an
     * adapter. Publication never contains the configuration, native resource, throwable, or
     * exception message.
     */
    public static <C, T> BoundResource<T> start(
            C configuration,
            StartupBinding<C, T> binding,
            LifecycleEventBuffer lifecycleEvents) throws Exception {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(lifecycleEvents, "lifecycleEvents");
        EventTimer timer = EventTimer.start(lifecycleEvents);

        T candidate;
        try {
            candidate = Objects.requireNonNull(
                    binding.create(configuration),
                    "binding.create() returned null");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            timer.failure(LifecycleEvent.Stage.START, 0, 0, failure);
            throw failure;
        } catch (Exception | Error failure) {
            timer.failure(LifecycleEvent.Stage.START, 0, 0, failure);
            throw failure;
        }
        try {
            Health health = Objects.requireNonNull(
                    binding.check(candidate),
                    "binding.check() returned null");
            if (!health.isHealthy()) {
                throw new IllegalStateException("The bound resource is unhealthy");
            }
            BoundResource<T> bound = new BoundResource<>(
                    candidate, binding, binding, lifecycleEvents);
            timer.success(LifecycleEvent.Stage.START, 0, 0);
            return bound;
        } catch (Exception | Error failure) {
            closeFailedCandidate(candidate, binding, failure);
            restoreInterrupt(failure);
            timer.failure(LifecycleEvent.Stage.START, 0, 0, failure);
            throw failure;
        }
    }

    /** Returns the owned native resource while this owner is open. */
    public synchronized T resource() {
        if (state != State.OPEN) {
            throw new IllegalStateException("The bound resource is not open: " + state);
        }
        return resource;
    }

    /** Returns the current lifecycle state. */
    public State state() {
        return state;
    }

    /** Returns whether the resource can no longer be used through this owner. */
    public boolean isClosed() {
        return state != State.OPEN;
    }

    /**
     * Runs the binding-specific health probe against the owned resource.
     *
     * <p>This is a demand-time probe. Its result or exception is returned directly and is not
     * retained as lifecycle state. An interrupted probe restores the calling thread's interrupt
     * status before propagating the exception. Health checking and closing are mutually exclusive:
     * an in-progress probe delays {@link #close()}, while a probe waiting behind an in-progress
     * close fails after closure instead of invoking the native check. The binding must configure a
     * finite vendor probe timeout because this owner does not impose one.
     *
     * @return detail-free health result
     * @throws Exception when the native check cannot complete normally
     */
    public synchronized Health health() throws Exception {
        try {
            return Objects.requireNonNull(
                    healthCheck.check(resource()),
                    "binding.check() returned null");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    /**
     * Closes the native resource once, waiting for any in-progress health probe to return.
     *
     * <p>The call is synchronous and has no owner-level timeout. The binding must configure a
     * finite vendor shutdown timeout when the SDK supports one.
     */
    @Override
    public synchronized void close() {
        if (state != State.OPEN) {
            return;
        }

        T current = resource;
        resource = null;
        state = State.CLOSING;
        EventTimer timer = EventTimer.start(lifecycleEvents);
        try {
            closer.close(current);
            state = State.CLOSED;
            timer.success(LifecycleEvent.Stage.CLOSE, 0, 0);
        } catch (RuntimeException | Error failure) {
            state = State.CLOSE_FAILED;
            timer.failure(LifecycleEvent.Stage.CLOSE, 0, 0, failure);
            throw failure;
        } catch (InterruptedException failure) {
            state = State.CLOSE_FAILED;
            Thread.currentThread().interrupt();
            timer.failure(LifecycleEvent.Stage.CLOSE, 0, 0, failure);
            throw new IllegalStateException("Interrupted while closing the bound resource", failure);
        } catch (Exception failure) {
            state = State.CLOSE_FAILED;
            timer.failure(LifecycleEvent.Stage.CLOSE, 0, 0, failure);
            throw new IllegalStateException("Failed to close the bound resource", failure);
        }
    }

    private static <T> void closeFailedCandidate(
            T candidate,
            ResourceCloser<? super T> closer,
            Throwable failure) {
        try {
            closer.close(candidate);
        } catch (Exception | Error closeFailure) {
            restoreInterrupt(closeFailure);
            failure.addSuppressed(closeFailure);
        }
    }

    private static void restoreInterrupt(Throwable failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
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

        private static EventTimer start(LifecycleEventBuffer events) {
            if (!events.isEnabled()) {
                return DISABLED;
            }
            return new EventTimer(events, Instant.now(), System.nanoTime());
        }

        private void success(LifecycleEvent.Stage stage, long generation, long revision) {
            publish(stage, LifecycleEvent.Outcome.SUCCESS, generation, revision, null);
        }

        private void failure(
                LifecycleEvent.Stage stage,
                long generation,
                long revision,
                Throwable failure) {
            publish(
                    stage, LifecycleEvent.Outcome.FAILURE, generation, revision,
                    failure.getClass().getName());
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
}
