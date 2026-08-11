package yunqi.zhibei.steward.interaction.redis.framework.client.spring.framework.v6.jedis.v7;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import redis.clients.jedis.JedisPooled;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7.Jedis7Configuration;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiShapeTest {

    @Test
    void exposesOnlyTheAuditedSpringLifecycleContract() throws NoSuchMethodException {
        assertSpringLifecycleType(Jedis7SpringFactoryBean.class);
        assertThat(Jedis7SpringFactoryBean.class.getConstructors())
                .containsExactly(Jedis7SpringFactoryBean.class.getConstructor(
                        Jedis7Configuration.class));
        assertThat(Jedis7SpringFactoryBean.class.getMethod("getObject").getReturnType())
                .isEqualTo(JedisPooled.class);

        assertSpringLifecycleType(Jedis7ManagedResourceFactoryBean.class);
        assertThat(Jedis7ManagedResourceFactoryBean.class.getConstructors())
                .containsExactlyInAnyOrder(
                        Jedis7ManagedResourceFactoryBean.class.getConstructor(
                                ConfigurationSource.class),
                        Jedis7ManagedResourceFactoryBean.class.getConstructor(
                                ConfigurationSource.class,
                                LifecycleEventBuffer.class,
                                Duration.class));
        assertThat(Jedis7ManagedResourceFactoryBean.class.getMethod("getObject").getReturnType())
                .isEqualTo(ManagedResource.class);
    }

    private static void assertSpringLifecycleType(Class<?> type) {
        assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
        assertThat(type)
                .isAssignableTo(FactoryBean.class)
                .isAssignableTo(InitializingBean.class)
                .isAssignableTo(DisposableBean.class);
        assertThat(publicMethodNames(type))
                .containsExactlyInAnyOrder(
                        "afterPropertiesSet", "getObject", "getObjectType", "isSingleton", "destroy");
    }

    @Test
    void exposesNoProtectedExtensionPoints() {
        assertThat(declaredMembers(Jedis7SpringFactoryBean.class))
                .noneMatch(member -> Modifier.isProtected(member.getModifiers()));
        assertThat(declaredMembers(Jedis7ManagedResourceFactoryBean.class))
                .noneMatch(member -> Modifier.isProtected(member.getModifiers()));
    }

    private static Set<String> publicMethodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Member::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Stream<? extends Member> declaredMembers(Class<?> type) {
        return Stream.of(
                        Arrays.stream(type.getDeclaredConstructors()),
                        Arrays.stream(type.getDeclaredFields()),
                        Arrays.stream(type.getDeclaredMethods()))
                .flatMap(stream -> stream);
    }
}
