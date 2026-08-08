package yunqi.zhibei.steward.refresh;

/**
 * Supplies the desired configuration snapshot and signals subscribers when it may have changed.
 *
 * <p>{@link #snapshot()} is the source of truth. A signal carries no snapshot because it may be
 * stale by the time a listener runs. Implementations may coalesce signals or invoke a listener
 * concurrently or reentrantly. They must install the listener before {@link #subscribe(Runnable)}
 * returns and make a new snapshot visible through {@code snapshot()} before signaling.
 *
 * <p>Configurations must be immutable and complete. Revisions start at {@code 1} and increase
 * strictly for new desired states during the lifetime of one source instance. Repeating a revision
 * is a duplicate signal and exposing a lower revision is an out-of-order observation; consumers
 * ignore both. A provider adapter must resolve its native ordering and only then assign a local
 * revision. Calling {@code snapshot()} may throw a runtime exception when no complete snapshot is
 * currently available. Initial failure prevents managed-resource startup; later failure leaves the
 * active generation unchanged.
 *
 * @param <C> configuration type
 */
public interface ConfigurationSource<C> {

    /** Returns the latest complete desired configuration snapshot. */
    ConfigurationSnapshot<C> snapshot();

    /**
     * Registers a change signal listener.
     *
     * <p>The listener is installed before this method returns. Closing the returned subscription is
     * idempotent. After {@code close()} returns, no new invocation of this listener may begin; an
     * invocation which already began may finish.
     *
     * @param listener callback which should re-read {@link #snapshot()}
     * @return subscription used to stop future signals
     */
    Subscription subscribe(Runnable listener);

    /** Owns one configuration change subscription. */
    @FunctionalInterface
    interface Subscription extends AutoCloseable {

        /** Prevents future listener invocations; an already-running invocation may finish. */
        @Override
        void close();
    }
}
