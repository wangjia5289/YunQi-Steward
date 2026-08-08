package yunqi.zhibei.steward.binding.rabbitmq.client.v5;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.refresh.ManagedResource;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.refresh.MutableConfigurationSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqClient5BindingTest {

    @Test
    void mapsEveryNativeConnectionSetting() throws Exception {
        ConnectionFactory factory = RabbitMqClient5Binding.connectionFactory(configuration(5673));

        assertThat(factory.getHost()).isEqualTo("rabbit.internal");
        assertThat(factory.getPort()).isEqualTo(5673);
        assertThat(factory.getUsername()).isEqualTo("app");
        assertThat(factory.getPassword()).isEqualTo("secret");
        assertThat(factory.getVirtualHost()).isEqualTo("orders");
        assertThat(factory.getConnectionTimeout()).isEqualTo(4_000);
        assertThat(factory.isAutomaticRecoveryEnabled()).isTrue();
        assertThat(factory.isTopologyRecoveryEnabled()).isFalse();
    }

    @Test
    void refreshesNativeConnectionsAndClosesRetiredOnesOffline() throws Exception {
        RabbitMqClient5Configuration initial = configuration(5672);
        RabbitMqClient5Configuration updated = configuration(5673);
        MutableConfigurationSource<RabbitMqClient5Configuration> source =
                new MutableConfigurationSource<>(initial);
        RabbitMqClient5Binding binding = new RabbitMqClient5Binding(
                ignored -> fakeConnection());

        Connection first;
        try (ManagedResource<Connection, RabbitMqClient5Configuration> managed =
                     ManagedResource.<Connection, RabbitMqClient5Configuration>builder(
                                     source, binding)
                             .healthCheck(ignored -> Health.healthy(ProbeScope.LOCAL))
                             .build()) {
            first = managed.execute(connection -> connection);
            source.update(updated);
            assertThat(managed.awaitIdle(Duration.ofSeconds(2))).isTrue();
            Connection second = managed.execute(connection -> connection);

            assertThat(second).isNotSameAs(first);
            assertThat(managed.status().activeRevision()).isEqualTo(2);
            assertThat(first.isOpen()).isFalse();
        }
    }

    @Test
    void closesAnOwnedConnectionOnlyOnce() throws Exception {
        TrackedConnection tracked = trackedConnection(true);
        RabbitMqClient5Binding binding = new RabbitMqClient5Binding(
                ignored -> tracked.connection());

        BoundResource<Connection> bound = BoundResource.start(configuration(5672), binding);
        bound.close();
        bound.close();

        assertThat(tracked.closeCount()).hasValue(1);
    }

    @Test
    void rollsBackAnUnhealthyReplacementAndKeepsTheActiveConnection() throws Exception {
        List<TrackedConnection> created = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        RabbitMqClient5Binding binding = new RabbitMqClient5Binding(ignored -> {
            TrackedConnection connection = trackedConnection(attempts.incrementAndGet() == 1);
            created.add(connection);
            return connection.connection();
        });
        MutableConfigurationSource<RabbitMqClient5Configuration> source =
                new MutableConfigurationSource<>(configuration(5672));
        ManagedResource<Connection, RabbitMqClient5Configuration> managed =
                ManagedResource.bind(source, binding);

        Connection initial = managed.execute(connection -> connection);
        source.update(configuration(5673));
        assertThat(managed.awaitIdle(Duration.ofSeconds(2))).isTrue();

        assertThat(managed.execute((Connection connection) -> connection)).isSameAs(initial);
        assertThat(created).hasSize(2);
        assertThat(created.get(0).closeCount()).hasValue(0);
        assertThat(created.get(1).closeCount()).hasValue(1);
        assertThat(managed.lastRefreshFailure()).isPresent();

        managed.close();
        assertThat(created.get(0).closeCount()).hasValue(1);
        assertThat(created.get(1).closeCount()).hasValue(1);
    }

    @Test
    void verifiesTheSelectedSdkMajor() {
        RabbitMqClient5Binding.verifyDependencyVersion();
    }

    private static RabbitMqClient5Configuration configuration(int port) {
        return new RabbitMqClient5Configuration(
                "amqp://app:secret@rabbit.internal:" + port + "/orders",
                Duration.ofSeconds(4),
                true,
                false);
    }

    private static Connection fakeConnection() {
        return trackedConnection(true).connection();
    }

    private static TrackedConnection trackedConnection(boolean initiallyOpen) {
        AtomicBoolean open = new AtomicBoolean(initiallyOpen);
        AtomicInteger closeCount = new AtomicInteger();
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isOpen" -> open.get();
                    case "close" -> {
                        closeCount.incrementAndGet();
                        open.set(false);
                        yield null;
                    }
                    case "toString" -> "FakeRabbitConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new TrackedConnection(connection, closeCount);
    }

    private record TrackedConnection(Connection connection, AtomicInteger closeCount) {
    }
}
