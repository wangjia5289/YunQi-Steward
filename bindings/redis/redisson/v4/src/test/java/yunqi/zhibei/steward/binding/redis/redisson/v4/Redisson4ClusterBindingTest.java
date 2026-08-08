package yunqi.zhibei.steward.binding.redis.redisson.v4;

import yunqi.zhibei.steward.support.testing.BindingContract;
import org.junit.jupiter.api.Test;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class Redisson4ClusterBindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new Redisson4ClusterBinding(),
                contractConfiguration(7000),
                contractConfiguration(7001),
                "redisson-cluster-secret");
    }

    @Test
    void mapsSeedsAuthenticationTimeoutsPoolsAndClientSettings() {
        Redisson4ClusterConfiguration.Pool pool =
                new Redisson4ClusterConfiguration.Pool(2, 12, 3, 13, 4, 14);
        Redisson4ClusterConfiguration configuration = Redisson4ClusterConfiguration.builder()
                .nodeAddress("rediss://redis-a:7000")
                .addNodeAddress("rediss://redis-b:7001")
                .username("app")
                .password("secret")
                .connectTimeout(Duration.ofMillis(321))
                .commandTimeout(Duration.ofMillis(654))
                .retryAttempts(7)
                .scanInterval(Duration.ofMillis(987))
                .pool(pool)
                .client(Redisson4Configuration.Client.defaults().withLazyInitialization(true))
                .build();

        Config nativeConfig = Redisson4ClusterBinding.nativeConfiguration(configuration);
        ClusterServersConfig cluster = nativeConfig.useClusterServers();

        assertThat(nativeConfig.getUsername()).isEqualTo("app");
        assertThat(nativeConfig.getPassword()).isEqualTo("secret");
        assertThat(nativeConfig.isLazyInitialization()).isTrue();
        assertThat(cluster.getNodeAddresses()).containsExactlyInAnyOrder(
                "rediss://redis-a:7000", "rediss://redis-b:7001");
        assertThat(cluster.getConnectTimeout()).isEqualTo(321);
        assertThat(cluster.getTimeout()).isEqualTo(654);
        assertThat(cluster.getRetryAttempts()).isEqualTo(7);
        assertThat(cluster.getScanInterval()).isEqualTo(987);
        assertThat(cluster.getMasterConnectionMinimumIdleSize()).isEqualTo(2);
        assertThat(cluster.getMasterConnectionPoolSize()).isEqualTo(12);
        assertThat(cluster.getSlaveConnectionMinimumIdleSize()).isEqualTo(3);
        assertThat(cluster.getSlaveConnectionPoolSize()).isEqualTo(13);
        assertThat(cluster.getSubscriptionConnectionMinimumIdleSize()).isEqualTo(4);
        assertThat(cluster.getSubscriptionConnectionPoolSize()).isEqualTo(14);
    }

    private static Redisson4ClusterConfiguration contractConfiguration(int port) {
        return Redisson4ClusterConfiguration.builder()
                .nodeAddress("redis://127.0.0.1:" + port)
                .password("redisson-cluster-secret")
                .client(Redisson4Configuration.Client.defaults().withLazyInitialization(true))
                .build();
    }
}
