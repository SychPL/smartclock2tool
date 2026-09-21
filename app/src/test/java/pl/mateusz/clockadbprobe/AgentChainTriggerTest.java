package pl.mateusz.clockadbprobe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import pl.mateusz.clockadbprobe.bridge.ExecutorLock;
import pl.mateusz.clockadbprobe.bridge.Executions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The chain must not be startable twice at once, whoever asks. The real starter runs an exploit, so the test
 * replaces it with one that simply blocks until released.
 */
public class AgentChainTriggerTest {
    private final Map<String, String> files = new HashMap<>();
    private BlockingStarter starter;

    @Before
    public void install() {
        Executions.install(new ExecutorLock(new ExecutorLock.Files() {
            public String read(String name) { return files.get(name); }
            public void write(String name, String text) { files.put(name, text); }
            public void delete(String name) { files.remove(name); }
        }, (pid, startTicks) -> ExecutorLock.Liveness.DEAD, () -> "b1"));
        starter = new BlockingStarter();
        AgentRuntime.setChainStarter(starter);
    }

    @After
    public void restore() {
        starter.release();
        AgentRuntime.setChainStarter(null);
        OperationGate.release(OperationGate.ownerId());
        files.clear();
    }

    @Test
    public void twoAgentTriggersDoNotStartTwoChains() throws Exception {
        assertTrue(AgentRuntime.startChain(null));
        assertTrue("the starter should be running", starter.started.await(5, TimeUnit.SECONDS));

        assertFalse("the second one must be refused while the first runs", AgentRuntime.startChain(null));
        assertEquals("exactly one chain ran", 1, starter.starts);

        starter.release();
        assertTrue(starter.finished.await(5, TimeUnit.SECONDS));
        assertTrue("the lock is released by the worker, not by the latch", awaitIdle());
        assertTrue("and it is allowed again once the first is over", AgentRuntime.startChain(null));
        assertTrue(starter.started.await(5, TimeUnit.SECONDS));
    }


    /** The worker releases the lock after it signals it is finished, so the test waits for the release itself. */
    private static boolean awaitIdle() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if ("idle".equals(Executions.lock().chain()) && !OperationGate.isBusy()) return true;
            Thread.sleep(20);
        }
        return false;
    }

    static final class BlockingStarter implements AgentRuntime.ChainStarter {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        private final CountDownLatch gate = new CountDownLatch(1);
        volatile int starts;

        public int start(android.content.Context application, RootKit.Log log) throws Exception {
            starts++;
            started.countDown();
            gate.await(5, TimeUnit.SECONDS);
            finished.countDown();
            return 0;
        }

        void release() {
            gate.countDown();
        }
    }
}
