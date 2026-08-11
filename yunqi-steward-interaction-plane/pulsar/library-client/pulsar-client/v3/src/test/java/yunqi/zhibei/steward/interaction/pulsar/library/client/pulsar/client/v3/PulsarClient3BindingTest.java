package yunqi.zhibei.steward.interaction.pulsar.library.client.pulsar.client.v3;

import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;
import yunqi.zhibei.steward.support.testing.BindingContract;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PulsarClient3BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new PulsarClient3Binding(),
                configuration(6650),
                configuration(6651));
    }

    @Test
    void mapsConfigurationToANativeClientBuilder() throws Exception {
        ClientBuilder builder = PulsarClient3Binding.clientBuilder(configuration(6650));

        assertThat(builder).isNotNull();
    }

    @Test
    void buildsAndRefreshesNativeClientsWithoutContactingPulsar() throws Exception {
        PulsarClient3Configuration initial = configuration(6650);
        PulsarClient3Configuration updated = configuration(6651);
        MutableConfigurationSource<PulsarClient3Configuration> source =
                new MutableConfigurationSource<>(initial);

        PulsarClient first;
        try (ManagedResource<PulsarClient, PulsarClient3Configuration> managed =
                     ManagedResource.<PulsarClient, PulsarClient3Configuration>builder(
                                     source, new PulsarClient3Binding())
                             .healthCheck(ignored -> Health.healthy(ProbeScope.LOCAL))
                             .build()) {
            first = managed.execute(client -> client);
            source.update(updated);
            assertThat(managed.awaitIdle(Duration.ofSeconds(5))).isTrue();
            PulsarClient second = managed.execute(client -> client);

            assertThat(second).isNotSameAs(first);
            assertThat(managed.status().activeRevision()).isEqualTo(2);
            assertThat(first.isClosed()).isTrue();
        }
    }

    @Test
    void verifiesTheSelectedSdkMajor() {
        PulsarClient3Binding.verifyDependencyVersion();
    }

    private static PulsarClient3Configuration configuration(int port) {
        return new PulsarClient3Configuration(
                "pulsar://127.0.0.1:" + port,
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(3),
                1,
                1);
    }
}
