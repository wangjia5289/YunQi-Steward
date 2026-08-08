package yunqi.zhibei.steward.observation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded event channel whose lifecycle-thread publication path never waits.
 *
 * <p>Publication uses {@link ReentrantLock#tryLock()}. An event is dropped when the buffer is full
 * or momentarily contended, so a slow adapter cannot delay resource startup, replacement, or
 * closure. The buffer creates no thread; an optional adapter owns its own polling and dispatch.
 * Closing stops publication but preserves already accepted events for draining.
 */
public final class LifecycleEventBuffer implements AutoCloseable {

    private static final LifecycleEventBuffer NOOP = new LifecycleEventBuffer();

    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<LifecycleEvent> events;
    private final int capacity;
    private final AtomicLong droppedEvents = new AtomicLong();
    private long nextSequence = 1;
    private volatile boolean closed;
    private final boolean enabled;

    private LifecycleEventBuffer() {
        events = new ArrayDeque<>(0);
        capacity = 0;
        enabled = false;
        closed = true;
    }

    private LifecycleEventBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
        events = new ArrayDeque<>(capacity);
        enabled = true;
    }

    /** Returns the shared disabled channel. It allocates no queue and accepts no events. */
    public static LifecycleEventBuffer noop() {
        return NOOP;
    }

    /** Creates an enabled channel with the exact maximum number of retained events. */
    public static LifecycleEventBuffer create(int capacity) {
        return new LifecycleEventBuffer(capacity);
    }

    /** Returns whether lifecycle owners should capture timestamps and emit events. */
    public boolean isEnabled() {
        return enabled && !closed;
    }

    /**
     * Attempts to publish one already-redacted fact without waiting.
     *
     * @return true when accepted; false when disabled, closed, contended, or full
     */
    public boolean publish(
            LifecycleEvent.Stage stage,
            LifecycleEvent.Outcome outcome,
            long generation,
            long revision,
            Instant startedAt,
            Duration duration,
            String failureType) {
        if (!isEnabled()) {
            return false;
        }
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(duration, "duration");
        if (!lock.tryLock()) {
            droppedEvents.incrementAndGet();
            return false;
        }
        try {
            if (closed) {
                return false;
            }
            if (events.size() == capacity) {
                droppedEvents.incrementAndGet();
                return false;
            }
            events.addLast(new LifecycleEvent(
                    nextSequence++, stage, outcome, generation, revision,
                    startedAt, duration, failureType));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Offers an immutable event retained by another buffer while preserving its source sequence. */
    boolean publishRetained(LifecycleEvent event) {
        Objects.requireNonNull(event, "event");
        if (!isEnabled()) {
            return false;
        }
        if (!lock.tryLock()) {
            droppedEvents.incrementAndGet();
            return false;
        }
        try {
            if (closed) {
                return false;
            }
            if (events.size() == capacity) {
                droppedEvents.incrementAndGet();
                return false;
            }
            events.addLast(event);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Returns and removes the oldest accepted event, or {@code null} when empty. */
    public LifecycleEvent poll() {
        if (!enabled) {
            return null;
        }
        lock.lock();
        try {
            return events.pollFirst();
        } finally {
            lock.unlock();
        }
    }

    /** Removes and returns up to {@code maximum} events in sequence order. */
    public List<LifecycleEvent> drain(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be at least 1");
        }
        if (!enabled) {
            return List.of();
        }
        lock.lock();
        try {
            int count = Math.min(maximum, events.size());
            List<LifecycleEvent> drained = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                drained.add(events.removeFirst());
            }
            return List.copyOf(drained);
        } finally {
            lock.unlock();
        }
    }

    /** Returns a current queue size snapshot. */
    public int size() {
        if (!enabled) {
            return 0;
        }
        lock.lock();
        try {
            return events.size();
        } finally {
            lock.unlock();
        }
    }

    public int capacity() { return capacity; }
    public long droppedEvents() { return droppedEvents.get(); }
    public boolean isClosed() { return closed; }

    /** Stops future publication without discarding events already accepted. */
    @Override
    public void close() {
        if (!enabled) {
            return;
        }
        lock.lock();
        try {
            closed = true;
        } finally {
            lock.unlock();
        }
    }
}
