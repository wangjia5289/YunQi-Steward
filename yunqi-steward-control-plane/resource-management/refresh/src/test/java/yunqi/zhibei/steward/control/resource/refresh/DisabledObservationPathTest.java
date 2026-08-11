package yunqi.zhibei.steward.control.resource.refresh;

import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class DisabledObservationPathTest {

    @Test
    void omittedAndExplicitNoopUseTheSameSingleton() throws Exception {
        MutableConfigurationSource<Integer> omittedSource = new MutableConfigurationSource<>(1);
        MutableConfigurationSource<Integer> explicitSource = new MutableConfigurationSource<>(1);
        ResourceBinding<Integer, Integer> binding = new ResourceBinding<>() {
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

        try (ManagedResource<Integer, Integer> omitted =
                        ManagedResource.bind(omittedSource, binding);
                ManagedResource<Integer, Integer> explicit = ManagedResource
                        .builder(explicitSource, binding)
                        .lifecycleEvents(LifecycleEventBuffer.noop())
                        .build()) {
            Field events = ManagedResource.class.getDeclaredField("lifecycleEvents");
            events.setAccessible(true);

            assertThat(events.get(omitted))
                    .isSameAs(LifecycleEventBuffer.noop())
                    .isSameAs(events.get(explicit));
            int omittedResult = omitted.execute(value -> value);
            int explicitResult = explicit.execute(value -> value);
            assertThat(omittedResult).isEqualTo(1);
            assertThat(explicitResult).isEqualTo(1);
        }
    }
}
