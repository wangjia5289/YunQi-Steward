package yunqi.zhibei.steward.interaction.redis.library.client.lettuce.v6;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Configuration for a Lettuce 6 Redis Cluster client.
 */
public final class Lettuce6ClusterConfiguration {

    private final Set<Node> nodes;

    private final Optional<String> username;

    private final Optional<String> password;

    private final boolean tls;

    private final Duration connectTimeout;

    private final Duration commandTimeout;

    private final int maximumRedirects;

    private final Duration topologyRefreshPeriod;

    Lettuce6ClusterConfiguration(Set<Node> nodes, Optional<String> username, Optional<String> password, boolean tls, Duration connectTimeout, Duration commandTimeout, int maximumRedirects, Duration topologyRefreshPeriod) {
        nodes = Set.copyOf(Objects.requireNonNull(nodes, "nodes"));
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("nodes must not be empty");
        }
        username = Objects.requireNonNull(username, "username");
        password = Objects.requireNonNull(password, "password");
        connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        commandTimeout = requirePositive(commandTimeout, "commandTimeout");
        if (maximumRedirects < 1) {
            throw new IllegalArgumentException("maximumRedirects must be positive");
        }
        topologyRefreshPeriod = requirePositive(topologyRefreshPeriod, "topologyRefreshPeriod");
        this.nodes = nodes;
        this.username = username;
        this.password = password;
        this.tls = tls;
        this.connectTimeout = connectTimeout;
        this.commandTimeout = commandTimeout;
        this.maximumRedirects = maximumRedirects;
        this.topologyRefreshPeriod = topologyRefreshPeriod;
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

    public int maximumRedirects() {
        return maximumRedirects;
    }

    public Duration topologyRefreshPeriod() {
        return topologyRefreshPeriod;
    }

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    static Lettuce6ClusterConfiguration defaults() {
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
        return "Lettuce6ClusterConfiguration[nodes=" + nodes + ", username=" + username + ", password=" + (password.isPresent() ? "[REDACTED]" : "empty") + ", tls=" + tls + ", connectTimeout=" + connectTimeout + ", commandTimeout=" + commandTimeout + ", maximumRedirects=" + maximumRedirects + ", topologyRefreshPeriod=" + topologyRefreshPeriod + ']';
    }

    private static Duration requirePositive(Duration value, String field) {
        Duration duration = Objects.requireNonNull(value, field);
        if (duration.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException(field + " must be at least 1 millisecond");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + " is too large", failure);
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

        private int maximumRedirects = 5;

        private Duration topologyRefreshPeriod = Duration.ofSeconds(30);

        private Builder() {
        }

        private Builder(Lettuce6ClusterConfiguration source) {
            nodes.clear();
            nodes.addAll(source.nodes());
            username = source.username();
            password = source.password();
            tls = source.tls();
            connectTimeout = source.connectTimeout();
            commandTimeout = source.commandTimeout();
            maximumRedirects = source.maximumRedirects();
            topologyRefreshPeriod = source.topologyRefreshPeriod();
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

        public Builder maximumRedirects(int maximumRedirects) {
            this.maximumRedirects = maximumRedirects;
            return this;
        }

        public Builder topologyRefreshPeriod(Duration topologyRefreshPeriod) {
            this.topologyRefreshPeriod = topologyRefreshPeriod;
            return this;
        }

        public Lettuce6ClusterConfiguration build() {
            return new Lettuce6ClusterConfiguration(nodes, username, password, tls, connectTimeout, commandTimeout, maximumRedirects, topologyRefreshPeriod);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Lettuce6ClusterConfiguration that))
            return false;
        return java.util.Objects.equals(nodes, that.nodes) && java.util.Objects.equals(username, that.username) && java.util.Objects.equals(password, that.password) && tls == that.tls && java.util.Objects.equals(connectTimeout, that.connectTimeout) && java.util.Objects.equals(commandTimeout, that.commandTimeout) && maximumRedirects == that.maximumRedirects && java.util.Objects.equals(topologyRefreshPeriod, that.topologyRefreshPeriod);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(nodes, username, password, tls, connectTimeout, commandTimeout, maximumRedirects, topologyRefreshPeriod);
    }
}
