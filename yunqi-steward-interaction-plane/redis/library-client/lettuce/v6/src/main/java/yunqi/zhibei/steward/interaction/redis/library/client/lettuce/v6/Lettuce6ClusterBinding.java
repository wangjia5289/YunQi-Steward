package yunqi.zhibei.steward.interaction.redis.library.client.lettuce.v6;

import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.codec.ByteArrayCodec;

import java.util.List;
import java.util.Objects;
import java.time.Duration;

/** Creates, checks, and closes a native Lettuce 6 Redis Cluster client. */
public final class Lettuce6ClusterBinding
        implements ResourceBinding<Lettuce6ClusterConfiguration, RedisClusterClient> {

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    public Lettuce6ClusterBinding() {
        Lettuce6Binding.verifyDependencyVersion();
    }

    public static BoundResource<RedisClusterClient> start(
            Lettuce6ClusterConfiguration configuration) throws Exception {
        return BoundResource.start(configuration, new Lettuce6ClusterBinding());
    }

    @Override
    public RedisClusterClient create(Lettuce6ClusterConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        RedisClusterClient client = RedisClusterClient.create(redisUris(configuration));
        client.setOptions(clientOptions(configuration));
        return client;
    }

    @Override
    public Health check(RedisClusterClient client) {
        Objects.requireNonNull(client, "client");
        try (var connection = client.connect(ByteArrayCodec.INSTANCE)) {
            return "PONG".equalsIgnoreCase(connection.sync().ping())
                    ? Health.healthy(ProbeScope.REMOTE)
                    : Health.unhealthy(ProbeScope.REMOTE);
        } catch (RuntimeException failure) {
            return Health.unhealthy(ProbeScope.REMOTE);
        }
    }

    @Override
    public void close(RedisClusterClient client) {
        Objects.requireNonNull(client, "client").shutdown(Duration.ZERO, CLOSE_TIMEOUT);
    }

    static List<RedisURI> redisUris(Lettuce6ClusterConfiguration configuration) {
        return configuration.nodes().stream()
                .map(node -> redisUri(configuration, node))
                .toList();
    }

    static ClusterClientOptions clientOptions(Lettuce6ClusterConfiguration configuration) {
        ClusterTopologyRefreshOptions topology = ClusterTopologyRefreshOptions.builder()
                .enableAllAdaptiveRefreshTriggers()
                .enablePeriodicRefresh(configuration.topologyRefreshPeriod())
                .build();
        return ClusterClientOptions.builder()
                .maxRedirects(configuration.maximumRedirects())
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(configuration.connectTimeout())
                        .build())
                .topologyRefreshOptions(topology)
                .build();
    }

    private static RedisURI redisUri(
            Lettuce6ClusterConfiguration configuration,
            Lettuce6ClusterConfiguration.Node node) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(node.host())
                .withPort(node.port())
                .withSsl(configuration.tls())
                .withTimeout(configuration.commandTimeout());
        if (configuration.username().isPresent()) {
            builder.withAuthentication(
                    configuration.username().orElseThrow(),
                    configuration.password().orElse(""));
        } else {
            configuration.password().ifPresent(
                    password -> builder.withPassword((CharSequence) password));
        }
        return builder.build();
    }
}
