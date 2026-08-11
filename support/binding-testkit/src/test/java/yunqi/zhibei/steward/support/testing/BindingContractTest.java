package yunqi.zhibei.steward.support.testing;

import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class BindingContractTest {

    @Test
    void verifiesTheReusableLifecycleRules() throws Exception {
        TestBinding binding = new TestBinding();

        BindingContract.verify(
                binding,
                new TestConfiguration("first", "first-secret"),
                new TestConfiguration("second", "second-secret"),
                "first-secret",
                "second-secret");

        assertThat(binding.created).isGreaterThanOrEqualTo(4);
        assertThat(binding.closed).isEqualTo(binding.created);
    }

    @Test
    void exposesOnlyTheSingleContractEntryPoint() {
        assertThat(Modifier.isFinal(BindingContract.class.getModifiers())).isTrue();
        assertThat(BindingContract.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(BindingContract.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Member::getName))
                .containsExactly("verify");
    }

    private record TestConfiguration(String value, String secret) {
        @Override
        public String toString() {
            return "TestConfiguration[value=" + value + ", secret=[REDACTED]]";
        }
    }

    private static final class TestResource {
        private final AtomicBoolean closed = new AtomicBoolean();
    }

    private static final class TestBinding
            implements ResourceBinding<TestConfiguration, TestResource> {

        private int created;
        private int closed;

        @Override
        public TestResource create(TestConfiguration configuration) {
            created++;
            return new TestResource();
        }

        @Override
        public Health check(TestResource resource) {
            return Health.healthy(ProbeScope.LOCAL);
        }

        @Override
        public void close(TestResource resource) {
            if (resource.closed.compareAndSet(false, true)) {
                closed++;
            }
        }
    }
}
