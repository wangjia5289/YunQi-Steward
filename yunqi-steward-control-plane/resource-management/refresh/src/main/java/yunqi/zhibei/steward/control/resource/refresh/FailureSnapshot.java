package yunqi.zhibei.steward.control.resource.refresh;

import java.time.Instant;
import java.util.Objects;

/** A redacted failure description which never retains a Throwable or its message. */
public final class FailureSnapshot {

    private final Stage stage;
    private final String failureType;
    private final Instant occurredAt;
    private final long generation;
    private final long revision;

    FailureSnapshot(
            Stage stage,
            String failureType,
            Instant occurredAt,
            long generation,
            long revision) {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        this.stage = Objects.requireNonNull(stage, "stage");
        this.failureType = Objects.requireNonNull(failureType, "failureType");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.generation = generation;
        this.revision = revision;
    }

    static FailureSnapshot from(Stage stage, Throwable failure, Instant occurredAt) {
        return from(stage, failure, 0, 0, occurredAt);
    }

    static FailureSnapshot from(
            Stage stage,
            Throwable failure,
            long generation,
            long revision,
            Instant occurredAt) {
        Objects.requireNonNull(failure, "failure");
        return new FailureSnapshot(
                stage, failure.getClass().getName(), occurredAt, generation, revision);
    }

    static FailureSnapshot unhealthy(long generation, long revision, Instant occurredAt) {
        return new FailureSnapshot(
                Stage.CANDIDATE_HEALTH_CHECK,
                "unhealthy",
                occurredAt,
                generation,
                revision);
    }

    /** Returns the lifecycle stage which failed. */
    public Stage stage() {
        return stage;
    }

    /** Returns the exception class name or the synthetic value {@code unhealthy}. */
    public String failureType() {
        return failureType;
    }

    /** Returns when the failure was captured. */
    public Instant occurredAt() {
        return occurredAt;
    }

    /** Returns the related resource generation, or {@code 0} when no generation was assigned. */
    public long generation() {
        return generation;
    }

    /** Returns the related configuration revision, or {@code 0} when none can be identified. */
    public long revision() {
        return revision;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof FailureSnapshot snapshot
                && stage == snapshot.stage
                && failureType.equals(snapshot.failureType)
                && occurredAt.equals(snapshot.occurredAt)
                && generation == snapshot.generation
                && revision == snapshot.revision;
    }

    @Override
    public int hashCode() {
        return Objects.hash(stage, failureType, occurredAt, generation, revision);
    }

    @Override
    public String toString() {
        return "FailureSnapshot[stage=" + stage
                + ", failureType=" + failureType
                + ", occurredAt=" + occurredAt
                + ", generation=" + generation
                + ", revision=" + revision + ']';
    }

    /** Lifecycle stage associated with a redacted failure. */
    public enum Stage {
        /** Reading or subscribing to the configuration source failed. */
        CONFIGURATION_SOURCE,
        /** Creating a native candidate failed. */
        RESOURCE_CREATION,
        /** Checking a candidate before publication failed or reported unhealthy. */
        CANDIDATE_HEALTH_CHECK,
        /** Refresh coordination stopped because of an otherwise unclassified fatal engine error. */
        REFRESH_ENGINE,
        /** Closing an unpublished candidate failed. */
        CANDIDATE_CLOSE,
        /** Closing a formerly active resource failed. */
        RESOURCE_CLOSE,
        /** Closing the configuration subscription failed. */
        SUBSCRIPTION_CLOSE
    }
}
