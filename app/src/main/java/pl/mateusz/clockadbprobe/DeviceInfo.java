package pl.mateusz.clockadbprobe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

/** Static device/build/app info for the report header sections. */
public final class DeviceInfo {

    public static void probe(Context ctx, Report rep) {
        rep.line("DEVICE", "Manufacturer: " + Build.MANUFACTURER);
        rep.line("DEVICE", "Brand: " + Build.BRAND);
        rep.line("DEVICE", "Model: " + Build.MODEL);
        rep.line("DEVICE", "Device: " + Build.DEVICE);
        rep.line("DEVICE", "Product: " + Build.PRODUCT);
        rep.line("DEVICE", "Hardware: " + Build.HARDWARE);
        rep.line("DEVICE", "Board: " + Build.BOARD);
        try {
            rep.line("DEVICE", "Serial (Build.getSerial): "
                    + (Build.VERSION.SDK_INT >= 26 ? Build.getSerial() : Build.SERIAL));
        } catch (Throwable t) {
            rep.line("DEVICE", "Serial: NOT ACCESSIBLE FROM APP (" + t.getClass().getSimpleName() + ")");
        }

        rep.line("BUILD", "Fingerprint: " + Build.FINGERPRINT);
        rep.line("BUILD", "ID: " + Build.ID + "  Display: " + Build.DISPLAY);
        rep.line("BUILD", "Type: " + Build.TYPE + "  Tags: " + Build.TAGS);
        rep.line("BUILD", "Incremental: " + Build.VERSION.INCREMENTAL);
        rep.line("BUILD", "Codename: " + Build.VERSION.CODENAME);

        rep.line("ANDROID", "Release: " + Build.VERSION.RELEASE);
        rep.line("ANDROID", "SDK_INT: " + Build.VERSION.SDK_INT);
        rep.line("ANDROID", "Security patch: "
                + (Build.VERSION.SDK_INT >= 23 ? Build.VERSION.SECURITY_PATCH : "NOT ACCESSIBLE (pre-M API)"));

        rep.line("SECURITY", "App is debuggable build: " + ((ctx.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0));
        rep.line("SECURITY", "SystemProperties reflection: " + (Props.reflectionAvailable() ? "available" : "NOT ACCESSIBLE FROM APP"));

        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            rep.line("DEVICE", "Probe app: " + pi.packageName + " v" + pi.versionName + " (" + pi.versionCode + ")");
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }
}
