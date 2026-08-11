package yunqi.zhibei.steward.interaction.redis.library.client.redisson.v4;

import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;
import yunqi.zhibei.steward.support.testing.BindingContract;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class Redisson4BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new Redisson4Binding(),
                contractConfiguration(6379),
                contractConfiguration(6380),
                "redisson-secret");
    }

    @Test
    void mapsEveryConfigurationGroupToNativeRedissonConfig() {
        Redisson4Configuration configuration = Redisson4Configuration.defaults()
                .withAddress(URI.create("rediss://redis.internal:6380"))
                .withLazyInitialization(true);

        Config nativeConfig = Redisson4Binding.nativeConfiguration(configuration);
        SingleServerConfig server = nativeConfig.useSingleServer();

        assertThat(nativeConfig.getThreads()).isEqualTo(16);
        assertThat(nativeConfig.getNettyThreads()).isEqualTo(32);
        assertThat(nativeConfig.isLazyInitialization()).isTrue();
        assertThat(server.getAddress()).isEqualTo("rediss://redis.internal:6380");
        assertThat(server.getDatabase()).isZero();
        assertThat(server.getConnectTimeout()).isEqualTo(10_000);
        assertThat(server.getTimeout()).isEqualTo(3_000);
        assertThat(server.getConnectionMinimumIdleSize()).isEqualTo(24);
        assertThat(server.getConnectionPoolSize()).isEqualTo(64);
    }

    @Test
    void bindsAndRefreshesOnlyNativeRedissonClientWithoutContactingRedis() throws Exception {
        Redisson4Configuration firstConfiguration = Redisson4Configuration.defaults()
                .withLazyInitialization(true);
        Redisson4Configuration secondConfiguration = firstConfiguration
                .withAddress(URI.create("redis://127.0.0.1:6380"));
        MutableConfigurationSource<Redisson4Configuration> source =
                new MutableConfigurationSource<>(firstConfiguration);

        RedissonClient first;
        try (ManagedResource<RedissonClient, Redisson4Configuration> managed =
                     ManagedResource.<RedissonClient, Redisson4Configuration>builder(
                                     source, new Redisson4Binding())
                             .healthCheck(ignored -> Health.healthy(ProbeScope.LOCAL))
                             .build()) {
            first = managed.execute(client -> client);
            source.update(secondConfiguration);
            assertThat(managed.awaitIdle(Duration.ofSeconds(5))).isTrue();
            RedissonClient second = managed.execute(client -> client);

            assertThat(managed.status().activeRevision()).isEqualTo(2);
            assertThat(second).isNotSameAs(first);
            assertThat(first.isShutdown()).isTrue();
        }
    }

    @Test
    void verifiesTheSelectedSdkVersion() {
        Redisson4Binding.verifyDependencyVersion();
    }

    private static Redisson4Configuration contractConfiguration(int port) {
        return Redisson4Configuration.builder()
                .address("redis://127.0.0.1:" + port)
                .password("redisson-secret")
                .lazyInitialization(true)
                .build();
    }
}
