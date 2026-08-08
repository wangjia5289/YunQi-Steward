package yunqi.zhibei.steward.binding.redis.redisson.v4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class Redisson4BindingIntegrationTest {

    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7.4.2-alpine");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(REDIS_IMAGE)
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
                    .withStartupTimeout(Duration.ofSeconds(60))
                    .withLogConsumer(frame -> System.out.print("[redis-redisson4] " + frame.getUtf8String()));

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void startsChecksUsesAndClosesARealRedisClient() throws Exception {
        Redisson4Configuration configuration = Redisson4Configuration.builder()
                .address("redis://" + REDIS.getHost() + ':' + REDIS.getMappedPort(6379))
                .build();

        try (var resource = Redisson4Binding.start(configuration)) {
            assertThat(resource.health().isHealthy()).isTrue();
            resource.resource().getBucket("yunqi-steward:redisson4").set("ok");
            assertThat(resource.resource().getBucket("yunqi-steward:redisson4").get())
                    .isEqualTo("ok");
        }
    }
}
