package pl.mateusz.clockadbprobe;

/** Serializes the operations that must not overlap. */
final class OperationGate {
    private static final int IDLE = 0;
    private static final int PROBE = 1;

    private static int owner = IDLE;

    private OperationGate() {}

    static synchronized boolean tryStartProbe() {
        if (owner != IDLE) return false;
        owner = PROBE;
        return true;
    }

    static synchronized void finishProbe() {
        if (owner == PROBE) owner = IDLE;
    }

    static synchronized boolean isBusy() {
        return owner != IDLE;
    }
}
