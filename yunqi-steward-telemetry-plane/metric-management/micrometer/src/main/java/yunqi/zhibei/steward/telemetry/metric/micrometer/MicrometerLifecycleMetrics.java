package yunqi.zhibei.steward.telemetry.metric.micrometer;

import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.telemetry.LifecycleEventBuffer;
import yunqi.zhibei.steward.telemetry.LifecycleEventDelivery;
import yunqi.zhibei.steward.telemetry.LifecycleEventFanOut;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResourceStatus;
import yunqi.zhibei.steward.control.resource.restart.RestartRequiredMonitor;
import yunqi.zhibei.steward.control.resource.restart.RestartRequiredStatus;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Registers a fixed, bounded set of Micrometer meters for lifecycle owners.
 *
 * <p>The adapter polls secret-free owner status when a registry is scraped. It weakly references
 * owners and never retains a configuration or native client. The supplied owner name is a stable,
 * non-sensitive metric identity, not an endpoint or arbitrary tag value.
 */
public final class MicrometerLifecycleMetrics {

    private static final String PREFIX = "middleware.lifecycle.";
    private static final Pattern SAFE_OWNER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private MicrometerLifecycleMetrics() {
    }

    /** Registers six meters for a startup-fixed owner. */
    public static Registration bind(
            MeterRegistry registry,
            String ownerName,
            BoundResource<?> owner,
            LifecycleEventBuffer events) {
        Objects.requireNonNull(registry, "registry");
        String safeName = requireOwnerName(ownerName);
        BoundView view = new BoundView(Objects.requireNonNull(owner, "owner"));
        BufferView buffer = new BufferView(Objects.requireNonNull(events, "events"));
        Tags tags = Tags.of("owner", safeName, "kind", "bound");
        List<Meter> meters = new ArrayList<>();

        for (BoundResource.State state : BoundResource.State.values()) {
            meters.add(Gauge.builder(PREFIX + "state", view,
                            value -> value.state() == state ? 1.0 : 0.0)
                    .tags(tags.and("state", state.name().toLowerCase(Locale.ROOT)))
                    .description("One-hot lifecycle owner state")
                    .register(registry));
        }
        registerBufferMeters(registry, tags, buffer, meters);
        return new Registration(registry, List.of(view, buffer), meters);
    }

    /** Registers source and per-branch health meters for one fan-out. */
    public static Registration bind(
            MeterRegistry registry,
            String ownerName,
            LifecycleEventFanOut fanOut) {
        Objects.requireNonNull(registry, "registry");
        String safeName = requireOwnerName(ownerName);
        Objects.requireNonNull(fanOut, "fanOut");
        Tags tags = Tags.of("owner", safeName, "kind", "fanout");
        ensureAvailable(registry, PREFIX + "pipeline.source.buffered", tags);
        FanOutView fanOutView = new FanOutView(fanOut);
        List<Object> views = new ArrayList<>();
        views.add(fanOutView);
        List<Meter> meters = new ArrayList<>();

        meters.add(gauge(registry, PREFIX + "pipeline.source.buffered", tags, fanOutView,
                FanOutView::sourceBuffered, "Lifecycle events currently buffered before fan-out"));
        meters.add(functionCounter(registry, PREFIX + "pipeline.source.dropped", tags, fanOutView,
                FanOutView::sourceDropped, "Lifecycle events dropped before fan-out admission"));
        meters.add(functionCounter(registry, PREFIX + "pipeline.source.drained", tags, fanOutView,
                FanOutView::drained, "Lifecycle source events drained for fan-out"));
        for (String branchName : fanOut.branchNames()) {
            BranchView branch = new BranchView(fanOut, branchName);
            views.add(branch);
            Tags branchTags = tags.and("branch", branchName);
            meters.add(gauge(registry, PREFIX + "pipeline.branch.buffered", branchTags, branch,
                    BranchView::buffered, "Lifecycle event copies currently buffered for a branch"));
            meters.add(functionCounter(registry, PREFIX + "pipeline.branch.delivered", branchTags,
                    branch, BranchView::delivered, "Lifecycle event copies accepted by a branch"));
            meters.add(functionCounter(registry, PREFIX + "pipeline.branch.dropped", branchTags,
                    branch, BranchView::dropped, "Lifecycle event copies rejected by a branch"));
        }
        return new Registration(registry, views, meters);
    }

    /** Registers five delivery-health meters for one product-neutral event adapter. */
    public static Registration bind(
            MeterRegistry registry,
            String ownerName,
            String adapterName,
            LifecycleEventDelivery delivery) {
        Objects.requireNonNull(registry, "registry");
        String safeOwner = requireOwnerName(ownerName);
        String safeAdapter = requireAdapterName(adapterName);
        DeliveryView view = new DeliveryView(Objects.requireNonNull(delivery, "delivery"));
        Tags tags = Tags.of("owner", safeOwner, "kind", "adapter", "adapter", safeAdapter);
        ensureAvailable(registry, PREFIX + "pipeline.adapter.drained", tags);
        List<Meter> meters = new ArrayList<>();

        meters.add(functionCounter(registry, PREFIX + "pipeline.adapter.drained", tags, view,
                DeliveryView::drained, "Lifecycle events removed from an adapter input"));
        meters.add(functionCounter(registry, PREFIX + "pipeline.adapter.successes", tags, view,
                DeliveryView::successful, "Lifecycle events delivered by an adapter"));
        meters.add(functionCounter(registry, PREFIX + "pipeline.adapter.failures", tags, view,
                DeliveryView::failed, "Lifecycle events rejected by adapter-side processing"));
        meters.add(functionCounter(registry, PREFIX + "pipeline.adapter.source.dropped", tags, view,
                DeliveryView::sourceDropped, "Lifecycle events dropped before adapter draining"));
        meters.add(gauge(registry, PREFIX + "pipeline.adapter.closed", tags, view,
                DeliveryView::closed, "Whether adapter close and final draining completed"));
        return new Registration(registry, List.of(view), meters);
    }

    /** Registers sixteen meters for an overlap-safe managed owner. */
    public static Registration bind(
            MeterRegistry registry,
            String ownerName,
            ManagedResource<?, ?> owner,
            LifecycleEventBuffer events) {
        Objects.requireNonNull(registry, "registry");
        String safeName = requireOwnerName(ownerName);
        ManagedView view = new ManagedView(Objects.requireNonNull(owner, "owner"));
        BufferView buffer = new BufferView(Objects.requireNonNull(events, "events"));
        Tags tags = Tags.of("owner", safeName, "kind", "managed");
        List<Meter> meters = new ArrayList<>();

        for (ManagedResourceStatus.Lifecycle state : ManagedResourceStatus.Lifecycle.values()) {
            meters.add(Gauge.builder(PREFIX + "state", view,
                            value -> value.lifecycle() == state ? 1.0 : 0.0)
                    .tags(tags.and("state", state.name().toLowerCase(Locale.ROOT)))
                    .description("One-hot lifecycle owner state")
                    .register(registry));
        }
        meters.add(gauge(registry, PREFIX + "active.generation", tags, view,
                ManagedView::activeGeneration, "Current native resource generation"));
        meters.add(gauge(registry, PREFIX + "active.revision", tags, view,
                ManagedView::activeRevision, "Current applied configuration revision"));
        meters.add(gauge(registry, PREFIX + "desired.revision", tags, view,
                ManagedView::desiredRevision, "Highest observed configuration revision"));
        meters.add(gauge(registry, PREFIX + "active.leases", tags, view,
                ManagedView::activeLeases, "Current active resource leases"));
        meters.add(gauge(registry, PREFIX + "replacement.active", tags, view,
                ManagedView::replacementActive, "Whether generation replacement is active"));
        meters.add(gauge(registry, PREFIX + "refresh.pending", tags, view,
                ManagedView::refreshPending, "Whether a refresh signal is pending"));
        meters.add(functionCounter(registry, PREFIX + "refresh.successes", tags, view,
                ManagedView::refreshSuccesses, "Successful refresh publications"));
        meters.add(functionCounter(registry, PREFIX + "refresh.failures", tags, view,
                ManagedView::refreshFailures, "Failed refresh attempts"));
        meters.add(functionCounter(registry, PREFIX + "close.failures", tags, view,
                ManagedView::closeFailures, "Failed native resource close attempts"));
        registerBufferMeters(registry, tags, buffer, meters);
        return new Registration(registry, List.of(view, buffer), meters);
    }

    /** Registers nine meters for a restart-required monitor. */
    public static Registration bind(
            MeterRegistry registry,
            String ownerName,
            RestartRequiredMonitor<?> owner,
            LifecycleEventBuffer events) {
        Objects.requireNonNull(registry, "registry");
        String safeName = requireOwnerName(ownerName);
        RestartView view = new RestartView(Objects.requireNonNull(owner, "owner"));
        BufferView buffer = new BufferView(Objects.requireNonNull(events, "events"));
        Tags tags = Tags.of("owner", safeName, "kind", "restart");
        List<Meter> meters = new ArrayList<>();

        for (RestartRequiredStatus.State state : RestartRequiredStatus.State.values()) {
            meters.add(Gauge.builder(PREFIX + "state", view,
                            value -> value.state() == state ? 1.0 : 0.0)
                    .tags(tags.and("state", state.name().toLowerCase(Locale.ROOT)))
                    .description("One-hot lifecycle owner state")
                    .register(registry));
        }
        meters.add(gauge(registry, PREFIX + "applied.revision", tags, view,
                RestartView::appliedRevision, "Configuration revision used by the running process"));
        meters.add(gauge(registry, PREFIX + "desired.revision", tags, view,
                RestartView::desiredRevision, "Highest observed configuration revision"));
        meters.add(gauge(registry, PREFIX + "restart.required", tags, view,
                RestartView::restartRequired, "Whether a rolling restart is required"));
        meters.add(gauge(registry, PREFIX + "observation.failure", tags, view,
                RestartView::hasFailure, "Whether the monitor retains a redacted failure"));
        registerBufferMeters(registry, tags, buffer, meters);
        return new Registration(registry, List.of(view, buffer), meters);
    }

    private static <T> Gauge gauge(
            MeterRegistry registry,
            String name,
            Tags tags,
            T view,
            java.util.function.ToDoubleFunction<T> value,
            String description) {
        return Gauge.builder(name, view, value)
                .tags(tags)
                .description(description)
                .register(registry);
    }

    private static <T> FunctionCounter functionCounter(
            MeterRegistry registry,
            String name,
            Tags tags,
            T view,
            java.util.function.ToDoubleFunction<T> value,
            String description) {
        return FunctionCounter.builder(name, view, value)
                .tags(tags)
                .description(description)
                .register(registry);
    }

    private static void registerBufferMeters(
            MeterRegistry registry,
            Tags tags,
            BufferView buffer,
            List<Meter> meters) {
        meters.add(gauge(registry, PREFIX + "events.buffered", tags, buffer,
                BufferView::size, "Lifecycle events currently buffered"));
        meters.add(functionCounter(registry, PREFIX + "events.dropped", tags, buffer,
                BufferView::droppedEvents, "Lifecycle events dropped before adapter consumption"));
    }

    private static String requireOwnerName(String ownerName) {
        Objects.requireNonNull(ownerName, "ownerName");
        if (!SAFE_OWNER_NAME.matcher(ownerName).matches()) {
            throw new IllegalArgumentException(
                    "ownerName must be 1-128 ASCII letters, digits, dots, underscores, or hyphens");
        }
        return ownerName;
    }

    private static String requireAdapterName(String adapterName) {
        Objects.requireNonNull(adapterName, "adapterName");
        if (!SAFE_OWNER_NAME.matcher(adapterName).matches()) {
            throw new IllegalArgumentException(
                    "adapterName must be 1-128 ASCII letters, digits, dots, underscores, or hyphens");
        }
        return adapterName;
    }

    private static void ensureAvailable(MeterRegistry registry, String meterName, Tags tags) {
        if (registry.find(meterName).tags(tags).meter() != null) {
            throw new IllegalStateException("pipeline metric identity is already registered");
        }
    }

    /** Idempotent ownership handle which removes only the meters created by one registration. */
    public static final class Registration implements AutoCloseable {

        private final MeterRegistry registry;
        private final List<Object> views;
        private final List<Meter.Id> meterIds;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(MeterRegistry registry, List<Object> views, List<Meter> meters) {
            this.registry = registry;
            this.views = new ArrayList<>(views);
            meterIds = meters.stream().map(Meter::getId).toList();
        }

        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            RuntimeException failure = null;
            for (Meter.Id meterId : meterIds) {
                try {
                    registry.remove(meterId);
                } catch (RuntimeException current) {
                    if (failure == null) {
                        failure = current;
                    } else {
                        failure.addSuppressed(current);
                    }
                }
            }
            views.clear();
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class BoundView {
        private final WeakReference<BoundResource<?>> owner;

        private BoundView(BoundResource<?> owner) {
            this.owner = new WeakReference<>(owner);
        }

        private BoundResource.State state() {
            BoundResource<?> value = owner.get();
            return value == null ? null : value.state();
        }
    }

    private static final class ManagedView {
        private final WeakReference<ManagedResource<?, ?>> owner;
        private final AtomicLong refreshSuccesses = new AtomicLong();
        private final AtomicLong refreshFailures = new AtomicLong();
        private final AtomicLong closeFailures = new AtomicLong();

        private ManagedView(ManagedResource<?, ?> owner) {
            this.owner = new WeakReference<>(owner);
        }

        private ManagedResourceStatus status() {
            ManagedResource<?, ?> value = owner.get();
            return value == null ? null : value.status();
        }

        private ManagedResourceStatus.Lifecycle lifecycle() {
            ManagedResourceStatus value = status();
            return value == null ? null : value.lifecycle();
        }

        private double activeGeneration() { return number(ManagedResourceStatus::activeGeneration); }
        private double activeRevision() { return number(ManagedResourceStatus::activeRevision); }
        private double desiredRevision() { return number(ManagedResourceStatus::desiredRevision); }
        private double activeLeases() { return number(ManagedResourceStatus::activeLeases); }
        private double replacementActive() { return bool(ManagedResourceStatus::replacementInProgress); }
        private double refreshPending() { return bool(ManagedResourceStatus::refreshPending); }

        private double refreshSuccesses() {
            return monotonic(refreshSuccesses, ManagedResourceStatus::refreshSuccesses);
        }

        private double refreshFailures() {
            return monotonic(refreshFailures, ManagedResourceStatus::refreshFailures);
        }

        private double closeFailures() {
            return monotonic(closeFailures, status -> status.closeFailures());
        }

        private double number(java.util.function.ToLongFunction<ManagedResourceStatus> function) {
            ManagedResourceStatus value = status();
            return value == null ? Double.NaN : function.applyAsLong(value);
        }

        private double bool(java.util.function.Predicate<ManagedResourceStatus> predicate) {
            ManagedResourceStatus value = status();
            return value == null ? Double.NaN : predicate.test(value) ? 1.0 : 0.0;
        }

        private double monotonic(
                AtomicLong retained,
                java.util.function.ToLongFunction<ManagedResourceStatus> function) {
            ManagedResourceStatus value = status();
            if (value != null) {
                retained.accumulateAndGet(function.applyAsLong(value), Math::max);
            }
            return retained.get();
        }
    }

    private static final class RestartView {
        private final WeakReference<RestartRequiredMonitor<?>> owner;

        private RestartView(RestartRequiredMonitor<?> owner) {
            this.owner = new WeakReference<>(owner);
        }

        private RestartRequiredStatus status() {
            RestartRequiredMonitor<?> value = owner.get();
            return value == null ? null : value.status();
        }

        private RestartRequiredStatus.State state() {
            RestartRequiredStatus value = status();
            return value == null ? null : value.state();
        }

        private double appliedRevision() { return number(RestartRequiredStatus::appliedRevision); }
        private double desiredRevision() { return number(RestartRequiredStatus::desiredRevision); }
        private double restartRequired() { return bool(RestartRequiredStatus::restartRequired); }
        private double hasFailure() { return bool(value -> value.lastFailure().isPresent()); }

        private double number(java.util.function.ToLongFunction<RestartRequiredStatus> function) {
            RestartRequiredStatus value = status();
            return value == null ? Double.NaN : function.applyAsLong(value);
        }

        private double bool(java.util.function.Predicate<RestartRequiredStatus> predicate) {
            RestartRequiredStatus value = status();
            return value == null ? Double.NaN : predicate.test(value) ? 1.0 : 0.0;
        }
    }

    private static final class BufferView {
        private final LifecycleEventBuffer events;

        private BufferView(LifecycleEventBuffer events) {
            this.events = events;
        }

        private double size() { return events.size(); }
        private double droppedEvents() { return events.droppedEvents(); }
    }

    private static final class FanOutView {
        private final WeakReference<LifecycleEventFanOut> fanOut;
        private final AtomicLong drained = new AtomicLong();
        private final AtomicLong sourceDropped = new AtomicLong();

        private FanOutView(LifecycleEventFanOut fanOut) {
            this.fanOut = new WeakReference<>(fanOut);
        }

        private double sourceBuffered() {
            LifecycleEventFanOut value = fanOut.get();
            return value == null ? Double.NaN : value.source().size();
        }

        private double drained() {
            LifecycleEventFanOut value = fanOut.get();
            if (value != null) {
                drained.accumulateAndGet(value.drainedEvents(), Math::max);
            }
            return drained.get();
        }

        private double sourceDropped() {
            LifecycleEventFanOut value = fanOut.get();
            if (value != null) {
                sourceDropped.accumulateAndGet(value.sourceDroppedEvents(), Math::max);
            }
            return sourceDropped.get();
        }
    }

    private static final class BranchView {
        private final WeakReference<LifecycleEventFanOut> fanOut;
        private final String branchName;
        private final AtomicLong delivered = new AtomicLong();
        private final AtomicLong dropped = new AtomicLong();

        private BranchView(LifecycleEventFanOut fanOut, String branchName) {
            this.fanOut = new WeakReference<>(fanOut);
            this.branchName = branchName;
        }

        private double buffered() {
            LifecycleEventFanOut value = fanOut.get();
            return value == null ? Double.NaN : value.branch(branchName).size();
        }

        private double delivered() {
            LifecycleEventFanOut value = fanOut.get();
            if (value != null) {
                delivered.accumulateAndGet(value.deliveredEvents(branchName), Math::max);
            }
            return delivered.get();
        }

        private double dropped() {
            LifecycleEventFanOut value = fanOut.get();
            if (value != null) {
                dropped.accumulateAndGet(value.droppedEvents(branchName), Math::max);
            }
            return dropped.get();
        }
    }

    private static final class DeliveryView {
        private final WeakReference<LifecycleEventDelivery> delivery;
        private final AtomicLong drained = new AtomicLong();
        private final AtomicLong successful = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong sourceDropped = new AtomicLong();

        private DeliveryView(LifecycleEventDelivery delivery) {
            this.delivery = new WeakReference<>(delivery);
        }

        private double drained() { return monotonic(drained, LifecycleEventDelivery::drainedEvents); }
        private double successful() {
            return monotonic(successful, LifecycleEventDelivery::successfulEvents);
        }
        private double failed() { return monotonic(failed, LifecycleEventDelivery::failedEvents); }
        private double sourceDropped() {
            return monotonic(sourceDropped, LifecycleEventDelivery::sourceDroppedEvents);
        }
        private double closed() {
            LifecycleEventDelivery value = delivery.get();
            return value == null ? Double.NaN : value.isClosed() ? 1.0 : 0.0;
        }

        private double monotonic(
                AtomicLong retained,
                java.util.function.ToLongFunction<LifecycleEventDelivery> function) {
            LifecycleEventDelivery value = delivery.get();
            if (value != null) {
                retained.accumulateAndGet(function.applyAsLong(value), Math::max);
            }
            return retained.get();
        }
    }
}
