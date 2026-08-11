package yunqi.zhibei.steward.interaction.redis.library.client.lettuce.v6;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Lettuce6ClusterConfigurationTest {

    @Test
    void buildsFromDefaultsAndCopiesOnlyRequestedChanges() {
        Lettuce6ClusterConfiguration configured = Lettuce6ClusterConfiguration.builder()
                .node("redis-a", 7000)
                .addNode("redis-b", 7001)
                .password("secret")
                .maximumRedirects(9)
                .build();
        Lettuce6ClusterConfiguration updated = configured.toBuilder()
                .commandTimeout(Duration.ofSeconds(5))
                .build();

        assertThat(configured.nodes()).containsExactlyInAnyOrder(
                new Lettuce6ClusterConfiguration.Node("redis-a", 7000),
                new Lettuce6ClusterConfiguration.Node("redis-b", 7001));
        assertThat(configured.maximumRedirects()).isEqualTo(9);
        assertThat(updated.nodes()).isEqualTo(configured.nodes());
        assertThat(updated.password()).contains("secret");
        assertThat(updated.commandTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void validatesNodesRedirectsAndDurations() {
        Lettuce6ClusterConfiguration defaults = Lettuce6ClusterConfiguration.defaults();

        assertThat(defaults.nodes()).containsExactly(
                new Lettuce6ClusterConfiguration.Node("127.0.0.1", 6379));
        assertThatThrownBy(() -> Lettuce6ClusterConfiguration.builder()
                .nodes(Set.of())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodes");
        assertThatThrownBy(() -> new Lettuce6ClusterConfiguration.Node("localhost", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
        assertThatThrownBy(() -> defaults.toBuilder().maximumRedirects(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumRedirects");
        assertThatThrownBy(() -> defaults.toBuilder()
                .topologyRefreshPeriod(Duration.ZERO)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topologyRefreshPeriod");
    }

    @Test
    void redactsPasswordFromDiagnostics() {
        Lettuce6ClusterConfiguration secured = Lettuce6ClusterConfiguration.builder()
                .username("app")
                .password("secret")
                .build();

        assertThat(secured.toString())
                .contains("[REDACTED]")
                .doesNotContain("secret");
    }
}
