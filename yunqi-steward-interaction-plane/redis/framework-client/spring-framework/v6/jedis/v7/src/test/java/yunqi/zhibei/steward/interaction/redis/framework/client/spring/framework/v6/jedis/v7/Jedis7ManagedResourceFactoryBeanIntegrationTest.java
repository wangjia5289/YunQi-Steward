package yunqi.zhibei.steward.interaction.redis.framework.client.spring.framework.v6.jedis.v7;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPooled;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;
import yunqi.zhibei.steward.control.configuration.file.properties.PropertiesFileConfigurationSource;
import yunqi.zhibei.steward.control.configuration.nacos.v3.Nacos3ConfigurationSource;
import yunqi.zhibei.steward.control.resource.refresh.FailureSnapshot;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResourceStatus;
import yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7.Jedis7Configuration;

import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class Jedis7ManagedResourceFactoryBeanIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7.4.2-alpine");

    @Container
    private static final GenericContainer<?> REDIS_A = redisContainer();

    @Container
    private static final GenericContainer<?> REDIS_B = redisContainer();

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void programmaticSourceRefreshesRollsBackDrainsAndClosesThroughSpring() throws Exception {
        var source = new MutableConfigurationSource<>(configuration(REDIS_A));
        AnnotationConfigApplicationContext context = startContext(source);
        Jedis7ManagedResourceFactoryBean factory = context.getBean(
                "&redis", Jedis7ManagedResourceFactoryBean.class);
        ManagedResource<JedisPooled, Jedis7Configuration> managed = factory.getObject();

        try {
            assertThat(context.getBean("redis")).isSameAs(managed);
            managed.execute(client -> client.set("steward:spring:programmatic", "redis-a"));

            try (ManagedResource.Lease<JedisPooled> oldLease = managed.acquire()) {
                source.update(configuration(REDIS_B));
                await(() -> managed.status().activeRevision() == 2,
                        "the second Redis generation to become active");

                assertThat(get(managed, "steward:spring:programmatic")).isNull();
                managed.execute(client -> client.set(
                        "steward:spring:programmatic", "redis-b"));
                assertThat(get(oldLease, "steward:spring:programmatic"))
                        .isEqualTo("redis-a");
                assertThat(managed.awaitIdle(Duration.ofMillis(50))).isFalse();
            }

            assertThat(managed.awaitIdle(TIMEOUT)).isTrue();

            source.update(configuration("127.0.0.1", 1));
            assertThat(managed.awaitIdle(TIMEOUT)).isTrue();
            assertFailedReplacementKeepsRevision(managed, 2, 3);
            assertThat(get(managed, "steward:spring:programmatic"))
                    .isEqualTo("redis-b");

            source.update(configuration(REDIS_A).toBuilder()
                    .password("wrong-password")
                    .build());
            assertThat(managed.awaitIdle(TIMEOUT)).isTrue();
            assertFailedReplacementKeepsRevision(managed, 2, 4);

            source.update(configuration(REDIS_A));
            assertThat(managed.awaitIdle(TIMEOUT)).isTrue();
            assertThat(managed.status().activeRevision()).isEqualTo(5);
            assertThat(managed.lastRefreshFailure()).isEmpty();
            assertThat(get(managed, "steward:spring:programmatic"))
                    .isEqualTo("redis-a");
        } finally {
            context.close();
        }

        assertThat(managed.isTerminated()).isTrue();
        assertThat(managed.status().lifecycle())
                .isEqualTo(ManagedResourceStatus.Lifecycle.TERMINATED);
        assertThatThrownBy(factory::getObject).isInstanceOf(IllegalStateException.class);

        source.update(configuration(REDIS_B));
        assertThat(managed.status().lifecycle())
                .isEqualTo(ManagedResourceStatus.Lifecycle.TERMINATED);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void propertiesSourceRefreshesAndRecoversFromInvalidContent(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("redis.properties");
        writeProperties(file, REDIS_A);

        try (PropertiesFileConfigurationSource<Jedis7Configuration> source =
                     PropertiesFileConfigurationSource.open(
                             file, Jedis7ManagedResourceFactoryBeanIntegrationTest::loadProperties)) {
            AnnotationConfigApplicationContext context = startContext(source);
            ManagedResource<JedisPooled, Jedis7Configuration> managed = context.getBean(
                    "&redis", Jedis7ManagedResourceFactoryBean.class).getObject();
            try {
                managed.execute(client -> client.set("steward:spring:properties", "redis-a"));

                writeProperties(file, REDIS_B);
                source.refresh();
                await(() -> managed.status().activeRevision() == 2,
                        "the properties-driven Redis generation to become active");
                assertThat(get(managed, "steward:spring:properties")).isNull();

                Files.writeString(file, "port=invalid\n", StandardCharsets.ISO_8859_1);
                source.refresh();
                await(() -> managed.lastRefreshFailure().isPresent(),
                        "the invalid properties failure to reach the managed resource");
                assertThat(managed.status().activeRevision()).isEqualTo(2);
                assertThat(managed.lastRefreshFailure()).hasValueSatisfying(failure ->
                        assertThat(failure.stage())
                                .isEqualTo(FailureSnapshot.Stage.CONFIGURATION_SOURCE));

                writeProperties(file, REDIS_A);
                source.refresh();
                await(() -> managed.status().activeRevision() == 3,
                        "the repaired properties configuration to become active");
                assertThat(managed.lastRefreshFailure()).isEmpty();
                assertThat(get(managed, "steward:spring:properties"))
                        .isEqualTo("redis-a");
            } finally {
                context.close();
            }
            assertThat(managed.isTerminated()).isTrue();
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void nacosSourceRefreshesAndRecoversFromInvalidContent() throws Exception {
        FakeNacosConfigService fake = new FakeNacosConfigService(content(REDIS_A));
        try (Nacos3ConfigurationSource<Jedis7Configuration> source =
                     Nacos3ConfigurationSource.open(
                             fake.service(),
                             "redis.properties",
                             "PROD",
                             Duration.ofSeconds(1),
                             Jedis7ManagedResourceFactoryBeanIntegrationTest::loadNacosContent)) {
            AnnotationConfigApplicationContext context = startContext(source);
            ManagedResource<JedisPooled, Jedis7Configuration> managed = context.getBean(
                    "&redis", Jedis7ManagedResourceFactoryBean.class).getObject();
            try {
                managed.execute(client -> client.set("steward:spring:nacos", "redis-a"));

                fake.emit(content(REDIS_B));
                await(() -> managed.status().activeRevision() == 2,
                        "the Nacos-driven Redis generation to become active");
                assertThat(get(managed, "steward:spring:nacos")).isNull();

                fake.emit("invalid-content");
                await(() -> managed.lastRefreshFailure().isPresent(),
                        "the invalid Nacos failure to reach the managed resource");
                assertThat(managed.status().activeRevision()).isEqualTo(2);

                fake.emit(content(REDIS_A));
                await(() -> managed.status().activeRevision() == 3,
                        "the repaired Nacos configuration to become active");
                assertThat(managed.lastRefreshFailure()).isEmpty();
                assertThat(get(managed, "steward:spring:nacos"))
                        .isEqualTo("redis-a");
            } finally {
                context.close();
            }
            assertThat(managed.isTerminated()).isTrue();
            assertThat(fake.hasListener()).isTrue();
        }
        assertThat(fake.hasListener()).isFalse();
    }

    private static AnnotationConfigApplicationContext startContext(
            ConfigurationSource<Jedis7Configuration> source) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(
                "redis",
                Jedis7ManagedResourceFactoryBean.class,
                () -> new Jedis7ManagedResourceFactoryBean(source));
        context.refresh();
        return context;
    }

    private static void assertFailedReplacementKeepsRevision(
            ManagedResource<JedisPooled, Jedis7Configuration> managed,
            long activeRevision,
            long desiredRevision) {
        assertThat(managed.status().activeRevision()).isEqualTo(activeRevision);
        assertThat(managed.status().desiredRevision()).isEqualTo(desiredRevision);
        assertThat(managed.lastRefreshFailure()).isPresent();
    }

    private static String get(
            ManagedResource<JedisPooled, Jedis7Configuration> managed,
            String key) throws Exception {
        return managed.execute(client -> client.get(key));
    }

    private static String get(ManagedResource.Lease<JedisPooled> lease, String key)
            throws Exception {
        return lease.execute(client -> client.get(key));
    }

    private static Jedis7Configuration loadProperties(Properties properties) {
        return configuration(
                properties.getProperty("host"),
                Integer.parseInt(properties.getProperty("port")));
    }

    private static Jedis7Configuration loadNacosContent(String content) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(content.replace('|', '\n')));
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid Redis configuration content", failure);
        }
        return loadProperties(properties);
    }

    private static void writeProperties(Path file, GenericContainer<?> redis) throws Exception {
        Files.writeString(file, content(redis).replace('|', '\n') + '\n',
                StandardCharsets.ISO_8859_1);
    }

    private static String content(GenericContainer<?> redis) {
        return "host=" + redis.getHost() + "|port=" + redis.getMappedPort(6379);
    }

    private static Jedis7Configuration configuration(GenericContainer<?> redis) {
        return configuration(redis.getHost(), redis.getMappedPort(6379));
    }

    private static Jedis7Configuration configuration(String host, int port) {
        return Jedis7Configuration.builder()
                .host(host)
                .port(port)
                .connectTimeout(Duration.ofMillis(250))
                .commandTimeout(Duration.ofMillis(250))
                .build();
    }

    private static GenericContainer<?> redisContainer() {
        return new GenericContainer<>(REDIS_IMAGE)
                .withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
                .withStartupTimeout(Duration.ofSeconds(60));
    }

    private static void await(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for " + description);
            }
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
        }
    }

    private static final class FakeNacosConfigService {

        private final String initialContent;
        private final AtomicReference<Listener> listener = new AtomicReference<>();
        private final ConfigService service;

        private FakeNacosConfigService(String initialContent) {
            this.initialContent = initialContent;
            service = ConfigService.class.cast(Proxy.newProxyInstance(
                    ConfigService.class.getClassLoader(),
                    new Class<?>[]{ConfigService.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getConfigAndSignListener" -> {
                            listener.set((Listener) arguments[3]);
                            yield this.initialContent;
                        }
                        case "removeListener" -> {
                            listener.compareAndSet((Listener) arguments[2], null);
                            yield null;
                        }
                        case "toString" -> "ConfigServiceProxy";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> throw new UnsupportedOperationException(method.toString());
                    }));
        }

        private ConfigService service() {
            return service;
        }

        private boolean hasListener() {
            return listener.get() != null;
        }

        private void emit(String content) {
            Listener current = listener.get();
            if (current != null) {
                current.getExecutor().execute(() -> current.receiveConfigInfo(content));
            }
        }
    }
}
