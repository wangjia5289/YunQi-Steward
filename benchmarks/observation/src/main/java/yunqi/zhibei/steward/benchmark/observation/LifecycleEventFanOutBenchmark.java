package yunqi.zhibei.steward.benchmark.observation;

import yunqi.zhibei.steward.telemetry.LifecycleEvent;
import yunqi.zhibei.steward.telemetry.LifecycleEventFanOut;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Measures enabled asynchronous fan-out distribution by fixed branch and batch counts. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
public class LifecycleEventFanOutBenchmark {

    private static final int EVENTS = 256;

    @State(Scope.Thread)
    public static class FanOutState {
        @Param({"1", "2", "4"})
        private int branches;

        @Param({"1", "16", "64"})
        private int batchSize;

        private LifecycleEventFanOut fanOut;

        @Setup(Level.Invocation)
        public void start() {
            Map<String, Integer> capacities = new LinkedHashMap<>();
            for (int index = 0; index < branches; index++) {
                capacities.put("branch-" + index, EVENTS);
            }
            fanOut = LifecycleEventFanOut.start(
                    EVENTS, capacities, Duration.ofDays(1), batchSize);
        }

        @TearDown(Level.Invocation)
        public void close() {
            fanOut.close();
        }
    }

    @Benchmark
    @OperationsPerInvocation(EVENTS)
    public long publishDistributeAndDrain(FanOutState state) {
        for (int index = 0; index < EVENTS; index++) {
            while (!state.fanOut.source().publish(
                    LifecycleEvent.Stage.REFRESH,
                    LifecycleEvent.Outcome.SUCCESS,
                    index,
                    index,
                    Instant.EPOCH,
                    Duration.ZERO,
                    null)) {
                Thread.onSpinWait();
            }
        }
        state.fanOut.close();
        long drainedCopies = 0;
        for (String branch : state.fanOut.branchNames()) {
            drainedCopies += state.fanOut.branch(branch).drain(EVENTS).size();
        }
        return drainedCopies;
    }
}
