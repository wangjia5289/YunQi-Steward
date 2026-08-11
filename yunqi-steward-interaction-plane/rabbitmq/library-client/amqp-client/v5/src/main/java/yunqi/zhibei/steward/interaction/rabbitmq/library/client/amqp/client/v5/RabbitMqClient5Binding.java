package yunqi.zhibei.steward.interaction.rabbitmq.library.client.amqp.client.v5;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.ClientVersion;
import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;

import java.util.Objects;

/** Binds managed lifecycle to a native RabbitMQ 5 connection. */
public final class RabbitMqClient5Binding
        implements ResourceBinding<RabbitMqClient5Configuration, Connection> {

    private static final int CLOSE_TIMEOUT_MILLIS = 30_000;

    public static BoundResource<Connection> start(RabbitMqClient5Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new RabbitMqClient5Binding());
    }

    static final String SDK_MAJOR = "5";

    private final ConnectionCreator connectionCreator;

    public RabbitMqClient5Binding() {
        this(ConnectionFactory::newConnection);
    }

    RabbitMqClient5Binding(ConnectionCreator connectionCreator) {
        this.connectionCreator = Objects.requireNonNull(connectionCreator, "connectionCreator");
        verifyDependencyVersion();
    }

    @Override
    public Connection create(RabbitMqClient5Configuration configuration) throws Exception {
        return connectionCreator.create(connectionFactory(configuration));
    }

    @Override
    public Health check(Connection connection) {
        return Objects.requireNonNull(connection, "connection").isOpen()
                ? Health.healthy(ProbeScope.CONNECTION_STATE)
                : Health.unhealthy(ProbeScope.CONNECTION_STATE);
    }

    @Override
    public void close(Connection connection) throws Exception {
        Objects.requireNonNull(connection, "connection")
                .close(0, "yunqi-steward binding close", CLOSE_TIMEOUT_MILLIS);
    }

    static ConnectionFactory connectionFactory(RabbitMqClient5Configuration configuration)
            throws Exception {
        Objects.requireNonNull(configuration, "configuration");
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(configuration.uri());
        factory.setConnectionTimeout(Math.toIntExact(configuration.connectionTimeout().toMillis()));
        factory.setAutomaticRecoveryEnabled(configuration.automaticRecovery());
        factory.setTopologyRecoveryEnabled(configuration.topologyRecovery());
        return factory;
    }

    static void verifyDependencyVersion() {
        String actual = ClientVersion.VERSION;
        if (!SDK_MAJOR.equals(majorOf(actual))) {
            throw new IllegalStateException(
                    "RabbitMQ client binding requires major " + SDK_MAJOR + " but loaded " + actual);
        }
    }

    private static String majorOf(String version) {
        if (version == null) {
            return null;
        }
        int separator = version.indexOf('.');
        return separator < 0 ? version : version.substring(0, separator);
    }

    @FunctionalInterface
    interface ConnectionCreator {
        Connection create(ConnectionFactory factory) throws Exception;
    }
}
