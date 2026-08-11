package yunqi.zhibei.steward.interaction.redis.library.client.redisson.v4;

import org.redisson.config.Protocol;
import org.redisson.config.TransportMode;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class Redisson4Configuration {

    private final Connection connection;

    private final Pool pool;

    private final Client client;

    Redisson4Configuration(Connection connection, Pool pool, Client client) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(client, "client");
        this.connection = connection;
        this.pool = pool;
        this.client = client;
    }

    public Connection connection() {
        return connection;
    }

    public Pool pool() {
        return pool;
    }

    public Client client() {
        return client;
    }

    static Redisson4Configuration defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public Redisson4Configuration withLazyInitialization(boolean enabled) {
        return new Redisson4Configuration(connection, pool, client.withLazyInitialization(enabled));
    }

    public Redisson4Configuration withAddress(URI address) {
        return new Redisson4Configuration(connection.withAddress(address), pool, client);
    }

    public record Connection(URI address, Optional<String> username, Optional<String> password, int database, Optional<String> clientName, Duration idleConnectionTimeout, Duration connectTimeout, Duration commandTimeout, Duration subscriptionTimeout, int retryAttempts, int subscriptionsPerConnection, Duration pingConnectionInterval, Duration dnsMonitoringInterval) {

        private static final Set<String> SUPPORTED_SCHEMES = Set.of("redis", "rediss");

        public Connection {
            address = requireAddress(address);
            username = Objects.requireNonNull(username, "username");
            password = Objects.requireNonNull(password, "password");
            clientName = Objects.requireNonNull(clientName, "clientName");
            database = requireNonNegative(database, "database");
            idleConnectionTimeout = requireIntMilliseconds(idleConnectionTimeout, "idleConnectionTimeout");
            connectTimeout = requireIntMilliseconds(connectTimeout, "connectTimeout");
            commandTimeout = requireIntMilliseconds(commandTimeout, "commandTimeout");
            subscriptionTimeout = requireIntMilliseconds(subscriptionTimeout, "subscriptionTimeout");
            retryAttempts = requireNonNegative(retryAttempts, "retryAttempts");
            subscriptionsPerConnection = requirePositive(subscriptionsPerConnection, "subscriptionsPerConnection");
            pingConnectionInterval = requireIntMilliseconds(pingConnectionInterval, "pingConnectionInterval");
            dnsMonitoringInterval = requirePositiveDuration(dnsMonitoringInterval, "dnsMonitoringInterval");
        }

        public static Connection defaults() {
            return new Connection(URI.create("redis://127.0.0.1:6379"), Optional.empty(), Optional.empty(), 0, Optional.empty(), Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofMillis(7500), 4, 5, Duration.ofSeconds(30), Duration.ofSeconds(5));
        }

        public Connection withAddress(URI updatedAddress) {
            return new Connection(updatedAddress, username, password, database, clientName, idleConnectionTimeout, connectTimeout, commandTimeout, subscriptionTimeout, retryAttempts, subscriptionsPerConnection, pingConnectionInterval, dnsMonitoringInterval);
        }

        @Override
        public String toString() {
            return "Connection[address=" + address + ", username=" + username + ", password=" + (password.isPresent() ? "[REDACTED]" : "empty") + ", database=" + database + ", clientName=" + clientName + ", idleConnectionTimeout=" + idleConnectionTimeout + ", connectTimeout=" + connectTimeout + ", commandTimeout=" + commandTimeout + ", subscriptionTimeout=" + subscriptionTimeout + ", retryAttempts=" + retryAttempts + ", subscriptionsPerConnection=" + subscriptionsPerConnection + ", pingConnectionInterval=" + pingConnectionInterval + ", dnsMonitoringInterval=" + dnsMonitoringInterval + "]";
        }

        private static URI requireAddress(URI address) {
            Objects.requireNonNull(address, "address");
            String scheme = address.getScheme();
            if (scheme == null || !SUPPORTED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT)) || address.getHost() == null) {
                throw new IllegalArgumentException("address must use redis or rediss and include a host");
            }
            if (address.getUserInfo() != null || address.getPath() != null && !address.getPath().isEmpty()) {
                throw new IllegalArgumentException("address must not contain credentials or a path");
            }
            int port = address.getPort();
            if (port != -1 && (port < 1 || port > 65_535)) {
                throw new IllegalArgumentException("address port must be between 1 and 65535");
            }
            return address;
        }
    }

    public record Pool(int connectionMinimumIdleSize, int connectionPoolSize, int subscriptionConnectionMinimumIdleSize, int subscriptionConnectionPoolSize) {

        public Pool {
            connectionMinimumIdleSize = requireNonNegative(connectionMinimumIdleSize, "connectionMinimumIdleSize");
            connectionPoolSize = requirePositive(connectionPoolSize, "connectionPoolSize");
            subscriptionConnectionMinimumIdleSize = requireNonNegative(subscriptionConnectionMinimumIdleSize, "subscriptionConnectionMinimumIdleSize");
            subscriptionConnectionPoolSize = requirePositive(subscriptionConnectionPoolSize, "subscriptionConnectionPoolSize");
            if (connectionMinimumIdleSize > connectionPoolSize) {
                throw new IllegalArgumentException("connectionMinimumIdleSize must not exceed connectionPoolSize");
            }
            if (subscriptionConnectionMinimumIdleSize > subscriptionConnectionPoolSize) {
                throw new IllegalArgumentException("subscriptionConnectionMinimumIdleSize must not exceed " + "subscriptionConnectionPoolSize");
            }
        }

        public static Pool defaults() {
            return new Pool(24, 64, 1, 50);
        }
    }

    public record Client(int threads, int nettyThreads, Duration lockWatchdogTimeout, Duration fairLockWaitTimeout, int lockWatchdogBatchSize, boolean checkLockSyncedSlaves, boolean keepPubSubOrder, boolean useScriptCache, boolean lazyInitialization, boolean tcpKeepAlive, boolean tcpNoDelay, Protocol protocol, TransportMode transportMode) {

        public Client {
            threads = requirePositive(threads, "threads");
            nettyThreads = requirePositive(nettyThreads, "nettyThreads");
            lockWatchdogTimeout = requirePositiveDuration(lockWatchdogTimeout, "lockWatchdogTimeout");
            fairLockWaitTimeout = requirePositiveDuration(fairLockWaitTimeout, "fairLockWaitTimeout");
            lockWatchdogBatchSize = requirePositive(lockWatchdogBatchSize, "lockWatchdogBatchSize");
            Objects.requireNonNull(protocol, "protocol");
            Objects.requireNonNull(transportMode, "transportMode");
        }

        public static Client defaults() {
            return new Client(16, 32, Duration.ofSeconds(30), Duration.ofMinutes(5), 100, true, true, true, false, true, true, Protocol.RESP2, TransportMode.NIO);
        }

        public Client withLazyInitialization(boolean enabled) {
            return new Client(threads, nettyThreads, lockWatchdogTimeout, fairLockWaitTimeout, lockWatchdogBatchSize, checkLockSyncedSlaves, keepPubSubOrder, useScriptCache, enabled, tcpKeepAlive, tcpNoDelay, protocol, transportMode);
        }
    }

    public static final class Builder {

        private Connection connection = Connection.defaults();

        private Pool pool = Pool.defaults();

        private Client client = Client.defaults();

        private Builder() {
        }

        private Builder(Redisson4Configuration source) {
            connection = source.connection();
            pool = source.pool();
            client = source.client();
        }

        public Builder address(String address) {
            return address(parseUri(address, "address"));
        }

        public Builder address(URI address) {
            connection = copyConnection(connection, address, connection.username(), connection.password(), connection.database());
            return this;
        }

        public Builder username(String username) {
            connection = copyConnection(connection, connection.address(), Optional.of(Objects.requireNonNull(username, "username")), connection.password(), connection.database());
            return this;
        }

        public Builder clearUsername() {
            connection = copyConnection(connection, connection.address(), Optional.empty(), connection.password(), connection.database());
            return this;
        }

        public Builder password(String password) {
            connection = copyConnection(connection, connection.address(), connection.username(), Optional.of(Objects.requireNonNull(password, "password")), connection.database());
            return this;
        }

        public Builder clearPassword() {
            connection = copyConnection(connection, connection.address(), connection.username(), Optional.empty(), connection.database());
            return this;
        }

        public Builder database(int database) {
            connection = copyConnection(connection, connection.address(), connection.username(), connection.password(), database);
            return this;
        }

        public Builder connection(Connection connection) {
            this.connection = Objects.requireNonNull(connection, "connection");
            return this;
        }

        public Builder pool(Pool pool) {
            this.pool = Objects.requireNonNull(pool, "pool");
            return this;
        }

        public Builder client(Client client) {
            this.client = Objects.requireNonNull(client, "client");
            return this;
        }

        public Builder lazyInitialization(boolean enabled) {
            client = client.withLazyInitialization(enabled);
            return this;
        }

        public Redisson4Configuration build() {
            return new Redisson4Configuration(connection, pool, client);
        }
    }

    private static Connection copyConnection(Connection source, URI address, Optional<String> username, Optional<String> password, int database) {
        return new Connection(address, username, password, database, source.clientName(), source.idleConnectionTimeout(), source.connectTimeout(), source.commandTimeout(), source.subscriptionTimeout(), source.retryAttempts(), source.subscriptionsPerConnection(), source.pingConnectionInterval(), source.dnsMonitoringInterval());
    }

    private static URI parseUri(String value, String field) {
        Objects.requireNonNull(value, field);
        try {
            return URI.create(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " must be a valid URI");
        }
    }

    private static int requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static int requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private static Duration requirePositiveDuration(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException(field + " must be at least 1 millisecond");
        }
        try {
            value.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + " is too large", failure);
        }
        return value;
    }

    private static Duration requireIntMilliseconds(Duration value, String field) {
        requirePositiveDuration(value, field);
        long milliseconds = value.toMillis();
        if (milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must not exceed " + Integer.MAX_VALUE + " milliseconds");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Redisson4Configuration that))
            return false;
        return java.util.Objects.equals(connection, that.connection) && java.util.Objects.equals(pool, that.pool) && java.util.Objects.equals(client, that.client);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(connection, pool, client);
    }

    @Override
    public String toString() {
        return "Redisson4Configuration[connection=" + connection + ", pool=" + pool + ", client=" + client + "]";
    }
}
