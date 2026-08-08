package yunqi.zhibei.steward.binding.mysql.connectorj.v9;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlConnectorJ9ConfigurationTest {
    @Test
    void buildsFromDatabaseDefaultsAndCopiesChanges() {
        var configured = MySqlConnectorJ9Configuration.builder("orders")
                .host("mysql.internal")
                .credentials("app", "secret")
                .driverProperty("useServerPrepStmts", "true")
                .build();
        var updated = configured.toBuilder().port(3307).build();

        assertThat(configured.database()).isEqualTo("orders");
        assertThat(configured.port()).isEqualTo(3306);
        assertThat(configured.password()).contains("secret");
        assertThat(configured.driverProperties()).containsEntry("useServerPrepStmts", "true");
        assertThat(updated.host()).isEqualTo("mysql.internal");
        assertThat(updated.port()).isEqualTo(3307);
    }
}
