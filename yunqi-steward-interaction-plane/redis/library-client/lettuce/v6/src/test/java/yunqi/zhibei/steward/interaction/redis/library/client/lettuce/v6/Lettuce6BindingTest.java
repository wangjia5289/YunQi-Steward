package yunqi.zhibei.steward.interaction.redis.library.client.lettuce.v6;

import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;
import yunqi.zhibei.steward.support.testing.BindingContract;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class Lettuce6BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new Lettuce6Binding(),
                configuration(6380),
                configuration(6381),
                "secret");
    }

    @Test
    void mapsConfigurationToNativeLettuceOptions() {
        Lettuce6Configuration configuration = configuration(6380);

        RedisURI uri = Lettuce6Binding.redisUri(configuration);
        ClientOptions options = Lettuce6Binding.clientOptions(configuration);
        RedisCredentials credentials = uri.getCredentialsProvider()
                .resolveCredentials()
                .block();

        assertThat(uri.getHost()).isEqualTo("localhost");
        assertThat(uri.getPort()).isEqualTo(6380);
        assertThat(uri.getDatabase()).isEqualTo(3);
        assertThat(uri.isSsl()).isTrue();
        assertThat(uri.getTimeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(credentials).isNotNull();
        assertThat(credentials.getUsername()).isEqualTo("app");
        assertThat(credentials.getPassword()).containsExactly("secret".toCharArray());
        assertThat(options.getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void bindsAndRefreshesOnlyNativeRedisClientWithoutContactingRedis() throws Exception {
        MutableConfigurationSource<Lettuce6Configuration> source =
                new MutableConfigurationSource<>(configuration(6380));

        RedisClient first;
        try (ManagedResource<RedisClient, Lettuce6Configuration> managed =
                     ManagedResource.<RedisClient, Lettuce6Configuration>builder(
                                     source, new Lettuce6Binding())
                             .healthCheck(ignored -> Health.healthy(ProbeScope.LOCAL))
                             .build()) {
            first = managed.execute(client -> client);
            source.update(configuration(6381));
            assertThat(managed.awaitIdle(Duration.ofSeconds(2))).isTrue();
            RedisClient second = managed.execute(client -> client);

            assertThat(managed.status().activeRevision()).isEqualTo(2);
            assertThat(second).isNotSameAs(first);
        }
    }

    @Test
    void verifiesTheSelectedSdkVersion() {
        Lettuce6Binding.verifyDependencyVersion();
    }

    private static Lettuce6Configuration configuration(int port) {
        return new Lettuce6Configuration(
                "localhost",
                port,
                Optional.of("app"),
                Optional.of("secret"),
                3,
                true,
                Duration.ofSeconds(2),
                Duration.ofSeconds(4));
    }
}
