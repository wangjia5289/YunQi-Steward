package yunqi.zhibei.steward.control.configuration.nacos.v3;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import yunqi.zhibei.steward.control.configuration.ConfigurationSnapshot;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import yunqi.zhibei.steward.control.configuration.ConfigurationSourceStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Supplies complete typed configuration snapshots from one Nacos 3 data ID and group. */
public final class Nacos3ConfigurationSource<C>
        implements ConfigurationSource<C>, AutoCloseable {

    private final ConfigService configService;
    private final String dataId;
    private final String group;
    private final Loader<C> loader;
    private final ExecutorService loaderExecutor;
    private final CopyOnWriteArrayList<ListenerSubscription> subscriptions =
            new CopyOnWriteArrayList<>();
    private final Object monitor = new Object();
    private final Listener nativeListener = new Listener() {
        @Override
        public Executor getExecutor() {
            return Runnable::run;
        }

        @Override
        public void receiveConfigInfo(String content) {
            offer(content);
        }
    };

    private volatile ConfigurationSnapshot<C> current;
    private volatile boolean loadFailed;
    private long failures;
    private long recoveries;
    private ConfigurationSourceStatus.FailureStage lastFailureStage =
            ConfigurationSourceStatus.FailureStage.NONE;
    private byte[] appliedDigest;
    private String pendingContent;
    private boolean pending;
    private boolean workerScheduled;
    private boolean initialized;
    private boolean closed;

    private Nacos3ConfigurationSource(
            ConfigService configService,
            String dataId,
            String group,
            Loader<C> loader) {
        this.configService = configService;
        this.dataId = dataId;
        this.group = group;
        this.loader = loader;
        loaderExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("nacos3-configuration-loader-", 0).factory());
    }

    /**
     * Atomically registers the Nacos listener, loads the initial content, and returns the source.
     *
     * <p>The supplied Nacos service remains owned by the caller. Closing this source removes only
     * its listener and stops its internal loader executor.
     *
     * @param configService caller-owned Nacos configuration client
     * @param dataId Nacos data ID
     * @param group Nacos group
     * @param initialReadTimeout maximum wait passed to the initial Nacos read
     * @param loader parses, validates, and optionally resolves secrets into a complete configuration
     * @param <C> immutable typed configuration
     * @return initialized configuration source
     * @throws NacosException when Nacos cannot read or register the listener
     * @throws IllegalStateException when initial content cannot produce a complete configuration
     */
    public static <C> Nacos3ConfigurationSource<C> open(
            ConfigService configService,
            String dataId,
            String group,
            Duration initialReadTimeout,
            Loader<C> loader) throws NacosException {
        ConfigService service = Objects.requireNonNull(configService, "configService");
        String checkedDataId = requireText(dataId, "dataId");
        String checkedGroup = requireText(group, "group");
        long timeoutMillis = requirePositiveMillis(initialReadTimeout, "initialReadTimeout");
        Loader<C> checkedLoader = Objects.requireNonNull(loader, "loader");
        Nacos3ConfigurationSource<C> source = new Nacos3ConfigurationSource<>(
                service, checkedDataId, checkedGroup, checkedLoader);
        boolean initialized = false;
        try {
            String initialContent = service.getConfigAndSignListener(
                    checkedDataId,
                    checkedGroup,
                    timeoutMillis,
                    source.nativeListener);
            source.initialize(initialContent);
            initialized = true;
            return source;
        } finally {
            if (!initialized) {
                source.close();
            }
        }
    }

    @Override
    public ConfigurationSnapshot<C> snapshot() {
        if (loadFailed) {
            throw unavailable();
        }
        return Objects.requireNonNull(current, "configuration source is not initialized");
    }

    @Override
    public ConfigurationSourceStatus status() {
        synchronized (monitor) {
            return ConfigurationSourceStatus.of(
                    closed
                            ? ConfigurationSourceStatus.State.CLOSED
                            : loadFailed
                                    ? ConfigurationSourceStatus.State.UNAVAILABLE
                                    : ConfigurationSourceStatus.State.AVAILABLE,
                    current == null ? 0 : current.revision(),
                    failures,
                    recoveries,
                    lastFailureStage);
        }
    }

    @Override
    public Subscription subscribe(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("The Nacos configuration source is closed");
            }
            ListenerSubscription subscription = new ListenerSubscription(listener);
            subscriptions.add(subscription);
            return subscription;
        }
    }

    /** Removes the Nacos listener and prevents future source notifications. */
    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            pending = false;
            pendingContent = null;
            monitor.notifyAll();
        }
        for (ListenerSubscription subscription : subscriptions) {
            subscription.close();
        }
        try {
            configService.removeListener(dataId, group, nativeListener);
        } finally {
            loaderExecutor.shutdown();
        }
    }

    /** Deliberately omits Nacos identifiers, raw content, and typed configuration. */
    @Override
    public String toString() {
        ConfigurationSourceStatus status = status();
        return "Nacos3ConfigurationSource[revision=" + status.revision()
                + ", available=" + (status.state() == ConfigurationSourceStatus.State.AVAILABLE)
                + ", closed=" + (status.state() == ConfigurationSourceStatus.State.CLOSED) + ']';
    }

    private void initialize(String content) {
        Loaded<C> loaded = load(content);
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("The Nacos configuration source is closed");
            }
            current = ConfigurationSnapshot.of(1, loaded.configuration());
            appliedDigest = loaded.digest();
            initialized = true;
            scheduleWorkerIfNeeded();
        }
    }

    private void offer(String content) {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            pendingContent = content;
            pending = true;
            if (initialized) {
                scheduleWorkerIfNeeded();
            }
        }
    }

    private void scheduleWorkerIfNeeded() {
        if (workerScheduled || !pending || closed) {
            return;
        }
        workerScheduled = true;
        try {
            loaderExecutor.execute(this::drain);
        } catch (RuntimeException failure) {
            workerScheduled = false;
            failures++;
            lastFailureStage = ConfigurationSourceStatus.FailureStage.WATCH;
            loadFailed = true;
            monitor.notifyAll();
            signalSubscribers();
        }
    }

    private void drain() {
        try {
            while (true) {
                String content;
                synchronized (monitor) {
                    if (closed || !pending) {
                        return;
                    }
                    content = pendingContent;
                    pendingContent = null;
                    pending = false;
                }
                apply(content);
            }
        } catch (Error failure) {
            recordLoadFailure(ConfigurationSourceStatus.FailureStage.LOAD);
            throw failure;
        } finally {
            synchronized (monitor) {
                workerScheduled = false;
                scheduleWorkerIfNeeded();
                monitor.notifyAll();
            }
        }
    }

    private void apply(String content) {
        byte[] digest;
        try {
            digest = digest(requireContent(content));
        } catch (RuntimeException failure) {
            recordLoadFailure(ConfigurationSourceStatus.FailureStage.READ);
            return;
        }

        synchronized (monitor) {
            if (closed) {
                return;
            }
            if (Arrays.equals(appliedDigest, digest)) {
                if (loadFailed) {
                    loadFailed = false;
                    recoveries++;
                    signalSubscribers();
                }
                return;
            }
        }

        Loaded<C> loaded;
        try {
            loaded = load(content);
        } catch (SourceFailure failure) {
            recordLoadFailure(failure.stage());
            return;
        }

        synchronized (monitor) {
            if (closed) {
                return;
            }
            long revision = Math.incrementExact(current.revision());
            current = ConfigurationSnapshot.of(revision, loaded.configuration());
            appliedDigest = loaded.digest();
            if (loadFailed) {
                recoveries++;
            }
            loadFailed = false;
        }
        signalSubscribers();
    }

    private Loaded<C> load(String content) {
        String checkedContent;
        try {
            checkedContent = requireContent(content);
        } catch (RuntimeException failure) {
            throw sourceFailure(ConfigurationSourceStatus.FailureStage.READ);
        }
        try {
            C configuration = Objects.requireNonNull(
                    loader.load(checkedContent),
                    "loader returned null");
            return new Loaded<>(configuration, digest(checkedContent));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw sourceFailure(ConfigurationSourceStatus.FailureStage.LOAD);
        } catch (Exception failure) {
            throw sourceFailure(ConfigurationSourceStatus.FailureStage.LOAD);
        }
    }

    private void recordLoadFailure(ConfigurationSourceStatus.FailureStage stage) {
        boolean notify;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            notify = !loadFailed;
            loadFailed = true;
            failures++;
            lastFailureStage = Objects.requireNonNull(stage, "stage");
        }
        if (notify) {
            signalSubscribers();
        }
    }

    private void signalSubscribers() {
        for (ListenerSubscription subscription : subscriptions) {
            try {
                subscription.signal();
            } catch (RuntimeException ignored) {
                // One subscriber cannot prevent later subscribers or configuration updates.
            }
        }
    }

    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long remaining = Objects.requireNonNull(timeout, "timeout").toNanos();
        long deadline = System.nanoTime() + remaining;
        synchronized (monitor) {
            while ((workerScheduled || pending) && remaining > 0) {
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                remaining = deadline - System.nanoTime();
            }
            return !workerScheduled && !pending;
        }
    }

    private static String requireContent(String content) {
        if (content == null) {
            throw unavailable();
        }
        return content;
    }

    private static byte[] digest(String content) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
    }

    private static String requireText(String value, String field) {
        String checked = Objects.requireNonNull(value, field).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return checked;
    }

    private static long requirePositiveMillis(Duration value, String field) {
        Duration duration = Objects.requireNonNull(value, field);
        long millis;
        try {
            millis = duration.toMillis();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + " is too large", failure);
        }
        if (millis < 1) {
            throw new IllegalArgumentException(field + " must be at least 1 millisecond");
        }
        return millis;
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("No complete Nacos configuration is currently available");
    }

    private static SourceFailure sourceFailure(ConfigurationSourceStatus.FailureStage stage) {
        return new SourceFailure(stage);
    }

    /** Loads one complete typed configuration from raw Nacos content. */
    @FunctionalInterface
    public interface Loader<C> {
        /** Parses, validates, and resolves all required values before returning. */
        C load(String content) throws Exception;
    }

    private record Loaded<C>(C configuration, byte[] digest) {
    }

    private static final class SourceFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final ConfigurationSourceStatus.FailureStage stage;

        private SourceFailure(ConfigurationSourceStatus.FailureStage stage) {
            super("No complete Nacos configuration is currently available");
            this.stage = Objects.requireNonNull(stage, "stage");
        }

        private ConfigurationSourceStatus.FailureStage stage() {
            return stage;
        }
    }

    private final class ListenerSubscription implements Subscription {

        private final Runnable listener;
        private boolean subscriptionClosed;

        private ListenerSubscription(Runnable listener) {
            this.listener = listener;
        }

        private synchronized void signal() {
            if (!subscriptionClosed) {
                listener.run();
            }
        }

        @Override
        public synchronized void close() {
            if (!subscriptionClosed) {
                subscriptionClosed = true;
                subscriptions.remove(this);
            }
        }
    }
}
