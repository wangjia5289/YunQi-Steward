package yunqi.zhibei.steward.restart;

import java.time.Instant;
import java.util.Objects;

/** A redacted monitor failure which never retains a throwable or its message. */
public final class RestartRequiredFailure {

    /** Monitor stage associated with a redacted failure. */
    public enum Stage {
        /** Reading the latest complete configuration snapshot failed. */
        CONFIGURATION_SOURCE,
        /** Closing the configuration subscription failed. */
        SUBSCRIPTION_CLOSE
    }

    private final Stage stage;
    private final String failureType;
    private final Instant occurredAt;

    RestartRequiredFailure(Stage stage, String failureType, Instant occurredAt) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.failureType = Objects.requireNonNull(failureType, "failureType");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    static RestartRequiredFailure from(Stage stage, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return new RestartRequiredFailure(
                stage,
                failure.getClass().getName(),
                Instant.now());
    }

    /** Returns the monitor stage which failed. */
    public Stage stage() {
        return stage;
    }

    /** Returns only the exception class name, never its message. */
    public String failureType() {
        return failureType;
    }

    /** Returns when the failure was captured. */
    public Instant occurredAt() {
        return occurredAt;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof RestartRequiredFailure failure
                && stage == failure.stage
                && failureType.equals(failure.failureType)
                && occurredAt.equals(failure.occurredAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stage, failureType, occurredAt);
    }

    @Override
    public String toString() {
        return "RestartRequiredFailure[stage=" + stage
                + ", failureType=" + failureType
                + ", occurredAt=" + occurredAt + ']';
    }
}
