package yunqi.zhibei.steward.control.resource.refresh;

import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import yunqi.zhibei.steward.telemetry.LifecycleEvent;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedResourceObservationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void publishesStartupRefreshRetirementAndSingleConcurrentShutdownClose() throws Exception {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first-secret");
        LifecycleEventBuffer events = LifecycleEventBuffer.create(16);
        ManagedResource<TestResource, String> managed = ManagedResource.builder(source, binding())
                .lifecycleEvents(events)
                .closeWaitTimeout(TIMEOUT)
                .build();

        ManagedResource.Lease<TestResource> oldLease = managed.acquire();
        source.update("second-secret");
        awaitRevision(managed, 2);
        oldLease.close();
        assertThat(managed.awaitIdle(TIMEOUT)).isTrue();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(managed::close);
            var second = executor.submit(managed::close);
            first.get();
            second.get();
        }
        assertThat(managed.awaitTermination(TIMEOUT)).isTrue();

        List<LifecycleEvent> observed = events.drain(16);
        assertThat(observed).extracting(LifecycleEvent::stage)
                .containsExactly(
                        LifecycleEvent.Stage.START,
                        LifecycleEvent.Stage.REFRESH,
                        LifecycleEvent.Stage.CLOSE,
                        LifecycleEvent.Stage.CLOSE);
        assertThat(observed).extracting(LifecycleEvent::revision)
                .containsExactly(1L, 2L, 1L, 2L);
        assertThat(observed).extracting(LifecycleEvent::sequence).isSorted();
        assertThat(observed).allSatisfy(event -> assertThat(event.toString())
                .doesNotContain("first-secret", "second-secret"));
    }

    @Test
    void unhealthyCandidateProducesFailedRefreshAndSuccessfulRollback() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("good-secret");
        LifecycleEventBuffer events = LifecycleEventBuffer.create(8);
        ResourceBinding<String, TestResource> binding = new ResourceBinding<>() {
            @Override
            public TestResource create(String configuration) {
                return new TestResource(configuration);
            }

            @Override
            public Health check(TestResource resource) {
                return resource.value.equals("bad-secret")
                        ? Health.unhealthy(ProbeScope.LOCAL)
                        : Health.healthy(ProbeScope.LOCAL);
            }

            @Override
            public void close(TestResource resource) {
                resource.closed = true;
            }
        };
        ManagedResource<TestResource, String> managed = ManagedResource.builder(source, binding)
                .lifecycleEvents(events)
                .closeWaitTimeout(TIMEOUT)
                .build();

        source.update("bad-secret");
        assertThat(managed.awaitIdle(TIMEOUT)).isTrue();

        List<LifecycleEvent> observed = events.drain(8);
        assertThat(observed).extracting(LifecycleEvent::stage)
                .containsExactly(
                        LifecycleEvent.Stage.START,
                        LifecycleEvent.Stage.ROLLBACK,
                        LifecycleEvent.Stage.REFRESH);
        assertThat(observed.get(1).outcome()).isEqualTo(LifecycleEvent.Outcome.SUCCESS);
        assertThat(observed.get(2).outcome()).isEqualTo(LifecycleEvent.Outcome.FAILURE);
        assertThat(observed.get(2).failureType()).contains("unhealthy");
        assertThat(observed).allSatisfy(event -> assertThat(event.toString())
                .doesNotContain("good-secret", "bad-secret"));

        managed.close();
    }

    private static ResourceBinding<String, TestResource> binding() {
        return new ResourceBinding<>() {
            @Override
            public TestResource create(String configuration) {
                return new TestResource(configuration);
            }

            @Override
            public Health check(TestResource resource) {
                return Health.healthy(ProbeScope.LOCAL);
            }

            @Override
            public void close(TestResource resource) {
                resource.closed = true;
            }
        };
    }

    private static void awaitRevision(
            ManagedResource<TestResource, String> managed,
            long revision) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (managed.status().activeRevision() != revision && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(managed.status().activeRevision()).isEqualTo(revision);
    }

    private static final class TestResource {
        private final String value;
        private boolean closed;

        private TestResource(String value) {
            this.value = value;
        }
    }
}
