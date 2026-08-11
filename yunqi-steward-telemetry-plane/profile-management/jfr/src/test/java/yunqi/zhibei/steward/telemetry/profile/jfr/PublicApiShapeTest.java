package yunqi.zhibei.steward.telemetry.profile.jfr;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiShapeTest {

    @Test
    void exposesOnlyAdapterOwnershipAndAccountingOperations() {
        assertThat(declaredMembers(JfrLifecycleAdapter.class))
                .noneMatch(member -> Modifier.isProtected(member.getModifiers()));
        assertThat(publicMethodNames(JfrLifecycleAdapter.class))
                .containsExactlyInAnyOrder(
                        "start",
                        "drainedEvents",
                        "forwardedEvents",
                        "successfulEvents",
                        "commitFailures",
                        "failedEvents",
                        "sourceDroppedEvents",
                        "isClosed",
                        "close");
        assertThat(JfrLifecycleAdapter.class.getConstructors()).isEmpty();
        assertThat(Modifier.isFinal(JfrLifecycleAdapter.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(JfrLifecycleEvent.class.getModifiers())).isFalse();
    }

    @Test
    void jfrPayloadHasNoSensitiveOrRetainedObjectFields() {
        assertThat(Arrays.stream(JfrLifecycleEvent.class.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType))
                .allMatch(type -> type == long.class || type == String.class)
                .noneMatch(Throwable.class::isAssignableFrom)
                .noneMatch(Class.class::isAssignableFrom)
                .noneMatch(ClassLoader.class::isAssignableFrom);
        assertThat(Arrays.stream(JfrLifecycleEvent.class.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .map(java.lang.reflect.Field::getName))
                .containsExactlyInAnyOrder(
                        "owner",
                        "sequence",
                        "stage",
                        "outcome",
                        "generation",
                        "revision",
                        "lifecycleStartedAt",
                        "lifecycleDuration",
                        "failureType")
                .noneMatch(name -> name.matches(
                        ".*(configuration|client|throwable|message|endpoint|credential|secret).*"));
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
