package yunqi.zhibei.steward.binding.consul.api.v1;

import java.util.Objects;

/**
 * Configuration that belongs to one native Consul API 1 client.
 */
public final class ConsulApi1Configuration {

    private final String host;

    private final int port;

    ConsulApi1Configuration(String host, int port) {
        host = requireText(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.host = host;
        this.port = port;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    static ConsulApi1Configuration defaults() {
        return new ConsulApi1Configuration("127.0.0.1", 8500);
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private String host;

        private int port;

        private Builder(ConsulApi1Configuration source) {
            host = source.host();
            port = source.port();
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public ConsulApi1Configuration build() {
            return new ConsulApi1Configuration(host, port);
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof ConsulApi1Configuration that))
            return false;
        return java.util.Objects.equals(host, that.host) && port == that.port;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return "ConsulApi1Configuration[host=" + host + ", port=" + port + "]";
    }
}
