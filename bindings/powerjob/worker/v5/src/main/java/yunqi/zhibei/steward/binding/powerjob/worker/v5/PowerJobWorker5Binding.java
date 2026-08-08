package yunqi.zhibei.steward.binding.powerjob.worker.v5;

import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.StartupBinding;
import tech.powerjob.worker.PowerJobWorker;
import tech.powerjob.worker.common.PowerJobWorkerConfig;

import java.util.Objects;

public final class PowerJobWorker5Binding
        implements StartupBinding<PowerJobWorkerConfig, PowerJobWorker> {

    public static BoundResource<PowerJobWorker> start(PowerJobWorkerConfig configuration)
            throws Exception {
        return BoundResource.start(configuration, new PowerJobWorker5Binding());
    }

    @Override
    public PowerJobWorker create(PowerJobWorkerConfig configuration) throws Exception {
        PowerJobWorker worker = new PowerJobWorker(
                Objects.requireNonNull(configuration, "configuration"));
        try {
            worker.init();
            return worker;
        } catch (Exception | Error failure) {
            try {
                worker.destroy();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public Health check(PowerJobWorker worker) {
        Objects.requireNonNull(worker, "worker");
        return Health.healthy(ProbeScope.STARTUP_ONLY);
    }

    @Override
    public void close(PowerJobWorker worker) throws Exception {
        Objects.requireNonNull(worker, "worker").destroy();
    }
}
