package yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class Jedis7BindingIntegrationTest {

    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7.4.2-alpine");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(REDIS_IMAGE)
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
                    .withStartupTimeout(Duration.ofSeconds(60))
                    .withLogConsumer(frame -> System.out.print("[redis-jedis7] " + frame.getUtf8String()));

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void startsChecksUsesAndClosesARealRedisClient() throws Exception {
        Jedis7Configuration configuration = Jedis7Configuration.builder()
                .host(REDIS.getHost())
                .port(REDIS.getMappedPort(6379))
                .build();

        try (var resource = Jedis7Binding.start(configuration)) {
            assertThat(resource.health().isHealthy()).isTrue();
            resource.resource().set("yunqi-steward:jedis7", "ok");
            assertThat(resource.resource().get("yunqi-steward:jedis7")).isEqualTo("ok");
        }
    }
}
