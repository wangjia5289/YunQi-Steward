package yunqi.zhibei.steward.binding.kafka.client.v3;

import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.utils.AppInfoParser;

import java.util.Objects;
import java.time.Duration;
import java.util.Properties;

/** Binds managed lifecycle to a native Kafka 3 byte-array producer. */
public final class KafkaClient3Binding
        implements ResourceBinding<KafkaClient3Configuration, KafkaProducer<byte[], byte[]>> {

    public static BoundResource<KafkaProducer<byte[], byte[]>> start(
            KafkaClient3Configuration configuration) throws Exception {
        return BoundResource.start(configuration, new KafkaClient3Binding());
    }

    static final String SDK_MAJOR = "3";
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    public KafkaClient3Binding() {
        verifyDependencyVersion();
    }

    @Override
    public KafkaProducer<byte[], byte[]> create(KafkaClient3Configuration configuration) {
        return new KafkaProducer<>(producerProperties(configuration));
    }

    @Override
    public Health check(KafkaProducer<byte[], byte[]> producer) {
        Objects.requireNonNull(producer, "producer");
        try {
            producer.metrics();
            return Health.healthy(ProbeScope.LOCAL);
        } catch (RuntimeException failure) {
            return Health.unhealthy(ProbeScope.LOCAL);
        }
    }

    @Override
    public void close(KafkaProducer<byte[], byte[]> producer) {
        Objects.requireNonNull(producer, "producer").close(CLOSE_TIMEOUT);
    }

    static Properties producerProperties(KafkaClient3Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, configuration.bootstrapServers());
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, configuration.clientId());
        properties.put(ProducerConfig.ACKS_CONFIG, configuration.acks());
        properties.put(ProducerConfig.RETRIES_CONFIG, configuration.retries());
        properties.put(
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                Math.toIntExact(configuration.requestTimeout().toMillis()));
        properties.put(
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                Math.toIntExact(configuration.deliveryTimeout().toMillis()));
        properties.put(ProducerConfig.LINGER_MS_CONFIG, configuration.linger().toMillis());
        properties.put(ProducerConfig.BATCH_SIZE_CONFIG, configuration.batchSize());
        properties.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, configuration.compressionType());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return properties;
    }

    static void verifyDependencyVersion() {
        String actual = AppInfoParser.getVersion();
        if (!SDK_MAJOR.equals(majorOf(actual))) {
            throw new IllegalStateException(
                    "Kafka client binding requires major " + SDK_MAJOR + " but loaded " + actual);
        }
    }

    private static String majorOf(String version) {
        if (version == null) {
            return null;
        }
        int separator = version.indexOf('.');
        return separator < 0 ? version : version.substring(0, separator);
    }
}
