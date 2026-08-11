package yunqi.zhibei.steward.interaction.elasticsearch.library.client.java.v9;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.TransportUtils;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class ElasticsearchJava9Binding
        implements ResourceBinding<ElasticsearchJava9Configuration, ElasticsearchJava9Handle> {

    public static BoundResource<ElasticsearchJava9Handle> start(
            ElasticsearchJava9Configuration configuration) throws Exception {
        return BoundResource.start(configuration, new ElasticsearchJava9Binding());
    }

    @Override
    public ElasticsearchJava9Handle create(ElasticsearchJava9Configuration configuration)
            throws IOException {
        Rest5Client restClient = restClientBuilder(configuration).build();
        try {
            Rest5ClientTransport transport =
                    new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
            return new ElasticsearchJava9Handle(
                    new ElasticsearchClient(transport), transport);
        } catch (RuntimeException | Error failure) {
            try {
                restClient.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public Health check(ElasticsearchJava9Handle handle) {
        Objects.requireNonNull(handle, "handle");
        try {
            return handle.client().ping().value()
                    ? Health.healthy(ProbeScope.REMOTE)
                    : Health.unhealthy(ProbeScope.REMOTE);
        } catch (Exception exception) {
            return Health.unhealthy(ProbeScope.REMOTE);
        }
    }

    @Override
    public void close(ElasticsearchJava9Handle handle) throws IOException {
        Objects.requireNonNull(handle, "handle").transport().close();
    }

    private static Rest5ClientBuilder restClientBuilder(
            ElasticsearchJava9Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Rest5ClientBuilder builder = Rest5Client.builder(configuration.endpoints());
        builder.setRequestConfigCallback(request -> request
                .setResponseTimeout(Timeout.of(configuration.requestTimeout()))
                .setConnectionRequestTimeout(Timeout.of(configuration.socketTimeout())));
        builder.setConnectionConfigCallback(connection -> connection
                .setConnectTimeout(Timeout.of(configuration.connectTimeout()))
                .setSocketTimeout(Timeout.of(configuration.socketTimeout()))
                .setTimeToLive(configuration.pool().keepAlive().toMillis(), TimeUnit.MILLISECONDS));
        builder.setConnectionManagerCallback(manager -> manager
                .setMaxConnTotal(configuration.pool().maximumConnections())
                .setMaxConnPerRoute(configuration.pool().maximumConnectionsPerRoute()));
        configuration.certificateFingerprint()
                .map(TransportUtils::sslContextFromCaFingerprint)
                .ifPresent(builder::setSSLContext);
        builder.setDefaultHeaders(authenticationHeaders(configuration));
        return builder;
    }

    private static Header[] authenticationHeaders(ElasticsearchJava9Configuration configuration) {
        if (configuration.apiKey().isPresent()) {
            return new Header[]{new BasicHeader(
                    "Authorization", "ApiKey " + configuration.apiKey().orElseThrow())};
        }
        if (configuration.username().isPresent()) {
            String credentials = configuration.username().orElseThrow() + ":"
                    + configuration.password().orElseThrow();
            String token = Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8));
            return new Header[]{new BasicHeader("Authorization", "Basic " + token)};
        }
        return new Header[0];
    }
}
