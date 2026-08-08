package yunqi.zhibei.steward.binding.neo4j.driver.v6;

import yunqi.zhibei.steward.support.testing.BindingContract;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Neo4jDriver6BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new Neo4jDriver6Binding(),
                configuration(7687),
                configuration(7688),
                "graph-secret");
    }

    @Test
    void mapsResourceConfigurationWithoutConnecting() {
        Neo4jDriver6Configuration configuration = new Neo4jDriver6Configuration(
                URI.create("neo4j+s://graph.internal:7687"),
                Optional.of("neo4j"),
                Optional.of("graph-secret"),
                Duration.ofSeconds(4),
                Duration.ofSeconds(20),
                new Neo4jDriver6Configuration.Pool(
                        25, Duration.ofSeconds(30), Duration.ofMinutes(15)));

        Config nativeConfiguration = Neo4jDriver6Binding.nativeConfiguration(configuration);

        assertThat(nativeConfiguration.connectionTimeoutMillis()).isEqualTo(4_000);
        assertThat(nativeConfiguration.connectionAcquisitionTimeoutMillis()).isEqualTo(20_000);
        assertThat(nativeConfiguration.idleTimeBeforeConnectionTest()).isEqualTo(30_000);
        assertThat(nativeConfiguration.maxConnectionPoolSize()).isEqualTo(25);
        assertThat(nativeConfiguration.maxConnectionLifetimeMillis()).isEqualTo(900_000);
        assertThat(configuration.toString())
                .contains("[REDACTED]")
                .doesNotContain("graph-secret");

        Neo4jDriver6Binding binding = new Neo4jDriver6Binding();
        Driver driver = binding.create(configuration);
        binding.close(driver);
    }

    @Test
    void rejectsInvalidConnectionSettingsByConstruction() {
        Neo4jDriver6Configuration defaults = Neo4jDriver6Configuration.defaults();
        assertThat(defaults.uri()).isEqualTo(URI.create("bolt://127.0.0.1:7687"));

        assertThatThrownBy(() -> new Neo4jDriver6Configuration(
                URI.create("http://graph.internal"),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Neo4jDriver6Configuration.Pool.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bolt or neo4j");

        assertThatThrownBy(() -> new Neo4jDriver6Configuration(
                URI.create("bolt://graph.internal:7687"),
                Optional.of("neo4j"),
                Optional.empty(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Neo4jDriver6Configuration.Pool.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both be present");
    }

    private static Neo4jDriver6Configuration configuration(int port) {
        return new Neo4jDriver6Configuration(
                URI.create("bolt://127.0.0.1:" + port),
                Optional.of("neo4j"),
                Optional.of("graph-secret"),
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                new Neo4jDriver6Configuration.Pool(
                        2, Duration.ofMillis(100), Duration.ofSeconds(1)));
    }
}
