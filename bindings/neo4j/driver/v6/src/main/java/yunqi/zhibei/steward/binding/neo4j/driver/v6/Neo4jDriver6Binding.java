package yunqi.zhibei.steward.binding.neo4j.driver.v6;

import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class Neo4jDriver6Binding
        implements ResourceBinding<Neo4jDriver6Configuration, Driver> {

    public static BoundResource<Driver> start(Neo4jDriver6Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new Neo4jDriver6Binding());
    }

    @Override
    public Driver create(Neo4jDriver6Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return GraphDatabase.driver(
                configuration.uri(),
                authentication(configuration),
                nativeConfiguration(configuration));
    }

    @Override
    public Health check(Driver driver) {
        Objects.requireNonNull(driver, "driver");
        try {
            driver.verifyConnectivity();
            return Health.healthy(ProbeScope.REMOTE);
        } catch (RuntimeException failure) {
            return Health.unhealthy(ProbeScope.REMOTE);
        }
    }

    @Override
    public void close(Driver driver) {
        Objects.requireNonNull(driver, "driver").close();
    }

    static Config nativeConfiguration(Neo4jDriver6Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Neo4jDriver6Configuration.Pool pool = configuration.pool();
        return Config.builder()
                .withConnectionTimeout(
                        configuration.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .withConnectionAcquisitionTimeout(
                        configuration.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .withConnectionLivenessCheckTimeout(
                        pool.idleTestInterval().toMillis(), TimeUnit.MILLISECONDS)
                .withMaxConnectionPoolSize(pool.maximumSize())
                .withMaxConnectionLifetime(
                        pool.maximumLifetime().toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    private static AuthToken authentication(Neo4jDriver6Configuration configuration) {
        return configuration.username()
                .map(username -> AuthTokens.basic(
                        username, configuration.password().orElseThrow()))
                .orElseGet(AuthTokens::none);
    }
}
