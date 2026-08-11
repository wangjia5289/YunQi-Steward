package yunqi.zhibei.steward.interaction.redis.framework.client.spring.framework.v6.jedis.v7;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import redis.clients.jedis.JedisPooled;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7.Jedis7Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Jedis7ManagedResourceFactoryBeanTest {

    @Test
    void exposesManagedTypeBeforeCreatingTheSpringContext() {
        var source = new MutableConfigurationSource<>(Jedis7Configuration.builder().build());
        var factory = new Jedis7ManagedResourceFactoryBean(source);

        assertThat(factory.getObjectType()).isEqualTo(ManagedResource.class);
        assertThat(factory.isSingleton()).isTrue();
        assertThatThrownBy(factory::getObject)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startupFailureIsReportedAsARegularSpringBeanFailure() {
        var source = new MutableConfigurationSource<>(
                Jedis7Configuration.builder()
                        .host("127.0.0.1")
                        .port(1)
                        .connectTimeout(Duration.ofMillis(50))
                        .commandTimeout(Duration.ofMillis(50))
                        .build());

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    "redis",
                    Jedis7ManagedResourceFactoryBean.class,
                    () -> new Jedis7ManagedResourceFactoryBean(source));

            assertThatThrownBy(context::refresh)
                    .isInstanceOf(BeanCreationException.class);
        }
    }

    @Test
    void destroyIsIdempotentBeforeInitialization() {
        var source = new MutableConfigurationSource<>(Jedis7Configuration.builder().build());
        var factory = new Jedis7ManagedResourceFactoryBean(source);

        factory.destroy();
        factory.destroy();

        assertThatThrownBy(factory::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(factory::getObject)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatesOptionalObservationAndShutdownConfiguration() {
        var source = new MutableConfigurationSource<>(Jedis7Configuration.builder().build());
        LifecycleEventBuffer events = LifecycleEventBuffer.create(4);

        var factory = new Jedis7ManagedResourceFactoryBean(
                source, events, Duration.ofSeconds(2));
        factory.destroy();
        events.close();

        assertThatThrownBy(() -> new Jedis7ManagedResourceFactoryBean(
                source, LifecycleEventBuffer.noop(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("closeWaitTimeout must be positive");
    }
}
