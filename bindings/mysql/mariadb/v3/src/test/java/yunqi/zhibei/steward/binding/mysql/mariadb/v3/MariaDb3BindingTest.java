package yunqi.zhibei.steward.binding.mysql.mariadb.v3;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import yunqi.zhibei.steward.support.testing.BindingContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MariaDb3BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new MariaDb3Binding(),
                configuration("db-a.internal"),
                configuration("db-b.internal"),
                "mariadb-secret");
    }

    @Test
    void mapsConfigurationAndBuildsALazyDataSource() {
        MariaDb3Configuration configuration = configuration("db.internal");

        HikariConfig hikari = MariaDb3Binding.hikariConfiguration(configuration);
        assertThat(hikari.getJdbcUrl()).isEqualTo("jdbc:mariadb://db.internal:3307/orders");
        assertThat(hikari.getDataSourceProperties())
                .containsEntry("user", "app")
                .containsEntry("password", "mariadb-secret")
                .containsEntry("useSsl", "true")
                .containsEntry("connectTimeout", "4000")
                .containsEntry("useServerPrepStmts", "true");
        assertThat(hikari.getMinimumIdle()).isZero();
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(2);
        assertThat(hikari.getInitializationFailTimeout()).isEqualTo(-1L);
        assertThat(configuration.toString()).doesNotContain("mariadb-secret");

        MariaDb3Binding binding = new MariaDb3Binding();
        HikariDataSource dataSource = binding.create(configuration);
        binding.close(dataSource);
        assertThat(dataSource.isClosed()).isTrue();
    }

    private static MariaDb3Configuration configuration(String host) {
        return new MariaDb3Configuration(
                host,
                3307,
                "orders",
                Optional.of("app"),
                Optional.of("mariadb-secret"),
                true,
                Duration.ofSeconds(4),
                Duration.ofSeconds(40),
                pool(),
                Map.of("useServerPrepStmts", "true"));
    }

    private static MariaDb3Configuration.Pool pool() {
        return new MariaDb3Configuration.Pool(
                0,
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30));
    }
}
