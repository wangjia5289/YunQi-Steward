package yunqi.zhibei.steward.telemetry.metric.micrometer;

import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import yunqi.zhibei.steward.control.resource.StartupBinding;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import yunqi.zhibei.steward.telemetry.LifecycleEvent;
import yunqi.zhibei.steward.telemetry.LifecycleEventDelivery;
import yunqi.zhibei.steward.telemetry.LifecycleEventFanOut;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;
import yunqi.zhibei.steward.control.resource.restart.RestartRequiredMonitor;
import yunqi.zhibei.steward.control.configuration.ConfigurationSnapshot;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import yunqi.zhibei.steward.control.configuration.ConfigurationSourceStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicrometerLifecycleMetricsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void pollsAllOwnerStatusesWithFixedCardinalityAndRemovesMetersOnClose() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LifecycleEventBuffer boundEvents = LifecycleEventBuffer.create(4);
        LifecycleEventBuffer managedEvents = LifecycleEventBuffer.create(1);
        LifecycleEventBuffer restartEvents = LifecycleEventBuffer.create(4);
        BoundResource<StringBuilder> bound = BoundResource.start(
                "bound-secret", startupBinding(), boundEvents);
        MutableConfigurationSource<Integer> managedSource = new MutableConfigurationSource<>(1);
        ManagedResource<Integer, Integer> managed = ManagedResource.builder(
                        managedSource, resourceBinding())
                .lifecycleEvents(managedEvents)
                .build();
        MutableConfigurationSource<String> restartSource =
                new MutableConfigurationSource<>("restart-secret");
        RestartRequiredMonitor<String> restart =
                RestartRequiredMonitor.watch(restartSource, 1, restartEvents);

        MicrometerLifecycleMetrics.Registration boundMetrics =
                MicrometerLifecycleMetrics.bind(registry, "bound-main", bound, boundEvents);
        MicrometerLifecycleMetrics.Registration managedMetrics =
                MicrometerLifecycleMetrics.bind(registry, "managed-main", managed, managedEvents);
        MicrometerLifecycleMetrics.Registration restartMetrics =
                MicrometerLifecycleMetrics.bind(registry, "restart-main", restart, restartEvents);

        assertThat(registry.getMeters()).hasSize(31);
        assertThat(state(registry, "bound-main", "bound", "open")).isEqualTo(1.0);
        assertThat(gauge(registry, "active.revision", "managed-main", "managed")).isEqualTo(1.0);
        assertThat(gauge(registry, "restart.required", "restart-main", "restart")).isZero();

        managedSource.update(2);
        assertThat(managed.awaitIdle(TIMEOUT)).isTrue();
        restartSource.update("new-restart-secret");
        bound.close();

        assertThat(gauge(registry, "active.revision", "managed-main", "managed")).isEqualTo(2.0);
        assertThat(counter(registry, "events.dropped", "managed-main", "managed"))
                .isGreaterThanOrEqualTo(1.0);
        assertThat(gauge(registry, "restart.required", "restart-main", "restart")).isEqualTo(1.0);
        assertThat(state(registry, "bound-main", "bound", "closed")).isEqualTo(1.0);

        managed.close();
        restart.close();
        assertThat(state(registry, "managed-main", "managed", "terminated")).isEqualTo(1.0);
        assertThat(state(registry, "restart-main", "restart", "closed")).isEqualTo(1.0);

        boundMetrics.close();
        boundMetrics.close();
        managedMetrics.close();
        restartMetrics.close();
        assertThat(boundMetrics.isClosed()).isTrue();
        assertThat(registry.getMeters()).isEmpty();

        boundEvents.close();
        managedEvents.close();
        restartEvents.close();
    }

    @Test
    void rejectsUnsafeMetricIdentityWithoutReflectingItsValue() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LifecycleEventBuffer events = LifecycleEventBuffer.create(2);
        BoundResource<StringBuilder> owner = BoundResource.start(
                "secret", startupBinding(), events);

        assertThatThrownBy(() -> MicrometerLifecycleMetrics.bind(
                registry, "redis://user:password@host", owner, events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("password")
                .hasMessageNotContaining("redis://");

        owner.close();
        events.close();
    }

    @Test
    void pollsFixedFanOutMetersAndKeepsBranchLossIndependent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LifecycleEventFanOut fanOut = LifecycleEventFanOut.start(
                8, Map.of("logs", 4, "otel", 4), Duration.ofDays(1), 4);
        MicrometerLifecycleMetrics.Registration metrics =
                MicrometerLifecycleMetrics.bind(registry, "orders-cache", fanOut);
        fanOut.branch("logs").close();

        publishAccepted(fanOut.source(), LifecycleEvent.Stage.START, 1);
        publishAccepted(fanOut.source(), LifecycleEvent.Stage.CLOSE, 1);
        fanOut.close();

        assertThat(registry.getMeters()).hasSize(9);
        assertThat(pipelineCounter(registry, "source.drained", "orders-cache", "fanout"))
                .isEqualTo(2.0);
        assertThat(branchCounter(registry, "delivered", "orders-cache", "logs")).isZero();
        assertThat(branchCounter(registry, "dropped", "orders-cache", "logs")).isEqualTo(2.0);
        assertThat(branchCounter(registry, "delivered", "orders-cache", "otel")).isEqualTo(2.0);
        assertThat(branchCounter(registry, "dropped", "orders-cache", "otel")).isZero();
        assertThat(branchGauge(registry, "buffered", "orders-cache", "otel")).isEqualTo(2.0);

        metrics.close();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void pollsFixedAdapterMetersRejectsCollisionsAndClosesConcurrently() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableDelivery delivery = new MutableDelivery();
        delivery.drained.set(5);
        delivery.successful.set(3);
        delivery.failed.set(2);
        delivery.sourceDropped.set(7);
        MicrometerLifecycleMetrics.Registration metrics = MicrometerLifecycleMetrics.bind(
                registry, "orders-cache", "otel", delivery);

        assertThat(registry.getMeters()).hasSize(5);
        assertThat(adapterCounter(registry, "drained", "orders-cache", "otel")).isEqualTo(5.0);
        assertThat(adapterCounter(registry, "successes", "orders-cache", "otel")).isEqualTo(3.0);
        assertThat(adapterCounter(registry, "failures", "orders-cache", "otel")).isEqualTo(2.0);
        assertThat(adapterCounter(registry, "source.dropped", "orders-cache", "otel"))
                .isEqualTo(7.0);
        assertThat(adapterGauge(registry, "closed", "orders-cache", "otel")).isZero();

        assertThatThrownBy(() -> MicrometerLifecycleMetrics.bind(
                registry, "orders-cache", "otel", delivery))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("pipeline metric identity is already registered");
        assertThatThrownBy(() -> MicrometerLifecycleMetrics.bind(
                registry, "orders-cache", "unsafe adapter", delivery))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("unsafe adapter");

        delivery.closed = true;
        assertThat(adapterGauge(registry, "closed", "orders-cache", "otel")).isEqualTo(1.0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var closes = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> executor.submit(metrics::close))
                    .toList();
            for (var close : closes) {
                close.get();
            }
        }
        assertThat(metrics.isClosed()).isTrue();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void pollsFixedConfigurationSourceStatusAndRemovesMeters() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableConfigurationSource<Integer> source = new MutableConfigurationSource<>(1);
        MicrometerConfigurationSourceMetrics.Registration metrics =
                MicrometerConfigurationSourceMetrics.bind(registry, "orders-source", source);

        assertThat(registry.getMeters()).hasSize(11);
        assertThat(sourceState(registry, "available")).isEqualTo(1.0);
        assertThat(sourceGauge(registry, "revision")).isEqualTo(1.0);
        assertThat(sourceCounter(registry, "failures")).isZero();
        assertThat(sourceGauge(registry, "last.failure.stage", "none")).isEqualTo(1.0);

        source.update(2);
        assertThat(sourceGauge(registry, "revision")).isEqualTo(2.0);

        metrics.close();
        metrics.close();
        assertThat(metrics.isClosed()).isTrue();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void rejectsUnsafeSourceIdentityWithoutReflectingItsValue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConfigurationSource<Integer> source = new MutableConfigurationSource<>(1);

        assertThatThrownBy(() -> MicrometerConfigurationSourceMetrics.bind(
                registry, "nacos://user:password@host", source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("password")
                .hasMessageNotContaining("nacos://");
    }

    private static double state(
            SimpleMeterRegistry registry,
            String owner,
            String kind,
            String state) {
        return registry.get("middleware.lifecycle.state")
                .tags("owner", owner, "kind", kind, "state", state)
                .gauge()
                .value();
    }

    private static double gauge(
            SimpleMeterRegistry registry,
            String suffix,
            String owner,
            String kind) {
        return registry.get("middleware.lifecycle." + suffix)
                .tags("owner", owner, "kind", kind)
                .gauge()
                .value();
    }

    private static double counter(
            SimpleMeterRegistry registry,
            String suffix,
            String owner,
            String kind) {
        return registry.get("middleware.lifecycle." + suffix)
                .tags("owner", owner, "kind", kind)
                .functionCounter()
                .count();
    }

    private static double pipelineCounter(
            SimpleMeterRegistry registry,
            String suffix,
            String owner,
            String kind) {
        return registry.get("middleware.lifecycle.pipeline." + suffix)
                .tags("owner", owner, "kind", kind)
                .functionCounter()
                .count();
    }

    private static double branchCounter(
            SimpleMeterRegistry registry,
            String suffix,
            String owner,
            String branch) {
        return registry.get("middleware.lifecycle.pipeline.branch." + suffix)
                .tags("owner", owner, "kind", "fanout", "branch", branch)
                .functionCounter()
                .count();
    }

    private static double branchGauge(
            SimpleMeterRegistry registry,
            String suffix,
            String owner,
            String branch) {
        return registry.get("middleware.lifecycle.pipeline.branch." + suffix)
                .tags("owner", owner, "kind", "fanout", "branch", branch)
                .gauge()
                .value();
    }

    private static double adapterCounter(
            SimpleMeterRegistry registry,
            String suffix,
            String owner,
            String adapter) {
        return registry.get("middleware.lifecycle.pipeline.adapter." + suffix)
                .tags("owner", owner, "kind", "adapter", "adapter", adapter)
                .functionCounter()
                .count();
    }

    private static double adapterGauge(
            SimpleMeterRegistry registry,
            String suffix,
            String owner,
            String adapter) {
        return registry.get("middleware.lifecycle.pipeline.adapter." + suffix)
                .tags("owner", owner, "kind", "adapter", "adapter", adapter)
                .gauge()
                .value();
    }

    private static double sourceState(SimpleMeterRegistry registry, String state) {
        return registry.get("middleware.configuration.source.state")
                .tags("source", "orders-source", "state", state)
                .gauge()
                .value();
    }

    private static double sourceGauge(SimpleMeterRegistry registry, String name) {
        return registry.get("middleware.configuration.source." + name)
                .tags("source", "orders-source")
                .gauge()
                .value();
    }

    private static double sourceGauge(
            SimpleMeterRegistry registry,
            String name,
            String stage) {
        return registry.get("middleware.configuration.source." + name)
                .tags("source", "orders-source", "stage", stage)
                .gauge()
                .value();
    }

    private static double sourceCounter(SimpleMeterRegistry registry, String name) {
        return registry.get("middleware.configuration.source." + name)
                .tags("source", "orders-source")
                .functionCounter()
                .count();
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

    private static StartupBinding<String, StringBuilder> startupBinding() {
        return new StartupBinding<>() {
            @Override
            public StringBuilder create(String configuration) {
                return new StringBuilder(configuration);
            }

            @Override
            public Health check(StringBuilder resource) {
                return Health.healthy(ProbeScope.LOCAL);
            }

            @Override
            public void close(StringBuilder resource) {
                resource.setLength(0);
            }
        };
    }

    private static ResourceBinding<Integer, Integer> resourceBinding() {
        return new ResourceBinding<>() {
            @Override
            public Integer create(Integer configuration) {
                return configuration;
            }

            @Override
            public Health check(Integer resource) {
                return Health.healthy(ProbeScope.LOCAL);
            }

            @Override
            public void close(Integer resource) {
            }
        };
    }

    private static final class MutableDelivery implements LifecycleEventDelivery {
        private final AtomicLong drained = new AtomicLong();
        private final AtomicLong successful = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong sourceDropped = new AtomicLong();
        private volatile boolean closed;

        @Override public long drainedEvents() { return drained.get(); }
        @Override public long successfulEvents() { return successful.get(); }
        @Override public long failedEvents() { return failed.get(); }
        @Override public long sourceDroppedEvents() { return sourceDropped.get(); }
        @Override public boolean isClosed() { return closed; }
    }
}
