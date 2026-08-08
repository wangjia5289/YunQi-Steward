package yunqi.zhibei.steward.binding.mongodb.sync.v5;

import com.mongodb.ConnectionString;
import java.util.Objects;

/**
 * Configuration that belongs to one native MongoDB synchronous client.
 */
public final class MongoDbSync5Configuration {

    private final String connectionString;

    MongoDbSync5Configuration(String connectionString) {
        connectionString = requireText(connectionString, "connectionString");
        try {
            new ConnectionString(connectionString);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "connectionString must be a valid MongoDB connection string");
        }
        this.connectionString = connectionString;
    }

    public String connectionString() {
        return connectionString;
    }

    static MongoDbSync5Configuration defaults() {
        return new MongoDbSync5Configuration("mongodb://127.0.0.1:27017");
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private String connectionString;

        private Builder(MongoDbSync5Configuration source) {
            connectionString = source.connectionString();
        }

        public Builder connectionString(String connectionString) {
            this.connectionString = connectionString;
            return this;
        }

        public MongoDbSync5Configuration build() {
            return new MongoDbSync5Configuration(connectionString);
        }
    }

    @Override
    public String toString() {
        return "MongoDbSync5Configuration[connectionString=[REDACTED]]";
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
        if (!(other instanceof MongoDbSync5Configuration that))
            return false;
        return java.util.Objects.equals(connectionString, that.connectionString);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(connectionString);
    }
}
