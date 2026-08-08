package yunqi.zhibei.steward.adapter.observability.jfr;

import yunqi.zhibei.steward.observation.LifecycleEvent;
import yunqi.zhibei.steward.observation.LifecycleEventBuffer;
import yunqi.zhibei.steward.observation.LifecycleEventDelivery;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/**
 * Asynchronously drains lifecycle facts into JDK Flight Recorder correlation events.
 *
 * <p>One adapter has exclusive ownership of one enabled buffer. Closing the adapter first closes
 * that buffer, then waits for a final drain. Lifecycle publishers never execute JFR code and never
 * wait for this adapter; publication contention is handled by the buffer's drop policy.
 */
public final class JfrLifecycleAdapter implements AutoCloseable, LifecycleEventDelivery {

    private static final int DEFAULT_BATCH_SIZE = 64;
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(50);
    private static final Pattern SAFE_OWNER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final LifecycleEventBuffer events;
    private final String ownerName;
    private final int batchSize;
    private final long pollIntervalNanos;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong drainedEvents = new AtomicLong();
    private final AtomicLong forwardedEvents = new AtomicLong();
    private final AtomicLong commitFailures = new AtomicLong();
    private final Thread worker;

    private JfrLifecycleAdapter(
            LifecycleEventBuffer events,
            String ownerName,
            Duration pollInterval,
            int batchSize) {
        this.events = requireEnabled(events);
        this.ownerName = requireOwnerName(ownerName);
        this.pollIntervalNanos = requirePositive(pollInterval);
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.batchSize = batchSize;
        worker = Thread.ofPlatform()
                .daemon(true)
                .name("middleware-jfr-" + ownerName)
                .unstarted(this::run);
        worker.start();
    }

    /** Starts an adapter with a 64-event batch and a 50 millisecond idle poll interval. */
    public static JfrLifecycleAdapter start(
            LifecycleEventBuffer events,
            String ownerName) {
        return new JfrLifecycleAdapter(
                events, ownerName, DEFAULT_POLL_INTERVAL, DEFAULT_BATCH_SIZE);
    }

    /** Starts an adapter with explicit, positive polling and batch bounds. */
    public static JfrLifecycleAdapter start(
            LifecycleEventBuffer events,
            String ownerName,
            Duration pollInterval,
            int batchSize) {
        return new JfrLifecycleAdapter(events, ownerName, pollInterval, batchSize);
    }

    /** Returns events removed from the source buffer, including commit failures. */
    public long drainedEvents() {
        return drainedEvents.get();
    }

    /** Returns events passed to the JFR API without an adapter exception. */
    public long forwardedEvents() {
        return forwardedEvents.get();
    }

    @Override
    public long successfulEvents() {
        return forwardedEvents();
    }

    /** Returns adapter-side exceptions raised while creating or committing JFR events. */
    public long commitFailures() {
        return commitFailures.get();
    }

    @Override
    public long failedEvents() {
        return commitFailures();
    }

    /** Returns source events dropped on non-blocking publication before this adapter could drain. */
    public long sourceDroppedEvents() {
        return events.droppedEvents();
    }

    public boolean isClosed() {
        return closing.get() && !worker.isAlive();
    }

    private void run() {
        while (!closing.get()) {
            if (forwardBatch() == 0) {
                LockSupport.parkNanos(this, pollIntervalNanos);
            }
        }
        while (forwardBatch() != 0) {
            // Drain every event accepted before close sealed the source buffer.
        }
    }

    private int forwardBatch() {
        List<LifecycleEvent> batch = events.drain(batchSize);
        for (LifecycleEvent event : batch) {
            drainedEvents.incrementAndGet();
            try {
                forward(event);
                forwardedEvents.incrementAndGet();
            } catch (RuntimeException failure) {
                commitFailures.incrementAndGet();
            }
        }
        return batch.size();
    }

    private void forward(LifecycleEvent event) {
        JfrLifecycleEvent target = new JfrLifecycleEvent();
        target.owner = ownerName;
        target.sequence = event.sequence();
        target.stage = event.stage().name();
        target.outcome = event.outcome().name();
        target.generation = event.generation();
        target.revision = event.revision();
        target.lifecycleStartedAt = event.startedAt().toEpochMilli();
        target.lifecycleDuration = event.duration().toNanos();
        target.failureType = event.failureType().orElse(null);
        target.commit();
    }

    /**
     * Seals the source buffer and waits for the worker to account for every accepted event.
     * Interruption is restored after the final drain completes.
     */
    @Override
    public void close() {
        if (closing.compareAndSet(false, true)) {
            events.close();
            LockSupport.unpark(worker);
        }
        if (Thread.currentThread() == worker) {
            return;
        }
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static LifecycleEventBuffer requireEnabled(LifecycleEventBuffer events) {
        Objects.requireNonNull(events, "events");
        if (!events.isEnabled()) {
            throw new IllegalArgumentException("events must be enabled and open");
        }
        return events;
    }

    private static String requireOwnerName(String ownerName) {
        Objects.requireNonNull(ownerName, "ownerName");
        if (!SAFE_OWNER_NAME.matcher(ownerName).matches()) {
            throw new IllegalArgumentException(
                    "ownerName must be 1-128 ASCII letters, digits, dots, underscores, or hyphens");
        }
        return ownerName;
    }

    private static long requirePositive(Duration pollInterval) {
        Objects.requireNonNull(pollInterval, "pollInterval");
        long nanos;
        try {
            nanos = pollInterval.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("pollInterval is too large", failure);
        }
        if (nanos < 1) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        return nanos;
    }
}
