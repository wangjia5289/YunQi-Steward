package yunqi.zhibei.steward.interaction.nacos.library.client.nacos.client.v3;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;
import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NacosClient3BindingTest {

    @Test
    void checksAndClosesBothNativeServicesOffline() throws Exception {
        AtomicInteger configShutdowns = new AtomicInteger();
        AtomicInteger namingShutdowns = new AtomicInteger();
        ConfigService configService = service(ConfigService.class, "UP", configShutdowns);
        NamingService namingService = service(NamingService.class, "UP", namingShutdowns);
        NacosClient3Handle handle = new NacosClient3Handle(configService, namingService);
        NacosClient3Binding binding = new NacosClient3Binding();

        assertThat(binding.check(handle).isHealthy()).isTrue();
        binding.close(handle);

        assertThat(configShutdowns).hasValue(1);
        assertThat(namingShutdowns).hasValue(1);
        assertThat(handle.configService()).isSameAs(configService);
        assertThat(handle.namingService()).isSameAs(namingService);
    }

    @Test
    void closesAHealthyCandidateOnlyOnceThroughItsOwner() throws Exception {
        AtomicInteger configShutdowns = new AtomicInteger();
        AtomicInteger namingShutdowns = new AtomicInteger();
        ConfigService configService = service(ConfigService.class, "UP", configShutdowns);
        NamingService namingService = service(NamingService.class, "UP", namingShutdowns);
        NacosClient3Binding binding = new NacosClient3Binding(
                factory((ignored) -> configService, (ignored) -> namingService));

        BoundResource<NacosClient3Handle> bound = BoundResource.start(configuration(1, "orders-a"), binding);
        bound.close();
        bound.close();

        assertThat(configShutdowns).hasValue(1);
        assertThat(namingShutdowns).hasValue(1);
    }

    @Test
    void closesConfigServiceWhenNamingServiceCreationFails() {
        AtomicInteger configShutdowns = new AtomicInteger();
        ConfigService configService = service(ConfigService.class, "UP", configShutdowns);
        NacosClient3Binding binding = new NacosClient3Binding(
                factory((ignored) -> configService, ignored -> {
                    throw new IllegalStateException("naming creation failed");
                }));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> binding.create(configuration(1, "orders-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("naming creation failed");
        assertThat(configShutdowns).hasValue(1);
    }

    @Test
    void rollsBackAnUnhealthyReplacementAndKeepsTheActiveServicePair() throws Exception {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger configShutdowns = new AtomicInteger();
        AtomicInteger namingShutdowns = new AtomicInteger();
        NacosClient3Binding binding = new NacosClient3Binding(new NacosServiceFactory() {
            @Override
            public ConfigService createConfigService(Properties properties) {
                int number = created.incrementAndGet();
                return service(ConfigService.class, number == 1 ? "UP" : "DOWN", configShutdowns);
            }

            @Override
            public NamingService createNamingService(Properties properties) {
                int number = created.incrementAndGet();
                return service(NamingService.class, number == 2 ? "UP" : "DOWN", namingShutdowns);
            }
        });
        MutableConfigurationSource<NacosClient3Configuration> source =
                new MutableConfigurationSource<>(configuration(1, "orders-a"));
        ManagedResource<NacosClient3Handle, NacosClient3Configuration> managed =
                ManagedResource.bind(source, binding);

        NacosClient3Handle initial = managed.execute((NacosClient3Handle handle) -> handle);
        source.update(configuration(2, "orders-b"));
        assertThat(managed.awaitIdle(java.time.Duration.ofSeconds(2))).isTrue();

        assertThat(managed.execute((NacosClient3Handle handle) -> handle)).isSameAs(initial);
        assertThat(configShutdowns).hasValue(1);
        assertThat(namingShutdowns).hasValue(1);
        assertThat(managed.lastRefreshFailure()).isPresent();
        managed.close();
        assertThat(configShutdowns).hasValue(2);
        assertThat(namingShutdowns).hasValue(2);
    }

    @Test
    void mapsClientPropertiesWithoutOverridingExplicitConnectionFields() {
        Map<String, String> additional = new LinkedHashMap<>();
        additional.put("custom.property", "custom-value");
        additional.put("serverAddr", "ignored:8848");
        NacosClient3Configuration configuration = new NacosClient3Configuration(
                "nacos.internal:8848",
                Optional.of("nacos-user"),
                Optional.of("password-secret"),
                Optional.of("tenant-a"),
                additional);

        additional.clear();
        Properties properties = configuration.properties();

        assertThat(properties)
                .containsEntry("serverAddr", "nacos.internal:8848")
                .containsEntry("username", "nacos-user")
                .containsEntry("password", "password-secret")
                .containsEntry("namespace", "tenant-a")
                .containsEntry("custom.property", "custom-value");
        assertThat(configuration.clientProperties()).containsEntry("custom.property", "custom-value");
        assertThat(configuration.toString())
                .doesNotContain("password-secret", "custom-value")
                .contains("password=[REDACTED]");
    }

    private static <T> T service(
            Class<T> serviceType,
            String status,
            AtomicInteger shutdowns) {
        Object proxy = Proxy.newProxyInstance(
                serviceType.getClassLoader(),
                new Class<?>[]{serviceType},
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "getServerStatus" -> status;
                    case "shutDown" -> {
                        shutdowns.incrementAndGet();
                        yield null;
                    }
                    case "toString" -> serviceType.getSimpleName() + "Proxy";
                    case "hashCode" -> System.identityHashCode(ignored);
                    case "equals" -> ignored == arguments[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                });
        return serviceType.cast(proxy);
    }

    private static NacosClient3Configuration configuration(int port, String namespace) {
        return NacosClient3Configuration.builder()
                .serverAddress("127.0.0.1:" + port)
                .credentials("nacos-user", "password-secret")
                .namespace(namespace)
                .build();
    }

    private static NacosServiceFactory factory(
            NacosServiceCreator<ConfigService> config,
            NacosServiceCreator<NamingService> naming) {
        return new NacosServiceFactory() {
            @Override
            public ConfigService createConfigService(Properties properties) throws Exception {
                return config.create(properties);
            }

            @Override
            public NamingService createNamingService(Properties properties) throws Exception {
                return naming.create(properties);
            }
        };
    }

    @FunctionalInterface
    private interface NacosServiceCreator<T> {
        T create(Properties properties) throws Exception;
    }
}
