package yunqi.zhibei.steward.binding.minio.java.v8;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class MinioJava8Configuration {

    private final URI endpoint;

    private final Optional<String> accessKey;

    private final Optional<String> secretKey;

    private final Optional<String> sessionToken;

    private final Optional<String> region;

    private final Duration connectTimeout;

    private final Duration readTimeout;

    private final Duration writeTimeout;

    private final Pool pool;

    MinioJava8Configuration(URI endpoint, Optional<String> accessKey, Optional<String> secretKey, Optional<String> sessionToken, Optional<String> region, Duration connectTimeout, Duration readTimeout, Duration writeTimeout, Pool pool) {
        endpoint = requireEndpoint(endpoint);
        accessKey = Objects.requireNonNull(accessKey, "accessKey");
        secretKey = Objects.requireNonNull(secretKey, "secretKey");
        sessionToken = Objects.requireNonNull(sessionToken, "sessionToken");
        region = Objects.requireNonNull(region, "region");
        if (accessKey.isPresent() != secretKey.isPresent()) {
            throw new IllegalArgumentException("accessKey and secretKey must either both be present or both be absent");
        }
        if (sessionToken.isPresent() && accessKey.isEmpty()) {
            throw new IllegalArgumentException("sessionToken requires access credentials");
        }
        connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        readTimeout = requirePositive(readTimeout, "readTimeout");
        writeTimeout = requirePositive(writeTimeout, "writeTimeout");
        pool = Objects.requireNonNull(pool, "pool");
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.sessionToken = sessionToken;
        this.region = region;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.writeTimeout = writeTimeout;
        this.pool = pool;
    }

    public URI endpoint() {
        return endpoint;
    }

    public Optional<String> accessKey() {
        return accessKey;
    }

    public Optional<String> secretKey() {
        return secretKey;
    }

    public Optional<String> sessionToken() {
        return sessionToken;
    }

    public Optional<String> region() {
        return region;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    public Duration writeTimeout() {
        return writeTimeout;
    }

    public Pool pool() {
        return pool;
    }

    static MinioJava8Configuration defaults() {
        return new MinioJava8Configuration(URI.create("http://127.0.0.1:9000"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(30), new Pool(64, 16, Duration.ofMinutes(5)));
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private URI endpoint;

        private Optional<String> accessKey;

        private Optional<String> secretKey;

        private Optional<String> sessionToken;

        private Optional<String> region;

        private Duration connectTimeout;

        private Duration readTimeout;

        private Duration writeTimeout;

        private Pool pool;

        private Builder(MinioJava8Configuration source) {
            endpoint = source.endpoint();
            accessKey = source.accessKey();
            secretKey = source.secretKey();
            sessionToken = source.sessionToken();
            region = source.region();
            connectTimeout = source.connectTimeout();
            readTimeout = source.readTimeout();
            writeTimeout = source.writeTimeout();
            pool = source.pool();
        }

        public Builder endpoint(String value) {
            endpoint = parseUri(value, "endpoint");
            return this;
        }

        public Builder endpoint(URI value) {
            endpoint = value;
            return this;
        }

        public Builder credentials(String access, String secret) {
            accessKey = Optional.of(access);
            secretKey = Optional.of(secret);
            sessionToken = Optional.empty();
            return this;
        }

        public Builder credentials(String access, String secret, String session) {
            accessKey = Optional.of(access);
            secretKey = Optional.of(secret);
            sessionToken = Optional.of(session);
            return this;
        }

        public Builder clearCredentials() {
            accessKey = Optional.empty();
            secretKey = Optional.empty();
            sessionToken = Optional.empty();
            return this;
        }

        public Builder region(String value) {
            region = Optional.of(value);
            return this;
        }

        public Builder clearRegion() {
            region = Optional.empty();
            return this;
        }

        public Builder connectTimeout(Duration value) {
            connectTimeout = value;
            return this;
        }

        public Builder readTimeout(Duration value) {
            readTimeout = value;
            return this;
        }

        public Builder writeTimeout(Duration value) {
            writeTimeout = value;
            return this;
        }

        public Builder pool(Pool value) {
            pool = value;
            return this;
        }

        public MinioJava8Configuration build() {
            return new MinioJava8Configuration(endpoint, accessKey, secretKey, sessionToken, region, connectTimeout, readTimeout, writeTimeout, pool);
        }
    }

    @Override
    public String toString() {
        return "MinioJava8Configuration[endpoint=" + endpoint + ", accessKey=" + (accessKey.isPresent() ? "[PRESENT]" : "empty") + ", secretKey=" + (secretKey.isPresent() ? "[REDACTED]" : "empty") + ", sessionToken=" + (sessionToken.isPresent() ? "[REDACTED]" : "empty") + ", region=" + region + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + ", writeTimeout=" + writeTimeout + ", pool=" + pool + "]";
    }

    public record Pool(int maximumConnections, int maximumRequestsPerHost, Duration keepAlive) {

        public Pool {
            if (maximumConnections < 1) {
                throw new IllegalArgumentException("maximumConnections must be positive");
            }
            if (maximumRequestsPerHost < 1 || maximumRequestsPerHost > maximumConnections) {
                throw new IllegalArgumentException("maximumRequestsPerHost must be positive and not exceed maximumConnections");
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
        if (!(other instanceof MinioJava8Configuration that))
            return false;
        return java.util.Objects.equals(endpoint, that.endpoint) && java.util.Objects.equals(accessKey, that.accessKey) && java.util.Objects.equals(secretKey, that.secretKey) && java.util.Objects.equals(sessionToken, that.sessionToken) && java.util.Objects.equals(region, that.region) && java.util.Objects.equals(connectTimeout, that.connectTimeout) && java.util.Objects.equals(readTimeout, that.readTimeout) && java.util.Objects.equals(writeTimeout, that.writeTimeout) && java.util.Objects.equals(pool, that.pool);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(endpoint, accessKey, secretKey, sessionToken, region, connectTimeout, readTimeout, writeTimeout, pool);
    }
}
