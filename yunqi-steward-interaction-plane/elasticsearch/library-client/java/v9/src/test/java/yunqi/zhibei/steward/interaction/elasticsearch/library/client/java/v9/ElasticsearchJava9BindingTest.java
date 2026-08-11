package yunqi.zhibei.steward.interaction.elasticsearch.library.client.java.v9;

import yunqi.zhibei.steward.support.testing.BindingContract;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchJava9BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new ElasticsearchJava9Binding(),
                configuration(9200),
                configuration(9201),
                "password-secret");
    }

    @Test
    void createsAndClosesNativeClientWithoutConnecting() throws Exception {
        ElasticsearchJava9Configuration configuration = configuration(1);
        ElasticsearchJava9Binding binding = new ElasticsearchJava9Binding();

        ElasticsearchJava9Handle handle = binding.create(configuration);
        assertThat(handle.client()).isNotNull();
        assertThat(handle.transport()).isNotNull();
        assertThat(handle.transport().restClient().isRunning()).isTrue();

        binding.close(handle);

        assertThat(handle.transport().restClient().isRunning()).isFalse();
        assertThat(configuration.toString())
                .doesNotContain("password-secret")
                .contains("password=[REDACTED]");
    }

    @Test
    void redactsApiKey() {
        ElasticsearchJava9Configuration configuration = new ElasticsearchJava9Configuration(
                List.of(URI.create("http://127.0.0.1:9200")),
                Optional.empty(),
                Optional.empty(),
                Optional.of("api-key-secret"),
                Optional.empty(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                new ElasticsearchJava9Configuration.Pool(2, 1, Duration.ofSeconds(1)));

        assertThat(configuration.toString())
                .doesNotContain("api-key-secret")
                .contains("apiKey=[REDACTED]");
    }

    private static ElasticsearchJava9Configuration configuration(int port) {
        return new ElasticsearchJava9Configuration(
                List.of(URI.create("http://127.0.0.1:" + port)),
                Optional.of("elastic"),
                Optional.of("password-secret"),
                Optional.empty(),
                Optional.empty(),
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                new ElasticsearchJava9Configuration.Pool(4, 2, Duration.ofSeconds(1)));
    }
}
