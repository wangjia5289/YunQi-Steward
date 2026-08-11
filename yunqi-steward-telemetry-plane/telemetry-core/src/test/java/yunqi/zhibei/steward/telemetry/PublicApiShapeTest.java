package yunqi.zhibei.steward.telemetry;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiShapeTest {

    private static final Class<?>[] PUBLIC_TYPES = {
            LifecycleEvent.class,
            LifecycleEvent.Stage.class,
            LifecycleEvent.Outcome.class,
            LifecycleEventBuffer.class,
            LifecycleEventFanOut.class,
            LifecycleEventDelivery.class
    };

    @Test
    void exposesNoProtectedExtensionPointsOrConstructibleEvents() {
        assertThat(Arrays.stream(PUBLIC_TYPES).flatMap(PublicApiShapeTest::declaredMembers))
                .noneMatch(member -> Modifier.isProtected(member.getModifiers()));
        assertThat(LifecycleEvent.class.getConstructors()).isEmpty();
        assertThat(LifecycleEventFanOut.class.getConstructors()).isEmpty();
        assertThat(Modifier.isFinal(LifecycleEvent.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(LifecycleEventBuffer.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(LifecycleEventFanOut.class.getModifiers())).isTrue();
    }

    @Test
    void eventContainsOnlyTheAuditedFields() {
        assertThat(publicMethodNames(LifecycleEvent.class))
                .containsExactlyInAnyOrder(
                        "sequence",
                        "stage",
                        "outcome",
                        "generation",
                        "revision",
                        "startedAt",
                        "duration",
                        "failureType",
                        "toString");
        assertThat(Arrays.stream(LifecycleEvent.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType))
                .noneMatch(Throwable.class::isAssignableFrom)
                .noneMatch(Class.class::isAssignableFrom)
                .noneMatch(ClassLoader.class::isAssignableFrom);
    }

    @Test
    void fanOutExposesOnlyBoundedOwnershipAndAccountingOperations() {
        assertThat(publicMethodNames(LifecycleEventFanOut.class))
                .containsExactlyInAnyOrder(
                        "start",
                        "source",
                        "branch",
                        "branchNames",
                        "drainedEvents",
                        "deliveredEvents",
                        "droppedEvents",
                        "sourceDroppedEvents",
                        "isClosed",
                        "close");
    }

    @Test
    void deliveryExposesOnlyNeutralMonotonicAccounting() {
        assertThat(publicMethodNames(LifecycleEventDelivery.class))
                .containsExactlyInAnyOrder(
                        "drainedEvents",
                        "successfulEvents",
                        "failedEvents",
                        "sourceDroppedEvents",
                        "isClosed");
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
