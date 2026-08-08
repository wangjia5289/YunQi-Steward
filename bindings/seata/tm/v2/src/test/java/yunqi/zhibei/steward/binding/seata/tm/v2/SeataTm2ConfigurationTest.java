package yunqi.zhibei.steward.binding.seata.tm.v2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeataTm2ConfigurationTest {
    @Test
    void validatesTheProcessGlobalCoordinatesWithoutAddingTransactionOperations() {
        SeataTm2Configuration configuration =
                new SeataTm2Configuration("orders", "orders_tx_group");
        assertThat(configuration.applicationId()).isEqualTo("orders");
        assertThatThrownBy(() -> new SeataTm2Configuration(" ", "group"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
