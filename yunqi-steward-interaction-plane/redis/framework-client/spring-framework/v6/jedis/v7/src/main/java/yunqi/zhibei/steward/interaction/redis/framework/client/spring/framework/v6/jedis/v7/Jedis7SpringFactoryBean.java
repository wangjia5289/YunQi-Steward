package yunqi.zhibei.steward.interaction.redis.framework.client.spring.framework.v6.jedis.v7;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import redis.clients.jedis.JedisPooled;
import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7.Jedis7Binding;
import yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7.Jedis7Configuration;

import java.util.Objects;

/**
 * Exposes a healthy native Jedis 7 client as a Spring-managed singleton.
 *
 * <p>The factory owns the {@link BoundResource}; Spring initialization starts the native client and
 * Spring destruction closes it. The object returned by {@link #getObject()} remains the vendor's
 * {@link JedisPooled} type.
 */
public final class Jedis7SpringFactoryBean
        implements FactoryBean<JedisPooled>, InitializingBean, DisposableBean {

    private final Jedis7Configuration configuration;

    private BoundResource<JedisPooled> boundResource;

    private boolean destroyed;

    /** Creates a Spring factory for one complete Jedis 7 configuration. */
    public Jedis7SpringFactoryBean(Jedis7Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /** Starts and health-checks the native client during Spring bean initialization. */
    @Override
    public synchronized void afterPropertiesSet() throws Exception {
        if (destroyed) {
            throw new IllegalStateException("The Spring factory has already been destroyed");
        }
        if (boundResource != null) {
            throw new IllegalStateException("The Spring factory has already been initialized");
        }
        boundResource = Jedis7Binding.start(configuration);
    }

    /** Returns the native Jedis client owned by this Spring factory. */
    @Override
    public synchronized JedisPooled getObject() {
        if (destroyed || boundResource == null) {
            throw new IllegalStateException("The Spring factory is not initialized");
        }
        return boundResource.resource();
    }

    @Override
    public Class<?> getObjectType() {
        return JedisPooled.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    /** Closes the owned native client during Spring context destruction. */
    @Override
    public synchronized void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        if (boundResource != null) {
            boundResource.close();
        }
    }
}
