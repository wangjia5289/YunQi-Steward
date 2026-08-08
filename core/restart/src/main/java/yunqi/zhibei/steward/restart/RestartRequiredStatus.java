package yunqi.zhibei.steward.restart;

import java.util.Objects;
import java.util.Optional;

/** Secret-free, internally consistent restart-requirement status. */
public final class RestartRequiredStatus {

    /** Current relationship between the running process and desired configuration. */
    public enum State {
        /** No newer complete configuration has been observed. */
        CURRENT,
        /** A newer complete configuration requires a restart or rolling deployment. */
        RESTART_REQUIRED,
        /** Monitoring has stopped and this snapshot is terminal. */
        CLOSED
    }

    private final State state;
    private final long appliedRevision;
    private final long desiredRevision;
    private final Optional<RestartRequiredFailure> lastFailure;

    RestartRequiredStatus(
            State state,
            long appliedRevision,
            long desiredRevision,
            Optional<RestartRequiredFailure> lastFailure) {
        if (appliedRevision < 1) {
            throw new IllegalArgumentException("appliedRevision must be at least 1");
        }
        if (desiredRevision < appliedRevision) {
            throw new IllegalArgumentException("desiredRevision must not precede appliedRevision");
        }
        this.state = Objects.requireNonNull(state, "state");
        this.appliedRevision = appliedRevision;
        this.desiredRevision = desiredRevision;
        this.lastFailure = Objects.requireNonNull(lastFailure, "lastFailure");
    }

    /** Returns the current monitor state. */
    public State state() {
        return state;
    }

    /** Returns the configuration revision used to start this process resource. */
    public long appliedRevision() {
        return appliedRevision;
    }

    /** Returns the highest complete source revision observed by this monitor. */
    public long desiredRevision() {
        return desiredRevision;
    }

    /** Returns whether the running monitor has observed a newer complete configuration. */
    public boolean restartRequired() {
        return state == State.RESTART_REQUIRED;
    }

    /** Returns the last redacted observation or subscription-close failure, if any. */
    public Optional<RestartRequiredFailure> lastFailure() {
        return lastFailure;
    }

    @Override
    public String toString() {
        return "RestartRequiredStatus[state=" + state
                + ", appliedRevision=" + appliedRevision
                + ", desiredRevision=" + desiredRevision
                + ", lastFailure=" + lastFailure + ']';
    }
}
