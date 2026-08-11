package yunqi.zhibei.steward.interaction.zookeeper.library.client.curator.v5;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Curator5ConfigurationTest {
    @Test
    void buildsFromDefaultsAndCopiesRetrySettings() {
        var configured = Curator5Configuration.builder()
                .connectString("zk-a:2181,zk-b:2181")
                .namespace("orders")
                .retryMaxRetries(5)
                .build();
        var updated = configured.toBuilder().connectionTimeoutMillis(5_000).build();

        assertThat(configured.retryMaxRetries()).isEqualTo(5);
        assertThat(updated.connectString()).isEqualTo(configured.connectString());
        assertThat(updated.namespace()).contains("orders");
        assertThat(updated.connectionTimeoutMillis()).isEqualTo(5_000);
    }
}
