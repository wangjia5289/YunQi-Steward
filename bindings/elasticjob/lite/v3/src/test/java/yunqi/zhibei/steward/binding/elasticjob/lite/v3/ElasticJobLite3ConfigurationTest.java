package yunqi.zhibei.steward.binding.elasticjob.lite.v3;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticJobLite3ConfigurationTest {
    @Test
    void buildsFromDefaultsAndCopiesDigest() {
        var configured = ElasticJobLite3Configuration.builder()
                .serverLists("zk-a:2181,zk-b:2181")
                .namespace("orders")
                .digest("user:secret")
                .build();
        var updated = configured.toBuilder().maxRetries(5).build();

        assertThat(configured.digest()).contains("user:secret");
        assertThat(updated.serverLists()).isEqualTo(configured.serverLists());
        assertThat(updated.maxRetries()).isEqualTo(5);
    }
}
