package yunqi.zhibei.steward.interaction.redis.library.client.redisson.v4;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Configuration for a Redisson 4 Redis Cluster client.
 */
public final class Redisson4ClusterConfiguration {

    private final Set<URI> nodeAddresses;

    private final Optional<String> username;

    private final Optional<String> password;

    private final Duration connectTimeout;

    private final Duration commandTimeout;

    private final int retryAttempts;

    private final Duration scanInterval;

    private final Pool pool;

    private final Redisson4Configuration.Client client;

    Redisson4ClusterConfiguration(Set<URI> nodeAddresses, Optional<String> username, Optional<String> password, Duration connectTimeout, Duration commandTimeout, int retryAttempts, Duration scanInterval, Pool pool, Redisson4Configuration.Client client) {
        nodeAddresses = Set.copyOf(Objects.requireNonNull(nodeAddresses, "nodeAddresses"));
        if (nodeAddresses.isEmpty()) {
            throw new IllegalArgumentException("nodeAddresses must not be empty");
        }
        nodeAddresses.forEach(Redisson4ClusterConfiguration::requireAddress);
        username = Objects.requireNonNull(username, "username");
        password = Objects.requireNonNull(password, "password");
        connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        commandTimeout = requirePositive(commandTimeout, "commandTimeout");
        if (retryAttempts < 0) {
            throw new IllegalArgumentException("retryAttempts must not be negative");
        }
        scanInterval = requirePositive(scanInterval, "scanInterval");
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(client, "client");
        this.nodeAddresses = nodeAddresses;
        this.username = username;
        this.password = password;
        this.connectTimeout = connectTimeout;
        this.commandTimeout = commandTimeout;
        this.retryAttempts = retryAttempts;
        this.scanInterval = scanInterval;
        this.pool = pool;
        this.client = client;
    }

    public Set<URI> nodeAddresses() {
        return nodeAddresses;
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

    public Duration commandTimeout() {
        return commandTimeout;
    }

    public int retryAttempts() {
        return retryAttempts;
    }

    public Duration scanInterval() {
        return scanInterval;
    }

    public Pool pool() {
        return pool;
    }

    public Redisson4Configuration.Client client() {
        return client;
    }

    static Redisson4ClusterConfiguration defaults() {
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
        return "Redisson4ClusterConfiguration[nodeAddresses=" + nodeAddresses + ", username=" + username + ", password=" + (password.isPresent() ? "[REDACTED]" : "empty") + ", connectTimeout=" + connectTimeout + ", commandTimeout=" + commandTimeout + ", retryAttempts=" + retryAttempts + ", scanInterval=" + scanInterval + ", pool=" + pool + ", client=" + client + ']';
    }

    private static URI requireAddress(URI address) {
        Objects.requireNonNull(address, "address");
        String scheme = address.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("redis") || scheme.equalsIgnoreCase("rediss")) || address.getHost() == null) {
            throw new IllegalArgumentException("node address must use redis or rediss and include a host");
        }
        if (address.getUserInfo() != null || address.getPath() != null && !address.getPath().isEmpty() || address.getQuery() != null || address.getFragment() != null) {
            throw new IllegalArgumentException("node address must not contain credentials, a path, a query, or a fragment");
        }
        if (address.getPort() < 1 || address.getPort() > 65_535) {
            throw new IllegalArgumentException("node address port must be between 1 and 65535");
        }
        return address;
    }

    private static Duration requirePositive(Duration value, String field) {
        Duration duration = Objects.requireNonNull(value, field);
        if (duration.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException(field + " must be at least 1 millisecond");
        }
        if (duration.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must not exceed " + Integer.MAX_VALUE + " milliseconds");
        }
        return duration;
    }

    private static URI parseUri(String value, String field) {
        Objects.requireNonNull(value, field);
        try {
            return URI.create(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " must be a valid URI");
        }
    }

    public record Pool(int masterMinimumIdle, int masterMaximum, int slaveMinimumIdle, int slaveMaximum, int subscriptionMinimumIdle, int subscriptionMaximum) {

        public Pool {
            if (masterMinimumIdle < 0 || slaveMinimumIdle < 0 || subscriptionMinimumIdle < 0) {
                throw new IllegalArgumentException("minimum idle pool sizes must not be negative");
            }
            if (masterMaximum < 1 || slaveMaximum < 1 || subscriptionMaximum < 1) {
                throw new IllegalArgumentException("maximum pool sizes must be positive");
            }
            if (masterMinimumIdle > masterMaximum || slaveMinimumIdle > slaveMaximum || subscriptionMinimumIdle > subscriptionMaximum) {
                throw new IllegalArgumentException("minimum idle pool sizes must not exceed their maximums");
            }
        }

        public static Pool defaults() {
            return new Pool(24, 64, 24, 64, 1, 50);
        }
    }

    public static final class Builder {

        private final Set<URI> nodeAddresses = new LinkedHashSet<>(Set.of(URI.create("redis://127.0.0.1:6379")));

        private Optional<String> username = Optional.empty();

        private Optional<String> password = Optional.empty();

        private Duration connectTimeout = Duration.ofSeconds(10);

        private Duration commandTimeout = Duration.ofSeconds(3);

        private int retryAttempts = 4;

        private Duration scanInterval = Duration.ofSeconds(1);

        private Pool pool = Pool.defaults();

        private Redisson4Configuration.Client client = Redisson4Configuration.Client.defaults();

        private Builder() {
        }

        private Builder(Redisson4ClusterConfiguration source) {
            nodeAddresses.clear();
            nodeAddresses.addAll(source.nodeAddresses());
            username = source.username();
            password = source.password();
            connectTimeout = source.connectTimeout();
            commandTimeout = source.commandTimeout();
            retryAttempts = source.retryAttempts();
            scanInterval = source.scanInterval();
            pool = source.pool();
            client = source.client();
        }

        public Builder nodeAddress(String address) {
            URI parsed = parseUri(address, "node address");
            nodeAddresses.clear();
            nodeAddresses.add(parsed);
            return this;
        }

        public Builder addNodeAddress(String address) {
            nodeAddresses.add(parseUri(address, "node address"));
            return this;
        }

        public Builder nodeAddresses(Set<URI> addresses) {
            nodeAddresses.clear();
            nodeAddresses.addAll(Objects.requireNonNull(addresses, "addresses"));
            return this;
        }

        public Builder username(String username) {
            this.username = Optional.of(Objects.requireNonNull(username, "username"));
            return this;
        }

        public Builder password(String password) {
            this.password = Optional.of(Objects.requireNonNull(password, "password"));
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

        public Builder retryAttempts(int retryAttempts) {
            this.retryAttempts = retryAttempts;
            return this;
        }

        public Builder scanInterval(Duration scanInterval) {
            this.scanInterval = scanInterval;
            return this;
        }

        public Builder pool(Pool pool) {
            this.pool = Objects.requireNonNull(pool, "pool");
            return this;
        }

        public Builder client(Redisson4Configuration.Client client) {
            this.client = Objects.requireNonNull(client, "client");
            return this;
        }

        public Redisson4ClusterConfiguration build() {
            return new Redisson4ClusterConfiguration(nodeAddresses, username, password, connectTimeout, commandTimeout, retryAttempts, scanInterval, pool, client);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Redisson4ClusterConfiguration that))
            return false;
        return java.util.Objects.equals(nodeAddresses, that.nodeAddresses) && java.util.Objects.equals(username, that.username) && java.util.Objects.equals(password, that.password) && java.util.Objects.equals(connectTimeout, that.connectTimeout) && java.util.Objects.equals(commandTimeout, that.commandTimeout) && retryAttempts == that.retryAttempts && java.util.Objects.equals(scanInterval, that.scanInterval) && java.util.Objects.equals(pool, that.pool) && java.util.Objects.equals(client, that.client);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(nodeAddresses, username, password, connectTimeout, commandTimeout, retryAttempts, scanInterval, pool, client);
    }
}
