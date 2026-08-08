package yunqi.zhibei.steward.binding.etcd.jetcd.v0;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Jetcd0ConfigurationTest {
    @Test
    void buildsFromDefaultsAndCopiesClusterSettings() {
        var configured = Jetcd0Configuration.builder()
                .endpoint("https://etcd.internal:2379")
                .tlsEnabled(true)
                .credentials("app", "secret")
                .namespace("orders")
                .build();
        var updated = configured.toBuilder().keepaliveTime(Duration.ofSeconds(15)).build();

        assertThat(configured.password()).contains("secret");
        assertThat(configured.tlsEnabled()).isTrue();
        assertThat(updated.endpoints()).isEqualTo(configured.endpoints());
        assertThat(updated.keepaliveTime()).isEqualTo(Duration.ofSeconds(15));
        assertThatThrownBy(() -> Jetcd0Configuration.builder()
                .endpoint("https://user:credential-leak-marker@bad host:2379"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("credential-leak-marker")
                .hasNoCause();
    }
}
