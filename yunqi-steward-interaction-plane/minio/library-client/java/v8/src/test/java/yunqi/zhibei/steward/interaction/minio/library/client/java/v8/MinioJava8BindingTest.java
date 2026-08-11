package yunqi.zhibei.steward.interaction.minio.library.client.java.v8;

import yunqi.zhibei.steward.support.testing.BindingContract;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MinioJava8BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new MinioJava8Binding(),
                configuration(1),
                configuration(2),
                "access-secret",
                "key-secret",
                "session-secret");
    }

    @Test
    void createsAndClosesNativeClientWithoutConnecting() throws Exception {
        MinioJava8Configuration configuration = configuration(1);
        MinioJava8Binding binding = new MinioJava8Binding();

        MinioJava8Handle handle = binding.create(configuration);
        assertThat(handle.client()).isNotNull();
        assertThat(handle.httpClient()).isNotNull();
        assertThat(handle.httpClient().dispatcher().getMaxRequests()).isEqualTo(8);
        assertThat(handle.httpClient().dispatcher().getMaxRequestsPerHost()).isEqualTo(3);

        binding.close(handle);

        assertThat(handle.httpClient().dispatcher().executorService().isShutdown()).isTrue();
        assertThat(configuration.toString())
                .doesNotContain("access-secret", "key-secret", "session-secret")
                .contains("accessKey=[PRESENT]", "secretKey=[REDACTED]", "sessionToken=[REDACTED]");
    }

    private static MinioJava8Configuration configuration(int port) {
        return new MinioJava8Configuration(
                URI.create("http://127.0.0.1:" + port),
                Optional.of("access-secret"),
                Optional.of("key-secret"),
                Optional.of("session-secret"),
                Optional.of("us-east-1"),
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                new MinioJava8Configuration.Pool(8, 3, Duration.ofSeconds(1)));
    }
}
