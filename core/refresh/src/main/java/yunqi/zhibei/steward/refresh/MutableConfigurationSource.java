package yunqi.zhibei.steward.refresh;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An in-memory configuration source suitable for programmatic updates and tests.
 *
 * @param <C> configuration type
 */
public final class MutableConfigurationSource<C> implements ConfigurationSource<C> {

    private final AtomicReference<ConfigurationSnapshot<C>> current;
    private final CopyOnWriteArrayList<ListenerSubscription> subscriptions =
            new CopyOnWriteArrayList<>();

    /** Creates a source with the first complete configuration snapshot. */
    public MutableConfigurationSource(C initialConfiguration) {
        current = new AtomicReference<>(ConfigurationSnapshot.of(1, initialConfiguration));
    }

    @Override
    public ConfigurationSnapshot<C> snapshot() {
        return current.get();
    }

    @Override
    public Subscription subscribe(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        ListenerSubscription subscription = new ListenerSubscription(listener);
        subscriptions.add(subscription);
        return subscription;
    }

    /**
     * Publishes a complete snapshot and synchronously signals current subscribers.
     *
     * @param configuration new desired configuration
     */
    public void update(C configuration) {
        C updated = Objects.requireNonNull(configuration, "configuration");
        current.updateAndGet(previous -> ConfigurationSnapshot.of(
                Math.incrementExact(previous.revision()), updated));

        RuntimeException notificationFailure = null;
        for (ListenerSubscription subscription : subscriptions) {
            try {
                subscription.signal();
            } catch (RuntimeException failure) {
                if (notificationFailure == null) {
                    notificationFailure = failure;
                } else {
                    notificationFailure.addSuppressed(failure);
                }
            }
        }
        if (notificationFailure != null) {
            throw notificationFailure;
        }
    }

    private final class ListenerSubscription implements Subscription {

        private final Runnable listener;
        private boolean closed;

        private ListenerSubscription(Runnable listener) {
            this.listener = listener;
        }

        private synchronized void signal() {
            if (!closed) {
                listener.run();
            }
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                subscriptions.remove(this);
            }
        }
    }
}
