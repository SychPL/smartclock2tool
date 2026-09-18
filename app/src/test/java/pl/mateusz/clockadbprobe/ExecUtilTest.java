package pl.mateusz.clockadbprobe;

import org.junit.Test;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ExecUtilTest {

    @Test
    public void commandThatExceedsDeadlineIsKilledAndReturnsPromptly() throws Exception {
        Path temporaryDirectory = Files.createTempDirectory("exec-util-test");
        Path marker = temporaryDirectory.resolve("survived-timeout");
        try {
            long startedAt = System.nanoTime();

            String[] result = ExecUtil.run(
                    300L, childCommand("sleep-then-write", marker.toString()));

            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            assertEquals("TIMEOUT", result[0]);
            assertTrue("timeout cleanup took " + elapsedMillis + " ms",
                    elapsedMillis < 5_000L);

            Thread.sleep(1_500L);
            assertFalse("timed-out child process was still running", Files.exists(marker));
        } finally {
            Files.deleteIfExists(marker);
            Files.deleteIfExists(temporaryDirectory);
        }
    }

    @Test
    public void drainsStdoutAndStderrConcurrently() {
        String[] result = ExecUtil.run(10_000L, childCommand("both-streams"));

        assertEquals("0", result[0]);
        assertTrue(result[1].startsWith("OUT-BEGIN"));
        assertTrue(result[2].startsWith("ERR-BEGIN"));
    }

    @Test
    public void truncatesCapturedTextButContinuesDrainingUntilProcessExits() {
        String[] result = ExecUtil.run(10_000L, childCommand("large-stdout"));

        assertEquals("0", result[0]);
        assertTrue("captured stdout was " + result[1].length() + " chars",
                result[1].length() <= 65_536);
        assertTrue(result[1].endsWith("...(truncated)\n"));
    }

    @Test
    public void remoteCancellationDoesNotTouchAnOrdinaryExecUtilCommand() throws Exception {
        Path temporaryDirectory = Files.createTempDirectory("exec-util-local-isolation");
        Path marker = temporaryDirectory.resolve("local-completed");
        AtomicReference<String[]> result = new AtomicReference<>();
        Thread commandThread = new Thread(() -> result.set(ExecUtil.run(
                5_000L, childCommand("sleep-then-write", marker.toString()))));
        try {
            commandThread.start();
            Thread.sleep(100L);

            assertFalse("remote recovery claimed a local command", ExecUtil.cancelRemoteShell());
            commandThread.join(3_000L);

            assertFalse("local command did not finish", commandThread.isAlive());
            assertEquals("0", result.get()[0]);
            assertTrue("local command was killed by remote recovery", Files.exists(marker));
        } finally {
            commandThread.join(5_000L);
            Files.deleteIfExists(marker);
            Files.deleteIfExists(temporaryDirectory);
        }
    }

    @Test
    public void cancelRemoteShellSignalsItsProcessGroupAndHidesThePgidMarker() throws Exception {
        Path temporaryDirectory = Files.createTempDirectory("exec-util-cancel-test");
        Path marker = temporaryDirectory.resolve("survived-cancel");
        AtomicReference<String[]> result = new AtomicReference<>();
        RecordingRemotePlatform platform = new RecordingRemotePlatform(marker.toString());
        Thread commandThread = new Thread(() -> result.set(ExecUtil.runRemoteShell(
                10_000L, "ignored-test-command", platform)));
        try {
            commandThread.start();

            long cancelDeadline = System.nanoTime() + 2_000_000_000L;
            boolean cancelled = false;
            while (!cancelled && System.nanoTime() < cancelDeadline) {
                cancelled = ExecUtil.cancelRemoteShell();
                if (!cancelled) Thread.sleep(10L);
            }

            assertTrue("active command was never available for cancellation", cancelled);
            commandThread.join(5_000L);
            assertFalse("cancelled ExecUtil.run did not return", commandThread.isAlive());
            assertEquals("CANCELLED", result.get()[0]);
            assertEquals("TERM:4242", platform.signals.get(0));
            assertEquals("KILL:4242", platform.signals.get(1));
            assertFalse(result.get()[2].contains("CLOCK_AGENT_PGID"));
            assertFalse(result.get()[2].contains("4242"));

            Thread.sleep(1_500L);
            assertFalse("cancelled child process was still running", Files.exists(marker));
            assertFalse("completed execution remained registered", ExecUtil.cancelRemoteShell());

            String[] next = ExecUtil.run(5_000L, childCommand("small-stdout"));
            assertEquals("0", next[0]);
            assertTrue(next[1].startsWith("NEXT"));
        } finally {
            ExecUtil.cancelRemoteShell();
            commandThread.join(5_000L);
            Files.deleteIfExists(marker);
            Files.deleteIfExists(temporaryDirectory);
        }
    }

    @Test
    public void remoteTimeoutEscalatesFromTermToKillForTheProcessGroup() {
        RecordingRemotePlatform platform = new RecordingRemotePlatform(null);

        String[] result = ExecUtil.runRemoteShell(
                200L, "ignored-test-command", platform);

        assertEquals("TIMEOUT", result[0]);
        assertEquals("TERM:4242", platform.signals.get(0));
        assertEquals("KILL:4242", platform.signals.get(1));
        long graceMillis = TimeUnit.NANOSECONDS.toMillis(
                platform.signalNanos.get(1) - platform.signalNanos.get(0));
        assertTrue("process-group grace was only " + graceMillis + " ms",
                graceMillis >= 450L);
    }

    @Test
    public void resultWaitsForConcurrentGroupCleanupAndReportsItsFailure() throws Exception {
        BlockingKillRemotePlatform platform = new BlockingKillRemotePlatform();
        AtomicReference<String[]> result = new AtomicReference<>();
        Thread commandThread = new Thread(() -> result.set(ExecUtil.runRemoteShell(
                10_000L, "ignored-test-command", platform)));
        Thread cancellationThread = new Thread(() -> {
            while (!ExecUtil.cancelRemoteShell()) Thread.yield();
        });
        try {
            commandThread.start();
            cancellationThread.start();

            assertTrue("KILL phase was never reached",
                    platform.killEntered.await(3L, TimeUnit.SECONDS));
            assertTrue("command returned before group cleanup completed", commandThread.isAlive());

            platform.releaseKill.countDown();
            cancellationThread.join(3_000L);
            commandThread.join(3_000L);

            assertFalse("cancellation call remained blocked", cancellationThread.isAlive());
            assertFalse("remote result remained blocked", commandThread.isAlive());
            assertEquals("CANCELLED", result.get()[0]);
            assertTrue(result.get()[2].contains("process-group KILL failed"));
            assertTrue(result.get()[2].contains("synthetic kill failure"));
        } finally {
            platform.releaseKill.countDown();
            cancellationThread.join(3_000L);
            commandThread.join(3_000L);
        }
    }

    @Test
    public void delayedPgidMarkerStillTriggersProcessGroupCleanup() throws Exception {
        RecordingRemotePlatform platform = new RecordingRemotePlatform(null, false, 400L);
        AtomicReference<String[]> result = new AtomicReference<>();
        Thread commandThread = new Thread(() -> result.set(ExecUtil.runRemoteShell(
                10_000L, "ignored-test-command", platform)));
        try {
            commandThread.start();
            long cancelDeadline = System.nanoTime() + 2_000_000_000L;
            boolean cancelled = false;
            while (!cancelled && System.nanoTime() < cancelDeadline) {
                cancelled = ExecUtil.cancelRemoteShell();
                if (!cancelled) Thread.sleep(10L);
            }

            assertTrue("active command was never available for cancellation", cancelled);
            commandThread.join(5_000L);

            assertFalse("delayed-marker command remained blocked", commandThread.isAlive());
            assertEquals("CANCELLED", result.get()[0]);
            assertEquals("TERM:4242", platform.signals.get(0));
            assertEquals("KILL:4242", platform.signals.get(1));
        } finally {
            ExecUtil.cancelRemoteShell();
            commandThread.join(5_000L);
        }
    }

    @Test
    public void remoteTerminationKillsTheGroupEvenWhenItsLeaderExitsOnTerm() {
        RecordingRemotePlatform platform = new RecordingRemotePlatform(null, true);

        String[] result = ExecUtil.runRemoteShell(
                200L, "ignored-test-command", platform);

        assertEquals("TIMEOUT", result[0]);
        assertEquals("TERM:4242", platform.signals.get(0));
        assertEquals("KILL:4242", platform.signals.get(1));
    }

    private static String[] childCommand(String mode, String... extraArguments) {
        String javaExecutable = new File(
                new File(System.getProperty("java.home"), "bin"),
                isWindows() ? "java.exe" : "java").getAbsolutePath();
        String testClasses = ExecUtilChildProcess.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
        String mainClasses = ExecUtil.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();

        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.add("-cp");
        command.add(testClasses + File.pathSeparator + mainClasses);
        command.add(ExecUtilChildProcess.class.getName());
        command.add(mode);
        for (String argument : extraArguments) command.add(argument);
        return command.toArray(new String[0]);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

final class RecordingRemotePlatform implements ExecUtil.RemoteShellPlatform {
    final List<String> signals = new ArrayList<>();
    final List<Long> signalNanos = new ArrayList<>();
    private final String marker;
    private final boolean exitOnTerm;
    private final long markerReadDelayMillis;
    private Process process;

    RecordingRemotePlatform(String marker) {
        this(marker, false, 0L);
    }

    RecordingRemotePlatform(String marker, boolean exitOnTerm) {
        this(marker, exitOnTerm, 0L);
    }

    RecordingRemotePlatform(String marker, boolean exitOnTerm, long markerReadDelayMillis) {
        this.marker = marker;
        this.exitOnTerm = exitOnTerm;
        this.markerReadDelayMillis = markerReadDelayMillis;
    }

    @Override public Process start(String command) throws Exception {
        Process started = new ProcessBuilder(ExecUtilTestCommands.childCommand(
                "remote-marker-sleep", marker == null ? "" : marker)).start();
        process = markerReadDelayMillis > 0L
                ? new DelayedErrorProcess(started, markerReadDelayMillis) : started;
        return process;
    }

    @Override public void signalGroup(long pgid, String signal) {
        signals.add(signal + ":" + pgid);
        signalNanos.add(System.nanoTime());
        if (("KILL".equals(signal) || (exitOnTerm && "TERM".equals(signal)))
                && process != null) process.destroy();
    }
}

final class DelayedErrorProcess extends Process {
    private final Process delegate;
    private final InputStream delayedError;

    DelayedErrorProcess(Process delegate, final long delayMillis) {
        this.delegate = delegate;
        this.delayedError = new FilterInputStream(delegate.getErrorStream()) {
            private boolean delayed;

            private void delayOnce() throws IOException {
                if (delayed) return;
                delayed = true;
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException("delayed error read interrupted", failure);
                }
            }

            @Override public int read() throws IOException {
                delayOnce();
                return super.read();
            }

            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                delayOnce();
                return super.read(buffer, offset, length);
            }
        };
    }

    @Override public OutputStream getOutputStream() { return delegate.getOutputStream(); }
    @Override public InputStream getInputStream() { return delegate.getInputStream(); }
    @Override public InputStream getErrorStream() { return delayedError; }
    @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
    @Override public int exitValue() { return delegate.exitValue(); }
    @Override public void destroy() { delegate.destroy(); }
    @Override public Process destroyForcibly() { delegate.destroyForcibly(); return this; }
    @Override public boolean isAlive() { return delegate.isAlive(); }
}

final class BlockingKillRemotePlatform implements ExecUtil.RemoteShellPlatform {
    final CountDownLatch killEntered = new CountDownLatch(1);
    final CountDownLatch releaseKill = new CountDownLatch(1);
    private Process process;

    @Override public Process start(String command) throws Exception {
        process = new ProcessBuilder(ExecUtilTestCommands.childCommand(
                "remote-marker-sleep", "")).start();
        return process;
    }

    @Override public void signalGroup(long pgid, String signal) throws Exception {
        if ("TERM".equals(signal)) {
            if (process != null) process.destroy();
            return;
        }
        killEntered.countDown();
        if (!releaseKill.await(3L, TimeUnit.SECONDS)) {
            throw new IOException("synthetic kill release timeout");
        }
        throw new IOException("synthetic kill failure");
    }
}

final class ExecUtilTestCommands {
    private ExecUtilTestCommands() {}

    static String[] childCommand(String mode, String... extraArguments) {
        String javaExecutable = new File(
                new File(System.getProperty("java.home"), "bin"),
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "java.exe" : "java").getAbsolutePath();
        String testClasses = ExecUtilChildProcess.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
        String mainClasses = ExecUtil.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.add("-cp");
        command.add(testClasses + File.pathSeparator + mainClasses);
        command.add(ExecUtilChildProcess.class.getName());
        command.add(mode);
        for (String argument : extraArguments) command.add(argument);
        return command.toArray(new String[0]);
    }
}

final class ExecUtilChildProcess {
    private ExecUtilChildProcess() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "sleep-then-write":
                Thread.sleep(1_000L);
                Files.write(new File(args[1]).toPath(), new byte[]{1});
                return;
            case "both-streams":
                writeBothStreams();
                return;
            case "large-stdout":
                writeLarge(System.out, "OUT");
                return;
            case "small-stdout":
                System.out.println("NEXT");
                return;
            case "remote-marker-sleep":
                System.err.println(ExecUtil.REMOTE_PGID_MARKER + "4242");
                System.err.flush();
                Thread.sleep(5_000L);
                if (!args[1].isEmpty()) Files.write(new File(args[1]).toPath(), new byte[]{1});
                return;
            default:
                throw new IllegalArgumentException("unknown mode: " + args[0]);
        }
    }

    private static void writeBothStreams() throws InterruptedException {
        System.out.println("OUT-BEGIN");
        System.out.flush();
        System.err.println("ERR-BEGIN");
        System.err.flush();

        Thread stdout = new Thread(() -> writeLarge(System.out, "OUT"));
        Thread stderr = new Thread(() -> writeLarge(System.err, "ERR"));
        stdout.start();
        stderr.start();
        stdout.join();
        stderr.join();
    }

    private static void writeLarge(PrintStream stream, String prefix) {
        for (int i = 0; i < 20_000; i++) {
            stream.println(prefix + "-0123456789abcdef");
        }
        stream.flush();
    }
}
