package yunqi.zhibei.steward.interaction.elasticjob.library.client.lite.v3;

import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import org.apache.shardingsphere.elasticjob.reg.zookeeper.ZookeeperConfiguration;
import org.apache.shardingsphere.elasticjob.reg.zookeeper.ZookeeperRegistryCenter;

import java.util.Objects;

public final class ElasticJobLite3Binding
        implements ResourceBinding<ElasticJobLite3Configuration, ZookeeperRegistryCenter> {

    public static BoundResource<ZookeeperRegistryCenter> start(
            ElasticJobLite3Configuration configuration) throws Exception {
        return BoundResource.start(configuration, new ElasticJobLite3Binding());
    }

    @Override
    public ZookeeperRegistryCenter create(ElasticJobLite3Configuration configuration) {
        ZookeeperRegistryCenter registryCenter =
                new ZookeeperRegistryCenter(nativeConfiguration(configuration));
        try {
            registryCenter.init();
            return registryCenter;
        } catch (RuntimeException | Error failure) {
            registryCenter.close();
            throw failure;
        }
    }

    @Override
    public Health check(ZookeeperRegistryCenter registryCenter) {
        Objects.requireNonNull(registryCenter, "registryCenter");
        try {
            registryCenter.getChildrenKeys("/");
            return Health.healthy(ProbeScope.REMOTE);
        } catch (RuntimeException failure) {
            return Health.unhealthy(ProbeScope.REMOTE);
        }
    }

    @Override
    public void close(ZookeeperRegistryCenter registryCenter) {
        Objects.requireNonNull(registryCenter, "registryCenter").close();
    }

    static ZookeeperConfiguration nativeConfiguration(
            ElasticJobLite3Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        ZookeeperConfiguration result = new ZookeeperConfiguration(
                configuration.serverLists(), configuration.namespace());
        result.setBaseSleepTimeMilliseconds(configuration.baseSleepTimeMilliseconds());
        result.setMaxSleepTimeMilliseconds(configuration.maxSleepTimeMilliseconds());
        result.setMaxRetries(configuration.maxRetries());
        result.setSessionTimeoutMilliseconds(configuration.sessionTimeoutMilliseconds());
        result.setConnectionTimeoutMilliseconds(configuration.connectionTimeoutMilliseconds());
        configuration.digest().ifPresent(result::setDigest);
        return result;
    }
}
