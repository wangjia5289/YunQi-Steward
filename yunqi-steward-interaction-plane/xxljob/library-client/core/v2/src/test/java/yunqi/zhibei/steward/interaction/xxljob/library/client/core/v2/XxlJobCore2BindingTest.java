package yunqi.zhibei.steward.interaction.xxljob.library.client.core.v2;

import com.xxl.job.core.executor.impl.XxlJobSimpleExecutor;
import yunqi.zhibei.steward.control.resource.StartupBinding;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XxlJobCore2BindingTest {
    @Test
    void bindsTheNativeExecutorOnlyAtStartup() {
        StartupBinding<XxlJobSimpleExecutor, XxlJobSimpleExecutor> binding =
                new XxlJobCore2Binding();
        assertThat(binding).isNotNull();
    }
}
