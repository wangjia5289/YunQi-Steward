package yunqi.zhibei.steward.telemetry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleEventFanOutTest {

    @Test
    void distributesRetainedEventsToEveryBranchInSourceOrder() {
        LifecycleEventFanOut fanOut = LifecycleEventFanOut.start(
                16, Map.of("jfr", 8, "otel", 8), Duration.ofMillis(1), 4);

        publishAccepted(fanOut.source(), LifecycleEvent.Stage.START, 1);
        publishAccepted(fanOut.source(), LifecycleEvent.Stage.REFRESH, 2);
        publishAccepted(fanOut.source(), LifecycleEvent.Stage.CLOSE, 1);
        fanOut.close();

        assertThat(fanOut.drainedEvents()).isEqualTo(3);
        assertThat(fanOut.deliveredEvents("jfr")).isEqualTo(3);
        assertThat(fanOut.deliveredEvents("otel")).isEqualTo(3);
        assertThat(fanOut.droppedEvents("jfr")).isZero();
        assertThat(fanOut.droppedEvents("otel")).isZero();
        assertThat(fanOut.branch("jfr").drain(8))
                .extracting(LifecycleEvent::sequence)
                .containsExactly(1L, 2L, 3L);
        assertThat(fanOut.branch("otel").drain(8))
                .extracting(LifecycleEvent::sequence)
                .containsExactly(1L, 2L, 3L);
        assertThat(fanOut.source().isClosed()).isTrue();
        assertThat(fanOut.branch("jfr").isClosed()).isTrue();
        assertThat(fanOut.branch("otel").isClosed()).isTrue();
        assertThat(fanOut.isClosed()).isTrue();
    }

    @Test
    void aFullBranchDropsOnlyItsOwnCopies() {
        LifecycleEventFanOut fanOut = LifecycleEventFanOut.start(
                32, Map.of("slow", 1, "healthy", 16), Duration.ofMillis(1), 8);

        for (int sequence = 1; sequence <= 8; sequence++) {
            publishAccepted(fanOut.source(), LifecycleEvent.Stage.REFRESH, sequence);
        }
        fanOut.close();

        assertThat(fanOut.drainedEvents()).isEqualTo(8);
        assertThat(fanOut.deliveredEvents("slow")).isEqualTo(1);
        assertThat(fanOut.droppedEvents("slow")).isEqualTo(7);
        assertThat(fanOut.deliveredEvents("healthy")).isEqualTo(8);
        assertThat(fanOut.droppedEvents("healthy")).isZero();
        assertThat(fanOut.branch("slow").drain(8))
                .extracting(LifecycleEvent::sequence)
                .containsExactly(1L);
        assertThat(fanOut.branch("healthy").drain(16))
                .extracting(LifecycleEvent::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }

    @Test
    void aPrematurelyClosedBranchIsAccountedWithoutAffectingAnotherBranch() {
        LifecycleEventFanOut fanOut = LifecycleEventFanOut.start(
                8, Map.of("closed", 4, "healthy", 4), Duration.ofDays(1), 4);
        fanOut.branch("closed").close();

        publishAccepted(fanOut.source(), LifecycleEvent.Stage.START, 1);
        publishAccepted(fanOut.source(), LifecycleEvent.Stage.CLOSE, 1);
        fanOut.close();

        assertThat(fanOut.deliveredEvents("closed")).isZero();
        assertThat(fanOut.droppedEvents("closed")).isEqualTo(2);
        assertThat(fanOut.deliveredEvents("healthy")).isEqualTo(2);
        assertThat(fanOut.droppedEvents("healthy")).isZero();
        assertThat(fanOut.branch("healthy").drain(4))
                .extracting(LifecycleEvent::sequence)
                .containsExactly(1L, 2L);
    }

    @Test
    void concurrentClosePerformsOneCompleteFinalDistribution() throws Exception {
        LifecycleEventFanOut fanOut = LifecycleEventFanOut.start(
                64, Map.of("first", 64, "second", 64), Duration.ofDays(1), 3);
        for (int sequence = 1; sequence <= 32; sequence++) {
            publishAccepted(fanOut.source(), LifecycleEvent.Stage.OBSERVE, sequence);
        }
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var closes = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> executor.submit(() -> {
                        start.await();
                        fanOut.close();
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (var close : closes) {
                close.get(5, TimeUnit.SECONDS);
            }
        }

        assertThat(fanOut.isClosed()).isTrue();
        assertThat(fanOut.drainedEvents()).isEqualTo(32);
        assertThat(fanOut.deliveredEvents("first")).isEqualTo(32);
        assertThat(fanOut.deliveredEvents("second")).isEqualTo(32);
        assertThat(fanOut.droppedEvents("first")).isZero();
        assertThat(fanOut.droppedEvents("second")).isZero();
    }

    @Test
    void rejectsInvalidDefinitionsAndUnknownBranches() {
        assertThatThrownBy(() -> LifecycleEventFanOut.start(0, Map.of("logs", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("capacity must be at least 1");
        assertThatThrownBy(() -> LifecycleEventFanOut.start(1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("at least one branch is required");
        assertThatThrownBy(() -> LifecycleEventFanOut.start(1, Map.of("unsafe name", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branchName");
        assertThatThrownBy(() -> LifecycleEventFanOut.start(1, Map.of("logs", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("branch capacity must be at least 1");
        assertThatThrownBy(() -> LifecycleEventFanOut.start(
                1, Map.of("logs", 1), Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pollInterval must be positive");
        assertThatThrownBy(() -> LifecycleEventFanOut.start(
                1, Map.of("logs", 1), Duration.ofMillis(1), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("batchSize must be at least 1");

        LifecycleEventFanOut fanOut = LifecycleEventFanOut.start(1, Map.of("logs", 1));
        try {
            assertThat(fanOut.branchNames()).containsExactly("logs");
            assertThatThrownBy(() -> fanOut.branch("missing"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("unknown branchName");
        } finally {
            fanOut.close();
        }
    }

    private static void publishAccepted(
            LifecycleEventBuffer events,
            LifecycleEvent.Stage stage,
            long generation) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!events.publish(
                stage,
                LifecycleEvent.Outcome.SUCCESS,
                generation,
                generation,
                Instant.EPOCH,
                Duration.ZERO,
                null)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("event was not accepted before deadline");
            }
            Thread.onSpinWait();
        }
    }
}
