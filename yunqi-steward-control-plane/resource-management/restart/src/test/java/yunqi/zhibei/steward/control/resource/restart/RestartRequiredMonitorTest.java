package yunqi.zhibei.steward.control.resource.restart;

import yunqi.zhibei.steward.control.configuration.ConfigurationSnapshot;
import yunqi.zhibei.steward.control.configuration.ConfigurationSource;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestartRequiredMonitorTest {

    @Test
    void remainsCurrentUntilANewerCompleteRevisionIsObserved() {
        TestSource source = new TestSource(1, "initial-secret");

        try (RestartRequiredMonitor<String> monitor = RestartRequiredMonitor.watch(source, 1)) {
            RestartRequiredStatus initial = monitor.status();
            assertThat(initial.state()).isEqualTo(RestartRequiredStatus.State.CURRENT);
            assertThat(initial.restartRequired()).isFalse();
            assertThat(initial.appliedRevision()).isEqualTo(1);
            assertThat(initial.desiredRevision()).isEqualTo(1);
            assertThat(initial.lastFailure()).isEmpty();

            source.publish(2, "updated-secret");

            RestartRequiredStatus updated = monitor.status();
            assertThat(updated.state()).isEqualTo(RestartRequiredStatus.State.RESTART_REQUIRED);
            assertThat(updated.restartRequired()).isTrue();
            assertThat(updated.appliedRevision()).isEqualTo(1);
            assertThat(updated.desiredRevision()).isEqualTo(2);
            assertThat(updated.toString())
                    .doesNotContain("initial-secret", "updated-secret");
        }
    }

    @Test
    void subscribeThenReadDetectsAnUpdatePublishedBeforeWatchReturns() {
        TestSource source = new TestSource(1, "initial");
        source.publish(4, "newest");

        try (RestartRequiredMonitor<String> monitor = RestartRequiredMonitor.watch(source, 1)) {
            assertThat(monitor.status().state())
                    .isEqualTo(RestartRequiredStatus.State.RESTART_REQUIRED);
            assertThat(monitor.status().desiredRevision()).isEqualTo(4);
        }
    }

    @Test
    void duplicateAndOutOfOrderObservationsCannotMoveDesiredRevisionBackward() {
        TestSource source = new TestSource(3, "applied");

        try (RestartRequiredMonitor<String> monitor = RestartRequiredMonitor.watch(source, 3)) {
            source.publish(5, "newest");
            source.publish(5, "duplicate");
            source.publish(2, "stale");

            assertThat(monitor.status().desiredRevision()).isEqualTo(5);
            assertThat(monitor.status().restartRequired()).isTrue();
        }
    }

    @Test
    void sourceFailureIsRedactedAndACompleteObservationClearsIt() {
        TestSource source = new TestSource(1, "initial");
        source.fail(new IllegalStateException("vault-token-secret"));

        try (RestartRequiredMonitor<String> monitor = RestartRequiredMonitor.watch(source, 1)) {
            RestartRequiredStatus failed = monitor.status();
            assertThat(failed.state()).isEqualTo(RestartRequiredStatus.State.CURRENT);
            assertThat(failed.lastFailure()).hasValueSatisfying(failure -> {
                assertThat(failure.stage())
                        .isEqualTo(RestartRequiredFailure.Stage.CONFIGURATION_SOURCE);
                assertThat(failure.failureType())
                        .isEqualTo(IllegalStateException.class.getName());
                assertThat(failure.toString()).doesNotContain("vault-token-secret");
            });

            source.recover(1, "initial");

            assertThat(monitor.status().state()).isEqualTo(RestartRequiredStatus.State.CURRENT);
            assertThat(monitor.status().lastFailure()).isEmpty();
        }
    }

    @Test
    void sourceFailureCannotClearAnExistingRestartRequirement() {
        TestSource source = new TestSource(1, "initial");

        try (RestartRequiredMonitor<String> monitor = RestartRequiredMonitor.watch(source, 1)) {
            source.publish(2, "updated");
            source.fail(new IllegalArgumentException("secret-value"));

            RestartRequiredStatus status = monitor.status();
            assertThat(status.state()).isEqualTo(RestartRequiredStatus.State.RESTART_REQUIRED);
            assertThat(status.desiredRevision()).isEqualTo(2);
            assertThat(status.lastFailure()).isPresent();
            assertThat(status.toString()).doesNotContain("secret-value");
        }
    }

    @Test
    void closeIsIdempotentAndAClosingCallbackCannotRepublishState() {
        TestSource source = new TestSource(1, "initial");
        RestartRequiredMonitor<String> monitor = RestartRequiredMonitor.watch(source, 1);
        source.signalRevisionDuringClose = 2;

        monitor.close();
        monitor.close();

        RestartRequiredStatus closed = monitor.status();
        assertThat(closed.state()).isEqualTo(RestartRequiredStatus.State.CLOSED);
        assertThat(closed.restartRequired()).isFalse();
        assertThat(closed.desiredRevision()).isEqualTo(1);
        assertThat(source.subscriptionCloses).hasValue(1);
    }

    @Test
    void subscriptionCloseFailureIsTerminalRedactedAndNotRetried() {
        TestSource source = new TestSource(1, "initial");
        RestartRequiredMonitor<String> monitor = RestartRequiredMonitor.watch(source, 1);
        source.closeFailure = new IllegalStateException("subscription-secret");

        assertThatThrownBy(monitor::close)
                .isSameAs(source.closeFailure);
        monitor.close();

        RestartRequiredStatus closed = monitor.status();
        assertThat(closed.state()).isEqualTo(RestartRequiredStatus.State.CLOSED);
        assertThat(closed.lastFailure()).hasValueSatisfying(failure -> {
            assertThat(failure.stage())
                    .isEqualTo(RestartRequiredFailure.Stage.SUBSCRIPTION_CLOSE);
            assertThat(failure.failureType()).isEqualTo(IllegalStateException.class.getName());
            assertThat(failure.toString()).doesNotContain("subscription-secret");
        });
        assertThat(source.subscriptionCloses).hasValue(1);
    }

    @Test
    void validatesTheAppliedRevisionAndSubscriptionContract() {
        TestSource source = new TestSource(1, "initial");

        assertThatThrownBy(() -> RestartRequiredMonitor.watch(null, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("source");
        assertThatThrownBy(() -> RestartRequiredMonitor.watch(source, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appliedRevision must be at least 1");

        ConfigurationSource<String> failedSubscription = new ConfigurationSource<>() {
            @Override
            public ConfigurationSnapshot<String> snapshot() {
                return ConfigurationSnapshot.of(1, "initial");
            }

            @Override
            public Subscription subscribe(Runnable listener) {
                throw new IllegalStateException("subscription unavailable");
            }
        };
        assertThatThrownBy(() -> RestartRequiredMonitor.watch(failedSubscription, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("subscription unavailable");
    }

    private static final class TestSource implements ConfigurationSource<String> {

        private final AtomicInteger subscriptionCloses = new AtomicInteger();
        private ConfigurationSnapshot<String> current;
        private RuntimeException snapshotFailure;
        private RuntimeException closeFailure;
        private Runnable listener;
        private long signalRevisionDuringClose;

        private TestSource(long revision, String configuration) {
            current = ConfigurationSnapshot.of(revision, configuration);
        }

        @Override
        public synchronized ConfigurationSnapshot<String> snapshot() {
            if (snapshotFailure != null) {
                throw snapshotFailure;
            }
            return current;
        }

        @Override
        public synchronized Subscription subscribe(Runnable subscribedListener) {
            listener = subscribedListener;
            return () -> {
                Runnable closingSignal;
                synchronized (this) {
                    subscriptionCloses.incrementAndGet();
                    if (signalRevisionDuringClose > 0) {
                        current = ConfigurationSnapshot.of(
                                signalRevisionDuringClose,
                                "closing-update");
                    }
                    closingSignal = signalRevisionDuringClose > 0 ? listener : null;
                    listener = null;
                }
                if (closingSignal != null) {
                    closingSignal.run();
                }
                if (closeFailure != null) {
                    throw closeFailure;
                }
            };
        }

        private void publish(long revision, String configuration) {
            Runnable currentListener;
            synchronized (this) {
                current = ConfigurationSnapshot.of(revision, configuration);
                snapshotFailure = null;
                currentListener = listener;
            }
            if (currentListener != null) {
                currentListener.run();
            }
        }

        private void fail(RuntimeException failure) {
            Runnable currentListener;
            synchronized (this) {
                snapshotFailure = failure;
                currentListener = listener;
            }
            if (currentListener != null) {
                currentListener.run();
            }
        }

        private void recover(long revision, String configuration) {
            publish(revision, configuration);
        }
    }
}
