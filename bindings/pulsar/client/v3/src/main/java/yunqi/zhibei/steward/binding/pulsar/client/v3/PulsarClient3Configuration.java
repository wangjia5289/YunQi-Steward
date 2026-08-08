package yunqi.zhibei.steward.binding.pulsar.client.v3;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration that belongs to one native Pulsar 3 client.
 */
public final class PulsarClient3Configuration {

    private final String serviceUrl;

    private final Optional<String> authenticationPluginClassName;

    private final Optional<String> authenticationParams;

    private final Duration operationTimeout;

    private final int ioThreads;

    private final int listenerThreads;

    PulsarClient3Configuration(String serviceUrl, Optional<String> authenticationPluginClassName, Optional<String> authenticationParams, Duration operationTimeout, int ioThreads, int listenerThreads) {
        serviceUrl = requireText(serviceUrl, "serviceUrl");
        authenticationPluginClassName = normalizeText(authenticationPluginClassName, "authenticationPluginClassName", true);
        authenticationParams = normalizeText(authenticationParams, "authenticationParams", false);
        if (authenticationPluginClassName.isPresent() != authenticationParams.isPresent()) {
            throw new IllegalArgumentException("authentication plugin class and parameters must be configured together");
        }
        operationTimeout = requireIntMillis(operationTimeout, "operationTimeout");
        if (ioThreads < 1) {
            throw new IllegalArgumentException("ioThreads must be positive");
        }
        if (listenerThreads < 1) {
            throw new IllegalArgumentException("listenerThreads must be positive");
        }
        this.serviceUrl = serviceUrl;
        this.authenticationPluginClassName = authenticationPluginClassName;
        this.authenticationParams = authenticationParams;
        this.operationTimeout = operationTimeout;
        this.ioThreads = ioThreads;
        this.listenerThreads = listenerThreads;
    }

    public String serviceUrl() {
        return serviceUrl;
    }

    public Optional<String> authenticationPluginClassName() {
        return authenticationPluginClassName;
    }

    public Optional<String> authenticationParams() {
        return authenticationParams;
    }

    public Duration operationTimeout() {
        return operationTimeout;
    }

    public int ioThreads() {
        return ioThreads;
    }

    public int listenerThreads() {
        return listenerThreads;
    }

    static PulsarClient3Configuration defaults() {
        return new PulsarClient3Configuration("pulsar://127.0.0.1:6650", Optional.empty(), Optional.empty(), Duration.ofSeconds(30), 1, 1);
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private String serviceUrl;

        private Optional<String> authenticationPluginClassName;

        private Optional<String> authenticationParams;

        private Duration operationTimeout;

        private int ioThreads;

        private int listenerThreads;

        private Builder(PulsarClient3Configuration source) {
            serviceUrl = source.serviceUrl();
            authenticationPluginClassName = source.authenticationPluginClassName();
            authenticationParams = source.authenticationParams();
            operationTimeout = source.operationTimeout();
            ioThreads = source.ioThreads();
            listenerThreads = source.listenerThreads();
        }

        public Builder serviceUrl(String value) {
            serviceUrl = value;
            return this;
        }

        public Builder authentication(String pluginClassName, String params) {
            authenticationPluginClassName = Optional.of(pluginClassName);
            authenticationParams = Optional.of(params);
            return this;
        }

        public Builder clearAuthentication() {
            authenticationPluginClassName = Optional.empty();
            authenticationParams = Optional.empty();
            return this;
        }

        public Builder operationTimeout(Duration value) {
            operationTimeout = value;
            return this;
        }

        public Builder ioThreads(int value) {
            ioThreads = value;
            return this;
        }

        public Builder listenerThreads(int value) {
            listenerThreads = value;
            return this;
        }

        public PulsarClient3Configuration build() {
            return new PulsarClient3Configuration(serviceUrl, authenticationPluginClassName, authenticationParams, operationTimeout, ioThreads, listenerThreads);
        }
    }

    @Override
    public String toString() {
        return "PulsarClient3Configuration[serviceUrl=" + serviceUrl + ", authenticationPluginClassName=" + authenticationPluginClassName + ", authenticationParams=" + (authenticationParams.isPresent() ? "[REDACTED]" : "empty") + ", operationTimeout=" + operationTimeout + ", ioThreads=" + ioThreads + ", listenerThreads=" + listenerThreads + ']';
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static Optional<String> normalizeText(Optional<String> value, String field, boolean trim) {
        return Objects.requireNonNull(value, field).map(item -> {
            String required = Objects.requireNonNull(item, field);
            if (required.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return trim ? required.trim() : required;
        });
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
        if (!(other instanceof PulsarClient3Configuration that))
            return false;
        return java.util.Objects.equals(serviceUrl, that.serviceUrl) && java.util.Objects.equals(authenticationPluginClassName, that.authenticationPluginClassName) && java.util.Objects.equals(authenticationParams, that.authenticationParams) && java.util.Objects.equals(operationTimeout, that.operationTimeout) && ioThreads == that.ioThreads && listenerThreads == that.listenerThreads;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(serviceUrl, authenticationPluginClassName, authenticationParams, operationTimeout, ioThreads, listenerThreads);
    }
}
