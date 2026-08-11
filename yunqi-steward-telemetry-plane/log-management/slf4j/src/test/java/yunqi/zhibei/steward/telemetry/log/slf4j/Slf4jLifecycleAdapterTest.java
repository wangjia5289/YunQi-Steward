package yunqi.zhibei.steward.telemetry.log.slf4j;

import yunqi.zhibei.steward.telemetry.LifecycleEvent;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Slf4jLifecycleAdapterTest {

    @Test
    void writesFixedStructuredArgumentsOnTheAdapterThreadWithoutThrowables() {
        RecordingLogger recording = new RecordingLogger(false);
        LifecycleEventBuffer events = LifecycleEventBuffer.create(8);
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

        Slf4jLifecycleAdapter adapter = Slf4jLifecycleAdapter.start(
                events, "orders-cache", recording.logger(), Duration.ofMillis(1), 4);
        adapter.close();

        List<LogCall> calls = recording.calls();
        assertThat(calls).extracting(LogCall::level).containsExactly("info", "warn");
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.template()).startsWith("middleware.lifecycle owner={}");
            assertThat(call.template()).doesNotContain("message", "throwable", "secret");
            assertThat(call.threadName()).startsWith("middleware-slf4j-orders-cache");
            assertThat(call.arguments()).noneMatch(Throwable.class::isInstance);
        });
        assertThat(calls.get(1).arguments())
                .containsExactly(
                        "orders-cache", 2L, "REFRESH", "FAILURE", 2L, 8L,
                        234_567L, 199L, "java.io.IOException");
        assertThat(adapter.drainedEvents()).isEqualTo(2);
        assertThat(adapter.loggedEvents()).isEqualTo(2);
        assertThat(adapter.logFailures()).isZero();
        assertThat(adapter.isClosed()).isTrue();
    }

    @Test
    void loggerFailuresAreAccountedAndConcurrentCloseStillDrains() throws Exception {
        RecordingLogger recording = new RecordingLogger(true);
        LifecycleEventBuffer events = LifecycleEventBuffer.create(1);
        assertThat(publishSuccess(events, 1)).isTrue();
        assertThat(publishSuccess(events, 2)).isFalse();
        Slf4jLifecycleAdapter adapter = Slf4jLifecycleAdapter.start(
                events, "billing-db", recording.logger(), Duration.ofDays(1), 1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(adapter::close);
            var second = executor.submit(adapter::close);
            first.get();
            second.get();
        }

        assertThat(adapter.isClosed()).isTrue();
        assertThat(adapter.drainedEvents()).isEqualTo(1);
        assertThat(adapter.loggedEvents()).isZero();
        assertThat(adapter.logFailures()).isEqualTo(1);
        assertThat(adapter.sourceDroppedEvents()).isEqualTo(1);
        assertThat(events.size()).isZero();
        assertThat(events.isClosed()).isTrue();
    }

    @Test
    void rejectsDisabledBuffersAndUnsafeOwnerIdentitiesWithoutEchoingThem() {
        RecordingLogger recording = new RecordingLogger(false);
        assertThatThrownBy(() -> Slf4jLifecycleAdapter.start(
                LifecycleEventBuffer.noop(), "valid-owner", recording.logger()))
                .isInstanceOf(IllegalArgumentException.class);

        LifecycleEventBuffer events = LifecycleEventBuffer.create(2);
        assertThatThrownBy(() -> Slf4jLifecycleAdapter.start(
                events, "https://user:password@host", recording.logger()))
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

    private record LogCall(
            String level,
            String threadName,
            String template,
            List<Object> arguments) {
    }

    private static final class RecordingLogger {
        private final List<LogCall> calls = new ArrayList<>();
        private final boolean fail;
        private final Logger logger;

        private RecordingLogger(boolean fail) {
            this.fail = fail;
            logger = (Logger) Proxy.newProxyInstance(
                    Logger.class.getClassLoader(),
                    new Class<?>[] {Logger.class},
                    (proxy, method, arguments) -> invoke(method.getName(), method.getReturnType(), arguments));
        }

        private synchronized Object invoke(
                String method,
                Class<?> returnType,
                Object[] invocationArguments) {
            if (method.equals("getName")) {
                return "recording";
            }
            if (returnType == boolean.class) {
                return true;
            }
            if (method.equals("info") || method.equals("warn")) {
                if (fail) {
                    throw new IllegalStateException("logger failure secret");
                }
                Object[] values = ((Object[]) invocationArguments[1]).clone();
                calls.add(new LogCall(
                        method,
                        Thread.currentThread().getName(),
                        (String) invocationArguments[0],
                        List.of(values)));
            }
            return null;
        }

        private Logger logger() {
            return logger;
        }

        private synchronized List<LogCall> calls() {
            return List.copyOf(calls);
        }
    }
}
