package yunqi.zhibei.steward.adapter.observability.jfr;

import yunqi.zhibei.steward.observation.LifecycleEvent;
import yunqi.zhibei.steward.observation.LifecycleEventBuffer;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JfrLifecycleAdapterTest {

    @Test
    void recordsTheNeutralEventFieldsOnTheAdapterThread() throws Exception {
        Path destination = Files.createTempFile("middleware-lifecycle-", ".jfr");
        LifecycleEventBuffer events = LifecycleEventBuffer.create(8);
        JfrLifecycleAdapter adapter;

        try (Recording recording = new Recording()) {
            recording.enable("yunqi.zhibei.steward.Lifecycle").withThreshold(Duration.ZERO);
            recording.start();
            assertThat(events.publish(
                    LifecycleEvent.Stage.START,
                    LifecycleEvent.Outcome.SUCCESS,
                    1,
                    7,
                    Instant.ofEpochMilli(123_456),
                    Duration.ofNanos(99),
                    null)).isTrue();
            assertThat(events.publish(
                    LifecycleEvent.Stage.REFRESH,
                    LifecycleEvent.Outcome.FAILURE,
                    2,
                    8,
                    Instant.ofEpochMilli(234_567),
                    Duration.ofNanos(199),
                    "java.io.IOException")).isTrue();

            adapter = JfrLifecycleAdapter.start(
                    events, "orders-cache", Duration.ofMillis(1), 4);
            adapter.close();
            adapter.close();
            recording.stop();
            recording.dump(destination);
        }

        List<RecordedEvent> recorded = RecordingFile.readAllEvents(destination).stream()
                .filter(event -> event.getEventType().getName().equals("yunqi.zhibei.steward.Lifecycle"))
                .toList();
        assertThat(recorded).hasSize(2);
        assertThat(recorded).extracting(event -> event.getLong("sequence"))
                .containsExactly(1L, 2L);
        assertThat(recorded).extracting(event -> event.getString("stage"))
                .containsExactly("START", "REFRESH");
        assertThat(recorded).extracting(event -> event.getString("outcome"))
                .containsExactly("SUCCESS", "FAILURE");
        assertThat(recorded).extracting(event -> event.getString("owner"))
                .containsOnly("orders-cache");
        assertThat(recorded.get(1).getLong("generation")).isEqualTo(2);
        assertThat(recorded.get(1).getLong("revision")).isEqualTo(8);
        assertThat(recorded.get(1).getLong("lifecycleStartedAt")).isEqualTo(234_567);
        assertThat(recorded.get(1).getLong("lifecycleDuration")).isEqualTo(199);
        assertThat(recorded.get(1).getString("failureType")).isEqualTo("java.io.IOException");
        assertThat(recorded).allSatisfy(event -> assertThat(event.getThread().getJavaName())
                .startsWith("middleware-jfr-orders-cache"));
        assertThat(adapter.drainedEvents()).isEqualTo(2);
        assertThat(adapter.forwardedEvents()).isEqualTo(2);
        assertThat(adapter.commitFailures()).isZero();
        assertThat(adapter.isClosed()).isTrue();

        Files.delete(destination);
    }

    @Test
    void concurrentCloseSealsAndDrainsWhilePreservingSourceDropAccounting() throws Exception {
        LifecycleEventBuffer events = LifecycleEventBuffer.create(1);
        assertThat(publishSuccess(events, 1)).isTrue();
        assertThat(publishSuccess(events, 2)).isFalse();
        JfrLifecycleAdapter adapter = JfrLifecycleAdapter.start(
                events, "billing-db", Duration.ofDays(1), 1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(adapter::close);
            var second = executor.submit(adapter::close);
            first.get();
            second.get();
        }

        assertThat(adapter.isClosed()).isTrue();
        assertThat(adapter.drainedEvents()).isEqualTo(1);
        assertThat(adapter.forwardedEvents()).isEqualTo(1);
        assertThat(adapter.commitFailures()).isZero();
        assertThat(adapter.sourceDroppedEvents()).isEqualTo(1);
        assertThat(events.size()).isZero();
        assertThat(events.isClosed()).isTrue();
        assertThat(publishSuccess(events, 3)).isFalse();
    }

    @Test
    void rejectsDisabledBuffersAndUnsafeOwnerIdentitiesWithoutEchoingThem() {
        assertThatThrownBy(() -> JfrLifecycleAdapter.start(
                LifecycleEventBuffer.noop(), "valid-owner"))
                .isInstanceOf(IllegalArgumentException.class);

        LifecycleEventBuffer events = LifecycleEventBuffer.create(2);
        assertThatThrownBy(() -> JfrLifecycleAdapter.start(
                events, "https://user:password@host"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("password")
                .hasMessageNotContaining("https://");
        events.close();
    }

    private static boolean publishSuccess(LifecycleEventBuffer events, long sequenceHint) {
        return events.publish(
                LifecycleEvent.Stage.CLOSE,
                LifecycleEvent.Outcome.SUCCESS,
                sequenceHint,
                sequenceHint,
                Instant.EPOCH,
                Duration.ZERO,
                null);
    }
}
