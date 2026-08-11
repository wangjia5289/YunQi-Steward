package yunqi.zhibei.steward.telemetry.metric.micrometer;

import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.control.resource.restart.RestartRequiredMonitor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiShapeTest {

    private static final Class<?>[] PUBLIC_TYPES = {
            MicrometerLifecycleMetrics.class,
            MicrometerLifecycleMetrics.Registration.class,
            MicrometerConfigurationSourceMetrics.class,
            MicrometerConfigurationSourceMetrics.Registration.class
    };

    @Test
    void exposesOnlyFixedRegistrationAndOwnershipOperations() {
        assertThat(Arrays.stream(PUBLIC_TYPES).flatMap(PublicApiShapeTest::declaredMembers))
                .noneMatch(member -> Modifier.isProtected(member.getModifiers()));
        assertThat(publicMethodNames(MicrometerLifecycleMetrics.class))
                .containsExactly("bind");
        assertThat(publicMethodNames(MicrometerLifecycleMetrics.Registration.class))
                .containsExactlyInAnyOrder("isClosed", "close");
        assertThat(publicMethodNames(MicrometerConfigurationSourceMetrics.class))
                .containsExactly("bind");
        assertThat(publicMethodNames(MicrometerConfigurationSourceMetrics.Registration.class))
                .containsExactlyInAnyOrder("isClosed", "close");
        assertThat(MicrometerLifecycleMetrics.class.getConstructors()).isEmpty();
        assertThat(MicrometerLifecycleMetrics.Registration.class.getConstructors()).isEmpty();
        assertThat(MicrometerConfigurationSourceMetrics.class.getConstructors()).isEmpty();
        assertThat(MicrometerConfigurationSourceMetrics.Registration.class.getConstructors()).isEmpty();
    }

    @Test
    void adapterStateDoesNotStronglyRetainOwnersOrSensitiveValues() {
        assertThat(Arrays.stream(MicrometerLifecycleMetrics.class.getDeclaredClasses())
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(java.lang.reflect.Field::getType))
                .noneMatch(BoundResource.class::isAssignableFrom)
                .noneMatch(ManagedResource.class::isAssignableFrom)
                .noneMatch(RestartRequiredMonitor.class::isAssignableFrom)
                .noneMatch(ConfigurationSource.class::isAssignableFrom)
                .noneMatch(Throwable.class::isAssignableFrom);
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
