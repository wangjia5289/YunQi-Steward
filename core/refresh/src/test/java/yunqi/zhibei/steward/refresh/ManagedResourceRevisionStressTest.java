package yunqi.zhibei.steward.refresh;

import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import yunqi.zhibei.steward.observation.LifecycleEvent;
import yunqi.zhibei.steward.observation.LifecycleEventFanOut;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedResourceRevisionStressTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void rapidRevisionsConvergeWhileObservedEventsRemainOrdered() throws Exception {
        MutableConfigurationSource<Integer> source = new MutableConfigurationSource<>(0);
        LifecycleEventFanOut fanOut = LifecycleEventFanOut.start(
                4_096, Map.of("audit", 4_096), Duration.ofMillis(1), 64);
        ManagedResource<TestResource, Integer> owner = ManagedResource
                .builder(source, new TestBinding())
                .lifecycleEvents(fanOut.source())
                .build();
        try {
            for (int revision = 1; revision <= 1_000; revision++) {
                source.update(revision);
            }
            assertThat(owner.awaitIdle(Duration.ofSeconds(10))).isTrue();
            assertThat(owner.status().activeRevision()).isEqualTo(1_001);
            int activeConfiguration = owner.execute(resource -> resource.configuration);
            assertThat(activeConfiguration).isEqualTo(1_000);
        } finally {
            owner.close();
            fanOut.close();
        }

        List<LifecycleEvent> retained = fanOut.branch("audit").drain(4_096);
        assertThat(retained).isNotEmpty();
        assertThat(retained).extracting(LifecycleEvent::sequence).isSorted();
        assertThat(retained.getLast().revision()).isIn(0L, 1_001L);
        assertThat(fanOut.drainedEvents()).isEqualTo(retained.size());
    }

    private static final class TestResource {
        private final int configuration;

        private TestResource(int configuration) {
            this.configuration = configuration;
        }
    }

    private static final class TestBinding implements ResourceBinding<Integer, TestResource> {
        @Override
        public TestResource create(Integer configuration) {
            return new TestResource(configuration);
        }

        @Override
        public Health check(TestResource resource) {
            return Health.healthy(ProbeScope.LOCAL);
        }

        @Override
        public void close(TestResource resource) {
            // Nothing external is owned by this deterministic fixture.
        }
    }
}
