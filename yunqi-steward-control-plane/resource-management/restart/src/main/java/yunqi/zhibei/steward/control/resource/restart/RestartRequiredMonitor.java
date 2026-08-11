package yunqi.zhibei.steward.control.resource.restart;

import yunqi.zhibei.steward.control.configuration.ConfigurationSnapshot;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import yunqi.zhibei.steward.telemetry.LifecycleEvent;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Reports when a startup-only resource is behind its complete desired configuration.
 *
 * <p>This monitor does not restart the JVM, invoke a deployment platform, retain configuration
 * objects, or make a {@code StartupBinding} refreshable. The applied revision must identify the
 * snapshot used to start the resource and must belong to the same {@link ConfigurationSource}
 * instance. A newer complete revision makes the status {@code RESTART_REQUIRED} until this process
 * is replaced.
 *
 * @param <C> immutable configuration type
 */
public final class RestartRequiredMonitor<C> implements AutoCloseable {

    private final ConfigurationSource<C> source;
    private final LifecycleEventBuffer lifecycleEvents;
    private final Object lifecycle = new Object();
    private RestartRequiredStatus current;
    private ConfigurationSource.Subscription subscription;
    private boolean closed;

    private RestartRequiredMonitor(
            ConfigurationSource<C> source,
            long appliedRevision,
            LifecycleEventBuffer lifecycleEvents) {
        this.source = source;
        this.lifecycleEvents = lifecycleEvents;
        current = new RestartRequiredStatus(
                RestartRequiredStatus.State.CURRENT,
                appliedRevision,
                appliedRevision,
                Optional.empty());
    }

    /**
     * Starts monitoring the source for a revision newer than the resource's applied revision.
     *
     * <p>The listener is installed before the latest snapshot is inspected, so a source change
     * between resource startup and monitor creation cannot be lost. A runtime snapshot failure is
     * retained as redacted status while monitoring continues; a subscription failure prevents
     * monitor creation.
     *
     * @param source source used to obtain the configuration applied to the resource
     * @param appliedRevision revision of the snapshot used to start the resource
     * @param <C> immutable configuration type
     * @return active restart-requirement monitor
     */
    public static <C> RestartRequiredMonitor<C> watch(
            ConfigurationSource<C> source,
            long appliedRevision) {
        return watch(source, appliedRevision, LifecycleEventBuffer.noop());
    }

    /** Starts monitoring and publishes secret-free lifecycle facts to the supplied buffer. */
    public static <C> RestartRequiredMonitor<C> watch(
            ConfigurationSource<C> source,
            long appliedRevision,
            LifecycleEventBuffer lifecycleEvents) {
        ConfigurationSource<C> checkedSource = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(lifecycleEvents, "lifecycleEvents");
        if (appliedRevision < 1) {
            throw new IllegalArgumentException("appliedRevision must be at least 1");
        }

        EventTimer timer = EventTimer.start(lifecycleEvents);
        RestartRequiredMonitor<C> monitor = new RestartRequiredMonitor<>(
                checkedSource, appliedRevision, lifecycleEvents);
        ConfigurationSource.Subscription installed;
        try {
            installed = Objects.requireNonNull(
                    checkedSource.subscribe(monitor::observe),
                    "configuration source returned a null subscription");
        } catch (RuntimeException | Error failure) {
            timer.failure(LifecycleEvent.Stage.START, 0, appliedRevision, failure);
            throw failure;
        }
        monitor.install(installed);
        timer.success(LifecycleEvent.Stage.START, 0, appliedRevision);
        try {
            monitor.observe();
            return monitor;
        } catch (Error failure) {
            try {
                monitor.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** Returns one immutable, internally consistent control-plane status snapshot. */
    public RestartRequiredStatus status() {
        synchronized (lifecycle) {
            return current;
        }
    }

    /** Stops source observation. Closing more than once has no effect. */
    @Override
    public void close() {
        ConfigurationSource.Subscription installed;
        long revision;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            current = new RestartRequiredStatus(
                    RestartRequiredStatus.State.CLOSED,
                    current.appliedRevision(),
                    current.desiredRevision(),
                    current.lastFailure());
            installed = subscription;
            revision = current.desiredRevision();
        }

        EventTimer timer = EventTimer.start(lifecycleEvents);
        try {
            installed.close();
            timer.success(LifecycleEvent.Stage.CLOSE, 0, revision);
        } catch (RuntimeException | Error failure) {
            recordCloseFailure(failure);
            timer.failure(LifecycleEvent.Stage.CLOSE, 0, revision, failure);
            throw failure;
        }
    }

    private void install(ConfigurationSource.Subscription installed) {
        synchronized (lifecycle) {
            subscription = installed;
        }
    }

    private void observe() {
        EventTimer timer = EventTimer.start(lifecycleEvents);
        ConfigurationSnapshot<C> snapshot;
        try {
            snapshot = Objects.requireNonNull(
                    source.snapshot(),
                    "configuration source returned a null snapshot");
        } catch (RuntimeException failure) {
            recordSourceFailure(failure);
            timer.failure(LifecycleEvent.Stage.OBSERVE, 0, 0, failure);
            return;
        } catch (Error failure) {
            timer.failure(LifecycleEvent.Stage.OBSERVE, 0, 0, failure);
            throw failure;
        }

        boolean requirementPublished = false;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            long desiredRevision = Math.max(current.desiredRevision(), snapshot.revision());
            RestartRequiredStatus.State state = desiredRevision > current.appliedRevision()
                    ? RestartRequiredStatus.State.RESTART_REQUIRED
                    : RestartRequiredStatus.State.CURRENT;
            requirementPublished = current.state() != RestartRequiredStatus.State.RESTART_REQUIRED
                    && state == RestartRequiredStatus.State.RESTART_REQUIRED;
            current = new RestartRequiredStatus(
                    state,
                    current.appliedRevision(),
                    desiredRevision,
                    Optional.empty());
        }
        if (requirementPublished) {
            timer.success(LifecycleEvent.Stage.RESTART_REQUIRED, 0, snapshot.revision());
        }
    }

    private void recordSourceFailure(RuntimeException failure) {
        RestartRequiredFailure redacted = RestartRequiredFailure.from(
                RestartRequiredFailure.Stage.CONFIGURATION_SOURCE,
                failure);
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            current = new RestartRequiredStatus(
                    current.state(),
                    current.appliedRevision(),
                    current.desiredRevision(),
                    Optional.of(redacted));
        }
    }

    private void recordCloseFailure(Throwable failure) {
        RestartRequiredFailure redacted = RestartRequiredFailure.from(
                RestartRequiredFailure.Stage.SUBSCRIPTION_CLOSE,
                failure);
        synchronized (lifecycle) {
            current = new RestartRequiredStatus(
                    RestartRequiredStatus.State.CLOSED,
                    current.appliedRevision(),
                    current.desiredRevision(),
                    Optional.of(redacted));
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
