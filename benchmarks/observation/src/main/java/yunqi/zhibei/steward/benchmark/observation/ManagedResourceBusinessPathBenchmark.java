package yunqi.zhibei.steward.benchmark.observation;

import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;
import yunqi.zhibei.steward.control.resource.refresh.ResourceOperation;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Compares the business path when observation is omitted or explicitly disabled. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
// Keep the lease allocation visible so paired trials cannot diverge through
// fork-specific escape analysis. This benchmark compares observation modes,
// not HotSpot's ability to scalar-replace the existing business-path lease.
@Fork(value = 2, jvmArgsAppend = "-XX:-DoEscapeAnalysis")
public class ManagedResourceBusinessPathBenchmark {

    private static final ResourceOperation<TestResource, Integer, RuntimeException> READ =
            resource -> resource.value;
    private static final ResourceOperation<
            TestResource, CompletionStage<Integer>, RuntimeException> READ_ASYNC =
            resource -> resource.completed;

    @State(Scope.Thread)
    public static class OwnerState {

        @Param({"OMITTED", "EXPLICIT_NOOP"})
        public String observation;

        private ManagedResource<TestResource, Integer> managed;

        @Setup(Level.Trial)
        public void setup() {
            var builder = ManagedResource.builder(
                    new MutableConfigurationSource<>(42), new TestBinding());
            if ("EXPLICIT_NOOP".equals(observation)) {
                builder.lifecycleEvents(LifecycleEventBuffer.noop());
            }
            managed = builder.build();
        }

        @TearDown(Level.Trial)
        public void teardown() {
            managed.close();
        }
    }

    @Benchmark
    public int acquireAndClose(OwnerState state) {
        try (ManagedResource.Lease<TestResource> lease = state.managed.acquire()) {
            return lease.execute(READ);
        }
    }

    @Benchmark
    public int execute(OwnerState state) {
        return state.managed.execute(READ);
    }

    @Benchmark
    public CompletionStage<Integer> executeAsync(OwnerState state) {
        return state.managed.executeAsync(READ_ASYNC);
    }

    private static final class TestBinding implements ResourceBinding<Integer, TestResource> {

        @Override
        public TestResource create(Integer configuration) {
            return new TestResource(configuration);
        }

        @Override
        public Health check(TestResource resource) {
            return Health.healthy(ProbeScope.LOCAL);
        }

        @Override
        public void close(TestResource resource) {
            // No native work in the microbenchmark fixture.
        }
    }

    private static final class TestResource {

        private final int value;
        private final CompletionStage<Integer> completed;

        private TestResource(int value) {
            this.value = value;
            completed = CompletableFuture.completedFuture(value);
        }
    }
}
