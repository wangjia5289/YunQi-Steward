package yunqi.zhibei.steward.binding.milvus.sdk.v2;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class MilvusSdk2Configuration {

    private final URI uri;

    private final Optional<String> token;

    private final String database;

    private final Duration connectTimeout;

    MilvusSdk2Configuration(URI uri, Optional<String> token, String database, Duration connectTimeout) {
        uri = requireUri(uri);
        token = Objects.requireNonNull(token, "token").map(value -> requireText(value, "token"));
        database = requireText(database, "database");
        connectTimeout = requirePositiveDuration(connectTimeout, "connectTimeout");
        this.uri = uri;
        this.token = token;
        this.database = database;
        this.connectTimeout = connectTimeout;
    }

    public URI uri() {
        return uri;
    }

    public Optional<String> token() {
        return token;
    }

    public String database() {
        return database;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    private static final Set<String> SUPPORTED_SCHEMES = Set.of("http", "https");

    static MilvusSdk2Configuration defaults() {
        return new MilvusSdk2Configuration(URI.create("http://127.0.0.1:19530"), Optional.empty(), "default", Duration.ofSeconds(10));
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private URI uri;

        private Optional<String> token;

        private String database;

        private Duration connectTimeout;

        private Builder(MilvusSdk2Configuration source) {
            uri = source.uri();
            token = source.token();
            database = source.database();
            connectTimeout = source.connectTimeout();
        }

        public Builder uri(String value) {
            uri = parseUri(value, "uri");
            return this;
        }

        public Builder uri(URI value) {
            uri = value;
            return this;
        }

        public Builder token(String value) {
            token = Optional.of(value);
            return this;
        }

        public Builder clearToken() {
            token = Optional.empty();
            return this;
        }

        public Builder database(String value) {
            database = value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            connectTimeout = value;
            return this;
        }

        public MilvusSdk2Configuration build() {
            return new MilvusSdk2Configuration(uri, token, database, connectTimeout);
        }
    }

    boolean secure() {
        return "https".equalsIgnoreCase(uri.getScheme());
    }

    @Override
    public String toString() {
        return "MilvusSdk2Configuration[uri=" + uri + ", token=" + (token.isPresent() ? "[REDACTED]" : "empty") + ", database=" + database + ", connectTimeout=" + connectTimeout + "]";
    }

    private static URI requireUri(URI value) {
        URI uri = Objects.requireNonNull(value, "uri");
        String scheme = uri.getScheme();
        if (scheme == null || !SUPPORTED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT)) || uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65_535) {
            throw new IllegalArgumentException("uri must use http or https and include a host and explicit port");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("uri must not contain credentials");
        }
        return uri;
    }

    private static URI parseUri(String value, String field) {
        Objects.requireNonNull(value, field);
        try {
            return URI.create(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " must be a valid URI");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
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
        if (!(other instanceof MilvusSdk2Configuration that))
            return false;
        return java.util.Objects.equals(uri, that.uri) && java.util.Objects.equals(token, that.token) && java.util.Objects.equals(database, that.database) && java.util.Objects.equals(connectTimeout, that.connectTimeout);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(uri, token, database, connectTimeout);
    }
}
