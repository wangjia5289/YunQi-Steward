package yunqi.zhibei.steward.refresh;

/** Operation executed while one managed resource generation is leased. */
@FunctionalInterface
public interface ResourceOperation<T, R, E extends Exception> {

    /**
     * Runs while the owning generation is leased.
     *
     * <p>The result must not retain the resource or another object whose lifetime depends on it.
     *
     * @param resource leased native resource
     * @return operation result independent of the resource lifetime
     * @throws E operation-specific checked failure
     */
    R execute(T resource) throws E;
}
