package yunqi.zhibei.steward.example.observation;

import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.StartupBinding;
import yunqi.zhibei.steward.telemetry.LifecycleEventFanOut;
import yunqi.zhibei.steward.telemetry.profile.jfr.JfrLifecycleAdapter;
import yunqi.zhibei.steward.telemetry.trace.opentelemetry.OpenTelemetryLifecycleAdapter;
import yunqi.zhibei.steward.telemetry.log.slf4j.Slf4jLifecycleAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationExampleSmokeTest {

    @Test
    void oneSourceProducesMetricsLogsSpansAndJfrMarkers() throws Exception {
        Path destination = Files.createTempFile("observation-example-", ".jfr");
        RecordingExporter exporter = new RecordingExporter();
        RecordingLogger logger = new RecordingLogger();
        ObservationExample.Result result;

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
                Recording recording = new Recording()) {
            recording.enable("yunqi.zhibei.steward.Lifecycle").withThreshold(Duration.ZERO);
            recording.start();
            result = ObservationExample.run(
                    registry, provider.get("middleware-example-test"), logger.logger());
            recording.stop();
            recording.dump(destination);
        } finally {
            registry.close();
        }

        List<Long> jfrSequences = RecordingFile.readAllEvents(destination).stream()
                .filter(event -> event.getEventType().getName().equals("yunqi.zhibei.steward.Lifecycle"))
                .map(event -> event.getLong("sequence"))
                .toList();
        Files.delete(destination);
        List<Long> spanSequences = exporter.spans().stream()
                .map(span -> span.getAttributes().get(
                        AttributeKey.longKey("middleware.sequence")))
                .toList();

        assertThat(result.sourceEvents()).isGreaterThanOrEqualTo(5);
        assertThat(result.refreshSuccesses()).isEqualTo(2);
        assertThat(result.refreshFailures()).isEqualTo(1);
        assertThat(result.metricDrainedEvents()).isEqualTo(result.sourceEvents());
        assertThat(result.jfrEvents()).isEqualTo(result.sourceEvents());
        assertThat(result.spans()).isEqualTo(result.sourceEvents());
        assertThat(result.logRecords()).isEqualTo(result.sourceEvents());
        assertThat(jfrSequences).containsExactlyElementsOf(spanSequences);
        assertThat(logger.sequences()).containsExactlyElementsOf(spanSequences);
        assertThat(exporter.spans()).extracting(SpanData::getName)
                .contains("middleware.lifecycle.start", "middleware.lifecycle.refresh",
                        "middleware.lifecycle.rollback", "middleware.lifecycle.close");
    }

    @Test
    void concurrentOwnerFanOutAndAdapterCloseIsBoundedAndIdempotent() throws Exception {
        String ownerName = "concurrent-example";
        LifecycleEventFanOut fanOut = LifecycleEventFanOut.start(
                32, Map.of("jfr", 32, "otel", 32, "logs", 32));
        JfrLifecycleAdapter jfr = JfrLifecycleAdapter.start(fanOut.branch("jfr"), ownerName);
        OpenTelemetryLifecycleAdapter otel = OpenTelemetryLifecycleAdapter.start(
                fanOut.branch("otel"), ownerName,
                io.opentelemetry.api.OpenTelemetry.noop().getTracer("close-test"));
        Slf4jLifecycleAdapter logs = Slf4jLifecycleAdapter.start(
                fanOut.branch("logs"), ownerName, new RecordingLogger().logger());
        BoundResource<Object> owner = BoundResource.start("safe", new ObjectBinding(), fanOut.source());
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> closes = List.of(
                    executor.submit(() -> closeAfter(start, owner)),
                    executor.submit(() -> closeAfter(start, fanOut)),
                    executor.submit(() -> closeAfter(start, logs)),
                    executor.submit(() -> closeAfter(start, otel)),
                    executor.submit(() -> closeAfter(start, jfr)));
            start.countDown();
            for (var close : closes) {
                close.get(5, TimeUnit.SECONDS);
            }
        }

        owner.close();
        fanOut.close();
        logs.close();
        otel.close();
        jfr.close();
        assertThat(owner.isClosed()).isTrue();
        assertThat(fanOut.isClosed()).isTrue();
        assertThat(logs.isClosed()).isTrue();
        assertThat(otel.isClosed()).isTrue();
        assertThat(jfr.isClosed()).isTrue();
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(thread ->
                thread.isAlive() && thread.getName().contains(ownerName));
    }

    private static void closeAfter(CountDownLatch start, AutoCloseable closeable) {
        try {
            start.await();
            closeable.close();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("close interrupted", failure);
        } catch (Exception failure) {
            throw new IllegalStateException("close failed", failure);
        }
    }

    private static final class ObjectBinding implements StartupBinding<String, Object> {
        @Override
        public Object create(String configuration) {
            return new Object();
        }

        @Override
        public Health check(Object resource) {
            return Health.healthy(ProbeScope.LOCAL);
        }

        @Override
        public void close(Object resource) {
            // Nothing external is owned by this fixture.
        }
    }

    private static final class RecordingExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();

        @Override
        public synchronized CompletableResultCode export(Collection<SpanData> exported) {
            spans.addAll(exported);
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
    }

    private static final class RecordingLogger {
        private final List<Long> sequences = new ArrayList<>();
        private final Logger logger = (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class},
                (proxy, method, arguments) -> invoke(method.getName(), method.getReturnType(), arguments));

        private synchronized Object invoke(
                String method, Class<?> returnType, Object[] arguments) {
            if (method.equals("getName")) {
                return "recording";
            }
            if (returnType == boolean.class) {
                return true;
            }
            if (method.equals("info") || method.equals("warn")) {
                Object[] values = (Object[]) arguments[1];
                sequences.add((Long) values[1]);
            }
            return null;
        }

        private Logger logger() {
            return logger;
        }

        private synchronized List<Long> sequences() {
            return List.copyOf(sequences);
        }
    }
}
