package pl.mateusz.clockadbprobe;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates installed packages and their components, focusing on
 * Lenovo / MediaTek / debug / engineering / adb-related names.
 * Read-only: never invokes any component.
 */
public final class ComponentScanner {

    // Vendor terms mark a package as interesting outright.
    private static final String[] VENDOR_TERMS = {
            "lenovo", "mediatek", "mtk", "helios", "ota"
    };
    // Generic terms only flag a component when the package itself is vendor-ish,
    // or the component name matches strongly.
    private static final String[] STRONG_TERMS = {
            "adb", "adbd", "engineering", "engineer", "factory", "diagnostic",
            "diag", "factorytest", "debugtool"
    };
    private static final String[] WEAK_TERMS = {
            "debug", "usb", "tcp", "network", "test", "service"
    };

    public static void scan(Context ctx, Report rep) {
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> pkgs;
        try {
            pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS
                    | PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                    | PackageManager.GET_DISABLED_COMPONENTS);
        } catch (Throwable t) {
            rep.line("INSTALLED PACKAGES OF INTEREST", "getInstalledPackages: NOT ACCESSIBLE FROM APP (" + t + ")");
            return;
        }
        rep.line("INSTALLED PACKAGES OF INTEREST", "Total installed packages: " + pkgs.size());

        List<PackageInfo> interesting = new ArrayList<>();
        for (PackageInfo pi : pkgs) {
            if (isInterestingPackage(pi.packageName)) interesting.add(pi);
        }

        StringBuilder vendorFindings = new StringBuilder();

        for (PackageInfo pi : interesting) {
            boolean vendor = matchesAny(pi.packageName, VENDOR_TERMS);
            rep.line("INSTALLED PACKAGES OF INTEREST", "");
            rep.line("INSTALLED PACKAGES OF INTEREST",
                    "PKG " + pi.packageName
                    + " v" + safe(pi.versionName) + " (" + (pi.versionCode >= 0 ? pi.versionCode : "?") + ")"
                    + (pi.sharedUserId != null ? " sharedUserId=" + pi.sharedUserId : "")
                    + (vendor ? " [VENDOR]" : ""));
            describePermissions(pi, rep);
            dumpComponents(pi, rep, vendorFindings);
        }
        // SAFE TO QUERY: read-only probe of the one unprotected system-uid
        // provider (com.tonly.helios.ota/AccessoryStatusProvider).
        try {
            android.net.Uri u = android.net.Uri.parse("content://com.tonly.helios.ota.AccessoryStatusProvider");
            android.database.Cursor cur = ctx.getContentResolver().query(u, null, null, null, null);
            if (cur == null) {
                rep.line("VENDOR / LENOVO / MTK FINDINGS", "AccessoryStatusProvider query: null cursor");
            } else {
                StringBuilder cols = new StringBuilder();
                for (String cn : cur.getColumnNames()) cols.append(cn).append(' ');
                int rows = 0;
                while (cur.moveToNext() && rows < 20) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 0; i < cur.getColumnCount(); i++)
                        row.append(cur.getString(i)).append(" | ");
                    rep.line("VENDOR / LENOVO / MTK FINDINGS", "OTA provider row: " + row);
                    rows++;
                }
                rep.line("VENDOR / LENOVO / MTK FINDINGS",
                        "AccessoryStatusProvider columns: " + cols + " rows=" + rows);
                cur.close();
            }
        } catch (Throwable t) {
            rep.line("VENDOR / LENOVO / MTK FINDINGS",
                    "AccessoryStatusProvider query: " + t);
        }
    }

    private static String safe(Object o) { return o == null ? "?" : String.valueOf(o); }

    private static boolean matchesAny(String s, String[] terms) {
        if (s == null) return false;
        String l = s.toLowerCase();
        for (String t : terms) if (l.contains(t)) return true;
        return false;
    }

    private static boolean isInterestingPackage(String name) {
        return matchesAny(name, VENDOR_TERMS) || matchesAny(name, STRONG_TERMS);
    }

    private static boolean isInterestingComponent(String name) {
        return matchesAny(name, VENDOR_TERMS) || matchesAny(name, STRONG_TERMS) || matchesAny(name, WEAK_TERMS);
    }

    private static void describePermissions(PackageInfo pi, Report rep) {
        try {
            if (pi.requestedPermissions == null || pi.requestedPermissions.length == 0) {
                rep.line("INSTALLED PACKAGES OF INTEREST", "  permissions: none requested");
                return;
            }
            for (int i = 0; i < pi.requestedPermissions.length; i++) {
                String p = pi.requestedPermissions[i];
                boolean granted = (pi.requestedPermissionsFlags != null)
                        && i < pi.requestedPermissionsFlags.length
                        && (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                if (p != null && (p.contains("adb") || p.contains("debug") || p.contains("usb")
                        || p.contains("WRITE_SECURE") || p.contains("factory") || granted)) {
                    rep.line("INSTALLED PACKAGES OF INTEREST", "  perm " + p + (granted ? " [GRANTED]" : ""));
                }
            }
        } catch (Throwable t) {
            rep.line("INSTALLED PACKAGES OF INTEREST", "  permissions: NOT ACCESSIBLE FROM APP (" + t + ")");
        }
    }

    private static void dumpComponents(PackageInfo pi, Report rep, StringBuilder vendorFindings) {
        boolean vendor = matchesAny(pi.packageName, VENDOR_TERMS);

        if (pi.activities != null) {
            for (ActivityInfo ai : pi.activities) {
                if (!vendor && !isInterestingComponent(ai.name)) continue;
                emit(rep, "EXPORTED ACTIVITIES", pi, ai.name, ai.exported, ai.enabled, ai.permission, ai.processName, ai, vendorFindings);
            }
        }
        if (pi.services != null) {
            for (ServiceInfo si : pi.services) {
                if (!vendor && !isInterestingComponent(si.name)) continue;
                emit(rep, "EXPORTED SERVICES", pi, si.name, si.exported, si.enabled, si.permission, si.processName, si, vendorFindings);
            }
        }
        if (pi.receivers != null) {
            for (ActivityInfo ai : pi.receivers) {
                if (!vendor && !isInterestingComponent(ai.name)) continue;
                emit(rep, "EXPORTED RECEIVERS", pi, ai.name, ai.exported, ai.enabled, ai.permission, ai.processName, ai, vendorFindings);
            }
        }
        if (pi.providers != null) {
            for (ProviderInfo pri : pi.providers) {
                if (!vendor && !isInterestingComponent(pri.name)) continue;
                emit(rep, "EXPORTED PROVIDERS", pi, pri.name, pri.exported, pri.enabled, pri.readPermission, pri.processName, pri, vendorFindings);
            }
        }
        rep.line("INSTALLED PACKAGES OF INTEREST",
                "  intent filters: NOT ACCESSIBLE FROM APP (PackageManager does not expose third-party intent filters)");
    }

    private static void emit(Report rep, String section, PackageInfo pi, String name,
                             boolean exported, boolean enabled, String permission,
                             String processName, Object info, StringBuilder vendorFindings) {
        String s = pi.packageName + "/" + shortName(name)
                + " exported=" + exported
                + " enabled=" + enabled
                + " permission=" + (permission != null ? permission : "none")
                + " process=" + (processName != null ? processName : "default");
        if (exported) rep.line(section, s);
        if (exported && matchesAny(pi.packageName, VENDOR_TERMS)) {
            vendorFindings.append("EXPORTED ").append(section.substring(9)).append(": ").append(s)
                    .append("  [class: SAFE TO QUERY / POTENTIALLY STATE-CHANGING / UNKNOWN - not invoked]\n");
        }
    }

    private static String shortName(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1) : name;
    }
}
