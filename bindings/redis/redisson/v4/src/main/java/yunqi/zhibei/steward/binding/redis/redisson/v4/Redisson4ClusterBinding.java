package yunqi.zhibei.steward.binding.redis.redisson.v4;

import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Creates, checks, and closes a native Redisson 4 Redis Cluster client. */
public final class Redisson4ClusterBinding
        implements ResourceBinding<Redisson4ClusterConfiguration, RedissonClient> {

    private static final long CLOSE_TIMEOUT_SECONDS = 30L;

    public Redisson4ClusterBinding() {
        Redisson4Binding.verifyDependencyVersion();
    }

    public static BoundResource<RedissonClient> start(
            Redisson4ClusterConfiguration configuration) throws Exception {
        return BoundResource.start(configuration, new Redisson4ClusterBinding());
    }

    @Override
    public RedissonClient create(Redisson4ClusterConfiguration configuration) {
        return Redisson.create(nativeConfiguration(configuration));
    }

    @Override
    public Health check(RedissonClient client) {
        return Objects.requireNonNull(client, "client")
                .getRedisNodes(RedisNodes.CLUSTER)
                .pingAll()
                ? Health.healthy(ProbeScope.REMOTE)
                : Health.unhealthy(ProbeScope.REMOTE);
    }

    @Override
    public void close(RedissonClient client) {
        Objects.requireNonNull(client, "client")
                .shutdown(0L, CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    static Config nativeConfiguration(Redisson4ClusterConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Config result = Redisson4Binding.baseConfiguration(configuration.client());
        configuration.username().ifPresent(result::setUsername);
        configuration.password().ifPresent(result::setPassword);

        Redisson4ClusterConfiguration.Pool pool = configuration.pool();
        ClusterServersConfig cluster = result.useClusterServers()
                .addNodeAddress(configuration.nodeAddresses().stream()
                        .map(Object::toString)
                        .toArray(String[]::new))
                .setConnectTimeout(Redisson4Binding.toIntMillis(
                        configuration.connectTimeout(), "connectTimeout"))
                .setTimeout(Redisson4Binding.toIntMillis(
                        configuration.commandTimeout(), "commandTimeout"))
                .setRetryAttempts(configuration.retryAttempts())
                .setScanInterval(Redisson4Binding.toIntMillis(
                        configuration.scanInterval(), "scanInterval"))
                .setMasterConnectionMinimumIdleSize(pool.masterMinimumIdle())
                .setMasterConnectionPoolSize(pool.masterMaximum())
                .setSlaveConnectionMinimumIdleSize(pool.slaveMinimumIdle())
                .setSlaveConnectionPoolSize(pool.slaveMaximum())
                .setSubscriptionConnectionMinimumIdleSize(pool.subscriptionMinimumIdle())
                .setSubscriptionConnectionPoolSize(pool.subscriptionMaximum());
        Objects.requireNonNull(cluster, "cluster");
        return result;
    }
}
