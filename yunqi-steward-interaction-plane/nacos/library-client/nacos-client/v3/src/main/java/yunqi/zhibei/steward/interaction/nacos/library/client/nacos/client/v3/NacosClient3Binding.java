package yunqi.zhibei.steward.interaction.nacos.library.client.nacos.client.v3;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;
import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;

import java.util.Objects;
import java.util.Properties;

public final class NacosClient3Binding
        implements ResourceBinding<NacosClient3Configuration, NacosClient3Handle> {

    public static BoundResource<NacosClient3Handle> start(NacosClient3Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new NacosClient3Binding());
    }

    private final NacosServiceFactory serviceFactory;

    public NacosClient3Binding() {
        this(new NacosServiceFactory() {
            @Override
            public ConfigService createConfigService(Properties properties) throws Exception {
                return NacosFactory.createConfigService(properties);
            }

            @Override
            public NamingService createNamingService(Properties properties) throws Exception {
                return NacosFactory.createNamingService(properties);
            }
        });
    }

    NacosClient3Binding(NacosServiceFactory serviceFactory) {
        this.serviceFactory = Objects.requireNonNull(serviceFactory, "serviceFactory");
    }

    @Override
    public NacosClient3Handle create(NacosClient3Configuration configuration) throws Exception {
        Objects.requireNonNull(configuration, "configuration");
        Properties properties = configuration.properties();
        ConfigService configService = serviceFactory.createConfigService(properties);
        try {
            NamingService namingService = serviceFactory.createNamingService(properties);
            return new NacosClient3Handle(configService, namingService);
        } catch (Exception | Error failure) {
            try {
                configService.shutDown();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public Health check(NacosClient3Handle handle) {
        Objects.requireNonNull(handle, "handle");
        try {
            return "UP".equalsIgnoreCase(handle.configService().getServerStatus())
                            && "UP".equalsIgnoreCase(handle.namingService().getServerStatus())
                    ? Health.healthy(ProbeScope.REMOTE)
                    : Health.unhealthy(ProbeScope.REMOTE);
        } catch (RuntimeException exception) {
            return Health.unhealthy(ProbeScope.REMOTE);
        }
    }

    @Override
    public void close(NacosClient3Handle handle) throws Exception {
        Objects.requireNonNull(handle, "handle");
        Exception failure = null;
        try {
            handle.configService().shutDown();
        } catch (Exception exception) {
            failure = exception;
        }
        try {
            handle.namingService().shutDown();
        } catch (Exception exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
