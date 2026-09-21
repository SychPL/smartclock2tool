package pl.mateusz.clockadbprobe;

/**
 * Serializes the operations that must not overlap.
 *
 * <p>The lock has an owner and a stage (SPEC 0.12 pkt 4.5): only the owner can move the stage or release it, so an
 * unbalanced release from another thread cannot free somebody else's work. Exclusion is strict, the owner included:
 * a second acquire is always a refusal, never re-entry, because every acquire means "start new work".
 *
 * <p>This lock lives in memory and therefore dies with the process. Work that outlives the process is tracked by the
 * executor trace, and Executions is the only place allowed to combine the two.
 */
public final class OperationGate {
    private static final String PROBE = "probe";

    private static String owner;
    private static String stage = "";

    private OperationGate() {}

    public static synchronized boolean acquire(String ownerId, String newStage) {
        if (ownerId == null || owner != null) return false;
        owner = ownerId;
        stage = newStage == null ? "" : newStage;
        return true;
    }

    public static synchronized void stage(String ownerId, String newStage) {
        if (ownerId != null && ownerId.equals(owner)) stage = newStage == null ? "" : newStage;
    }

    public static synchronized void release(String ownerId) {
        if (ownerId != null && ownerId.equals(owner)) {
            owner = null;
            stage = "";
        }
    }

    public static synchronized String ownerId() {
        return owner;
    }

    public static synchronized String stageOf() {
        return stage;
    }

    public static synchronized boolean isBusy() {
        return owner != null;
    }

    /** The pre-bridge entry point: one well-known owner, the same exclusion as before. */
    public static boolean tryStartProbe() {
        return acquire(PROBE, "running");
    }

    public static void finishProbe() {
        release(PROBE);
    }
}
