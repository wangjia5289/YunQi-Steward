package yunqi.zhibei.steward.interaction.redis.framework.client.spring.framework.v6.jedis.v7;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import redis.clients.jedis.JedisPooled;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7.Jedis7Binding;
import yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7.Jedis7Configuration;

import java.time.Duration;
import java.util.Objects;

/**
 * Exposes a dynamically replaceable Jedis 7 managed resource as a Spring singleton.
 *
 * <p>The factory owns the {@link ManagedResource}, while the supplied configuration source remains
 * caller-owned and may be registered as a separate Spring bean. Application components inject the
 * managed resource and use {@link ManagedResource#execute} or another scoped operation; this
 * factory never exposes a replaceable {@link JedisPooled} reference directly.
 */
public final class Jedis7ManagedResourceFactoryBean
        implements FactoryBean<ManagedResource<JedisPooled, Jedis7Configuration>>,
        InitializingBean,
        DisposableBean {

    private final ConfigurationSource<Jedis7Configuration> configurationSource;
    private final LifecycleEventBuffer lifecycleEvents;
    private final Duration closeWaitTimeout;

    private ManagedResource<JedisPooled, Jedis7Configuration> managedResource;

    private boolean destroyed;

    /** Creates a Spring factory backed by one caller-owned configuration source. */
    public Jedis7ManagedResourceFactoryBean(
            ConfigurationSource<Jedis7Configuration> configurationSource) {
        this(configurationSource, LifecycleEventBuffer.noop(), Duration.ofSeconds(30));
    }

    /**
     * Creates a factory with caller-owned lifecycle observation and a bounded Spring shutdown wait.
     *
     * <p>The factory owns neither the source nor the event buffer. Applications must close the
     * buffer after the Spring context has been closed and all adapters have drained it.
     */
    public Jedis7ManagedResourceFactoryBean(
            ConfigurationSource<Jedis7Configuration> configurationSource,
            LifecycleEventBuffer lifecycleEvents,
            Duration closeWaitTimeout) {
        this.configurationSource = Objects.requireNonNull(
                configurationSource, "configurationSource");
        this.lifecycleEvents = Objects.requireNonNull(lifecycleEvents, "lifecycleEvents");
        Duration timeout = Objects.requireNonNull(closeWaitTimeout, "closeWaitTimeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("closeWaitTimeout must be positive");
        }
        this.closeWaitTimeout = timeout;
    }

    /** Subscribes to configuration and publishes the initial healthy client generation. */
    @Override
    public synchronized void afterPropertiesSet() {
        if (destroyed) {
            throw new IllegalStateException("The Spring factory has already been destroyed");
        }
        if (managedResource != null) {
            throw new IllegalStateException("The Spring factory has already been initialized");
        }
        managedResource = ManagedResource.builder(configurationSource, new Jedis7Binding())
                .lifecycleEvents(lifecycleEvents)
                .closeWaitTimeout(closeWaitTimeout)
                .build();
    }

    /** Returns the managed owner applications use for scoped access to the current client. */
    @Override
    public synchronized ManagedResource<JedisPooled, Jedis7Configuration> getObject() {
        if (destroyed || managedResource == null) {
            throw new IllegalStateException("The Spring factory is not initialized");
        }
        return managedResource;
    }

    @Override
    public Class<?> getObjectType() {
        return ManagedResource.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    /** Stops refresh work and safely retires every owned client generation. */
    @Override
    public synchronized void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        if (managedResource != null) {
            managedResource.close();
        }
    }
}
