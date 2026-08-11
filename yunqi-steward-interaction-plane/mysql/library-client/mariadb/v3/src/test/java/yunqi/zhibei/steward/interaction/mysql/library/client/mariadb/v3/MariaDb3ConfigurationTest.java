package yunqi.zhibei.steward.interaction.mysql.library.client.mariadb.v3;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MariaDb3ConfigurationTest {
    @Test
    void buildsFromDatabaseDefaultsAndCopiesChanges() {
        var configured = MariaDb3Configuration.builder("orders")
                .host("mariadb.internal")
                .credentials("app", "secret")
                .driverProperty("useBulkStmts", "true")
                .build();
        var updated = configured.toBuilder().port(3307).build();

        assertThat(configured.database()).isEqualTo("orders");
        assertThat(configured.port()).isEqualTo(3306);
        assertThat(configured.password()).contains("secret");
        assertThat(configured.driverProperties()).containsEntry("useBulkStmts", "true");
        assertThat(updated.host()).isEqualTo("mariadb.internal");
        assertThat(updated.port()).isEqualTo(3307);
    }
}
