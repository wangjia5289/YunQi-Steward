package yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7;

import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;

import java.util.Objects;
import java.util.stream.Collectors;

/** Creates, checks, and closes a native Jedis 7 Redis Cluster client. */
public final class Jedis7ClusterBinding
        implements ResourceBinding<Jedis7ClusterConfiguration, JedisCluster> {

    public Jedis7ClusterBinding() {
        Jedis7Binding.verifyDependencyMajorVersion();
    }

    public static BoundResource<JedisCluster> start(Jedis7ClusterConfiguration configuration)
            throws Exception {
        return BoundResource.start(configuration, new Jedis7ClusterBinding());
    }

    @Override
    public JedisCluster create(Jedis7ClusterConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return JedisCluster.builder()
                .nodes(configuration.nodes().stream()
                        .map(node -> new HostAndPort(node.host(), node.port()))
                        .collect(Collectors.toUnmodifiableSet()))
                .clientConfig(clientConfig(configuration))
                .maxAttempts(configuration.maximumAttempts())
                .topologyRefreshPeriod(configuration.topologyRefreshPeriod())
                .poolConfig(poolConfig(configuration))
                .build();
    }

    @Override
    public Health check(JedisCluster client) {
        return "PONG".equalsIgnoreCase(Objects.requireNonNull(client, "client").ping())
                ? Health.healthy(ProbeScope.REMOTE)
                : Health.unhealthy(ProbeScope.REMOTE);
    }

    @Override
    public void close(JedisCluster client) {
        Objects.requireNonNull(client, "client").close();
    }

    static DefaultJedisClientConfig clientConfig(Jedis7ClusterConfiguration configuration) {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(Math.toIntExact(configuration.connectTimeout().toMillis()))
                .socketTimeoutMillis(Math.toIntExact(configuration.commandTimeout().toMillis()))
                .ssl(configuration.tls());
        if (configuration.username().isPresent()) {
            builder.user(configuration.username().orElseThrow());
            builder.password(configuration.password().orElse(""));
        } else {
            configuration.password().ifPresent(builder::password);
        }
        return builder.build();
    }

    static GenericObjectPoolConfig<Connection> poolConfig(
            Jedis7ClusterConfiguration configuration) {
        GenericObjectPoolConfig<Connection> pool = new GenericObjectPoolConfig<>();
        pool.setMinIdle(configuration.minimumIdle());
        pool.setMaxIdle(configuration.maximumIdle());
        pool.setMaxTotal(configuration.maximumTotal());
        pool.setMaxWait(configuration.acquireTimeout());
        return pool;
    }
}
