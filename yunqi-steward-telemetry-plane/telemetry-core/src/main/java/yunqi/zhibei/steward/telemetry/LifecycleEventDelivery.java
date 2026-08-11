package yunqi.zhibei.steward.telemetry;

/**
 * Product-neutral delivery accounting exposed by an asynchronous lifecycle event adapter.
 *
 * <p>Implementations expose only monotonic counts and terminal state. They must not expose an
 * exporter, logger, throwable, message, configuration, endpoint, or native client.
 */
public interface LifecycleEventDelivery {

    /** Returns events removed from the adapter input. */
    long drainedEvents();

    /** Returns drained events delivered without an adapter-side exception. */
    long successfulEvents();

    /** Returns drained events rejected by adapter-side conversion or delivery. */
    long failedEvents();

    /** Returns events dropped before this adapter could drain its input. */
    long sourceDroppedEvents();

    /** Returns whether close and final draining have completed. */
    boolean isClosed();
}
