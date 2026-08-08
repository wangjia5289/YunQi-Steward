package yunqi.zhibei.steward.binding.kafka.client.v3;

import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.refresh.ManagedResource;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.refresh.MutableConfigurationSource;
import yunqi.zhibei.steward.support.testing.BindingContract;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaClient3BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new KafkaClient3Binding(),
                configuration("producer-a", 3),
                configuration("producer-b", 4));
    }

    @Test
    void mapsEveryNativeProducerSetting() {
        KafkaClient3Configuration configuration = configuration("producer-a", 11);
        Properties properties = KafkaClient3Binding.producerProperties(configuration);

        assertThat(properties)
                .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092")
                .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "producer-a")
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.RETRIES_CONFIG, 11)
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4_000)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 40_000)
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 25L)
                .containsEntry(ProducerConfig.BATCH_SIZE_CONFIG, 32_768)
                .containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip")
                .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)
                .containsEntry(
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                        ByteArraySerializer.class);
    }

    @Test
    void buildsAndRefreshesNativeProducersWithoutContactingKafka() throws Exception {
        KafkaClient3Configuration initial = configuration("producer-a", 3);
        KafkaClient3Configuration updated = configuration("producer-b", 4);
        MutableConfigurationSource<KafkaClient3Configuration> source =
                new MutableConfigurationSource<>(initial);

        try (ManagedResource<KafkaProducer<byte[], byte[]>, KafkaClient3Configuration> managed =
                     ManagedResource
                             .<KafkaProducer<byte[], byte[]>, KafkaClient3Configuration>builder(
                                     source, new KafkaClient3Binding())
                             .healthCheck(ignored -> Health.healthy(ProbeScope.LOCAL))
                             .build()) {
            KafkaProducer<byte[], byte[]> first = managed.execute(producer -> producer);
            source.update(updated);
            assertThat(managed.awaitIdle(Duration.ofSeconds(5))).isTrue();
            KafkaProducer<byte[], byte[]> second = managed.execute(producer -> producer);

            assertThat(second).isNotSameAs(first);
            assertThat(managed.status().activeRevision()).isEqualTo(2);
        }
    }

    @Test
    void verifiesTheSelectedSdkMajor() {
        KafkaClient3Binding.verifyDependencyVersion();
    }

    private static KafkaClient3Configuration configuration(String clientId, int retries) {
        return new KafkaClient3Configuration(
                "127.0.0.1:9092",
                clientId,
                "all",
                retries,
                Duration.ofSeconds(4),
                Duration.ofSeconds(40),
                Duration.ofMillis(25),
                32_768,
                "gzip");
    }
}
