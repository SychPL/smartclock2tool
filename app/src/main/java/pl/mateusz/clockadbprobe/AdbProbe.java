package pl.mateusz.clockadbprobe;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * ADB TCP state checks. Read-only probes here; the setprop attempts live in
 * TryAdbWifi (user-confirmed) and are executed via ExecUtil.
 */
public final class AdbProbe {

    public static final int ADB_PORT = 5555;

    public static void checkAdbState(android.content.Context ctx, Report rep) {
        String tcpPortProp = Props.get("service.adb.tcp.port");
        String persistPortProp = Props.get("persist.adb.tcp.port");

        String devRaw = readGlobal(ctx, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED);
        boolean devOpts = "1".equals(devRaw);
        Boolean adbEnabled = null;
        String raw = readGlobal(ctx, android.provider.Settings.Global.ADB_ENABLED);
        if ("1".equals(raw)) adbEnabled = true;
        else if ("0".equals(raw)) adbEnabled = false;

        rep.line("ADB", "Developer options (development_settings_enabled=" + devRaw + "): " + (devOpts ? "ENABLED" : "DISABLED"));
        rep.line("ADB", "USB debugging (ADB_ENABLED): "
                + (adbEnabled == null ? "UNKNOWN (" + raw + ")" : (adbEnabled ? "ENABLED" : "DISABLED")));
        boolean tcp = !tcpPortProp.startsWith("PROPERTY") && !"-1".equals(tcpPortProp) && !"0".equals(tcpPortProp)
                || !persistPortProp.startsWith("PROPERTY") && !"-1".equals(persistPortProp) && !"0".equals(persistPortProp);
        rep.line("ADB", "ADB TCP: " + (tcp ? "POSSIBLY ENABLED (see ports below)" : "DISABLED/UNKNOWN (no port property)"));
        rep.line("ADB", "ADB TCP port: service.adb.tcp.port=" + tcpPortProp
                + ", persist.adb.tcp.port=" + persistPortProp);
    }

    private static String readGlobal(android.content.Context ctx, String key) {
        try {
            return String.valueOf(android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), key, -1));
        } catch (Throwable t) {
            return "NOT ACCESSIBLE FROM APP (" + t.getClass().getSimpleName() + ")";
        }
    }

    /** Result of a port probe. */
    public static class PortResult {
        public String host; public int port;
        public boolean open; public String banner; public String error;
    }

    /**
     * Connects to host:port with a short timeout and, if open, sends the ADB
     * CNXN handshake to check whether it behaves like adbd.
     */
    public static PortResult probePort(String host, int port) {
        PortResult r = new PortResult();
        r.host = host; r.port = port;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 1500);
            r.open = true;
            s.setSoTimeout(1500);
            // ADB protocol: client sends CNXN packet first.
            OutputStream os = s.getOutputStream();
            os.write("CNXN".getBytes("US-ASCII"));
            os.write(new byte[]{0, 0, 0, 0}); // command checksum etc. minimal probe
            os.write(new byte[]{0, 0, 0, 0});
            os.write(new byte[]{0, 0, 0, 0});
            os.write(new byte[]{0, 0, 0, 0});
            os.flush();
            InputStream is = s.getInputStream();
            byte[] head = new byte[4];
            int read = is.read(head);
            if (read >= 4 && new String(head, "US-ASCII").equals("CNXN")) {
                r.banner = "ADB PROTOCOL CONFIRMED (CNXN response)";
            } else {
                r.banner = "open, but response not ADB-like" + (read < 0 ? " (EOF)" : "");
            }
        } catch (Throwable t) {
            r.error = String.valueOf(t);
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
        return r;
    }

    public static boolean adbListening(Report rep) {
        for (String host : new String[]{"127.0.0.1", NetProbe.wifiIp}) {
            if (host == null) continue;
            PortResult pr = probePort(host, ADB_PORT);
            rep.log("ADB TCP ATTEMPTS", "port probe " + host + ":" + ADB_PORT
                    + " -> " + (pr.open ? "OPEN (" + pr.banner + ")" : "closed (" + pr.error + ")"));
            if (pr.open) return true;
        }
        return false;
    }
}
