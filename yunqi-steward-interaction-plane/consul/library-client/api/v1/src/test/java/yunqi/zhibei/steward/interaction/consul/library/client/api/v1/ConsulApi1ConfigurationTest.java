package yunqi.zhibei.steward.interaction.consul.library.client.api.v1;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsulApi1ConfigurationTest {

    @Test
    void buildsFromDefaultsAndCopiesOnlyRequestedChanges() {
        ConsulApi1Configuration configured = ConsulApi1Configuration.builder()
                .host("consul.internal")
                .build();
        ConsulApi1Configuration updated = configured.toBuilder().port(8600).build();

        assertThat(configured.port()).isEqualTo(8500);
        assertThat(updated.host()).isEqualTo("consul.internal");
        assertThat(updated.port()).isEqualTo(8600);
    }

    @Test
    void providesOnlyEndpointDefaults() {
        ConsulApi1Configuration configuration = ConsulApi1Configuration.defaults();

        assertThat(configuration.host()).isEqualTo("127.0.0.1");
        assertThat(configuration.port()).isEqualTo(8500);
    }

    @Test
    void validatesTheEndpoint() {
        assertThatThrownBy(() -> new ConsulApi1Configuration(" ", 8500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
        assertThatThrownBy(() -> new ConsulApi1Configuration("localhost", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }
}
