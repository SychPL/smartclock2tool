package pl.mateusz.clockadbprobe;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tiny dependency-free HTTP file server exposing the pulled APKs on the LAN. */
public final class FileServer {

    public interface Log { void log(String line); }

    private ServerSocket ss;
    private Thread acceptThread;
    private int actualPort = -1;
    /** Last start error, surfaced to the UI when start() returns -1. */
    public volatile String lastError = null;

    /** Must be called OFF the UI thread. Tries each port until one binds;
     *  @return bound port, or -1 if all failed. */
    public synchronized int start(final File root, final int[] ports, final Log log) {
        if (ss != null) return actualPort;
        lastError = null;
        for (final int port : ports) {
            ServerSocket candidate = null;
            try {
                candidate = new ServerSocket();
                candidate.setReuseAddress(true); // survive sockets stuck in TIME_WAIT
                candidate.bind(new java.net.InetSocketAddress(port), 8);
            } catch (Throwable t) {
                lastError = "port " + port + ": " + t;
                log.log("FileServer port " + port + " failed: " + t);
                try { if (candidate != null) candidate.close(); } catch (Throwable ignored) {}
                continue;
            }
            ss = candidate;
            actualPort = port;
            acceptThread = new Thread(new Runnable() {
                public void run() {
                    while (!ss.isClosed()) {
                        try {
                            final Socket s = ss.accept();
                            new Thread(new Runnable() {
                                public void run() { handle(s, root, log); }
                            }, "http-conn").start();
                        } catch (Throwable t) {
                            if (!ss.isClosed()) log.log("FileServer accept: " + t);
                        }
                    }
                }
            }, "http-accept");
            acceptThread.start();
            log.log("FileServer listening on 0.0.0.0:" + port + " root=" + root);
            return port;
        }
        return -1;
    }

    public synchronized void stop(Log log) {
        try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
        ss = null;
        actualPort = -1;
        log.log("FileServer stopped");
    }

    public synchronized boolean isRunning() { return ss != null && !ss.isClosed(); }

    public synchronized int port() { return actualPort; }

    private void handle(Socket s, File root, Log log) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = in.readLine();
            if (line == null) { s.close(); return; }
            String[] parts = line.split(" ");
            if (parts.length < 2) { s.close(); return; }
            String path = parts[1];
            OutputStream os = s.getOutputStream();

            if (path.equals("/") || path.isEmpty()) {
                StringBuilder html = new StringBuilder("<html><body><h3>Clock APKs</h3><pre>");
                File[] files = root.listFiles();
                if (files != null) {
                    for (File f : files) {
                        html.append("<a href='/").append(f.getName()).append("'>")
                            .append(f.getName()).append("</a>  (").append(f.length()).append(" B)\n");
                    }
                }
                html.append("</pre></body></html>");
                byte[] b = html.toString().getBytes("UTF-8");
                head(os, "200 OK", "text/html", b.length);
                os.write(b);
            } else {
                String name = path.startsWith("/") ? path.substring(1) : path;
                File f = new File(root, name);
                if (f.exists() && f.isFile() && f.getCanonicalPath().startsWith(root.getCanonicalPath())) {
                    head(os, "200 OK", "application/octet-stream", (int) f.length());
                    FileInputStream fis = new FileInputStream(f);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
                    fis.close();
                    log.log("[" + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "] served " + name + " to " + s.getInetAddress().getHostAddress());
                } else {
                    byte[] b = "not found".getBytes("UTF-8");
                    head(os, "404 Not Found", "text/plain", b.length);
                    os.write(b);
                }
            }
            os.flush();
            s.close();
        } catch (Throwable t) {
            log.log("FileServer handler: " + t);
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private void head(OutputStream os, String status, String type, int len) throws java.io.IOException {
        os.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + len + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
    }
}
