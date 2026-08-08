package yunqi.zhibei.steward.binding.neo4j.driver.v6;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Neo4jDriver6ConfigurationTest {
    @Test
    void buildsFromDefaultsAndCopiesCredentials() {
        var configured = Neo4jDriver6Configuration.builder()
                .uri("neo4j+s://graph.internal:7687")
                .credentials("neo4j", "secret")
                .build();
        var updated = configured.toBuilder().acquireTimeout(Duration.ofSeconds(5)).build();

        assertThat(configured.password()).contains("secret");
        assertThat(updated.uri()).isEqualTo(configured.uri());
        assertThat(updated.acquireTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThatThrownBy(() -> Neo4jDriver6Configuration.builder()
                .uri("neo4j://user:credential-leak-marker@bad host:7687"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("credential-leak-marker")
                .hasNoCause();
    }
}
