package yunqi.zhibei.steward.binding.elasticsearch.java.v9;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class ElasticsearchJava9Configuration {

    private final List<URI> endpoints;

    private final Optional<String> username;

    private final Optional<String> password;

    private final Optional<String> apiKey;

    private final Optional<String> certificateFingerprint;

    private final Duration connectTimeout;

    private final Duration socketTimeout;

    private final Duration requestTimeout;

    private final Pool pool;

    ElasticsearchJava9Configuration(List<URI> endpoints, Optional<String> username, Optional<String> password, Optional<String> apiKey, Optional<String> certificateFingerprint, Duration connectTimeout, Duration socketTimeout, Duration requestTimeout, Pool pool) {
        Objects.requireNonNull(endpoints, "endpoints");
        endpoints = endpoints.stream().map(ElasticsearchJava9Configuration::requireEndpoint).toList();
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints must not be empty");
        }
        username = Objects.requireNonNull(username, "username");
        password = Objects.requireNonNull(password, "password");
        apiKey = Objects.requireNonNull(apiKey, "apiKey");
        certificateFingerprint = normalizeFingerprint(certificateFingerprint);
        if (username.isPresent() != password.isPresent()) {
            throw new IllegalArgumentException("username and password must either both be present or both be absent");
        }
        if (apiKey.isPresent() && username.isPresent()) {
            throw new IllegalArgumentException("apiKey and username/password are mutually exclusive");
        }
        if (certificateFingerprint.isPresent() && endpoints.stream().noneMatch(endpoint -> "https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("certificateFingerprint requires an HTTPS endpoint");
        }
        connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        socketTimeout = requirePositive(socketTimeout, "socketTimeout");
        requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        pool = Objects.requireNonNull(pool, "pool");
        this.endpoints = endpoints;
        this.username = username;
        this.password = password;
        this.apiKey = apiKey;
        this.certificateFingerprint = certificateFingerprint;
        this.connectTimeout = connectTimeout;
        this.socketTimeout = socketTimeout;
        this.requestTimeout = requestTimeout;
        this.pool = pool;
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

    public Optional<String> apiKey() {
        return apiKey;
    }

    public Optional<String> certificateFingerprint() {
        return certificateFingerprint;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration socketTimeout() {
        return socketTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Pool pool() {
        return pool;
    }

    static ElasticsearchJava9Configuration defaults() {
        return new ElasticsearchJava9Configuration(List.of(URI.create("http://127.0.0.1:9200")), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(30), new Pool(50, 25, Duration.ofMinutes(5)));
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

        private Optional<String> apiKey;

        private Optional<String> certificateFingerprint;

        private Duration connectTimeout;

        private Duration socketTimeout;

        private Duration requestTimeout;

        private Pool pool;

        private Builder(ElasticsearchJava9Configuration source) {
            endpoints = source.endpoints();
            username = source.username();
            password = source.password();
            apiKey = source.apiKey();
            certificateFingerprint = source.certificateFingerprint();
            connectTimeout = source.connectTimeout();
            socketTimeout = source.socketTimeout();
            requestTimeout = source.requestTimeout();
            pool = source.pool();
        }

        public Builder endpoint(String value) {
            endpoints = List.of(parseUri(value, "endpoint"));
            return this;
        }

        public Builder endpoints(List<URI> values) {
            endpoints = List.copyOf(values);
            return this;
        }

        public Builder basicAuthentication(String user, String secret) {
            username = Optional.of(user);
            password = Optional.of(secret);
            apiKey = Optional.empty();
            return this;
        }

        public Builder apiKey(String value) {
            apiKey = Optional.of(value);
            username = Optional.empty();
            password = Optional.empty();
            return this;
        }

        public Builder clearAuthentication() {
            username = Optional.empty();
            password = Optional.empty();
            apiKey = Optional.empty();
            return this;
        }

        public Builder certificateFingerprint(String value) {
            certificateFingerprint = Optional.of(value);
            return this;
        }

        public Builder clearCertificateFingerprint() {
            certificateFingerprint = Optional.empty();
            return this;
        }

        public Builder connectTimeout(Duration value) {
            connectTimeout = value;
            return this;
        }

        public Builder socketTimeout(Duration value) {
            socketTimeout = value;
            return this;
        }

        public Builder requestTimeout(Duration value) {
            requestTimeout = value;
            return this;
        }

        public Builder pool(Pool value) {
            pool = value;
            return this;
        }

        public ElasticsearchJava9Configuration build() {
            return new ElasticsearchJava9Configuration(endpoints, username, password, apiKey, certificateFingerprint, connectTimeout, socketTimeout, requestTimeout, pool);
        }
    }

    @Override
    public String toString() {
        return "ElasticsearchJava9Configuration[endpoints=" + endpoints + ", username=" + username + ", password=" + (password.isPresent() ? "[REDACTED]" : "empty") + ", apiKey=" + (apiKey.isPresent() ? "[REDACTED]" : "empty") + ", certificateFingerprint=" + certificateFingerprint + ", connectTimeout=" + connectTimeout + ", socketTimeout=" + socketTimeout + ", requestTimeout=" + requestTimeout + ", pool=" + pool + "]";
    }

    public record Pool(int maximumConnections, int maximumConnectionsPerRoute, Duration keepAlive) {

        public Pool {
            if (maximumConnections < 1) {
                throw new IllegalArgumentException("maximumConnections must be positive");
            }
            if (maximumConnectionsPerRoute < 1 || maximumConnectionsPerRoute > maximumConnections) {
                throw new IllegalArgumentException("maximumConnectionsPerRoute must be positive and not exceed maximumConnections");
            }
            keepAlive = requirePositive(keepAlive, "keepAlive");
        }
    }

    private static URI requireEndpoint(URI endpoint) {
        URI value = Objects.requireNonNull(endpoint, "endpoint");
        String scheme = value.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || value.getHost() == null || value.getUserInfo() != null || value.getPort() < -1 || value.getPort() > 65_535) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI without credentials");
        }
        return value;
    }

    private static URI parseUri(String value, String field) {
        Objects.requireNonNull(value, field);
        try {
            return URI.create(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " must be a valid URI");
        }
    }

    private static Optional<String> normalizeFingerprint(Optional<String> value) {
        return Objects.requireNonNull(value, "certificateFingerprint").map(fingerprint -> {
            String normalized = fingerprint.trim().replace(":", "");
            if (!normalized.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("certificateFingerprint must be a SHA-256 fingerprint");
            }
            return normalized.toLowerCase(Locale.ROOT);
        });
    }

    private static Duration requirePositive(Duration value, String field) {
        Duration duration = Objects.requireNonNull(value, field);
        if (duration.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException(field + " must be at least 1 millisecond");
        }
        duration.toMillis();
        return duration;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof ElasticsearchJava9Configuration that))
            return false;
        return java.util.Objects.equals(endpoints, that.endpoints) && java.util.Objects.equals(username, that.username) && java.util.Objects.equals(password, that.password) && java.util.Objects.equals(apiKey, that.apiKey) && java.util.Objects.equals(certificateFingerprint, that.certificateFingerprint) && java.util.Objects.equals(connectTimeout, that.connectTimeout) && java.util.Objects.equals(socketTimeout, that.socketTimeout) && java.util.Objects.equals(requestTimeout, that.requestTimeout) && java.util.Objects.equals(pool, that.pool);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(endpoints, username, password, apiKey, certificateFingerprint, connectTimeout, socketTimeout, requestTimeout, pool);
    }
}
