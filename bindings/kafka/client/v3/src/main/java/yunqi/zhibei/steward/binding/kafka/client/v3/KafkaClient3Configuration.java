package yunqi.zhibei.steward.binding.kafka.client.v3;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration that belongs to one native Kafka 3 producer.
 */
public final class KafkaClient3Configuration {

    private final String bootstrapServers;

    private final String clientId;

    private final String acks;

    private final int retries;

    private final Duration requestTimeout;

    private final Duration deliveryTimeout;

    private final Duration linger;

    private final int batchSize;

    private final String compressionType;

    KafkaClient3Configuration(String bootstrapServers, String clientId, String acks, int retries, Duration requestTimeout, Duration deliveryTimeout, Duration linger, int batchSize, String compressionType) {
        bootstrapServers = requireText(bootstrapServers, "bootstrapServers");
        clientId = requireText(clientId, "clientId");
        acks = requireText(acks, "acks").toLowerCase(Locale.ROOT);
        if (!ACK_VALUES.contains(acks)) {
            throw new IllegalArgumentException("unsupported acks: " + acks);
        }
        if (retries < 0) {
            throw new IllegalArgumentException("retries must not be negative");
        }
        requestTimeout = requireIntMillis(requestTimeout, "requestTimeout", false);
        deliveryTimeout = requireIntMillis(deliveryTimeout, "deliveryTimeout", false);
        linger = requireIntMillis(linger, "linger", true);
        Duration minimumDeliveryTimeout;
        try {
            minimumDeliveryTimeout = requestTimeout.plus(linger);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("requestTimeout plus linger is too large", failure);
        }
        if (deliveryTimeout.compareTo(minimumDeliveryTimeout) < 0) {
            throw new IllegalArgumentException("deliveryTimeout must not be shorter than requestTimeout plus linger");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        compressionType = requireText(compressionType, "compressionType").toLowerCase(Locale.ROOT);
        if (!COMPRESSION_TYPES.contains(compressionType)) {
            throw new IllegalArgumentException("unsupported compressionType: " + compressionType);
        }
        this.bootstrapServers = bootstrapServers;
        this.clientId = clientId;
        this.acks = acks;
        this.retries = retries;
        this.requestTimeout = requestTimeout;
        this.deliveryTimeout = deliveryTimeout;
        this.linger = linger;
        this.batchSize = batchSize;
        this.compressionType = compressionType;
    }

    public String bootstrapServers() {
        return bootstrapServers;
    }

    public String clientId() {
        return clientId;
    }

    public String acks() {
        return acks;
    }

    public int retries() {
        return retries;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration deliveryTimeout() {
        return deliveryTimeout;
    }

    public Duration linger() {
        return linger;
    }

    public int batchSize() {
        return batchSize;
    }

    public String compressionType() {
        return compressionType;
    }

    private static final Set<String> COMPRESSION_TYPES = Set.of("none", "gzip", "snappy", "lz4", "zstd");

    private static final Set<String> ACK_VALUES = Set.of("all", "-1", "0", "1");

    static KafkaClient3Configuration defaults() {
        return new KafkaClient3Configuration("127.0.0.1:9092", "yunqi-steward", "all", 3, Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ZERO, 16_384, "none");
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private String bootstrapServers;

        private String clientId;

        private String acks;

        private int retries;

        private Duration requestTimeout;

        private Duration deliveryTimeout;

        private Duration linger;

        private int batchSize;

        private String compressionType;

        private Builder(KafkaClient3Configuration source) {
            bootstrapServers = source.bootstrapServers();
            clientId = source.clientId();
            acks = source.acks();
            retries = source.retries();
            requestTimeout = source.requestTimeout();
            deliveryTimeout = source.deliveryTimeout();
            linger = source.linger();
            batchSize = source.batchSize();
            compressionType = source.compressionType();
        }

        public Builder bootstrapServers(String value) {
            bootstrapServers = value;
            return this;
        }

        public Builder clientId(String value) {
            clientId = value;
            return this;
        }

        public Builder acks(String value) {
            acks = value;
            return this;
        }

        public Builder retries(int value) {
            retries = value;
            return this;
        }

        public Builder requestTimeout(Duration value) {
            requestTimeout = value;
            return this;
        }

        public Builder deliveryTimeout(Duration value) {
            deliveryTimeout = value;
            return this;
        }

        public Builder linger(Duration value) {
            linger = value;
            return this;
        }

        public Builder batchSize(int value) {
            batchSize = value;
            return this;
        }

        public Builder compressionType(String value) {
            compressionType = value;
            return this;
        }

        public KafkaClient3Configuration build() {
            return new KafkaClient3Configuration(bootstrapServers, clientId, acks, retries, requestTimeout, deliveryTimeout, linger, batchSize, compressionType);
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static Duration requireIntMillis(Duration value, String field, boolean allowZero) {
        Duration duration = Objects.requireNonNull(value, field);
        if (duration.isNegative() || (!allowZero && duration.isZero())) {
            throw new IllegalArgumentException(field + (allowZero ? " must not be negative" : " must be positive"));
        }
        long milliseconds;
        try {
            milliseconds = duration.toMillis();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + " is too large", failure);
        }
        if ((!allowZero && milliseconds < 1) || milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must be between " + (allowZero ? 0 : 1) + " and " + Integer.MAX_VALUE + " milliseconds");
        }
        return duration;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof KafkaClient3Configuration that))
            return false;
        return java.util.Objects.equals(bootstrapServers, that.bootstrapServers) && java.util.Objects.equals(clientId, that.clientId) && java.util.Objects.equals(acks, that.acks) && retries == that.retries && java.util.Objects.equals(requestTimeout, that.requestTimeout) && java.util.Objects.equals(deliveryTimeout, that.deliveryTimeout) && java.util.Objects.equals(linger, that.linger) && batchSize == that.batchSize && java.util.Objects.equals(compressionType, that.compressionType);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(bootstrapServers, clientId, acks, retries, requestTimeout, deliveryTimeout, linger, batchSize, compressionType);
    }

    @Override
    public String toString() {
        return "KafkaClient3Configuration[bootstrapServers=" + bootstrapServers + ", clientId=" + clientId + ", acks=" + acks + ", retries=" + retries + ", requestTimeout=" + requestTimeout + ", deliveryTimeout=" + deliveryTimeout + ", linger=" + linger + ", batchSize=" + batchSize + ", compressionType=" + compressionType + "]";
    }
}
