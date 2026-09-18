package pl.mateusz.clockadbprobe;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Local listening-socket map from /proc/net/tcp, /proc/net/tcp6 and the unix
 * socket table. Read-only; replaces port scanning entirely.
 */
public final class PortMap {

    public static void probe(Report rep) {
        readTcp(rep, "/proc/net/tcp");
        readTcp(rep, "/proc/net/tcp6");
        readUnix(rep);
    }

    private static void readTcp(Report rep, String file) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(file));
            rep.log("SYSTEM / BINDER SERVICES", "reading " + file);
            String line;
            int listens = 0;
            while ((line = r.readLine()) != null) {
                // fields: sl local_address rem_address st ...
                String[] f = line.trim().split("\\s+");
                if (f.length < 4 || f[1].contains("local_address")) continue;
                if (!f[3].equalsIgnoreCase("0A")) continue; // LISTEN
                String[] addrPort = f[1].split(":");
                int port = Integer.parseInt(addrPort[1], 16);
                String uid = f.length > 7 ? f[7] : "?";
                rep.line("SYSTEM / BINDER SERVICES",
                        "LISTEN " + (file.endsWith("6") ? "tcp6" : "tcp") + " port " + port
                                + " local=" + f[1] + " uid=" + uid);
                listens++;
            }
            rep.line("SYSTEM / BINDER SERVICES", file + ": " + listens + " LISTEN sockets"
                    + (listens == 0 ? " (denied or empty)" : ""));
        } catch (Throwable t) {
            rep.line("SYSTEM / BINDER SERVICES", file + ": NOT ACCESSIBLE FROM APP (" + t + ")");
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
    }

    private static void readUnix(Report rep) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/net/unix"));
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < 200) {
                String l = line.toLowerCase();
                if (l.contains("adbd") || l.contains("adb") || l.contains("debug") || l.contains("mtk")
                        || l.contains("diag") || l.contains("eng") || l.contains("atci")
                        || l.contains("tonly") || l.contains("ota") || l.contains("service_manager")
                        || l.contains("listener")) {
                    rep.line("SYSTEM / BINDER SERVICES", "unix: " + line.trim());
                    n++;
                }
            }
            rep.line("SYSTEM / BINDER SERVICES", "/proc/net/unix interesting sockets: " + n);
        } catch (Throwable t) {
            rep.line("SYSTEM / BINDER SERVICES", "/proc/net/unix: NOT ACCESSIBLE FROM APP (" + t + ")");
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
    }
}
