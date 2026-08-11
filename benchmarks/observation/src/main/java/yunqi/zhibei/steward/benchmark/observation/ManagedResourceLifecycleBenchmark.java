package yunqi.zhibei.steward.benchmark.observation;

import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import yunqi.zhibei.steward.control.configuration.ConfigurationSnapshot;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
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
import java.util.concurrent.TimeUnit;

/** Compares disabled-observation owner construction and complete reconciliation. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
public class ManagedResourceLifecycleBenchmark {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(5);
    private static final TestBinding BINDING = new TestBinding();

    @State(Scope.Thread)
    public static class RefreshState {

        @Param({"OMITTED", "EXPLICIT_NOOP"})
        public String observation;

        private BenchmarkSource source;
        private ManagedResource<TestResource, Integer> managed;

        @Setup(Level.Trial)
        public void setup() {
            source = new BenchmarkSource();
            var builder = ManagedResource.builder(source, BINDING);
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

    @State(Scope.Thread)
    public static class ConstructionState {

        @Param({"OMITTED", "EXPLICIT_NOOP"})
        public String observation;

        private ManagedResource<TestResource, Integer> create() {
            var builder = ManagedResource.builder(new BenchmarkSource(), BINDING);
            if ("EXPLICIT_NOOP".equals(observation)) {
                builder.lifecycleEvents(LifecycleEventBuffer.noop());
            }
            return builder.build();
        }
    }

    @Benchmark
    public long constructAndClose(ConstructionState state) {
        try (ManagedResource<TestResource, Integer> managed = state.create()) {
            return managed.status().activeRevision();
        }
    }

    @Benchmark
    public long reconcile(RefreshState state) {
        state.source.advance();
        state.managed.refresh();
        requireIdle(state.managed);
        return state.managed.status().activeRevision();
    }

    private static void requireIdle(ManagedResource<?, ?> managed) {
        if (!managed.awaitIdle(IDLE_TIMEOUT)) {
            throw new IllegalStateException("managed lifecycle did not become idle");
        }
    }

    private static final class BenchmarkSource implements ConfigurationSource<Integer> {

        private ConfigurationSnapshot<Integer> snapshot = ConfigurationSnapshot.of(1, 1);

        @Override
        public ConfigurationSnapshot<Integer> snapshot() {
            return snapshot;
        }

        @Override
        public Subscription subscribe(Runnable listener) {
            return () -> {
            };
        }

        private void advance() {
            long revision = snapshot.revision() + 1;
            snapshot = ConfigurationSnapshot.of(revision, Math.toIntExact(revision));
        }
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

    private record TestResource(int value) {
    }
}
