package yunqi.zhibei.steward.refresh;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Secret-free control-plane status for one managed resource.
 *
 * <p>All fields in one instance come from the same published lifecycle transition. The instance
 * is immutable and may become stale immediately after it is obtained.
 */
public final class ManagedResourceStatus {

    /** Lifecycle of the refresh owner, independent of native resource health. */
    public enum Lifecycle {
        /** Initial configuration is being reconciled synchronously. */
        INITIALIZING,
        /** Refresh and resource operations are available. */
        RUNNING,
        /** A fatal refresh failure stopped replacement; the last active resource may still serve. */
        REFRESH_DISABLED,
        /** Shutdown has started. */
        CLOSING,
        /** Subscription, worker, and resource shutdown have completed. */
        TERMINATED
    }

    private final Lifecycle lifecycle;
    private final long activeGeneration;
    private final long activeRevision;
    private final long desiredRevision;
    private final int activeLeases;
    private final boolean replacementInProgress;
    private final boolean refreshPending;
    private final long refreshSuccesses;
    private final long refreshFailures;
    private final Optional<Instant> lastSuccessfulRefreshAt;
    private final Optional<FailureSnapshot> lastRefreshFailure;
    private final int closeFailures;

    ManagedResourceStatus(
            Lifecycle lifecycle,
            long activeGeneration,
            long activeRevision,
            long desiredRevision,
            int activeLeases,
            boolean replacementInProgress,
            boolean refreshPending,
            long refreshSuccesses,
            long refreshFailures,
            Optional<Instant> lastSuccessfulRefreshAt,
            Optional<FailureSnapshot> lastRefreshFailure,
            int closeFailures) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.activeGeneration = activeGeneration;
        this.activeRevision = activeRevision;
        this.desiredRevision = desiredRevision;
        this.activeLeases = activeLeases;
        this.replacementInProgress = replacementInProgress;
        this.refreshPending = refreshPending;
        this.refreshSuccesses = refreshSuccesses;
        this.refreshFailures = refreshFailures;
        this.lastSuccessfulRefreshAt = Objects.requireNonNull(
                lastSuccessfulRefreshAt, "lastSuccessfulRefreshAt");
        this.lastRefreshFailure = Objects.requireNonNull(
                lastRefreshFailure, "lastRefreshFailure");
        this.closeFailures = closeFailures;
    }

    public Lifecycle lifecycle() { return lifecycle; }
    public long activeGeneration() { return activeGeneration; }
    /** Returns the active source revision, or {@code 0} before a generation is published. */
    public long activeRevision() { return activeRevision; }
    /** Returns the highest source revision observed by reconciliation, or {@code 0} before a read. */
    public long desiredRevision() { return desiredRevision; }
    public int activeLeases() { return activeLeases; }
    /** Returns whether candidate work or generation retirement is active. */
    public boolean replacementInProgress() { return replacementInProgress; }
    public boolean refreshPending() { return refreshPending; }
    public long refreshSuccesses() { return refreshSuccesses; }
    public long refreshFailures() { return refreshFailures; }
    public Optional<Instant> lastSuccessfulRefreshAt() { return lastSuccessfulRefreshAt; }
    public Optional<FailureSnapshot> lastRefreshFailure() { return lastRefreshFailure; }
    public int closeFailures() { return closeFailures; }

    @Override
    public String toString() {
        return "ManagedResourceStatus[lifecycle=" + lifecycle
                + ", activeGeneration=" + activeGeneration
                + ", activeRevision=" + activeRevision
                + ", desiredRevision=" + desiredRevision
                + ", activeLeases=" + activeLeases
                + ", replacementInProgress=" + replacementInProgress
                + ", refreshPending=" + refreshPending
                + ", refreshSuccesses=" + refreshSuccesses
                + ", refreshFailures=" + refreshFailures
                + ", lastSuccessfulRefreshAt=" + lastSuccessfulRefreshAt
                + ", lastRefreshFailure=" + lastRefreshFailure
                + ", closeFailures=" + closeFailures + ']';
    }
}
