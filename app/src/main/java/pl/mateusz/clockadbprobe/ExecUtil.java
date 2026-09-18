package pl.mateusz.clockadbprobe;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs diagnostic commands while bounding time and captured output. */
public final class ExecUtil {
    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    private static final long PGID_DISCOVERY_GRACE_MILLIS = 250L;
    private static final long LATE_PGID_DISCOVERY_MILLIS = 1_000L;
    private static final long TERMINATION_GRACE_MILLIS = 500L;
    private static final long DRAIN_GRACE_MILLIS = 1_000L;
    private static final int MAX_CAPTURE_CHARS = 65_536;
    private static final String TRUNCATED_MARKER = "...(truncated)\n";
    static final String REMOTE_PGID_MARKER = "__CLOCK_AGENT_PGID__=";
    private static final AtomicReference<RemoteExecution> REMOTE_EXECUTION =
            new AtomicReference<>();

    interface RemoteShellPlatform {
        Process start(String command) throws Exception;
        void signalGroup(long pgid, String signal) throws Exception;
    }

    private interface ProcessStarter {
        Process start() throws Exception;
    }

    /** @return [0]=exit code, TIMEOUT, CANCELLED, or EXCEPTION; [1]=stdout; [2]=stderr */
    public static String[] run(String... cmd) {
        return run(DEFAULT_TIMEOUT_MILLIS, cmd);
    }

    static String[] run(long timeoutMillis, final String... cmd) {
        return execute(timeoutMillis, new ProcessStarter() {
            @Override public Process start() throws Exception {
                return new ProcessBuilder(cmd).redirectErrorStream(false).start();
            }
        }, null);
    }

    /** Runs an authenticated agent command in its own Android session/process group. */
    public static String[] runRemoteShell(String command) {
        return runRemoteShell(DEFAULT_TIMEOUT_MILLIS, command, new AndroidRemoteShellPlatform());
    }

    static String[] runRemoteShell(long timeoutMillis, final String command,
                                   RemoteShellPlatform platform) {
        if (command == null) {
            return new String[]{"EXCEPTION", "",
                    "java.lang.NullPointerException: command"};
        }
        final RemoteExecution execution = new RemoteExecution(platform);
        if (!REMOTE_EXECUTION.compareAndSet(null, execution)) {
            return new String[]{"EXCEPTION", "",
                    "java.lang.IllegalStateException: another remote command is already running"};
        }
        try {
            return execute(timeoutMillis, new ProcessStarter() {
                @Override public Process start() throws Exception {
                    return execution.platform.start(command);
                }
            }, execution);
        } finally {
            execution.finish();
            REMOTE_EXECUTION.compareAndSet(execution, null);
        }
    }

    /** Cancels only the active authenticated /agent/exec command. */
    public static boolean cancelRemoteShell() {
        RemoteExecution execution = REMOTE_EXECUTION.get();
        return execution != null && execution.cancel();
    }

    private static String[] execute(long timeoutMillis, ProcessStarter starter,
                                    final RemoteExecution remote) {
        String[] result = new String[]{"EXCEPTION", "", ""};
        if (timeoutMillis <= 0L) {
            result[2] = "java.lang.IllegalArgumentException: timeoutMillis must be positive";
            return result;
        }

        Process process = null;
        StreamCollector stdout = null;
        StreamCollector stderr = null;
        Thread stdoutThread = null;
        Thread stderrThread = null;
        ProcessWaiter processWaiter = null;
        try {
            process = starter.start();
            closeQuietly(process.getOutputStream());

            stdout = new StreamCollector(process.getInputStream(), null);
            stderr = new StreamCollector(process.getErrorStream(),
                    remote == null ? null : new PgidConsumer() {
                        @Override public void accept(long pgid) { remote.attachPgid(pgid); }
                    });
            stdoutThread = collectorThread("exec-stdout", stdout);
            stderrThread = collectorThread("exec-stderr", stderr);
            stdoutThread.start();
            stderrThread.start();

            processWaiter = new ProcessWaiter(process);
            Thread waiterThread = new Thread(processWaiter, "exec-waiter");
            waiterThread.setDaemon(true);
            waiterThread.start();
            if (remote != null) remote.attachProcess(process, processWaiter);

            if (processWaiter.await(timeoutMillis)) {
                result[0] = remote != null && remote.isCancellationRequested()
                        ? "CANCELLED" : String.valueOf(processWaiter.exitCode());
            } else {
                result[0] = "TIMEOUT";
                if (remote != null) remote.terminate();
                else terminateSingleProcess(process, processWaiter);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result[2] = String.valueOf(interrupted);
            if (remote != null) remote.terminate();
            else terminateSingleProcess(process, processWaiter);
        } catch (Throwable failure) {
            result[2] = String.valueOf(failure);
            if (remote != null) remote.terminate();
            else terminateSingleProcess(process, processWaiter);
        } finally {
            awaitCollectors(stdoutThread, stderrThread);
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
            awaitCollectors(stdoutThread, stderrThread);
            if (remote != null) remote.finish();

            if (stdout != null) result[1] = stdout.captured();
            if (stderr != null) {
                String capturedError = stderr.captured();
                if (result[2].isEmpty()) result[2] = capturedError;
                else if (!capturedError.isEmpty()) result[2] = bounded(capturedError + result[2]);
            }
            if (remote != null) {
                String cleanupFailure = remote.cleanupFailure();
                if (!cleanupFailure.isEmpty()) {
                    if (!result[2].isEmpty() && !result[2].endsWith("\n")) result[2] += "\n";
                    result[2] = bounded(result[2] + cleanupFailure + "\n");
                }
            }
            if (remote != null && remote.isCancellationRequested()
                    && !"TIMEOUT".equals(result[0])) {
                result[0] = "CANCELLED";
            }
        }
        return result;
    }

    private static Thread collectorThread(String name, StreamCollector collector) {
        Thread thread = new Thread(collector, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void terminateSingleProcess(Process process, ProcessWaiter waiter) {
        if (process == null) return;
        process.destroy();
        try {
            if (waiter == null || !waiter.await(TERMINATION_GRACE_MILLIS)) {
                forceDestroy(process);
                if (waiter != null) waiter.await(TERMINATION_GRACE_MILLIS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            forceDestroy(process);
        } catch (Throwable ignored) {
            process.destroy();
        }
    }

    private static void forceDestroy(Process process) {
        try {
            Method destroyForcibly = Process.class.getMethod("destroyForcibly");
            destroyForcibly.invoke(process);
        } catch (Throwable unavailable) {
            process.destroy();
        }
    }

    private static void awaitCollectors(Thread stdoutThread, Thread stderrThread) {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(DRAIN_GRACE_MILLIS);
        joinUntil(stdoutThread, deadlineNanos);
        joinUntil(stderrThread, deadlineNanos);
    }

    private static void joinUntil(Thread thread, long deadlineNanos) {
        if (thread == null || !thread.isAlive()) return;
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) return;
        long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
        try {
            thread.join(millis, nanos);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort cleanup; the process result remains the primary outcome.
        }
    }

    private static String bounded(String text) {
        if (text.length() <= MAX_CAPTURE_CHARS) return text;
        return text.substring(0, MAX_CAPTURE_CHARS - TRUNCATED_MARKER.length())
                + TRUNCATED_MARKER;
    }

    private static final class AndroidRemoteShellPlatform implements RemoteShellPlatform {
        private static final String WRAPPER = "printf '" + REMOTE_PGID_MARKER
                + "%s\\n' \"$$\" >&2; exec /system/bin/sh -c \"$1\"";

        @Override public Process start(String command) throws Exception {
            return new ProcessBuilder("/system/bin/setsid", "/system/bin/sh", "-c",
                    WRAPPER, "agent-exec", command)
                    .redirectErrorStream(false)
                    .start();
        }

        @Override public void signalGroup(long pgid, String signal) throws Exception {
            if (pgid <= 0L || pgid > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid process group: " + pgid);
            }
            int number = "TERM".equals(signal) ? OsConstants.SIGTERM : OsConstants.SIGKILL;
            try {
                Os.kill(-(int) pgid, number);
            } catch (ErrnoException failure) {
                // ESRCH means the complete group already exited between the grace
                // check and signal; every other errno is a cleanup failure.
                if (failure.errno != OsConstants.ESRCH) throw failure;
            }
        }
    }

    private static final class RemoteExecution {
        final RemoteShellPlatform platform;
        private final CountDownLatch pgidReady = new CountDownLatch(1);
        private final CountDownLatch terminationFinished = new CountDownLatch(1);
        private Process process;
        private ProcessWaiter waiter;
        private long pgid = -1L;
        private boolean cancellationRequested;
        private boolean terminationStarted;
        private boolean complete;
        private String cleanupFailure = "";

        RemoteExecution(RemoteShellPlatform platform) { this.platform = platform; }

        void attachProcess(Process process, ProcessWaiter waiter) {
            boolean cancelImmediately;
            synchronized (this) {
                this.process = process;
                this.waiter = waiter;
                cancelImmediately = cancellationRequested;
            }
            if (cancelImmediately) terminate();
        }

        void attachPgid(long pgid) {
            if (pgid <= 0L) return;
            synchronized (this) {
                if (this.pgid > 0L) return;
                this.pgid = pgid;
            }
            pgidReady.countDown();
        }

        boolean cancel() {
            Process processToCancel;
            synchronized (this) {
                // A finished session leader is not proof that every member of
                // its process group exited. Keep recovery available until the
                // whole remote execution has completed and been unregistered.
                if (complete) return false;
                cancellationRequested = true;
                processToCancel = process;
            }
            if (processToCancel != null) terminate();
            return true;
        }

        void terminate() {
            Process target;
            ProcessWaiter targetWaiter;
            boolean ownsTermination;
            synchronized (this) {
                if (terminationStarted) {
                    target = null;
                    targetWaiter = null;
                    ownsTermination = false;
                } else {
                    target = process;
                    targetWaiter = waiter;
                    if (target == null) return;
                    terminationStarted = true;
                    ownsTermination = true;
                }
            }
            if (!ownsTermination) {
                awaitTerminationFinished();
                return;
            }

            try {
                long group = awaitPgid(PGID_DISCOVERY_GRACE_MILLIS);
                if (group <= 0L) {
                    // Cancellation can arrive before the stderr collector has
                    // consumed the wrapper's first-line marker. Stop the known
                    // leader, then give the already-buffered marker a second
                    // chance before deciding that no process group exists.
                    target.destroy();
                    group = awaitPgid(LATE_PGID_DISCOVERY_MILLIS);
                }
                if (group > 0L) {
                    signalIgnoringFailure(group, "TERM");
                    // The session leader can exit on TERM while a descendant that
                    // ignores TERM remains in the same process group. Give every
                    // member the full grace period, then always signal the group.
                    waitGracePeriod(TERMINATION_GRACE_MILLIS);
                    signalIgnoringFailure(group, "KILL");
                } else {
                    target.destroy();
                    if (awaitIgnoringInterrupt(targetWaiter, TERMINATION_GRACE_MILLIS)) return;
                    forceDestroy(target);
                }
                if (!awaitIgnoringInterrupt(targetWaiter, TERMINATION_GRACE_MILLIS)) {
                    forceDestroy(target);
                }
            } finally {
                terminationFinished.countDown();
            }
        }

        /** Atomically closes the cancellation window and waits for its cleanup owner. */
        void finish() {
            boolean startRequestedTermination;
            boolean waitForTermination;
            synchronized (this) {
                if (complete) return;
                startRequestedTermination = cancellationRequested
                        && !terminationStarted && process != null;
                waitForTermination = terminationStarted;
                if (!startRequestedTermination && !waitForTermination) {
                    complete = true;
                    return;
                }
            }
            if (startRequestedTermination) terminate();
            else awaitTerminationFinished();
            synchronized (this) { complete = true; }
        }

        private void awaitTerminationFinished() {
            boolean interrupted = false;
            while (true) {
                try {
                    terminationFinished.await();
                    break;
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private long awaitPgid(long timeoutMillis) {
            try {
                pgidReady.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            synchronized (this) { return pgid; }
        }

        private void signalIgnoringFailure(long pgid, String signal) {
            try {
                platform.signalGroup(pgid, signal);
            } catch (Throwable failure) {
                synchronized (this) {
                    cleanupFailure = "process-group " + signal + " failed: " + failure;
                }
            }
        }

        synchronized boolean isCancellationRequested() { return cancellationRequested; }
        synchronized String cleanupFailure() { return cleanupFailure; }
    }

    private static void waitGracePeriod(long millis) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        boolean interrupted = false;
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) break;
            try {
                TimeUnit.NANOSECONDS.sleep(remainingNanos);
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static boolean awaitIgnoringInterrupt(ProcessWaiter waiter, long timeoutMillis) {
        if (waiter == null) return false;
        try {
            return waiter.await(timeoutMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final class ProcessWaiter implements Runnable {
        private final Process process;
        private final CountDownLatch finished = new CountDownLatch(1);
        private int exitCode;

        ProcessWaiter(Process process) { this.process = process; }

        @Override public void run() {
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        }

        boolean await(long timeoutMillis) throws InterruptedException {
            return finished.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        int exitCode() { return exitCode; }
        boolean isFinished() { return finished.getCount() == 0L; }
    }

    private interface PgidConsumer { void accept(long pgid); }

    private static final class StreamCollector implements Runnable {
        private final InputStream input;
        private final PgidConsumer pgidConsumer;
        private final StringBuilder output = new StringBuilder();
        private final StringBuilder firstLine = new StringBuilder();
        private boolean firstLinePending;
        private boolean truncated;

        StreamCollector(InputStream input, PgidConsumer pgidConsumer) {
            this.input = input;
            this.pgidConsumer = pgidConsumer;
            this.firstLinePending = pgidConsumer != null;
        }

        @Override public void run() {
            char[] buffer = new char[4_096];
            try {
                InputStreamReader reader = new InputStreamReader(input);
                int count;
                while ((count = reader.read(buffer)) != -1) capture(buffer, count);
            } catch (IOException ignored) {
                // Closing the stream is how a timed-out process unblocks this reader.
            }
        }

        private synchronized void capture(char[] buffer, int count) {
            int start = 0;
            if (firstLinePending) {
                for (int i = 0; i < count; i++) {
                    char value = buffer[i];
                    firstLine.append(value);
                    if (value == '\n') {
                        consumeFirstLine();
                        start = i + 1;
                        break;
                    }
                }
                if (firstLinePending) return;
            }
            append(buffer, start, count - start);
        }

        private void consumeFirstLine() {
            String line = firstLine.toString();
            String trimmed = line.trim();
            boolean marker = false;
            if (trimmed.startsWith(REMOTE_PGID_MARKER)) {
                try {
                    long pgid = Long.parseLong(trimmed.substring(REMOTE_PGID_MARKER.length()));
                    pgidConsumer.accept(pgid);
                    marker = pgid > 0L;
                } catch (NumberFormatException ignored) {
                    marker = false;
                }
            }
            if (!marker) append(line.toCharArray(), 0, line.length());
            firstLine.setLength(0);
            firstLinePending = false;
        }

        private void append(char[] buffer, int offset, int count) {
            int remaining = MAX_CAPTURE_CHARS - TRUNCATED_MARKER.length() - output.length();
            if (remaining > 0) output.append(buffer, offset, Math.min(remaining, count));
            if (count > remaining) truncated = true;
        }

        synchronized String captured() {
            if (firstLinePending && firstLine.length() > 0) {
                append(firstLine.toString().toCharArray(), 0, firstLine.length());
                firstLine.setLength(0);
                firstLinePending = false;
            }
            if (truncated && output.length() <= MAX_CAPTURE_CHARS - TRUNCATED_MARKER.length()) {
                output.append(TRUNCATED_MARKER);
            }
            return output.toString();
        }
    }
}
