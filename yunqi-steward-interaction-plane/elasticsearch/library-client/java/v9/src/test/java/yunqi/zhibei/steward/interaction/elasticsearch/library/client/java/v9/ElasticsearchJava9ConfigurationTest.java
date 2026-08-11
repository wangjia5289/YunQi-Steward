package yunqi.zhibei.steward.interaction.elasticsearch.library.client.java.v9;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticsearchJava9ConfigurationTest {
    @Test
    void buildsFromDefaultsAndKeepsAuthenticationConsistent() {
        var configured = ElasticsearchJava9Configuration.builder()
                .endpoint("https://search.internal:9200")
                .apiKey("secret")
                .build();
        var updated = configured.toBuilder().requestTimeout(Duration.ofSeconds(5)).build();

        assertThat(configured.apiKey()).contains("secret");
        assertThat(configured.username()).isEmpty();
        assertThat(updated.endpoints()).isEqualTo(configured.endpoints());
        assertThat(updated.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThatThrownBy(() -> ElasticsearchJava9Configuration.builder()
                .endpoint("https://user:credential-leak-marker@bad host:9200"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("credential-leak-marker")
                .hasNoCause();
    }
}
