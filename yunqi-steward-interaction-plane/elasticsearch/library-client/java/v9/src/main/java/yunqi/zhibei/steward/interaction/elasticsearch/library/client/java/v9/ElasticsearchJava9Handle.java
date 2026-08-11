package yunqi.zhibei.steward.interaction.elasticsearch.library.client.java.v9;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;

import java.util.Objects;

public final class ElasticsearchJava9Handle {

    private final ElasticsearchClient client;
    private final Rest5ClientTransport transport;

    ElasticsearchJava9Handle(
            ElasticsearchClient client,
            Rest5ClientTransport transport) {
        this.client = Objects.requireNonNull(client, "client");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public ElasticsearchClient client() {
        return client;
    }

    public Rest5ClientTransport transport() {
        return transport;
    }
}
