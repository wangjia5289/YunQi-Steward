package yunqi.zhibei.steward.binding.etcd.jetcd.v0;

import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.ClientBuilder;

import javax.net.ssl.SSLException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class Jetcd0Binding implements ResourceBinding<Jetcd0Configuration, Client> {

    public static BoundResource<Client> start(Jetcd0Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new Jetcd0Binding());
    }

    private static final long HEALTH_TIMEOUT_SECONDS = 5L;

    @Override
    public Client create(Jetcd0Configuration configuration) throws SSLException {
        return nativeBuilder(configuration).build();
    }

    @Override
    public Health check(Client client) {
        Objects.requireNonNull(client, "client");
        try {
            client.getKVClient()
                    .get(ByteSequence.EMPTY)
                    .get(HEALTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Health.healthy(ProbeScope.REMOTE);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return Health.unhealthy(ProbeScope.REMOTE);
        } catch (Exception failure) {
            return Health.unhealthy(ProbeScope.REMOTE);
        }
    }

    @Override
    public void close(Client client) {
        Objects.requireNonNull(client, "client").close();
    }

    static ClientBuilder nativeBuilder(Jetcd0Configuration configuration) throws SSLException {
        Objects.requireNonNull(configuration, "configuration");
        ClientBuilder builder = Client.builder()
                .endpoints(configuration.endpoints())
                .connectTimeout(configuration.connectTimeout())
                .retryDelay(configuration.retryDelayMillis())
                .retryMaxDelay(configuration.retryMaxDelayMillis())
                .retryMaxAttempts(configuration.retryMaxAttempts())
                .keepaliveTime(configuration.keepaliveTime())
                .keepaliveTimeout(configuration.keepaliveTimeout())
                .keepaliveWithoutCalls(configuration.keepaliveWithoutCalls())
                .maxInboundMessageSize(configuration.maxInboundMessageSize());
        configuration.authority().ifPresent(builder::authority);
        configuration.namespace().ifPresent(value -> builder.namespace(text(value)));
        if (configuration.username().isPresent()) {
            builder.user(text(configuration.username().orElseThrow()));
            builder.password(text(configuration.password().orElseThrow()));
        }
        if (configuration.tlsEnabled()) {
            builder.sslContext(ignored -> { });
        }
        return builder;
    }

    private static ByteSequence text(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }
}
