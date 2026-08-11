package yunqi.zhibei.steward.support.testing;

import org.junit.jupiter.api.Test;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationSourceContractTest {

    @Test
    void verifiesTheInMemorySourceContract() throws Exception {
        MutableScenario scenario = new MutableScenario();

        ConfigurationSourceContract.verify(scenario, "initial-secret", "updated-secret");

        assertThat(scenario.source.snapshot().revision()).isEqualTo(3);
    }

    @Test
    void exposesOneContractEntryPointAndItsScenarioTypes() {
        assertThat(Modifier.isFinal(ConfigurationSourceContract.class.getModifiers())).isTrue();
        assertThat(ConfigurationSourceContract.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(ConfigurationSourceContract.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Member::getName))
                .containsExactly("verify");
    }

    private static final class MutableScenario
            implements ConfigurationSourceContract.Scenario<Settings> {

        private final MutableConfigurationSource<Settings> source =
                new MutableConfigurationSource<>(new Settings(1, "initial-secret"));

        @Override
        public ConfigurationSource<Settings> source() {
            return source;
        }

        @Override
        public long semanticVersion(Settings configuration) {
            return configuration.version();
        }

        @Override
        public void publishUpdate() {
            source.update(new Settings(2, "updated-secret"));
        }

        @Override
        public void publishFailure() {
            try {
                source.update(null);
            } catch (NullPointerException expected) {
                // Programmatic sources reject an incomplete value before publication.
            }
        }

        @Override
        public void publishRecovery() {
            source.update(new Settings(3, "recovered-secret"));
        }

        @Override
        public boolean awaitIdle(Duration timeout) {
            return true;
        }

        @Override
        public ConfigurationSourceContract.FailureMode failureMode() {
            return ConfigurationSourceContract.FailureMode.RETAINS_LAST_SNAPSHOT;
        }

        @Override
        public boolean closesSource() {
            return false;
        }

        @Override
        public void close() {
        }
    }

    private record Settings(long version, String secret) {
        @Override
        public String toString() {
            return "Settings[version=" + version + ", secret=[REDACTED]]";
        }
    }
}
