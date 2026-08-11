package yunqi.zhibei.steward.telemetry.log.slf4j;

import yunqi.zhibei.steward.telemetry.LifecycleEvent;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import yunqi.zhibei.steward.telemetry.LifecycleEventDelivery;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/**
 * Asynchronously writes secret-free lifecycle facts through an application-supplied SLF4J logger.
 *
 * <p>The adapter has exclusive draining ownership of one enabled buffer. It uses a fixed template,
 * never passes a throwable, and never invokes the logger on a lifecycle thread.
 */
public final class Slf4jLifecycleAdapter implements AutoCloseable, LifecycleEventDelivery {

    private static final String TEMPLATE = "middleware.lifecycle owner={} sequence={} stage={} "
            + "outcome={} generation={} revision={} startedAtEpochMilli={} durationNanos={} "
            + "failureType={}";
    private static final int DEFAULT_BATCH_SIZE = 64;
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(50);
    private static final Pattern SAFE_OWNER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final LifecycleEventBuffer events;
    private final String ownerName;
    private final Logger logger;
    private final int batchSize;
    private final long pollIntervalNanos;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong drainedEvents = new AtomicLong();
    private final AtomicLong loggedEvents = new AtomicLong();
    private final AtomicLong logFailures = new AtomicLong();
    private final Thread worker;

    private Slf4jLifecycleAdapter(
            LifecycleEventBuffer events,
            String ownerName,
            Logger logger,
            Duration pollInterval,
            int batchSize) {
        this.events = requireEnabled(events);
        this.ownerName = requireOwnerName(ownerName);
        this.logger = Objects.requireNonNull(logger, "logger");
        pollIntervalNanos = requirePositive(pollInterval);
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.batchSize = batchSize;
        worker = Thread.ofPlatform()
                .daemon(true)
                .name("middleware-slf4j-" + ownerName)
                .unstarted(this::run);
        worker.start();
    }

    /** Starts an adapter with a 64-event batch and a 50 millisecond idle poll interval. */
    public static Slf4jLifecycleAdapter start(
            LifecycleEventBuffer events,
            String ownerName,
            Logger logger) {
        return new Slf4jLifecycleAdapter(
                events, ownerName, logger, DEFAULT_POLL_INTERVAL, DEFAULT_BATCH_SIZE);
    }

    /** Starts an adapter with explicit, positive polling and batch bounds. */
    public static Slf4jLifecycleAdapter start(
            LifecycleEventBuffer events,
            String ownerName,
            Logger logger,
            Duration pollInterval,
            int batchSize) {
        return new Slf4jLifecycleAdapter(
                events, ownerName, logger, pollInterval, batchSize);
    }

    /** Returns events removed from the source buffer, including log failures. */
    public long drainedEvents() {
        return drainedEvents.get();
    }

    /** Returns lifecycle events accepted by the logger call without an adapter exception. */
    public long loggedEvents() {
        return loggedEvents.get();
    }

    @Override
    public long successfulEvents() {
        return loggedEvents();
    }

    /** Returns adapter-side exceptions raised by logger calls. */
    public long logFailures() {
        return logFailures.get();
    }

    @Override
    public long failedEvents() {
        return logFailures();
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
                log(event);
                loggedEvents.incrementAndGet();
            } catch (RuntimeException failure) {
                logFailures.incrementAndGet();
            }
        }
        return batch.size();
    }

    private void log(LifecycleEvent event) {
        Object[] arguments = {
                ownerName,
                event.sequence(),
                event.stage().name(),
                event.outcome().name(),
                event.generation(),
                event.revision(),
                event.startedAt().toEpochMilli(),
                event.duration().toNanos(),
                event.failureType().orElse("-")
        };
        if (event.outcome() == LifecycleEvent.Outcome.FAILURE) {
            logger.warn(TEMPLATE, arguments);
        } else {
            logger.info(TEMPLATE, arguments);
        }
    }

    /** Seals the source buffer and waits for every accepted event to be drained or accounted. */
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
