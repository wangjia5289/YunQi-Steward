package yunqi.zhibei.steward.adapter.configuration.nacos3;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class FakeNacosConfigService {

    private final String initialContent;
    private final AtomicReference<Listener> listener = new AtomicReference<>();
    private final AtomicInteger removals = new AtomicInteger();
    private final AtomicInteger shutdowns = new AtomicInteger();
    private final ConfigService service;

    FakeNacosConfigService(String initialContent) {
        this.initialContent = initialContent;
        service = ConfigService.class.cast(Proxy.newProxyInstance(
                ConfigService.class.getClassLoader(),
                new Class<?>[]{ConfigService.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getConfigAndSignListener" -> {
                        listener.set((Listener) arguments[3]);
                        yield this.initialContent;
                    }
                    case "removeListener" -> {
                        removals.incrementAndGet();
                        listener.compareAndSet((Listener) arguments[2], null);
                        yield null;
                    }
                    case "shutDown" -> {
                        shutdowns.incrementAndGet();
                        yield null;
                    }
                    case "toString" -> "ConfigServiceProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                }));
    }

    ConfigService service() {
        return service;
    }

    boolean hasListener() {
        return listener.get() != null;
    }

    int removals() {
        return removals.get();
    }

    int shutdowns() {
        return shutdowns.get();
    }

    void emit(String content) {
        Listener current = listener.get();
        if (current != null) {
            current.getExecutor().execute(() -> current.receiveConfigInfo(content));
        }
    }
}
