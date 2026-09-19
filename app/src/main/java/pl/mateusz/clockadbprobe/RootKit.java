package pl.mateusz.clockadbprobe;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * One-press root for the clock.
 *
 * The whole chain lives in a bundled shell script (assets/rootkit/bootstrap.sh):
 * it arms selinux_enforcing and modprobe_path through the PowerVR OOB write,
 * fires the unknown-binfmt trigger, and the kernel then runs our helper as root,
 * which starts the root channels. This class only unpacks the payload tree next
 * to the app's files and runs that script as the app's own uid -- the same thing
 * tools/reroot.py does from a host, minus the host.
 */
public final class RootKit {

    private static final String ASSET_DIR = "rootkit";

    /** The port the ADB-over-Wi-Fi recipe asks adbd to listen on. */
    public static final String ADB_PORT = "5555";

    private RootKit() {
    }

    /** Where the payload tree is unpacked (filesDir/rootkit). */
    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), ASSET_DIR);
        if (!d.exists() && !d.mkdirs()) {
            throw new IllegalStateException("cannot create " + d);
        }
        return d;
    }

    /**
     * Copies every asset under rootkit/ into filesDir/rootkit, keeping the tree.
     * Always re-copies: assets carry no mode or mtime, the tree is small, and a
     * half-updated payload would be worse than a slow button.
     */
    public static int unpack(Context ctx) throws IOException {
        AssetManager am = ctx.getAssets();
        File root = dir(ctx);
        List<String> stack = new ArrayList<String>();
        stack.add(ASSET_DIR);
        int files = 0;
        while (!stack.isEmpty()) {
            String path = stack.remove(stack.size() - 1);
            String[] children = am.list(path);
            if (children == null || children.length == 0) {
                copyAsset(am, path, new File(ctx.getFilesDir(), path));
                files++;
                continue;
            }
            for (String child : children) {
                stack.add(path + "/" + child);
            }
        }
        return files;
    }

    private static void copyAsset(AssetManager am, String assetPath, File dst)
            throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        InputStream in = am.open(assetPath);
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    /** Sink for the bootstrap's output; called from a background thread. */
    public interface Log {
        void line(String text);
    }

    /**
     * Unpacks and runs the bootstrap, streaming its output. Returns the exit code.
     * Must be called off the main thread.
     */
    public static int run(Context ctx, Log log) throws IOException, InterruptedException {
        unpack(ctx);
        File script = new File(dir(ctx), "bootstrap.sh");
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", script.getAbsolutePath());
        pb.directory(ctx.getFilesDir());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                log.line(line);
            }
        } finally {
            r.close();
        }
        return p.waitFor();
    }

    /** "ssh -p 2223 root@<ip>" for the address the clock is reachable at. */
    public static String sshHint() {
        String ip = NetProbe.wifiIp != null ? NetProbe.wifiIp : "<clock-ip>";
        return "ssh -i <key> -p 2223 root@" + ip;
    }

    /**
     * Runs one command through the root channel and returns its combined output.
     * The channel binary must be executable: assets carry no mode, so the app
     * sets it on its own copy (filesDir belongs to the app, no root needed).
     */
    public static String channel(Context ctx, String cmd)
            throws IOException, InterruptedException {
        File bin = new File(dir(ctx), "clockroot");
        if (!bin.exists()) {
            throw new IOException("no root channel (" + bin + ") - run ROOT + ADB first");
        }
        if (!bin.canExecute()) {
            bin.setExecutable(true, true);
        }
        ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "-c", cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            r.close();
        }
        p.waitFor();
        return sb.toString();
    }

    /**
     * Turns ADB over Wi-Fi on or off. It writes into /dev/__properties__, which
     * the property service refuses to do for us and only root may do directly,
     * so it goes through the channel instead of running with the app's uid.
     */
    public static int runAdbWifi(Context ctx, Log log, boolean on)
            throws IOException, InterruptedException {
        unpack(ctx);
        File script = new File(dir(ctx), "adbwifi.sh");
        if (!script.exists()) {
            throw new IOException("missing " + script);
        }
        String out = channel(ctx, "sh " + script.getAbsolutePath()
                + (on ? " on" : " off"));
        int lines = 0;
        for (String line : out.split("\n")) {
            log.line(line);
            lines++;
        }
        if (lines == 0) {
            return 1;
        }
        return out.contains(on ? "adbwifi: ON" : "adbwifi: OFF") ? 0 : 1;
    }

    /** True while the property still asks adbd for the TCP port. */
    public static boolean adbWifiOn(Context ctx) {
        try {
            return ADB_PORT.equals(channel(ctx, "getprop service.adb.tcp.port").trim());
        } catch (Throwable t) {
            return false;
        }
    }

    /** What is up right now: root channel, ADB listener, SSH, connect commands. */
    public static String[] stateLines(Context ctx) {
        List<String> out = new ArrayList<String>();
        try {
            String id = channel(ctx, "id").trim();
            out.add("root: " + (id.isEmpty() ? "<none>" : id));
        } catch (Throwable t) {
            out.add("root: down (" + t.getMessage() + ")");
        }
        try {
            String port = channel(ctx, "getprop service.adb.tcp.port").trim();
            String secure = channel(ctx, "getprop ro.adb.secure").trim();
            String listen = channel(ctx, "netstat -ltn | grep 5555").trim();
            out.add("adb: " + (listen.isEmpty() ? "OFF" : "ON") + " port='" + port
                    + "' ro.adb.secure='" + secure + "'");
            if (!listen.isEmpty() && NetProbe.wifiIp != null) {
                out.add("adb connect " + NetProbe.wifiIp + ":" + ADB_PORT);
            }
        } catch (Throwable t) {
            out.add("adb: not checked (" + t.getMessage() + ")");
        }
        try {
            String ssh = channel(ctx, "netstat -ltn | grep 2223").trim();
            out.add("ssh: " + (ssh.isEmpty()
                    ? "2223 not listening (optional - root and adb work without it)"
                    : "2223 listening"));
        } catch (Throwable t) {
            out.add("ssh: not checked (" + t.getMessage() + ")");
        }
        out.add(sshHint());
        return out.toArray(new String[0]);
    }
}
