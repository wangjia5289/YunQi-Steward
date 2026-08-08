package yunqi.zhibei.steward.binding.redis.jedis.v5;

import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.refresh.ManagedResource;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.refresh.MutableConfigurationSource;
import yunqi.zhibei.steward.support.testing.BindingContract;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisPooled;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class Jedis5BindingTest {

    @Test
    void mapsEveryNativeClientAndPoolSetting() {
        Jedis5Configuration configuration = configuration("redis-a", 1);

        DefaultJedisClientConfig client = Jedis5Binding.clientConfig(configuration);
        GenericObjectPoolConfig<Connection> pool = Jedis5Binding.poolConfig(configuration);

        assertThat(client.getUser()).isEqualTo(" acl-user ");
        assertThat(client.getPassword()).isEqualTo(" secret ");
        assertThat(client.getDatabase()).isEqualTo(1);
        assertThat(client.isSsl()).isTrue();
        assertThat(client.getConnectionTimeoutMillis()).isEqualTo(321);
        assertThat(client.getSocketTimeoutMillis()).isEqualTo(654);
        assertThat(pool.getMinIdle()).isEqualTo(2);
        assertThat(pool.getMaxIdle()).isEqualTo(4);
        assertThat(pool.getMaxTotal()).isEqualTo(12);
        assertThat(pool.getMaxWaitDuration()).isEqualTo(Duration.ofMillis(987));
    }

    @Test
    void verifiesTheLoadedJedisMajorVersion() {
        assertThatCode(Jedis5Binding::verifyDependencyMajorVersion)
                .doesNotThrowAnyException();
    }

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new Jedis5Binding(),
                configuration("redis-a", 1),
                configuration("redis-b", 2),
                " secret ");
    }

    @Test
    void buildsAndRefreshesLazyNativeClientsWithoutContactingRedis() throws Exception {
        Jedis5Configuration initial = configuration("redis-a", 1);
        Jedis5Configuration updated = configuration("redis-b", 2);
        MutableConfigurationSource<Jedis5Configuration> source =
                new MutableConfigurationSource<>(initial);

        try (ManagedResource<JedisPooled, Jedis5Configuration> resource =
                     ManagedResource.<JedisPooled, Jedis5Configuration>builder(
                                     source, new Jedis5Binding())
                             .healthCheck(ignored -> Health.healthy(ProbeScope.LOCAL))
                             .build()) {
            JedisPooled first = resource.execute(client -> client);

            source.update(updated);
            assertThat(resource.awaitIdle(Duration.ofSeconds(5))).isTrue();
            JedisPooled second = resource.execute(client -> client);

            assertThat(second).isNotSameAs(first);
            assertThat(resource.status().activeRevision()).isEqualTo(2);
        }
    }

    private static Jedis5Configuration configuration(String host, int database) {
        return new Jedis5Configuration(
                host,
                6380,
                Optional.of(" acl-user "),
                Optional.of(" secret "),
                database,
                true,
                Duration.ofMillis(321),
                Duration.ofMillis(654),
                2,
                4,
                12,
                Duration.ofMillis(987));
    }
}
