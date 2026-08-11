package yunqi.zhibei.steward.control.configuration;

import java.util.Objects;

/**
 * One immutable, secret-free view of a configuration source.
 *
 * <p>The status deliberately contains no configuration, provider identifier, path, throwable,
 * exception message, endpoint, or credential. Failure and recovery counters are source-local and
 * monotonic for the lifetime of one source instance.
 */
public final class ConfigurationSourceStatus {

    private final State state;
    private final long revision;
    private final long failures;
    private final long recoveries;
    private final FailureStage lastFailureStage;

    private ConfigurationSourceStatus(
            State state,
            long revision,
            long failures,
            long recoveries,
            FailureStage lastFailureStage) {
        this.state = Objects.requireNonNull(state, "state");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (state == State.AVAILABLE && revision < 1) {
            throw new IllegalArgumentException("an available source must have a positive revision");
        }
        if (failures < 0) {
            throw new IllegalArgumentException("failures must not be negative");
        }
        if (recoveries < 0 || recoveries > failures) {
            throw new IllegalArgumentException("recoveries must be between zero and failures");
        }
        this.revision = revision;
        this.failures = failures;
        this.recoveries = recoveries;
        this.lastFailureStage = Objects.requireNonNull(lastFailureStage, "lastFailureStage");
        if (failures == 0 && lastFailureStage != FailureStage.NONE) {
            throw new IllegalArgumentException("a source without failures must use stage NONE");
        }
        if (failures != 0 && lastFailureStage == FailureStage.NONE) {
            throw new IllegalArgumentException("a source with failures must name a failure stage");
        }
    }

    /** Creates a validated status snapshot. */
    public static ConfigurationSourceStatus of(
            State state,
            long revision,
            long failures,
            long recoveries,
            FailureStage lastFailureStage) {
        return new ConfigurationSourceStatus(
                state, revision, failures, recoveries, lastFailureStage);
    }

    /** Returns whether the source is available, unavailable, or closed. */
    public State state() {
        return state;
    }

    /** Returns the latest complete revision retained by the source, or zero before one exists. */
    public long revision() {
        return revision;
    }

    /** Returns the number of failed source read, load, watch, or close attempts. */
    public long failures() {
        return failures;
    }

    /** Returns the number of unavailable-to-available transitions. */
    public long recoveries() {
        return recoveries;
    }

    /** Returns the stage of the latest failure, or {@link FailureStage#NONE}. */
    public FailureStage lastFailureStage() {
        return lastFailureStage;
    }

    /** Contains no source-specific or sensitive diagnostic values. */
    @Override
    public String toString() {
        return "ConfigurationSourceStatus[state=" + state
                + ", revision=" + revision
                + ", failures=" + failures
                + ", recoveries=" + recoveries
                + ", lastFailureStage=" + lastFailureStage + ']';
    }

    /** Current source availability. */
    public enum State {
        AVAILABLE,
        UNAVAILABLE,
        CLOSED
    }

    /** Bounded, provider-neutral stage of the latest source failure. */
    public enum FailureStage {
        NONE,
        READ,
        LOAD,
        WATCH,
        CLOSE
    }
}
