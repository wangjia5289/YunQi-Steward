package yunqi.zhibei.steward.lifecycle;

/** Creates one owned native resource from a complete immutable configuration snapshot. */
@FunctionalInterface
public interface ResourceFactory<C, T> {

    /**
     * Creates a resource which the paired closer will own after this method returns.
     *
     * @param configuration complete desired configuration
     * @return newly created native resource
     * @throws Exception when resource creation fails
     */
    T create(C configuration) throws Exception;
}
