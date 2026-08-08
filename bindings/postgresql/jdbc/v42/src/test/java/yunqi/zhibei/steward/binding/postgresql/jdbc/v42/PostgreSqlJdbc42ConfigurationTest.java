package yunqi.zhibei.steward.binding.postgresql.jdbc.v42;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSqlJdbc42ConfigurationTest {
    @Test
    void buildsFromDatabaseDefaultsAndCopiesChanges() {
        var configured = PostgreSqlJdbc42Configuration.builder("orders")
                .host("postgres.internal")
                .credentials("app", "secret")
                .driverProperty("ApplicationName", "orders-service")
                .build();
        var updated = configured.toBuilder().port(5433).build();

        assertThat(configured.database()).isEqualTo("orders");
        assertThat(configured.port()).isEqualTo(5432);
        assertThat(configured.password()).contains("secret");
        assertThat(configured.driverProperties())
                .containsEntry("ApplicationName", "orders-service");
        assertThat(updated.host()).isEqualTo("postgres.internal");
        assertThat(updated.port()).isEqualTo(5433);
    }
}
