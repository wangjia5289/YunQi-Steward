package yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Jedis7ConfigurationTest {

    @Test
    void buildsFromDefaultsAndCopiesOnlyRequestedChanges() {
        Jedis7Configuration configured = Jedis7Configuration.builder()
                .host("redis.internal")
                .password("secret")
                .maximumTotal(32)
                .build();
        Jedis7Configuration updated = configured.toBuilder().database(2).build();

        assertThat(configured.host()).isEqualTo("redis.internal");
        assertThat(configured.password()).contains("secret");
        assertThat(configured.maximumIdle()).isEqualTo(8);
        assertThat(configured.maximumTotal()).isEqualTo(32);
        assertThat(updated.database()).isEqualTo(2);
        assertThat(updated.host()).isEqualTo(configured.host());
    }

    @Test
    void validatesAndNormalizesConfiguration() {
        Jedis7Configuration defaults = Jedis7Configuration.defaults();

        assertThat(defaults.host()).isEqualTo("127.0.0.1");
        assertThat(defaults.port()).isEqualTo(6379);
        assertThatThrownBy(() -> configuration(" ", 6379, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
        assertThatThrownBy(() -> configuration("localhost", 65_536, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
        assertThatThrownBy(() -> configuration("localhost", 6379, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1 millisecond");
    }

    @Test
    void redactsPasswordFromDiagnostics() {
        Jedis7Configuration configuration = new Jedis7Configuration(
                "redis.internal", 6379, Optional.of("user"), Optional.of("secret"),
                2, true, Duration.ofSeconds(1), Duration.ofSeconds(2),
                1, 4, 8, Duration.ofSeconds(3));

        assertThat(configuration.toString())
                .contains("[REDACTED]")
                .doesNotContain("secret");
    }

    private static Jedis7Configuration configuration(
            String host, int port, Duration connectTimeout) {
        return new Jedis7Configuration(
                host, port, Optional.empty(), Optional.empty(), 0, false,
                connectTimeout, Duration.ofSeconds(1), 1, 4, 8, Duration.ofSeconds(1));
    }
}
