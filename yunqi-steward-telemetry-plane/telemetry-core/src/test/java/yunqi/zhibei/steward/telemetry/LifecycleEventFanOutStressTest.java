package yunqi.zhibei.steward.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleEventFanOutStressTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void burstSeparatesSourceOverflowFromSaturatedBranchLoss() {
        LifecycleEventFanOut fanOut = LifecycleEventFanOut.start(
                8, Map.of("saturated", 1, "healthy", 20_000), Duration.ofDays(1), 64);
        awaitFanOutParked();
        long accepted = 0;
        long rejected = 0;
        for (int index = 0; index < 20_000; index++) {
            if (publish(fanOut.source(), index)) {
                accepted++;
            } else {
                rejected++;
            }
        }
        fanOut.close();

        assertThat(accepted).isPositive();
        assertThat(rejected).isPositive();
        assertThat(fanOut.sourceDroppedEvents()).isEqualTo(rejected);
        assertThat(fanOut.drainedEvents()).isEqualTo(accepted);
        assertThat(fanOut.deliveredEvents("saturated")
                + fanOut.droppedEvents("saturated")).isEqualTo(accepted);
        assertThat(fanOut.droppedEvents("saturated")).isPositive();
        assertThat(fanOut.deliveredEvents("healthy")).isEqualTo(accepted);
        assertThat(fanOut.droppedEvents("healthy")).isZero();
        assertStrictlyIncreasing(fanOut.branch("healthy").drain(20_000));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void longFinalDrainPreservesOrderAndLeavesNoFanOutThread() {
        int eventCount = 4_096;
        LifecycleEventFanOut fanOut = LifecycleEventFanOut.start(
                eventCount, Map.of("events", eventCount), Duration.ofDays(1), 7);
        awaitFanOutParked();
        for (int index = 0; index < eventCount; index++) {
            publishUntilAccepted(fanOut.source(), index);
        }

        fanOut.close();
        List<LifecycleEvent> events = fanOut.branch("events").drain(eventCount);

        assertThat(fanOut.drainedEvents()).isEqualTo(eventCount);
        assertThat(events).hasSize(eventCount);
        assertStrictlyIncreasing(events);
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(
                thread -> thread.getName().equals("middleware-lifecycle-fanout")
                        && thread.isAlive());
    }

    private static boolean publish(LifecycleEventBuffer buffer, long revision) {
        return buffer.publish(
                LifecycleEvent.Stage.REFRESH,
                LifecycleEvent.Outcome.SUCCESS,
                revision,
                revision,
                Instant.EPOCH,
                Duration.ZERO,
                null);
    }

    private static void publishUntilAccepted(LifecycleEventBuffer buffer, long revision) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!publish(buffer, revision)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("event was not accepted before deadline");
            }
            Thread.onSpinWait();
        }
    }

    private static void assertStrictlyIncreasing(List<LifecycleEvent> events) {
        long previous = 0;
        for (LifecycleEvent event : events) {
            assertThat(event.sequence()).isGreaterThan(previous);
            previous = event.sequence();
        }
    }

    private static void awaitFanOutParked() {
        await(() -> Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().equals("middleware-lifecycle-fanout")
                        && thread.getState() == Thread.State.TIMED_WAITING));
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not satisfied before deadline");
            }
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
    }
}
