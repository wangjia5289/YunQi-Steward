package yunqi.zhibei.steward.binding.kafka.client.v3;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaClient3ConfigurationTest {

    @Test
    void buildsFromDefaultsAndCopiesOnlyRequestedChanges() {
        KafkaClient3Configuration configured = KafkaClient3Configuration.builder()
                .bootstrapServers("kafka-a:9092,kafka-b:9092")
                .clientId("orders")
                .compressionType("zstd")
                .build();
        KafkaClient3Configuration updated = configured.toBuilder().retries(8).build();

        assertThat(configured.acks()).isEqualTo("all");
        assertThat(configured.compressionType()).isEqualTo("zstd");
        assertThat(updated.bootstrapServers()).isEqualTo(configured.bootstrapServers());
        assertThat(updated.retries()).isEqualTo(8);
    }

    @Test
    void providesResourceLevelDefaults() {
        KafkaClient3Configuration configuration = KafkaClient3Configuration.defaults();

        assertThat(configuration.bootstrapServers()).isEqualTo("127.0.0.1:9092");
        assertThat(configuration.clientId()).isEqualTo("yunqi-steward");
        assertThat(configuration.acks()).isEqualTo("all");
        assertThat(configuration.linger()).isZero();
        assertThat(configuration.batchSize()).isEqualTo(16_384);
        assertThat(configuration.compressionType()).isEqualTo("none");
    }

    @Test
    void rejectsInvalidProducerSettings() {
        assertThatThrownBy(() -> configuration(-1, Duration.ZERO, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retries");
        assertThatThrownBy(() -> configuration(
                0, Duration.ofSeconds(20), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deliveryTimeout");
        assertThatThrownBy(() -> new KafkaClient3Configuration(
                "broker:9092", "client", "all", 0,
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ZERO,
                1, "unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compressionType");
    }

    private static KafkaClient3Configuration configuration(
            int retries,
            Duration requestTimeout,
            Duration deliveryTimeout) {
        return new KafkaClient3Configuration(
                "broker:9092", "client", "all", retries,
                requestTimeout, deliveryTimeout, Duration.ZERO, 1, "none");
    }
}
