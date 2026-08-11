package yunqi.zhibei.steward.control.resource;

import yunqi.zhibei.steward.telemetry.LifecycleEvent;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundResourceObservationTest {

    @Test
    void publishesOrderedStartAndCloseFacts() throws Exception {
        LifecycleEventBuffer events = LifecycleEventBuffer.create(4);
        StartupBinding<String, StringBuilder> binding = binding(false);

        BoundResource<StringBuilder> resource = BoundResource.start("secret-value", binding, events);
        resource.close();
        resource.close();

        List<LifecycleEvent> observed = events.drain(4);
        assertThat(observed).extracting(LifecycleEvent::stage)
                .containsExactly(LifecycleEvent.Stage.START, LifecycleEvent.Stage.CLOSE);
        assertThat(observed).extracting(LifecycleEvent::outcome)
                .containsOnly(LifecycleEvent.Outcome.SUCCESS);
        assertThat(observed).extracting(LifecycleEvent::sequence).containsExactly(1L, 2L);
        assertThat(observed).allSatisfy(event -> {
            assertThat(event.generation()).isZero();
            assertThat(event.revision()).isZero();
            assertThat(event.duration()).isGreaterThanOrEqualTo(Duration.ZERO);
            assertThat(event.toString()).doesNotContain("secret-value");
        });
    }

    @Test
    void failureContainsOnlyItsTypeAndNeverItsMessageOrConfiguration() {
        LifecycleEventBuffer events = LifecycleEventBuffer.create(2);

        assertThatThrownBy(() -> BoundResource.start(
                "configuration-secret", binding(true), events))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exception-secret");

        LifecycleEvent failure = events.poll();
        assertThat(failure.stage()).isEqualTo(LifecycleEvent.Stage.START);
        assertThat(failure.outcome()).isEqualTo(LifecycleEvent.Outcome.FAILURE);
        assertThat(failure.failureType()).contains(IOException.class.getName());
        assertThat(failure.toString())
                .doesNotContain("configuration-secret", "exception-secret");
    }

    private static StartupBinding<String, StringBuilder> binding(boolean failHealth) {
        return new StartupBinding<>() {
            @Override
            public StringBuilder create(String configuration) {
                return new StringBuilder(configuration);
            }

            @Override
            public Health check(StringBuilder resource) throws Exception {
                if (failHealth) {
                    throw new IOException("exception-secret");
                }
                return Health.healthy(ProbeScope.LOCAL);
            }

            @Override
            public void close(StringBuilder resource) {
                resource.setLength(0);
            }
        };
    }
}
