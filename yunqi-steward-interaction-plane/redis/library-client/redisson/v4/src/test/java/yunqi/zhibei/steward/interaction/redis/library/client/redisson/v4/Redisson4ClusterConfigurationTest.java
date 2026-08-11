package yunqi.zhibei.steward.interaction.redis.library.client.redisson.v4;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Redisson4ClusterConfigurationTest {

    @Test
    void buildsFromDefaultsAndCopiesOnlyRequestedChanges() {
        Redisson4ClusterConfiguration configured = Redisson4ClusterConfiguration.builder()
                .nodeAddress("rediss://redis-a:7000")
                .addNodeAddress("rediss://redis-b:7001")
                .password("secret")
                .retryAttempts(6)
                .build();
        Redisson4ClusterConfiguration updated = configured.toBuilder()
                .scanInterval(Duration.ofSeconds(2))
                .build();

        assertThat(configured.nodeAddresses()).containsExactlyInAnyOrder(
                URI.create("rediss://redis-a:7000"),
                URI.create("rediss://redis-b:7001"));
        assertThat(configured.retryAttempts()).isEqualTo(6);
        assertThat(updated.nodeAddresses()).isEqualTo(configured.nodeAddresses());
        assertThat(updated.password()).contains("secret");
        assertThat(updated.scanInterval()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void validatesAddressesDurationsAndPools() {
        Redisson4ClusterConfiguration defaults = Redisson4ClusterConfiguration.defaults();
        Redisson4ClusterConfiguration.Builder builder = defaults.toBuilder();

        assertThat(defaults.nodeAddresses())
                .containsExactly(URI.create("redis://127.0.0.1:6379"));
        assertThatThrownBy(() -> builder
                .nodeAddress("redis://user:credential-leak-marker@bad host:6379"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("credential-leak-marker")
                .hasNoCause();
        assertThat(builder.build()).isEqualTo(defaults);
        assertThatThrownBy(() -> Redisson4ClusterConfiguration.builder()
                .nodeAddresses(Set.of())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeAddresses");
        assertThatThrownBy(() -> defaults.toBuilder()
                .nodeAddress("http://localhost:6379")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redis or rediss");
        assertThatThrownBy(() -> defaults.toBuilder()
                .nodeAddress("redis://localhost")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
        assertThatThrownBy(() -> defaults.toBuilder()
                .nodeAddress("redis://localhost:6379?mode=cluster")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
        assertThatThrownBy(() -> defaults.toBuilder()
                .pool(new Redisson4ClusterConfiguration.Pool(5, 4, 1, 2, 1, 2))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum idle");
    }

    @Test
    void redactsPasswordFromDiagnostics() {
        Redisson4ClusterConfiguration secured = Redisson4ClusterConfiguration.builder()
                .username("app")
                .password("secret")
                .build();

        assertThat(secured.toString())
                .contains("[REDACTED]")
                .doesNotContain("secret");
    }
}
