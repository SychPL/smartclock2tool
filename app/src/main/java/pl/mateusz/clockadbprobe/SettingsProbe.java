package pl.mateusz.clockadbprobe;

import android.content.Context;
import android.provider.Settings;

/** Reads diagnostic-relevant settings. Read-only; never writes. */
public final class SettingsProbe {

    public static void probe(Context ctx, Report rep) {
        String[][] globals = {
                {"ADB_ENABLED", Settings.Global.ADB_ENABLED},
                {"DEVELOPMENT_SETTINGS_ENABLED", Settings.Global.DEVELOPMENT_SETTINGS_ENABLED},
                {"ADB_WIFI_ENABLED", "adb_wifi_enabled"}, // hidden/SystemApi setting; read literally
                {"USB_MASS_STORAGE_ENABLED", "usb_mass_storage_enabled"},
                {"ADDITIONAL_SYSTEM_UPDATE", "additional_system_update"},
        };
        for (String[] g : globals) {
            String v;
            try {
                v = String.valueOf(Settings.Global.getInt(ctx.getContentResolver(), g[1], -1));
            } catch (Throwable t) {
                v = "NOT ACCESSIBLE FROM APP (" + t + ")";
            }
            rep.log("DEVELOPER SETTINGS", "Global." + g[0] + " = " + v);
        }

        // Sweep Global/Secure/System keys containing interesting substrings.
        sweep(ctx, rep, "Global", Settings.Global.CONTENT_URI);
        sweep(ctx, rep, "Secure", Settings.Secure.CONTENT_URI);
        sweep(ctx, rep, "System", Settings.System.CONTENT_URI);

        try {
            int canWrite = ctx.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS);
            rep.line("DEVELOPER SETTINGS", "WRITE_SECURE_SETTINGS granted: " + (canWrite == android.content.pm.PackageManager.PERMISSION_GRANTED));
        } catch (Throwable t) {
            rep.exception("SettingsProbe.permissions", t);
        }
    }

    private static void sweep(Context ctx, Report rep, String table, android.net.Uri uri) {
        try {
            android.database.Cursor c = ctx.getContentResolver().query(
                    uri, new String[]{"name"}, null, null, null);
            if (c == null) {
                rep.line("DEVELOPER SETTINGS", table + " key list: NOT ACCESSIBLE FROM APP (null cursor)");
                return;
            }
            String[] needles = {"adb", "debug", "usb", "developer"};
            int n = 0;
            while (c.moveToNext() && n < 60) {
                String name = c.getString(0);
                if (name == null) continue;
                String lower = name.toLowerCase();
                boolean match = false;
                for (String nd : needles) if (lower.contains(nd)) { match = true; break; }
                if (!match) continue;
                String val;
                try { val = android.provider.Settings.NameValueTable.class.getName(); val = readValue(ctx, table, name); }
                catch (Throwable t) { val = "READ ERROR: " + t; }
                rep.line("DEVELOPER SETTINGS", "  " + table + "." + name + " = " + val);
                n++;
            }
            c.close();
            if (n == 0) rep.line("DEVELOPER SETTINGS", table + ": no matching keys (or hidden)");
        } catch (Throwable t) {
            rep.line("DEVELOPER SETTINGS", table + " key list: NOT ACCESSIBLE FROM APP (" + t + ")");
        }
    }

    private static String readValue(Context ctx, String table, String name) {
        android.net.Uri uri;
        if ("Global".equals(table)) uri = Settings.Global.getUriFor(name);
        else if ("Secure".equals(table)) uri = Settings.Secure.getUriFor(name);
        else uri = Settings.System.getUriFor(name);
        android.database.Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) return c.getString(c.getColumnIndex("value"));
            } finally { c.close(); }
        }
        return "(unreadable)";
    }
}
