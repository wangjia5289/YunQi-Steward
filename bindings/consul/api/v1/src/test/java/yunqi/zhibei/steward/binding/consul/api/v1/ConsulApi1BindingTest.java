package yunqi.zhibei.steward.binding.consul.api.v1;

import com.ecwid.consul.v1.ConsulClient;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.refresh.ManagedResource;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.refresh.MutableConfigurationSource;
import yunqi.zhibei.steward.support.testing.BindingContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ConsulApi1BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new ConsulApi1Binding(),
                new ConsulApi1Configuration("127.0.0.1", 8500),
                new ConsulApi1Configuration("127.0.0.1", 8501));
    }

    @Test
    void buildsAndRefreshesNativeClientsWithoutContactingConsul() throws Exception {
        ConsulApi1Configuration initial = new ConsulApi1Configuration("127.0.0.1", 8500);
        ConsulApi1Configuration updated = new ConsulApi1Configuration("127.0.0.1", 8501);
        MutableConfigurationSource<ConsulApi1Configuration> source =
                new MutableConfigurationSource<>(initial);

        try (ManagedResource<ConsulClient, ConsulApi1Configuration> managed =
                     ManagedResource.<ConsulClient, ConsulApi1Configuration>builder(
                                     source, new ConsulApi1Binding())
                             .healthCheck(ignored -> Health.healthy(ProbeScope.LOCAL))
                             .build()) {
            ConsulClient first = managed.execute(client -> client);
            source.update(updated);
            assertThat(managed.awaitIdle(Duration.ofSeconds(2))).isTrue();
            ConsulClient second = managed.execute(client -> client);

            assertThat(second).isNotSameAs(first);
            assertThat(managed.status().activeRevision()).isEqualTo(2);
        }
    }

    @Test
    void verifiesTheSelectedSdkMajor() {
        assertThat(ConsulApi1Binding.loadedDependencyVersion()).startsWith("1.");
        ConsulApi1Binding.verifyDependencyVersion();
    }
}
