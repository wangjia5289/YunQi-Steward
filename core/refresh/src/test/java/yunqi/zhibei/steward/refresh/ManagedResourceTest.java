package yunqi.zhibei.steward.refresh;

import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.HealthCheck;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import yunqi.zhibei.steward.lifecycle.ResourceCloser;
import yunqi.zhibei.steward.lifecycle.ResourceFactory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedResourceTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void refreshesAutomaticallyAndClosesTheRetiredResource() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        Map<String, TestResource> resources = new ConcurrentHashMap<>();
        Instant lifecycleTime = Instant.parse("2026-01-02T03:04:05Z");
        ControlledLifecycleRuntime runtime = new ControlledLifecycleRuntime(lifecycleTime);

        ManagedResource<TestResource, String> managed =
                ManagedResource.<TestResource, String>builder(
                                source,
                                binding(
                                        configuration -> resources.computeIfAbsent(
                                                configuration, TestResource::new),
                                        resource -> Health.healthy(ProbeScope.LOCAL),
                                        TestResource::close))
                        .closeWaitTimeout(TEST_TIMEOUT)
                        .runtime(runtime)
                        .build();
        try {
            assertThat(managed.status().lastSuccessfulRefreshAt()).contains(lifecycleTime);
            assertThat(managed.execute(TestResource::name)).isEqualTo("first");

            source.update("second");
            assertThat(managed.execute(TestResource::name)).isEqualTo("first");
            assertThat(runtime.pendingTasks()).isEqualTo(1);

            runtime.runUntilIdle();

            awaitActive(managed, "second");
            assertThat(managed.execute(TestResource::name)).isEqualTo("second");
            assertThat(managed.status().lastSuccessfulRefreshAt()).contains(lifecycleTime);
            assertThat(resources.get("first").closed()).isTrue();
            assertThat(resources.get("second").closed()).isFalse();
        } finally {
            runtime.useSystemDispatch();
            managed.close();
        }

        await(() -> resources.get("second").closed());
    }

    @Test
    void fixedBindingCreatesAndRefreshesOnlyItsNativeResourceType() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        Map<String, TestResource> resources = new ConcurrentHashMap<>();
        ResourceBinding<String, TestResource> binding = new ResourceBinding<>() {
            @Override
            public TestResource create(String configuration) {
                return resources.computeIfAbsent(configuration, TestResource::new);
            }

            @Override
            public Health check(TestResource resource) {
                return Health.healthy(ProbeScope.LOCAL);
            }

            @Override
            public void close(TestResource resource) {
                resource.close();
            }
        };

        try (ManagedResource<TestResource, String> managed =
                     ManagedResource.bind(source, binding)) {
            assertThat(managed.execute(TestResource::name)).isEqualTo("first");
            source.update("second");
            awaitActive(managed, "second");
            assertThat(managed.execute(TestResource::name)).isEqualTo("second");
            await(() -> resources.get("first").closed());
        }
    }

    @Test
    void failedHealthCheckKeepsTheCurrentResourceAndCleansUpTheCandidate() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("good");
        Map<String, TestResource> resources = new ConcurrentHashMap<>();

        try (ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> resources.computeIfAbsent(configuration, TestResource::new),
                resource -> {
                    if (resource.name().equals("bad")) {
                        throw new IOException("vendor password=secret");
                    }
                    return Health.healthy(ProbeScope.LOCAL);
                },
                TestResource::close,
                TEST_TIMEOUT)) {
            source.update("bad");

            await(() -> managed.lastRefreshFailure().isPresent());
            assertThat(managed.execute(TestResource::name)).isEqualTo("good");
            assertThat(managed.lastRefreshFailure()).hasValueSatisfying(failure -> {
                assertThat(failure.stage())
                        .isEqualTo(FailureSnapshot.Stage.CANDIDATE_HEALTH_CHECK);
                assertThat(failure.failureType()).isEqualTo(IOException.class.getName());
                assertThat(failure.generation()).isPositive();
                assertThat(failure.revision()).isEqualTo(2);
                assertThat(failure.toString()).doesNotContain("secret", "password");
            });
            await(() -> resources.get("bad").closed());
            assertThat(resources.get("good").closed()).isFalse();
        }
    }

    @Test
    void unhealthyResultKeepsTheCurrentResourceAndCleansUpTheCandidate() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("good");
        Map<String, TestResource> resources = new ConcurrentHashMap<>();

        try (ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> resources.computeIfAbsent(configuration, TestResource::new),
                resource -> resource.name().equals("bad")
                        ? Health.unhealthy(ProbeScope.LOCAL)
                        : Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                TEST_TIMEOUT)) {
            source.update("bad");

            await(() -> managed.lastRefreshFailure().isPresent());
            assertThat(managed.execute(TestResource::name)).isEqualTo("good");
            assertThat(managed.lastRefreshFailure()).hasValueSatisfying(failure -> {
                assertThat(failure.stage())
                        .isEqualTo(FailureSnapshot.Stage.CANDIDATE_HEALTH_CHECK);
                assertThat(failure.failureType()).isEqualTo("unhealthy");
                assertThat(failure.generation()).isPositive();
                assertThat(failure.revision()).isEqualTo(2);
            });
            await(() -> resources.get("bad").closed());
        }
    }

    @Test
    void explicitRefreshRetriesAnUnchangedDesiredConfiguration() throws Exception {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        AtomicBoolean secondAvailable = new AtomicBoolean();

        try (ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> {
                    if (configuration.equals("second") && !secondAvailable.get()) {
                        throw new IOException("temporarily unavailable");
                    }
                    return new TestResource(configuration);
                },
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                TEST_TIMEOUT)) {
            source.update("second");
            await(() -> managed.lastRefreshFailure().isPresent());
            assertThat(managed.execute(TestResource::name)).isEqualTo("first");
            assertThat(managed.awaitIdle(TEST_TIMEOUT)).isTrue();
            assertThat(managed.status().activeRevision()).isEqualTo(1);
            assertThat(managed.status().desiredRevision()).isEqualTo(2);
            assertThat(managed.lastRefreshFailure()).hasValueSatisfying(failure -> {
                assertThat(failure.stage()).isEqualTo(FailureSnapshot.Stage.RESOURCE_CREATION);
                assertThat(failure.generation()).isZero();
                assertThat(failure.revision()).isEqualTo(2);
            });

            secondAvailable.set(true);
            managed.refresh();

            assertThat(managed.execute(TestResource::name)).isEqualTo("second");
            assertThat(managed.lastRefreshFailure()).isEmpty();
            assertThat(managed.health()).isEqualTo(Health.healthy(ProbeScope.LOCAL));
        }
    }

    @Test
    void healthCheckErrorClosesTheCandidateAndDisablesRefresh() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("good");
        Map<String, TestResource> resources = new ConcurrentHashMap<>();

        try (ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> resources.computeIfAbsent(configuration, TestResource::new),
                resource -> {
                    if (resource.name().equals("bad")) {
                        throw new AssertionError("vendor secret");
                    }
                    return Health.healthy(ProbeScope.LOCAL);
                },
                TestResource::close,
                TEST_TIMEOUT)) {
            source.update("bad");

            await(() -> managed.status().lifecycle()
                    == ManagedResourceStatus.Lifecycle.REFRESH_DISABLED);
            assertThat(managed.execute(TestResource::name)).isEqualTo("good");
            assertThat(managed.lastRefreshFailure()).hasValueSatisfying(failure -> {
                assertThat(failure.stage())
                        .isEqualTo(FailureSnapshot.Stage.CANDIDATE_HEALTH_CHECK);
                assertThat(failure.failureType())
                        .isEqualTo(AssertionError.class.getName());
                assertThat(failure.generation()).isPositive();
                assertThat(failure.revision()).isEqualTo(2);
            });
            await(() -> resources.get("bad").closed());
        }
    }

    @Test
    void anExistingLeaseKeepsItsGenerationAliveDuringRefresh() throws Exception {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        Map<String, TestResource> resources = new ConcurrentHashMap<>();

        try (ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> resources.computeIfAbsent(configuration, TestResource::new),
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                Duration.ofMillis(30))) {
            ManagedResource.Lease<TestResource> oldLease = managed.acquire();
            source.update("second");

            awaitActive(managed, "second");
            assertThat(managed.execute(TestResource::name)).isEqualTo("second");
            assertThat(oldLease.execute(TestResource::name)).isEqualTo("first");
            assertThat(resources.get("first").closed()).isFalse();

            oldLease.close();
            oldLease.close();
            assertThat(managed.awaitIdle(TEST_TIMEOUT)).isTrue();
            assertThat(resources.get("first").closed()).isTrue();
            assertThatThrownBy(() -> oldLease.execute(TestResource::name))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void checkedOperationFailureIsNotWrappedAndStillReleasesTheLease() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        Map<String, TestResource> resources = new ConcurrentHashMap<>();
        IOException operationFailure = new IOException("business failure");

        try (ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> resources.computeIfAbsent(configuration, TestResource::new),
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                TEST_TIMEOUT)) {
            assertThatThrownBy(() -> managed.execute(resource -> {
                throw operationFailure;
            })).isSameAs(operationFailure);

            source.update("second");
            assertThat(managed.awaitIdle(TEST_TIMEOUT)).isTrue();
            assertThat(resources.get("first").closed()).isTrue();
        }
    }

    @Test
    void uncheckedOperationFailuresStillReleaseTheirLeases() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        Map<String, TestResource> resources = new ConcurrentHashMap<>();

        try (ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> resources.computeIfAbsent(configuration, TestResource::new),
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                TEST_TIMEOUT)) {
            assertThatThrownBy(() -> managed.execute(resource -> {
                throw new IllegalArgumentException("runtime");
            })).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> managed.execute(resource -> {
                throw new AssertionError("error");
            })).isInstanceOf(AssertionError.class);

            source.update("second");
            assertThat(managed.awaitIdle(TEST_TIMEOUT)).isTrue();
            assertThat(resources.get("first").closed()).isTrue();
        }
    }

    @Test
    void asynchronousExecutionRetainsItsGenerationUntilCompletion() throws Exception {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        Map<String, TestResource> resources = new ConcurrentHashMap<>();
        java.util.concurrent.CompletableFuture<String> operation =
                new java.util.concurrent.CompletableFuture<>();

        try (ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> resources.computeIfAbsent(configuration, TestResource::new),
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                TEST_TIMEOUT)) {
            var result = managed.executeAsync(resource -> operation);

            source.update("second");
            assertThat(resources.get("first").closed()).isFalse();

            operation.complete("done");
            assertThat(result.toCompletableFuture().get()).isEqualTo("done");
            await(() -> resources.get("first").closed());
        }
    }

    @Test
    void coalescesChangesAndNeverOwnsMoreThanTwoResources() throws Exception {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        Map<String, TestResource> resources = new ConcurrentHashMap<>();
        AtomicInteger liveResources = new AtomicInteger();
        AtomicInteger maximumLiveResources = new AtomicInteger();
        ControlledLifecycleRuntime runtime = new ControlledLifecycleRuntime(
                Instant.parse("2026-01-02T03:04:05Z"));

        ManagedResource<TestResource, String> managed =
                ManagedResource.<TestResource, String>builder(
                                source,
                                binding(
                                        configuration -> {
                                            liveResources.incrementAndGet();
                                            maximumLiveResources.accumulateAndGet(
                                                    liveResources.get(), Math::max);
                                            TestResource resource = new TestResource(configuration);
                                            resources.put(configuration, resource);
                                            return resource;
                                        },
                                        ignored -> Health.healthy(ProbeScope.LOCAL),
                                        resource -> {
                                            resource.close();
                                            liveResources.decrementAndGet();
                                        }))
                        .closeWaitTimeout(TEST_TIMEOUT)
                        .runtime(runtime)
                        .build();
        ManagedResource.Lease<TestResource> firstLease = managed.acquire();
        try {
            source.update("second");
            runtime.runUntilIdle();
            awaitActive(managed, "second");
            source.update("third");
            source.update("fourth");
            source.update("latest");

            assertThat(managed.status().refreshPending()).isTrue();
            assertThat(managed.status().replacementInProgress()).isTrue();
            assertThat(resources).doesNotContainKeys("third", "fourth", "latest");
            assertThat(maximumLiveResources).hasValue(2);

            firstLease.close();
            runtime.runUntilIdle();
            awaitActive(managed, "latest");
            assertThat(managed.awaitIdle(Duration.ZERO)).isTrue();
            assertThat(resources).doesNotContainKeys("third", "fourth");
            assertThat(maximumLiveResources).hasValue(2);
        } finally {
            firstLease.close();
            runtime.runUntilIdle();
            runtime.useSystemDispatch();
            managed.close();
        }
    }

    @Test
    void exposesSecretFreeLifecycleStatus() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");

        ManagedResource<TestResource, String> managed = managed(
                source,
                TestResource::new,
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                TEST_TIMEOUT);
        try {
            ManagedResourceStatus initial = managed.status();
            assertThat(initial.lifecycle()).isEqualTo(ManagedResourceStatus.Lifecycle.RUNNING);
            assertThat(initial.activeGeneration()).isPositive();
            assertThat(initial.activeRevision()).isEqualTo(1);
            assertThat(initial.desiredRevision()).isEqualTo(1);
            assertThat(initial.activeLeases()).isZero();
            assertThat(initial.replacementInProgress()).isFalse();
            assertThat(initial.refreshPending()).isFalse();
            assertThat(initial.refreshSuccesses()).isEqualTo(1);
            assertThat(initial.lastSuccessfulRefreshAt()).isPresent();
            assertThat(initial.lastRefreshFailure()).isEmpty();

            try (ManagedResource.Lease<TestResource> lease = managed.acquire()) {
                ManagedResourceStatus leased = managed.status();
                assertThat(leased.activeGeneration()).isEqualTo(initial.activeGeneration());
                assertThat(leased.activeRevision()).isEqualTo(initial.activeRevision());
                assertThat(leased.activeLeases()).isEqualTo(1);
                assertThat(lease.execute(TestResource::name)).isEqualTo("first");
            }
        } finally {
            managed.close();
        }

        assertThat(managed.awaitTermination(TEST_TIMEOUT)).isTrue();
        ManagedResourceStatus terminated = managed.status();
        assertThat(terminated.lifecycle()).isEqualTo(ManagedResourceStatus.Lifecycle.TERMINATED);
        assertThat(terminated.activeGeneration()).isZero();
        assertThat(terminated.activeRevision()).isZero();
        assertThat(terminated.activeLeases()).isZero();
        assertThat(terminated.replacementInProgress()).isFalse();
        assertThat(terminated.refreshPending()).isFalse();
    }

    @Test
    void staleCandidateIsNeverPublished() throws Exception {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        Map<String, TestResource> resources = new ConcurrentHashMap<>();
        CountDownLatch secondFactoryEntered = new CountDownLatch(1);
        CountDownLatch releaseSecondFactory = new CountDownLatch(1);
        CountDownLatch thirdFactoryEntered = new CountDownLatch(1);
        CountDownLatch releaseThirdFactory = new CountDownLatch(1);

        ResourceFactory<String, TestResource> factory = configuration -> {
            if (configuration.equals("second")) {
                secondFactoryEntered.countDown();
                releaseSecondFactory.await();
            } else if (configuration.equals("third")) {
                thirdFactoryEntered.countDown();
                releaseThirdFactory.await();
            }
            return resources.computeIfAbsent(configuration, TestResource::new);
        };

        try (ManagedResource<TestResource, String> managed = managed(
                source,
                factory,
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                TEST_TIMEOUT);
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var secondUpdate = executor.submit(() -> source.update("second"));
            assertThat(secondFactoryEntered.await(1, TimeUnit.SECONDS)).isTrue();

            var thirdUpdate = executor.submit(() -> source.update("third"));
            releaseSecondFactory.countDown();
            assertThat(thirdFactoryEntered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(managed.execute(TestResource::name)).isEqualTo("first");
            await(() -> resources.containsKey("second") && resources.get("second").closed());

            releaseThirdFactory.countDown();
            secondUpdate.get();
            thirdUpdate.get();
            awaitActive(managed, "third");
            assertThat(managed.execute(TestResource::name)).isEqualTo("third");
        }
    }

    @Test
    void synchronousSubscriptionCallbackCannotLeakItsSubscriptionToken() {
        AtomicBoolean subscriptionClosed = new AtomicBoolean();
        ConfigurationSource<String> source = new ConfigurationSource<>() {
            @Override
            public ConfigurationSnapshot<String> snapshot() {
                return ConfigurationSnapshot.of(1, "bad");
            }

            @Override
            public Subscription subscribe(Runnable listener) {
                listener.run();
                return () -> subscriptionClosed.set(true);
            }
        };

        assertThatThrownBy(() -> ManagedResource.builder(source, binding(
                TestResource::new,
                ignored -> {
                    throw new IOException("vendor secret");
                },
                TestResource::close))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANDIDATE_HEALTH_CHECK", IOException.class.getName())
                .hasMessageNotContaining("secret");
        assertThat(subscriptionClosed).isTrue();
    }

    @Test
    void failedSubscriptionDoesNotCreateAResourceFromItsSynchronousSignal() {
        AtomicInteger creations = new AtomicInteger();
        AtomicReference<TestResource> published = new AtomicReference<>();
        AtomicReference<Runnable> retainedListener = new AtomicReference<>();
        ConfigurationSource<String> source = new ConfigurationSource<>() {
            @Override
            public ConfigurationSnapshot<String> snapshot() {
                return ConfigurationSnapshot.of(1, "first");
            }

            @Override
            public Subscription subscribe(Runnable listener) {
                retainedListener.set(listener);
                listener.run();
                return null;
            }
        };

        assertThatThrownBy(() -> ManagedResource.builder(source, binding(
                configuration -> {
                    creations.incrementAndGet();
                    TestResource resource = new TestResource(configuration);
                    published.set(resource);
                    return resource;
                },
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FailureSnapshot.Stage.CONFIGURATION_SOURCE.name());

        assertThat(published).hasNullValue();
        retainedListener.get().run();
        assertThat(creations).hasValue(0);
    }

    @Test
    void revisionDrivesRefreshWithoutCallingConfigurationEquality() {
        ExplodingConfiguration initial = new ExplodingConfiguration("first");
        MutableConfigurationSource<ExplodingConfiguration> source =
                new MutableConfigurationSource<>(initial);

        try (ManagedResource<TestResource, ExplodingConfiguration> managed = managed(
                source,
                configuration -> new TestResource(configuration.id()),
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                TEST_TIMEOUT)) {
            initial.explodeOnEquals();
            source.update(new ExplodingConfiguration("second"));

            await(() -> managed.status().activeRevision() == 2);
            assertThat(managed.execute(TestResource::name)).isEqualTo("second");
            assertThat(managed.status().desiredRevision()).isEqualTo(2);
            assertThat(managed.status().lifecycle())
                    .isEqualTo(ManagedResourceStatus.Lifecycle.RUNNING);
        }
    }

    @Test
    void duplicateAndOutOfOrderRevisionsDoNotReplaceTheActiveGeneration() {
        TestConfigurationSource<String> source = new TestConfigurationSource<>(
                ConfigurationSnapshot.of(1, "first"));
        AtomicInteger creations = new AtomicInteger();

        try (ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> {
                    creations.incrementAndGet();
                    return new TestResource(configuration);
                },
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                TEST_TIMEOUT)) {
            source.publish(ConfigurationSnapshot.of(3, "latest"));
            await(() -> managed.status().activeRevision() == 3);
            source.publish(ConfigurationSnapshot.of(3, "duplicate-with-different-payload"));
            source.publish(ConfigurationSnapshot.of(2, "stale"));

            assertThat(managed.awaitIdle(TEST_TIMEOUT)).isTrue();
            assertThat(creations).hasValue(2);
            assertThat(managed.status().activeRevision()).isEqualTo(3);
            assertThat(managed.status().desiredRevision()).isEqualTo(3);
            assertThat(managed.lastRefreshFailure()).isEmpty();
        }
    }

    @Test
    void initialSnapshotFailureAbortsStartupAndClosesTheSubscription() {
        AtomicBoolean subscriptionClosed = new AtomicBoolean();
        ConfigurationSource<String> source = new ConfigurationSource<>() {
            @Override
            public ConfigurationSnapshot<String> snapshot() {
                throw new IllegalStateException("provider secret");
            }

            @Override
            public Subscription subscribe(Runnable listener) {
                return () -> subscriptionClosed.set(true);
            }
        };

        assertThatThrownBy(() -> managed(
                source,
                TestResource::new,
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                TEST_TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FailureSnapshot.Stage.CONFIGURATION_SOURCE.name())
                .hasMessageNotContaining("secret");
        assertThat(subscriptionClosed).isTrue();
    }

    @Test
    void awaitIdleObservesTheGenerationReplacedByRefresh() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);

        try (ManagedResource<TestResource, String> managed = managed(
                source,
                TestResource::new,
                ignored -> Health.healthy(ProbeScope.LOCAL),
                resource -> {
                    if (resource.name().equals("first")) {
                        closeEntered.countDown();
                        releaseClose.await();
                    }
                    resource.close();
                },
                TEST_TIMEOUT)) {
            source.update("second");

            assertThat(awaitLatch(closeEntered)).isTrue();
            assertThat(managed.status().activeRevision()).isEqualTo(2);
            assertThat(managed.awaitIdle(Duration.ZERO)).isFalse();

            releaseClose.countDown();
            assertThat(managed.awaitIdle(TEST_TIMEOUT)).isTrue();
        }
    }

    @Test
    void awaitIdleIncludesRefreshDeferredByADrainingGeneration() throws Exception {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        CountDownLatch latestFactoryEntered = new CountDownLatch(1);
        CountDownLatch releaseLatestFactory = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);

        try (ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> {
                    if (configuration.equals("latest")) {
                        latestFactoryEntered.countDown();
                        releaseLatestFactory.await();
                    }
                    return new TestResource(configuration);
                },
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                TEST_TIMEOUT);
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            ManagedResource.Lease<TestResource> firstLease = managed.acquire();
            source.update("second");
            awaitActive(managed, "second");
            source.update("latest");
            assertThat(managed.status().refreshPending()).isTrue();

            var waiting = executor.submit(() -> {
                waiterStarted.countDown();
                return managed.awaitIdle(TEST_TIMEOUT);
            });
            assertThat(awaitLatch(waiterStarted)).isTrue();

            try {
                firstLease.close();
                assertThat(awaitLatch(latestFactoryEntered)).isTrue();
                assertThatThrownBy(() -> waiting.get(50, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                firstLease.close();
                releaseLatestFactory.countDown();
            }

            assertThat(waiting.get()).isTrue();
            assertThat(managed.execute(TestResource::name)).isEqualTo("latest");
            assertThat(managed.status().refreshPending()).isFalse();
        }
    }

    @Test
    void closeTimeoutDoesNotForceCloseAnInUseResource() throws Exception {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        TestResource resource = new TestResource("first");
        ManagedResource<TestResource, String> managed = managed(
                source,
                ignored -> resource,
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                Duration.ofMillis(20));
        ManagedResource.Lease<TestResource> lease = managed.acquire();

        managed.close();

        assertThat(managed.isTerminated()).isFalse();
        assertThat(managed.awaitTermination(Duration.ZERO)).isFalse();
        assertThat(resource.closed()).isFalse();
        assertThat(lease.execute(TestResource::name)).isEqualTo("first");
        assertThatThrownBy(managed::acquire).isInstanceOf(IllegalStateException.class);

        lease.close();
        assertThat(managed.awaitTermination(TEST_TIMEOUT)).isTrue();
        assertThat(managed.awaitTermination(Duration.ZERO)).isTrue();
        assertThat(resource.closed()).isTrue();
    }

    @Test
    void closeIsBoundedWhileARefreshFactoryIsBlocked() throws Exception {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        AtomicReference<TestResource> candidate = new AtomicReference<>();
        ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> {
                    TestResource resource = new TestResource(configuration);
                    if (configuration.equals("second")) {
                        candidate.set(resource);
                        factoryEntered.countDown();
                        releaseFactory.await();
                    }
                    return resource;
                },
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                Duration.ofMillis(100));

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var refresh = executor.submit(() -> source.update("second"));
            assertThat(awaitLatch(factoryEntered)).isTrue();

            long startedAt = System.nanoTime();
            managed.close();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
            assertThat(refresh.isDone()).isTrue();
            assertThat(managed.isTerminated()).isFalse();

            releaseFactory.countDown();
            refresh.get();
        }

        await(() -> candidate.get() != null && candidate.get().closed());
        assertThat(managed.awaitTermination(TEST_TIMEOUT)).isTrue();
    }

    @Test
    void terminationIncludesAnAdmittedManualRefresh() throws Exception {
        AtomicReference<ConfigurationSnapshot<String>> desired =
                new AtomicReference<>(ConfigurationSnapshot.of(1, "first"));
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        AtomicReference<TestResource> candidate = new AtomicReference<>();
        ConfigurationSource<String> source = new ConfigurationSource<>() {
            @Override
            public ConfigurationSnapshot<String> snapshot() {
                return desired.get();
            }

            @Override
            public Subscription subscribe(Runnable listener) {
                return () -> {
                };
            }
        };
        ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> {
                    TestResource resource = new TestResource(configuration);
                    if (configuration.equals("second")) {
                        candidate.set(resource);
                        factoryEntered.countDown();
                        releaseFactory.await();
                    }
                    return resource;
                },
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                Duration.ofMillis(20));

        try {
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                desired.set(ConfigurationSnapshot.of(2, "second"));
                var refresh = executor.submit(managed::refresh);
                try {
                    assertThat(awaitLatch(factoryEntered)).isTrue();

                    managed.close();
                    assertThat(managed.isTerminated()).isFalse();
                    assertThat(managed.awaitTermination(Duration.ZERO)).isFalse();
                } finally {
                    releaseFactory.countDown();
                }
                refresh.get();
            }

            assertThat(candidate.get().closed()).isTrue();
            assertThat(managed.awaitTermination(TEST_TIMEOUT)).isTrue();
        } finally {
            releaseFactory.countDown();
            managed.close();
        }
    }

    @Test
    void reentrantCloseFromFactoryCannotRepublishItsCandidate() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        AtomicReference<ManagedResource<TestResource, String>> reference = new AtomicReference<>();
        AtomicReference<TestResource> candidate = new AtomicReference<>();
        ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> {
                    TestResource resource = new TestResource(configuration);
                    if (configuration.equals("second")) {
                        candidate.set(resource);
                        reference.get().close();
                    }
                    return resource;
                },
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                Duration.ofMillis(100));
        reference.set(managed);

        source.update("second");

        await(() -> candidate.get() != null && candidate.get().closed());
        assertThat(managed.status().activeGeneration()).isZero();
        assertThat(managed.awaitTermination(TEST_TIMEOUT)).isTrue();
    }

    @Test
    void awaitIdleWaitsForAnInProgressRefresh() throws Exception {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        ManagedResource<TestResource, String> managed = managed(
                source,
                configuration -> {
                    if (configuration.equals("second")) {
                        factoryEntered.countDown();
                        releaseFactory.await();
                    }
                    return new TestResource(configuration);
                },
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                TEST_TIMEOUT);

        try (managed;
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var refresh = executor.submit(() -> source.update("second"));
            assertThat(awaitLatch(factoryEntered)).isTrue();

            assertThat(managed.awaitIdle(Duration.ofMillis(20))).isFalse();

            releaseFactory.countDown();
            refresh.get();
            assertThat(managed.awaitIdle(TEST_TIMEOUT)).isTrue();
        }
    }

    @Test
    void terminationIncludesConfigurationSubscriptionShutdown() throws Exception {
        CountDownLatch subscriptionCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseSubscriptionClose = new CountDownLatch(1);
        ConfigurationSource<String> source = new ConfigurationSource<>() {
            @Override
            public ConfigurationSnapshot<String> snapshot() {
                return ConfigurationSnapshot.of(1, "first");
            }

            @Override
            public Subscription subscribe(Runnable listener) {
                return () -> {
                    subscriptionCloseEntered.countDown();
                    try {
                        releaseSubscriptionClose.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                };
            }
        };
        ManagedResource<TestResource, String> managed = managed(
                source,
                TestResource::new,
                ignored -> Health.healthy(ProbeScope.LOCAL),
                TestResource::close,
                Duration.ofSeconds(1));

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var firstClose = executor.submit(managed::close);
            assertThat(awaitLatch(subscriptionCloseEntered)).isTrue();
            assertThat(managed.isTerminated()).isFalse();
            assertThat(managed.awaitTermination(Duration.ZERO)).isFalse();

            var secondClose = executor.submit(managed::close);
            assertThatThrownBy(() -> secondClose.get(50, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            releaseSubscriptionClose.countDown();
            firstClose.get();
            secondClose.get();
        }

        assertThat(managed.awaitTermination(TEST_TIMEOUT)).isTrue();
    }

    @Test
    void lateCloseFailuresAreRedactedAndRemainObservable() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        ManagedResource<TestResource, String> managed = managed(
                source,
                TestResource::new,
                ignored -> Health.healthy(ProbeScope.LOCAL),
                resource -> {
                    closeEntered.countDown();
                    releaseClose.await();
                    throw new IOException("vendor token=secret");
                },
                Duration.ofMillis(20));

        managed.close();
        assertThat(awaitLatch(closeEntered)).isTrue();
        assertThat(managed.isTerminated()).isFalse();

        releaseClose.countDown();
        assertThat(managed.awaitTermination(TEST_TIMEOUT)).isTrue();
        assertThat(managed.closeFailures()).singleElement().satisfies(failure -> {
            assertThat(failure.stage()).isEqualTo(FailureSnapshot.Stage.RESOURCE_CLOSE);
            assertThat(failure.failureType()).isEqualTo(IOException.class.getName());
            assertThat(failure.generation()).isPositive();
            assertThat(failure.revision()).isEqualTo(1);
            assertThat(failure.toString()).doesNotContain("secret", "token");
        });
        assertThat(FailureSnapshot.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(FailureSnapshot.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType))
                .noneMatch(Throwable.class::isAssignableFrom)
                .noneMatch(Class.class::isAssignableFrom)
                .noneMatch(ClassLoader.class::isAssignableFrom);
    }

    @Test
    void closerErrorClosesAnUnhealthyCandidateOnceAndTerminationStillCompletes() {
        AtomicReference<ConfigurationSnapshot<String>> desired =
                new AtomicReference<>(ConfigurationSnapshot.of(1, "first"));
        ConfigurationSource<String> source = new ConfigurationSource<>() {
            @Override
            public ConfigurationSnapshot<String> snapshot() {
                return desired.get();
            }

            @Override
            public Subscription subscribe(Runnable listener) {
                return () -> {
                };
            }
        };
        ManagedResource<TestResource, String> managed = managed(
                source,
                TestResource::new,
                resource -> resource.name().equals("bad")
                        ? Health.unhealthy(ProbeScope.LOCAL)
                        : Health.healthy(ProbeScope.LOCAL),
                resource -> {
                    throw new AssertionError("vendor secret");
                },
                TEST_TIMEOUT);

        desired.set(ConfigurationSnapshot.of(2, "bad"));
        assertThatThrownBy(managed::refresh)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("vendor secret");
        assertThat(managed.status().lifecycle())
                .isEqualTo(ManagedResourceStatus.Lifecycle.REFRESH_DISABLED);
        assertThat(managed.lastRefreshFailure()).hasValueSatisfying(failure -> {
            assertThat(failure.stage()).isEqualTo(FailureSnapshot.Stage.CANDIDATE_CLOSE);
            assertThat(failure.failureType()).isEqualTo(AssertionError.class.getName());
            assertThat(failure.generation()).isPositive();
            assertThat(failure.revision()).isEqualTo(2);
        });
        assertThat(managed.closeFailures()).singleElement().satisfies(failure ->
                assertThat(failure.stage()).isEqualTo(FailureSnapshot.Stage.CANDIDATE_CLOSE));

        managed.close();

        assertThat(managed.awaitTermination(TEST_TIMEOUT)).isTrue();
        assertThat(managed.closeFailures())
                .extracting(FailureSnapshot::stage)
                .containsExactly(
                        FailureSnapshot.Stage.CANDIDATE_CLOSE,
                        FailureSnapshot.Stage.RESOURCE_CLOSE);
        assertThat(managed.closeFailures()).allSatisfy(failure -> {
            assertThat(failure.failureType()).isEqualTo(AssertionError.class.getName());
            assertThat(failure.generation()).isPositive();
            assertThat(failure.revision()).isPositive();
            assertThat(failure.toString()).doesNotContain("secret");
        });
    }

    @Test
    void concurrentCloseIsIdempotent() throws Exception {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        AtomicInteger closes = new AtomicInteger();
        ManagedResource<TestResource, String> managed = managed(
                source,
                TestResource::new,
                ignored -> Health.healthy(ProbeScope.LOCAL),
                resource -> closes.incrementAndGet(),
                TEST_TIMEOUT);

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(managed::close);
            var second = executor.submit(managed::close);
            var third = executor.submit(managed::close);
            first.get();
            second.get();
            third.get();
        }

        assertThat(managed.awaitTermination(TEST_TIMEOUT)).isTrue();
        assertThat(closes).hasValue(1);
    }

    @Test
    void acquireAndCloseRaceNeverReturnsAnAlreadyClosedResource() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int iteration = 0; iteration < 100; iteration++) {
                MutableConfigurationSource<Integer> source =
                        new MutableConfigurationSource<>(iteration);
                ManagedResource<TestResource, Integer> managed = managed(
                        source,
                        configuration -> new TestResource(configuration.toString()),
                        ignored -> Health.healthy(ProbeScope.LOCAL),
                        TestResource::close,
                        Duration.ofMillis(100));
                CountDownLatch start = new CountDownLatch(1);

                var acquiring = executor.submit(() -> {
                    start.await();
                    try (ManagedResource.Lease<TestResource> lease = managed.acquire()) {
                        return lease.execute(resource -> !resource.closed());
                    } catch (IllegalStateException alreadyClosed) {
                        return true;
                    }
                });
                var closing = executor.submit(() -> {
                    start.await();
                    managed.close();
                    return null;
                });
                start.countDown();

                assertThat(acquiring.get()).isTrue();
                closing.get();
                assertThat(managed.awaitTermination(TEST_TIMEOUT)).isTrue();
            }
        }
    }

    @Test
    void closeFailuresAreBounded() {
        MutableConfigurationSource<Integer> source = new MutableConfigurationSource<>(0);
        AtomicInteger closedResources = new AtomicInteger();

        try (ManagedResource<TestResource, Integer> managed = managed(
                source,
                configuration -> new TestResource(configuration.toString()),
                ignored -> Health.healthy(ProbeScope.LOCAL),
                resource -> {
                    closedResources.incrementAndGet();
                    throw new IOException("not retained");
                },
                TEST_TIMEOUT)) {
            for (int configuration = 1; configuration <= 110; configuration++) {
                source.update(configuration);
                awaitActive(managed, configuration);
                assertThat(managed.awaitIdle(TEST_TIMEOUT)).isTrue();
            }
            await(() -> closedResources.get() >= 110);
            assertThat(managed.closeFailures()).hasSize(100);
        }
    }

    private static <C> ManagedResource<TestResource, C> managed(
            ConfigurationSource<C> source,
            ResourceFactory<C, TestResource> factory,
            HealthCheck<TestResource> healthCheck,
            ResourceCloser<TestResource> closer,
            Duration closeWaitTimeout) {
        return ManagedResource.<TestResource, C>builder(
                        source, binding(factory, healthCheck, closer))
                .closeWaitTimeout(closeWaitTimeout)
                .build();
    }

    private static final class ControlledLifecycleRuntime implements LifecycleRuntime {

        private final Instant currentTime;
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean systemDispatch;

        private ControlledLifecycleRuntime(Instant currentTime) {
            this.currentTime = currentTime;
        }

        @Override
        public Instant now() {
            return currentTime;
        }

        @Override
        public void start(String name, Runnable task) {
            synchronized (tasks) {
                if (!systemDispatch) {
                    tasks.add(task);
                    return;
                }
            }
            LifecycleRuntime.system().start(name, task);
        }

        private int pendingTasks() {
            synchronized (tasks) {
                return tasks.size();
            }
        }

        private void runUntilIdle() {
            while (true) {
                Runnable task;
                synchronized (tasks) {
                    task = tasks.poll();
                }
                if (task == null) {
                    return;
                }
                task.run();
            }
        }

        private void useSystemDispatch() {
            synchronized (tasks) {
                if (!tasks.isEmpty()) {
                    throw new IllegalStateException("Controlled lifecycle tasks remain pending");
                }
                systemDispatch = true;
            }
        }
    }

    private static <C> ResourceBinding<C, TestResource> binding(
            ResourceFactory<C, TestResource> factory,
            HealthCheck<TestResource> healthCheck,
            ResourceCloser<TestResource> closer) {
        return new ResourceBinding<>() {
            @Override
            public TestResource create(C configuration) throws Exception {
                return factory.create(configuration);
            }

            @Override
            public Health check(TestResource resource) throws Exception {
                return healthCheck.check(resource);
            }

            @Override
            public void close(TestResource resource) throws Exception {
                closer.close(resource);
            }
        };
    }

    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static <C> void awaitActive(
            ManagedResource<TestResource, C> managed,
            C configuration) {
        await(() -> managed.execute(TestResource::name)
                .equals(String.valueOf(configuration)));
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Condition was not met before the timeout");
            }
            Thread.onSpinWait();
        }
    }

    private static final class TestConfigurationSource<C> implements ConfigurationSource<C> {

        private final AtomicReference<ConfigurationSnapshot<C>> current;
        private final AtomicReference<Runnable> listener = new AtomicReference<>();

        private TestConfigurationSource(ConfigurationSnapshot<C> initial) {
            current = new AtomicReference<>(initial);
        }

        @Override
        public ConfigurationSnapshot<C> snapshot() {
            return current.get();
        }

        @Override
        public Subscription subscribe(Runnable subscribedListener) {
            listener.set(subscribedListener);
            return () -> listener.compareAndSet(subscribedListener, null);
        }

        private void publish(ConfigurationSnapshot<C> snapshot) {
            current.set(snapshot);
            Runnable currentListener = listener.get();
            if (currentListener != null) {
                currentListener.run();
            }
        }
    }

    private static final class TestResource {

        private final String name;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TestResource(String name) {
            this.name = name;
        }

        private String name() {
            return name;
        }

        private boolean closed() {
            return closed.get();
        }

        private void close() {
            closed.set(true);
        }
    }

    private static final class ExplodingConfiguration {

        private final String id;
        private final AtomicBoolean explode = new AtomicBoolean();

        private ExplodingConfiguration(String id) {
            this.id = id;
        }

        private String id() {
            return id;
        }

        private void explodeOnEquals() {
            explode.set(true);
        }

        @Override
        public boolean equals(Object other) {
            if (explode.get()) {
                throw new AssertionError("configuration secret");
            }
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
