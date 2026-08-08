package yunqi.zhibei.steward.binding.zookeeper.curator.v5;

import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import java.util.Objects;

public final class Curator5Binding
        implements ResourceBinding<Curator5Configuration, CuratorFramework> {

    public static BoundResource<CuratorFramework> start(Curator5Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new Curator5Binding());
    }

    @Override
    public CuratorFramework create(Curator5Configuration configuration) {
        CuratorFramework client = nativeBuilder(configuration).build();
        try {
            client.start();
            return client;
        } catch (RuntimeException | Error failure) {
            client.close();
            throw failure;
        }
    }

    @Override
    public Health check(CuratorFramework client) {
        Objects.requireNonNull(client, "client");
        try {
            return client.checkExists().forPath("/") == null
                    ? Health.unhealthy(ProbeScope.REMOTE)
                    : Health.healthy(ProbeScope.REMOTE);
        } catch (Exception failure) {
            return Health.unhealthy(ProbeScope.REMOTE);
        }
    }

    @Override
    public void close(CuratorFramework client) {
        Objects.requireNonNull(client, "client").close();
    }

    static CuratorFrameworkFactory.Builder nativeBuilder(
            Curator5Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                .connectString(configuration.connectString())
                .sessionTimeoutMs(configuration.sessionTimeoutMillis())
                .connectionTimeoutMs(configuration.connectionTimeoutMillis())
                .retryPolicy(new ExponentialBackoffRetry(
                        configuration.retryBaseSleepMillis(),
                        configuration.retryMaxRetries()));
        configuration.namespace().ifPresent(builder::namespace);
        return builder;
    }
}
