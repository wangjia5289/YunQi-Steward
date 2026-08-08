package yunqi.zhibei.steward.refresh;

import java.time.Instant;

/** Internal execution and time boundary for managed lifecycle coordination. */
interface LifecycleRuntime {

    Instant now();

    /** Admits a task for execution; an exception means the task was not started. */
    void start(String name, Runnable task);

    static LifecycleRuntime system() {
        return SystemLifecycleRuntime.INSTANCE;
    }
}

enum SystemLifecycleRuntime implements LifecycleRuntime {
    INSTANCE;

    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public void start(String name, Runnable task) {
        Thread.ofVirtual().name(name).start(task);
    }
}
