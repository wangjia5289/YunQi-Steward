package yunqi.zhibei.steward.interaction.rocketmq.library.client.rocketmq.client.v5;

import java.time.Duration;
import java.util.Objects;

public final class RocketMq5Configuration {

    private final String nameServerAddress;

    private final String producerGroup;

    private final Duration sendTimeout;

    private final int retryTimesWhenSendFailed;

    private final int maximumMessageSize;

    RocketMq5Configuration(String nameServerAddress, String producerGroup, Duration sendTimeout, int retryTimesWhenSendFailed, int maximumMessageSize) {
        nameServerAddress = requireText(nameServerAddress, "nameServerAddress");
        producerGroup = requireText(producerGroup, "producerGroup");
        sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
        long sendTimeoutMillis;
        try {
            sendTimeoutMillis = sendTimeout.toMillis();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("sendTimeout is too large", failure);
        }
        if (sendTimeoutMillis < 1 || sendTimeoutMillis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("sendTimeout must fit a positive millisecond value");
        }
        if (retryTimesWhenSendFailed < 0) {
            throw new IllegalArgumentException("retryTimesWhenSendFailed must not be negative");
        }
        if (maximumMessageSize <= 0) {
            throw new IllegalArgumentException("maximumMessageSize must be positive");
        }
        this.nameServerAddress = nameServerAddress;
        this.producerGroup = producerGroup;
        this.sendTimeout = sendTimeout;
        this.retryTimesWhenSendFailed = retryTimesWhenSendFailed;
        this.maximumMessageSize = maximumMessageSize;
    }

    public String nameServerAddress() {
        return nameServerAddress;
    }

    public String producerGroup() {
        return producerGroup;
    }

    public Duration sendTimeout() {
        return sendTimeout;
    }

    public int retryTimesWhenSendFailed() {
        return retryTimesWhenSendFailed;
    }

    public int maximumMessageSize() {
        return maximumMessageSize;
    }

    static RocketMq5Configuration defaults(String producerGroup) {
        return new RocketMq5Configuration("127.0.0.1:9876", producerGroup, Duration.ofSeconds(3), 2, 4 * 1024 * 1024);
    }

    public static Builder builder(String producerGroup) {
        return new Builder(defaults(producerGroup));
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private String nameServerAddress;

        private String producerGroup;

        private Duration sendTimeout;

        private int retryTimesWhenSendFailed;

        private int maximumMessageSize;

        private Builder(RocketMq5Configuration source) {
            nameServerAddress = source.nameServerAddress();
            producerGroup = source.producerGroup();
            sendTimeout = source.sendTimeout();
            retryTimesWhenSendFailed = source.retryTimesWhenSendFailed();
            maximumMessageSize = source.maximumMessageSize();
        }

        public Builder nameServerAddress(String value) {
            nameServerAddress = value;
            return this;
        }

        public Builder producerGroup(String value) {
            producerGroup = value;
            return this;
        }

        public Builder sendTimeout(Duration value) {
            sendTimeout = value;
            return this;
        }

        public Builder retryTimesWhenSendFailed(int value) {
            retryTimesWhenSendFailed = value;
            return this;
        }

        public Builder maximumMessageSize(int value) {
            maximumMessageSize = value;
            return this;
        }

        public RocketMq5Configuration build() {
            return new RocketMq5Configuration(nameServerAddress, producerGroup, sendTimeout, retryTimesWhenSendFailed, maximumMessageSize);
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof RocketMq5Configuration that))
            return false;
        return java.util.Objects.equals(nameServerAddress, that.nameServerAddress) && java.util.Objects.equals(producerGroup, that.producerGroup) && java.util.Objects.equals(sendTimeout, that.sendTimeout) && retryTimesWhenSendFailed == that.retryTimesWhenSendFailed && maximumMessageSize == that.maximumMessageSize;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(nameServerAddress, producerGroup, sendTimeout, retryTimesWhenSendFailed, maximumMessageSize);
    }

    @Override
    public String toString() {
        return "RocketMq5Configuration[nameServerAddress=" + nameServerAddress + ", producerGroup=" + producerGroup + ", sendTimeout=" + sendTimeout + ", retryTimesWhenSendFailed=" + retryTimesWhenSendFailed + ", maximumMessageSize=" + maximumMessageSize + "]";
    }
}
