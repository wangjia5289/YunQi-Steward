package yunqi.zhibei.steward.binding.redis.jedis.v7;

import yunqi.zhibei.steward.support.testing.BindingContract;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class Jedis7ClusterBindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContractAgainstLocalClusterProtocolFixture()
            throws Exception {
        try (FakeRedisClusterServer server = new FakeRedisClusterServer()) {
            BindingContract.verify(
                    new Jedis7ClusterBinding(),
                    contractConfiguration(server.port()),
                    contractConfiguration(server.port()).toBuilder()
                            .maximumAttempts(4)
                            .build(),
                    "jedis-cluster-secret");
        }
    }

    @Test
    void mapsAuthenticationTlsTimeoutsAndPoolSettings() {
        Jedis7ClusterConfiguration configuration = Jedis7ClusterConfiguration.builder()
                .node("redis-a", 7000)
                .username("app")
                .password("secret")
                .tls(true)
                .connectTimeout(Duration.ofMillis(321))
                .commandTimeout(Duration.ofMillis(654))
                .pool(2, 4, 12)
                .acquireTimeout(Duration.ofMillis(987))
                .build();

        DefaultJedisClientConfig client = Jedis7ClusterBinding.clientConfig(configuration);
        GenericObjectPoolConfig<Connection> pool = Jedis7ClusterBinding.poolConfig(configuration);

        assertThat(client.getUser()).isEqualTo("app");
        assertThat(client.getPassword()).isEqualTo("secret");
        assertThat(client.isSsl()).isTrue();
        assertThat(client.getConnectionTimeoutMillis()).isEqualTo(321);
        assertThat(client.getSocketTimeoutMillis()).isEqualTo(654);
        assertThat(pool.getMinIdle()).isEqualTo(2);
        assertThat(pool.getMaxIdle()).isEqualTo(4);
        assertThat(pool.getMaxTotal()).isEqualTo(12);
        assertThat(pool.getMaxWaitDuration()).isEqualTo(Duration.ofMillis(987));
    }

    private static Jedis7ClusterConfiguration contractConfiguration(int port) {
        return Jedis7ClusterConfiguration.builder()
                .node("127.0.0.1", port)
                .username("app")
                .password("jedis-cluster-secret")
                .connectTimeout(Duration.ofMillis(100))
                .commandTimeout(Duration.ofMillis(100))
                .topologyRefreshPeriod(Duration.ofSeconds(30))
                .pool(0, 1, 1)
                .acquireTimeout(Duration.ofMillis(100))
                .build();
    }
}
