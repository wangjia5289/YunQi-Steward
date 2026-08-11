package yunqi.zhibei.steward.interaction.nacos.library.client.nacos.client.v3;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NacosClient3ConfigurationTest {
    @Test
    void buildsFromDefaultsAndCopiesClientProperties() {
        var configured = NacosClient3Configuration.builder()
                .serverAddress("nacos.internal:8848")
                .credentials("app", "secret")
                .namespace("orders")
                .clientProperty("contextPath", "/nacos")
                .build();
        var updated = configured.toBuilder().clearNamespace().build();

        assertThat(configured.password()).contains("secret");
        assertThat(configured.clientProperties()).containsEntry("contextPath", "/nacos");
        assertThat(updated.serverAddress()).isEqualTo("nacos.internal:8848");
        assertThat(updated.namespace()).isEmpty();
    }
}
