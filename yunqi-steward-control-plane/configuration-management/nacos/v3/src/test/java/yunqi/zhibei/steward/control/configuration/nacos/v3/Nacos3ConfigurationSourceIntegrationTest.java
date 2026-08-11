package yunqi.zhibei.steward.control.configuration.nacos.v3;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import yunqi.zhibei.steward.control.configuration.ConfigurationSourceStatus;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requires a reachable Nacos 3 deployment in YUNQI_NACOS_TEST_SERVER_ADDR. */
@EnabledIfEnvironmentVariable(named = "YUNQI_NACOS_TEST_SERVER_ADDR", matches = ".+")
class Nacos3ConfigurationSourceIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void publishesUpdatesFailureRecoveryAndCleanShutdownAgainstNacos() throws Exception {
        String serverAddress = System.getenv("YUNQI_NACOS_TEST_SERVER_ADDR");
        String dataId = "steward-integration-" + UUID.randomUUID();
        String group = "STEWard_TEST";
        ConfigService service = NacosFactory.createConfigService(serverAddress);
        Nacos3ConfigurationSource<Settings> source = null;
        try {
            assertThat(service.publishConfig(dataId, group, content(1, "initial-secret"))).isTrue();
            Nacos3ConfigurationSource<Settings> opened = Nacos3ConfigurationSource.open(
                    service,
                    dataId,
                    group,
                    Duration.ofSeconds(5),
                    Nacos3ConfigurationSourceIntegrationTest::parse);
            source = opened;
            assertThat(opened.snapshot().revision()).isOne();

            assertThat(service.publishConfig(dataId, group, content(2, "updated-secret"))).isTrue();
            await(() -> revisionIs(opened, 2));
            assertThat(opened.snapshot().configuration().version()).isEqualTo(2);

            assertThat(service.publishConfig(dataId, group, "broken|failed-secret")).isTrue();
            await(() -> opened.status().state() == ConfigurationSourceStatus.State.UNAVAILABLE);
            assertThat(opened.status().revision()).isEqualTo(2);
            assertThatThrownBy(opened::snapshot)
                    .isInstanceOf(IllegalStateException.class)
                    .hasNoCause();

            assertThat(service.publishConfig(dataId, group, content(3, "recovered-secret"))).isTrue();
            await(() -> revisionIs(opened, 3));
            assertThat(opened.status().recoveries()).isEqualTo(1);
        } finally {
            if (source != null) {
                source.close();
                source.close();
            }
            try {
                service.removeConfig(dataId, group);
            } finally {
                service.shutDown();
            }
        }
        assertThat(source).isNotNull();
        assertThat(source.status().state()).isEqualTo(ConfigurationSourceStatus.State.CLOSED);
    }

    private static String content(int version, String secret) {
        return version + "|" + secret;
    }

    private static Settings parse(String content) {
        String[] parts = content.split("\\|", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("incomplete content");
        }
        int version = Integer.parseInt(parts[0]);
        if (version < 1) {
            throw new IllegalArgumentException("invalid version");
        }
        return new Settings(version, parts[1]);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Nacos source did not converge before " + TIMEOUT);
            }
            Thread.onSpinWait();
        }
    }

    private static boolean revisionIs(
            Nacos3ConfigurationSource<?> source,
            long revision) {
        try {
            return source.snapshot().revision() == revision;
        } catch (IllegalStateException unavailable) {
            return false;
        }
    }

    private record Settings(int version, String secret) {
        @Override
        public String toString() {
            return "Settings[version=" + version + ", secret=[REDACTED]]";
        }
    }
}
