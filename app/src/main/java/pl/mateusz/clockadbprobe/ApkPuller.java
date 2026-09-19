package pl.mateusz.clockadbprobe;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Copies world-readable system/vendor APKs (and sepolicy text files, if
 * readable) into the app's external files dir so FileServer can expose them
 * on the LAN for offline decompilation. Read-only from the device's POV.
 */
public final class ApkPuller {

    private static final String[] TARGET_PREFIXES = {
            "com.tonly", "com.lenovo", "com.mediatek", "com.android.settings",
            "com.android.systemui", "com.google.android.googlequicksearchbox",
            "com.google.android.gms", "com.google.android.gsf", "com.android.shell",
            "com.android.providers", "com.google.android.cast"
    };

    private static final String[] EXTRA_FILES = {
            // property/SELinux contexts - plain text, often world-readable on /system & /vendor
            "/system/etc/selinux/plat_property_contexts",
            "/system/etc/sepolicy/precompiled_sepolicy", // binary, large - skip on failure
            "/vendor/etc/selinux/vendor_property_contexts",
            "/vendor/etc/selinux/precompiled_sepolicy",
            "/odm/etc/selinux/odm_property_contexts",
            "/system/etc/init/hw/init.rc", // normally not readable from ramdisk; try anyway
            "/proc/net/tcp", "/proc/net/tcp6", "/proc/net/unix",
            "/system/build.prop", "/vendor/build.prop", "/odm/etc/build.prop",
    };

    public static File pullDir(Context ctx) {
        File d = ctx.getExternalFilesDir("apks");
        if (d == null) d = new File(ctx.getFilesDir(), "apks");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static void pull(Context ctx, Report rep) {
        File out = pullDir(ctx);
        int ok = 0, fail = 0;

        try {
            List<PackageInfo> pkgs = ctx.getPackageManager().getInstalledPackages(0);
            for (PackageInfo pi : pkgs) {
                String pn = pi.packageName != null ? pi.packageName : "";
                if (!isTarget(pn)) continue;
                File src = new File(pi.applicationInfo.sourceDir);
                File dst = new File(out, pn + ".apk");
                if (copy(src, dst)) { ok++; rep.line("COMMAND RESULTS", "PULLED " + pn + " (" + dst.length() + " B) <- " + src); }
                else { fail++; rep.line("COMMAND RESULTS", "PULL FAILED " + pn + " src=" + src + " canRead=" + src.canRead()); }
            }
        } catch (Throwable t) {
            rep.exception("ApkPuller.packages", t);
        }

        for (String p : EXTRA_FILES) {
            if (p.contains("/proc/")) continue; // handled by PortMap
            if (p.endsWith("precompiled_sepolicy")) continue; // big binary; text contexts are enough
            File src = new File(p);
            String name = p.replace('/', '_');
            File dst = new File(out, name);
            if (copy(src, dst)) rep.line("COMMAND RESULTS", "PULLED " + p + " (" + dst.length() + " B)");
            else rep.line("COMMAND RESULTS", "PULL FAILED " + p + " canRead=" + src.canRead());
        }
        rep.line("CONCLUSIONS", "APK pull: " + ok + " ok, " + fail + " failed -> " + out.getAbsolutePath());
    }

    private static boolean isTarget(String pn) {
        for (String p : TARGET_PREFIXES) if (pn.startsWith(p) || pn.equals(p)) return true;
        return pn.contains("tonly") || pn.contains("helios") || pn.contains("engineer");
    }

    static boolean copy(File src, File dst) {
        InputStream is = null; OutputStream os = null;
        try {
            if (!src.exists() || !src.canRead()) return false;
            is = new FileInputStream(src);
            os = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            return dst.length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
            try { if (os != null) os.close(); } catch (Throwable ignored) {}
        }
    }
}
