package yunqi.zhibei.steward.binding.mysql.connectorj.v9;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import yunqi.zhibei.steward.support.testing.BindingContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlConnectorJ9BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new MySqlConnectorJ9Binding(),
                configuration("db-a.internal"),
                configuration("db-b.internal"),
                "mysql-secret");
    }

    @Test
    void mapsConfigurationAndBuildsALazyDataSource() {
        MySqlConnectorJ9Configuration configuration = configuration("db.internal");

        HikariConfig hikari = MySqlConnectorJ9Binding.hikariConfiguration(configuration);
        assertThat(hikari.getJdbcUrl()).isEqualTo("jdbc:mysql://db.internal:3307/orders");
        assertThat(hikari.getDataSourceProperties())
                .containsEntry("user", "app")
                .containsEntry("password", "mysql-secret")
                .containsEntry("sslMode", "REQUIRED")
                .containsEntry("connectTimeout", "4000")
                .containsEntry("useServerPrepStmts", "true");
        assertThat(hikari.getMinimumIdle()).isZero();
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(2);
        assertThat(hikari.getInitializationFailTimeout()).isEqualTo(-1L);
        assertThat(configuration.toString()).doesNotContain("mysql-secret");

        MySqlConnectorJ9Binding binding = new MySqlConnectorJ9Binding();
        HikariDataSource dataSource = binding.create(configuration);
        binding.close(dataSource);
        assertThat(dataSource.isClosed()).isTrue();
    }

    private static MySqlConnectorJ9Configuration configuration(String host) {
        return new MySqlConnectorJ9Configuration(
                host,
                3307,
                "orders",
                Optional.of("app"),
                Optional.of("mysql-secret"),
                true,
                Duration.ofSeconds(4),
                Duration.ofSeconds(40),
                pool(),
                Map.of("useServerPrepStmts", "true"));
    }

    private static MySqlConnectorJ9Configuration.Pool pool() {
        return new MySqlConnectorJ9Configuration.Pool(
                0,
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30));
    }
}
