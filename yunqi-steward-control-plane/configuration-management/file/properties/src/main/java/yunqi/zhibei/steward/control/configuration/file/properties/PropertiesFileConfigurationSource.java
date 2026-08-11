package yunqi.zhibei.steward.control.configuration.file.properties;

import yunqi.zhibei.steward.control.configuration.ConfigurationSnapshot;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import yunqi.zhibei.steward.control.configuration.ConfigurationSourceStatus;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Supplies complete typed snapshots from one local Java properties file.
 *
 * <p>The source owns only its file watcher. It does not own the resulting configuration, a
 * resource created from that configuration, or any secret provider used by the loader. The loader
 * must validate all related fields and resolve secrets before returning one complete immutable
 * application configuration.
 *
 * <p>File notifications are a hint to reload the file and may be coalesced. Applications which
 * use an external watcher can call {@link #refresh()} directly and use this source without relying
 * on the built-in watcher.
 *
 * @param <C> immutable complete configuration type
 */
public final class PropertiesFileConfigurationSource<C>
        implements ConfigurationSource<C>, AutoCloseable {

    private final Path file;
    private final Loader<C> loader;
    private final WatchService watchService;
    private final ExecutorService watcherExecutor;
    private final ExecutorService loaderExecutor;
    private final CopyOnWriteArrayList<ListenerSubscription> subscriptions =
            new CopyOnWriteArrayList<>();
    private final Object monitor = new Object();
    private final WatchKey watchKey;

    private volatile ConfigurationSnapshot<C> current;
    private byte[] appliedDigest;
    private volatile boolean available = true;
    private long failures;
    private long recoveries;
    private ConfigurationSourceStatus.FailureStage lastFailureStage =
            ConfigurationSourceStatus.FailureStage.NONE;
    private boolean refreshPending;
    private boolean workerScheduled;
    private boolean closed;

    private PropertiesFileConfigurationSource(
            Path file,
            Loader<C> loader,
            WatchService watchService,
            WatchKey watchKey) {
        this.file = file;
        this.loader = loader;
        this.watchService = watchService;
        this.watchKey = watchKey;
        watcherExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("properties-configuration-watcher-", 0).factory());
        loaderExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("properties-configuration-loader-", 0).factory());
    }

    /**
     * Opens and watches one properties file after loading its initial complete configuration.
     *
     * @param file properties file to read and watch
     * @param loader converts one properties snapshot into a complete typed configuration
     * @param <C> immutable complete configuration type
     * @return an active configuration source
     * @throws IOException when the file's parent directory cannot be watched
     * @throws IllegalStateException when the initial file cannot be read or cannot produce a
     * complete configuration
     */
    public static <C> PropertiesFileConfigurationSource<C> open(
            Path file,
            Loader<C> loader) throws IOException {
        Path checkedFile = normalizeFile(file);
        Loader<C> checkedLoader = Objects.requireNonNull(loader, "loader");
        WatchService watchService = FileSystems.getDefault().newWatchService();
        WatchKey watchKey = null;
        PropertiesFileConfigurationSource<C> source = null;
        boolean initialized = false;
        try {
            Path parent = checkedFile.getParent();
            watchKey = parent.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            source = new PropertiesFileConfigurationSource<>(
                    checkedFile, checkedLoader, watchService, watchKey);
            source.reloadInitial();
            source.startWatcher();
            initialized = true;
            return source;
        } finally {
            if (!initialized) {
                if (source != null) {
                    source.close();
                } else {
                    if (watchKey != null) {
                        watchKey.cancel();
                    }
                    watchService.close();
                }
            }
        }
    }

    @Override
    public ConfigurationSnapshot<C> snapshot() {
        synchronized (monitor) {
            if (!available) {
                throw unavailable();
            }
            return Objects.requireNonNull(current, "configuration source is not initialized");
        }
    }

    @Override
    public ConfigurationSourceStatus status() {
        synchronized (monitor) {
            return ConfigurationSourceStatus.of(
                    closed
                            ? ConfigurationSourceStatus.State.CLOSED
                            : available
                                    ? ConfigurationSourceStatus.State.AVAILABLE
                                    : ConfigurationSourceStatus.State.UNAVAILABLE,
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
                throw new IllegalStateException("The properties configuration source is closed");
            }
            ListenerSubscription subscription = new ListenerSubscription(listener);
            subscriptions.add(subscription);
            return subscription;
        }
    }

    /**
     * Requests a reload on the source worker. The call returns after the request is accepted, not
     * after the new snapshot is published. Duplicate content is ignored.
     */
    public void refresh() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            refreshPending = true;
            scheduleWorkerIfNeeded();
        }
    }

    /** Stops watching the file and prevents future callbacks. This operation is idempotent. */
    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            refreshPending = false;
            monitor.notifyAll();
        }
        for (ListenerSubscription subscription : subscriptions) {
            subscription.close();
        }
        watchKey.cancel();
        try {
            watchService.close();
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to close the properties watch service", failure);
        } finally {
            watcherExecutor.shutdownNow();
            loaderExecutor.shutdownNow();
        }
    }

    /** Deliberately omits the file path, typed configuration, and all property values. */
    @Override
    public String toString() {
        ConfigurationSourceStatus status = status();
        return "PropertiesFileConfigurationSource[revision=" + status.revision()
                + ", available=" + (status.state() == ConfigurationSourceStatus.State.AVAILABLE)
                + ", closed=" + (status.state() == ConfigurationSourceStatus.State.CLOSED) + ']';
    }

    private void reloadInitial() {
        Loaded<C> loaded = load();
        synchronized (monitor) {
            current = ConfigurationSnapshot.of(1, loaded.configuration());
            appliedDigest = loaded.digest();
        }
    }

    private void startWatcher() {
        watcherExecutor.execute(this::watch);
    }

    private void watch() {
        try {
            while (true) {
                WatchKey key = watchService.take();
                boolean changed = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    changed = true;
                }
                if (!key.reset()) {
                    recordUnavailable(ConfigurationSourceStatus.FailureStage.WATCH);
                    return;
                }
                if (changed) {
                    refresh();
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException ignored) {
            // Normal source shutdown.
        } catch (RuntimeException failure) {
            recordUnavailable(ConfigurationSourceStatus.FailureStage.WATCH);
        }
    }

    private void scheduleWorkerIfNeeded() {
        if (workerScheduled || !refreshPending || closed) {
            return;
        }
        workerScheduled = true;
        try {
            loaderExecutor.execute(this::drainRefreshes);
        } catch (RuntimeException failure) {
            workerScheduled = false;
            failures++;
            lastFailureStage = ConfigurationSourceStatus.FailureStage.WATCH;
            available = false;
            monitor.notifyAll();
            signalSubscribers();
        }
    }

    private void drainRefreshes() {
        try {
            while (true) {
                synchronized (monitor) {
                    if (closed || !refreshPending) {
                        return;
                    }
                    refreshPending = false;
                }
                reload();
            }
        } catch (Error failure) {
            recordUnavailable(ConfigurationSourceStatus.FailureStage.LOAD);
            throw failure;
        } finally {
            synchronized (monitor) {
                workerScheduled = false;
                scheduleWorkerIfNeeded();
                monitor.notifyAll();
            }
        }
    }

    private void reload() {
        try {
            apply(load());
        } catch (SourceFailure failure) {
            recordUnavailable(failure.stage());
        }
    }

    private void recordUnavailable(ConfigurationSourceStatus.FailureStage stage) {
        boolean notify;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            notify = available;
            available = false;
            failures++;
            lastFailureStage = Objects.requireNonNull(stage, "stage");
        }
        if (notify) {
            signalSubscribers();
        }
    }

    private void apply(Loaded<C> loaded) {
        boolean notify;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            boolean recovering = !available;
            if (Arrays.equals(appliedDigest, loaded.digest())) {
                notify = recovering;
                available = true;
            } else {
                current = ConfigurationSnapshot.of(
                        Math.incrementExact(current.revision()), loaded.configuration());
                appliedDigest = loaded.digest();
                available = true;
                notify = true;
            }
            if (recovering) {
                recoveries++;
            }
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
                // One subscriber cannot prevent later subscribers or future reloads.
            }
        }
    }

    private Loaded<C> load() {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            properties.load(reader);
        } catch (Exception failure) {
            throw sourceFailure(ConfigurationSourceStatus.FailureStage.READ);
        }
        byte[] contentDigest = digest(properties);
        try {
            C configuration = Objects.requireNonNull(loader.load(properties), "loader returned null");
            return new Loaded<>(configuration, contentDigest);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw sourceFailure(ConfigurationSourceStatus.FailureStage.LOAD);
        } catch (Exception failure) {
            throw sourceFailure(ConfigurationSourceStatus.FailureStage.LOAD);
        }
    }

    private static byte[] digest(Properties properties) {
        try {
            StringBuilder canonical = new StringBuilder();
            properties.stringPropertyNames().stream().sorted().forEach(name -> {
                String value = properties.getProperty(name);
                canonical.append(name.length()).append(':').append(name)
                        .append(value.length()).append(':').append(value);
            });
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
    }

    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long remaining = Objects.requireNonNull(timeout, "timeout").toNanos();
        long deadline = System.nanoTime() + remaining;
        synchronized (monitor) {
            while ((workerScheduled || refreshPending) && remaining > 0) {
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                remaining = deadline - System.nanoTime();
            }
            return !workerScheduled && !refreshPending;
        }
    }

    private static Path normalizeFile(Path file) {
        Path checked = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (checked.getFileName() == null || checked.getParent() == null) {
            throw new IllegalArgumentException("file must name a file below a directory");
        }
        return checked;
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException(
                "No complete properties configuration is currently available");
    }

    private static SourceFailure sourceFailure(ConfigurationSourceStatus.FailureStage stage) {
        return new SourceFailure(stage);
    }

    /** Converts one properties snapshot into a complete immutable typed configuration. */
    @FunctionalInterface
    public interface Loader<C> {
        /** Validates related values and resolves required secrets before returning. */
        C load(Properties properties) throws Exception;
    }

    private record Loaded<C>(C configuration, byte[] digest) {
    }

    private static final class SourceFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final ConfigurationSourceStatus.FailureStage stage;

        private SourceFailure(ConfigurationSourceStatus.FailureStage stage) {
            super("No complete properties configuration is currently available");
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
