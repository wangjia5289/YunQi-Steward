package yunqi.zhibei.steward.control.configuration.file.properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import yunqi.zhibei.steward.support.testing.ConfigurationSourceContract;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertiesFileConfigurationSourceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void satisfiesTheSharedConfigurationSourceContract(@TempDir Path directory) throws Exception {
        ConfigurationSourceContract.verify(
                new ContractScenario(directory),
                "initial-secret",
                "updated-secret",
                "recovered-secret");
    }

    @Test
    void loadsTypedSnapshotAndPublishesManualRefresh(@TempDir Path directory) throws Exception {
        Path file = createFile(directory, "host=redis-a\npassword=initial-secret\n");
        try (PropertiesFileConfigurationSource<Settings> source = open(file)) {
            AtomicInteger notifications = new AtomicInteger();
            source.subscribe(notifications::incrementAndGet);

            assertThat(source.snapshot().revision()).isOne();
            assertThat(source.snapshot().configuration().host()).isEqualTo("redis-a");

            writeContent(file, "host=redis-b\npassword=updated-secret\n");
            source.refresh();
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();

            assertThat(source.snapshot().revision()).isEqualTo(2);
            assertThat(source.snapshot().configuration().host()).isEqualTo("redis-b");
            assertThat(notifications).hasValue(1);
            assertThat(source.toString())
                    .contains("revision=2", "available=true", "closed=false")
                    .doesNotContain("redis-b", "updated-secret", directory.toString());
        }
    }

    @Test
    void ignoresEquivalentPropertiesAndRecoversFromLoaderFailure(@TempDir Path directory)
            throws Exception {
        Path file = createFile(directory, "host=redis-a\npassword=initial-secret\n");
        try (PropertiesFileConfigurationSource<Settings> source =
                     PropertiesFileConfigurationSource.open(file, properties -> {
                         if ("bad".equals(properties.getProperty("host"))) {
                             throw new IllegalArgumentException(
                                     "invalid " + properties.getProperty("password"));
                         }
                         return settings(properties);
                     })) {
            AtomicInteger notifications = new AtomicInteger();
            source.subscribe(notifications::incrementAndGet);

            writeContent(file, "password=initial-secret\nhost=redis-a\n");
            source.refresh();
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertThat(source.snapshot().revision()).isOne();
            assertThat(notifications).hasValue(0);

            writeContent(file, "host=bad\npassword=bad-secret\n");
            source.refresh();
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertThatThrownBy(source::snapshot)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("No complete properties configuration is currently available")
                    .hasNoCause();
            assertThat(source.toString()).contains("revision=1", "available=false")
                    .doesNotContain("bad", "bad-secret", directory.toString());
            assertThat(source.status().failures()).isEqualTo(1);
            assertThat(source.status().lastFailureStage())
                    .isEqualTo(yunqi.zhibei.steward.control.configuration.ConfigurationSourceStatus.FailureStage.LOAD);

            writeContent(file, "host=redis-c\npassword=recovered-secret\n");
            source.refresh();
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertThat(source.snapshot().revision()).isEqualTo(2);
            assertThat(source.snapshot().configuration().host()).isEqualTo("redis-c");
            assertThat(notifications).hasValue(2);
            assertThat(source.status().recoveries()).isEqualTo(1);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void followsKubernetesAtomicWriterDataSymlinkSwaps(@TempDir Path directory) throws Exception {
        Path mount = directory.resolve("mount");
        Files.createDirectories(mount);
        Path first = mount.resolve("..2026_08_11_01");
        Files.createDirectories(first);
        writeContent(first.resolve("redis.properties"),
                "host=redis-a\npassword=secret-a\n");
        Files.createSymbolicLink(mount.resolve("..data"), Path.of("..2026_08_11_01"));
        Path file = mount.resolve("redis.properties");
        Files.createSymbolicLink(file, Path.of("..data/redis.properties"));

        try (PropertiesFileConfigurationSource<Settings> source = open(file)) {
            Path second = mount.resolve("..2026_08_11_02");
            Files.createDirectories(second);
            writeContent(second.resolve("redis.properties"),
                    "host=redis-b\npassword=secret-b\n");
            Path pending = mount.resolve("..data_tmp");
            Files.createSymbolicLink(pending, Path.of("..2026_08_11_02"));
            Files.move(pending, mount.resolve("..data"),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            awaitRevision(source, 2);
            assertThat(source.snapshot().configuration().host()).isEqualTo("redis-b");
            assertThat(source.snapshot().configuration().password()).isEqualTo("secret-b");
        }
    }

    @Test
    void retainsTheLastRevisionAcrossTemporaryAbsenceAndPartialWrites(@TempDir Path directory)
            throws Exception {
        Path file = createFile(directory, "host=redis-a\npassword=secret-a\n");
        try (PropertiesFileConfigurationSource<Settings> source = open(file)) {
            Files.delete(file);
            source.refresh();
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertThat(source.status().state())
                    .isEqualTo(yunqi.zhibei.steward.control.configuration.ConfigurationSourceStatus.State.UNAVAILABLE);
            assertThat(source.status().revision()).isOne();
            assertThatThrownBy(source::snapshot)
                    .isInstanceOf(IllegalStateException.class)
                    .hasNoCause();

            writeContent(file, "host=redis-b\n");
            source.refresh();
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertThat(source.status().revision()).isOne();
            assertThat(source.status().state())
                    .isEqualTo(yunqi.zhibei.steward.control.configuration.ConfigurationSourceStatus.State.UNAVAILABLE);

            writeContent(file, "host=redis-b\npassword=secret-b\n");
            source.refresh();
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertThat(source.snapshot().revision()).isEqualTo(2);
            assertThat(source.status().recoveries()).isEqualTo(1);
        }
    }

    @Test
    void fileChangesAreObservedAndSubscriptionsCloseIdempotently(@TempDir Path directory)
            throws Exception {
        Path file = createFile(directory, "host=redis-a\npassword=secret-a\n");
        try (PropertiesFileConfigurationSource<Settings> source = open(file)) {
            CountDownLatch notification = new CountDownLatch(1);
            ConfigurationSource.Subscription subscription = source.subscribe(notification::countDown);

            writeContent(file, "host=redis-b\npassword=secret-b\n");
            assertThat(notification.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertThat(source.snapshot().configuration().host()).isEqualTo("redis-b");

            subscription.close();
            subscription.close();
            ConfigurationSource.Subscription late = source.subscribe(() -> { });
            late.close();
        }
    }

    @Test
    void sourceClosureIsIdempotentAndRejectsLateSubscriptions(@TempDir Path directory)
            throws Exception {
        Path file = createFile(directory, "host=redis-a\npassword=secret-a\n");
        PropertiesFileConfigurationSource<Settings> source = open(file);
        source.close();
        source.close();

        assertThatThrownBy(() -> source.subscribe(() -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The properties configuration source is closed");
        assertThat(source.toString()).contains("closed=true");
    }

    @Test
    void exposesOnlyTheAuditedAdapterOperations() {
        assertThat(Modifier.isFinal(PropertiesFileConfigurationSource.class.getModifiers()))
                .isTrue();
        assertThat(PropertiesFileConfigurationSource.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(PropertiesFileConfigurationSource.class.getDeclaredMethods())
                        .map(Member::getName))
                .contains("open", "snapshot", "subscribe", "refresh", "close")
                .doesNotContain("getFile", "getLoader");
    }

    private static PropertiesFileConfigurationSource<Settings> open(Path file) throws Exception {
        return PropertiesFileConfigurationSource.open(file, PropertiesFileConfigurationSourceTest::settings);
    }

    private static Settings settings(Properties properties) {
        String host = properties.getProperty("host");
        String password = properties.getProperty("password");
        if (host == null || host.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("incomplete settings");
        }
        return new Settings(
                host,
                password);
    }

    private static void awaitRevision(
            PropertiesFileConfigurationSource<?> source,
            long revision) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (source.snapshot().revision() >= revision) {
                    return;
                }
            } catch (IllegalStateException ignored) {
                // A Kubernetes writer update can briefly expose no complete file.
            }
            Thread.sleep(10);
        }
        throw new AssertionError("source did not reach revision " + revision);
    }

    private static Path createFile(Path directory, String content) throws Exception {
        Path file = directory.resolve("redis.properties");
        return writeContent(file, content);
    }

    private static Path writeContent(Path file, String content) throws Exception {
        Files.writeString(file, content, StandardCharsets.ISO_8859_1);
        return file;
    }

    private record Settings(String host, String password) {
    }

    private static final class ContractScenario
            implements ConfigurationSourceContract.Scenario<ContractSettings> {

        private final Path file;
        private final PropertiesFileConfigurationSource<ContractSettings> source;

        private ContractScenario(Path directory) throws Exception {
            file = directory.resolve("contract.properties");
            writeContent(file, "version=1\nsecret=initial-secret\n");
            source = PropertiesFileConfigurationSource.open(file, properties -> {
                String secret = properties.getProperty("secret");
                if (!properties.containsKey("version") || !properties.containsKey("secret")) {
                    throw new IllegalArgumentException("incomplete");
                }
                int version = Integer.parseInt(properties.getProperty("version"));
                if (version == 0) {
                    throw new IllegalArgumentException("invalid");
                }
                return new ContractSettings(version, secret);
            });
        }

        @Override
        public ConfigurationSource<ContractSettings> source() {
            return source;
        }

        @Override
        public long semanticVersion(ContractSettings configuration) {
            return configuration.version();
        }

        @Override
        public void publishUpdate() throws Exception {
            writeContent(file, "version=2\nsecret=updated-secret\n");
            source.refresh();
        }

        @Override
        public void publishFailure() throws Exception {
            writeContent(file, "version=broken\nsecret=failed-secret\n");
            source.refresh();
        }

        @Override
        public void publishRecovery() throws Exception {
            writeContent(file, "version=3\nsecret=recovered-secret\n");
            source.refresh();
        }

        @Override
        public boolean awaitIdle(Duration timeout) throws Exception {
            return source.awaitIdle(timeout);
        }

        @Override
        public ConfigurationSourceContract.FailureMode failureMode() {
            return ConfigurationSourceContract.FailureMode.BECOMES_UNAVAILABLE;
        }

        @Override
        public boolean closesSource() {
            return true;
        }

        @Override
        public void close() {
            source.close();
        }
    }

    private record ContractSettings(int version, String secret) {
        @Override
        public String toString() {
            return "ContractSettings[version=" + version + ", secret=[REDACTED]]";
        }
    }
}
