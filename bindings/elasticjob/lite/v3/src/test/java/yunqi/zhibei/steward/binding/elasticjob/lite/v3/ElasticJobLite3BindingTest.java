package yunqi.zhibei.steward.binding.elasticjob.lite.v3;

import yunqi.zhibei.steward.support.testing.BindingContract;
import org.apache.curator.test.TestingServer;
import org.apache.shardingsphere.elasticjob.reg.zookeeper.ZookeeperConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticJobLite3BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContractAgainstEmbeddedZooKeeper() throws Exception {
        try (TestingServer server = new TestingServer()) {
            BindingContract.verify(
                    new ElasticJobLite3Binding(),
                    configuration(server.getConnectString(), "orders-a"),
                    configuration(server.getConnectString(), "orders-b"),
                    "elasticjob:secret");
        }
    }

    @Test
    void mapsRegistryConfigurationWithoutConnecting() {
        ElasticJobLite3Configuration configuration = new ElasticJobLite3Configuration(
                "zk-a:2181,zk-b:2181",
                "orders",
                100,
                500,
                5,
                30_000,
                5_000,
                Optional.of("elasticjob:secret"));

        ZookeeperConfiguration nativeConfiguration =
                ElasticJobLite3Binding.nativeConfiguration(configuration);

        assertThat(nativeConfiguration.getServerLists()).isEqualTo("zk-a:2181,zk-b:2181");
        assertThat(nativeConfiguration.getNamespace()).isEqualTo("orders");
        assertThat(nativeConfiguration.getBaseSleepTimeMilliseconds()).isEqualTo(100);
        assertThat(nativeConfiguration.getMaxSleepTimeMilliseconds()).isEqualTo(500);
        assertThat(nativeConfiguration.getMaxRetries()).isEqualTo(5);
        assertThat(nativeConfiguration.getSessionTimeoutMilliseconds()).isEqualTo(30_000);
        assertThat(nativeConfiguration.getConnectionTimeoutMilliseconds()).isEqualTo(5_000);
        assertThat(nativeConfiguration.getDigest()).isEqualTo("elasticjob:secret");
        assertThat(configuration.toString())
                .contains("[REDACTED]")
                .doesNotContain("elasticjob:secret");
    }

    @Test
    void validatesRetryWindowAndDefaults() {
        assertThat(ElasticJobLite3Configuration.defaults().serverLists())
                .isEqualTo("127.0.0.1:2181");

        assertThatThrownBy(() -> new ElasticJobLite3Configuration(
                "127.0.0.1:2181",
                "orders",
                1_000,
                500,
                3,
                60_000,
                15_000,
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least baseSleepTimeMilliseconds");

        assertThatThrownBy(() -> new ElasticJobLite3Configuration(
                "127.0.0.1:2181",
                " ",
                1_000,
                3_000,
                3,
                60_000,
                15_000,
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace");
    }

    private static ElasticJobLite3Configuration configuration(
            String serverLists,
            String namespace) {
        return new ElasticJobLite3Configuration(
                serverLists,
                namespace,
                10,
                20,
                1,
                1_000,
                1_000,
                Optional.of("elasticjob:secret"));
    }
}
