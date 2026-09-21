package pl.mateusz.clockadbprobe.bridge;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MicStateTest {
    private final FakeStore store = new FakeStore();

    private static MicState.Current holding(final String... packages) {
        final List<String> with = Arrays.asList(packages);
        return pkg -> with.contains(pkg);
    }

    private static MicState.Current presentOnly(final String... packages) {
        final List<String> present = Arrays.asList(packages);
        return pkg -> present.contains(pkg) ? Boolean.TRUE : null;
    }

    @Test
    public void theFirstReleaseRecordsTheRealStateOfEveryTarget() {
        MicState m = new MicState(store);
        m.rememberBefore(asList("a", "b"), holding("a"));
        assertEquals("saved", m.state());
        assertTrue(m.saved("a"));
        assertFalse(m.saved("b"));
        assertEquals("the record must survive a restart", "saved", new MicState(store).state());
    }

    @Test
    public void aSecondReleaseDoesNotOverwriteTheRecord() {
        new MicState(store).rememberBefore(asList("a"), holding("a"));
        new MicState(store).rememberBefore(asList("a"), holding());   // by now we already took it away
        assertTrue("the original state survives", new MicState(store).saved("a"));
    }

    @Test
    public void aTargetAddedInANewToolVersionIsRecordedBeforeItIsChanged() {
        MicState m = new MicState(store);
        m.rememberBefore(asList("a"), holding("a"));
        m.rememberBefore(asList("a", "c"), holding("c"));
        assertTrue(m.saved("a"));
        assertTrue("the new target is recorded as it was, not as the old one", m.saved("c"));
    }

    @Test
    public void aMissingPackageIsSkippedAndNeverRecorded() {
        MicState m = new MicState(store);
        m.rememberBefore(asList("a", "gone"), presentOnly("a"));
        assertTrue(m.hasEntry("a"));
        assertFalse(m.hasEntry("gone"));
    }

    @Test
    public void restoreNeverGrantsWhatWasNotThere() {
        MicState m = new MicState(store);
        m.rememberBefore(asList("a", "b"), holding("a"));
        assertEquals(asList("a"), m.toGrant());
        assertEquals(asList("b"), m.toDeny());
    }

    @Test
    public void aFullRestoreClearsTheRecord() {
        MicState m = new MicState(store);
        m.rememberBefore(asList("a", "b"), holding("a"));
        RecordingApplier applier = new RecordingApplier();
        MicState.Outcome outcome = m.restore(applier);

        assertEquals("ok", outcome.status);
        assertEquals(asList("a"), applier.granted);
        assertEquals(asList("b"), applier.denied);
        assertEquals("none", new MicState(store).state());
    }

    @Test
    public void aPartialRestoreKeepsTheRestAndReportsFailure() {
        MicState m = new MicState(store);
        m.rememberBefore(asList("a", "b"), holding("a", "b"));
        RecordingApplier applier = new RecordingApplier();
        applier.failOn = "b";

        MicState.Outcome outcome = m.restore(applier);
        assertEquals("failed", outcome.status);
        assertEquals(asList("a"), outcome.done);
        assertEquals(asList("b"), outcome.left);

        MicState reloaded = new MicState(store);
        assertEquals("saved", reloaded.state());
        assertEquals("what failed is still waiting for the next attempt", asList("b"), reloaded.remaining());
    }

    @Test
    public void restoringWithNothingRecordedIsANoOp() {
        MicState.Outcome outcome = new MicState(store).restore(new RecordingApplier());
        assertEquals("ok", outcome.status);
        assertEquals("none", new MicState(store).state());
    }

    static final class RecordingApplier implements MicState.Applier {
        final List<String> granted = new ArrayList<>();
        final List<String> denied = new ArrayList<>();
        String failOn;

        public boolean grant(String pkg) {
            if (pkg.equals(failOn)) return false;
            granted.add(pkg);
            return true;
        }

        public boolean deny(String pkg) {
            if (pkg.equals(failOn)) return false;
            denied.add(pkg);
            return true;
        }
    }

    static final class FakeStore implements MicState.Store {
        private String data = "";
        public String read() { return data; }
        public void write(String text) { data = text; }
    }
}
