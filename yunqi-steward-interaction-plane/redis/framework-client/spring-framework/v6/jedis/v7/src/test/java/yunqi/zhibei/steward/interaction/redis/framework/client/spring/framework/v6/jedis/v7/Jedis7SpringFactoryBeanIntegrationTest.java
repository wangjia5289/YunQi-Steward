package yunqi.zhibei.steward.interaction.redis.framework.client.spring.framework.v6.jedis.v7;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisException;
import yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7.Jedis7Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class Jedis7SpringFactoryBeanIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.2-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
            .withStartupTimeout(Duration.ofSeconds(60));

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void springStartsUsesAndClosesTheNativeClient() {
        Jedis7Configuration configuration = Jedis7Configuration.builder()
                .host(REDIS.getHost())
                .port(REDIS.getMappedPort(6379))
                .build();

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(
                "redis",
                Jedis7SpringFactoryBean.class,
                () -> new Jedis7SpringFactoryBean(configuration));
        context.refresh();

        JedisPooled client = context.getBean(JedisPooled.class);
        client.set("yunqi-steward:spring-jedis7", "ok");
        assertThat(client.get("yunqi-steward:spring-jedis7")).isEqualTo("ok");
        Jedis7SpringFactoryBean factory = context.getBean(
                "&redis", Jedis7SpringFactoryBean.class);

        context.close();
        factory.destroy();

        assertThatThrownBy(factory::getObject)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(client::ping)
                .isInstanceOf(JedisException.class);
    }
}
