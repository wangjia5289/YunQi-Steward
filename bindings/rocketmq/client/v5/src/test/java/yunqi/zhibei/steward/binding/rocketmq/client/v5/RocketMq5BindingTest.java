package yunqi.zhibei.steward.binding.rocketmq.client.v5;

import yunqi.zhibei.steward.lifecycle.StartupBinding;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RocketMq5BindingTest {
    @Test
    void isStartupOnlyAndValidatesConfiguration() {
        StartupBinding<RocketMq5Configuration, DefaultMQProducer> binding =
                new RocketMq5Binding();
        assertThat(binding).isNotNull();
        assertThat(RocketMq5Configuration.defaults("orders").producerGroup()).isEqualTo("orders");
        assertThatThrownBy(() -> RocketMq5Configuration.defaults(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
