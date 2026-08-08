package yunqi.zhibei.steward.binding.redis.redisson.v4;

import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public final class Redisson4Binding
        implements ResourceBinding<Redisson4Configuration, RedissonClient> {

    public static BoundResource<RedissonClient> start(Redisson4Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new Redisson4Binding());
    }

    static final String SDK_MAJOR = "4";
    private static final long CLOSE_TIMEOUT_SECONDS = 30L;
    private static final String VERSION_RESOURCE =
            "META-INF/maven/org.redisson/redisson/pom.properties";

    public Redisson4Binding() {
        verifyDependencyVersion();
    }

    @Override
    public RedissonClient create(Redisson4Configuration configuration) {
        return Redisson.create(nativeConfiguration(configuration));
    }

    @Override
    public Health check(RedissonClient client) {
        Objects.requireNonNull(client, "client");
        return client.getRedisNodes(RedisNodes.SINGLE).pingAll()
                ? Health.healthy(ProbeScope.REMOTE)
                : Health.unhealthy(ProbeScope.REMOTE);
    }

    @Override
    public void close(RedissonClient client) {
        Objects.requireNonNull(client, "client")
                .shutdown(0L, CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    static Config nativeConfiguration(Redisson4Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Redisson4Configuration.Connection connection = configuration.connection();
        Redisson4Configuration.Pool pool = configuration.pool();
        Redisson4Configuration.Client client = configuration.client();

        Config result = baseConfiguration(client);
        connection.username().ifPresent(result::setUsername);
        connection.password().ifPresent(result::setPassword);

        SingleServerConfig server = result.useSingleServer()
                .setAddress(connection.address().toString())
                .setDatabase(connection.database())
                .setIdleConnectionTimeout(toIntMillis(
                        connection.idleConnectionTimeout(), "idleConnectionTimeout"))
                .setConnectTimeout(toIntMillis(connection.connectTimeout(), "connectTimeout"))
                .setTimeout(toIntMillis(connection.commandTimeout(), "commandTimeout"))
                .setSubscriptionTimeout(toIntMillis(
                        connection.subscriptionTimeout(), "subscriptionTimeout"))
                .setRetryAttempts(connection.retryAttempts())
                .setSubscriptionsPerConnection(connection.subscriptionsPerConnection())
                .setPingConnectionInterval(toIntMillis(
                        connection.pingConnectionInterval(), "pingConnectionInterval"))
                .setDnsMonitoringInterval(toMillis(connection.dnsMonitoringInterval()))
                .setConnectionMinimumIdleSize(pool.connectionMinimumIdleSize())
                .setConnectionPoolSize(pool.connectionPoolSize())
                .setSubscriptionConnectionMinimumIdleSize(
                        pool.subscriptionConnectionMinimumIdleSize())
                .setSubscriptionConnectionPoolSize(pool.subscriptionConnectionPoolSize());
        connection.clientName().ifPresent(server::setClientName);
        return result;
    }

    static Config baseConfiguration(Redisson4Configuration.Client client) {
        return new Config()
                .setThreads(client.threads())
                .setNettyThreads(client.nettyThreads())
                .setLockWatchdogTimeout(toMillis(client.lockWatchdogTimeout()))
                .setFairLockWaitTimeout(toMillis(client.fairLockWaitTimeout()))
                .setLockWatchdogBatchSize(client.lockWatchdogBatchSize())
                .setCheckLockSyncedSlaves(client.checkLockSyncedSlaves())
                .setKeepPubSubOrder(client.keepPubSubOrder())
                .setUseScriptCache(client.useScriptCache())
                .setLazyInitialization(client.lazyInitialization())
                .setTcpKeepAlive(client.tcpKeepAlive())
                .setTcpNoDelay(client.tcpNoDelay())
                .setProtocol(client.protocol())
                .setTransportMode(client.transportMode());
    }

    static void verifyDependencyVersion() {
        Properties properties = new Properties();
        ClassLoader loader = RedissonClient.class.getClassLoader();
        try (InputStream input = loader == null
                ? ClassLoader.getSystemResourceAsStream(VERSION_RESOURCE)
                : loader.getResourceAsStream(VERSION_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Cannot verify Redisson version; missing " + VERSION_RESOURCE);
            }
            properties.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read Redisson version", failure);
        }
        String actual = properties.getProperty("version");
        if (actual == null || !SDK_MAJOR.equals(majorOf(actual))) {
            throw new IllegalStateException(
                    "Redisson binding requires major " + SDK_MAJOR + " but loaded " + actual);
        }
    }

    private static String majorOf(String version) {
        int separator = version.indexOf('.');
        return separator < 0 ? version : version.substring(0, separator);
    }

    static int toIntMillis(Duration duration, String field) {
        long milliseconds = toMillis(duration);
        if (milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + Integer.MAX_VALUE + " milliseconds");
        }
        return (int) milliseconds;
    }

    static long toMillis(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Duration is too large", failure);
        }
    }
}
