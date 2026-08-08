package yunqi.zhibei.steward.binding.clickhouse.jdbc.v0;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import yunqi.zhibei.steward.support.testing.BindingContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ClickHouseJdbc0BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new ClickHouseJdbc0Binding(),
                configuration("db-a.internal"),
                configuration("db-b.internal"),
                "clickhouse-secret");
    }

    @Test
    void mapsConfigurationAndBuildsALazyDataSource() {
        ClickHouseJdbc0Configuration configuration = configuration("db.internal");

        HikariConfig hikari = ClickHouseJdbc0Binding.hikariConfiguration(configuration);
        assertThat(hikari.getJdbcUrl())
                .isEqualTo("jdbc:clickhouse://db.internal:8124/analytics");
        assertThat(hikari.getDataSourceProperties())
                .containsEntry("user", "app")
                .containsEntry("password", "clickhouse-secret")
                .containsEntry("ssl", "true")
                .containsEntry("connection_timeout", "4000")
                .containsEntry("compress", "1");
        assertThat(hikari.getMinimumIdle()).isZero();
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(2);
        assertThat(hikari.getInitializationFailTimeout()).isEqualTo(-1L);
        assertThat(configuration.toString()).doesNotContain("clickhouse-secret");

        ClickHouseJdbc0Binding binding = new ClickHouseJdbc0Binding();
        HikariDataSource dataSource = binding.create(configuration);
        binding.close(dataSource);
        assertThat(dataSource.isClosed()).isTrue();
    }

    private static ClickHouseJdbc0Configuration configuration(String host) {
        return new ClickHouseJdbc0Configuration(
                host,
                8124,
                "analytics",
                Optional.of("app"),
                Optional.of("clickhouse-secret"),
                true,
                Duration.ofSeconds(4),
                Duration.ofSeconds(40),
                pool(),
                Map.of("compress", "1"));
    }

    private static ClickHouseJdbc0Configuration.Pool pool() {
        return new ClickHouseJdbc0Configuration.Pool(
                0,
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30));
    }
}
