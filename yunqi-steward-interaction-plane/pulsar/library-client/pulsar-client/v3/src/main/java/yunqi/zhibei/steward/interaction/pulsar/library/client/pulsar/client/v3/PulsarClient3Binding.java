package yunqi.zhibei.steward.interaction.pulsar.library.client.pulsar.client.v3;

import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import org.apache.pulsar.PulsarVersion;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.PulsarClient;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Binds managed lifecycle to a native Pulsar 3 client. */
public final class PulsarClient3Binding
        implements ResourceBinding<PulsarClient3Configuration, PulsarClient> {

    public static BoundResource<PulsarClient> start(PulsarClient3Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new PulsarClient3Binding());
    }

    static final String SDK_MAJOR = "3";

    public PulsarClient3Binding() {
        verifyDependencyVersion();
    }

    @Override
    public PulsarClient create(PulsarClient3Configuration configuration) throws Exception {
        return clientBuilder(configuration).build();
    }

    @Override
    public Health check(PulsarClient client) {
        return Objects.requireNonNull(client, "client").isClosed()
                ? Health.unhealthy(ProbeScope.LOCAL)
                : Health.healthy(ProbeScope.LOCAL);
    }

    @Override
    public void close(PulsarClient client) throws Exception {
        Objects.requireNonNull(client, "client").close();
    }

    static ClientBuilder clientBuilder(PulsarClient3Configuration configuration)
            throws Exception {
        Objects.requireNonNull(configuration, "configuration");
        ClientBuilder builder = PulsarClient.builder()
                .serviceUrl(configuration.serviceUrl())
                .operationTimeout(
                        Math.toIntExact(configuration.operationTimeout().toMillis()),
                        TimeUnit.MILLISECONDS)
                .ioThreads(configuration.ioThreads())
                .listenerThreads(configuration.listenerThreads());
        if (configuration.authenticationPluginClassName().isPresent()) {
            builder.authentication(
                    configuration.authenticationPluginClassName().orElseThrow(),
                    configuration.authenticationParams().orElseThrow());
        }
        return builder;
    }

    static void verifyDependencyVersion() {
        String actual = PulsarVersion.getVersion();
        if (!SDK_MAJOR.equals(majorOf(actual))) {
            throw new IllegalStateException(
                    "Pulsar client binding requires major " + SDK_MAJOR + " but loaded " + actual);
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
