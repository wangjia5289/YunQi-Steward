package yunqi.zhibei.steward.interaction.rabbitmq.library.client.amqp.client.v5;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RabbitMqClient5ConfigurationTest {

    @Test
    void buildsFromDefaultsAndCopiesRecoverySettings() {
        RabbitMqClient5Configuration configured = RabbitMqClient5Configuration.builder()
                .uri("amqps://app:secret@rabbit.internal:5671/orders")
                .automaticRecovery(false)
                .build();
        RabbitMqClient5Configuration updated = configured.toBuilder()
                .topologyRecovery(false)
                .build();

        assertThat(configured.automaticRecovery()).isFalse();
        assertThat(updated.uri()).isEqualTo(configured.uri());
        assertThat(updated.topologyRecovery()).isFalse();
    }

    @Test
    void providesOnlyConnectionLevelDefaults() {
        RabbitMqClient5Configuration configuration = RabbitMqClient5Configuration.defaults();

        assertThat(configuration.uri())
                .isEqualTo("amqp://guest:guest@127.0.0.1:5672/%2f");
        assertThat(configuration.connectionTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(configuration.automaticRecovery()).isTrue();
        assertThat(configuration.topologyRecovery()).isTrue();
    }

    @Test
    void validatesAndRedactsTheConnectionUri() {
        assertThatThrownBy(() -> new RabbitMqClient5Configuration(
                " ", Duration.ofSeconds(1), true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uri");
        assertThatThrownBy(() -> new RabbitMqClient5Configuration(
                "amqp://broker", Duration.ZERO, true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectionTimeout");
        assertThatThrownBy(() -> new RabbitMqClient5Configuration(
                "amqp://user:credential-leak-marker@bad host:5672",
                Duration.ofSeconds(1),
                true,
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("credential-leak-marker")
                .hasNoCause();

        RabbitMqClient5Configuration configuration = new RabbitMqClient5Configuration(
                "amqp://user:secret@broker:5672/vhost",
                Duration.ofSeconds(1),
                true,
                false);
        assertThat(configuration.toString())
                .contains("[REDACTED]")
                .doesNotContain("secret");
    }
}
