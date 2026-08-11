package yunqi.zhibei.steward.benchmark.observation;

import yunqi.zhibei.steward.telemetry.LifecycleEvent;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Measures disabled, accepted, overflow, and contended buffer publication paths. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
public class LifecycleEventBufferBenchmark {

    private static final int BATCH_SIZE = 256;
    private static final Instant STARTED_AT = Instant.EPOCH;
    private static final Duration DURATION = Duration.ZERO;

    @State(Scope.Thread)
    public static class NoopState {
        private final LifecycleEventBuffer buffer = LifecycleEventBuffer.noop();
    }

    @State(Scope.Thread)
    public static class AcceptedState {
        private final LifecycleEventBuffer buffer = LifecycleEventBuffer.create(BATCH_SIZE);
    }

    @State(Scope.Thread)
    public static class FullState {
        private LifecycleEventBuffer buffer;

        @Setup(Level.Iteration)
        public void fill() {
            buffer = LifecycleEventBuffer.create(1);
            publish(buffer);
        }
    }

    @State(Scope.Group)
    public static class ContendedState {
        private final LifecycleEventBuffer buffer = LifecycleEventBuffer.create(BATCH_SIZE);
    }

    @Benchmark
    public boolean publishIntoNoopBuffer(NoopState state) {
        return publish(state.buffer);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    public int publishAndDrainAcceptedBatch(AcceptedState state) {
        for (int index = 0; index < BATCH_SIZE; index++) {
            publish(state.buffer);
        }
        return state.buffer.drain(BATCH_SIZE).size();
    }

    @Benchmark
    public boolean publishIntoFullBuffer(FullState state) {
        return publish(state.buffer);
    }

    @Benchmark
    @Group("contended")
    @GroupThreads(3)
    public boolean contendedPublish(ContendedState state) {
        return publish(state.buffer);
    }

    @Benchmark
    @Group("contended")
    @GroupThreads(1)
    public LifecycleEvent contendedDrain(ContendedState state) {
        return state.buffer.poll();
    }

    private static boolean publish(LifecycleEventBuffer buffer) {
        return buffer.publish(
                LifecycleEvent.Stage.REFRESH,
                LifecycleEvent.Outcome.SUCCESS,
                2,
                2,
                STARTED_AT,
                DURATION,
                null);
    }
}
