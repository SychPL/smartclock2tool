package pl.mateusz.clockadbprobe.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * What is true right now, as far as we can tell in the time we allow ourselves (SPEC 0.12 pkt 5.1).
 *
 * <p>Two rules make this honest. A measurement that did not finish says {@code "unknown"} instead of a default, and
 * the whole snapshot is bounded, because the caller asked a question, not for a five minute investigation. The mic
 * holders are the sharpest case: without root the system anonymises that list, so an empty list would be a lie told
 * exactly when the problem is present.
 */
public final class Snapshot {
    public interface Probe {
        Boolean root();
        Boolean ssh();
        String adbProperty();
        Boolean adbListening();
        String firmware();
        Boolean firmwareSupported();
        /** Null means we could not tell; an empty list means we looked with privileges and nobody holds it. */
        List<String> micHolders();
        String micSavedState();
        String toolVersion();
        JSONObject caller();
    }

    public interface Clock {
        long uptimeMs();
        String bootId();
    }

    private static final String UNKNOWN = "unknown";

    private final Probe probe;
    private final Clock clock;
    private final ExecutorService pool;

    public Snapshot(Probe probe, Clock clock, ExecutorService pool) {
        this.probe = probe;
        this.clock = clock;
        this.pool = pool;
    }

    public String json(long budgetMs) {
        return json(budgetMs, null, null);
    }

    /** With an entry, the snapshot also answers "what happened to that request of mine". */
    public String json(long budgetMs, OpRegistry.Entry about, String chain) {
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        Future<Boolean> root = submit(probe::root);
        Future<Boolean> ssh = submit(probe::ssh);
        Future<String> adbProperty = submit(probe::adbProperty);
        Future<Boolean> adbListening = submit(probe::adbListening);
        Future<String> firmware = submit(probe::firmware);
        Future<Boolean> firmwareSupported = submit(probe::firmwareSupported);
        Future<List<String>> micHolders = submit(probe::micHolders);
        Future<String> micSaved = submit(probe::micSavedState);
        Future<String> toolVersion = submit(probe::toolVersion);
        Future<JSONObject> caller = submit(probe::caller);

        JSONObject out = new JSONObject();
        try {
            put(out, "root", await(root, deadline));
            put(out, "ssh", await(ssh, deadline));
            put(out, "adb_property", await(adbProperty, deadline));
            put(out, "adb_listening", await(adbListening, deadline));
            put(out, "firmware", await(firmware, deadline));
            put(out, "firmware_supported", await(firmwareSupported, deadline));
            put(out, "mic_saved_state", await(micSaved, deadline));
            put(out, "tool_version", await(toolVersion, deadline));
            put(out, "caller", await(caller, deadline));

            List<String> holders = await(micHolders, deadline);
            out.put("mic_holders", holders == null ? UNKNOWN : new JSONArray(new ArrayList<>(holders)));

            out.put("chain", chain == null ? UNKNOWN : chain);
            out.put("api", BridgeRequest.API);
            out.put("measured_at_uptime_ms", clock.uptimeMs());
            out.put("now_uptime_ms", clock.uptimeMs());
            out.put("boot_id", clock.bootId());
            if (about != null) out.put("about", aboutJson(about));
        } catch (Exception ignored) {
            // a half-built snapshot is still better than no answer at all
        }
        return out.toString();
    }

    private JSONObject aboutJson(OpRegistry.Entry entry) throws Exception {
        JSONObject about = new JSONObject();
        about.put("op_id", entry.opId);
        about.put("op", entry.op);
        about.put("stage", entry.stage);
        about.put("status", entry.status);
        about.put("started_at_uptime_ms", entry.startedAtUptimeMs);
        about.put("stage_since_uptime_ms", entry.stageSinceUptimeMs);
        about.put("now_uptime_ms", clock.uptimeMs());
        about.put("boot_id", entry.bootId);
        about.put("finished_at_ms", entry.finishedAtMs);
        return about;
    }

    private <T> Future<T> submit(Callable<T> work) {
        return pool.submit(work);
    }

    /** The value, or null when this particular measurement ran out of the shared budget. */
    private <T> T await(Future<T> future, long deadlineNanos) {
        long left = deadlineNanos - System.nanoTime();
        if (left <= 0) {
            future.cancel(true);
            return null;
        }
        try {
            return future.get(left, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return null;
        }
    }

    private static void put(JSONObject out, String key, Object value) throws Exception {
        out.put(key, value == null ? UNKNOWN : value);
    }
}
