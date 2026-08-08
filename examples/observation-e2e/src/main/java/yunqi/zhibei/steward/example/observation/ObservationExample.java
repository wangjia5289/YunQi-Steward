package yunqi.zhibei.steward.example.observation;

import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import yunqi.zhibei.steward.observation.LifecycleEventFanOut;
import yunqi.zhibei.steward.adapter.observability.jfr.JfrLifecycleAdapter;
import yunqi.zhibei.steward.adapter.observability.micrometer.MicrometerLifecycleMetrics;
import yunqi.zhibei.steward.adapter.observability.opentelemetry.OpenTelemetryLifecycleAdapter;
import yunqi.zhibei.steward.adapter.observability.slf4j.Slf4jLifecycleAdapter;
import yunqi.zhibei.steward.refresh.ManagedResource;
import yunqi.zhibei.steward.refresh.MutableConfigurationSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runnable, dependency-free lifecycle scenario used by the documentation and smoke test. */
public final class ObservationExample {

    private static final String OWNER = "example-cache";
    private static final Duration WAIT = Duration.ofSeconds(5);

    private ObservationExample() {
    }

    /** Runs startup, successful refresh, unhealthy-candidate rollback, and shutdown. */
    public static Result run(MeterRegistry registry, Tracer tracer, Logger logger) {
        LifecycleEventFanOut fanOut = LifecycleEventFanOut.start(
                128, Map.of("jfr", 128, "otel", 128, "logs", 128));
        JfrLifecycleAdapter jfr = JfrLifecycleAdapter.start(fanOut.branch("jfr"), OWNER);
        OpenTelemetryLifecycleAdapter otel = OpenTelemetryLifecycleAdapter.start(
                fanOut.branch("otel"), OWNER, tracer);
        Slf4jLifecycleAdapter logs = Slf4jLifecycleAdapter.start(
                fanOut.branch("logs"), OWNER, logger);
        MutableConfigurationSource<ExampleConfiguration> source =
                new MutableConfigurationSource<>(new ExampleConfiguration("v1", true));
        ManagedResource<ExampleClient, ExampleConfiguration> owner = null;
        MicrometerLifecycleMetrics.Registration ownerMetrics = null;
        MicrometerLifecycleMetrics.Registration fanOutMetrics = null;
        MicrometerLifecycleMetrics.Registration jfrMetrics = null;
        MicrometerLifecycleMetrics.Registration otelMetrics = null;
        MicrometerLifecycleMetrics.Registration logMetrics = null;
        try {
            owner = ManagedResource.builder(source, new ExampleBinding())
                    .lifecycleEvents(fanOut.source())
                    .build();
            ownerMetrics = MicrometerLifecycleMetrics.bind(
                    registry, OWNER, owner, fanOut.source());
            fanOutMetrics = MicrometerLifecycleMetrics.bind(registry, OWNER, fanOut);
            jfrMetrics = MicrometerLifecycleMetrics.bind(registry, OWNER, "jfr", jfr);
            otelMetrics = MicrometerLifecycleMetrics.bind(registry, OWNER, "otel", otel);
            logMetrics = MicrometerLifecycleMetrics.bind(registry, OWNER, "logs", logs);

            source.update(new ExampleConfiguration("v2", true));
            requireIdle(owner);
            source.update(new ExampleConfiguration("v3", false));
            requireIdle(owner);

            long refreshSuccesses = owner.status().refreshSuccesses();
            long refreshFailures = owner.status().refreshFailures();
            owner.close();
            owner = null;
            fanOut.close();
            logs.close();
            otel.close();
            jfr.close();

            double metricDrained = registry.find("middleware.lifecycle.pipeline.source.drained")
                    .tags("owner", OWNER, "kind", "fanout")
                    .functionCounter()
                    .count();
            return new Result(
                    fanOut.drainedEvents(), jfr.forwardedEvents(), otel.endedSpans(),
                    logs.loggedEvents(), refreshSuccesses, refreshFailures, metricDrained);
        } finally {
            close(logMetrics);
            close(otelMetrics);
            close(jfrMetrics);
            close(fanOutMetrics);
            close(ownerMetrics);
            if (owner != null) {
                owner.close();
            }
            fanOut.close();
            logs.close();
            otel.close();
            jfr.close();
        }
    }

    /** Runs the same scenario with application-selected API defaults. */
    public static void main(String[] arguments) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            Result result = run(
                    registry,
                    OpenTelemetry.noop().getTracer("middleware-example"),
                    LoggerFactory.getLogger("middleware.lifecycle"));
            System.out.println(result);
        } finally {
            registry.close();
        }
    }

    private static void requireIdle(ManagedResource<?, ?> owner) {
        if (!owner.awaitIdle(WAIT)) {
            throw new IllegalStateException("managed resource did not become idle");
        }
    }

    private static void close(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("unexpected close failure", failure);
        }
    }

    /** Safe example configuration containing no address or credential. */
    public record ExampleConfiguration(String version, boolean healthy) {
    }

    private static final class ExampleClient {
        private final ExampleConfiguration configuration;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ExampleClient(ExampleConfiguration configuration) {
            this.configuration = configuration;
        }
    }

    private static final class ExampleBinding
            implements ResourceBinding<ExampleConfiguration, ExampleClient> {

        @Override
        public ExampleClient create(ExampleConfiguration configuration) {
            return new ExampleClient(configuration);
        }

        @Override
        public Health check(ExampleClient resource) {
            return resource.configuration.healthy()
                    ? Health.healthy(ProbeScope.LOCAL)
                    : Health.unhealthy(ProbeScope.LOCAL);
        }

        @Override
        public void close(ExampleClient resource) {
            resource.closed.set(true);
        }
    }

    /** Final pipeline accounting captured before Micrometer registrations are removed. */
    public record Result(
            long sourceEvents,
            long jfrEvents,
            long spans,
            long logRecords,
            long refreshSuccesses,
            long refreshFailures,
            double metricDrainedEvents) {
    }
}
