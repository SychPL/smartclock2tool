package pl.mateusz.clockadbprobe;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OperationGateTest {
    @After
    public void releaseWhateverIsHeld() {
        OperationGate.release(OperationGate.ownerId());
    }

    @Test
    public void oneOperationAtATime() {
        assertFalse(OperationGate.isBusy());

        assertTrue(OperationGate.tryStartProbe());
        try {
            assertTrue(OperationGate.isBusy());
            assertFalse(OperationGate.tryStartProbe());
        } finally {
            OperationGate.finishProbe();
        }
        assertFalse(OperationGate.isBusy());

        // the gate must be reusable after a release
        assertTrue(OperationGate.tryStartProbe());
        OperationGate.finishProbe();
        assertFalse(OperationGate.isBusy());
    }

    @Test
    public void aLockBelongsToItsOwnerAndCarriesAStage() {
        assertTrue(OperationGate.acquire("op-1", "running"));
        assertFalse("a busy lock refuses everyone, its owner included", OperationGate.acquire("op-1", "running"));
        assertFalse(OperationGate.acquire("op-2", "running"));
        assertEquals("op-1", OperationGate.ownerId());
        assertEquals("running", OperationGate.stageOf());

        OperationGate.stage("op-1", "installing");
        assertEquals("installing", OperationGate.stageOf());
        OperationGate.stage("op-2", "copying");
        assertEquals("a foreign stage change is ignored", "installing", OperationGate.stageOf());

        OperationGate.release("op-2");
        assertTrue("a foreign release does not free the lock", OperationGate.isBusy());
        OperationGate.release(null);
        assertTrue("and neither does a null one", OperationGate.isBusy());

        OperationGate.release("op-1");
        assertFalse(OperationGate.isBusy());
        assertNull(OperationGate.ownerId());
        assertEquals("", OperationGate.stageOf());
    }

    @Test
    public void theOldEntryPointIsJustAnOwnerNamedProbe() {
        assertTrue(OperationGate.tryStartProbe());
        assertEquals("probe", OperationGate.ownerId());
        assertFalse("a bridge operation cannot slip past a running probe", OperationGate.acquire("op-1", "running"));
        OperationGate.finishProbe();

        assertTrue(OperationGate.acquire("op-1", "running"));
        assertFalse("and a probe cannot slip past a bridge operation", OperationGate.tryStartProbe());
        OperationGate.release("op-1");
    }
}
