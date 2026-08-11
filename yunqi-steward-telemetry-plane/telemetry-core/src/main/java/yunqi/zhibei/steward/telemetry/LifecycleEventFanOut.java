package yunqi.zhibei.steward.telemetry;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/**
 * Asynchronously distributes one lifecycle event stream to independent bounded branch buffers.
 *
 * <p>A lifecycle owner publishes only to {@link #source()}. One daemon thread drains that source
 * and attempts a non-waiting write to every branch. A full, contended, or prematurely closed branch
 * loses only its own copy and cannot delay another branch or lifecycle work. Branches preserve the
 * source event sequence, so a branch loss remains visible as a sequence gap.
 *
 * <p>Each event adapter has exclusive draining ownership of its branch. Close lifecycle owners
 * first, this fan-out second, and adapters last. Closing the fan-out seals and drains the source,
 * distributes every retained source event, then seals all branches for adapter final draining.
 */
public final class LifecycleEventFanOut implements AutoCloseable {

    private static final int DEFAULT_BATCH_SIZE = 64;
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(10);
    private static final Pattern SAFE_BRANCH_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final LifecycleEventBuffer source;
    private final Map<String, Branch> branches;
    private final int batchSize;
    private final long pollIntervalNanos;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong drainedEvents = new AtomicLong();
    private final Thread worker;

    private LifecycleEventFanOut(
            int sourceCapacity,
            Map<String, Integer> branchCapacities,
            Duration pollInterval,
            int batchSize) {
        source = LifecycleEventBuffer.create(sourceCapacity);
        branches = createBranches(branchCapacities);
        pollIntervalNanos = requirePositive(pollInterval);
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.batchSize = batchSize;
        worker = Thread.ofPlatform()
                .daemon(true)
                .name("middleware-lifecycle-fanout")
                .unstarted(this::run);
        worker.start();
    }

    /** Starts fan-out with a 64-event batch and a 10 millisecond idle poll interval. */
    public static LifecycleEventFanOut start(
            int sourceCapacity,
            Map<String, Integer> branchCapacities) {
        return new LifecycleEventFanOut(
                sourceCapacity, branchCapacities, DEFAULT_POLL_INTERVAL, DEFAULT_BATCH_SIZE);
    }

    /** Starts fan-out with explicit, positive polling and batch bounds. */
    public static LifecycleEventFanOut start(
            int sourceCapacity,
            Map<String, Integer> branchCapacities,
            Duration pollInterval,
            int batchSize) {
        return new LifecycleEventFanOut(
                sourceCapacity, branchCapacities, pollInterval, batchSize);
    }

    /** Returns the only buffer that lifecycle owners should publish to. */
    public LifecycleEventBuffer source() {
        return source;
    }

    /** Returns the exclusively drained input buffer for one configured adapter branch. */
    public LifecycleEventBuffer branch(String branchName) {
        return requireBranch(branchName).events;
    }

    /** Returns the immutable set of configured branch identifiers. */
    public Set<String> branchNames() {
        return branches.keySet();
    }

    /** Returns source events removed for distribution. */
    public long drainedEvents() {
        return drainedEvents.get();
    }

    /** Returns copies accepted by one branch. */
    public long deliveredEvents(String branchName) {
        return requireBranch(branchName).deliveredEvents.get();
    }

    /** Returns copies rejected by one full, contended, or prematurely closed branch. */
    public long droppedEvents(String branchName) {
        return requireBranch(branchName).droppedEvents.get();
    }

    /** Returns events rejected before entering the source buffer. */
    public long sourceDroppedEvents() {
        return source.droppedEvents();
    }

    public boolean isClosed() {
        return closing.get() && !worker.isAlive();
    }

    private void run() {
        try {
            while (!closing.get()) {
                if (distributeBatch() == 0) {
                    LockSupport.parkNanos(this, pollIntervalNanos);
                }
            }
            while (distributeBatch() != 0) {
                // Distribute every source event accepted before close sealed publication.
            }
        } finally {
            for (Branch branch : branches.values()) {
                branch.events.close();
            }
        }
    }

    private int distributeBatch() {
        var batch = source.drain(batchSize);
        for (LifecycleEvent event : batch) {
            drainedEvents.incrementAndGet();
            for (Branch branch : branches.values()) {
                if (branch.events.publishRetained(event)) {
                    branch.deliveredEvents.incrementAndGet();
                } else {
                    branch.droppedEvents.incrementAndGet();
                }
            }
        }
        return batch.size();
    }

    /** Seals and drains the source, seals every branch, and waits for distribution to finish. */
    @Override
    public void close() {
        if (closing.compareAndSet(false, true)) {
            source.close();
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

    private Branch requireBranch(String branchName) {
        Objects.requireNonNull(branchName, "branchName");
        Branch branch = branches.get(branchName);
        if (branch == null) {
            throw new IllegalArgumentException("unknown branchName");
        }
        return branch;
    }

    private static Map<String, Branch> createBranches(Map<String, Integer> branchCapacities) {
        Objects.requireNonNull(branchCapacities, "branchCapacities");
        if (branchCapacities.isEmpty()) {
            throw new IllegalArgumentException("at least one branch is required");
        }
        Map<String, Branch> created = new LinkedHashMap<>();
        branchCapacities.forEach((name, capacity) -> {
            requireBranchName(name);
            Objects.requireNonNull(capacity, "branch capacity");
            if (capacity < 1) {
                throw new IllegalArgumentException("branch capacity must be at least 1");
            }
            created.put(name, new Branch(LifecycleEventBuffer.create(capacity)));
        });
        return Collections.unmodifiableMap(created);
    }

    private static void requireBranchName(String branchName) {
        Objects.requireNonNull(branchName, "branchName");
        if (!SAFE_BRANCH_NAME.matcher(branchName).matches()) {
            throw new IllegalArgumentException(
                    "branchName must be 1-128 ASCII letters, digits, dots, underscores, or hyphens");
        }
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

    private static final class Branch {
        private final LifecycleEventBuffer events;
        private final AtomicLong deliveredEvents = new AtomicLong();
        private final AtomicLong droppedEvents = new AtomicLong();

        private Branch(LifecycleEventBuffer events) {
            this.events = events;
        }
    }
}
