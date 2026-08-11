package yunqi.zhibei.steward.interaction.rabbitmq.library.client.amqp.client.v5;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Configuration that belongs to one native RabbitMQ 5 connection.
 */
public final class RabbitMqClient5Configuration {

    private final String uri;

    private final Duration connectionTimeout;

    private final boolean automaticRecovery;

    private final boolean topologyRecovery;

    RabbitMqClient5Configuration(String uri, Duration connectionTimeout, boolean automaticRecovery, boolean topologyRecovery) {
        uri = requireUri(uri);
        connectionTimeout = requireIntMillis(connectionTimeout, "connectionTimeout");
        this.uri = uri;
        this.connectionTimeout = connectionTimeout;
        this.automaticRecovery = automaticRecovery;
        this.topologyRecovery = topologyRecovery;
    }

    public String uri() {
        return uri;
    }

    public Duration connectionTimeout() {
        return connectionTimeout;
    }

    public boolean automaticRecovery() {
        return automaticRecovery;
    }

    public boolean topologyRecovery() {
        return topologyRecovery;
    }

    static RabbitMqClient5Configuration defaults() {
        return new RabbitMqClient5Configuration("amqp://guest:guest@127.0.0.1:5672/%2f", Duration.ofSeconds(30), true, true);
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private String uri;

        private Duration connectionTimeout;

        private boolean automaticRecovery;

        private boolean topologyRecovery;

        private Builder(RabbitMqClient5Configuration source) {
            uri = source.uri();
            connectionTimeout = source.connectionTimeout();
            automaticRecovery = source.automaticRecovery();
            topologyRecovery = source.topologyRecovery();
        }

        public Builder uri(String value) {
            uri = value;
            return this;
        }

        public Builder connectionTimeout(Duration value) {
            connectionTimeout = value;
            return this;
        }

        public Builder automaticRecovery(boolean value) {
            automaticRecovery = value;
            return this;
        }

        public Builder topologyRecovery(boolean value) {
            topologyRecovery = value;
            return this;
        }

        public RabbitMqClient5Configuration build() {
            return new RabbitMqClient5Configuration(uri, connectionTimeout, automaticRecovery, topologyRecovery);
        }
    }

    @Override
    public String toString() {
        return "RabbitMqClient5Configuration[uri=[REDACTED]" + ", connectionTimeout=" + connectionTimeout + ", automaticRecovery=" + automaticRecovery + ", topologyRecovery=" + topologyRecovery + ']';
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String requireUri(String value) {
        String normalized = requireText(value, "uri");
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("uri must be a valid RabbitMQ AMQP URI");
        }

        String scheme = uri.getScheme();
        if (!("amqp".equalsIgnoreCase(scheme) || "amqps".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("uri must use the amqp or amqps scheme");
        }
        if (uri.getHost() == null
                && (uri.getRawUserInfo() != null || uri.getPort() != -1)) {
            throw new IllegalArgumentException(
                    "uri must include a host when credentials or a port are present");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw new IllegalArgumentException("uri port must be between 1 and 65535");
        }
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && userInfo.indexOf(':') != userInfo.lastIndexOf(':')) {
            throw new IllegalArgumentException("uri credentials must contain at most one separator");
        }
        String path = uri.getRawPath();
        if (path != null && path.indexOf('/', 1) != -1) {
            throw new IllegalArgumentException(
                    "uri virtual host must contain at most one path segment");
        }
        return normalized;
    }

    private static Duration requireIntMillis(Duration value, String field) {
        Duration duration = Objects.requireNonNull(value, field);
        long milliseconds;
        try {
            milliseconds = duration.toMillis();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + " is too large", failure);
        }
        if (milliseconds < 1 || milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must be between 1 and " + Integer.MAX_VALUE + " milliseconds");
        }
        return duration;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof RabbitMqClient5Configuration that))
            return false;
        return java.util.Objects.equals(uri, that.uri) && java.util.Objects.equals(connectionTimeout, that.connectionTimeout) && automaticRecovery == that.automaticRecovery && topologyRecovery == that.topologyRecovery;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(uri, connectionTimeout, automaticRecovery, topologyRecovery);
    }
}
