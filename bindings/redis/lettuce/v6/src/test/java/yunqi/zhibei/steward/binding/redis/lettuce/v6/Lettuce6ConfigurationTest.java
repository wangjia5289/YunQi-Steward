package yunqi.zhibei.steward.binding.redis.lettuce.v6;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Lettuce6ConfigurationTest {

    @Test
    void buildsFromDefaultsAndCopiesOnlyRequestedChanges() {
        Lettuce6Configuration configured = Lettuce6Configuration.builder()
                .host("redis.internal")
                .password("secret")
                .commandTimeout(Duration.ofSeconds(5))
                .build();
        Lettuce6Configuration updated = configured.toBuilder().database(2).build();

        assertThat(configured.host()).isEqualTo("redis.internal");
        assertThat(configured.password()).contains("secret");
        assertThat(configured.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(configured.commandTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(updated.database()).isEqualTo(2);
        assertThat(updated.host()).isEqualTo(configured.host());
    }

    @Test
    void providesPortableDefaultsAndRedactsThePassword() {
        Lettuce6Configuration defaults = Lettuce6Configuration.defaults();
        Lettuce6Configuration secured = new Lettuce6Configuration(
                " redis.internal ",
                6380,
                Optional.of("app"),
                Optional.of("secret"),
                2,
                true,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2));

        assertThat(defaults.host()).isEqualTo("127.0.0.1");
        assertThat(defaults.port()).isEqualTo(6379);
        assertThat(secured.host()).isEqualTo("redis.internal");
        assertThat(secured.toString()).contains("[REDACTED]").doesNotContain("secret");
    }

    @Test
    void rejectsValuesThatCannotBeRepresentedByAllMappedTimeouts() {
        assertThatThrownBy(() -> new Lettuce6Configuration(
                "localhost",
                0,
                Optional.empty(),
                Optional.empty(),
                0,
                false,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");

        assertThatThrownBy(() -> new Lettuce6Configuration(
                "localhost",
                6379,
                Optional.empty(),
                Optional.empty(),
                -1,
                false,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database");
    }
}
