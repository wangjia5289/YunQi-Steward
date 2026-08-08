package yunqi.zhibei.steward.binding.minio.java.v8;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinioJava8ConfigurationTest {
    @Test
    void buildsFromDefaultsAndCopiesCredentials() {
        var configured = MinioJava8Configuration.builder()
                .endpoint("https://minio.internal:9000")
                .credentials("access", "secret", "session")
                .region("cn-east-1")
                .build();
        var updated = configured.toBuilder().readTimeout(Duration.ofSeconds(5)).build();

        assertThat(configured.secretKey()).contains("secret");
        assertThat(configured.sessionToken()).contains("session");
        assertThat(updated.endpoint()).isEqualTo(configured.endpoint());
        assertThat(updated.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThatThrownBy(() -> MinioJava8Configuration.builder()
                .endpoint("https://user:credential-leak-marker@bad host:9000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("credential-leak-marker")
                .hasNoCause();
    }
}
