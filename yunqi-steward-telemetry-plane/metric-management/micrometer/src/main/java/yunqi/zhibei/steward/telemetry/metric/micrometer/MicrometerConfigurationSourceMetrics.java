package yunqi.zhibei.steward.telemetry.metric.micrometer;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import yunqi.zhibei.steward.control.configuration.ConfigurationSourceStatus;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Exports the bounded, secret-free operational status of one configuration source.
 *
 * <p>Scrapes perform only a source status read. The source is weakly referenced and no typed
 * configuration, provider identifier, path, throwable, or native client is retained.
 */
public final class MicrometerConfigurationSourceMetrics {

    private static final String PREFIX = "middleware.configuration.source.";
    private static final Pattern SAFE_SOURCE_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private MicrometerConfigurationSourceMetrics() {
    }

    /** Registers eleven fixed-cardinality meters for one source. */
    public static Registration bind(
            MeterRegistry registry,
            String sourceName,
            ConfigurationSource<?> source) {
        Objects.requireNonNull(registry, "registry");
        String safeName = requireSourceName(sourceName);
        SourceView view = new SourceView(Objects.requireNonNull(source, "source"));
        Tags tags = Tags.of("source", safeName);
        ensureAvailable(registry, PREFIX + "state", tags);
        List<Meter> meters = new ArrayList<>();

        for (ConfigurationSourceStatus.State state : ConfigurationSourceStatus.State.values()) {
            meters.add(Gauge.builder(PREFIX + "state", view,
                            value -> value.state() == state ? 1.0 : 0.0)
                    .tags(tags.and("state", state.name().toLowerCase(Locale.ROOT)))
                    .description("One-hot configuration source state")
                    .register(registry));
        }
        meters.add(gauge(registry, PREFIX + "revision", tags, view,
                SourceView::revision, "Latest complete configuration revision"));
        meters.add(functionCounter(registry, PREFIX + "failures", tags, view,
                SourceView::failures, "Configuration source failures"));
        meters.add(functionCounter(registry, PREFIX + "recoveries", tags, view,
                SourceView::recoveries, "Configuration source availability recoveries"));
        for (ConfigurationSourceStatus.FailureStage stage
                : ConfigurationSourceStatus.FailureStage.values()) {
            meters.add(Gauge.builder(PREFIX + "last.failure.stage", view,
                            value -> value.lastFailureStage() == stage ? 1.0 : 0.0)
                    .tags(tags.and("stage", stage.name().toLowerCase(Locale.ROOT)))
                    .description("One-hot latest configuration source failure stage")
                    .register(registry));
        }
        return new Registration(registry, List.of(view), meters);
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

    private static String requireSourceName(String sourceName) {
        Objects.requireNonNull(sourceName, "sourceName");
        if (!SAFE_SOURCE_NAME.matcher(sourceName).matches()) {
            throw new IllegalArgumentException(
                    "sourceName must be 1-128 ASCII letters, digits, dots, underscores, or hyphens");
        }
        return sourceName;
    }

    private static void ensureAvailable(MeterRegistry registry, String meterName, Tags tags) {
        if (registry.find(meterName).tags(tags).meter() != null) {
            throw new IllegalStateException("configuration source metric identity is already registered");
        }
    }

    /** Idempotent registration owner which removes only meters created by one binding. */
    public static final class Registration implements AutoCloseable {

        private final MeterRegistry registry;
        private final List<Object> views;
        private final List<Meter.Id> meterIds;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(MeterRegistry registry, List<Object> views, List<Meter> meters) {
            this.registry = registry;
            this.views = new ArrayList<>(views);
            this.meterIds = meters.stream().map(Meter::getId).toList();
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

    private static final class SourceView {
        private final WeakReference<ConfigurationSource<?>> source;
        private final AtomicLong retainedFailures = new AtomicLong();
        private final AtomicLong retainedRecoveries = new AtomicLong();

        private SourceView(ConfigurationSource<?> source) {
            this.source = new WeakReference<>(source);
        }

        private ConfigurationSourceStatus status() {
            ConfigurationSource<?> value = source.get();
            return value == null ? null : value.status();
        }

        private ConfigurationSourceStatus.State state() {
            ConfigurationSourceStatus value = status();
            return value == null ? null : value.state();
        }

        private double revision() {
            ConfigurationSourceStatus value = status();
            return value == null ? Double.NaN : value.revision();
        }

        private double failures() {
            return monotonic(retainedFailures, ConfigurationSourceStatus::failures);
        }

        private double recoveries() {
            return monotonic(retainedRecoveries, ConfigurationSourceStatus::recoveries);
        }

        private ConfigurationSourceStatus.FailureStage lastFailureStage() {
            ConfigurationSourceStatus value = status();
            return value == null ? null : value.lastFailureStage();
        }

        private double monotonic(
                AtomicLong retained,
                java.util.function.ToLongFunction<ConfigurationSourceStatus> value) {
            ConfigurationSourceStatus status = status();
            if (status != null) {
                retained.accumulateAndGet(value.applyAsLong(status), Math::max);
            }
            return retained.get();
        }
    }
}
