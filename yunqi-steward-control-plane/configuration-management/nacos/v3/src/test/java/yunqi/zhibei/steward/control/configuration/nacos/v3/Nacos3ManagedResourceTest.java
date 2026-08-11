package yunqi.zhibei.steward.control.configuration.nacos.v3;

import com.alibaba.nacos.api.exception.NacosException;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import yunqi.zhibei.steward.control.resource.refresh.FailureSnapshot;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Nacos3ManagedResourceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    void initialLoaderFailurePreventsNativeResourceCreation() {
        FakeNacosConfigService fake =
                new FakeNacosConfigService("first|healthy|initial-secret");
        TrackingBinding binding = new TrackingBinding();

        assertThatThrownBy(() -> {
            try (Nacos3ConfigurationSource<TestConfiguration> source = open(fake, content -> {
                        throw new IllegalArgumentException("cannot resolve " + content);
                    });
                    ManagedResource<TestResource, TestConfiguration> ignored =
                            ManagedResource.bind(source, binding)) {
                assertThat(ignored).isNotNull();
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No complete Nacos configuration is currently available")
                .hasNoCause()
                .satisfies(failure -> assertThat(failure.toString())
                        .doesNotContain("first", "initial-secret"));

        assertThat(binding.creationCount()).isZero();
        assertThat(fake.removals()).isEqualTo(1);
    }

    @Test
    void validUpdatePublishesNewGenerationAndRetiresTheOldOne() throws Exception {
        FakeNacosConfigService fake =
                new FakeNacosConfigService("first|healthy|initial-secret");
        TrackingBinding binding = new TrackingBinding();

        try (Nacos3ConfigurationSource<TestConfiguration> source = open(fake,
                     Nacos3ManagedResourceTest::parse);
             ManagedResource<TestResource, TestConfiguration> managed =
                     ManagedResource.bind(source, binding)) {
            TestResource first = binding.resource("first");

            emitAndSettle(fake, source, managed, "second|healthy|updated-secret");

            assertThat(managed.execute(TestResource::value)).isEqualTo("second");
            assertThat(managed.status().activeGeneration()).isEqualTo(2);
            assertThat(managed.status().activeRevision()).isEqualTo(2);
            assertThat(managed.status().desiredRevision()).isEqualTo(2);
            assertThat(first.closeCount()).isEqualTo(1);
            assertThat(binding.resource("second").closeCount()).isZero();
            assertThat(binding.creationCount()).isEqualTo(2);
        }

        assertThat(binding.resources()).allSatisfy(resource ->
                assertThat(resource.closeCount()).isEqualTo(1));
    }

    @Test
    void loaderOrSecretFailureKeepsActiveGenerationAndLaterUpdateRecovers()
            throws Exception {
        FakeNacosConfigService fake =
                new FakeNacosConfigService("first|healthy|initial-secret");
        TrackingBinding binding = new TrackingBinding();

        try (Nacos3ConfigurationSource<TestConfiguration> source = open(fake,
                     Nacos3ManagedResourceTest::parse);
             ManagedResource<TestResource, TestConfiguration> managed =
                     ManagedResource.bind(source, binding)) {
            TestResource first = binding.resource("first");

            emitAndSettle(fake, source, managed, "partial|healthy|missing-secret");

            assertThat(managed.execute(TestResource::value)).isEqualTo("first");
            assertThat(managed.status().activeRevision()).isEqualTo(1);
            assertThat(managed.status().desiredRevision()).isEqualTo(1);
            assertThat(managed.lastRefreshFailure()).hasValueSatisfying(failure -> {
                assertThat(failure.stage()).isEqualTo(FailureSnapshot.Stage.CONFIGURATION_SOURCE);
                assertThat(failure.failureType()).isEqualTo(IllegalStateException.class.getName());
                assertThat(failure.revision()).isZero();
                assertThat(failure.toString()).doesNotContain("partial", "missing-secret");
            });
            assertThat(binding.creationCount()).isEqualTo(1);
            assertThat(first.closeCount()).isZero();

            emitAndSettle(fake, source, managed, "recovered|healthy|renewed-secret");

            assertThat(managed.execute(TestResource::value)).isEqualTo("recovered");
            assertThat(managed.status().activeRevision()).isEqualTo(2);
            assertThat(managed.status().desiredRevision()).isEqualTo(2);
            assertThat(managed.lastRefreshFailure()).isEmpty();
            assertThat(first.closeCount()).isEqualTo(1);
            assertThat(binding.creationCount()).isEqualTo(2);
        }
    }

    @Test
    void rapidUpdatesConvergeOnLatestCompleteSnapshot() throws Exception {
        FakeNacosConfigService fake =
                new FakeNacosConfigService("first|healthy|initial-secret");
        TrackingBinding binding = new TrackingBinding();
        CountDownLatch slowLoaderEntered = new CountDownLatch(1);
        CountDownLatch releaseSlowLoader = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();

        try (Nacos3ConfigurationSource<TestConfiguration> source = open(fake, content -> {
                     loads.incrementAndGet();
                     if (content.startsWith("slow|")) {
                         slowLoaderEntered.countDown();
                         releaseSlowLoader.await();
                     }
                     return parse(content);
                 });
             ManagedResource<TestResource, TestConfiguration> managed =
                     ManagedResource.bind(source, binding)) {
            fake.emit("slow|healthy|slow-secret");
            assertThat(slowLoaderEntered.await(1, TimeUnit.SECONDS)).isTrue();
            fake.emit("discarded|healthy|discarded-secret");
            fake.emit("latest|healthy|latest-secret");
            releaseSlowLoader.countDown();

            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertThat(managed.awaitIdle(TIMEOUT)).isTrue();

            assertThat(source.snapshot().revision()).isEqualTo(3);
            assertThat(managed.execute(TestResource::value)).isEqualTo("latest");
            assertThat(managed.status().activeRevision()).isEqualTo(3);
            assertThat(managed.status().desiredRevision()).isEqualTo(3);
            assertThat(managed.lastRefreshFailure()).isEmpty();
            assertThat(loads).hasValue(3);
            assertThat(binding.resources())
                    .extracting(TestResource::value)
                    .doesNotContain("discarded");
        } finally {
            releaseSlowLoader.countDown();
        }
    }

    @Test
    void unhealthyCandidateRollsBackAndClosesExactlyOnce() throws Exception {
        FakeNacosConfigService fake =
                new FakeNacosConfigService("first|healthy|initial-secret");
        TrackingBinding binding = new TrackingBinding();

        try (Nacos3ConfigurationSource<TestConfiguration> source = open(fake,
                     Nacos3ManagedResourceTest::parse);
             ManagedResource<TestResource, TestConfiguration> managed =
                     ManagedResource.bind(source, binding)) {
            TestResource first = binding.resource("first");

            emitAndSettle(fake, source, managed, "bad|unhealthy|candidate-secret");

            TestResource candidate = binding.resource("bad");
            assertThat(managed.execute(TestResource::value)).isEqualTo("first");
            assertThat(managed.status().activeRevision()).isEqualTo(1);
            assertThat(managed.status().desiredRevision()).isEqualTo(2);
            assertThat(managed.lastRefreshFailure()).hasValueSatisfying(failure -> {
                assertThat(failure.stage())
                        .isEqualTo(FailureSnapshot.Stage.CANDIDATE_HEALTH_CHECK);
                assertThat(failure.failureType()).isEqualTo("unhealthy");
                assertThat(failure.revision()).isEqualTo(2);
            });
            assertThat(first.closeCount()).isZero();
            assertThat(candidate.closeCount()).isEqualTo(1);
        }

        assertThat(binding.resource("bad").closeCount()).isEqualTo(1);
        assertThat(binding.resource("first").closeCount()).isEqualTo(1);
    }

    @Test
    void closingSourceWhileLoaderIsBusyRejectsTheLateSnapshot() throws Exception {
        FakeNacosConfigService fake =
                new FakeNacosConfigService("first|healthy|initial-secret");
        TrackingBinding binding = new TrackingBinding();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        Nacos3ConfigurationSource<TestConfiguration> source = open(fake, content -> {
            if (content.startsWith("late|")) {
                loaderEntered.countDown();
                releaseLoader.await();
            }
            return parse(content);
        });
        ManagedResource<TestResource, TestConfiguration> managed =
                ManagedResource.bind(source, binding);

        try {
            fake.emit("late|healthy|late-secret");
            assertThat(loaderEntered.await(1, TimeUnit.SECONDS)).isTrue();

            source.close();
            releaseLoader.countDown();

            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertThat(managed.awaitIdle(TIMEOUT)).isTrue();
            assertThat(managed.execute(TestResource::value)).isEqualTo("first");
            assertThat(managed.status().activeRevision()).isEqualTo(1);
            assertThat(binding.creationCount()).isEqualTo(1);
            assertThat(fake.hasListener()).isFalse();

            fake.emit("later|healthy|later-secret");
            assertThat(binding.creationCount()).isEqualTo(1);
        } finally {
            releaseLoader.countDown();
            managed.close();
            source.close();
        }
    }

    @Test
    void closingManagedResourceDuringCreationPreventsLateCandidatePublication()
            throws Exception {
        FakeNacosConfigService fake =
                new FakeNacosConfigService("first|healthy|initial-secret");
        CountDownLatch candidateFactoryEntered = new CountDownLatch(1);
        CountDownLatch releaseCandidateFactory = new CountDownLatch(1);
        AtomicReference<TestResource> candidate = new AtomicReference<>();
        TrackingBinding binding = new TrackingBinding() {
            @Override
            public TestResource create(TestConfiguration configuration) throws Exception {
                TestResource resource = super.create(configuration);
                if (configuration.value().equals("blocked")) {
                    candidate.set(resource);
                    candidateFactoryEntered.countDown();
                    releaseCandidateFactory.await();
                }
                return resource;
            }
        };
        Nacos3ConfigurationSource<TestConfiguration> source = open(fake,
                Nacos3ManagedResourceTest::parse);
        ManagedResource<TestResource, TestConfiguration> managed =
                ManagedResource.<TestResource, TestConfiguration>builder(source, binding)
                        .closeWaitTimeout(Duration.ofMillis(20))
                        .build();

        try {
            fake.emit("blocked|healthy|candidate-secret");
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertThat(candidateFactoryEntered.await(1, TimeUnit.SECONDS)).isTrue();

            managed.close();
            source.close();
            fake.emit("later|healthy|later-secret");
            releaseCandidateFactory.countDown();

            assertThat(managed.awaitTermination(TIMEOUT)).isTrue();
            assertThat(candidate.get()).isNotNull();
            assertThat(candidate.get().closeCount()).isEqualTo(1);
            assertThat(managed.status().activeGeneration()).isZero();
            assertThat(managed.status().activeRevision()).isZero();
            assertThat(binding.creationCount()).isEqualTo(2);
            assertThat(fake.hasListener()).isFalse();
        } finally {
            releaseCandidateFactory.countDown();
            managed.close();
            source.close();
        }
    }

    private static Nacos3ConfigurationSource<TestConfiguration> open(
            FakeNacosConfigService fake,
            Nacos3ConfigurationSource.Loader<TestConfiguration> loader) throws NacosException {
        return Nacos3ConfigurationSource.open(
                fake.service(),
                "orders.yaml",
                "PROD",
                Duration.ofSeconds(1),
                loader);
    }

    private static void emitAndSettle(
            FakeNacosConfigService fake,
            Nacos3ConfigurationSource<TestConfiguration> source,
            ManagedResource<TestResource, TestConfiguration> managed,
            String content) throws InterruptedException {
        fake.emit(content);
        assertThat(source.awaitIdle(TIMEOUT)).isTrue();
        assertThat(managed.awaitIdle(TIMEOUT)).isTrue();
    }

    private static TestConfiguration parse(String content) {
        String[] fields = content.split("\\|", -1);
        if (fields.length != 3) {
            throw new IllegalArgumentException("invalid content: " + content);
        }
        if (fields[2].equals("missing-secret")) {
            throw new IllegalStateException("secret unavailable: " + content);
        }
        boolean healthy = switch (fields[1]) {
            case "healthy" -> true;
            case "unhealthy" -> false;
            default -> throw new IllegalArgumentException("invalid health: " + content);
        };
        return new TestConfiguration(fields[0], healthy, fields[2]);
    }

    private record TestConfiguration(String value, boolean healthy, String secret) {
        @Override
        public String toString() {
            return "TestConfiguration[value=" + value
                    + ", healthy=" + healthy
                    + ", secret=[REDACTED]]";
        }
    }

    private static class TrackingBinding
            implements ResourceBinding<TestConfiguration, TestResource> {

        private final List<TestResource> resources = new CopyOnWriteArrayList<>();

        @Override
        public TestResource create(TestConfiguration configuration) throws Exception {
            TestResource resource = new TestResource(
                    configuration.value(), configuration.healthy());
            resources.add(resource);
            return resource;
        }

        @Override
        public Health check(TestResource resource) {
            return resource.healthy()
                    ? Health.healthy(ProbeScope.LOCAL)
                    : Health.unhealthy(ProbeScope.LOCAL);
        }

        @Override
        public void close(TestResource resource) {
            resource.close();
        }

        int creationCount() {
            return resources.size();
        }

        TestResource resource(String value) {
            return resources.stream()
                    .filter(resource -> resource.value().equals(value))
                    .findFirst()
                    .orElseThrow();
        }

        List<TestResource> resources() {
            return List.copyOf(resources);
        }
    }

    private static final class TestResource {

        private final String value;
        private final boolean healthy;
        private final AtomicInteger closes = new AtomicInteger();

        private TestResource(String value, boolean healthy) {
            this.value = value;
            this.healthy = healthy;
        }

        private String value() {
            return value;
        }

        private boolean healthy() {
            return healthy;
        }

        private void close() {
            closes.incrementAndGet();
        }

        private int closeCount() {
            return closes.get();
        }
    }
}
