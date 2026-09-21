package pl.mateusz.clockadbprobe.bridge;

import org.json.JSONObject;

/**
 * The trace of work that can outlive the app process (SPEC 0.12 pkt 4.2, 4.5).
 *
 * <p>The in-memory lock dies with the process, a privileged child does not. This class writes a small file before
 * anything starts, so a later run can tell "nothing is running" from "something may still be running".
 *
 * <p>Order matters and is the whole point:
 * <ol>
 *   <li>{@link #reserve} writes the trace <em>before</em> any work begins,
 *   <li>{@link #promote} marks that a privileged child is about to start, also <em>before</em> it starts,
 *   <li>{@link #attach} records which process it is,
 *   <li>{@link #handOff} marks work passed to someone we do not own (the installer), again before handing it over,
 *   <li>{@link #handOffFailed} records that the hand-off provably never started,
 *   <li>{@link #done} clears the trace.
 * </ol>
 * The reverse order would let a crash look like "idle" while a privileged process is still running.
 */
public final class ExecutorLock {
    /** Work that happens inside this app only: copying a file, showing a consent screen. */
    public static final String IN_PROCESS = "in_process";
    /** Work done, or about to be done, by a privileged child. */
    public static final String PRIVILEGED = "privileged";

    public static final String IDLE = "idle";
    public static final String RUNNING = "running";
    public static final String UNKNOWN = "unknown";

    public enum Liveness { ALIVE, DEAD, UNKNOWN }

    public interface Files {
        String read(String name);
        void write(String name, String text);
        void delete(String name);
    }

    public interface Processes {
        /** Whether this exact process is alive; the start time separates it from a recycled identifier. */
        Liveness alive(int pid, long startTicks);
    }

    public interface Clock {
        String bootId();
    }

    private static final String FILE = "executor.json";

    private final Files files;
    private final Processes processes;
    private final Clock clock;

    public ExecutorLock(Files files, Processes processes, Clock clock) {
        this.files = files;
        this.processes = processes;
        this.clock = clock;
    }

    public boolean reserve(String execId, String kind) {
        if (execId == null) return false;
        JSONObject trace = read();
        if (trace != null && !execId.equals(trace.optString("exec_id"))) return false;
        JSONObject fresh = new JSONObject();
        try {
            fresh.put("exec_id", execId);
            fresh.put("kind", kind == null ? PRIVILEGED : kind);
            fresh.put("boot_id", clock.bootId());
            fresh.put("pid", 0);
            fresh.put("start_ticks", 0L);
            fresh.put("handed_off", "");
            fresh.put("hand_off_failed", false);
        } catch (Exception e) {
            return false;
        }
        files.write(FILE, fresh.toString());
        return true;
    }

    /** In-process work turns into privileged work; written before the child starts, never after. */
    public void promote(String execId) {
        patch(execId, "kind", PRIVILEGED);
    }

    public void attach(String execId, int pid, long startTicks) {
        JSONObject trace = mine(execId);
        if (trace == null) return;
        try {
            trace.put("pid", pid);
            trace.put("start_ticks", startTicks);
            trace.put("kind", PRIVILEGED);
        } catch (Exception ignored) {
            return;
        }
        files.write(FILE, trace.toString());
    }

    public void handOff(String execId, String worker) {
        patch(execId, "handed_off", worker == null ? "worker" : worker);
    }

    public void handOffFailed(String execId) {
        JSONObject trace = mine(execId);
        if (trace == null) return;
        try {
            trace.put("hand_off_failed", true);
        } catch (Exception ignored) {
            return;
        }
        files.write(FILE, trace.toString());
    }

    public void done(String execId) {
        JSONObject trace = read();
        if (trace == null) return;
        if (execId != null && !execId.equals(trace.optString("exec_id"))) return;
        files.delete(FILE);
    }

    /** What the stored trace says about work right now: idle, running, or we cannot tell. */
    public String chain() {
        JSONObject trace = read();
        if (trace == null) return IDLE;
        if (!clock.bootId().equals(trace.optString("boot_id"))) return IDLE;   // nothing survives a reboot
        if (trace.optBoolean("hand_off_failed", false)) return IDLE;           // provably never started
        if (!trace.optString("handed_off", "").isEmpty()) return UNKNOWN;      // someone else may still be working
        if (IN_PROCESS.equals(trace.optString("kind"))) return IDLE;           // it lived in a process that is gone
        int pid = trace.optInt("pid", 0);
        if (pid <= 0) return UNKNOWN;                                          // reserved, but we never saw the child
        switch (processes.alive(pid, trace.optLong("start_ticks", 0L))) {
            case ALIVE: return RUNNING;
            case DEAD: return IDLE;
            default: return UNKNOWN;
        }
    }

    public boolean mayStartNew() {
        return IDLE.equals(chain());
    }

    /** The trace as it stands, for the recovery paths that need more than {@link #chain()}. */
    public JSONObject trace() {
        return read();
    }

    private void patch(String execId, String key, String value) {
        JSONObject trace = mine(execId);
        if (trace == null) return;
        try {
            trace.put(key, value);
        } catch (Exception ignored) {
            return;
        }
        files.write(FILE, trace.toString());
    }

    private JSONObject mine(String execId) {
        JSONObject trace = read();
        if (trace == null || execId == null || !execId.equals(trace.optString("exec_id"))) return null;
        return trace;
    }

    private JSONObject read() {
        String text = files.read(FILE);
        if (text == null || text.isEmpty()) return null;
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }
}
