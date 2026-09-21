package pl.mateusz.clockadbprobe.bridge;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import pl.mateusz.clockadbprobe.OperationGate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ExecutionsTest {
    private final Map<String, String> files = new HashMap<>();
    private ExecutorLock.Liveness liveness = ExecutorLock.Liveness.ALIVE;

    @Before
    public void install() {
        Executions.install(new ExecutorLock(new ExecutorLock.Files() {
            public String read(String name) { return files.get(name); }
            public void write(String name, String text) { files.put(name, text); }
            public void delete(String name) { files.remove(name); }
        }, (pid, startTicks) -> liveness, () -> "b1"));
    }

    @After
    public void release() {
        OperationGate.release(OperationGate.ownerId());
        files.clear();
    }

    @Test
    public void twoCallersMayUseTheSameOpIdWithoutCollidingOnIdentity() {
        String mine = Executions.execId("pl.mateusz.helios", "AA", "op1");
        String theirs = Executions.execId("pl.evil", "CC", "op1");
        assertNotEquals("op_id is unique per caller, not globally", mine, theirs);
        assertEquals("and the same caller always gets the same id",
                mine, Executions.execId("pl.mateusz.helios", "AA", "op1"));
    }

    @Test
    public void onlyOneExecutionRunsAtATime() {
        String first = Executions.execId("pl.mateusz.helios", "AA", "op1");
        String second = Executions.execId("pl.evil", "CC", "op1");

        assertTrue(Executions.begin(first, "running", ExecutorLock.PRIVILEGED).granted);
        Executions.Grant refused = Executions.begin(second, "running", ExecutorLock.PRIVILEGED);
        assertFalse(refused.granted);
        assertEquals("running", refused.reason);

        Executions.end(first);
        assertTrue(Executions.begin(second, "running", ExecutorLock.PRIVILEGED).granted);
        Executions.end(second);
    }

    @Test
    public void aTraceLeftByADeadProcessStillBlocksNewWork() {
        String stale = Executions.execId("pl.mateusz.helios", "AA", "op-stale");
        assertTrue(Executions.begin(stale, "running", ExecutorLock.PRIVILEGED).granted);
        Executions.lock().attach(stale, 1234, 500);

        OperationGate.release(stale);                       // the process died: only the trace survives
        Executions.Grant refused = Executions.begin(Executions.execId("pl.mateusz.helios", "AA", "op-new"),
                "running", ExecutorLock.PRIVILEGED);
        assertFalse("an in-memory lock alone would have allowed this", refused.granted);
        assertEquals("running", refused.reason);
    }

    @Test
    public void theOldProbeEntryPointLeavesNoPersistentReservationBehind() {
        assertTrue(OperationGate.tryStartProbe());
        OperationGate.finishProbe();
        assertTrue("a released run must not block the next one", OperationGate.tryStartProbe());
        OperationGate.finishProbe();
        assertEquals("idle", Executions.lock().chain());
    }

    @Test
    public void workHandedToSomeoneElseKeepsTheTraceButFreesTheLock() {
        String execId = Executions.execId("pl.mateusz.helios", "AA", "op1");
        assertTrue(Executions.begin(execId, "installing", ExecutorLock.PRIVILEGED).granted);
        Executions.lock().attach(execId, 1234, 500);
        Executions.lock().handOff(execId, "installer");
        Executions.endAfterWorker(execId);

        assertFalse("the in-memory lock is gone", OperationGate.isBusy());
        assertEquals("but the installer may still be working", "unknown", Executions.lock().chain());
        assertFalse(Executions.begin(Executions.execId("pl.mateusz.helios", "AA", "op2"),
                "running", ExecutorLock.PRIVILEGED).granted);
    }
}
