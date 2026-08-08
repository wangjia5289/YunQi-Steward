package yunqi.zhibei.steward.binding.postgresql.jdbc.v42;

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

public final class PostgreSqlJdbc42Binding
        implements ResourceBinding<PostgreSqlJdbc42Configuration, HikariDataSource> {

    public static BoundResource<HikariDataSource> start(
            PostgreSqlJdbc42Configuration configuration) throws Exception {
        return BoundResource.start(configuration, new PostgreSqlJdbc42Binding());
    }

    @Override
    public HikariDataSource create(PostgreSqlJdbc42Configuration configuration) {
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

    static HikariConfig hikariConfiguration(PostgreSqlJdbc42Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Properties properties = new Properties();
        configuration.username().ifPresent(value -> properties.setProperty("user", value));
        configuration.password().ifPresent(value -> properties.setProperty("password", value));
        properties.setProperty("sslmode", configuration.tls() ? "require" : "disable");
        properties.setProperty(
                "connectTimeout", Long.toString(configuration.connectTimeout().toSeconds()));
        properties.setProperty(
                "socketTimeout", Long.toString(configuration.socketTimeout().toSeconds()));
        configuration.driverProperties().forEach(properties::setProperty);

        PostgreSqlJdbc42Configuration.Pool pool = configuration.pool();
        HikariConfig result = new HikariConfig();
        result.setJdbcUrl("jdbc:postgresql://" + configuration.host() + ":"
                + configuration.port() + "/" + configuration.database());
        result.setDataSourceProperties(properties);
        configurePool(result, pool);
        return result;
    }

    private static void configurePool(
            HikariConfig target,
            PostgreSqlJdbc42Configuration.Pool pool) {
        target.setMinimumIdle(pool.minimumIdle());
        target.setMaximumPoolSize(pool.maximumSize());
        target.setConnectionTimeout(pool.acquireTimeout().toMillis());
        target.setIdleTimeout(pool.idleTimeout().toMillis());
        target.setMaxLifetime(pool.maximumLifetime().toMillis());
        target.setInitializationFailTimeout(-1L);
    }
}
