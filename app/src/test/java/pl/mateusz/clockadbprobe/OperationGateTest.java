package pl.mateusz.clockadbprobe;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OperationGateTest {
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
}
