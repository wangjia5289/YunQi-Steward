package yunqi.zhibei.steward.adapter.observability.opentelemetry;

import yunqi.zhibei.steward.observation.LifecycleEvent;
import yunqi.zhibei.steward.observation.LifecycleEventBuffer;
import yunqi.zhibei.steward.observation.LifecycleEventDelivery;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/**
 * Asynchronously converts lifecycle facts into independent OpenTelemetry spans.
 *
 * <p>The adapter has exclusive draining ownership of one enabled buffer. Spans have no parent
 * because the neutral event deliberately carries no request context. The adapter records no
 * throwable or exception message and never runs tracer or exporter code on a lifecycle thread.
 */
public final class OpenTelemetryLifecycleAdapter implements AutoCloseable, LifecycleEventDelivery {

    private static final int DEFAULT_BATCH_SIZE = 64;
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(50);
    private static final Pattern SAFE_OWNER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final AttributeKey<String> OWNER = AttributeKey.stringKey("middleware.owner");
    private static final AttributeKey<Long> SEQUENCE = AttributeKey.longKey("middleware.sequence");
    private static final AttributeKey<String> STAGE = AttributeKey.stringKey("middleware.stage");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("middleware.outcome");
    private static final AttributeKey<Long> GENERATION = AttributeKey.longKey("middleware.generation");
    private static final AttributeKey<Long> REVISION = AttributeKey.longKey("middleware.revision");
    private static final AttributeKey<String> FAILURE_TYPE =
            AttributeKey.stringKey("middleware.failure.type");

    private final LifecycleEventBuffer events;
    private final String ownerName;
    private final Tracer tracer;
    private final int batchSize;
    private final long pollIntervalNanos;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong drainedEvents = new AtomicLong();
    private final AtomicLong endedSpans = new AtomicLong();
    private final AtomicLong spanFailures = new AtomicLong();
    private final Thread worker;

    private OpenTelemetryLifecycleAdapter(
            LifecycleEventBuffer events,
            String ownerName,
            Tracer tracer,
            Duration pollInterval,
            int batchSize) {
        this.events = requireEnabled(events);
        this.ownerName = requireOwnerName(ownerName);
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        pollIntervalNanos = requirePositive(pollInterval);
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.batchSize = batchSize;
        worker = Thread.ofPlatform()
                .daemon(true)
                .name("middleware-otel-" + ownerName)
                .unstarted(this::run);
        worker.start();
    }

    /** Starts an adapter with a 64-event batch and a 50 millisecond idle poll interval. */
    public static OpenTelemetryLifecycleAdapter start(
            LifecycleEventBuffer events,
            String ownerName,
            Tracer tracer) {
        return new OpenTelemetryLifecycleAdapter(
                events, ownerName, tracer, DEFAULT_POLL_INTERVAL, DEFAULT_BATCH_SIZE);
    }

    /** Starts an adapter with explicit, positive polling and batch bounds. */
    public static OpenTelemetryLifecycleAdapter start(
            LifecycleEventBuffer events,
            String ownerName,
            Tracer tracer,
            Duration pollInterval,
            int batchSize) {
        return new OpenTelemetryLifecycleAdapter(
                events, ownerName, tracer, pollInterval, batchSize);
    }

    /** Returns events removed from the source buffer, including span failures. */
    public long drainedEvents() {
        return drainedEvents.get();
    }

    /** Returns lifecycle spans ended without an adapter-side exception. */
    public long endedSpans() {
        return endedSpans.get();
    }

    @Override
    public long successfulEvents() {
        return endedSpans();
    }

    /** Returns adapter-side exceptions raised while constructing or ending spans. */
    public long spanFailures() {
        return spanFailures.get();
    }

    @Override
    public long failedEvents() {
        return spanFailures();
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
                endSpan(event);
                endedSpans.incrementAndGet();
            } catch (RuntimeException failure) {
                spanFailures.incrementAndGet();
            }
        }
        return batch.size();
    }

    private void endSpan(LifecycleEvent event) {
        long startNanos = toEpochNanos(event.startedAt());
        long endNanos = Math.addExact(startNanos, event.duration().toNanos());
        Span span = tracer.spanBuilder("middleware.lifecycle."
                        + event.stage().name().toLowerCase(Locale.ROOT))
                .setNoParent()
                .setSpanKind(SpanKind.INTERNAL)
                .setStartTimestamp(startNanos, TimeUnit.NANOSECONDS)
                .setAttribute(OWNER, ownerName)
                .setAttribute(SEQUENCE, event.sequence())
                .setAttribute(STAGE, event.stage().name())
                .setAttribute(OUTCOME, event.outcome().name())
                .setAttribute(GENERATION, event.generation())
                .setAttribute(REVISION, event.revision())
                .startSpan();
        try {
            event.failureType().ifPresent(type -> span.setAttribute(FAILURE_TYPE, type));
            if (event.outcome() == LifecycleEvent.Outcome.FAILURE) {
                span.setStatus(StatusCode.ERROR);
            }
        } finally {
            span.end(endNanos, TimeUnit.NANOSECONDS);
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

    private static long toEpochNanos(Instant instant) {
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), TimeUnit.SECONDS.toNanos(1)),
                instant.getNano());
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
