package yunqi.zhibei.steward.control.resource;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiShapeTest {

    private static final Class<?>[] PUBLIC_TYPES = {
            BoundResource.class,
            BoundResource.State.class,
            Health.class,
            Health.Status.class,
            HealthCheck.class,
            ProbeScope.class,
            ResourceBinding.class,
            ResourceCloser.class,
            ResourceFactory.class,
            StartupBinding.class
    };

    @Test
    void exposesNoProtectedExtensionPoints() {
        assertThat(Arrays.stream(PUBLIC_TYPES).flatMap(PublicApiShapeTest::declaredMembers))
                .noneMatch(member -> Modifier.isProtected(member.getModifiers()));
    }

    @Test
    void ownersAndResultsCannotBeConstructedOrExtendedByCallers() {
        assertThat(BoundResource.class.getConstructors()).isEmpty();
        assertThat(Health.class.getConstructors()).isEmpty();
        assertThat(Modifier.isFinal(BoundResource.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(Health.class.getModifiers())).isTrue();
    }

    @Test
    void boundResourceExposesOnlyTheAuditedOwnerOperations() {
        assertThat(publicMethodNames(BoundResource.class))
                .containsExactlyInAnyOrder(
                        "start", "resource", "state", "isClosed", "health", "close");
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
