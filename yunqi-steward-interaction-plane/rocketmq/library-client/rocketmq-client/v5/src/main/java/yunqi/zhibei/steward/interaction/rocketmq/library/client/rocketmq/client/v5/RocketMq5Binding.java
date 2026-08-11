package yunqi.zhibei.steward.interaction.rocketmq.library.client.rocketmq.client.v5;

import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.StartupBinding;
import org.apache.rocketmq.client.producer.DefaultMQProducer;

import java.util.Objects;

public final class RocketMq5Binding
        implements StartupBinding<RocketMq5Configuration, DefaultMQProducer> {

    public static BoundResource<DefaultMQProducer> start(RocketMq5Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new RocketMq5Binding());
    }

    @Override
    public DefaultMQProducer create(RocketMq5Configuration configuration) throws Exception {
        Objects.requireNonNull(configuration, "configuration");
        DefaultMQProducer producer = new DefaultMQProducer(configuration.producerGroup());
        producer.setNamesrvAddr(configuration.nameServerAddress());
        producer.setSendMsgTimeout(Math.toIntExact(configuration.sendTimeout().toMillis()));
        producer.setRetryTimesWhenSendFailed(configuration.retryTimesWhenSendFailed());
        producer.setMaxMessageSize(configuration.maximumMessageSize());
        try {
            producer.start();
            return producer;
        } catch (Exception | Error failure) {
            try {
                producer.shutdown();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public Health check(DefaultMQProducer producer) {
        Objects.requireNonNull(producer, "producer");
        // The 5.x client exposes no non-deprecated broker readiness probe. A successful start is
        // the strongest local lifecycle signal; delivery readiness remains observable on send.
        return Health.healthy(ProbeScope.STARTUP_ONLY);
    }

    @Override
    public void close(DefaultMQProducer producer) {
        Objects.requireNonNull(producer, "producer").shutdown();
    }
}
