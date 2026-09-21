package pl.mateusz.clockadbprobe.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpRegistryTest {
    private final FakeStore store = new FakeStore();
    private final FakeClock clock = new FakeClock("boot-a", 1000);

    private static OpRegistry.Key key(String pkg, String fingerprint, String opId) {
        return new OpRegistry.Key(pkg, fingerprint, opId);
    }

    @Test
    public void anEntryBelongsToTheWholeKeyNotJustTheId() {
        OpRegistry r = new OpRegistry(store, clock);
        r.accept(key("pl.mateusz.helios", "AA", "op1"), "state", "d1");
        assertEquals("accepted", r.about(key("pl.mateusz.helios", "AA", "op1")).stage);
        assertEquals("a foreign package must not see the entry", "absent", r.about(key("pl.evil", "AA", "op1")).stage);
        assertEquals("a changed signature must not see it either", "absent", r.about(key("pl.mateusz.helios", "BB", "op1")).stage);
        assertEquals("absent", r.about(key("pl.mateusz.helios", "AA", "never-used")).stage);
    }

    @Test
    public void anotherAppCannotTouchAnExistingEntryButMayUseTheSameIdItself() {
        OpRegistry r = new OpRegistry(store, clock);
        r.accept(key("pl.mateusz.helios", "AA", "op1"), "root_adb_on", "d1");
        r.stage(key("pl.mateusz.helios", "AA", "op1"), "running");

        assertNotNull("the id is unique per caller, not globally", r.accept(key("pl.evil", "CC", "op1"), "state", "d2"));
        assertEquals("and the two entries do not touch each other", "running", r.about(key("pl.mateusz.helios", "AA", "op1")).stage);
        assertEquals("accepted", r.about(key("pl.evil", "CC", "op1")).stage);
    }

    @Test
    public void reacceptingTheSameIdDoesNotResetTheEntry() {
        OpRegistry r = new OpRegistry(store, clock);
        r.accept(key("p", "AA", "op1"), "adb_off", "d1");
        r.stage(key("p", "AA", "op1"), "running");
        assertNull("a duplicate is answered, never re-accepted", r.accept(key("p", "AA", "op1"), "adb_off", "d1"));
        assertEquals("running", r.about(key("p", "AA", "op1")).stage);
    }

    @Test
    public void aForeignKeyCannotMoveOrFinishAnEntry() {
        OpRegistry r = new OpRegistry(store, clock);
        r.accept(key("p", "AA", "op1"), "adb_off", "d1");
        assertFalse(r.stage(key("p", "BB", "op1"), "running"));
        assertFalse(r.finish(key("pl.evil", "AA", "op1"), "ok"));
        assertEquals("accepted", r.about(key("p", "AA", "op1")).stage);
    }

    @Test
    public void aDuplicateNeedsTheSameOperationAndArguments() {
        OpRegistry r = new OpRegistry(store, clock);
        OpRegistry.Entry first = r.accept(key("p", "AA", "op1"), "adb_off", "d1");
        assertTrue(r.duplicate(first, "adb_off", "d1"));
        assertFalse("another operation under the same id is not a duplicate", r.duplicate(first, "adb_on", "d1"));
        assertFalse("other arguments are not a duplicate either", r.duplicate(first, "adb_off", "d2"));
        assertFalse("and an absent entry is never a duplicate", r.duplicate(r.about(key("p", "AA", "nope")), "adb_off", "d1"));
    }

    @Test
    public void stageDecidesHowAnAbandonedEntryCloses() {
        OpRegistry r = new OpRegistry(store, clock);
        r.accept(key("p", "AA", "waiting"), "root_adb_on", "d");
        r.stage(key("p", "AA", "waiting"), "awaiting_consent");
        r.accept(key("p", "AA", "working"), "root_adb_on", "d");
        r.stage(key("p", "AA", "working"), "running");

        OpRegistry afterRestart = new OpRegistry(store, clock);
        afterRestart.recoverOnce();
        assertEquals("finished", afterRestart.about(key("p", "AA", "waiting")).stage);
        assertEquals("nothing had started yet", "denied", afterRestart.about(key("p", "AA", "waiting")).status);
        assertEquals("interrupted", afterRestart.about(key("p", "AA", "working")).stage);
        assertEquals("unknown", afterRestart.about(key("p", "AA", "working")).status);
    }

    @Test
    public void recoveryRunsOnceAndNeverClosesAnEntryOfThisProcess() {
        OpRegistry r = new OpRegistry(store, clock);
        r.recoverOnce();
        r.accept(key("p", "AA", "op1"), "root_adb_on", "d");
        r.stage(key("p", "AA", "op1"), "running");
        r.recoverOnce();
        assertEquals("a second call must not touch a live operation", "running", r.about(key("p", "AA", "op1")).stage);
    }

    @Test
    public void thePreviousBootIdSurvivesRecovery() {
        OpRegistry r = new OpRegistry(store, clock);
        r.accept(key("p", "AA", "op1"), "root_adb_on", "d");
        r.stage(key("p", "AA", "op1"), "running");

        clock.boot("boot-b");
        OpRegistry after = new OpRegistry(store, clock);
        after.recoverOnce();
        assertEquals("boot-a", after.previousBootId());
        assertEquals("interrupted", after.about(key("p", "AA", "op1")).stage);
    }

    @Test
    public void timesAreMonotonicAndCarryTheBootId() {
        OpRegistry r = new OpRegistry(store, clock);
        r.accept(key("p", "AA", "op1"), "root_adb_on", "d");
        clock.advance(5000);
        r.stage(key("p", "AA", "op1"), "running");
        clock.advance(2000);

        OpRegistry.Entry e = r.about(key("p", "AA", "op1"));
        assertEquals(1000, e.startedAtUptimeMs);
        assertEquals(6000, e.stageSinceUptimeMs);
        assertEquals("boot-a", e.bootId);
        assertEquals("an unfinished entry has no wall time", 0, e.finishedAtMs);

        r.finish(key("p", "AA", "op1"), "ok");
        assertTrue(r.about(key("p", "AA", "op1")).finishedAtMs > 0);
    }

    @Test
    public void theFileDigestIsBoundOnceTheCopyExists() {
        OpRegistry r = new OpRegistry(store, clock);
        r.accept(key("p", "AA", "op1"), "install_apk", "d");
        assertEquals("", r.about(key("p", "AA", "op1")).fileDigest);
        assertEquals(0, r.about(key("p", "AA", "op1")).copies);

        r.bindDigest(key("p", "AA", "op1"), "sha-of-copy");
        assertEquals("sha-of-copy", r.about(key("p", "AA", "op1")).fileDigest);
        assertEquals("exactly one copy was ever made", 1, r.about(key("p", "AA", "op1")).copies);
    }

    @Test
    public void everythingSurvivesAReload() {
        OpRegistry r = new OpRegistry(store, clock);
        r.accept(key("p", "AA", "op1"), "install_apk", "d");
        r.bindDigest(key("p", "AA", "op1"), "sha-of-copy");
        r.finish(key("p", "AA", "op1"), "ok");

        OpRegistry reloaded = new OpRegistry(store, clock);
        OpRegistry.Entry e = reloaded.about(key("p", "AA", "op1"));
        assertEquals("finished", e.stage);
        assertEquals("ok", e.status);
        assertEquals("sha-of-copy", e.fileDigest);
        assertTrue(e.terminal());
    }

    static final class FakeStore implements OpRegistry.Store {
        private String data = "";
        public String read() { return data; }
        public void write(String text) { data = text; }
    }

    static final class FakeClock implements OpRegistry.Clock {
        private String bootId;
        private long uptime;

        FakeClock(String bootId, long uptime) { this.bootId = bootId; this.uptime = uptime; }
        void advance(long ms) { uptime += ms; }
        void boot(String id) { bootId = id; uptime = 0; }
        public long uptimeMs() { return uptime; }
        public long wallMs() { return 1_700_000_000_000L + uptime; }
        public String bootId() { return bootId; }
    }
}
