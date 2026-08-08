package yunqi.zhibei.steward.adapter.configuration.nacos3;

import yunqi.zhibei.steward.refresh.ConfigurationSnapshot;
import yunqi.zhibei.steward.restart.RestartRequiredFailure;
import yunqi.zhibei.steward.restart.RestartRequiredMonitor;
import yunqi.zhibei.steward.restart.RestartRequiredStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class Nacos3RestartRequiredMonitorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    void validNacosUpdateRequiresRestartFromTheAppliedRevision() throws Exception {
        FakeNacosConfigService fake = new FakeNacosConfigService("initial|initial-secret");

        try (Nacos3ConfigurationSource<String> source = open(fake)) {
            ConfigurationSnapshot<String> applied = source.snapshot();
            try (RestartRequiredMonitor<String> monitor =
                    RestartRequiredMonitor.watch(source, applied.revision())) {
                fake.emit("updated|updated-secret");
                assertThat(source.awaitIdle(TIMEOUT)).isTrue();

                RestartRequiredStatus status = monitor.status();
                assertThat(status.state())
                        .isEqualTo(RestartRequiredStatus.State.RESTART_REQUIRED);
                assertThat(status.appliedRevision()).isEqualTo(1);
                assertThat(status.desiredRevision()).isEqualTo(2);
                assertThat(status.lastFailure()).isEmpty();
                assertThat(status.toString())
                        .doesNotContain("initial-secret", "updated-secret");
            }
        }
    }

    @Test
    void failedLoadDoesNotRequireRestartAndLaterCompleteUpdateRecovers() throws Exception {
        FakeNacosConfigService fake = new FakeNacosConfigService("initial|initial-secret");

        try (Nacos3ConfigurationSource<String> source = open(fake)) {
            ConfigurationSnapshot<String> applied = source.snapshot();
            try (RestartRequiredMonitor<String> monitor =
                    RestartRequiredMonitor.watch(source, applied.revision())) {
                fake.emit("partial|missing-secret");
                assertThat(source.awaitIdle(TIMEOUT)).isTrue();

                RestartRequiredStatus failed = monitor.status();
                assertThat(failed.state()).isEqualTo(RestartRequiredStatus.State.CURRENT);
                assertThat(failed.desiredRevision()).isEqualTo(1);
                assertThat(failed.lastFailure()).hasValueSatisfying(failure -> {
                    assertThat(failure.stage())
                            .isEqualTo(RestartRequiredFailure.Stage.CONFIGURATION_SOURCE);
                    assertThat(failure.failureType())
                            .isEqualTo(IllegalStateException.class.getName());
                    assertThat(failure.toString()).doesNotContain("missing-secret");
                });

                fake.emit("recovered|renewed-secret");
                assertThat(source.awaitIdle(TIMEOUT)).isTrue();

                RestartRequiredStatus recovered = monitor.status();
                assertThat(recovered.state())
                        .isEqualTo(RestartRequiredStatus.State.RESTART_REQUIRED);
                assertThat(recovered.desiredRevision()).isEqualTo(2);
                assertThat(recovered.lastFailure()).isEmpty();
            }
        }
    }

    private static Nacos3ConfigurationSource<String> open(FakeNacosConfigService fake)
            throws Exception {
        return Nacos3ConfigurationSource.open(
                fake.service(),
                "startup-only.yaml",
                "PROD",
                TIMEOUT,
                content -> {
                    if (content.contains("missing-secret")) {
                        throw new IllegalStateException("secret lookup failed: " + content);
                    }
                    return content;
                });
    }
}
