package yunqi.zhibei.steward.binding.milvus.sdk.v2;

import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;

import java.util.Objects;

public final class MilvusSdk2Binding
        implements ResourceBinding<MilvusSdk2Configuration, MilvusClientV2> {

    public static BoundResource<MilvusClientV2> start(MilvusSdk2Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new MilvusSdk2Binding());
    }

    @Override
    public MilvusClientV2 create(MilvusSdk2Configuration configuration) {
        return new MilvusClientV2(nativeConfiguration(configuration));
    }

    @Override
    public Health check(MilvusClientV2 client) {
        Objects.requireNonNull(client, "client");
        try {
            return Boolean.TRUE.equals(client.checkHealth().getIsHealthy())
                    ? Health.healthy(ProbeScope.REMOTE)
                    : Health.unhealthy(ProbeScope.REMOTE);
        } catch (RuntimeException failure) {
            return Health.unhealthy(ProbeScope.REMOTE);
        }
    }

    @Override
    public void close(MilvusClientV2 client) {
        Objects.requireNonNull(client, "client").close();
    }

    static ConnectConfig nativeConfiguration(MilvusSdk2Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        ConnectConfig.ConnectConfigBuilder<?, ?> builder = ConnectConfig.builder()
                .uri(configuration.uri().toString())
                .dbName(configuration.database())
                .secure(configuration.secure())
                .connectTimeoutMs(configuration.connectTimeout().toMillis());
        configuration.token().ifPresent(builder::token);
        return builder.build();
    }
}
