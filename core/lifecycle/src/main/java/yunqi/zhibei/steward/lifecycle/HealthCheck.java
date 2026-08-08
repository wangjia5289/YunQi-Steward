package yunqi.zhibei.steward.lifecycle;

/** Checks one native resource using the strongest side-effect-free probe exposed by its SDK. */
@FunctionalInterface
public interface HealthCheck<T> {

    /**
     * Returns a detail-free health result and must not retain the resource.
     *
     * @param resource native resource to check
     * @return binding-specific health result
     * @throws Exception when the native check cannot complete normally
     */
    Health check(T resource) throws Exception;
}
