package yunqi.zhibei.steward.telemetry.log.slf4j;

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
        assertThat(declaredMembers(Slf4jLifecycleAdapter.class))
                .noneMatch(member -> Modifier.isProtected(member.getModifiers()));
        assertThat(publicMethodNames(Slf4jLifecycleAdapter.class))
                .containsExactlyInAnyOrder(
                        "start",
                        "drainedEvents",
                        "loggedEvents",
                        "successfulEvents",
                        "logFailures",
                        "failedEvents",
                        "sourceDroppedEvents",
                        "isClosed",
                        "close");
        assertThat(Slf4jLifecycleAdapter.class.getConstructors()).isEmpty();
        assertThat(Modifier.isFinal(Slf4jLifecycleAdapter.class.getModifiers())).isTrue();
    }

    @Test
    void adapterRetainsNoThrowableOrNeutralContractExpansion() {
        assertThat(Arrays.stream(Slf4jLifecycleAdapter.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType))
                .noneMatch(Throwable.class::isAssignableFrom)
                .noneMatch(Class.class::isAssignableFrom)
                .noneMatch(ClassLoader.class::isAssignableFrom);
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
