package pl.mateusz.clockadbprobe.bridge;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExecutorLockTest {
    private final FakeFiles files = new FakeFiles();

    private ExecutorLock lock(ExecutorLock.Liveness liveness, long knownStartTicks, String bootId) {
        return new ExecutorLock(files, (pid, startTicks) ->
                startTicks == knownStartTicks ? liveness : ExecutorLock.Liveness.DEAD, () -> bootId);
    }

    @Test
    public void aLiveExecutorMeansRunning() {
        ExecutorLock l = lock(ExecutorLock.Liveness.ALIVE, 500, "b1");
        assertTrue(l.reserve("exec1", ExecutorLock.PRIVILEGED));
        l.attach("exec1", 1234, 500);
        assertEquals("running", l.chain());
        assertFalse(l.mayStartNew());
    }

    @Test
    public void aPrivilegedReservationWithoutAProcessIsUnknownNotIdle() {
        lock(ExecutorLock.Liveness.DEAD, 500, "b1").reserve("exec1", ExecutorLock.PRIVILEGED);
        ExecutorLock afterCrash = lock(ExecutorLock.Liveness.DEAD, 500, "b1");
        assertEquals("the child may have started before we crashed", "unknown", afterCrash.chain());
        assertFalse(afterCrash.mayStartNew());
    }

    @Test
    public void workThatLivesOnlyInsideTheAppDiesWithIt() {
        lock(ExecutorLock.Liveness.DEAD, 500, "b1").reserve("exec1", ExecutorLock.IN_PROCESS);
        ExecutorLock afterCrash = lock(ExecutorLock.Liveness.DEAD, 500, "b1");
        assertEquals("nothing outside the app was running, so nothing is running now", "idle", afterCrash.chain());
        assertTrue("and no power cycle may be required for that", afterCrash.mayStartNew());
    }

    @Test
    public void aCrashRightAfterPromotingIsUnknownNotIdle() {
        ExecutorLock l = lock(ExecutorLock.Liveness.DEAD, 500, "b1");
        l.reserve("exec1", ExecutorLock.IN_PROCESS);
        l.promote("exec1");
        assertEquals("unknown", lock(ExecutorLock.Liveness.DEAD, 500, "b1").chain());
    }

    @Test
    public void aDeadExecutorFromThisBootMeansIdle() {
        ExecutorLock l = lock(ExecutorLock.Liveness.ALIVE, 500, "b1");
        l.reserve("exec1", ExecutorLock.PRIVILEGED);
        l.attach("exec1", 1234, 500);
        assertEquals("a process that is gone did finish, whatever it did",
                "idle", lock(ExecutorLock.Liveness.DEAD, 500, "b1").chain());
    }

    @Test
    public void aRecycledPidIsNotOurExecutor() {
        ExecutorLock l = lock(ExecutorLock.Liveness.ALIVE, 500, "b1");
        l.reserve("exec1", ExecutorLock.PRIVILEGED);
        l.attach("exec1", 1234, 500);
        assertEquals("the same pid started later is a different process",
                "idle", lock(ExecutorLock.Liveness.ALIVE, 900, "b1").chain());
    }

    @Test
    public void anExecutorFromAnotherBootIsGoneByDefinition() {
        ExecutorLock l = lock(ExecutorLock.Liveness.ALIVE, 500, "b1");
        l.reserve("exec1", ExecutorLock.PRIVILEGED);
        l.attach("exec1", 1234, 500);
        assertEquals("idle", lock(ExecutorLock.Liveness.ALIVE, 500, "b2").chain());
    }

    @Test
    public void anUnreadableProcessStateIsUnknownNotIdle() {
        ExecutorLock l = lock(ExecutorLock.Liveness.UNKNOWN, 500, "b1");
        l.reserve("exec1", ExecutorLock.PRIVILEGED);
        l.attach("exec1", 1234, 500);
        ExecutorLock again = lock(ExecutorLock.Liveness.UNKNOWN, 500, "b1");
        assertEquals("unknown", again.chain());
        assertFalse("unknown never lets a new operation start", again.mayStartNew());
    }

    @Test
    public void handedOffWorkOutlivesTheExecutorProcess() {
        ExecutorLock l = lock(ExecutorLock.Liveness.ALIVE, 500, "b1");
        l.reserve("exec1", ExecutorLock.PRIVILEGED);
        l.attach("exec1", 1234, 500);
        l.handOff("exec1", "installer");
        ExecutorLock afterDeath = lock(ExecutorLock.Liveness.DEAD, 500, "b1");
        assertEquals("a dead child does not mean the installer finished", "unknown", afterDeath.chain());
        assertFalse(afterDeath.mayStartNew());
    }

    @Test
    public void aHandOffThatProvablyNeverStartedIsIdle() {
        ExecutorLock l = lock(ExecutorLock.Liveness.ALIVE, 500, "b1");
        l.reserve("exec1", ExecutorLock.PRIVILEGED);
        l.attach("exec1", 1234, 500);
        l.handOff("exec1", "installer");
        l.handOffFailed("exec1");
        assertEquals("nothing started, so nothing may hold the lock",
                "idle", lock(ExecutorLock.Liveness.DEAD, 500, "b1").chain());
    }

    @Test
    public void onlyTheOwnerMayMoveOrClearTheTrace() {
        ExecutorLock l = lock(ExecutorLock.Liveness.ALIVE, 500, "b1");
        l.reserve("exec1", ExecutorLock.PRIVILEGED);
        l.attach("exec1", 1234, 500);

        assertFalse("a second operation cannot reserve over a live one", l.reserve("exec2", ExecutorLock.PRIVILEGED));
        l.attach("exec2", 4321, 900);
        assertEquals("running", l.chain());
        l.done("exec2");
        assertEquals("a foreign release leaves the trace alone", "running", l.chain());

        l.done("exec1");
        assertEquals("idle", l.chain());
    }

    static final class FakeFiles implements ExecutorLock.Files {
        private final Map<String, String> data = new HashMap<>();
        public String read(String name) { return data.get(name); }
        public void write(String name, String text) { data.put(name, text); }
        public void delete(String name) { data.remove(name); }
    }
}
