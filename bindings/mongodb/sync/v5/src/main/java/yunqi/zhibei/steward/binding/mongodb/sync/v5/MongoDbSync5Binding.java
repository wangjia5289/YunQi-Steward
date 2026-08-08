package yunqi.zhibei.steward.binding.mongodb.sync.v5;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.internal.build.MongoDriverVersion;
import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import org.bson.Document;

import java.util.Objects;

/** Binds managed lifecycle to a native MongoDB synchronous 5 client. */
public final class MongoDbSync5Binding
        implements ResourceBinding<MongoDbSync5Configuration, MongoClient> {

    public static BoundResource<MongoClient> start(MongoDbSync5Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new MongoDbSync5Binding());
    }

    static final String SDK_MAJOR = "5";

    public MongoDbSync5Binding() {
        verifyDependencyVersion();
    }

    @Override
    public MongoClient create(MongoDbSync5Configuration configuration) {
        return MongoClients.create(
                Objects.requireNonNull(configuration, "configuration").connectionString());
    }

    @Override
    public Health check(MongoClient client) {
        Objects.requireNonNull(client, "client")
                .getDatabase("admin")
                .runCommand(new Document("ping", 1));
        return Health.healthy(ProbeScope.REMOTE);
    }

    @Override
    public void close(MongoClient client) {
        Objects.requireNonNull(client, "client").close();
    }

    static void verifyDependencyVersion() {
        String actual = MongoDriverVersion.VERSION;
        if (!SDK_MAJOR.equals(majorOf(actual))) {
            throw new IllegalStateException(
                    "MongoDB sync binding requires major " + SDK_MAJOR + " but loaded " + actual);
        }
    }

    private static String majorOf(String version) {
        if (version == null) {
            return null;
        }
        int separator = version.indexOf('.');
        return separator < 0 ? version : version.substring(0, separator);
    }
}
