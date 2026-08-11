package yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Configuration for a Jedis 7 Redis Cluster client.
 */
public final class Jedis7ClusterConfiguration {

    private final Set<Node> nodes;

    private final Optional<String> username;

    private final Optional<String> password;

    private final boolean tls;

    private final Duration connectTimeout;

    private final Duration commandTimeout;

    private final int maximumAttempts;

    private final Duration topologyRefreshPeriod;

    private final int minimumIdle;

    private final int maximumIdle;

    private final int maximumTotal;

    private final Duration acquireTimeout;

    Jedis7ClusterConfiguration(Set<Node> nodes, Optional<String> username, Optional<String> password, boolean tls, Duration connectTimeout, Duration commandTimeout, int maximumAttempts, Duration topologyRefreshPeriod, int minimumIdle, int maximumIdle, int maximumTotal, Duration acquireTimeout) {
        nodes = Set.copyOf(Objects.requireNonNull(nodes, "nodes"));
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("nodes must not be empty");
        }
        username = Objects.requireNonNull(username, "username");
        password = Objects.requireNonNull(password, "password");
        connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        commandTimeout = requirePositive(commandTimeout, "commandTimeout");
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be positive");
        }
        topologyRefreshPeriod = requirePositive(topologyRefreshPeriod, "topologyRefreshPeriod");
        if (minimumIdle < 0 || maximumIdle < 1 || maximumTotal < 1) {
            throw new IllegalArgumentException("pool sizes must be non-negative and non-zero");
        }
        if (minimumIdle > maximumIdle || maximumIdle > maximumTotal) {
            throw new IllegalArgumentException("minimumIdle must not exceed maximumIdle or maximumTotal");
        }
        acquireTimeout = requirePositive(acquireTimeout, "acquireTimeout");
        this.nodes = nodes;
        this.username = username;
        this.password = password;
        this.tls = tls;
        this.connectTimeout = connectTimeout;
        this.commandTimeout = commandTimeout;
        this.maximumAttempts = maximumAttempts;
        this.topologyRefreshPeriod = topologyRefreshPeriod;
        this.minimumIdle = minimumIdle;
        this.maximumIdle = maximumIdle;
        this.maximumTotal = maximumTotal;
        this.acquireTimeout = acquireTimeout;
    }

    public Set<Node> nodes() {
        return nodes;
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

    public Duration commandTimeout() {
        return commandTimeout;
    }

    public int maximumAttempts() {
        return maximumAttempts;
    }

    public Duration topologyRefreshPeriod() {
        return topologyRefreshPeriod;
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

    static Jedis7ClusterConfiguration defaults() {
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
        return "Jedis7ClusterConfiguration[nodes=" + nodes + ", username=" + username + ", password=" + (password.isPresent() ? "[REDACTED]" : "empty") + ", tls=" + tls + ", connectTimeout=" + connectTimeout + ", commandTimeout=" + commandTimeout + ", maximumAttempts=" + maximumAttempts + ", topologyRefreshPeriod=" + topologyRefreshPeriod + ", minimumIdle=" + minimumIdle + ", maximumIdle=" + maximumIdle + ", maximumTotal=" + maximumTotal + ", acquireTimeout=" + acquireTimeout + ']';
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

    public record Node(String host, int port) {

        public Node {
            host = Objects.requireNonNull(host, "host").trim();
            if (host.isEmpty()) {
                throw new IllegalArgumentException("host must not be blank");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
        }
    }

    public static final class Builder {

        private final Set<Node> nodes = new LinkedHashSet<>(Set.of(new Node("127.0.0.1", 6379)));

        private Optional<String> username = Optional.empty();

        private Optional<String> password = Optional.empty();

        private boolean tls;

        private Duration connectTimeout = DEFAULT_TIMEOUT;

        private Duration commandTimeout = DEFAULT_TIMEOUT;

        private int maximumAttempts = 5;

        private Duration topologyRefreshPeriod = Duration.ofSeconds(30);

        private int minimumIdle = 1;

        private int maximumIdle = 8;

        private int maximumTotal = 16;

        private Duration acquireTimeout = DEFAULT_TIMEOUT;

        private Builder() {
        }

        private Builder(Jedis7ClusterConfiguration source) {
            nodes.clear();
            nodes.addAll(source.nodes());
            username = source.username();
            password = source.password();
            tls = source.tls();
            connectTimeout = source.connectTimeout();
            commandTimeout = source.commandTimeout();
            maximumAttempts = source.maximumAttempts();
            topologyRefreshPeriod = source.topologyRefreshPeriod();
            minimumIdle = source.minimumIdle();
            maximumIdle = source.maximumIdle();
            maximumTotal = source.maximumTotal();
            acquireTimeout = source.acquireTimeout();
        }

        public Builder nodes(Set<Node> nodes) {
            this.nodes.clear();
            this.nodes.addAll(Objects.requireNonNull(nodes, "nodes"));
            return this;
        }

        public Builder node(String host, int port) {
            nodes.clear();
            nodes.add(new Node(host, port));
            return this;
        }

        public Builder addNode(String host, int port) {
            nodes.add(new Node(host, port));
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

        public Builder maximumAttempts(int maximumAttempts) {
            this.maximumAttempts = maximumAttempts;
            return this;
        }

        public Builder topologyRefreshPeriod(Duration topologyRefreshPeriod) {
            this.topologyRefreshPeriod = topologyRefreshPeriod;
            return this;
        }

        public Builder pool(int minimumIdle, int maximumIdle, int maximumTotal) {
            this.minimumIdle = minimumIdle;
            this.maximumIdle = maximumIdle;
            this.maximumTotal = maximumTotal;
            return this;
        }

        public Builder acquireTimeout(Duration acquireTimeout) {
            this.acquireTimeout = acquireTimeout;
            return this;
        }

        public Jedis7ClusterConfiguration build() {
            return new Jedis7ClusterConfiguration(nodes, username, password, tls, connectTimeout, commandTimeout, maximumAttempts, topologyRefreshPeriod, minimumIdle, maximumIdle, maximumTotal, acquireTimeout);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Jedis7ClusterConfiguration that))
            return false;
        return java.util.Objects.equals(nodes, that.nodes) && java.util.Objects.equals(username, that.username) && java.util.Objects.equals(password, that.password) && tls == that.tls && java.util.Objects.equals(connectTimeout, that.connectTimeout) && java.util.Objects.equals(commandTimeout, that.commandTimeout) && maximumAttempts == that.maximumAttempts && java.util.Objects.equals(topologyRefreshPeriod, that.topologyRefreshPeriod) && minimumIdle == that.minimumIdle && maximumIdle == that.maximumIdle && maximumTotal == that.maximumTotal && java.util.Objects.equals(acquireTimeout, that.acquireTimeout);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(nodes, username, password, tls, connectTimeout, commandTimeout, maximumAttempts, topologyRefreshPeriod, minimumIdle, maximumIdle, maximumTotal, acquireTimeout);
    }
}
