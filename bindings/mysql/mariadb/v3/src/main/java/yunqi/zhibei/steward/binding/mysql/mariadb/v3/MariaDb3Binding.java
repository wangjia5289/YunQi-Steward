package yunqi.zhibei.steward.binding.mysql.mariadb.v3;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;

public final class MariaDb3Binding
        implements ResourceBinding<MariaDb3Configuration, HikariDataSource> {

    public static BoundResource<HikariDataSource> start(MariaDb3Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new MariaDb3Binding());
    }

    @Override
    public HikariDataSource create(MariaDb3Configuration configuration) {
        return new HikariDataSource(hikariConfiguration(configuration));
    }

    @Override
    public Health check(HikariDataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(3)
                    ? Health.healthy(ProbeScope.REMOTE)
                    : Health.unhealthy(ProbeScope.REMOTE);
        } catch (SQLException | RuntimeException exception) {
            return Health.unhealthy(ProbeScope.REMOTE);
        }
    }

    @Override
    public void close(HikariDataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource").close();
    }

    static HikariConfig hikariConfiguration(MariaDb3Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Properties properties = new Properties();
        configuration.username().ifPresent(value -> properties.setProperty("user", value));
        configuration.password().ifPresent(value -> properties.setProperty("password", value));
        properties.setProperty("useSsl", Boolean.toString(configuration.tls()));
        properties.setProperty(
                "connectTimeout", Long.toString(configuration.connectTimeout().toMillis()));
        properties.setProperty(
                "socketTimeout", Long.toString(configuration.socketTimeout().toMillis()));
        configuration.driverProperties().forEach(properties::setProperty);

        MariaDb3Configuration.Pool pool = configuration.pool();
        HikariConfig result = new HikariConfig();
        result.setJdbcUrl("jdbc:mariadb://" + configuration.host() + ":" + configuration.port()
                + "/" + configuration.database());
        result.setDataSourceProperties(properties);
        configurePool(result, pool);
        return result;
    }

    private static void configurePool(HikariConfig target, MariaDb3Configuration.Pool pool) {
        target.setMinimumIdle(pool.minimumIdle());
        target.setMaximumPoolSize(pool.maximumSize());
        target.setConnectionTimeout(pool.acquireTimeout().toMillis());
        target.setIdleTimeout(pool.idleTimeout().toMillis());
        target.setMaxLifetime(pool.maximumLifetime().toMillis());
        target.setInitializationFailTimeout(-1L);
    }
}
