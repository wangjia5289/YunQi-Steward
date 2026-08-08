package yunqi.zhibei.steward.binding.redis.lettuce.v6;

import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import java.time.Duration;

public final class Lettuce6Binding
        implements ResourceBinding<Lettuce6Configuration, RedisClient> {

    public static BoundResource<RedisClient> start(Lettuce6Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new Lettuce6Binding());
    }

    static final String SDK_MAJOR = "6";
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);
    private static final String VERSION_RESOURCE =
            "META-INF/maven/io.lettuce/lettuce-core/pom.properties";

    public Lettuce6Binding() {
        verifyDependencyVersion();
    }

    @Override
    public RedisClient create(Lettuce6Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        RedisClient client = RedisClient.create(redisUri(configuration));
        client.setOptions(clientOptions(configuration));
        return client;
    }

    @Override
    public Health check(RedisClient client) {
        Objects.requireNonNull(client, "client");
        try (StatefulRedisConnection<byte[], byte[]> connection =
                     client.connect(ByteArrayCodec.INSTANCE)) {
            return "PONG".equals(connection.sync().ping())
                    ? Health.healthy(ProbeScope.REMOTE)
                    : Health.unhealthy(ProbeScope.REMOTE);
        }
    }

    @Override
    public void close(RedisClient client) {
        Objects.requireNonNull(client, "client").shutdown(Duration.ZERO, CLOSE_TIMEOUT);
    }

    static RedisURI redisUri(Lettuce6Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        RedisURI.Builder uri = RedisURI.Builder.redis(configuration.host(), configuration.port())
                .withDatabase(configuration.database())
                .withSsl(configuration.tls())
                .withTimeout(configuration.commandTimeout());
        if (configuration.username().isPresent()) {
            uri.withAuthentication(
                    configuration.username().orElseThrow(),
                    configuration.password().orElse("").toCharArray());
        } else {
            configuration.password().ifPresent(
                    password -> uri.withPassword(password.toCharArray()));
        }
        return uri.build();
    }

    static ClientOptions clientOptions(Lettuce6Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(configuration.connectTimeout())
                        .build())
                .build();
    }

    static void verifyDependencyVersion() {
        Properties properties = new Properties();
        ClassLoader loader = RedisClient.class.getClassLoader();
        try (InputStream input = loader == null
                ? ClassLoader.getSystemResourceAsStream(VERSION_RESOURCE)
                : loader.getResourceAsStream(VERSION_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Cannot verify Lettuce version; missing " + VERSION_RESOURCE);
            }
            properties.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read Lettuce version", failure);
        }
        String actual = properties.getProperty("version");
        if (actual == null || !SDK_MAJOR.equals(majorOf(actual))) {
            throw new IllegalStateException(
                    "Lettuce binding requires major " + SDK_MAJOR + " but loaded " + actual);
        }
    }

    private static String majorOf(String version) {
        int separator = version.indexOf('.');
        return separator < 0 ? version : version.substring(0, separator);
    }
}
