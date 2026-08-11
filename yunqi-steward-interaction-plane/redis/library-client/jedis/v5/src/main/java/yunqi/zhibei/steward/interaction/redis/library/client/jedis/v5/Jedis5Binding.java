package yunqi.zhibei.steward.interaction.redis.library.client.jedis.v5;

import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/** Creates, checks, and closes a native Jedis 5 pooled client. */
public final class Jedis5Binding
        implements ResourceBinding<Jedis5Configuration, JedisPooled> {

    public static BoundResource<JedisPooled> start(Jedis5Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new Jedis5Binding());
    }

    private static final String EXPECTED_JEDIS_MAJOR_VERSION = "5";
    private static final String VERSION_RESOURCE =
            "META-INF/maven/redis.clients/jedis/pom.properties";

    public Jedis5Binding() {
        verifyDependencyMajorVersion();
    }

    @Override
    public JedisPooled create(Jedis5Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        verifyDependencyMajorVersion();
        return new JedisPooled(
                new HostAndPort(configuration.host(), configuration.port()),
                clientConfig(configuration),
                poolConfig(configuration));
    }

    @Override
    public Health check(JedisPooled client) {
        Objects.requireNonNull(client, "client");
        return "PONG".equalsIgnoreCase(client.ping())
                ? Health.healthy(ProbeScope.REMOTE)
                : Health.unhealthy(ProbeScope.REMOTE);
    }

    @Override
    public void close(JedisPooled client) {
        Objects.requireNonNull(client, "client").close();
    }

    static DefaultJedisClientConfig clientConfig(Jedis5Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(Math.toIntExact(configuration.connectTimeout().toMillis()))
                .socketTimeoutMillis(Math.toIntExact(configuration.commandTimeout().toMillis()))
                .database(configuration.database())
                .ssl(configuration.tls());
        if (configuration.username().isPresent()) {
            builder.user(configuration.username().orElseThrow());
            builder.password(configuration.password().orElse(""));
        } else {
            configuration.password().ifPresent(builder::password);
        }
        return builder.build();
    }

    static GenericObjectPoolConfig<Connection> poolConfig(Jedis5Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        GenericObjectPoolConfig<Connection> pool = new GenericObjectPoolConfig<>();
        pool.setMinIdle(configuration.minimumIdle());
        pool.setMaxIdle(configuration.maximumIdle());
        pool.setMaxTotal(configuration.maximumTotal());
        pool.setMaxWait(configuration.acquireTimeout());
        return pool;
    }

    static void verifyDependencyMajorVersion() {
        Properties properties = new Properties();
        ClassLoader classLoader = JedisPooled.class.getClassLoader();
        try (InputStream input = classLoader == null
                ? ClassLoader.getSystemResourceAsStream(VERSION_RESOURCE)
                : classLoader.getResourceAsStream(VERSION_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Cannot verify Jedis dependency version; missing " + VERSION_RESOURCE);
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot read Jedis dependency version from " + VERSION_RESOURCE,
                    exception);
        }

        String actualVersion = properties.getProperty("version");
        String actualMajorVersion = actualVersion == null
                ? null
                : actualVersion.split("\\.", 2)[0];
        if (!EXPECTED_JEDIS_MAJOR_VERSION.equals(actualMajorVersion)) {
            throw new IllegalStateException(
                    "Jedis 5 binding requires dependency major version "
                            + EXPECTED_JEDIS_MAJOR_VERSION
                            + " but loaded " + actualVersion);
        }
    }
}
