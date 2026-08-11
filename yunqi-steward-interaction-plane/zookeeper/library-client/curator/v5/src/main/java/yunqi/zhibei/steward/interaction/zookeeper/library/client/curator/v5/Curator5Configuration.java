package yunqi.zhibei.steward.interaction.zookeeper.library.client.curator.v5;

import java.util.Objects;
import java.util.Optional;

public final class Curator5Configuration {

    private final String connectString;

    private final Optional<String> namespace;

    private final int sessionTimeoutMillis;

    private final int connectionTimeoutMillis;

    private final int retryBaseSleepMillis;

    private final int retryMaxRetries;

    Curator5Configuration(String connectString, Optional<String> namespace, int sessionTimeoutMillis, int connectionTimeoutMillis, int retryBaseSleepMillis, int retryMaxRetries) {
        connectString = requireText(connectString, "connectString");
        namespace = Objects.requireNonNull(namespace, "namespace").map(value -> requireText(value, "namespace"));
        sessionTimeoutMillis = requirePositive(sessionTimeoutMillis, "sessionTimeoutMillis");
        connectionTimeoutMillis = requirePositive(connectionTimeoutMillis, "connectionTimeoutMillis");
        retryBaseSleepMillis = requirePositive(retryBaseSleepMillis, "retryBaseSleepMillis");
        if (retryMaxRetries < 0) {
            throw new IllegalArgumentException("retryMaxRetries must not be negative");
        }
        this.connectString = connectString;
        this.namespace = namespace;
        this.sessionTimeoutMillis = sessionTimeoutMillis;
        this.connectionTimeoutMillis = connectionTimeoutMillis;
        this.retryBaseSleepMillis = retryBaseSleepMillis;
        this.retryMaxRetries = retryMaxRetries;
    }

    public String connectString() {
        return connectString;
    }

    public Optional<String> namespace() {
        return namespace;
    }

    public int sessionTimeoutMillis() {
        return sessionTimeoutMillis;
    }

    public int connectionTimeoutMillis() {
        return connectionTimeoutMillis;
    }

    public int retryBaseSleepMillis() {
        return retryBaseSleepMillis;
    }

    public int retryMaxRetries() {
        return retryMaxRetries;
    }

    static Curator5Configuration defaults() {
        return new Curator5Configuration("127.0.0.1:2181", Optional.empty(), 60_000, 15_000, 1_000, 3);
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private String connectString;

        private Optional<String> namespace;

        private int sessionTimeoutMillis;

        private int connectionTimeoutMillis;

        private int retryBaseSleepMillis;

        private int retryMaxRetries;

        private Builder(Curator5Configuration source) {
            connectString = source.connectString();
            namespace = source.namespace();
            sessionTimeoutMillis = source.sessionTimeoutMillis();
            connectionTimeoutMillis = source.connectionTimeoutMillis();
            retryBaseSleepMillis = source.retryBaseSleepMillis();
            retryMaxRetries = source.retryMaxRetries();
        }

        public Builder connectString(String value) {
            connectString = value;
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

        public Builder sessionTimeoutMillis(int value) {
            sessionTimeoutMillis = value;
            return this;
        }

        public Builder connectionTimeoutMillis(int value) {
            connectionTimeoutMillis = value;
            return this;
        }

        public Builder retryBaseSleepMillis(int value) {
            retryBaseSleepMillis = value;
            return this;
        }

        public Builder retryMaxRetries(int value) {
            retryMaxRetries = value;
            return this;
        }

        public Curator5Configuration build() {
            return new Curator5Configuration(connectString, namespace, sessionTimeoutMillis, connectionTimeoutMillis, retryBaseSleepMillis, retryMaxRetries);
        }
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
        if (!(other instanceof Curator5Configuration that))
            return false;
        return java.util.Objects.equals(connectString, that.connectString) && java.util.Objects.equals(namespace, that.namespace) && sessionTimeoutMillis == that.sessionTimeoutMillis && connectionTimeoutMillis == that.connectionTimeoutMillis && retryBaseSleepMillis == that.retryBaseSleepMillis && retryMaxRetries == that.retryMaxRetries;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(connectString, namespace, sessionTimeoutMillis, connectionTimeoutMillis, retryBaseSleepMillis, retryMaxRetries);
    }

    @Override
    public String toString() {
        return "Curator5Configuration[connectString=" + connectString + ", namespace=" + namespace + ", sessionTimeoutMillis=" + sessionTimeoutMillis + ", connectionTimeoutMillis=" + connectionTimeoutMillis + ", retryBaseSleepMillis=" + retryBaseSleepMillis + ", retryMaxRetries=" + retryMaxRetries + "]";
    }
}
