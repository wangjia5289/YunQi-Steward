package yunqi.zhibei.steward.control.resource;

/**
 * Binds one configuration type to one refresh-safe native library resource type.
 *
 * <p>The application selects the binding when it builds the managed resource. The binding never
 * changes afterward; configuration refreshes can only create another instance of the same
 * compile-time resource type. Implementations must support the old and candidate resources being
 * alive at the same time while health checking and draining occur. Use {@link StartupBinding} when
 * the underlying SDK cannot safely overlap instances.
 *
 * @param <C> immutable configuration type
 * @param <T> native SDK resource type
 */
public interface ResourceBinding<C, T> extends StartupBinding<C, T> {
}
