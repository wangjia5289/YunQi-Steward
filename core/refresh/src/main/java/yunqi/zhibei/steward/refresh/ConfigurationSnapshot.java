package yunqi.zhibei.steward.refresh;

import java.util.Objects;

/**
 * One immutable, complete typed configuration together with its source-local revision.
 *
 * <p>Revisions start at {@code 1} and increase strictly within one
 * {@link ConfigurationSource} instance. The revision is the identity of the desired state: the
 * same revision is a duplicate observation, while a lower revision is stale. Provider-specific
 * versions remain an adapter concern and are deliberately not part of this value.
 *
 * @param <C> immutable configuration type
 */
public final class ConfigurationSnapshot<C> {

    private final long revision;
    private final C configuration;

    private ConfigurationSnapshot(long revision, C configuration) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be at least 1");
        }
        this.revision = revision;
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /** Creates a complete snapshot with a source-local revision. */
    public static <C> ConfigurationSnapshot<C> of(long revision, C configuration) {
        return new ConfigurationSnapshot<>(revision, configuration);
    }

    /** Returns the source-local semantic revision. */
    public long revision() {
        return revision;
    }

    /** Returns the immutable complete configuration. */
    public C configuration() {
        return configuration;
    }

    /** Deliberately omits configuration data because it may contain credentials. */
    @Override
    public String toString() {
        return "ConfigurationSnapshot[revision=" + revision + ']';
    }
}
