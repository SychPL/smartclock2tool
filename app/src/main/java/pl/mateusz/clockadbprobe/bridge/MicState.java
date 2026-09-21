package pl.mateusz.clockadbprobe.bridge;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What the microphone permission of each watched shell looked like before we took it away (SPEC 0.12 pkt 5.7).
 *
 * <p>Taking the microphone from the factory shells is what makes the wake word usable, and it is permanent: it
 * survives reboots, which is the point. Giving it back therefore needs a record of what was there originally, and
 * that record has exactly one rule that matters: it is written before the first change and never overwritten with
 * an already-changed state.
 */
public final class MicState {
    public interface Store {
        String read();
        void write(String text);
    }

    /** Whether a package currently holds the permission; null for a package that is not installed. */
    public interface Current {
        Boolean granted(String pkg);
    }

    /** Applies one change; false means it did not work. */
    public interface Applier {
        boolean grant(String pkg);
        boolean deny(String pkg);
    }

    public static final class Outcome {
        public final String status;
        public final List<String> done = new ArrayList<>();
        public final List<String> left = new ArrayList<>();

        Outcome(String status) {
            this.status = status;
        }
    }

    private final Store store;
    private JSONObject saved;

    public MicState(Store store) {
        this.store = store;
        load();
    }

    /** "saved" while a record exists, "none" once everything has been given back. */
    public String state() {
        return saved.length() == 0 ? "none" : "saved";
    }

    public boolean hasEntry(String pkg) {
        return saved.has(pkg);
    }

    public boolean saved(String pkg) {
        return saved.optBoolean(pkg, false);
    }

    /**
     * Records the state of every target that is not recorded yet, and only those. A second release must not
     * overwrite the first record, and a target added by a newer version of the tool must be recorded before it is
     * changed, or it could never be restored.
     */
    public void rememberBefore(List<String> targets, Current current) {
        boolean changed = false;
        for (String pkg : targets) {
            if (saved.has(pkg)) continue;
            Boolean granted = current.granted(pkg);
            if (granted == null) continue;          // not installed: nothing to remember, nothing to restore
            try {
                saved.put(pkg, granted.booleanValue());
                changed = true;
            } catch (Exception ignored) {
                // a target we cannot record is a target we will not touch
            }
        }
        if (changed) save();
    }

    /** The packages that had the permission, so restoring means giving it back to exactly these. */
    public List<String> toGrant() {
        return partition(true);
    }

    /** The packages that did not have it; restoring must not hand it to them. */
    public List<String> toDeny() {
        return partition(false);
    }

    public List<String> remaining() {
        List<String> all = new ArrayList<>();
        org.json.JSONArray names = saved.names();
        for (int i = 0; names != null && i < names.length(); i++) all.add(names.optString(i));
        return all;
    }

    /**
     * Puts every recorded package back the way it was. A package that fails stays in the record, so the next
     * attempt still knows what to do; the outcome says "failed" as long as anything is left.
     */
    public Outcome restore(Applier applier) {
        if (saved.length() == 0) return new Outcome("ok");
        Outcome outcome = new Outcome("ok");
        for (String pkg : remaining()) {
            boolean shouldHaveIt = saved.optBoolean(pkg, false);
            boolean ok = shouldHaveIt ? applier.grant(pkg) : applier.deny(pkg);
            if (ok) {
                saved.remove(pkg);
                outcome.done.add(pkg);
            } else {
                outcome.left.add(pkg);
            }
        }
        save();
        return saved.length() == 0 ? outcome : failed(outcome);
    }

    private static Outcome failed(Outcome partial) {
        Outcome outcome = new Outcome("failed");
        outcome.done.addAll(partial.done);
        outcome.left.addAll(partial.left);
        return outcome;
    }

    private List<String> partition(boolean wanted) {
        List<String> out = new ArrayList<>();
        for (String pkg : remaining()) if (saved.optBoolean(pkg, false) == wanted) out.add(pkg);
        return out;
    }

    private void load() {
        String text = store.read();
        try {
            saved = text == null || text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception e) {
            saved = new JSONObject();
        }
    }

    private void save() {
        store.write(saved.toString());
    }
}
