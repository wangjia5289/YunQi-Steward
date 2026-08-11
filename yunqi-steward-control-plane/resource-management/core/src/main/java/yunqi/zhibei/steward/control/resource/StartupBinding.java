package yunqi.zhibei.steward.control.resource;

/**
 * Creates one native SDK resource during application startup.
 *
 * <p>This contract does not promise that two instances can overlap. It is suitable for SDKs that
 * bind process-global state, ports, worker identities, or producer groups.
 *
 * @param <C> immutable startup configuration type
 * @param <T> native SDK resource type
 */
public interface StartupBinding<C, T>
        extends ResourceFactory<C, T>, HealthCheck<T>, ResourceCloser<T> {
}
