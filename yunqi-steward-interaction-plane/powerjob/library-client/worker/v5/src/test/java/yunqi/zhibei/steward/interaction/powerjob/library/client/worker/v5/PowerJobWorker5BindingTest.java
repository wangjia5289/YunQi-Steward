package yunqi.zhibei.steward.interaction.powerjob.library.client.worker.v5;

import yunqi.zhibei.steward.control.resource.StartupBinding;
import org.junit.jupiter.api.Test;
import tech.powerjob.worker.PowerJobWorker;
import tech.powerjob.worker.common.PowerJobWorkerConfig;

import static org.assertj.core.api.Assertions.assertThat;

class PowerJobWorker5BindingTest {
    @Test
    void acceptsTheCompleteNativeWorkerConfiguration() {
        StartupBinding<PowerJobWorkerConfig, PowerJobWorker> binding =
                new PowerJobWorker5Binding();
        assertThat(binding).isNotNull();
    }
}
