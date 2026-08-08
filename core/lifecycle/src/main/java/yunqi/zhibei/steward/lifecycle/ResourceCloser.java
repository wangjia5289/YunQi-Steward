package yunqi.zhibei.steward.lifecycle;

/** Closes native resources created by the paired factory. */
@FunctionalInterface
public interface ResourceCloser<T> {

    /**
     * Closes one resource generation.
     *
     * <p>Managed resources may close different generations concurrently. Implementations should
     * use finite vendor shutdown timeouts because neither owner can safely force-close an SDK
     * object. {@link BoundResource#close()} is synchronous and otherwise unbounded; managed
     * retirement records slow closure and bounds owner waiting without force-closing the resource.
     *
     * @param resource resource to close
     * @throws Exception when native shutdown fails
     */
    void close(T resource) throws Exception;
}
