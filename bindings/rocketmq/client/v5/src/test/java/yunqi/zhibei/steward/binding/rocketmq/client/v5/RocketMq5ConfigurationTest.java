package yunqi.zhibei.steward.binding.rocketmq.client.v5;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RocketMq5ConfigurationTest {
    @Test
    void buildsFromRequiredGroupAndCopiesDefaults() {
        var configured = RocketMq5Configuration.builder("orders-producer")
                .nameServerAddress("rocketmq.internal:9876")
                .build();
        var updated = configured.toBuilder().sendTimeout(Duration.ofSeconds(5)).build();

        assertThat(configured.producerGroup()).isEqualTo("orders-producer");
        assertThat(configured.retryTimesWhenSendFailed()).isEqualTo(2);
        assertThat(updated.nameServerAddress()).isEqualTo("rocketmq.internal:9876");
        assertThat(updated.sendTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void rejectsTimeoutsThatCannotBeRepresentedByTheNativeClient() {
        assertThatThrownBy(() -> RocketMq5Configuration.builder("orders-producer")
                .sendTimeout(Duration.ofNanos(1))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("millisecond");
    }
}
