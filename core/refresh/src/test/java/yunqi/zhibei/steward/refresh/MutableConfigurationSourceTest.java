package yunqi.zhibei.steward.refresh;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MutableConfigurationSourceTest {

    @Test
    void publishesUpdatesUntilTheSubscriptionIsClosed() {
        MutableConfigurationSource<String> source = new MutableConfigurationSource<>("first");
        AtomicInteger notifications = new AtomicInteger();
        ConfigurationSource.Subscription subscription =
                source.subscribe(notifications::incrementAndGet);

        source.update("second");
        subscription.close();
        subscription.close();
        source.update("third");

        assertThat(source.snapshot().configuration()).isEqualTo("third");
        assertThat(source.snapshot().revision()).isEqualTo(3);
        assertThat(notifications).hasValue(1);
    }
}
