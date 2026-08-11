package yunqi.zhibei.steward.control.resource.refresh;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiShapeTest {

    private static final Class<?>[] PUBLIC_TYPES = {
            FailureSnapshot.class,
            FailureSnapshot.Stage.class,
            ManagedResource.class,
            ManagedResource.Builder.class,
            ManagedResource.Lease.class,
            ManagedResourceStatus.class,
            ManagedResourceStatus.Lifecycle.class,
            ResourceOperation.class
    };

    @Test
    void exposesNoProtectedExtensionPoints() {
        assertThat(Arrays.stream(PUBLIC_TYPES).flatMap(PublicApiShapeTest::declaredMembers))
                .noneMatch(member -> Modifier.isProtected(member.getModifiers()));
    }

    @Test
    void builderCannotBypassTheRefreshSafeBindingContract() {
        Set<String> publicMethods = Arrays.stream(ManagedResource.Builder.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Member::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(publicMethods)
                .containsExactlyInAnyOrder(
                        "healthCheck", "closeWaitTimeout", "lifecycleEvents", "build");
        assertThat(ManagedResource.Builder.class.getConstructors()).isEmpty();
    }

    @Test
    void engineSnapshotsCannotBeConstructedOrExtendedByCallers() {
        assertThat(FailureSnapshot.class.isRecord()).isFalse();
        assertThat(FailureSnapshot.class.getConstructors()).isEmpty();
        assertThat(ManagedResourceStatus.class.getConstructors()).isEmpty();
        assertThat(Modifier.isFinal(FailureSnapshot.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(ManagedResourceStatus.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(LifecycleRuntime.class.getModifiers())).isFalse();
        assertThat(Arrays.stream(ManagedResource.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Member::getName))
                .doesNotContain("activeConfiguration");
    }

    @Test
    void managedResourceExposesOnlyTheAuditedOwnerOperations() {
        assertThat(publicMethodNames(ManagedResource.class))
                .containsExactlyInAnyOrder(
                        "builder",
                        "bind",
                        "acquire",
                        "execute",
                        "executeAsync",
                        "refresh",
                        "health",
                        "status",
                        "lastRefreshFailure",
                        "closeFailures",
                        "isTerminated",
                        "awaitIdle",
                        "awaitTermination",
                        "close");
        assertThat(publicMethodNames(ManagedResource.Lease.class))
                .containsExactlyInAnyOrder("execute", "close");
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
