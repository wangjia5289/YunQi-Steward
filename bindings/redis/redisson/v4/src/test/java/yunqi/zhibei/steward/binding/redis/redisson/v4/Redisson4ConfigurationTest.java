package yunqi.zhibei.steward.binding.redis.redisson.v4;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Redisson4ConfigurationTest {

    @Test
    void buildsFromDefaultsAndCopiesOnlyRequestedChanges() {
        Redisson4Configuration configured = Redisson4Configuration.builder()
                .address("rediss://redis.internal:6380")
                .password("secret")
                .database(2)
                .lazyInitialization(true)
                .build();
        Redisson4Configuration updated = configured.toBuilder()
                .address("redis://redis-new:6379")
                .build();

        assertThat(configured.connection().address())
                .isEqualTo(URI.create("rediss://redis.internal:6380"));
        assertThat(configured.connection().password()).contains("secret");
        assertThat(configured.connection().database()).isEqualTo(2);
        assertThat(configured.client().lazyInitialization()).isTrue();
        assertThat(updated.connection().address())
                .isEqualTo(URI.create("redis://redis-new:6379"));
        assertThat(updated.connection().password()).contains("secret");
    }

    @Test
    void preservesDefaultsAndRedactsCredentials() {
        Redisson4Configuration defaults = Redisson4Configuration.defaults();
        Redisson4Configuration.Connection secured = new Redisson4Configuration.Connection(
                URI.create("rediss://redis.internal:6380"),
                Optional.of("app"),
                Optional.of("secret"),
                2,
                Optional.of("orders"),
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ofSeconds(4),
                3,
                5,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5));

        assertThat(defaults.connection().address())
                .isEqualTo(URI.create("redis://127.0.0.1:6379"));
        assertThat(secured.toString()).contains("[REDACTED]").doesNotContain("secret");
    }

    @Test
    void rejectsUnsupportedAddressesAndInconsistentPools() {
        assertThatThrownBy(() -> Redisson4Configuration.builder()
                .address("redis://user:credential-leak-marker@bad host:6379"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("credential-leak-marker")
                .hasNoCause();

        assertThatThrownBy(() -> Redisson4Configuration.Connection.defaults()
                .withAddress(URI.create("http://localhost:6379")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redis or rediss");

        assertThatThrownBy(() -> new Redisson4Configuration.Pool(10, 5, 1, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectionMinimumIdleSize");
    }
}
