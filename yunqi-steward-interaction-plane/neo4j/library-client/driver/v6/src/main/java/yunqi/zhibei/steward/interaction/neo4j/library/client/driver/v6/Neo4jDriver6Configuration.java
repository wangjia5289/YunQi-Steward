package yunqi.zhibei.steward.interaction.neo4j.library.client.driver.v6;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class Neo4jDriver6Configuration {

    private final URI uri;

    private final Optional<String> username;

    private final Optional<String> password;

    private final Duration connectTimeout;

    private final Duration acquireTimeout;

    private final Pool pool;

    Neo4jDriver6Configuration(URI uri, Optional<String> username, Optional<String> password, Duration connectTimeout, Duration acquireTimeout, Pool pool) {
        uri = requireUri(uri);
        username = requireOptionalText(username, "username");
        password = requireOptionalText(password, "password");
        if (username.isPresent() != password.isPresent()) {
            throw new IllegalArgumentException("username and password must either both be present or both be absent");
        }
        connectTimeout = requirePositiveMillis(connectTimeout, "connectTimeout", true);
        acquireTimeout = requirePositiveMillis(acquireTimeout, "acquireTimeout", false);
        pool = Objects.requireNonNull(pool, "pool");
        this.uri = uri;
        this.username = username;
        this.password = password;
        this.connectTimeout = connectTimeout;
        this.acquireTimeout = acquireTimeout;
        this.pool = pool;
    }

    public URI uri() {
        return uri;
    }

    public Optional<String> username() {
        return username;
    }

    public Optional<String> password() {
        return password;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration acquireTimeout() {
        return acquireTimeout;
    }

    public Pool pool() {
        return pool;
    }

    private static final Set<String> SUPPORTED_SCHEMES = Set.of("bolt", "bolt+s", "bolt+ssc", "neo4j", "neo4j+s", "neo4j+ssc");

    static Neo4jDriver6Configuration defaults() {
        return new Neo4jDriver6Configuration(URI.create("bolt://127.0.0.1:7687"), Optional.empty(), Optional.empty(), Duration.ofSeconds(5), Duration.ofSeconds(30), Pool.defaults());
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private URI uri;

        private Optional<String> username;

        private Optional<String> password;

        private Duration connectTimeout;

        private Duration acquireTimeout;

        private Pool pool;

        private Builder(Neo4jDriver6Configuration source) {
            uri = source.uri();
            username = source.username();
            password = source.password();
            connectTimeout = source.connectTimeout();
            acquireTimeout = source.acquireTimeout();
            pool = source.pool();
        }

        public Builder uri(String value) {
            uri = parseUri(value, "uri");
            return this;
        }

        public Builder uri(URI value) {
            uri = value;
            return this;
        }

        public Builder credentials(String user, String secret) {
            username = Optional.of(user);
            password = Optional.of(secret);
            return this;
        }

        public Builder clearCredentials() {
            username = Optional.empty();
            password = Optional.empty();
            return this;
        }

        public Builder connectTimeout(Duration value) {
            connectTimeout = value;
            return this;
        }

        public Builder acquireTimeout(Duration value) {
            acquireTimeout = value;
            return this;
        }

        public Builder pool(Pool value) {
            pool = value;
            return this;
        }

        public Neo4jDriver6Configuration build() {
            return new Neo4jDriver6Configuration(uri, username, password, connectTimeout, acquireTimeout, pool);
        }
    }

    @Override
    public String toString() {
        return "Neo4jDriver6Configuration[uri=" + uri + ", username=" + username + ", password=" + (password.isPresent() ? "[REDACTED]" : "empty") + ", connectTimeout=" + connectTimeout + ", acquireTimeout=" + acquireTimeout + ", pool=" + pool + "]";
    }

    public record Pool(int maximumSize, Duration idleTestInterval, Duration maximumLifetime) {

        public Pool {
            if (maximumSize < 1) {
                throw new IllegalArgumentException("maximumSize must be positive");
            }
            idleTestInterval = requirePositiveMillis(idleTestInterval, "idleTestInterval", false);
            maximumLifetime = requirePositiveMillis(maximumLifetime, "maximumLifetime", false);
        }

        public static Pool defaults() {
            return new Pool(100, Duration.ofMinutes(1), Duration.ofHours(1));
        }
    }

    private static URI requireUri(URI value) {
        URI uri = Objects.requireNonNull(value, "uri");
        String scheme = uri.getScheme();
        if (scheme == null || !SUPPORTED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT)) || uri.getHost() == null) {
            throw new IllegalArgumentException("uri must use a bolt or neo4j scheme and include a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("uri must not contain credentials");
        }
        if (uri.getPort() < -1 || uri.getPort() > 65_535) {
            throw new IllegalArgumentException("uri port must be between 1 and 65535");
        }
        return uri;
    }

    private static URI parseUri(String value, String field) {
        Objects.requireNonNull(value, field);
        try {
            return URI.create(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " must be a valid URI");
        }
    }

    private static Optional<String> requireOptionalText(Optional<String> value, String field) {
        return Objects.requireNonNull(value, field).map(item -> {
            if (item.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return item;
        });
    }

    private static Duration requirePositiveMillis(Duration value, String field, boolean requireIntRange) {
        Duration duration = Objects.requireNonNull(value, field);
        long milliseconds;
        try {
            milliseconds = duration.toMillis();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + " is too large", failure);
        }
        if (milliseconds < 1) {
            throw new IllegalArgumentException(field + " must be at least 1 millisecond");
        }
        if (requireIntRange && milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must not exceed " + Integer.MAX_VALUE + " milliseconds");
        }
        return duration;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Neo4jDriver6Configuration that))
            return false;
        return java.util.Objects.equals(uri, that.uri) && java.util.Objects.equals(username, that.username) && java.util.Objects.equals(password, that.password) && java.util.Objects.equals(connectTimeout, that.connectTimeout) && java.util.Objects.equals(acquireTimeout, that.acquireTimeout) && java.util.Objects.equals(pool, that.pool);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(uri, username, password, connectTimeout, acquireTimeout, pool);
    }
}
