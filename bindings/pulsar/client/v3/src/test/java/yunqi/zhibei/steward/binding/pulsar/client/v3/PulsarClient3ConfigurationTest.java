package yunqi.zhibei.steward.binding.pulsar.client.v3;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PulsarClient3ConfigurationTest {

    @Test
    void buildsFromDefaultsAndCopiesAuthentication() {
        PulsarClient3Configuration configured = PulsarClient3Configuration.builder()
                .serviceUrl("pulsar+ssl://pulsar.internal:6651")
                .authentication("example.Auth", "secret")
                .ioThreads(4)
                .build();
        PulsarClient3Configuration updated = configured.toBuilder().listenerThreads(3).build();

        assertThat(configured.authenticationParams()).contains("secret");
        assertThat(updated.serviceUrl()).isEqualTo(configured.serviceUrl());
        assertThat(updated.ioThreads()).isEqualTo(4);
        assertThat(updated.listenerThreads()).isEqualTo(3);
    }

    @Test
    void providesOnlyClientLevelDefaults() {
        PulsarClient3Configuration configuration = PulsarClient3Configuration.defaults();

        assertThat(configuration.serviceUrl()).isEqualTo("pulsar://127.0.0.1:6650");
        assertThat(configuration.authenticationPluginClassName()).isEmpty();
        assertThat(configuration.authenticationParams()).isEmpty();
        assertThat(configuration.operationTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(configuration.ioThreads()).isOne();
        assertThat(configuration.listenerThreads()).isOne();
    }

    @Test
    void requiresCompleteAuthenticationAndValidClientSettings() {
        assertThatThrownBy(() -> new PulsarClient3Configuration(
                "pulsar://broker:6650",
                Optional.of("auth.Plugin"),
                Optional.empty(),
                Duration.ofSeconds(1),
                1,
                1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("together");
        assertThatThrownBy(() -> new PulsarClient3Configuration(
                "pulsar://broker:6650",
                Optional.empty(),
                Optional.empty(),
                Duration.ZERO,
                1,
                1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operationTimeout");
    }

    @Test
    void redactsAuthenticationParameters() {
        PulsarClient3Configuration configuration = new PulsarClient3Configuration(
                "pulsar://broker:6650",
                Optional.of("auth.Plugin"),
                Optional.of("secret-token"),
                Duration.ofSeconds(1),
                1,
                1);

        assertThat(configuration.toString())
                .contains("[REDACTED]")
                .doesNotContain("secret-token");
    }
}
