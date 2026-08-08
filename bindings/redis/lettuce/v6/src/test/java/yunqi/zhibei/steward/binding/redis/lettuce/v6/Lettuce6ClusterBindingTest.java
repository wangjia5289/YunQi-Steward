package yunqi.zhibei.steward.binding.redis.lettuce.v6;

import yunqi.zhibei.steward.support.testing.BindingContract;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Lettuce6ClusterBindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new Lettuce6ClusterBinding(),
                contractConfiguration(7000),
                contractConfiguration(7001),
                "lettuce-cluster-secret");
    }

    @Test
    void mapsSeedsAuthenticationTimeoutsAndTopologyRefresh() {
        Lettuce6ClusterConfiguration configuration = Lettuce6ClusterConfiguration.builder()
                .node("redis-a", 7000)
                .addNode("redis-b", 7001)
                .username("app")
                .password("secret")
                .tls(true)
                .connectTimeout(Duration.ofSeconds(2))
                .commandTimeout(Duration.ofSeconds(4))
                .maximumRedirects(8)
                .topologyRefreshPeriod(Duration.ofSeconds(20))
                .build();

        List<RedisURI> uris = Lettuce6ClusterBinding.redisUris(configuration);
        ClusterClientOptions options = Lettuce6ClusterBinding.clientOptions(configuration);
        RedisCredentials credentials = uris.getFirst().getCredentialsProvider()
                .resolveCredentials()
                .block();
        ClusterTopologyRefreshOptions topology = options.getTopologyRefreshOptions();

        assertThat(uris).extracting(RedisURI::getHost)
                .containsExactlyInAnyOrder("redis-a", "redis-b");
        assertThat(uris).extracting(RedisURI::getPort)
                .containsExactlyInAnyOrder(7000, 7001);
        assertThat(uris).allSatisfy(uri -> {
            assertThat(uri.isSsl()).isTrue();
            assertThat(uri.getTimeout()).isEqualTo(Duration.ofSeconds(4));
        });
        assertThat(credentials).isNotNull();
        assertThat(credentials.getUsername()).isEqualTo("app");
        assertThat(credentials.getPassword()).containsExactly("secret".toCharArray());
        assertThat(options.getMaxRedirects()).isEqualTo(8);
        assertThat(options.getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(topology.isPeriodicRefreshEnabled()).isTrue();
        assertThat(topology.getRefreshPeriod()).isEqualTo(Duration.ofSeconds(20));
        assertThat(topology.getAdaptiveRefreshTriggers()).isNotEmpty();
    }

    private static Lettuce6ClusterConfiguration contractConfiguration(int port) {
        return Lettuce6ClusterConfiguration.builder()
                .node("127.0.0.1", port)
                .username("app")
                .password("lettuce-cluster-secret")
                .build();
    }
}
