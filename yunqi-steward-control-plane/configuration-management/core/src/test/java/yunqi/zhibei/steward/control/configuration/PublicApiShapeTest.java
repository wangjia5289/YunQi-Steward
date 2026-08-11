package yunqi.zhibei.steward.control.configuration;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiShapeTest {

    private static final Class<?>[] PUBLIC_TYPES = {
            ConfigurationSource.class,
            ConfigurationSource.Subscription.class,
            ConfigurationSourceStatus.class,
            ConfigurationSourceStatus.State.class,
            ConfigurationSourceStatus.FailureStage.class,
            ConfigurationSnapshot.class,
            MutableConfigurationSource.class
    };

    @Test
    void exposesNoProtectedExtensionPoints() {
        assertThat(Arrays.stream(PUBLIC_TYPES).flatMap(PublicApiShapeTest::declaredMembers))
                .noneMatch(member -> Modifier.isProtected(member.getModifiers()));
    }

    @Test
    void snapshotsAreFactoryCreatedAndSecretSafe() {
        assertThat(ConfigurationSnapshot.class.isRecord()).isFalse();
        assertThat(ConfigurationSnapshot.class.getConstructors()).isEmpty();
        assertThat(Modifier.isFinal(ConfigurationSnapshot.class.getModifiers())).isTrue();
        assertThat(ConfigurationSnapshot.of(1, "secret").toString()).doesNotContain("secret");
    }

    @Test
    void sourceStatusIsFactoryCreatedValidatedAndSecretSafe() {
        assertThat(ConfigurationSourceStatus.class.isRecord()).isFalse();
        assertThat(ConfigurationSourceStatus.class.getConstructors()).isEmpty();
        assertThat(Modifier.isFinal(ConfigurationSourceStatus.class.getModifiers())).isTrue();

        ConfigurationSourceStatus status = ConfigurationSourceStatus.of(
                ConfigurationSourceStatus.State.UNAVAILABLE,
                7,
                2,
                1,
                ConfigurationSourceStatus.FailureStage.LOAD);

        assertThat(status.toString())
                .contains("UNAVAILABLE", "revision=7", "failures=2", "recoveries=1", "LOAD")
                .doesNotContain("secret");
    }

    private static Stream<? extends Member> declaredMembers(Class<?> type) {
        return Stream.of(
                        Arrays.stream(type.getDeclaredConstructors()),
                        Arrays.stream(type.getDeclaredFields()),
                        Arrays.stream(type.getDeclaredMethods()))
                .flatMap(stream -> stream);
    }
}
