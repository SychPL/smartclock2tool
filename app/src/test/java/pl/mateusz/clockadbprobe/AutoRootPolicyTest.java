package pl.mateusz.clockadbprobe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoRootPolicyTest {
    private final MemoryStore store = new MemoryStore();
    private final AutoRootPolicy policy = new AutoRootPolicy(store);

    @Test public void offByDefaultAndOnlyRunsOncePerSuccessfulBoot() {
        assertEquals(AutoRootPolicy.Decision.SKIP, policy.claim("boot-1"));
        assertTrue(policy.enable("boot-0"));
        assertEquals(AutoRootPolicy.Decision.START, policy.claim("boot-1"));
        assertEquals(AutoRootPolicy.Decision.SKIP, policy.claim("boot-1"));
        assertTrue(policy.finish("boot-1", true));
        assertEquals(AutoRootPolicy.Decision.SKIP, policy.claim("boot-1"));
        assertEquals(AutoRootPolicy.Decision.START, policy.claim("boot-2"));
    }

    @Test public void interruptedAttemptBlocksNextBootUntilExplicitRearm() {
        assertTrue(policy.enable("boot-0"));
        assertEquals(AutoRootPolicy.Decision.START, policy.claim("boot-1"));
        // A reboot creates a new process but keeps the committed marker.
        AutoRootPolicy afterReboot = new AutoRootPolicy(store);
        assertEquals(AutoRootPolicy.Decision.BLOCKED, afterReboot.claim("boot-2"));
        assertEquals(AutoRootPolicy.BLOCKED, policy.state());
        assertEquals(AutoRootPolicy.Decision.SKIP, policy.claim("boot-3"));
        assertTrue(policy.enable("boot-2"));
        assertEquals(AutoRootPolicy.Decision.START, policy.claim("boot-3"));
    }

    @Test public void failureBlocksAndStaleCompletionCannotUnlock() {
        assertTrue(policy.enable("boot-0"));
        assertEquals(AutoRootPolicy.Decision.START, policy.claim("boot-1"));
        assertFalse(policy.finish("wrong-boot", true));
        assertTrue(policy.finish("boot-1", false));
        assertEquals(AutoRootPolicy.BLOCKED, policy.state());
        assertFalse(policy.finish("boot-1", true));
        assertEquals(AutoRootPolicy.Decision.SKIP, policy.claim("boot-2"));
    }

    @Test public void writeFailureNeverStartsExploit() {
        assertTrue(policy.enable("boot-0"));
        store.failSave = true;
        assertEquals(AutoRootPolicy.Decision.STORAGE_FAILED, policy.claim("boot-1"));
        assertEquals(AutoRootPolicy.READY, policy.state());
    }

    @Test public void cannotToggleWhileAttemptIsRunning() {
        assertTrue(policy.enable("boot-0"));
        assertEquals(AutoRootPolicy.Decision.START, policy.claim("boot-1"));
        assertFalse(policy.disable());
        assertFalse(policy.enable("boot-1"));
        assertEquals(AutoRootPolicy.RUNNING, policy.state());
    }

    @Test public void enablingNeverStartsOnTheSameBoot() {
        assertTrue(policy.enable("boot-1"));
        assertEquals(AutoRootPolicy.Decision.SKIP, policy.claim("boot-1"));
        assertEquals(AutoRootPolicy.Decision.START, policy.claim("boot-2"));
    }

    @Test public void missingBootIdentityAndUnknownStateFailClosed() {
        assertTrue(policy.enable("boot-0"));
        assertEquals(AutoRootPolicy.Decision.BLOCKED, policy.claim(""));
        assertEquals(AutoRootPolicy.BLOCKED, policy.state());
        store.state = "corrupt";
        assertEquals(AutoRootPolicy.Decision.BLOCKED, policy.claim("boot-1"));
    }

    @Test public void failedCompletionWriteLeavesCrashGuardArmed() {
        assertTrue(policy.enable("boot-0"));
        assertEquals(AutoRootPolicy.Decision.START, policy.claim("boot-1"));
        store.failSave = true;
        assertFalse(policy.finish("boot-1", true));
        store.failSave = false;
        assertEquals(AutoRootPolicy.Decision.BLOCKED,
                new AutoRootPolicy(store).claim("boot-2"));
    }

    private static final class MemoryStore implements AutoRootPolicy.Store {
        String state = AutoRootPolicy.OFF;
        String bootId = "";
        boolean failSave;
        public String state() { return state; }
        public String bootId() { return bootId; }
        public boolean save(String nextState, String nextBoot) {
            if (failSave) return false;
            state = nextState;
            bootId = nextBoot;
            return true;
        }
    }
}
