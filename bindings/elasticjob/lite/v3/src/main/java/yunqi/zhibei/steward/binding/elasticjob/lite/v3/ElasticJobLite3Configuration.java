package yunqi.zhibei.steward.binding.elasticjob.lite.v3;

import java.util.Objects;
import java.util.Optional;

public final class ElasticJobLite3Configuration {

    private final String serverLists;

    private final String namespace;

    private final int baseSleepTimeMilliseconds;

    private final int maxSleepTimeMilliseconds;

    private final int maxRetries;

    private final int sessionTimeoutMilliseconds;

    private final int connectionTimeoutMilliseconds;

    private final Optional<String> digest;

    ElasticJobLite3Configuration(String serverLists, String namespace, int baseSleepTimeMilliseconds, int maxSleepTimeMilliseconds, int maxRetries, int sessionTimeoutMilliseconds, int connectionTimeoutMilliseconds, Optional<String> digest) {
        serverLists = requireText(serverLists, "serverLists");
        namespace = requireText(namespace, "namespace");
        baseSleepTimeMilliseconds = requirePositive(baseSleepTimeMilliseconds, "baseSleepTimeMilliseconds");
        maxSleepTimeMilliseconds = requirePositive(maxSleepTimeMilliseconds, "maxSleepTimeMilliseconds");
        if (maxSleepTimeMilliseconds < baseSleepTimeMilliseconds) {
            throw new IllegalArgumentException("maxSleepTimeMilliseconds must be at least baseSleepTimeMilliseconds");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        sessionTimeoutMilliseconds = requirePositive(sessionTimeoutMilliseconds, "sessionTimeoutMilliseconds");
        connectionTimeoutMilliseconds = requirePositive(connectionTimeoutMilliseconds, "connectionTimeoutMilliseconds");
        digest = Objects.requireNonNull(digest, "digest").map(value -> requireText(value, "digest"));
        this.serverLists = serverLists;
        this.namespace = namespace;
        this.baseSleepTimeMilliseconds = baseSleepTimeMilliseconds;
        this.maxSleepTimeMilliseconds = maxSleepTimeMilliseconds;
        this.maxRetries = maxRetries;
        this.sessionTimeoutMilliseconds = sessionTimeoutMilliseconds;
        this.connectionTimeoutMilliseconds = connectionTimeoutMilliseconds;
        this.digest = digest;
    }

    public String serverLists() {
        return serverLists;
    }

    public String namespace() {
        return namespace;
    }

    public int baseSleepTimeMilliseconds() {
        return baseSleepTimeMilliseconds;
    }

    public int maxSleepTimeMilliseconds() {
        return maxSleepTimeMilliseconds;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public int sessionTimeoutMilliseconds() {
        return sessionTimeoutMilliseconds;
    }

    public int connectionTimeoutMilliseconds() {
        return connectionTimeoutMilliseconds;
    }

    public Optional<String> digest() {
        return digest;
    }

    static ElasticJobLite3Configuration defaults() {
        return new ElasticJobLite3Configuration("127.0.0.1:2181", "yunqi-steward", 1_000, 3_000, 3, 60_000, 15_000, Optional.empty());
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private String serverLists;

        private String namespace;

        private int baseSleepTimeMilliseconds;

        private int maxSleepTimeMilliseconds;

        private int maxRetries;

        private int sessionTimeoutMilliseconds;

        private int connectionTimeoutMilliseconds;

        private Optional<String> digest;

        private Builder(ElasticJobLite3Configuration source) {
            serverLists = source.serverLists();
            namespace = source.namespace();
            baseSleepTimeMilliseconds = source.baseSleepTimeMilliseconds();
            maxSleepTimeMilliseconds = source.maxSleepTimeMilliseconds();
            maxRetries = source.maxRetries();
            sessionTimeoutMilliseconds = source.sessionTimeoutMilliseconds();
            connectionTimeoutMilliseconds = source.connectionTimeoutMilliseconds();
            digest = source.digest();
        }

        public Builder serverLists(String value) {
            serverLists = value;
            return this;
        }

        public Builder namespace(String value) {
            namespace = value;
            return this;
        }

        public Builder baseSleepTimeMilliseconds(int value) {
            baseSleepTimeMilliseconds = value;
            return this;
        }

        public Builder maxSleepTimeMilliseconds(int value) {
            maxSleepTimeMilliseconds = value;
            return this;
        }

        public Builder maxRetries(int value) {
            maxRetries = value;
            return this;
        }

        public Builder sessionTimeoutMilliseconds(int value) {
            sessionTimeoutMilliseconds = value;
            return this;
        }

        public Builder connectionTimeoutMilliseconds(int value) {
            connectionTimeoutMilliseconds = value;
            return this;
        }

        public Builder digest(String value) {
            digest = Optional.of(value);
            return this;
        }

        public Builder clearDigest() {
            digest = Optional.empty();
            return this;
        }

        public ElasticJobLite3Configuration build() {
            return new ElasticJobLite3Configuration(serverLists, namespace, baseSleepTimeMilliseconds, maxSleepTimeMilliseconds, maxRetries, sessionTimeoutMilliseconds, connectionTimeoutMilliseconds, digest);
        }
    }

    @Override
    public String toString() {
        return "ElasticJobLite3Configuration[serverLists=" + serverLists + ", namespace=" + namespace + ", baseSleepTimeMilliseconds=" + baseSleepTimeMilliseconds + ", maxSleepTimeMilliseconds=" + maxSleepTimeMilliseconds + ", maxRetries=" + maxRetries + ", sessionTimeoutMilliseconds=" + sessionTimeoutMilliseconds + ", connectionTimeoutMilliseconds=" + connectionTimeoutMilliseconds + ", digest=" + (digest.isPresent() ? "[REDACTED]" : "empty") + "]";
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static int requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof ElasticJobLite3Configuration that))
            return false;
        return java.util.Objects.equals(serverLists, that.serverLists) && java.util.Objects.equals(namespace, that.namespace) && baseSleepTimeMilliseconds == that.baseSleepTimeMilliseconds && maxSleepTimeMilliseconds == that.maxSleepTimeMilliseconds && maxRetries == that.maxRetries && sessionTimeoutMilliseconds == that.sessionTimeoutMilliseconds && connectionTimeoutMilliseconds == that.connectionTimeoutMilliseconds && java.util.Objects.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(serverLists, namespace, baseSleepTimeMilliseconds, maxSleepTimeMilliseconds, maxRetries, sessionTimeoutMilliseconds, connectionTimeoutMilliseconds, digest);
    }
}
