package yunqi.zhibei.steward.interaction.mysql.library.client.connectorj.v9;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;

public final class MySqlConnectorJ9Binding
        implements ResourceBinding<MySqlConnectorJ9Configuration, HikariDataSource> {

    public static BoundResource<HikariDataSource> start(
            MySqlConnectorJ9Configuration configuration) throws Exception {
        return BoundResource.start(configuration, new MySqlConnectorJ9Binding());
    }

    @Override
    public HikariDataSource create(MySqlConnectorJ9Configuration configuration) {
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

    static HikariConfig hikariConfiguration(MySqlConnectorJ9Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Properties properties = new Properties();
        configuration.username().ifPresent(value -> properties.setProperty("user", value));
        configuration.password().ifPresent(value -> properties.setProperty("password", value));
        properties.setProperty("sslMode", configuration.tls() ? "REQUIRED" : "DISABLED");
        properties.setProperty(
                "connectTimeout", Long.toString(configuration.connectTimeout().toMillis()));
        properties.setProperty(
                "socketTimeout", Long.toString(configuration.socketTimeout().toMillis()));
        configuration.driverProperties().forEach(properties::setProperty);

        MySqlConnectorJ9Configuration.Pool pool = configuration.pool();
        HikariConfig result = new HikariConfig();
        result.setJdbcUrl("jdbc:mysql://" + configuration.host() + ":" + configuration.port()
                + "/" + configuration.database());
        result.setDataSourceProperties(properties);
        configurePool(result, pool);
        return result;
    }

    private static void configurePool(
            HikariConfig target,
            MySqlConnectorJ9Configuration.Pool pool) {
        target.setMinimumIdle(pool.minimumIdle());
        target.setMaximumPoolSize(pool.maximumSize());
        target.setConnectionTimeout(pool.acquireTimeout().toMillis());
        target.setIdleTimeout(pool.idleTimeout().toMillis());
        target.setMaxLifetime(pool.maximumLifetime().toMillis());
        target.setInitializationFailTimeout(-1L);
    }
}
