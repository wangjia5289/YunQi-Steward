package yunqi.zhibei.steward.binding.redis.jedis.v7;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Jedis7ClusterConfigurationTest {

    @Test
    void buildsFromDefaultsAndCopiesOnlyRequestedChanges() {
        Jedis7ClusterConfiguration configured = Jedis7ClusterConfiguration.builder()
                .node("redis-a", 7000)
                .addNode("redis-b", 7001)
                .password("secret")
                .maximumAttempts(7)
                .build();
        Jedis7ClusterConfiguration updated = configured.toBuilder()
                .topologyRefreshPeriod(Duration.ofSeconds(10))
                .build();

        assertThat(configured.nodes()).containsExactlyInAnyOrder(
                new Jedis7ClusterConfiguration.Node("redis-a", 7000),
                new Jedis7ClusterConfiguration.Node("redis-b", 7001));
        assertThat(configured.maximumAttempts()).isEqualTo(7);
        assertThat(updated.nodes()).isEqualTo(configured.nodes());
        assertThat(updated.password()).contains("secret");
        assertThat(updated.topologyRefreshPeriod()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void validatesNodesTimeoutsAndPoolBounds() {
        Jedis7ClusterConfiguration defaults = Jedis7ClusterConfiguration.defaults();

        assertThat(defaults.nodes()).containsExactly(
                new Jedis7ClusterConfiguration.Node("127.0.0.1", 6379));
        assertThatThrownBy(() -> Jedis7ClusterConfiguration.builder()
                .nodes(Set.of())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodes");
        assertThatThrownBy(() -> new Jedis7ClusterConfiguration.Node(" ", 6379))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
        assertThatThrownBy(() -> new Jedis7ClusterConfiguration.Node("localhost", 65_536))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
        assertThatThrownBy(() -> defaults.toBuilder()
                .commandTimeout(Duration.ofNanos(1))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1 millisecond");
        assertThatThrownBy(() -> defaults.toBuilder().pool(5, 4, 8).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumIdle");
    }

    @Test
    void redactsPasswordFromDiagnostics() {
        Jedis7ClusterConfiguration secured = Jedis7ClusterConfiguration.builder()
                .username("app")
                .password("secret")
                .build();

        assertThat(secured.toString())
                .contains("[REDACTED]")
                .doesNotContain("secret");
    }
}
