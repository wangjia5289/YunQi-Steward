package yunqi.zhibei.steward.binding.powerjob.worker.v5;

import yunqi.zhibei.steward.lifecycle.StartupBinding;
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
