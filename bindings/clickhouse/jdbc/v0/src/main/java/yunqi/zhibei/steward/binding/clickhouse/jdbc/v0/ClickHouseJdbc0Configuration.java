package yunqi.zhibei.steward.binding.clickhouse.jdbc.v0;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ClickHouseJdbc0Configuration {

    private final String host;

    private final int port;

    private final String database;

    private final Optional<String> username;

    private final Optional<String> password;

    private final boolean tls;

    private final Duration connectTimeout;

    private final Duration socketTimeout;

    private final Pool pool;

    private final Map<String, String> driverProperties;

    ClickHouseJdbc0Configuration(String host, int port, String database, Optional<String> username, Optional<String> password, boolean tls, Duration connectTimeout, Duration socketTimeout, Pool pool, Map<String, String> driverProperties) {
        host = requireText(host, "host");
        port = requirePort(port);
        database = requireText(database, "database");
        username = Objects.requireNonNull(username, "username");
        password = Objects.requireNonNull(password, "password");
        connectTimeout = requirePositiveDuration(connectTimeout, "connectTimeout");
        socketTimeout = requirePositiveDuration(socketTimeout, "socketTimeout");
        pool = Objects.requireNonNull(pool, "pool");
        driverProperties = immutableProperties(driverProperties);
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.tls = tls;
        this.connectTimeout = connectTimeout;
        this.socketTimeout = socketTimeout;
        this.pool = pool;
        this.driverProperties = driverProperties;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String database() {
        return database;
    }

    public Optional<String> username() {
        return username;
    }

    public Optional<String> password() {
        return password;
    }

    public boolean tls() {
        return tls;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration socketTimeout() {
        return socketTimeout;
    }

    public Pool pool() {
        return pool;
    }

    public Map<String, String> driverProperties() {
        return driverProperties;
    }

    static ClickHouseJdbc0Configuration defaults(String database) {
        return new ClickHouseJdbc0Configuration("127.0.0.1", 8123, database, Optional.empty(), Optional.empty(), false, Duration.ofSeconds(3), Duration.ofSeconds(30), Pool.defaults(), Map.of());
    }

    public static Builder builder(String database) {
        return new Builder(defaults(database));
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private String host;

        private int port;

        private String database;

        private Optional<String> username;

        private Optional<String> password;

        private boolean tls;

        private Duration connectTimeout;

        private Duration socketTimeout;

        private Pool pool;

        private final Map<String, String> driverProperties = new LinkedHashMap<>();

        private Builder(ClickHouseJdbc0Configuration source) {
            host = source.host();
            port = source.port();
            database = source.database();
            username = source.username();
            password = source.password();
            tls = source.tls();
            connectTimeout = source.connectTimeout();
            socketTimeout = source.socketTimeout();
            pool = source.pool();
            driverProperties.putAll(source.driverProperties());
        }

        public Builder host(String value) {
            host = value;
            return this;
        }

        public Builder port(int value) {
            port = value;
            return this;
        }

        public Builder database(String value) {
            database = value;
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

        public Builder tls(boolean value) {
            tls = value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            connectTimeout = value;
            return this;
        }

        public Builder socketTimeout(Duration value) {
            socketTimeout = value;
            return this;
        }

        public Builder pool(Pool value) {
            pool = value;
            return this;
        }

        public Builder driverProperties(Map<String, String> values) {
            driverProperties.clear();
            driverProperties.putAll(values);
            return this;
        }

        public Builder driverProperty(String key, String value) {
            driverProperties.put(key, value);
            return this;
        }

        public ClickHouseJdbc0Configuration build() {
            return new ClickHouseJdbc0Configuration(host, port, database, username, password, tls, connectTimeout, socketTimeout, pool, driverProperties);
        }
    }

    @Override
    public String toString() {
        return "ClickHouseJdbc0Configuration[host=" + host + ", port=" + port + ", database=" + database + ", username=" + username + ", password=" + (password.isPresent() ? "[REDACTED]" : "empty") + ", tls=" + tls + ", connectTimeout=" + connectTimeout + ", socketTimeout=" + socketTimeout + ", pool=" + pool + ", driverPropertyKeys=" + driverProperties.keySet() + "]";
    }

    public record Pool(int minimumIdle, int maximumSize, Duration acquireTimeout, Duration idleTimeout, Duration maximumLifetime) {

        private static final Duration MINIMUM_ACQUIRE_TIMEOUT = Duration.ofMillis(250);

        private static final Duration MINIMUM_IDLE_TIMEOUT = Duration.ofSeconds(10);

        private static final Duration MINIMUM_MAXIMUM_LIFETIME = Duration.ofSeconds(30);

        public Pool {
            if (minimumIdle < 0) {
                throw new IllegalArgumentException("minimumIdle must not be negative");
            }
            if (maximumSize < 1) {
                throw new IllegalArgumentException("maximumSize must be positive");
            }
            if (minimumIdle > maximumSize) {
                throw new IllegalArgumentException("minimumIdle must not exceed maximumSize");
            }
            acquireTimeout = requireZeroOrAtLeast(acquireTimeout, MINIMUM_ACQUIRE_TIMEOUT, "acquireTimeout");
            idleTimeout = requireZeroOrAtLeast(idleTimeout, MINIMUM_IDLE_TIMEOUT, "idleTimeout");
            maximumLifetime = requireZeroOrAtLeast(maximumLifetime, MINIMUM_MAXIMUM_LIFETIME, "maximumLifetime");
            if (minimumIdle < maximumSize && !idleTimeout.isZero() && !maximumLifetime.isZero() && idleTimeout.toMillis() > maximumLifetime.toMillis() - 1_000L) {
                throw new IllegalArgumentException("idleTimeout must be at least one second shorter than maximumLifetime");
            }
        }

        public static Pool defaults() {
            return new Pool(1, 10, Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(30));
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static int requirePort(int value) {
        if (value < 1 || value > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        return value;
    }

    private static Duration requirePositiveDuration(Duration value, String field) {
        Duration duration = Objects.requireNonNull(value, field);
        if (duration.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException(field + " must be at least 1 millisecond");
        }
        try {
            duration.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " is too large", exception);
        }
        return duration;
    }

    private static Duration requireZeroOrAtLeast(Duration value, Duration minimum, String field) {
        Duration duration = Objects.requireNonNull(value, field);
        try {
            duration.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " is too large", exception);
        }
        if (duration.isNegative() || (!duration.isZero() && duration.compareTo(minimum) < 0)) {
            throw new IllegalArgumentException(field + " must be zero or at least " + minimum);
        }
        return duration;
    }

    private static Map<String, String> immutableProperties(Map<String, String> source) {
        Objects.requireNonNull(source, "driverProperties");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(requireText(key, "driver property key"), Objects.requireNonNull(value, "driver property value")));
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof ClickHouseJdbc0Configuration that))
            return false;
        return java.util.Objects.equals(host, that.host) && port == that.port && java.util.Objects.equals(database, that.database) && java.util.Objects.equals(username, that.username) && java.util.Objects.equals(password, that.password) && tls == that.tls && java.util.Objects.equals(connectTimeout, that.connectTimeout) && java.util.Objects.equals(socketTimeout, that.socketTimeout) && java.util.Objects.equals(pool, that.pool) && java.util.Objects.equals(driverProperties, that.driverProperties);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(host, port, database, username, password, tls, connectTimeout, socketTimeout, pool, driverProperties);
    }
}
