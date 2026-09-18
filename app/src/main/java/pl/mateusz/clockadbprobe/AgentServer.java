package pl.mateusz.clockadbprobe;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * LAN remote-control agent for the owner's research workstation.
 * Endpoints (token required):
 *   GET /agent/status?token=..            -> liveness + info
 *   GET /agent/report?token=..            -> full report text
 *   GET /agent/exec?token=..&cmd=..       -> owner shell command (sh -c; blocklist)
 *   GET /agent/probe?token=..&name=..     -> trigger a probe module
 * A best-effort substring blocklist rejects common destructive commands. This
 * owner-only LAN endpoint is not a command sandbox.
 */
public final class AgentServer {

    static final int MAX_REQUEST_LINE_BYTES = 8_192;
    static final int MAX_REQUEST_HEAD_BYTES = 16_384;
    private static final int PRE_AUTH_READ_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_CONNECTIONS = 6;
    private static final ConnectionLimiter CONNECTIONS =
            new ConnectionLimiter(MAX_CONNECTIONS);

    private static volatile String token = "";

    public interface ProbeTrigger { boolean trigger(String name); }

    private static volatile Context appContext;
    private static volatile ProbeTrigger probeTrigger;
    private static volatile ProbeTrigger defaultProbeTrigger;
    private static volatile ServerSocket ss;
    private static volatile int port = -1;
    public static volatile String lastError = null;

    private static final String[] BLOCKED = {
            "reboot", "fastboot", " dd ", "flash", "erase", "wipe",
            "factory reset", "factory_reset", "rm -rf", "chmod", "chown",
            "mount", "umount", "mkfs", "vbmeta", "bootloader", "recovery",
            "format ", "system/bin/rm", "rm /system", "rm -r /"
    };

    public static synchronized void setProbeTrigger(ProbeTrigger t) { probeTrigger = t; }
    public static synchronized void clearProbeTrigger(ProbeTrigger expected) {
        if (probeTrigger == expected) probeTrigger = null;
    }
    public static void setDefaultProbeTrigger(ProbeTrigger t) { defaultProbeTrigger = t; }
    static ProbeTrigger selectedProbeTrigger() {
        ProbeTrigger explicit = probeTrigger;
        return explicit != null ? explicit : defaultProbeTrigger;
    }
    public static boolean isRunning() { ServerSocket s = ss; return s != null && !s.isClosed(); }
    public static int port() { return port; }
    public static String token() { return token; }

    public static synchronized void start(final Context context, final int[] ports) {
        try {
            Context applicationContext = context != null ? context.getApplicationContext() : null;
            if (applicationContext == null) {
                throw new IllegalArgumentException("non-null application context required");
            }
            appContext = applicationContext;
        } catch (Throwable failure) {
            lastError = "agent context: " + failure;
            Report.get().line("CONCLUSIONS", "Agent FAILED to initialize context: " + failure);
            return;
        }
        if (isRunning()) return;
        try {
            token = loadOrCreateToken(requireAppContext());
        } catch (Throwable failure) {
            lastError = "agent token: " + failure;
            Report.get().line("CONCLUSIONS", "Agent FAILED to initialize token: " + failure);
            return;
        }
        for (int p : ports) {
            ServerSocket cand = null;
            try {
                cand = new ServerSocket();
                cand.setReuseAddress(true);
                cand.bind(new InetSocketAddress(p), 8);
            } catch (Throwable t) {
                lastError = "port " + p + ": " + t;
                try { if (cand != null) cand.close(); } catch (Throwable ignored) {}
                continue;
            }
            ss = cand;
            port = p;
            Thread t = new Thread(new Runnable() {
                public void run() { acceptLoop(); }
            }, "agent-accept");
            t.setDaemon(true);
            t.start();
            Report.get().line("CONCLUSIONS", "Agent UP on port " + p + " (token required)");
            return;
        }
        Report.get().line("CONCLUSIONS", "Agent FAILED to start: " + lastError);
    }

    private static void acceptLoop() {
        ServerSocket s = ss;
        while (s != null && !s.isClosed()) {
            try {
                final Socket c = s.accept();
                c.setSoTimeout(PRE_AUTH_READ_TIMEOUT_MILLIS);
                if (!CONNECTIONS.tryAcquire()) {
                    c.close();
                    continue;
                }
                Thread connection = new Thread(new Runnable() {
                    public void run() {
                        try {
                            handle(c);
                        } finally {
                            CONNECTIONS.release();
                        }
                    }
                }, "agent-conn");
                connection.setDaemon(true);
                try {
                    connection.start();
                } catch (Throwable failure) {
                    CONNECTIONS.release();
                    try { c.close(); } catch (Throwable ignored) {}
                    throw failure;
                }
            } catch (Throwable t) {
                if (s != null && !s.isClosed())
                    Report.get().log("COMMAND RESULTS", "Agent accept: " + t);
            }
        }
    }

    private static void handle(Socket c) {
        try {
            String req;
            try {
                req = readRequestHead(c.getInputStream(), PRE_AUTH_READ_TIMEOUT_MILLIS,
                        new NanoClock() {
                            @Override public long nanoTime() { return System.nanoTime(); }
                        }, new ReadTimeoutSetter() {
                            @Override public void setTimeoutMillis(int timeoutMillis)
                                    throws java.io.IOException {
                                c.setSoTimeout(timeoutMillis);
                            }
                        });
            } catch (RequestLineTooLongException tooLong) {
                reply(c.getOutputStream(), "414 URI Too Long", "request line too long\n");
                c.close();
                return;
            } catch (RequestHeadTooLongException tooLong) {
                reply(c.getOutputStream(), "431 Request Header Fields Too Large",
                        "request headers too large\n");
                c.close();
                return;
            } catch (IncompleteRequestHeadException incomplete) {
                reply(c.getOutputStream(), "400 Bad Request",
                        "incomplete request headers\n");
                c.close();
                return;
            } catch (SocketTimeoutException timeout) {
                reply(c.getOutputStream(), "408 Request Timeout", "request timeout\n");
                c.close();
                return;
            }
            if (req == null || !req.startsWith("GET ")) { c.close(); return; }
            String path = req.substring(4).split(" ")[0];
            OutputStream os = c.getOutputStream();
            String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            Report.get().log("COMMAND RESULTS", "AGENT request: " + route);

            Map<String, String> q = parseQuery(path);

            if (!token.equals(q.get("token"))) {
                reply(os, "403 Forbidden", "bad token\n");
                c.close();
                return;
            }

            if (OperationGate.isBusy()) {
                reply(os, "409 Conflict", "another operation is active\n");
                c.close();
                return;
            }

            boolean directLease = requiresDirectLease(route);
            if (directLease && !OperationGate.tryStartProbe()) {
                reply(os, "409 Conflict", "another operation is active\n");
                c.close();
                return;
            }
            try {
            if (route.equals("/agent/status")) {
                reply(os, "200 OK", "AGENT OK\napp=" + Report.get().renderAppVersion()
                        + "\nagentPort=" + port
                        + "\napkServer=" + (FileServerRef.isRunning() ? FileServerRef.port() : "down")
                        + "\nautotapConnected=" + AutoTapService.connected
                        + "\nautotapArmed=" + AutoTapService.armed
                        + "\nresidentService=" + AgentKeepAliveService.running
                        + "\ntime=" + Report.ts() + "\n");
            } else if (route.equals("/agent/report")) {
                reply(os, "200 OK", Report.get().render(Report.get().renderAppVersion()));
            } else if (route.equals("/agent/exec")) {
                String cmd = q.get("cmd");
                if (cmd == null || cmd.trim().isEmpty()) {
                    reply(os, "400 Bad Request", "missing cmd\n");
                } else if (isBlocked(cmd)) {
                    reply(os, "403 Forbidden", "REFUSED (destructive-pattern blocklist): " + cmd + "\n");
                    Report.get().log("COMMAND RESULTS", "AGENT exec REFUSED: " + cmd);
                } else {
                    String[] r = ExecUtil.runRemoteShell(cmd);
                    StringBuilder out = new StringBuilder();
                    out.append("CMD: ").append(cmd).append('\n')
                       .append("EXIT: ").append(r[0]).append('\n')
                       .append("--- STDOUT ---\n").append(r[1])
                       .append("--- STDERR ---\n").append(r[2]);
                    reply(os, "200 OK", out.toString());
                }
            } else if (route.equals("/agent/screen")) {
                try {
                    byte[] jpg = OemClient.screenshot(requireAppContext());
                    if (jpg == null) {
                        reply(os, "200 OK", "screenshot returned null\n");
                    } else {
                        Report.get().log("COMMAND RESULTS", "AGENT screenshot: " + jpg.length + " bytes JPEG");
                        os.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg"
                                + "\r\nContent-Length: " + jpg.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                        os.write(jpg);
                    }
                } catch (Throwable t) {
                    Report.get().exception("AgentServer.screen", t);
                    reply(os, "500 Internal Server Error", "screenshot failed: " + t + "\n");
                }
            } else if (route.equals("/agent/dex")) {
                // Hot-load a research plugin: downloads a classes.dex and
                // invokes entry.run(Context, String). Avoids APK reinstalls
                // (which would disable the accessibility service each time).
                String url = q.get("url");
                String entry = q.get("entry");
                String arg = q.get("arg") != null ? q.get("arg") : "";
                if (url == null || entry == null) {
                    reply(os, "400 Bad Request", "need url & entry\n");
                } else {
                    reply(os, "200 OK", runDex(url, entry, arg));
                }
            } else if (route.equals("/agent/probe")) {
                String name = q.get("name");
                ProbeTrigger pt = selectedProbeTrigger();
                if (name == null || pt == null) {
                    reply(os, "400 Bad Request", "missing name or no trigger registered\n");
                } else {
                    reply(os, "200 OK", pt.trigger(name) ? "STARTED " + name + "\n"
                                                        : "UNKNOWN OR BUSY " + name + "\n");
                }
            } else {
                reply(os, "404 Not Found", "routes: /agent/status /agent/report /agent/exec /agent/probe /agent/screen /agent/dex\n");
            }
            } finally {
                if (directLease) OperationGate.finishProbe();
            }
            os.flush();
            c.close();
        } catch (Throwable t) {
            Report.get().exception("AgentServer.handle", t);
            try { c.close(); } catch (Throwable ignored) {}
        }
    }

    static String readRequestLine(InputStream input) throws java.io.IOException {
        return readHttpRequest(input, false, 0L, null, null);
    }

    static String readRequestHead(InputStream input) throws java.io.IOException {
        return readHttpRequest(input, true, 0L, null, null);
    }

    interface NanoClock { long nanoTime(); }

    interface ReadTimeoutSetter {
        void setTimeoutMillis(int timeoutMillis) throws java.io.IOException;
    }

    static String readRequestLine(InputStream input, long timeoutMillis,
                                  NanoClock clock, ReadTimeoutSetter timeoutSetter)
            throws java.io.IOException {
        return readHttpRequest(input, false, timeoutMillis, clock, timeoutSetter);
    }

    static String readRequestHead(InputStream input, long timeoutMillis,
                                  NanoClock clock, ReadTimeoutSetter timeoutSetter)
            throws java.io.IOException {
        return readHttpRequest(input, true, timeoutMillis, clock, timeoutSetter);
    }

    private static String readHttpRequest(InputStream input, boolean consumeHead,
                                          long timeoutMillis, NanoClock clock,
                                          ReadTimeoutSetter timeoutSetter)
            throws java.io.IOException {
        final boolean deadlineEnabled = timeoutMillis > 0L;
        if (deadlineEnabled && (clock == null || timeoutSetter == null)) {
            throw new IllegalArgumentException("deadline clock and timeout setter required");
        }
        final long startedNanos = deadlineEnabled ? clock.nanoTime() : 0L;
        final long timeoutNanos = deadlineEnabled
                ? TimeUnit.MILLISECONDS.toNanos(timeoutMillis) : 0L;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        String requestLine = null;
        int requestHeadBytes = 0;
        while (true) {
            if (deadlineEnabled) {
                enforceReadDeadline(startedNanos, timeoutNanos, clock, timeoutSetter);
            }
            int value = input.read();
            if (deadlineEnabled) {
                enforceReadDeadline(startedNanos, timeoutNanos, clock, timeoutSetter);
            }
            if (value < 0) {
                if (!consumeHead) {
                    return line.size() == 0 ? null : line.toString("US-ASCII");
                }
                if (requestLine == null && line.size() == 0) return null;
                throw new IncompleteRequestHeadException();
            }
            if (consumeHead && ++requestHeadBytes > MAX_REQUEST_HEAD_BYTES) {
                throw new RequestHeadTooLongException();
            }
            if (value == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') length--;
                if (requestLine == null) {
                    requestLine = new String(bytes, 0, length, "US-ASCII");
                    if (!consumeHead) return requestLine;
                } else if (length == 0) {
                    return requestLine;
                }
                line.reset();
                continue;
            }
            // The configured limit covers request-line content, not the CRLF
            // terminator. Permit one trailing CR when content is exactly at the
            // limit; any further non-LF byte is rejected on the next iteration.
            if (requestLine == null && line.size() >= MAX_REQUEST_LINE_BYTES
                    && !(value == '\r' && line.size() == MAX_REQUEST_LINE_BYTES)) {
                throw new RequestLineTooLongException();
            }
            line.write(value);
        }
    }

    private static void enforceReadDeadline(long startedNanos, long timeoutNanos,
                                            NanoClock clock,
                                            ReadTimeoutSetter timeoutSetter)
            throws java.io.IOException {
        long elapsedNanos = clock.nanoTime() - startedNanos;
        long remainingNanos = timeoutNanos - elapsedNanos;
        if (remainingNanos <= 0L) {
            throw new SocketTimeoutException("absolute request-head deadline exceeded");
        }
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        if (TimeUnit.MILLISECONDS.toNanos(remainingMillis) < remainingNanos) {
            remainingMillis++;
        }
        int socketTimeout = (int) Math.max(1L,
                Math.min((long) Integer.MAX_VALUE, remainingMillis));
        timeoutSetter.setTimeoutMillis(socketTimeout);
    }

    static final class RequestLineTooLongException extends java.io.IOException {
        RequestLineTooLongException() { super("request line too long"); }
    }

    static final class RequestHeadTooLongException extends java.io.IOException {
        RequestHeadTooLongException() { super("request headers too large"); }
    }

    static final class IncompleteRequestHeadException extends java.io.IOException {
        IncompleteRequestHeadException() { super("incomplete request headers"); }
    }

    static final class ConnectionLimiter {
        private final Semaphore permits;

        ConnectionLimiter(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
            permits = new Semaphore(capacity);
        }

        boolean tryAcquire() { return permits.tryAcquire(); }
        void release() { permits.release(); }
    }

    static boolean isBlocked(String cmd) {
        String l = " " + cmd.toLowerCase() + " ";
        for (String b : BLOCKED) if (l.contains(b)) return true;
        return false;
    }

    private static boolean requiresDirectLease(String route) {
        return route.equals("/agent/report")
                || route.equals("/agent/exec")
                || route.equals("/agent/screen")
                || route.equals("/agent/dex");
    }

    private static String loadOrCreateToken(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                "clock_agent", Context.MODE_PRIVATE);
        String existing = preferences.getString("token", "");
        if (AgentToken.isValid(existing)) return existing;
        String generated = AgentToken.generate(new java.security.SecureRandom());
        if (!preferences.edit().putString("token", generated).commit()) {
            throw new IllegalStateException("cannot persist agent token");
        }
        return generated;
    }

    private static Context requireAppContext() {
        Context context = appContext;
        if (context == null) {
            throw new IllegalStateException("AgentServer application context is not initialized");
        }
        return context;
    }

    private static String runDex(String url, String entry, String arg) {
        final Context context;
        try {
            context = requireAppContext();
        } catch (IllegalStateException failure) {
            return "plugin failed: " + failure.getMessage();
        }
        java.io.File dexFile;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(30000);
            if (c.getResponseCode() != 200) return "HTTP " + c.getResponseCode();
            java.io.File dir = new java.io.File(context.getFilesDir(), "plugins");
            if (!dir.exists()) dir.mkdirs();
            dexFile = new java.io.File(dir, "p_" + System.currentTimeMillis() + ".dex");
            java.io.InputStream is = c.getInputStream();
            java.io.FileOutputStream os2 = new java.io.FileOutputStream(dexFile);
            byte[] b = new byte[16384];
            int n;
            while ((n = is.read(b)) > 0) os2.write(b, 0, n);
            os2.close();
            is.close();
            c.disconnect();
        } catch (Throwable t) {
            return "download failed: " + t;
        }
        try {
            java.io.File optDir = new java.io.File(context.getCodeCacheDir(), "dexopt");
            if (!optDir.exists()) optDir.mkdirs();
            dalvik.system.DexClassLoader cl = new dalvik.system.DexClassLoader(
                    dexFile.getAbsolutePath(), optDir.getAbsolutePath(), null,
                    AgentServer.class.getClassLoader());
            Class<?> k = cl.loadClass(entry);
            Object result;
            try {
                java.lang.reflect.Method m = k.getMethod("run", android.content.Context.class, String.class);
                Object inst = null;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) inst = k.newInstance();
                result = m.invoke(inst, context, arg);
            } catch (NoSuchMethodException e) {
                java.lang.reflect.Method m = k.getMethod("main", String[].class);
                result = m.invoke(null, (Object) new String[]{arg});
            }
            Report.get().log("COMMAND RESULTS", "DEX plugin " + entry + " ok");
            return result != null ? String.valueOf(result) : "(null)";
        } catch (Throwable t) {
            Report.get().exception("AgentServer.runDex", t);
            return "plugin failed: " + t;
        }
    }

    private static Map<String, String> parseQuery(String path) {
        Map<String, String> m = new HashMap<>();
        int i = path.indexOf('?');
        if (i < 0) return m;
        for (String pair : path.substring(i + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                m.put(pair.substring(0, eq),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Throwable t) {
                m.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return m;
    }

    private static void reply(OutputStream os, String status, String body) throws java.io.IOException {
        byte[] b = body.getBytes("UTF-8");
        os.write(("HTTP/1.1 " + status + "\r\nContent-Type: text/plain; charset=utf-8"
                + "\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
        os.write(b);
    }

    /** Small indirection so AgentServer does not depend on MainActivity. */
    public static final class FileServerRef {
        public static boolean isRunning() { return MainActivity.fileServerShared != null && MainActivity.fileServerShared.isRunning(); }
        public static int port() { return MainActivity.fileServerShared != null ? MainActivity.fileServerShared.port() : -1; }
    }
}
