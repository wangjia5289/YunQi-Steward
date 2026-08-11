package yunqi.zhibei.steward.support.testing;

import yunqi.zhibei.steward.control.resource.BoundResource;
import yunqi.zhibei.steward.control.resource.Health;
import yunqi.zhibei.steward.control.resource.HealthCheck;
import yunqi.zhibei.steward.control.resource.ProbeScope;
import yunqi.zhibei.steward.control.resource.ResourceBinding;
import yunqi.zhibei.steward.control.resource.refresh.FailureSnapshot;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResource;
import yunqi.zhibei.steward.control.resource.refresh.ManagedResourceStatus;
import yunqi.zhibei.steward.control.configuration.MutableConfigurationSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Reusable lifecycle contract checks for a refresh-safe native SDK binding. */
public final class BindingContract {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    private BindingContract() {
    }

    /**
     * Verifies creation, failed-candidate cleanup, idempotent ownership, configuration redaction,
     * and refresh rollback using real resources created by the supplied binding.
     *
     * <p>The two configurations must construct resources without external services. Health is
     * controlled by the testkit so the binding's remote probe is not invoked. Secret values are
     * checked only against configuration diagnostic strings.
     *
     * @param binding refresh-safe binding under test
     * @param initialConfiguration first complete offline-safe configuration
     * @param updatedConfiguration different complete offline-safe configuration
     * @param secretValues exact secret values which diagnostic strings must omit
     * @param <C> configuration type
     * @param <T> native SDK resource type
     * @throws Exception when native creation or closure fails
     */
    public static <C, T> void verify(
            ResourceBinding<C, T> binding,
            C initialConfiguration,
            C updatedConfiguration,
            String... secretValues) throws Exception {
        ResourceBinding<C, T> checkedBinding = Objects.requireNonNull(binding, "binding");
        C initial = Objects.requireNonNull(initialConfiguration, "initialConfiguration");
        C updated = Objects.requireNonNull(updatedConfiguration, "updatedConfiguration");
        List<String> secrets = checkedSecrets(secretValues);

        verifyRedaction(initial, updated, secrets);
        verifyCreationAndIdempotentClose(checkedBinding, initial);
        verifyFailedCandidateCleanup(checkedBinding, initial);
        verifyRefreshRollback(checkedBinding, initial, updated);
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

    private static <C> void verifyRedaction(C initial, C updated, List<String> secrets) {
        String initialDiagnostic = initial.toString();
        String updatedDiagnostic = updated.toString();
        for (String secret : secrets) {
            require(!initialDiagnostic.contains(secret),
                    "configuration redaction",
                    "initial configuration diagnostic contains a supplied secret");
            require(!updatedDiagnostic.contains(secret),
                    "configuration redaction",
                    "updated configuration diagnostic contains a supplied secret");
        }
    }

    private static <C, T> void verifyCreationAndIdempotentClose(
            ResourceBinding<C, T> binding,
            C configuration) throws Exception {
        TrackingBinding<C, T> tracking = new TrackingBinding<>(
                binding, ignored -> Health.healthy(ProbeScope.LOCAL));
        BoundResource<T> bound = BoundResource.start(configuration, tracking);
        T resource = bound.resource();

        bound.close();
        bound.close();

        require(tracking.createdCount() == 1,
                "creation",
                "expected exactly one native resource");
        require(tracking.closeAttempts(resource) == 1,
                "idempotent close",
                "the native closer was not called exactly once");
    }

    private static <C, T> void verifyFailedCandidateCleanup(
            ResourceBinding<C, T> binding,
            C configuration) throws Exception {
        TrackingBinding<C, T> tracking = new TrackingBinding<>(
                binding, ignored -> Health.unhealthy(ProbeScope.LOCAL));
        try {
            BoundResource<T> unexpected = BoundResource.start(configuration, tracking);
            unexpected.close();
            throw violation(
                    "failed candidate cleanup",
                    "an unhealthy startup candidate was published");
        } catch (IllegalStateException expected) {
            require("The bound resource is unhealthy".equals(expected.getMessage()),
                    "failed candidate cleanup",
                    "startup failed for an unexpected reason");
            require(expected.getSuppressed().length == 0,
                    "failed candidate cleanup",
                    "closing the unhealthy candidate failed");
        }

        require(tracking.createdCount() == 1,
                "failed candidate cleanup",
                "expected exactly one unhealthy candidate");
        T candidate = tracking.created().getFirst();
        require(tracking.closeAttempts(candidate) == 1,
                "failed candidate cleanup",
                "the unhealthy candidate was not closed exactly once");
    }

    private static <C, T> void verifyRefreshRollback(
            ResourceBinding<C, T> binding,
            C initial,
            C updated) throws Exception {
        AtomicBoolean candidateHealthy = new AtomicBoolean(true);
        TrackingBinding<C, T> tracking = new TrackingBinding<>(binding, ignored ->
                candidateHealthy.get()
                        ? Health.healthy(ProbeScope.LOCAL)
                        : Health.unhealthy(ProbeScope.LOCAL));
        MutableConfigurationSource<C> source = new MutableConfigurationSource<>(initial);
        ManagedResource<T, C> managed = ManagedResource.bind(source, tracking);
        AtomicReference<T> initialResource = new AtomicReference<>();

        try {
            managed.execute(resource -> {
                initialResource.set(resource);
                return null;
            });
            candidateHealthy.set(false);
            source.update(updated);

            require(managed.awaitIdle(IDLE_TIMEOUT),
                    "refresh rollback",
                    "refresh did not become idle within " + IDLE_TIMEOUT);

            AtomicReference<T> activeAfterFailure = new AtomicReference<>();
            managed.execute(resource -> {
                activeAfterFailure.set(resource);
                return null;
            });
            ManagedResourceStatus status = managed.status();

            require(activeAfterFailure.get() == initialResource.get(),
                    "refresh rollback",
                    "an unhealthy candidate replaced the active resource");
            require(status.activeRevision() == 1 && status.desiredRevision() == 2,
                    "refresh rollback",
                    "active and desired revisions do not describe a rolled-back refresh");
            require(status.lastRefreshFailure()
                            .map(FailureSnapshot::stage)
                            .filter(stage -> stage == FailureSnapshot.Stage.CANDIDATE_HEALTH_CHECK)
                            .isPresent(),
                    "refresh rollback",
                    "candidate health failure was not recorded");
            require(tracking.createdCount() == 2,
                    "refresh rollback",
                    "expected one active resource and one replacement candidate");
            T candidate = tracking.created().get(1);
            require(candidate != initialResource.get(),
                    "refresh rollback",
                    "the binding reused the active resource as a replacement candidate");
            require(tracking.closeAttempts(candidate) == 1,
                    "refresh rollback",
                    "the rejected replacement candidate was not closed exactly once");
            require(tracking.closeAttempts(initialResource.get()) == 0,
                    "refresh rollback",
                    "the active resource was closed during rollback");
            require(managed.closeFailures().isEmpty(),
                    "refresh rollback",
                    "closing the rejected replacement candidate failed");
        } finally {
            managed.close();
        }

        require(tracking.closeAttempts(initialResource.get()) == 1,
                "refresh rollback",
                "the active resource was not closed exactly once at shutdown");
        require(managed.closeFailures().isEmpty(),
                "refresh rollback",
                "closing a resource generation failed");
    }

    private static void require(boolean condition, String rule, String detail) {
        if (!condition) {
            throw violation(rule, detail);
        }
    }

    private static AssertionError violation(String rule, String detail) {
        return new AssertionError("Binding contract violation (" + rule + "): " + detail);
    }

    private static final class TrackingBinding<C, T> implements ResourceBinding<C, T> {

        private final ResourceBinding<C, T> delegate;
        private final HealthCheck<? super T> healthCheck;
        private final List<T> created = new ArrayList<>();
        private final Map<T, Integer> closeAttempts = new IdentityHashMap<>();

        private TrackingBinding(
                ResourceBinding<C, T> delegate,
                HealthCheck<? super T> healthCheck) {
            this.delegate = delegate;
            this.healthCheck = healthCheck;
        }

        @Override
        public T create(C configuration) throws Exception {
            T resource = Objects.requireNonNull(
                    delegate.create(configuration),
                    "binding.create() returned null");
            synchronized (this) {
                created.add(resource);
            }
            return resource;
        }

        @Override
        public Health check(T resource) throws Exception {
            return healthCheck.check(resource);
        }

        @Override
        public void close(T resource) throws Exception {
            synchronized (this) {
                closeAttempts.merge(resource, 1, Integer::sum);
            }
            delegate.close(resource);
        }

        private synchronized int createdCount() {
            return created.size();
        }

        private synchronized List<T> created() {
            return List.copyOf(created);
        }

        private synchronized int closeAttempts(T resource) {
            return closeAttempts.getOrDefault(resource, 0);
        }
    }
}
