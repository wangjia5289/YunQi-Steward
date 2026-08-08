package yunqi.zhibei.steward.binding.seata.tm.v2;

import io.seata.tm.TMClient;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** One-time JVM initialization for Seata TM 2.x, which exposes no closeable client instance. */
public final class SeataTm2 {
    private static final AtomicReference<SeataTm2Configuration> CONFIGURATION =
            new AtomicReference<>();

    private SeataTm2() {
    }

    public static synchronized void initialize(SeataTm2Configuration configuration) {
        SeataTm2Configuration requested = Objects.requireNonNull(configuration, "configuration");
        SeataTm2Configuration existing = CONFIGURATION.get();
        if (existing != null) {
            if (!existing.equals(requested)) {
                throw new IllegalStateException(
                        "Seata TM is already initialized with a different configuration");
            }
            return;
        }
        TMClient.init(requested.applicationId(), requested.transactionServiceGroup());
        CONFIGURATION.set(requested);
    }

    public static boolean isInitialized() {
        return CONFIGURATION.get() != null;
    }

    public static Optional<SeataTm2Configuration> configuration() {
        return Optional.ofNullable(CONFIGURATION.get());
    }
}
