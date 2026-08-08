package yunqi.zhibei.steward.binding.seata.tm.v2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeataTm2BuilderTest {
    @Test
    void buildsFromRequiredIdentityAndCopiesChanges() {
        var configured = SeataTm2Configuration.builder("orders", "orders_tx_group").build();
        var updated = configured.toBuilder().applicationId("orders-v2").build();

        assertThat(configured.transactionServiceGroup()).isEqualTo("orders_tx_group");
        assertThat(updated.applicationId()).isEqualTo("orders-v2");
        assertThat(updated.transactionServiceGroup()).isEqualTo("orders_tx_group");
    }
}
