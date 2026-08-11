package yunqi.zhibei.steward.interaction.postgresql.library.client.jdbc.v42;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import yunqi.zhibei.steward.support.testing.BindingContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSqlJdbc42BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new PostgreSqlJdbc42Binding(),
                configuration("db-a.internal"),
                configuration("db-b.internal"),
                "postgres-secret");
    }

    @Test
    void mapsConfigurationAndBuildsALazyDataSource() {
        PostgreSqlJdbc42Configuration configuration = configuration("db.internal");

        HikariConfig hikari = PostgreSqlJdbc42Binding.hikariConfiguration(configuration);
        assertThat(hikari.getJdbcUrl())
                .isEqualTo("jdbc:postgresql://db.internal:5433/orders");
        assertThat(hikari.getDataSourceProperties())
                .containsEntry("user", "app")
                .containsEntry("password", "postgres-secret")
                .containsEntry("sslmode", "require")
                .containsEntry("connectTimeout", "4")
                .containsEntry("ApplicationName", "orders-service");
        assertThat(hikari.getMinimumIdle()).isZero();
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(2);
        assertThat(hikari.getInitializationFailTimeout()).isEqualTo(-1L);
        assertThat(configuration.toString()).doesNotContain("postgres-secret");

        PostgreSqlJdbc42Binding binding = new PostgreSqlJdbc42Binding();
        HikariDataSource dataSource = binding.create(configuration);
        binding.close(dataSource);
        assertThat(dataSource.isClosed()).isTrue();
    }

    private static PostgreSqlJdbc42Configuration configuration(String host) {
        return new PostgreSqlJdbc42Configuration(
                host,
                5433,
                "orders",
                Optional.of("app"),
                Optional.of("postgres-secret"),
                true,
                Duration.ofSeconds(4),
                Duration.ofSeconds(40),
                pool(),
                Map.of("ApplicationName", "orders-service"));
    }

    private static PostgreSqlJdbc42Configuration.Pool pool() {
        return new PostgreSqlJdbc42Configuration.Pool(
                0,
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30));
    }
}
