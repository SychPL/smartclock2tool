package pl.mateusz.clockadbprobe.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What happened to every request the bridge was ever given (SPEC 0.12 pkt 4.1, 4.2, 5.1).
 *
 * <p>A caller may lose the answer: its process can die, the clock can lose power, the user can close the window.
 * The registry is what lets it ask later. It is keyed by the whole caller identity, because op_id is unique only
 * within one caller, and it survives restarts, because that is the only case where it matters.
 */
public final class OpRegistry {
    public interface Store {
        String read();
        void write(String text);
    }

    public interface Clock {
        long uptimeMs();
        long wallMs();
        String bootId();
    }

    public static final String ABSENT = "absent";
    public static final String ACCEPTED = "accepted";
    public static final String AWAITING_CONSENT = "awaiting_consent";
    public static final String COPYING = "copying";
    public static final String RUNNING = "running";
    public static final String INSTALLING = "installing";
    public static final String FINISHED = "finished";
    public static final String INTERRUPTED = "interrupted";

    private static final List<String> TERMINAL = Arrays.asList(FINISHED, INTERRUPTED);
    /** Stages during which nothing has been done yet, so an abandoned entry is a refusal, not an unknown. */
    private static final List<String> NOT_STARTED = Arrays.asList(ACCEPTED, AWAITING_CONSENT);

    /** The identity of a request: the caller, what it was signed with, and the id the caller chose. */
    public static final class Key {
        public final String pkg;
        public final String fingerprint;
        public final String opId;

        public Key(String pkg, String fingerprint, String opId) {
            this.pkg = pkg == null ? "" : pkg;
            this.fingerprint = fingerprint == null ? "" : fingerprint;
            this.opId = opId == null ? "" : opId;
        }

        boolean matches(JSONObject entry) {
            return pkg.equals(entry.optString("pkg"))
                    && fingerprint.equals(entry.optString("fingerprint"))
                    && opId.equals(entry.optString("op_id"));
        }
    }

    public static final class Entry {
        public final String opId;
        public final String op;
        public final String stage;
        public final String status;
        public final String requestDigest;
        public final String fileDigest;
        public final long startedAtUptimeMs;
        public final long stageSinceUptimeMs;
        public final long finishedAtMs;
        public final String bootId;
        public final int copies;

        Entry(JSONObject json) {
            opId = json.optString("op_id");
            op = json.optString("op");
            stage = json.optString("stage");
            status = json.optString("status");
            requestDigest = json.optString("request_digest");
            fileDigest = json.optString("file_digest");
            startedAtUptimeMs = json.optLong("started_at_uptime_ms");
            stageSinceUptimeMs = json.optLong("stage_since_uptime_ms");
            finishedAtMs = json.optLong("finished_at_ms");
            bootId = json.optString("boot_id");
            copies = json.optInt("copies");
        }

        private Entry(String opId) {
            this.opId = opId == null ? "" : opId;
            op = "";
            stage = ABSENT;
            status = "none";
            requestDigest = "";
            fileDigest = "";
            startedAtUptimeMs = 0;
            stageSinceUptimeMs = 0;
            finishedAtMs = 0;
            bootId = "";
            copies = 0;
        }

        static Entry absent(String opId) { return new Entry(opId); }

        public boolean terminal() { return TERMINAL.contains(stage); }
    }

    private final Store store;
    private final Clock clock;
    private final List<JSONObject> entries = new ArrayList<>();
    private String previousBootId = "";
    private boolean recovered;

    public OpRegistry(Store store, Clock clock) {
        this.store = store;
        this.clock = clock;
        load();
    }

    /**
     * Accepts a new request. Returns null for a request this caller already made under this id: a duplicate is
     * answered from the stored entry, never accepted a second time.
     */
    public synchronized Entry accept(Key key, String op, String requestDigest) {
        if (find(key) != null) return null;
        JSONObject entry = new JSONObject();
        try {
            entry.put("pkg", key.pkg);
            entry.put("fingerprint", key.fingerprint);
            entry.put("op_id", key.opId);
            entry.put("op", op == null ? "" : op);
            entry.put("request_digest", requestDigest == null ? "" : requestDigest);
            entry.put("file_digest", "");
            entry.put("stage", ACCEPTED);
            entry.put("status", "in_progress");
            entry.put("started_at_uptime_ms", clock.uptimeMs());
            entry.put("stage_since_uptime_ms", clock.uptimeMs());
            entry.put("finished_at_ms", 0);
            entry.put("boot_id", clock.bootId());
            entry.put("copies", 0);
            entry.put("live", true);           // accepted by this process, so recovery must not touch it
        } catch (Exception e) {
            return null;
        }
        entries.add(entry);
        save();
        return new Entry(entry);
    }

    public synchronized boolean stage(Key key, String stage) {
        JSONObject entry = find(key);
        if (entry == null) return false;
        try {
            entry.put("stage", stage);
            entry.put("status", "in_progress");
            entry.put("stage_since_uptime_ms", clock.uptimeMs());
            entry.put("boot_id", clock.bootId());
        } catch (Exception e) {
            return false;
        }
        save();
        return true;
    }

    public synchronized boolean finish(Key key, String status) {
        JSONObject entry = find(key);
        if (entry == null) return false;
        try {
            entry.put("stage", FINISHED);
            entry.put("status", status);
            entry.put("stage_since_uptime_ms", clock.uptimeMs());
            entry.put("finished_at_ms", clock.wallMs());
        } catch (Exception e) {
            return false;
        }
        save();
        return true;
    }

    /** The digest of the copy that will actually be installed; from here on it identifies the request's file. */
    public synchronized boolean bindDigest(Key key, String fileDigest) {
        JSONObject entry = find(key);
        if (entry == null) return false;
        try {
            entry.put("file_digest", fileDigest == null ? "" : fileDigest);
            entry.put("copies", entry.optInt("copies") + 1);
        } catch (Exception e) {
            return false;
        }
        save();
        return true;
    }

    public synchronized Entry about(Key key) {
        JSONObject entry = find(key);
        return entry == null ? Entry.absent(key.opId) : new Entry(entry);
    }

    /** A repeat is the same request only when the operation and the original arguments match too. */
    public boolean duplicate(Entry entry, String op, String requestDigest) {
        return entry != null && !ABSENT.equals(entry.stage)
                && entry.op.equals(op == null ? "" : op)
                && entry.requestDigest.equals(requestDigest == null ? "" : requestDigest);
    }

    /**
     * Closes entries left behind by a process that is gone. The stage decides, not the reason: nothing had started
     * under "accepted" or "awaiting_consent", so those are refusals; anything further may have changed the system.
     * Runs once per instance and never touches an entry this process accepted.
     */
    public synchronized void recoverOnce() {
        if (recovered) return;
        recovered = true;
        boolean changed = false;
        for (JSONObject entry : entries) {
            if (entry.optBoolean("live", false)) continue;
            if (TERMINAL.contains(entry.optString("stage"))) continue;
            try {
                if (NOT_STARTED.contains(entry.optString("stage"))) {
                    entry.put("stage", FINISHED);
                    entry.put("status", "denied");
                    entry.put("finished_at_ms", clock.wallMs());
                } else {
                    entry.put("stage", INTERRUPTED);
                    entry.put("status", "unknown");
                }
                entry.put("stage_since_uptime_ms", clock.uptimeMs());
                entry.put("boot_id", clock.bootId());
                changed = true;
            } catch (Exception ignored) {
                // a single unreadable entry must not stop the rest
            }
        }
        if (changed) save();
    }

    /** The boot this registry was written in before the current one; empty when it never changed. */
    public synchronized String previousBootId() {
        return previousBootId;
    }

    private JSONObject find(Key key) {
        for (JSONObject entry : entries) if (key.matches(entry)) return entry;
        return null;
    }

    private void load() {
        String text = store.read();
        if (text == null || text.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(text);
            previousBootId = root.optString("boot_id", "");
            JSONArray list = root.optJSONArray("entries");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject entry = list.optJSONObject(i);
                if (entry == null) continue;
                entry.remove("live");              // whatever another process marked live is not live for us
                entries.add(entry);
            }
        } catch (Exception ignored) {
            // an unreadable registry starts empty; the alternative is refusing to work at all
        }
    }

    private void save() {
        try {
            JSONArray list = new JSONArray();
            for (JSONObject entry : entries) list.put(entry);
            JSONObject root = new JSONObject();
            root.put("boot_id", clock.bootId());
            root.put("entries", list);
            store.write(root.toString());
        } catch (Exception ignored) {
            // the in-memory view stays usable for this process
        }
    }
}
