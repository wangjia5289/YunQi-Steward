package yunqi.zhibei.steward.interaction.xxljob.library.client.core.v2;

import com.xxl.job.core.executor.impl.XxlJobSimpleExecutor;
import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.StartupBinding;

import java.util.Objects;

public final class XxlJobCore2Binding
        implements StartupBinding<XxlJobSimpleExecutor, XxlJobSimpleExecutor> {

    public static BoundResource<XxlJobSimpleExecutor> start(XxlJobSimpleExecutor executor)
            throws Exception {
        return BoundResource.start(executor, new XxlJobCore2Binding());
    }

    @Override
    public XxlJobSimpleExecutor create(XxlJobSimpleExecutor executor) throws Exception {
        XxlJobSimpleExecutor configured = Objects.requireNonNull(executor, "executor");
        try {
            configured.start();
            return configured;
        } catch (Exception | Error failure) {
            try {
                configured.destroy();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public Health check(XxlJobSimpleExecutor executor) {
        Objects.requireNonNull(executor, "executor");
        return Health.healthy(ProbeScope.STARTUP_ONLY);
    }

    @Override
    public void close(XxlJobSimpleExecutor executor) {
        Objects.requireNonNull(executor, "executor").destroy();
    }
}
