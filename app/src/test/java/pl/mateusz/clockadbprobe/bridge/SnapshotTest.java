package pl.mateusz.clockadbprobe.bridge;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SnapshotTest {
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Snapshot.Clock clock = new Snapshot.Clock() {
        public long uptimeMs() { return 5_000; }
        public String bootId() { return "b1"; }
    };

    @After
    public void shutdown() {
        pool.shutdownNow();
    }

    @Test
    public void unmeasuredFieldsAreUnknownNotDefaults() throws Exception {
        JSONObject j = new JSONObject(new Snapshot(new FailingProbe(), clock, pool).json(3000));
        assertEquals("unknown", j.getString("root"));
        assertEquals("unknown", j.getString("adb_listening"));
        assertEquals("a failed measurement is never an empty list", "unknown", j.getString("mic_holders"));
    }

    @Test
    public void micHoldersAreUnknownWithoutRoot() throws Exception {
        Probe probe = new Probe();
        probe.root = false;
        probe.micHolders = null;                       // no privileges, no trustworthy list
        JSONObject j = new JSONObject(new Snapshot(probe, clock, pool).json(3000));
        assertFalse(j.getBoolean("root"));
        assertEquals("unknown", j.getString("mic_holders"));
    }

    @Test
    public void micHoldersAreAListOnlyWhenMeasuredWithPrivileges() throws Exception {
        Probe probe = new Probe();
        probe.root = true;
        probe.micHolders = Arrays.asList("com.google.android.apps.mediashell");
        JSONObject j = new JSONObject(new Snapshot(probe, clock, pool).json(3000));
        assertEquals(1, j.getJSONArray("mic_holders").length());
        assertEquals("com.google.android.apps.mediashell", j.getJSONArray("mic_holders").getString(0));

        probe.micHolders = new ArrayList<>();
        JSONObject empty = new JSONObject(new Snapshot(probe, clock, pool).json(3000));
        assertEquals("nobody holds it, and we know that", 0, empty.getJSONArray("mic_holders").length());
    }

    @Test
    public void adbPropertyAndAdbListeningAreSeparate() throws Exception {
        Probe probe = new Probe();
        probe.adbProperty = "5555";
        probe.adbListening = false;                    // configured, but the daemon has not been restarted
        JSONObject j = new JSONObject(new Snapshot(probe, clock, pool).json(3000));
        assertEquals("5555", j.getString("adb_property"));
        assertFalse(j.getBoolean("adb_listening"));
    }

    @Test
    public void aSlowProbeIsCutOffByTheBudget() throws Exception {
        Probe probe = new Probe();
        probe.blockMs = 10_000;                        // a real thread, really blocking
        long started = System.nanoTime();
        JSONObject j = new JSONObject(new Snapshot(probe, clock, pool).json(500));
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue("the snapshot must return within its budget, took " + tookMs + " ms", tookMs < 3_000);
        assertEquals("unknown", j.getString("root"));
    }

    @Test
    public void theAboutBlockAppearsOnlyWhenAsked() throws Exception {
        JSONObject plain = new JSONObject(new Snapshot(new Probe(), clock, pool).json(3000));
        assertFalse(plain.has("about"));

        OpRegistryTest.FakeStore store = new OpRegistryTest.FakeStore();
        OpRegistryTest.FakeClock registryClock = new OpRegistryTest.FakeClock("b1", 1000);
        OpRegistry registry = new OpRegistry(store, registryClock);
        OpRegistry.Key key = new OpRegistry.Key("p", "AA", "op1");
        registry.accept(key, "root_adb_on", "d");
        registry.stage(key, "running");

        JSONObject asked = new JSONObject(new Snapshot(new Probe(), clock, pool)
                .json(3000, registry.about(key), "running"));
        assertNotNull(asked.getJSONObject("about"));
        assertEquals("op1", asked.getJSONObject("about").getString("op_id"));
        assertEquals("running", asked.getJSONObject("about").getString("stage"));
        assertEquals("the age is computable from the snapshot alone",
                5_000, asked.getJSONObject("about").getLong("now_uptime_ms"));
        assertEquals("b1", asked.getJSONObject("about").getString("boot_id"));
    }

    @Test
    public void theChainIsAlwaysStatedEvenWhenNobodyPassedIt() throws Exception {
        JSONObject j = new JSONObject(new Snapshot(new Probe(), clock, pool).json(3000));
        assertEquals("unknown", j.getString("chain"));
        JSONObject idle = new JSONObject(new Snapshot(new Probe(), clock, pool).json(3000, null, "idle"));
        assertEquals("idle", idle.getString("chain"));
    }

    /** Every measurement answers, nothing is unknown unless a test says so. */
    static class Probe implements Snapshot.Probe {
        Boolean root = true;
        Boolean ssh = false;
        String adbProperty = "0";
        Boolean adbListening = false;
        List<String> micHolders = new ArrayList<>();
        long blockMs;

        public Boolean root() { block(); return root; }
        public Boolean ssh() { return ssh; }
        public String adbProperty() { return adbProperty; }
        public Boolean adbListening() { return adbListening; }
        public String firmware() { return "LenovoCD-24502F_ROW_1.2.2.627_220105"; }
        public Boolean firmwareSupported() { return true; }
        public List<String> micHolders() { return micHolders; }
        public String micSavedState() { return "none"; }
        public String toolVersion() { return "2.19.0"; }
        public JSONObject caller() { return new JSONObject(); }

        private void block() {
            if (blockMs <= 0) return;
            try {
                Thread.sleep(blockMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Every measurement throws, so every field must come out unknown. */
    static final class FailingProbe implements Snapshot.Probe {
        public Boolean root() { throw new IllegalStateException("no channel"); }
        public Boolean ssh() { throw new IllegalStateException("no channel"); }
        public String adbProperty() { throw new IllegalStateException("no channel"); }
        public Boolean adbListening() { throw new IllegalStateException("no socket"); }
        public String firmware() { throw new IllegalStateException("no property"); }
        public Boolean firmwareSupported() { throw new IllegalStateException("no property"); }
        public List<String> micHolders() { throw new IllegalStateException("no channel"); }
        public String micSavedState() { throw new IllegalStateException("no store"); }
        public String toolVersion() { throw new IllegalStateException("no manager"); }
        public JSONObject caller() { throw new IllegalStateException("no manager"); }
    }
}
