package yunqi.zhibei.steward.interaction.etcd.library.client.jetcd.v0;

import yunqi.zhibei.steward.support.testing.BindingContract;
import io.etcd.jetcd.ClientBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Jetcd0BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new Jetcd0Binding(),
                contractConfiguration(2379),
                contractConfiguration(2380),
                "etcd-contract-secret");
    }

    @Test
    void mapsOnlyResourceScopedClientConfiguration() throws Exception {
        Jetcd0Configuration configuration = new Jetcd0Configuration(
                List.of(URI.create("http://etcd.internal:2379")),
                Optional.of("app"),
                Optional.of("etcd-secret"),
                Optional.of("orders"),
                Optional.of("etcd.internal"),
                false,
                Duration.ofSeconds(4),
                100,
                800,
                4,
                Duration.ofSeconds(20),
                Duration.ofSeconds(5),
                false,
                8 * 1024 * 1024);

        ClientBuilder builder = Jetcd0Binding.nativeBuilder(configuration);

        assertThat(builder.connectTimeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(builder.retryDelay()).isEqualTo(100);
        assertThat(builder.retryMaxDelay()).isEqualTo(800);
        assertThat(builder.retryMaxAttempts()).isEqualTo(4);
        assertThat(builder.keepaliveTime()).isEqualTo(Duration.ofSeconds(20));
        assertThat(builder.keepaliveTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(builder.keepaliveWithoutCalls()).isFalse();
        assertThat(builder.maxInboundMessageSize()).isEqualTo(8 * 1024 * 1024);
        assertThat(builder.authority()).isEqualTo("etcd.internal");
        assertThat(builder.namespace().toString(StandardCharsets.UTF_8)).isEqualTo("orders");
        assertThat(builder.user().toString(StandardCharsets.UTF_8)).isEqualTo("app");
        assertThat(configuration.toString())
                .contains("[REDACTED]")
                .doesNotContain("etcd-secret");

        assertThat(Arrays.stream(Jetcd0Configuration.class.getDeclaredMethods())
                .map(method -> method.getName()))
                .doesNotContain("callTimeout", "waitForReady");
    }

    @Test
    void validatesCredentialPairsTlsAndRetryBounds() {
        Jetcd0Configuration defaults = Jetcd0Configuration.defaults();
        assertThat(defaults.endpoints())
                .containsExactly(URI.create("http://127.0.0.1:2379"));

        assertThatThrownBy(() -> new Jetcd0Configuration(
                List.of(URI.create("http://etcd.internal:2379")),
                Optional.of("app"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                Duration.ofSeconds(1),
                100,
                200,
                1,
                Duration.ofSeconds(10),
                Duration.ofSeconds(3),
                true,
                1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configured together");

        assertThatThrownBy(() -> new Jetcd0Configuration(
                List.of(URI.create("http://etcd.internal:2379")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                true,
                Duration.ofSeconds(1),
                100,
                200,
                1,
                Duration.ofSeconds(10),
                Duration.ofSeconds(3),
                true,
                1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    private static Jetcd0Configuration contractConfiguration(int port) {
        return new Jetcd0Configuration(
                List.of(URI.create("http://127.0.0.1:" + port)),
                Optional.of("app"),
                Optional.of("etcd-contract-secret"),
                Optional.of("orders"),
                Optional.empty(),
                false,
                Duration.ofMillis(100),
                10,
                20,
                1,
                Duration.ofSeconds(10),
                Duration.ofSeconds(3),
                false,
                1024 * 1024);
    }
}
