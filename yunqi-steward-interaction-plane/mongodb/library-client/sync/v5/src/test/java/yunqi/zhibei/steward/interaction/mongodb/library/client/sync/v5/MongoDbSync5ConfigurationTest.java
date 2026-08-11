package yunqi.zhibei.steward.interaction.mongodb.library.client.sync.v5;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoDbSync5ConfigurationTest {

    @Test
    void buildsFromDefaultsAndCopiesConnectionString() {
        MongoDbSync5Configuration configured = MongoDbSync5Configuration.builder()
                .connectionString("mongodb://mongo-a:27017,mongo-b:27017/orders")
                .build();
        MongoDbSync5Configuration updated = configured.toBuilder().build();

        assertThat(updated).isEqualTo(configured);
        assertThat(updated.connectionString()).contains("mongo-a", "mongo-b");
    }

    @Test
    void providesOnlyAConnectionStringDefault() {
        assertThat(MongoDbSync5Configuration.defaults().connectionString())
                .isEqualTo("mongodb://127.0.0.1:27017");
    }

    @Test
    void validatesAndRedactsTheConnectionString() {
        assertThatThrownBy(() -> new MongoDbSync5Configuration(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectionString");
        assertThatThrownBy(() -> new MongoDbSync5Configuration("http://mongo:27017"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MongoDbSync5Configuration(
                "mongodb://user:credential-leak-marker%ZZ@mongo.internal:27017"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("credential-leak-marker")
                .hasNoCause();

        MongoDbSync5Configuration configuration = new MongoDbSync5Configuration(
                "mongodb://app:secret@mongo.internal:27017");
        assertThat(configuration.toString())
                .contains("[REDACTED]")
                .doesNotContain("secret");
    }
}
