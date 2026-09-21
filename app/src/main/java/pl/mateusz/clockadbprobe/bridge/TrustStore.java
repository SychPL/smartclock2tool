package pl.mateusz.clockadbprobe.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Which apps the user let talk to the bridge, and to what they agreed (SPEC 0.12 pkt 4.3, 4.4).
 *
 * <p>Trust is on first use, because no signature can be known in advance: everyone builds their own copy of the
 * clock app. What makes it safe is that a changed signature inherits nothing. Consents hang off the fingerprint,
 * not off the package name, so an app reinstalled with another key starts from zero.
 */
public final class TrustStore {
    public interface Store {
        String read();
        void write(String text);
    }

    private final Store store;
    private JSONObject root = new JSONObject();

    public TrustStore(Store store) {
        this.store = store;
        load();
    }

    public synchronized boolean trusted(String pkg, String fingerprint) {
        JSONObject entry = entryOf(pkg);
        return entry != null && !fingerprint.isEmpty() && fingerprint.equals(entry.optString("fingerprint"));
    }

    public synchronized void trust(String pkg, String fingerprint) {
        JSONObject entry = entryOf(pkg);
        if (entry != null && !fingerprint.equals(entry.optString("fingerprint"))) drop(pkg);
        try {
            JSONObject fresh = new JSONObject();
            fresh.put("fingerprint", fingerprint);
            fresh.put("consents", new JSONArray());
            apps().put(pkg, fresh);
            bump();
        } catch (Exception ignored) {
            // an unwritable store simply asks again next time
        }
        save();
    }

    /**
     * Called when a request arrives: if the signature is not the one we trusted, everything this package was allowed
     * is void immediately, whether or not the user accepts the new one.
     */
    public synchronized void seenFingerprint(String pkg, String fingerprint) {
        JSONObject entry = entryOf(pkg);
        if (entry == null) return;
        if (!fingerprint.equals(entry.optString("fingerprint"))) {
            drop(pkg);
            bump();
            save();
        }
    }

    public synchronized boolean consented(String pkg, String fingerprint, String op, String scope) {
        if (!trusted(pkg, fingerprint)) return false;
        JSONArray consents = entryOf(pkg).optJSONArray("consents");
        for (int i = 0; consents != null && i < consents.length(); i++) {
            JSONObject consent = consents.optJSONObject(i);
            if (consent == null) continue;
            if (consent.optString("op").equals(op) && consent.optString("scope").equals(scope == null ? "" : scope)) return true;
        }
        return false;
    }

    public synchronized void consent(String pkg, String fingerprint, String op, String scope) {
        if (!trusted(pkg, fingerprint)) return;
        if (consented(pkg, fingerprint, op, scope)) return;
        try {
            JSONObject consent = new JSONObject();
            consent.put("op", op);
            consent.put("scope", scope == null ? "" : scope);
            entryOf(pkg).optJSONArray("consents").put(consent);
            bump();
        } catch (Exception ignored) {
            // nothing stored means the user is asked again, which is the safe direction
        }
        save();
    }

    public synchronized void revokeAll(String pkg) {
        drop(pkg);
        bump();
        save();
    }

    public synchronized void revokeOne(String pkg, String op, String scope) {
        JSONObject entry = entryOf(pkg);
        if (entry == null) return;
        JSONArray consents = entry.optJSONArray("consents");
        JSONArray kept = new JSONArray();
        for (int i = 0; consents != null && i < consents.length(); i++) {
            JSONObject consent = consents.optJSONObject(i);
            if (consent == null) continue;
            boolean same = consent.optString("op").equals(op) && consent.optString("scope").equals(scope == null ? "" : scope);
            if (!same) kept.put(consent);
        }
        try {
            entry.put("consents", kept);
            bump();
        } catch (Exception ignored) {
            return;
        }
        save();
    }

    /**
     * Changes whenever trust or a consent changes. A request that was authorised before a revocation checks this
     * number again just before it runs, so revoking also cancels what is already waiting.
     */
    public synchronized long revision() {
        return root.optLong("revision", 0);
    }

    /** One human-readable line per trusted app, for the screen that lets the user take it back. */
    public synchronized List<String> describe() {
        List<String> lines = new ArrayList<>();
        JSONObject apps = apps();
        JSONArray names = apps.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            String pkg = names.optString(i);
            JSONObject entry = apps.optJSONObject(pkg);
            if (entry == null) continue;
            JSONArray consents = entry.optJSONArray("consents");
            StringBuilder line = new StringBuilder(pkg).append(" (").append(shortFingerprint(entry.optString("fingerprint"))).append(")");
            for (int c = 0; consents != null && c < consents.length(); c++) {
                JSONObject consent = consents.optJSONObject(c);
                if (consent == null) continue;
                line.append("\n  ").append(consent.optString("op"));
                String scope = consent.optString("scope");
                if (!scope.isEmpty()) line.append(": ").append(scope);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static String shortFingerprint(String fingerprint) {
        return fingerprint.length() <= 12 ? fingerprint : fingerprint.substring(0, 12) + "...";
    }

    private JSONObject apps() {
        JSONObject apps = root.optJSONObject("apps");
        if (apps == null) {
            apps = new JSONObject();
            try {
                root.put("apps", apps);
            } catch (Exception ignored) {
                // cannot happen for a literal key, and an empty store is still usable
            }
        }
        return apps;
    }

    private JSONObject entryOf(String pkg) {
        return apps().optJSONObject(pkg);
    }

    private void drop(String pkg) {
        apps().remove(pkg);
    }

    private void bump() {
        try {
            root.put("revision", revision() + 1);
        } catch (Exception ignored) {
            // the revision only ever needs to change, not to be exact
        }
    }

    private void load() {
        String text = store.read();
        if (text == null || text.isEmpty()) return;
        try {
            root = new JSONObject(text);
        } catch (Exception ignored) {
            root = new JSONObject();      // an unreadable store means nobody is trusted, which is the safe direction
        }
    }

    private void save() {
        store.write(root.toString());
    }
}
