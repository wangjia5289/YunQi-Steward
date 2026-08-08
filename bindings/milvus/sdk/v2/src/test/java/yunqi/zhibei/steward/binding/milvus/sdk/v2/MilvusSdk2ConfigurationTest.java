package yunqi.zhibei.steward.binding.milvus.sdk.v2;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusSdk2ConfigurationTest {
    @Test
    void buildsFromDefaultsAndCopiesToken() {
        var configured = MilvusSdk2Configuration.builder()
                .uri("https://milvus.internal:19530")
                .token("secret")
                .database("orders")
                .build();
        var updated = configured.toBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        assertThat(configured.token()).contains("secret");
        assertThat(updated.uri()).isEqualTo(configured.uri());
        assertThat(updated.database()).isEqualTo("orders");
        assertThat(updated.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThatThrownBy(() -> MilvusSdk2Configuration.builder()
                .uri("https://user:credential-leak-marker@bad host:19530"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("credential-leak-marker")
                .hasNoCause();
    }
}
