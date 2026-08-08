package yunqi.zhibei.steward.lifecycle;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundResourceTest {

    @Test
    void exposesOneNativeResourceUntilApplicationShutdown() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        StartupBinding<String, NativeClient> binding =
                binding(closed, Health.healthy(ProbeScope.LOCAL));

        BoundResource<NativeClient> bound = BoundResource.start("startup", binding);

        assertThat(bound.state()).isEqualTo(BoundResource.State.OPEN);
        assertThat(bound.isClosed()).isFalse();
        assertThat(bound.resource().value()).isEqualTo("startup");
        assertThat(bound.health())
                .isEqualTo(Health.healthy(ProbeScope.LOCAL))
                .extracting(Health::scope)
                .isEqualTo(ProbeScope.LOCAL);
        bound.close();
        bound.close();

        assertThat(closed).isTrue();
        assertThat(bound.state()).isEqualTo(BoundResource.State.CLOSED);
        assertThat(bound.isClosed()).isTrue();
        assertThatThrownBy(bound::resource).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closeFailureIsTerminalAndIsNotRetried() throws Exception {
        AtomicInteger closeAttempts = new AtomicInteger();
        StartupBinding<String, NativeClient> binding = new StartupBinding<>() {
            @Override
            public NativeClient create(String configuration) {
                return new NativeClient(configuration);
            }

            @Override
            public Health check(NativeClient resource) {
                return Health.healthy(ProbeScope.LOCAL);
            }

            @Override
            public void close(NativeClient resource) throws Exception {
                closeAttempts.incrementAndGet();
                throw new IOException("close");
            }
        };
        BoundResource<NativeClient> bound = BoundResource.start("startup", binding);

        assertThatThrownBy(bound::close)
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class);
        bound.close();

        assertThat(closeAttempts).hasValue(1);
        assertThat(bound.state()).isEqualTo(BoundResource.State.CLOSE_FAILED);
        assertThat(bound.isClosed()).isTrue();
    }

    @Test
    void closesAnUnhealthyStartupCandidate() {
        AtomicBoolean closed = new AtomicBoolean();

        assertThatThrownBy(() -> BoundResource.start(
                "startup", binding(closed, Health.unhealthy(ProbeScope.LOCAL))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The bound resource is unhealthy");
        assertThat(closed).isTrue();
    }

    @Test
    void preservesStartupFailureAndAddsCloseFailureAsSuppressed() {
        IOException healthFailure = new IOException("health");
        IOException closeFailure = new IOException("close");
        ResourceBinding<String, NativeClient> binding = new ResourceBinding<>() {
            @Override
            public NativeClient create(String configuration) {
                return new NativeClient(configuration);
            }

            @Override
            public Health check(NativeClient resource) throws Exception {
                throw healthFailure;
            }

            @Override
            public void close(NativeClient resource) throws Exception {
                throw closeFailure;
            }
        };

        assertThatThrownBy(() -> BoundResource.start("startup", binding))
                .isSameAs(healthFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(closeFailure));
    }

    @Test
    void restoresInterruptWhenCreationIsInterrupted() {
        StartupBinding<String, NativeClient> binding = new StartupBinding<>() {
            @Override
            public NativeClient create(String configuration) throws Exception {
                throw new InterruptedException("create");
            }

            @Override
            public Health check(NativeClient resource) {
                return Health.healthy(ProbeScope.LOCAL);
            }

            @Override
            public void close(NativeClient resource) {
            }
        };

        try {
            assertThatThrownBy(() -> BoundResource.start("startup", binding))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void restoresInterruptWhenFailedCandidateCleanupIsInterrupted() {
        IOException healthFailure = new IOException("health");
        StartupBinding<String, NativeClient> binding = new StartupBinding<>() {
            @Override
            public NativeClient create(String configuration) {
                return new NativeClient(configuration);
            }

            @Override
            public Health check(NativeClient resource) throws Exception {
                throw healthFailure;
            }

            @Override
            public void close(NativeClient resource) throws Exception {
                throw new InterruptedException("close");
            }
        };

        try {
            assertThatThrownBy(() -> BoundResource.start("startup", binding))
                    .isSameAs(healthFailure)
                    .satisfies(failure -> assertThat(failure.getSuppressed())
                            .singleElement()
                            .isInstanceOf(InterruptedException.class));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void inProgressHealthProbeBlocksCloseUntilTheProbeReturns() throws Exception {
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        CountDownLatch closeAttempted = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        AtomicBoolean nativeCloseEntered = new AtomicBoolean();
        AtomicReference<Throwable> healthFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        StartupBinding<String, NativeClient> binding = new StartupBinding<>() {
            @Override
            public NativeClient create(String configuration) {
                return new NativeClient(configuration);
            }

            @Override
            public Health check(NativeClient resource) throws Exception {
                if (checks.incrementAndGet() > 1) {
                    probeEntered.countDown();
                    releaseProbe.await();
                }
                return Health.healthy(ProbeScope.LOCAL);
            }

            @Override
            public void close(NativeClient resource) {
                nativeCloseEntered.set(true);
            }
        };
        BoundResource<NativeClient> bound = BoundResource.start("startup", binding);
        Thread healthThread = Thread.ofPlatform().start(() -> {
            try {
                bound.health();
            } catch (Throwable failure) {
                healthFailure.set(failure);
            }
        });
        await(probeEntered);
        Thread closeThread = Thread.ofPlatform().start(() -> {
            closeAttempted.countDown();
            try {
                bound.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        });

        try {
            await(closeAttempted);
            awaitBlocked(closeThread);
            assertThat(nativeCloseEntered).isFalse();
        } finally {
            releaseProbe.countDown();
        }

        assertThat(healthThread.join(Duration.ofSeconds(5))).isTrue();
        assertThat(closeThread.join(Duration.ofSeconds(5))).isTrue();
        assertThat(healthFailure).hasNullValue();
        assertThat(closeFailure).hasNullValue();
        assertThat(nativeCloseEntered).isTrue();
        assertThat(bound.state()).isEqualTo(BoundResource.State.CLOSED);
    }

    @Test
    void healthWaitingBehindCloseFailsWithoutRunningANativeProbe() throws Exception {
        CountDownLatch nativeCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch healthAttempted = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        AtomicReference<Throwable> healthFailure = new AtomicReference<>();
        StartupBinding<String, NativeClient> binding = new StartupBinding<>() {
            @Override
            public NativeClient create(String configuration) {
                return new NativeClient(configuration);
            }

            @Override
            public Health check(NativeClient resource) {
                checks.incrementAndGet();
                return Health.healthy(ProbeScope.LOCAL);
            }

            @Override
            public void close(NativeClient resource) throws Exception {
                nativeCloseEntered.countDown();
                releaseClose.await();
            }
        };
        BoundResource<NativeClient> bound = BoundResource.start("startup", binding);
        Thread closeThread = Thread.ofPlatform().start(bound::close);
        await(nativeCloseEntered);
        Thread healthThread = Thread.ofPlatform().start(() -> {
            healthAttempted.countDown();
            try {
                bound.health();
            } catch (Throwable failure) {
                healthFailure.set(failure);
            }
        });

        try {
            await(healthAttempted);
            awaitBlocked(healthThread);
            assertThat(checks).hasValue(1);
        } finally {
            releaseClose.countDown();
        }

        assertThat(closeThread.join(Duration.ofSeconds(5))).isTrue();
        assertThat(healthThread.join(Duration.ofSeconds(5))).isTrue();
        assertThat(healthFailure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The bound resource is not open: CLOSED");
        assertThat(checks).hasValue(1);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.BLOCKED);
    }

    private static ResourceBinding<String, NativeClient> binding(
            AtomicBoolean closed,
            Health health) {
        return new ResourceBinding<>() {
            @Override
            public NativeClient create(String configuration) {
                return new NativeClient(configuration);
            }

            @Override
            public Health check(NativeClient resource) {
                return health;
            }

            @Override
            public void close(NativeClient resource) {
                closed.set(true);
            }
        };
    }

    private record NativeClient(String value) {
    }
}
