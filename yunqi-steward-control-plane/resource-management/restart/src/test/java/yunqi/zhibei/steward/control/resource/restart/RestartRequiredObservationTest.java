package yunqi.zhibei.steward.control.resource.restart;

import yunqi.zhibei.steward.telemetry.LifecycleEvent;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RestartRequiredObservationTest {

    @Test
    void emitsOneRequirementTransitionAndAnOrderedClose() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("initial-secret");
        LifecycleEventBuffer events = LifecycleEventBuffer.create(8);
        RestartRequiredMonitor<String> monitor = RestartRequiredMonitor.watch(source, 1, events);

        source.update("updated-secret");
        source.update("newest-secret");
        monitor.close();
        monitor.close();

        List<LifecycleEvent> observed = events.drain(8);
        assertThat(observed).extracting(LifecycleEvent::stage)
                .containsExactly(
                        LifecycleEvent.Stage.START,
                        LifecycleEvent.Stage.RESTART_REQUIRED,
                        LifecycleEvent.Stage.CLOSE);
        assertThat(observed).extracting(LifecycleEvent::revision)
                .containsExactly(1L, 2L, 3L);
        assertThat(observed).allSatisfy(event -> assertThat(event.toString())
                .doesNotContain("initial-secret", "updated-secret", "newest-secret"));
    }
}
