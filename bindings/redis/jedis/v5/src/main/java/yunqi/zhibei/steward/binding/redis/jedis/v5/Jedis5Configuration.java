package yunqi.zhibei.steward.binding.redis.jedis.v5;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration for a Jedis 5 pooled client.
 */
public final class Jedis5Configuration {

    private final String host;

    private final int port;

    private final Optional<String> username;

    private final Optional<String> password;

    private final int database;

    private final boolean tls;

    private final Duration connectTimeout;

    private final Duration commandTimeout;

    private final int minimumIdle;

    private final int maximumIdle;

    private final int maximumTotal;

    private final Duration acquireTimeout;

    Jedis5Configuration(String host, int port, Optional<String> username, Optional<String> password, int database, boolean tls, Duration connectTimeout, Duration commandTimeout, int minimumIdle, int maximumIdle, int maximumTotal, Duration acquireTimeout) {
        host = requireText(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        username = Objects.requireNonNull(username, "username");
        password = Objects.requireNonNull(password, "password");
        if (database < 0) {
            throw new IllegalArgumentException("database must not be negative");
        }
        connectTimeout = requireJedisTimeout(connectTimeout, "connectTimeout");
        commandTimeout = requireJedisTimeout(commandTimeout, "commandTimeout");
        if (minimumIdle < 0) {
            throw new IllegalArgumentException("minimumIdle must not be negative");
        }
        if (maximumIdle < 1) {
            throw new IllegalArgumentException("maximumIdle must be positive");
        }
        if (maximumTotal < 1) {
            throw new IllegalArgumentException("maximumTotal must be positive");
        }
        if (minimumIdle > maximumIdle) {
            throw new IllegalArgumentException("minimumIdle must not exceed maximumIdle");
        }
        if (maximumIdle > maximumTotal) {
            throw new IllegalArgumentException("maximumIdle must not exceed maximumTotal");
        }
        acquireTimeout = requirePositiveDuration(acquireTimeout, "acquireTimeout");
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.database = database;
        this.tls = tls;
        this.connectTimeout = connectTimeout;
        this.commandTimeout = commandTimeout;
        this.minimumIdle = minimumIdle;
        this.maximumIdle = maximumIdle;
        this.maximumTotal = maximumTotal;
        this.acquireTimeout = acquireTimeout;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public Optional<String> username() {
        return username;
    }

    public Optional<String> password() {
        return password;
    }

    public int database() {
        return database;
    }

    public boolean tls() {
        return tls;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration commandTimeout() {
        return commandTimeout;
    }

    public int minimumIdle() {
        return minimumIdle;
    }

    public int maximumIdle() {
        return maximumIdle;
    }

    public int maximumTotal() {
        return maximumTotal;
    }

    public Duration acquireTimeout() {
        return acquireTimeout;
    }

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    static Jedis5Configuration defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public String toString() {
        return "Jedis5Configuration[host=" + host + ", port=" + port + ", username=" + username + ", password=" + (password.isPresent() ? "[REDACTED]" : "empty") + ", database=" + database + ", tls=" + tls + ", connectTimeout=" + connectTimeout + ", commandTimeout=" + commandTimeout + ", minimumIdle=" + minimumIdle + ", maximumIdle=" + maximumIdle + ", maximumTotal=" + maximumTotal + ", acquireTimeout=" + acquireTimeout + ']';
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static Duration requireJedisTimeout(Duration value, String field) {
        Duration timeout = requirePositiveDuration(value, field);
        long milliseconds = timeout.toMillis();
        if (milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must not exceed " + Integer.MAX_VALUE + " milliseconds");
        }
        return timeout;
    }

    private static Duration requirePositiveDuration(Duration value, String field) {
        Duration duration = Objects.requireNonNull(value, field);
        if (duration.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException(field + " must be at least 1 millisecond");
        }
        return duration;
    }

    public static final class Builder {

        private String host = "127.0.0.1";

        private int port = 6379;

        private Optional<String> username = Optional.empty();

        private Optional<String> password = Optional.empty();

        private int database;

        private boolean tls;

        private Duration connectTimeout = DEFAULT_TIMEOUT;

        private Duration commandTimeout = DEFAULT_TIMEOUT;

        private int minimumIdle = 1;

        private int maximumIdle = 8;

        private int maximumTotal = 16;

        private Duration acquireTimeout = DEFAULT_TIMEOUT;

        private Builder() {
        }

        private Builder(Jedis5Configuration source) {
            host = source.host();
            port = source.port();
            username = source.username();
            password = source.password();
            database = source.database();
            tls = source.tls();
            connectTimeout = source.connectTimeout();
            commandTimeout = source.commandTimeout();
            minimumIdle = source.minimumIdle();
            maximumIdle = source.maximumIdle();
            maximumTotal = source.maximumTotal();
            acquireTimeout = source.acquireTimeout();
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder username(String username) {
            this.username = Optional.of(Objects.requireNonNull(username, "username"));
            return this;
        }

        public Builder clearUsername() {
            username = Optional.empty();
            return this;
        }

        public Builder password(String password) {
            this.password = Optional.of(Objects.requireNonNull(password, "password"));
            return this;
        }

        public Builder clearPassword() {
            password = Optional.empty();
            return this;
        }

        public Builder database(int database) {
            this.database = database;
            return this;
        }

        public Builder tls(boolean tls) {
            this.tls = tls;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder commandTimeout(Duration commandTimeout) {
            this.commandTimeout = commandTimeout;
            return this;
        }

        public Builder minimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
            return this;
        }

        public Builder maximumIdle(int maximumIdle) {
            this.maximumIdle = maximumIdle;
            return this;
        }

        public Builder maximumTotal(int maximumTotal) {
            this.maximumTotal = maximumTotal;
            return this;
        }

        public Builder acquireTimeout(Duration acquireTimeout) {
            this.acquireTimeout = acquireTimeout;
            return this;
        }

        public Jedis5Configuration build() {
            return new Jedis5Configuration(host, port, username, password, database, tls, connectTimeout, commandTimeout, minimumIdle, maximumIdle, maximumTotal, acquireTimeout);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Jedis5Configuration that))
            return false;
        return java.util.Objects.equals(host, that.host) && port == that.port && java.util.Objects.equals(username, that.username) && java.util.Objects.equals(password, that.password) && database == that.database && tls == that.tls && java.util.Objects.equals(connectTimeout, that.connectTimeout) && java.util.Objects.equals(commandTimeout, that.commandTimeout) && minimumIdle == that.minimumIdle && maximumIdle == that.maximumIdle && maximumTotal == that.maximumTotal && java.util.Objects.equals(acquireTimeout, that.acquireTimeout);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(host, port, username, password, database, tls, connectTimeout, commandTimeout, minimumIdle, maximumIdle, maximumTotal, acquireTimeout);
    }
}
