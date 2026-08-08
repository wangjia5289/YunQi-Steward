package yunqi.zhibei.steward.restart;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiShapeTest {

    private static final Class<?>[] PUBLIC_TYPES = {
            RestartRequiredFailure.class,
            RestartRequiredFailure.Stage.class,
            RestartRequiredMonitor.class,
            RestartRequiredStatus.class,
            RestartRequiredStatus.State.class
    };

    @Test
    void exposesNoProtectedExtensionPoints() {
        assertThat(Arrays.stream(PUBLIC_TYPES).flatMap(PublicApiShapeTest::declaredMembers))
                .noneMatch(member -> Modifier.isProtected(member.getModifiers()));
    }

    @Test
    void engineValuesCannotBeConstructedOrExtendedByCallers() {
        assertThat(RestartRequiredFailure.class.getConstructors()).isEmpty();
        assertThat(RestartRequiredStatus.class.getConstructors()).isEmpty();
        assertThat(Modifier.isFinal(RestartRequiredFailure.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(RestartRequiredStatus.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(RestartRequiredMonitor.class.getModifiers())).isTrue();
    }

    @Test
    void monitorExposesOnlyObservationAndOwnershipOperations() {
        assertThat(publicMethodNames(RestartRequiredMonitor.class))
                .containsExactlyInAnyOrder("watch", "status", "close");
        assertThat(RestartRequiredMonitor.class.getConstructors()).isEmpty();
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
