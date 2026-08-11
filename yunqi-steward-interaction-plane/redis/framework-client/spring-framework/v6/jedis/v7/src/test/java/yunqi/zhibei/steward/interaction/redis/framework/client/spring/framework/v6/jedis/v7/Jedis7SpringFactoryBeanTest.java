package yunqi.zhibei.steward.interaction.redis.framework.client.spring.framework.v6.jedis.v7;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import redis.clients.jedis.JedisPooled;
import yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7.Jedis7Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Jedis7SpringFactoryBeanTest {

    @Test
    void exposesNativeTypeBeforeCreatingTheSpringContext() {
        Jedis7SpringFactoryBean factory = new Jedis7SpringFactoryBean(
                Jedis7Configuration.builder().build());

        assertThat(factory.getObjectType()).isEqualTo(JedisPooled.class);
        assertThat(factory.isSingleton()).isTrue();
        assertThatThrownBy(factory::getObject)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startupFailureIsReportedAsARegularSpringBeanFailure() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(
                    "redis",
                    Jedis7SpringFactoryBean.class,
                    () -> new Jedis7SpringFactoryBean(
                            Jedis7Configuration.builder()
                                    .host("127.0.0.1")
                                    .port(1)
                                    .connectTimeout(java.time.Duration.ofMillis(50))
                                    .commandTimeout(java.time.Duration.ofMillis(50))
                                    .build()));

            assertThatThrownBy(context::refresh)
                    .isInstanceOf(BeanCreationException.class);
        }
    }
}
