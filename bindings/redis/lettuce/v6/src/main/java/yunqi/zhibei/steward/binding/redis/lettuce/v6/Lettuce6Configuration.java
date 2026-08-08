package yunqi.zhibei.steward.binding.redis.lettuce.v6;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class Lettuce6Configuration {

    private final String host;

    private final int port;

    private final Optional<String> username;

    private final Optional<String> password;

    private final int database;

    private final boolean tls;

    private final Duration connectTimeout;

    private final Duration commandTimeout;

    Lettuce6Configuration(String host, int port, Optional<String> username, Optional<String> password, int database, boolean tls, Duration connectTimeout, Duration commandTimeout) {
        host = requireText(host, "host");
        port = requirePort(port);
        username = Objects.requireNonNull(username, "username");
        password = Objects.requireNonNull(password, "password");
        database = requireNonNegative(database, "database");
        connectTimeout = requirePortableTimeout(connectTimeout, "connectTimeout");
        commandTimeout = requirePortableTimeout(commandTimeout, "commandTimeout");
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.database = database;
        this.tls = tls;
        this.connectTimeout = connectTimeout;
        this.commandTimeout = commandTimeout;
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

    private static final Duration ONE_MILLISECOND = Duration.ofMillis(1);

    static Lettuce6Configuration defaults() {
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
        return "Lettuce6Configuration[host=" + host + ", port=" + port + ", username=" + username + ", password=" + (password.isPresent() ? "[REDACTED]" : "empty") + ", database=" + database + ", tls=" + tls + ", connectTimeout=" + connectTimeout + ", commandTimeout=" + commandTimeout + "]";
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
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

    private static int requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private static Duration requirePortableTimeout(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.compareTo(ONE_MILLISECOND) < 0) {
            throw new IllegalArgumentException(field + " must be at least 1 millisecond");
        }
        long milliseconds;
        try {
            milliseconds = value.toMillis();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + " is too large", failure);
        }
        if (milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must not exceed " + Integer.MAX_VALUE + " milliseconds");
        }
        return value;
    }

    public static final class Builder {

        private String host = "127.0.0.1";

        private int port = 6379;

        private Optional<String> username = Optional.empty();

        private Optional<String> password = Optional.empty();

        private int database;

        private boolean tls;

        private Duration connectTimeout = Duration.ofSeconds(3);

        private Duration commandTimeout = Duration.ofSeconds(3);

        private Builder() {
        }

        private Builder(Lettuce6Configuration source) {
            host = source.host();
            port = source.port();
            username = source.username();
            password = source.password();
            database = source.database();
            tls = source.tls();
            connectTimeout = source.connectTimeout();
            commandTimeout = source.commandTimeout();
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

        public Lettuce6Configuration build() {
            return new Lettuce6Configuration(host, port, username, password, database, tls, connectTimeout, commandTimeout);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Lettuce6Configuration that))
            return false;
        return java.util.Objects.equals(host, that.host) && port == that.port && java.util.Objects.equals(username, that.username) && java.util.Objects.equals(password, that.password) && database == that.database && tls == that.tls && java.util.Objects.equals(connectTimeout, that.connectTimeout) && java.util.Objects.equals(commandTimeout, that.commandTimeout);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(host, port, username, password, database, tls, connectTimeout, commandTimeout);
    }
}
