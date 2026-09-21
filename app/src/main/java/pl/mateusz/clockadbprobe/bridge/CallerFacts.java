package pl.mateusz.clockadbprobe.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.os.Process;

import java.security.MessageDigest;
import java.util.List;

/**
 * What the package manager knows about the app on the other side of the bridge.
 *
 * <p>Identity is the signing certificate, not the package name: a name can be taken by anything installed in its
 * place, a certificate cannot (SPEC 0.12 pkt 4.3).
 */
public final class CallerFacts {
    private CallerFacts() {}

    /** SHA-256 of the signing certificate, or the joined set when an app is signed by several. */
    @SuppressWarnings("deprecation")
    public static String fingerprint(PackageManager pm, String pkg) {
        try {
            Signature[] signatures;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.content.pm.SigningInfo info =
                        pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
                signatures = info.hasMultipleSigners() ? info.getApkContentsSigners() : info.getSigningCertificateHistory();
            } else {
                signatures = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
            }
            if (signatures == null || signatures.length == 0) return "";
            StringBuilder all = new StringBuilder();
            for (Signature signature : signatures) {
                if (all.length() > 0) all.append('+');
                all.append(sha256(signature.toByteArray()));
            }
            return all.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** The signing certificate of a file on disk, for comparing an update against what is installed. */
    @SuppressWarnings("deprecation")
    public static String fingerprintOfArchive(PackageManager pm, String path) {
        try {
            android.content.pm.PackageInfo info = pm.getPackageArchiveInfo(path,
                    android.os.Build.VERSION.SDK_INT >= 28
                            ? PackageManager.GET_SIGNING_CERTIFICATES
                            : PackageManager.GET_SIGNATURES);
            if (info == null) return "";
            Signature[] signatures;
            if (android.os.Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
                signatures = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
            } else {
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) return "";
            StringBuilder all = new StringBuilder();
            for (Signature signature : signatures) {
                if (all.length() > 0) all.append('+');
                all.append(sha256(signature.toByteArray()));
            }
            return all.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean declaresHome(PackageManager pm, String pkg) {
        return homeActivities(pm, pkg) > 0;
    }

    /** How many enabled home activities this package offers; more than one means we refuse to guess. */
    public static int homeActivities(PackageManager pm, String pkg) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> all = pm.queryIntentActivities(home, 0);
        int count = 0;
        for (ResolveInfo info : all) {
            if (info.activityInfo != null && pkg.equals(info.activityInfo.packageName)) count++;
        }
        return count;
    }

    public static String currentHome(PackageManager pm) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return info == null || info.activityInfo == null ? "" : info.activityInfo.packageName;
    }

    /** Whether the app may already write system settings; the operation only ever needs to be run once. */
    public static boolean canWriteSettings(Context context, String pkg) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            android.app.AppOpsManager ops =
                    (android.app.AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow("android:write_settings", info.uid, pkg);
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean declaresPermission(PackageManager pm, String pkg, String permission) {
        try {
            String[] declared = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (declared == null) return false;
            for (String one : declared) if (permission.equals(one)) return true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The Android user the caller belongs to; the contract covers user 0 only. */
    public static int userIdOf(int uid) {
        return uid / 100000;
    }

    public static int ownUserId() {
        return userIdOf(Process.myUid());
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
