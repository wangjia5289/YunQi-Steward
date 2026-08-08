package yunqi.zhibei.steward.binding.etcd.jetcd.v0;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class Jetcd0Configuration {

    private final List<URI> endpoints;

    private final Optional<String> username;

    private final Optional<String> password;

    private final Optional<String> namespace;

    private final Optional<String> authority;

    private final boolean tlsEnabled;

    private final Duration connectTimeout;

    private final int retryDelayMillis;

    private final int retryMaxDelayMillis;

    private final int retryMaxAttempts;

    private final Duration keepaliveTime;

    private final Duration keepaliveTimeout;

    private final boolean keepaliveWithoutCalls;

    private final int maxInboundMessageSize;

    Jetcd0Configuration(List<URI> endpoints, Optional<String> username, Optional<String> password, Optional<String> namespace, Optional<String> authority, boolean tlsEnabled, Duration connectTimeout, int retryDelayMillis, int retryMaxDelayMillis, int retryMaxAttempts, Duration keepaliveTime, Duration keepaliveTimeout, boolean keepaliveWithoutCalls, int maxInboundMessageSize) {
        endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints must not be empty");
        }
        endpoints.forEach(endpoint -> validateEndpoint(endpoint, tlsEnabled));
        username = requireOptionalText(username, "username");
        password = requireOptionalText(password, "password");
        if (username.isPresent() != password.isPresent()) {
            throw new IllegalArgumentException("username and password must be configured together");
        }
        namespace = requireOptionalText(namespace, "namespace");
        authority = requireOptionalText(authority, "authority");
        connectTimeout = requirePositiveDuration(connectTimeout, "connectTimeout");
        if (retryDelayMillis < 0) {
            throw new IllegalArgumentException("retryDelayMillis must not be negative");
        }
        if (retryMaxDelayMillis < retryDelayMillis) {
            throw new IllegalArgumentException("retryMaxDelayMillis must be at least retryDelayMillis");
        }
        if (retryMaxAttempts < 0) {
            throw new IllegalArgumentException("retryMaxAttempts must not be negative");
        }
        keepaliveTime = requirePositiveDuration(keepaliveTime, "keepaliveTime");
        keepaliveTimeout = requirePositiveDuration(keepaliveTimeout, "keepaliveTimeout");
        if (maxInboundMessageSize < 1) {
            throw new IllegalArgumentException("maxInboundMessageSize must be positive");
        }
        this.endpoints = endpoints;
        this.username = username;
        this.password = password;
        this.namespace = namespace;
        this.authority = authority;
        this.tlsEnabled = tlsEnabled;
        this.connectTimeout = connectTimeout;
        this.retryDelayMillis = retryDelayMillis;
        this.retryMaxDelayMillis = retryMaxDelayMillis;
        this.retryMaxAttempts = retryMaxAttempts;
        this.keepaliveTime = keepaliveTime;
        this.keepaliveTimeout = keepaliveTimeout;
        this.keepaliveWithoutCalls = keepaliveWithoutCalls;
        this.maxInboundMessageSize = maxInboundMessageSize;
    }

    public List<URI> endpoints() {
        return endpoints;
    }

    public Optional<String> username() {
        return username;
    }

    public Optional<String> password() {
        return password;
    }

    public Optional<String> namespace() {
        return namespace;
    }

    public Optional<String> authority() {
        return authority;
    }

    public boolean tlsEnabled() {
        return tlsEnabled;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public int retryDelayMillis() {
        return retryDelayMillis;
    }

    public int retryMaxDelayMillis() {
        return retryMaxDelayMillis;
    }

    public int retryMaxAttempts() {
        return retryMaxAttempts;
    }

    public Duration keepaliveTime() {
        return keepaliveTime;
    }

    public Duration keepaliveTimeout() {
        return keepaliveTimeout;
    }

    public boolean keepaliveWithoutCalls() {
        return keepaliveWithoutCalls;
    }

    public int maxInboundMessageSize() {
        return maxInboundMessageSize;
    }

    static Jetcd0Configuration defaults() {
        return new Jetcd0Configuration(List.of(URI.create("http://127.0.0.1:2379")), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, Duration.ofSeconds(5), 500, 2_500, 2, Duration.ofSeconds(30), Duration.ofSeconds(10), true, 4 * 1024 * 1024);
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private List<URI> endpoints;

        private Optional<String> username;

        private Optional<String> password;

        private Optional<String> namespace;

        private Optional<String> authority;

        private boolean tlsEnabled;

        private Duration connectTimeout;

        private int retryDelayMillis;

        private int retryMaxDelayMillis;

        private int retryMaxAttempts;

        private Duration keepaliveTime;

        private Duration keepaliveTimeout;

        private boolean keepaliveWithoutCalls;

        private int maxInboundMessageSize;

        private Builder(Jetcd0Configuration source) {
            endpoints = source.endpoints();
            username = source.username();
            password = source.password();
            namespace = source.namespace();
            authority = source.authority();
            tlsEnabled = source.tlsEnabled();
            connectTimeout = source.connectTimeout();
            retryDelayMillis = source.retryDelayMillis();
            retryMaxDelayMillis = source.retryMaxDelayMillis();
            retryMaxAttempts = source.retryMaxAttempts();
            keepaliveTime = source.keepaliveTime();
            keepaliveTimeout = source.keepaliveTimeout();
            keepaliveWithoutCalls = source.keepaliveWithoutCalls();
            maxInboundMessageSize = source.maxInboundMessageSize();
        }

        public Builder endpoint(String value) {
            endpoints = List.of(parseUri(value, "endpoint"));
            return this;
        }

        public Builder endpoints(List<URI> values) {
            endpoints = List.copyOf(values);
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

        public Builder namespace(String value) {
            namespace = Optional.of(value);
            return this;
        }

        public Builder clearNamespace() {
            namespace = Optional.empty();
            return this;
        }

        public Builder authority(String value) {
            authority = Optional.of(value);
            return this;
        }

        public Builder clearAuthority() {
            authority = Optional.empty();
            return this;
        }

        public Builder tlsEnabled(boolean value) {
            tlsEnabled = value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            connectTimeout = value;
            return this;
        }

        public Builder retryDelayMillis(int value) {
            retryDelayMillis = value;
            return this;
        }

        public Builder retryMaxDelayMillis(int value) {
            retryMaxDelayMillis = value;
            return this;
        }

        public Builder retryMaxAttempts(int value) {
            retryMaxAttempts = value;
            return this;
        }

        public Builder keepaliveTime(Duration value) {
            keepaliveTime = value;
            return this;
        }

        public Builder keepaliveTimeout(Duration value) {
            keepaliveTimeout = value;
            return this;
        }

        public Builder keepaliveWithoutCalls(boolean value) {
            keepaliveWithoutCalls = value;
            return this;
        }

        public Builder maxInboundMessageSize(int value) {
            maxInboundMessageSize = value;
            return this;
        }

        public Jetcd0Configuration build() {
            return new Jetcd0Configuration(endpoints, username, password, namespace, authority, tlsEnabled, connectTimeout, retryDelayMillis, retryMaxDelayMillis, retryMaxAttempts, keepaliveTime, keepaliveTimeout, keepaliveWithoutCalls, maxInboundMessageSize);
        }
    }

    @Override
    public String toString() {
        return "Jetcd0Configuration[endpoints=" + endpoints + ", username=" + username + ", password=" + (password.isPresent() ? "[REDACTED]" : "empty") + ", namespace=" + namespace + ", authority=" + authority + ", tlsEnabled=" + tlsEnabled + ", connectTimeout=" + connectTimeout + ", retryDelayMillis=" + retryDelayMillis + ", retryMaxDelayMillis=" + retryMaxDelayMillis + ", retryMaxAttempts=" + retryMaxAttempts + ", keepaliveTime=" + keepaliveTime + ", keepaliveTimeout=" + keepaliveTimeout + ", keepaliveWithoutCalls=" + keepaliveWithoutCalls + ", maxInboundMessageSize=" + maxInboundMessageSize + "]";
    }

    private static void validateEndpoint(URI endpoint, boolean tlsEnabled) {
        URI value = Objects.requireNonNull(endpoint, "endpoint");
        String expectedScheme = tlsEnabled ? "https" : "http";
        if (value.getScheme() == null || !expectedScheme.equals(value.getScheme().toLowerCase(Locale.ROOT)) || value.getHost() == null || value.getPort() < 1 || value.getPort() > 65_535) {
            throw new IllegalArgumentException("endpoint must be an " + expectedScheme + " URI with an explicit port");
        }
        if (value.getUserInfo() != null) {
            throw new IllegalArgumentException("endpoint must not contain credentials");
        }
    }

    private static URI parseUri(String value, String field) {
        Objects.requireNonNull(value, field);
        try {
            return URI.create(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " must be a valid URI");
        }
    }

    private static Optional<String> requireOptionalText(Optional<String> value, String field) {
        return Objects.requireNonNull(value, field).map(item -> {
            if (item.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return item;
        });
    }

    private static Duration requirePositiveDuration(Duration value, String field) {
        Duration duration = Objects.requireNonNull(value, field);
        try {
            if (duration.toMillis() < 1) {
                throw new IllegalArgumentException(field + " must be at least 1 millisecond");
            }
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + " is too large", failure);
        }
        return duration;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Jetcd0Configuration that))
            return false;
        return java.util.Objects.equals(endpoints, that.endpoints) && java.util.Objects.equals(username, that.username) && java.util.Objects.equals(password, that.password) && java.util.Objects.equals(namespace, that.namespace) && java.util.Objects.equals(authority, that.authority) && tlsEnabled == that.tlsEnabled && java.util.Objects.equals(connectTimeout, that.connectTimeout) && retryDelayMillis == that.retryDelayMillis && retryMaxDelayMillis == that.retryMaxDelayMillis && retryMaxAttempts == that.retryMaxAttempts && java.util.Objects.equals(keepaliveTime, that.keepaliveTime) && java.util.Objects.equals(keepaliveTimeout, that.keepaliveTimeout) && keepaliveWithoutCalls == that.keepaliveWithoutCalls && maxInboundMessageSize == that.maxInboundMessageSize;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(endpoints, username, password, namespace, authority, tlsEnabled, connectTimeout, retryDelayMillis, retryMaxDelayMillis, retryMaxAttempts, keepaliveTime, keepaliveTimeout, keepaliveWithoutCalls, maxInboundMessageSize);
    }
}
