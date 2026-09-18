package pl.mateusz.clockadbprobe;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AgentRuntimeTest {

    @Test
    public void execstopCancelsTheCurrentAgentCommand() throws Exception {
        assertExecStopCancelsCurrentCommand(new CancelAction() {
            @Override public boolean cancel() {
                return AgentRuntime.trigger(null, "execstop");
            }
        });
    }

    @Test
    public void activityProbeExecstopCancelsTheCurrentAgentCommand() throws Exception {
        assertExecStopCancelsCurrentCommand(new CancelAction() {
            @Override public boolean cancel() {
                return MainActivity.tryStopCurrentExec("execstop");
            }
        });
    }

    private static void assertExecStopCancelsCurrentCommand(CancelAction cancelAction)
            throws Exception {
        Path temporaryDirectory = Files.createTempDirectory("agent-runtime-execstop");
        Path marker = temporaryDirectory.resolve("unexpected-marker");
        AtomicReference<String[]> result = new AtomicReference<>();
        RecordingRemotePlatform platform = new RecordingRemotePlatform(marker.toString());
        Thread commandThread = new Thread(() -> result.set(ExecUtil.runRemoteShell(
                10_000L, "ignored-test-command", platform)));
        try {
            commandThread.start();

            long deadline = System.nanoTime() + 2_000_000_000L;
            boolean stopped = false;
            while (!stopped && System.nanoTime() < deadline) {
                stopped = cancelAction.cancel();
                if (!stopped) Thread.sleep(10L);
            }

            assertTrue("execstop did not find the active command", stopped);
            commandThread.join(5_000L);
            assertFalse("execstop left the command running", commandThread.isAlive());
            assertEquals("CANCELLED", result.get()[0]);
        } finally {
            ExecUtil.cancelRemoteShell();
            commandThread.join(5_000L);
            Files.deleteIfExists(marker);
            Files.deleteIfExists(temporaryDirectory);
        }
    }

    private interface CancelAction {
        boolean cancel();
    }

}
