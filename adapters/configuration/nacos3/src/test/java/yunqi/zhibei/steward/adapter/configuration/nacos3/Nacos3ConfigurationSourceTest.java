package yunqi.zhibei.steward.adapter.configuration.nacos3;

import com.alibaba.nacos.api.exception.NacosException;
import yunqi.zhibei.steward.refresh.ConfigurationSnapshot;
import yunqi.zhibei.steward.refresh.ConfigurationSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Nacos3ConfigurationSourceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void loadsInitialContentAndPublishesOnlyNewCompleteContent() throws Exception {
        FakeNacosConfigService fake = new FakeNacosConfigService("first|initial-secret");
        AtomicInteger loads = new AtomicInteger();

        try (Nacos3ConfigurationSource<TestConfiguration> source = open(fake, content -> {
            assertThat(fake.hasListener()).isTrue();
            loads.incrementAndGet();
            return parse(content);
        })) {
            AtomicInteger notifications = new AtomicInteger();
            source.subscribe(notifications::incrementAndGet);

            assertSnapshot(source.snapshot(), 1, "first", "initial-secret");

            fake.emit("first|initial-secret");
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertSnapshot(source.snapshot(), 1, "first", "initial-secret");
            assertThat(loads).hasValue(1);
            assertThat(notifications).hasValue(0);

            fake.emit("second|updated-secret");
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertSnapshot(source.snapshot(), 2, "second", "updated-secret");
            assertThat(loads).hasValue(2);
            assertThat(notifications).hasValue(1);
            assertThat(source.toString())
                    .contains("revision=2", "available=true", "closed=false")
                    .doesNotContain(
                            "orders.yaml",
                            "PROD",
                            "first",
                            "second",
                            "initial-secret",
                            "updated-secret");
        }

        assertThat(fake.removals()).isEqualTo(1);
        assertThat(fake.shutdowns()).isZero();
    }

    @Test
    void initialLoaderFailureRemovesTheInstalledListenerAndRedactsTheCause() {
        FakeNacosConfigService fake = new FakeNacosConfigService("bad|initial-secret");

        assertThatThrownBy(() -> open(fake, content -> {
            throw new IllegalArgumentException("cannot load " + content);
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No complete Nacos configuration is currently available")
                .hasNoCause()
                .satisfies(failure -> assertThat(failure.toString())
                        .doesNotContain("bad", "initial-secret"));

        assertThat(fake.removals()).isEqualTo(1);
        assertThat(fake.hasListener()).isFalse();
    }

    @Test
    void loaderOrSecretFailureKeepsThePreviousRevisionAndLaterUpdateRecovers()
            throws Exception {
        FakeNacosConfigService fake = new FakeNacosConfigService("first|initial-secret");
        try (Nacos3ConfigurationSource<TestConfiguration> source = open(fake, content -> {
            if (content.contains("vault-missing-secret")) {
                throw new IllegalStateException("secret failure: " + content);
            }
            return parse(content);
        })) {
            AtomicInteger notifications = new AtomicInteger();
            source.subscribe(notifications::incrementAndGet);

            fake.emit("bad|vault-missing-secret");
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();

            assertThatThrownBy(source::snapshot)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("No complete Nacos configuration is currently available")
                    .hasNoCause()
                    .satisfies(failure -> assertThat(failure.toString())
                            .doesNotContain("bad", "vault-missing-secret", "secret failure"));
            assertThat(notifications).hasValue(1);
            assertThat(source.toString())
                    .contains("revision=1", "available=false")
                    .doesNotContain("vault-missing-secret");

            fake.emit("recovered|renewed-secret");
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();

            assertSnapshot(source.snapshot(), 2, "recovered", "renewed-secret");
            assertThat(notifications).hasValue(2);
        }
    }

    @Test
    void coalescesCallbacksWhichArriveWhileTheLoaderIsBusy() throws Exception {
        FakeNacosConfigService fake = new FakeNacosConfigService("first|initial-secret");
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
        })) {
            AtomicInteger notifications = new AtomicInteger();
            source.subscribe(notifications::incrementAndGet);

            fake.emit("slow|slow-secret");
            assertThat(slowLoaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            fake.emit("discarded|discarded-secret");
            fake.emit("latest|latest-secret");
            releaseSlowLoader.countDown();

            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertSnapshot(source.snapshot(), 3, "latest", "latest-secret");
            assertThat(loads).hasValue(3);
            assertThat(notifications).hasValue(2);
        } finally {
            releaseSlowLoader.countDown();
        }
    }

    @Test
    void subscriptionAndSourceClosureAreIdempotentAndRejectLateWork() throws Exception {
        FakeNacosConfigService fake = new FakeNacosConfigService("first|initial-secret");
        Nacos3ConfigurationSource<TestConfiguration> source = open(fake,
                Nacos3ConfigurationSourceTest::parse);
        AtomicInteger notifications = new AtomicInteger();
        ConfigurationSource.Subscription subscription =
                source.subscribe(notifications::incrementAndGet);

        subscription.close();
        subscription.close();
        fake.emit("second|updated-secret");
        assertThat(source.awaitIdle(TIMEOUT)).isTrue();
        assertSnapshot(source.snapshot(), 2, "second", "updated-secret");
        assertThat(notifications).hasValue(0);

        source.close();
        source.close();
        fake.emit("late|late-secret");

        assertSnapshot(source.snapshot(), 2, "second", "updated-secret");
        assertThat(fake.removals()).isEqualTo(1);
        assertThatThrownBy(() -> source.subscribe(() -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The Nacos configuration source is closed");
        assertThat(source.toString()).contains("closed=true");
    }

    @Test
    void exposesOnlyTheAuditedAdapterOperations() {
        assertThat(Modifier.isFinal(Nacos3ConfigurationSource.class.getModifiers())).isTrue();
        assertThat(Nacos3ConfigurationSource.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(Nacos3ConfigurationSource.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Member::getName))
                .containsExactlyInAnyOrder("open", "snapshot", "subscribe", "close", "toString");
        assertThat(Nacos3ConfigurationSource.Loader.class.getDeclaredMethods())
                .extracting(Member::getName)
                .containsExactly("load");
    }

    private static Nacos3ConfigurationSource<TestConfiguration> open(
            FakeNacosConfigService fake,
            Nacos3ConfigurationSource.Loader<TestConfiguration> loader) throws NacosException {
        return Nacos3ConfigurationSource.open(
                fake.service(),
                "orders.yaml",
                "PROD",
                Duration.ofSeconds(3),
                loader);
    }

    private static TestConfiguration parse(String content) {
        String[] parts = content.split("\\|", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("invalid content");
        }
        return new TestConfiguration(parts[0], parts[1]);
    }

    private static void assertSnapshot(
            ConfigurationSnapshot<TestConfiguration> snapshot,
            long revision,
            String value,
            String secret) {
        assertThat(snapshot.revision()).isEqualTo(revision);
        assertThat(snapshot.configuration().value()).isEqualTo(value);
        assertThat(snapshot.configuration().secret()).isEqualTo(secret);
    }

    private record TestConfiguration(String value, String secret) {
        @Override
        public String toString() {
            return "TestConfiguration[value=" + value + ", secret=[REDACTED]]";
        }
    }

}
