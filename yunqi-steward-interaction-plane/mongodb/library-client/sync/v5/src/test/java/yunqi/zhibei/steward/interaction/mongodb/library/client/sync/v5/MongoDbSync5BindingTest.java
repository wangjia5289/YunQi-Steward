package yunqi.zhibei.steward.interaction.mongodb.library.client.sync.v5;

import com.mongodb.client.MongoClient;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;
import yunqi.zhibei.steward.support.testing.BindingContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MongoDbSync5BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContract() throws Exception {
        BindingContract.verify(
                new MongoDbSync5Binding(),
                configuration(27017),
                configuration(27018),
                "mongo-secret");
    }

    @Test
    void buildsAndRefreshesNativeClientsWithoutContactingMongoDb() throws Exception {
        MongoDbSync5Configuration initial = configuration(27017);
        MongoDbSync5Configuration updated = configuration(27018);
        MutableConfigurationSource<MongoDbSync5Configuration> source =
                new MutableConfigurationSource<>(initial);

        try (ManagedResource<MongoClient, MongoDbSync5Configuration> managed =
                     ManagedResource.<MongoClient, MongoDbSync5Configuration>builder(
                                     source, new MongoDbSync5Binding())
                             .healthCheck(ignored -> Health.healthy(ProbeScope.LOCAL))
                             .build()) {
            MongoClient first = managed.execute(client -> client);
            source.update(updated);
            assertThat(managed.awaitIdle(Duration.ofSeconds(5))).isTrue();
            MongoClient second = managed.execute(client -> client);

            assertThat(second).isNotSameAs(first);
            assertThat(managed.status().activeRevision()).isEqualTo(2);
        }
    }

    @Test
    void verifiesTheSelectedSdkMajor() {
        MongoDbSync5Binding.verifyDependencyVersion();
    }

    private static MongoDbSync5Configuration configuration(int port) {
        return new MongoDbSync5Configuration(
                "mongodb://app:mongo-secret@127.0.0.1:" + port + "/?authSource=admin");
    }
}
