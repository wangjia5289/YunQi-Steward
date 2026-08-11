package yunqi.zhibei.steward.telemetry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleEventBufferTest {

    @Test
    void retainsAcceptedEventsInSequenceOrderAndCountsOverflow() {
        LifecycleEventBuffer events = LifecycleEventBuffer.create(2);
        Instant startedAt = Instant.parse("2026-08-07T12:00:00Z");

        assertThat(events.publish(
                LifecycleEvent.Stage.START,
                LifecycleEvent.Outcome.SUCCESS,
                1,
                3,
                startedAt,
                Duration.ofMillis(4),
                null)).isTrue();
        assertThat(events.publish(
                LifecycleEvent.Stage.REFRESH,
                LifecycleEvent.Outcome.FAILURE,
                2,
                4,
                startedAt,
                Duration.ofMillis(5),
                IllegalStateException.class.getName())).isTrue();
        assertThat(events.publish(
                LifecycleEvent.Stage.CLOSE,
                LifecycleEvent.Outcome.SUCCESS,
                1,
                3,
                startedAt,
                Duration.ZERO,
                null)).isFalse();

        List<LifecycleEvent> drained = events.drain(10);
        assertThat(drained).extracting(LifecycleEvent::sequence).containsExactly(1L, 2L);
        assertThat(drained).extracting(LifecycleEvent::stage)
                .containsExactly(LifecycleEvent.Stage.START, LifecycleEvent.Stage.REFRESH);
        assertThat(events.droppedEvents()).isEqualTo(1);
        assertThat(events.size()).isZero();
    }

    @Test
    void closeMakesPublicationNoopAndPreservesAcceptedEvents() {
        LifecycleEventBuffer events = LifecycleEventBuffer.create(1);
        publishSuccess(events, LifecycleEvent.Stage.START, 1);

        events.close();
        events.close();

        assertThat(events.isEnabled()).isFalse();
        assertThat(events.isClosed()).isTrue();
        assertThat(publishSuccess(events, LifecycleEvent.Stage.CLOSE, 1)).isFalse();
        assertThat(events.droppedEvents()).isZero();
        assertThat(events.poll()).extracting(LifecycleEvent::stage)
                .isEqualTo(LifecycleEvent.Stage.START);
    }

    @Test
    void noopHasNoQueueThreadOrDropAccounting() {
        LifecycleEventBuffer noop = LifecycleEventBuffer.noop();

        assertThat(noop.isEnabled()).isFalse();
        assertThat(noop.capacity()).isZero();
        assertThat(publishSuccess(noop, LifecycleEvent.Stage.START, 0)).isFalse();
        assertThat(noop.poll()).isNull();
        assertThat(noop.drain(1)).isEmpty();
        assertThat(noop.droppedEvents()).isZero();
    }

    @Test
    void concurrentCloseAndPublishRemainBoundedAndOrdered() throws Exception {
        LifecycleEventBuffer events = LifecycleEventBuffer.create(32);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> publishMany(events, start));
            var second = executor.submit(() -> publishMany(events, start));
            var close = executor.submit(() -> {
                start.await();
                events.close();
                return null;
            });
            start.countDown();
            first.get();
            second.get();
            close.get();
        }

        List<LifecycleEvent> accepted = events.drain(64);
        assertThat(accepted).hasSizeLessThanOrEqualTo(32);
        assertThat(accepted).extracting(LifecycleEvent::sequence).isSorted();
        assertThat(events.isClosed()).isTrue();
    }

    @Test
    void eventShapeRejectsInvalidFailureMetadata() {
        LifecycleEventBuffer events = LifecycleEventBuffer.create(1);

        assertThatThrownBy(() -> events.publish(
                LifecycleEvent.Stage.START,
                LifecycleEvent.Outcome.FAILURE,
                0,
                0,
                Instant.EPOCH,
                Duration.ZERO,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("failureType is required for a failed event");
    }

    private static boolean publishSuccess(
            LifecycleEventBuffer events,
            LifecycleEvent.Stage stage,
            long generation) {
        return events.publish(
                stage,
                LifecycleEvent.Outcome.SUCCESS,
                generation,
                1,
                Instant.EPOCH,
                Duration.ZERO,
                null);
    }

    private static Void publishMany(
            LifecycleEventBuffer events,
            CountDownLatch start) throws InterruptedException {
        start.await();
        for (int index = 0; index < 100; index++) {
            publishSuccess(events, LifecycleEvent.Stage.REFRESH, index);
        }
        return null;
    }
}
