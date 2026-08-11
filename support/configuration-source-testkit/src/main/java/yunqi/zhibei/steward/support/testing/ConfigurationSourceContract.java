package yunqi.zhibei.steward.support.testing;

import yunqi.zhibei.steward.control.configuration.ConfigurationSnapshot;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import yunqi.zhibei.steward.control.configuration.ConfigurationSourceStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Reusable behavioral contract checks for a complete typed configuration source. */
public final class ConfigurationSourceContract {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    private ConfigurationSourceContract() {
    }

    /**
     * Verifies ordered publication, complete-state retention, failure recovery, redaction,
     * subscription closure, and source shutdown through an implementation-specific scenario.
     *
     * <p>The scenario starts with semantic version {@code 1}. Its update and recovery actions must
     * publish semantic versions {@code 2} and {@code 3}. A failure action must never increment the
     * revision or expose a partial configuration.
     *
     * @param scenario implementation-specific source driver
     * @param secretValues exact sensitive values which diagnostics must omit
     * @param <C> immutable configuration type
     * @throws Exception when source setup, publication, waiting, or closure fails
     */
    public static <C> void verify(Scenario<C> scenario, String... secretValues) throws Exception {
        Scenario<C> checked = Objects.requireNonNull(scenario, "scenario");
        List<String> secrets = checkedSecrets(secretValues);
        ConfigurationSource<C> source = Objects.requireNonNull(checked.source(), "scenario.source()");
        AtomicInteger notifications = new AtomicInteger();
        ConfigurationSource.Subscription subscription = source.subscribe(
                notifications::incrementAndGet);

        try {
            requireSnapshot(checked, source.snapshot(), 1, 1);
            require(source.status().state() == ConfigurationSourceStatus.State.AVAILABLE,
                    "initial status", "the initialized source is not available");
            requireRedacted(source, source.status(), null, secrets);

            checked.publishUpdate();
            require(checked.awaitIdle(IDLE_TIMEOUT),
                    "update", "the source did not become idle after an update");
            requireSnapshot(checked, source.snapshot(), 2, 2);
            require(notifications.get() > 0,
                    "update notification", "a published update did not signal the subscriber");

            subscription.close();
            subscription.close();
            int notificationsAfterClose = notifications.get();

            checked.publishFailure();
            require(checked.awaitIdle(IDLE_TIMEOUT),
                    "failure", "the source did not become idle after a failed publication");
            RuntimeException observedFailure = null;
            try {
                ConfigurationSnapshot<C> retained = source.snapshot();
                require(checked.failureMode() == FailureMode.RETAINS_LAST_SNAPSHOT,
                        "failure visibility", "the source remained available unexpectedly");
                requireSnapshot(checked, retained, 2, 2);
            } catch (RuntimeException failure) {
                observedFailure = failure;
                require(checked.failureMode() == FailureMode.BECOMES_UNAVAILABLE,
                        "failure visibility", "the source became unavailable unexpectedly");
            }
            requireRedacted(source, source.status(), observedFailure, secrets);

            checked.publishRecovery();
            require(checked.awaitIdle(IDLE_TIMEOUT),
                    "recovery", "the source did not become idle after recovery");
            requireSnapshot(checked, source.snapshot(), 3, 3);
            require(source.status().state() == ConfigurationSourceStatus.State.AVAILABLE,
                    "recovery status", "the recovered source is not available");
            require(notifications.get() == notificationsAfterClose,
                    "subscription close", "a closed subscription received a later signal");
            requireRedacted(source, source.status(), null, secrets);
        } finally {
            subscription.close();
            checked.close();
            checked.close();
        }

        if (checked.closesSource()) {
            require(source.status().state() == ConfigurationSourceStatus.State.CLOSED,
                    "source close", "the closed source does not report CLOSED");
            try {
                source.subscribe(() -> { });
                throw violation("source close", "the closed source accepted a new subscription");
            } catch (IllegalStateException expected) {
                requireRedacted(source, source.status(), expected, secrets);
            }
        }
    }

    private static <C> void requireSnapshot(
            Scenario<C> scenario,
            ConfigurationSnapshot<C> snapshot,
            long revision,
            long semanticVersion) {
        require(snapshot.revision() == revision,
                "revision ordering", "unexpected source-local revision");
        require(scenario.semanticVersion(snapshot.configuration()) == semanticVersion,
                "complete configuration", "unexpected or partial typed configuration");
    }

    private static List<String> checkedSecrets(String[] secretValues) {
        Objects.requireNonNull(secretValues, "secretValues");
        List<String> secrets = new ArrayList<>(secretValues.length);
        for (String secret : secretValues) {
            String checked = Objects.requireNonNull(secret, "secretValues contains null");
            if (checked.isEmpty()) {
                throw new IllegalArgumentException("secretValues must not contain an empty value");
            }
            secrets.add(checked);
        }
        return List.copyOf(secrets);
    }

    private static void requireRedacted(
            Object source,
            ConfigurationSourceStatus status,
            RuntimeException failure,
            List<String> secrets) {
        List<String> diagnostics = new ArrayList<>();
        diagnostics.add(String.valueOf(source));
        diagnostics.add(String.valueOf(status));
        if (failure != null) {
            diagnostics.add(String.valueOf(failure));
        }
        for (String secret : secrets) {
            for (String diagnostic : diagnostics) {
                require(!diagnostic.contains(secret),
                        "diagnostic redaction", "a source diagnostic contains a supplied secret");
            }
        }
    }

    private static void require(boolean condition, String rule, String detail) {
        if (!condition) {
            throw violation(rule, detail);
        }
    }

    private static AssertionError violation(String rule, String detail) {
        return new AssertionError("Configuration source contract violation (" + rule + "): " + detail);
    }

    /** Implementation-specific driver used by the shared contract. */
    public interface Scenario<C> extends AutoCloseable {

        /** Returns the initialized source under test. */
        ConfigurationSource<C> source();

        /** Returns a non-sensitive semantic version from a complete typed configuration. */
        long semanticVersion(C configuration);

        /** Publishes semantic version {@code 2}. */
        void publishUpdate() throws Exception;

        /** Attempts to publish an invalid or incomplete state. */
        void publishFailure() throws Exception;

        /** Publishes semantic version {@code 3} after the failure. */
        void publishRecovery() throws Exception;

        /** Waits until all source work admitted before the call has completed. */
        boolean awaitIdle(Duration timeout) throws Exception;

        /** Describes whether the failed publication makes {@link ConfigurationSource#snapshot()} fail. */
        FailureMode failureMode();

        /** Returns whether {@link #close()} closes the source itself. */
        boolean closesSource();

        /** Stops the scenario and, when declared, the source. Must be idempotent. */
        @Override
        void close();
    }

    /** Snapshot visibility after a provider or caller rejects an incomplete desired state. */
    public enum FailureMode {
        RETAINS_LAST_SNAPSHOT,
        BECOMES_UNAVAILABLE
    }
}
