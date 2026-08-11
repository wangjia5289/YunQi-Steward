package yunqi.zhibei.steward.telemetry.trace.opentelemetry;

import yunqi.zhibei.steward.telemetry.LifecycleEvent;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenTelemetryLifecycleAdapterTest {

    @Test
    void createsIndependentTimedSpansWithOnlyNeutralAttributes() {
        RecordingExporter exporter = new RecordingExporter();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Tracer tracer = provider.get("middleware-test");
            LifecycleEventBuffer events = LifecycleEventBuffer.create(8);
            Instant firstStart = Instant.ofEpochSecond(1_700_000_000L, 123);
            Instant secondStart = Instant.ofEpochSecond(1_700_000_001L, 456);

            assertThat(events.publish(
                    LifecycleEvent.Stage.START,
                    LifecycleEvent.Outcome.SUCCESS,
                    1,
                    7,
                    firstStart,
                    Duration.ofNanos(99),
                    null)).isTrue();
            assertThat(events.publish(
                    LifecycleEvent.Stage.REFRESH,
                    LifecycleEvent.Outcome.FAILURE,
                    2,
                    8,
                    secondStart,
                    Duration.ofNanos(199),
                    "java.io.IOException")).isTrue();

            OpenTelemetryLifecycleAdapter adapter = OpenTelemetryLifecycleAdapter.start(
                    events, "orders-cache", tracer, Duration.ofMillis(1), 4);
            adapter.close();

            List<SpanData> spans = exporter.spans();
            assertThat(spans).extracting(SpanData::getName)
                    .containsExactly("middleware.lifecycle.start", "middleware.lifecycle.refresh");
            assertThat(spans).allSatisfy(span -> {
                assertThat(span.getParentSpanContext().isValid()).isFalse();
                assertThat(span.getAttributes().asMap().keySet())
                        .extracting(AttributeKey::getKey)
                        .noneMatch(key -> key.matches(
                                ".*(configuration|client|throwable|message|endpoint|credential|secret).*"));
            });
            SpanData failed = spans.get(1);
            assertThat(failed.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(failed.getAttributes().get(AttributeKey.stringKey("middleware.owner")))
                    .isEqualTo("orders-cache");
            assertThat(failed.getAttributes().get(AttributeKey.longKey("middleware.sequence")))
                    .isEqualTo(2);
            assertThat(failed.getAttributes().get(AttributeKey.stringKey("middleware.failure.type")))
                    .isEqualTo("java.io.IOException");
            assertThat(failed.getStartEpochNanos()).isEqualTo(toEpochNanos(secondStart));
            assertThat(failed.getEndEpochNanos() - failed.getStartEpochNanos()).isEqualTo(199);
            assertThat(exporter.threadNames()).allMatch(name -> name.startsWith("middleware-otel-orders-cache"));
            assertThat(adapter.drainedEvents()).isEqualTo(2);
            assertThat(adapter.endedSpans()).isEqualTo(2);
            assertThat(adapter.spanFailures()).isZero();
            assertThat(adapter.isClosed()).isTrue();
        }
    }

    @Test
    void concurrentCloseSealsAndDrainsWhilePreservingSourceDropAccounting() throws Exception {
        RecordingExporter exporter = new RecordingExporter();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            LifecycleEventBuffer events = LifecycleEventBuffer.create(1);
            assertThat(publishSuccess(events, 1)).isTrue();
            assertThat(publishSuccess(events, 2)).isFalse();
            OpenTelemetryLifecycleAdapter adapter = OpenTelemetryLifecycleAdapter.start(
                    events, "billing-db", provider.get("middleware-test"), Duration.ofDays(1), 1);

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(adapter::close);
                var second = executor.submit(adapter::close);
                first.get();
                second.get();
            }

            assertThat(adapter.isClosed()).isTrue();
            assertThat(adapter.drainedEvents()).isEqualTo(1);
            assertThat(adapter.endedSpans()).isEqualTo(1);
            assertThat(adapter.spanFailures()).isZero();
            assertThat(adapter.sourceDroppedEvents()).isEqualTo(1);
            assertThat(events.size()).isZero();
            assertThat(events.isClosed()).isTrue();
        }
    }

    @Test
    void rejectsDisabledBuffersAndUnsafeOwnerIdentitiesWithoutEchoingThem() {
        RecordingExporter exporter = new RecordingExporter();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Tracer tracer = provider.get("middleware-test");
            assertThatThrownBy(() -> OpenTelemetryLifecycleAdapter.start(
                    LifecycleEventBuffer.noop(), "valid-owner", tracer))
                    .isInstanceOf(IllegalArgumentException.class);

            LifecycleEventBuffer events = LifecycleEventBuffer.create(2);
            assertThatThrownBy(() -> OpenTelemetryLifecycleAdapter.start(
                    events, "https://user:password@host", tracer))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("password")
                    .hasMessageNotContaining("https://");
            events.close();
        }
    }

    private static boolean publishSuccess(LifecycleEventBuffer events, long sequenceHint) {
        return events.publish(
                LifecycleEvent.Stage.CLOSE,
                LifecycleEvent.Outcome.SUCCESS,
                sequenceHint,
                sequenceHint,
                Instant.ofEpochSecond(1_700_000_000L),
                Duration.ZERO,
                null);
    }

    private static long toEpochNanos(Instant instant) {
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), TimeUnit.SECONDS.toNanos(1)),
                instant.getNano());
    }

    private static final class RecordingExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();
        private final List<String> threadNames = new ArrayList<>();

        @Override
        public synchronized CompletableResultCode export(Collection<SpanData> exported) {
            spans.addAll(exported);
            threadNames.add(Thread.currentThread().getName());
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        private synchronized List<SpanData> spans() {
            return List.copyOf(spans);
        }

        private synchronized List<String> threadNames() {
            return List.copyOf(threadNames);
        }
    }
}
