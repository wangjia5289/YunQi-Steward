package yunqi.zhibei.steward.binding.zookeeper.curator.v5;

import yunqi.zhibei.steward.support.testing.BindingContract;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Curator5BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new Curator5Binding(),
                configuration("127.0.0.1:2181"),
                configuration("127.0.0.1:2182"));
    }

    @Test
    void mapsConfigurationAndClosesAnOfflineClient() {
        Curator5Configuration configuration = new Curator5Configuration(
                "zk-a:2181,zk-b:2181", Optional.of("orders"), 30_000, 5_000, 200, 5);

        CuratorFrameworkFactory.Builder builder = Curator5Binding.nativeBuilder(configuration);
        assertThat(builder.getNamespace()).isEqualTo("orders");
        assertThat(builder.getSessionTimeoutMs()).isEqualTo(30_000);
        assertThat(builder.getConnectionTimeoutMs()).isEqualTo(5_000);
        assertThat(builder.getRetryPolicy()).isNotNull();

        Curator5Binding binding = new Curator5Binding();
        CuratorFramework client = binding.create(configuration);
        assertThat(client.getState()).isEqualTo(CuratorFrameworkState.STARTED);
        binding.close(client);
        assertThat(client.getState()).isEqualTo(CuratorFrameworkState.STOPPED);
    }

    @Test
    void validatesResourceSettings() {
        assertThat(Curator5Configuration.defaults().connectString())
                .isEqualTo("127.0.0.1:2181");

        assertThatThrownBy(() -> new Curator5Configuration(
                " ", Optional.empty(), 60_000, 15_000, 1_000, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectString");

        assertThatThrownBy(() -> new Curator5Configuration(
                "localhost:2181", Optional.empty(), 60_000, 15_000, 1_000, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryMaxRetries");
    }

    private static Curator5Configuration configuration(String connectString) {
        return new Curator5Configuration(
                connectString,
                Optional.of("orders"),
                1_000,
                100,
                10,
                0);
    }
}
