package pl.mateusz.clockadbprobe;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.Arrays;

/** Enumerates Binder/system services reachable from app context. No SELinux bypass attempts. */
public final class BinderProbe {

    public static void probe(Context ctx, Report rep) {
        // 1) ServiceManager.listServices() via reflection (hidden API; allowed at targetSdk 27).
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getMethod("listServices");
            String[] names = (String[]) m.invoke(null);
            if (names == null) {
                rep.line("SYSTEM / BINDER SERVICES", "ServiceManager.listServices(): null");
            } else {
                Arrays.sort(names);
                rep.line("SYSTEM / BINDER SERVICES", "ServiceManager.listServices() count = " + names.length);
                for (String n : names) rep.line("SYSTEM / BINDER SERVICES", "  " + n);
            }
        } catch (Throwable t) {
            rep.line("SYSTEM / BINDER SERVICES",
                    "ServiceManager.listServices(): NOT ACCESSIBLE FROM APP (" + t + ")");
        }

        // 2) Try fetching ServiceManager Binder objects for interesting names.
        // A successful getService() means the service exists; calling methods on
        // it may throw SecurityException which we record verbatim.
        String[] interesting = {
                "adb", "usb", "mount", "activity", "package", "audio",
                "media_router", "device_policy", "persistent_data_block",
                "oem_lock", "serial", "engineering", "factory", "mediatek",
                "lenovo", "mtk", "debug", "power", "connectivity"
        };
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method get = sm.getMethod("getService", String.class);
            for (String name : interesting) {
                Object binder;
                try {
                    binder = get.invoke(null, name);
                } catch (Throwable t) {
                    binder = null;
                }
                rep.log("SYSTEM / BINDER SERVICES",
                        "getService(\"" + name + "\") = " + (binder != null ? "PRESENT (binder handle)" : "not found / null"));
            }
        } catch (Throwable t) {
            rep.line("SYSTEM / BINDER SERVICES", "ServiceManager.getService reflection failed: " + t);
        }

        // 3) Context.getSystemService for standard names — shows which are app-reachable.
        String[] ctxServices = {
                Context.AUDIO_SERVICE, Context.CONNECTIVITY_SERVICE, Context.DEVICE_POLICY_SERVICE,
                Context.USB_SERVICE, Context.MEDIA_ROUTER_SERVICE, Context.POWER_SERVICE,
                Context.WIFI_SERVICE, Context.NETWORK_STATS_SERVICE, Context.NFC_SERVICE
        };
        for (String s : ctxServices) {
            Object svc;
            try { svc = ctx.getSystemService(s); } catch (Throwable t) { svc = null; }
            rep.line("SYSTEM / BINDER SERVICES", "getSystemService(" + s + ") = " + (svc != null ? "reachable" : "NOT ACCESSIBLE FROM APP / null"));
        }
    }
}
