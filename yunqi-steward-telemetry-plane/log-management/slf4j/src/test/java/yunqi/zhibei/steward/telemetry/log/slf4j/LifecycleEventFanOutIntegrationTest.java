package yunqi.zhibei.steward.telemetry.log.slf4j;

import yunqi.zhibei.steward.telemetry.LifecycleEvent;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import yunqi.zhibei.steward.telemetry.LifecycleEventFanOut;
import yunqi.zhibei.steward.telemetry.profile.jfr.JfrLifecycleAdapter;
import yunqi.zhibei.steward.telemetry.trace.opentelemetry.OpenTelemetryLifecycleAdapter;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleEventFanOutIntegrationTest {

    private static final Set<String> ADAPTER_THREADS = Set.of(
            "middleware-jfr-orders-cache",
            "middleware-otel-orders-cache",
            "middleware-slf4j-orders-cache");

    @Test
    void oneSourceFeedsAllThreeEventAdapters() {
        LifecycleEventFanOut fanOut = LifecycleEventFanOut.start(
                16,
                Map.of("jfr", 8, "otel", 8, "logs", 8),
                Duration.ofMillis(1),
                8);
        JfrLifecycleAdapter jfr = JfrLifecycleAdapter.start(
                fanOut.branch("jfr"), "orders-cache", Duration.ofDays(1), 8);
        OpenTelemetryLifecycleAdapter telemetry = OpenTelemetryLifecycleAdapter.start(
                fanOut.branch("otel"),
                "orders-cache",
                OpenTelemetry.noop().getTracer("fanout-test"),
                Duration.ofDays(1),
                8);
        Slf4jLifecycleAdapter logging = Slf4jLifecycleAdapter.start(
                fanOut.branch("logs"),
                "orders-cache",
                noOpLogger(),
                Duration.ofDays(1),
                8);

        try {
            await(() -> adapterThreadsParked() == ADAPTER_THREADS.size());
            publishAccepted(fanOut.source(), LifecycleEvent.Stage.START, 1);
            publishAccepted(fanOut.source(), LifecycleEvent.Stage.REFRESH, 2);
        } finally {
            fanOut.close();
            logging.close();
            telemetry.close();
            jfr.close();
        }

        assertThat(fanOut.drainedEvents()).isEqualTo(2);
        assertThat(fanOut.deliveredEvents("jfr")).isEqualTo(2);
        assertThat(fanOut.deliveredEvents("otel")).isEqualTo(2);
        assertThat(fanOut.deliveredEvents("logs")).isEqualTo(2);
        assertThat(fanOut.droppedEvents("jfr")).isZero();
        assertThat(fanOut.droppedEvents("otel")).isZero();
        assertThat(fanOut.droppedEvents("logs")).isZero();
        assertThat(jfr.forwardedEvents()).isEqualTo(2);
        assertThat(telemetry.endedSpans()).isEqualTo(2);
        assertThat(logging.loggedEvents()).isEqualTo(2);
    }

    private static long adapterThreadsParked() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> ADAPTER_THREADS.contains(thread.getName()))
                .filter(thread -> thread.getState() == Thread.State.TIMED_WAITING)
                .count();
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

    private static void publishAccepted(
            LifecycleEventBuffer events,
            LifecycleEvent.Stage stage,
            long generation) {
        await(() -> events.publish(
                stage,
                LifecycleEvent.Outcome.SUCCESS,
                generation,
                generation,
                Instant.EPOCH,
                Duration.ZERO,
                null));
    }

    private static Logger noOpLogger() {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getName")) {
                        return "fanout-test";
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    return null;
                });
    }
}
