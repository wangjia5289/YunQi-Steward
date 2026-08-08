package yunqi.zhibei.steward.binding.clickhouse.jdbc.v0;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClickHouseJdbc0ConfigurationTest {
    @Test
    void buildsFromDatabaseDefaultsAndCopiesChanges() {
        var configured = ClickHouseJdbc0Configuration.builder("orders")
                .host("clickhouse.internal")
                .credentials("app", "secret")
                .driverProperty("compress", "1")
                .build();
        var updated = configured.toBuilder().port(8443).tls(true).build();

        assertThat(configured.database()).isEqualTo("orders");
        assertThat(configured.port()).isEqualTo(8123);
        assertThat(configured.password()).contains("secret");
        assertThat(configured.driverProperties()).containsEntry("compress", "1");
        assertThat(updated.host()).isEqualTo("clickhouse.internal");
        assertThat(updated.port()).isEqualTo(8443);
        assertThat(updated.tls()).isTrue();
    }
}
