package yunqi.zhibei.steward.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One immutable, secret-free fact about a low-frequency lifecycle operation.
 *
 * <p>The value never contains configuration, a native resource, a throwable, or an exception
 * message. A generation or revision of {@code 0} means that the identity does not apply or could
 * not be attributed reliably.
 */
public final class LifecycleEvent {

    /** Stable lifecycle phases shared by startup, refresh, and restart-required owners. */
    public enum Stage {
        /** Starting an owner and its initial native resource or monitor. */
        START,
        /** Reading a complete desired-configuration snapshot. */
        OBSERVE,
        /** Creating, checking, and publishing a replacement generation. */
        REFRESH,
        /** Closing a candidate which was not published. */
        ROLLBACK,
        /** Observing the first revision which requires process replacement. */
        RESTART_REQUIRED,
        /** Closing a native generation, subscription owner, or monitor. */
        CLOSE
    }

    /** Whether the lifecycle phase completed normally or failed. */
    public enum Outcome {
        /** The phase completed normally. */
        SUCCESS,
        /** The phase failed or produced an unhealthy candidate. */
        FAILURE
    }

    private final long sequence;
    private final Stage stage;
    private final Outcome outcome;
    private final long generation;
    private final long revision;
    private final Instant startedAt;
    private final Duration duration;
    private final Optional<String> failureType;

    LifecycleEvent(
            long sequence,
            Stage stage,
            Outcome outcome,
            long generation,
            long revision,
            Instant startedAt,
            Duration duration,
            String failureType) {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        this.sequence = sequence;
        this.stage = Objects.requireNonNull(stage, "stage");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.generation = generation;
        this.revision = revision;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.duration = requireNonNegative(duration);
        if (outcome == Outcome.FAILURE && failureType == null) {
            throw new IllegalArgumentException("failureType is required for a failed event");
        }
        if (outcome == Outcome.SUCCESS && failureType != null) {
            throw new IllegalArgumentException("failureType is only valid for a failed event");
        }
        this.failureType = Optional.ofNullable(failureType);
    }

    public long sequence() { return sequence; }
    public Stage stage() { return stage; }
    public Outcome outcome() { return outcome; }
    public long generation() { return generation; }
    public long revision() { return revision; }
    public Instant startedAt() { return startedAt; }
    public Duration duration() { return duration; }
    /** Returns only an exception class name or a documented synthetic failure value. */
    public Optional<String> failureType() { return failureType; }

    @Override
    public String toString() {
        return "LifecycleEvent[sequence=" + sequence
                + ", stage=" + stage
                + ", outcome=" + outcome
                + ", generation=" + generation
                + ", revision=" + revision
                + ", startedAt=" + startedAt
                + ", duration=" + duration
                + ", failureType=" + failureType + ']';
    }

    private static Duration requireNonNegative(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("duration is too large", failure);
        }
        return duration;
    }
}
