package pl.mateusz.clockadbprobe;

/** Durable one-attempt-per-boot gate for the optional root chain. */
final class AutoRootPolicy {
    static final String OFF = "off";
    static final String READY = "ready";
    static final String RUNNING = "running";
    static final String DONE = "done";
    static final String BLOCKED = "blocked";

    interface Store {
        String state();
        String bootId();
        boolean save(String state, String bootId);
    }

    enum Decision { START, SKIP, BLOCKED, STORAGE_FAILED }

    private final Store store;

    AutoRootPolicy(Store store) {
        this.store = store;
    }

    synchronized String state() {
        String state = store.state();
        return OFF.equals(state) || READY.equals(state) || RUNNING.equals(state)
                || DONE.equals(state) || BLOCKED.equals(state) ? state : BLOCKED;
    }

    synchronized boolean enable(String currentBootId) {
        if (RUNNING.equals(store.state())) return false;
        if (currentBootId == null || currentBootId.isEmpty()) return false;
        // Arm for the next boot even if BOOT_COMPLETED is delivered twice today.
        return store.save(READY, currentBootId);
    }

    synchronized boolean disable() {
        if (RUNNING.equals(store.state())) return false;
        return store.save(OFF, store.bootId());
    }

    synchronized Decision claim(String bootId) {
        String state = store.state();
        if (OFF.equals(state) || BLOCKED.equals(state)) return Decision.SKIP;
        if (!READY.equals(state) && !RUNNING.equals(state) && !DONE.equals(state)) {
            return store.save(BLOCKED, store.bootId()) ? Decision.BLOCKED : Decision.STORAGE_FAILED;
        }
        if (bootId == null || bootId.isEmpty()) {
            return store.save(BLOCKED, store.bootId()) ? Decision.BLOCKED : Decision.STORAGE_FAILED;
        }
        String previousBoot = store.bootId();
        if (READY.equals(state) && bootId.equals(previousBoot)) return Decision.SKIP;
        if (RUNNING.equals(state)) {
            if (bootId.equals(previousBoot)) return Decision.SKIP;
            return store.save(BLOCKED, previousBoot) ? Decision.BLOCKED : Decision.STORAGE_FAILED;
        }
        if (DONE.equals(state) && bootId.equals(previousBoot)) return Decision.SKIP;
        return store.save(RUNNING, bootId) ? Decision.START : Decision.STORAGE_FAILED;
    }

    synchronized boolean finish(String bootId, boolean success) {
        if (!RUNNING.equals(store.state()) || !bootId.equals(store.bootId())) return false;
        return store.save(success ? DONE : BLOCKED, bootId);
    }
}
