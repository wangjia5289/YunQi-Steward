package yunqi.zhibei.steward.control.configuration.file.properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PropertiesFileManagedResourceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void fileSourceDrivesSameTypeManagedResourceReplacement(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("redis.properties");
        write(file, "host=redis-a\n");
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        Binding binding = new Binding(created, closed);

        try (PropertiesFileConfigurationSource<Settings> source =
                     PropertiesFileConfigurationSource.open(file, PropertiesFileManagedResourceTest::load);
             ManagedResource<Client, Settings> managed = ManagedResource.bind(source, binding)) {
            assertThat(managed.execute(Client::host)).isEqualTo("redis-a");

            write(file, "host=redis-b\n");
            source.refresh();
            assertThat(source.awaitIdle(TIMEOUT)).isTrue();
            assertThat(managed.awaitIdle(TIMEOUT)).isTrue();

            assertThat(managed.status().activeRevision()).isEqualTo(2);
            assertThat(managed.execute(Client::host)).isEqualTo("redis-b");
            assertThat(created).hasValue(2);
        }

        assertThat(closed).hasValue(2);
    }

    private static Settings load(Properties properties) {
        return new Settings(properties.getProperty("host"));
    }

    private static void write(Path file, String content) throws Exception {
        Files.writeString(file, content, StandardCharsets.ISO_8859_1);
    }

    private record Settings(String host) {
    }

    private record Client(String host) {
    }

    private static final class Binding implements ResourceBinding<Settings, Client> {

        private final AtomicInteger created;
        private final AtomicInteger closed;

        private Binding(AtomicInteger created, AtomicInteger closed) {
            this.created = created;
            this.closed = closed;
        }

        @Override
        public Client create(Settings configuration) {
            created.incrementAndGet();
            return new Client(configuration.host());
        }

        @Override
        public Health check(Client resource) {
            return Health.healthy(ProbeScope.LOCAL);
        }

        @Override
        public void close(Client resource) {
            closed.incrementAndGet();
        }
    }
}
