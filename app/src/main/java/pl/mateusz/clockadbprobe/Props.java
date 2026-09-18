package pl.mateusz.clockadbprobe;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads system properties via reflection on android.os.SystemProperties. */
public final class Props {

    private static Method sGet;

    static {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            sGet = c.getMethod("get", String.class);
        } catch (Throwable ignored) {
            sGet = null;
        }
    }

    public static boolean reflectionAvailable() { return sGet != null; }

    /** @return value, or one of: PROPERTY NOT PRESENT / PROPERTY EMPTY / PROPERTY ACCESS DENIED */
    public static String get(String key) {
        if (sGet == null) return "PROPERTY ACCESS DENIED (no reflection)";
        try {
            Object v = sGet.invoke(null, key);
            if (v == null) return "PROPERTY NOT PRESENT";
            String s = String.valueOf(v);
            if (s.isEmpty()) return "PROPERTY EMPTY";
            return s;
        } catch (Throwable t) {
            return "PROPERTY ACCESS DENIED (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
    }

    /** Reads a fixed key set plus a wildcard-ish sweep of interesting prefixes. */
    public static Map<String, String> collect(Report rep) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] keys = {
                "ro.secure", "ro.debuggable", "ro.adb.secure",
                "ro.build.type", "ro.build.tags", "ro.build.version.release",
                "ro.build.version.sdk", "ro.build.fingerprint",
                "service.adb.tcp.port", "persist.adb.tcp.port",
                "sys.usb.config", "persist.sys.usb.config", "sys.usb.state",
                "ro.boot.serialno", "ro.serialno", "ro.hardware",
                "ro.mediatek.version.release", "ro.mtk_platform", "ro.board.platform",
                "ro.vendor.mediatek.platform", "persist.sys.usb.config",
                "vendor.usb.controller", "ro.bootmode", "ro.boot.bootreason",
                "persist.service.adb.enable", "service.adb.tcp.portname",
                "ro.lenovo.platform", "ro.lenovo.device", "persist.lenovo.debug",
        };
        for (String k : keys) {
            String v = get(k);
            out.put(k, v);
            rep.log("SYSTEM PROPERTIES", k + " = " + v);
        }
        // Prefix sweep: ro.boot. ro.vendor. ro.mtk. via full getprop dump is
        // handled in ExecProbe (getprop output). Reflection has no list() API
        // exposed (list is hidden and takes no args on old versions); we try it.
        try {
            Method list = Class.forName("android.os.SystemProperties").getMethod("list");
            list.setAccessible(true);
            Object r = list.invoke(null);
            rep.line("SYSTEM PROPERTIES", "SystemProperties.list() = " + r);
        } catch (Throwable t) {
            rep.line("SYSTEM PROPERTIES", "SystemProperties.list(): NOT ACCESSIBLE FROM APP (" + t.getClass().getSimpleName() + ")");
        }
        return out;
    }
}
