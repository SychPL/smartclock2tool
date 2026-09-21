package pl.mateusz.clockadbprobe.bridge;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pl.mateusz.clockadbprobe.RootKit;

/**
 * Runs the operations the bridge offers (SPEC 0.12 pkt 5).
 *
 * <p>Everything privileged goes through the root channel, and every step is reported to the registry before it is
 * taken, so a process that dies in the middle leaves a record of where it was rather than a silence.
 */
public final class BridgeExecutor {
    /** The build the exploit calibration was written for; anything else refuses before it runs. */
    public static final String SUPPORTED_FIRMWARE = "LenovoCD-24502F_ROW_1.2.2.627_220105";

    public static final class Result {
        public final String status;
        public final String detail;

        public Result(String status, String detail) {
            this.status = status;
            this.detail = Detail.clean(detail);
        }
    }

    private final Context context;
    private final OpRegistry registry;
    private final MicStateHolder mic;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    /** Kept as an interface so the microphone record can be added without touching this class again. */
    public interface MicStateHolder {
        String state();
    }

    public BridgeExecutor(Context context, OpRegistry registry, MicStateHolder mic) {
        this.context = context.getApplicationContext();
        this.registry = registry;
        this.mic = mic;
    }

    public String snapshot(long budgetMs, OpRegistry.Entry about, String callerPackage) {
        Snapshot snapshot = new Snapshot(new DeviceProbe(callerPackage), new UptimeClock(), pool);
        return snapshot.json(budgetMs, about, chain());
    }

    public String chain() {
        ExecutorLock lock = Executions.lock();
        return lock == null ? ExecutorLock.UNKNOWN : lock.chain();
    }

    public static boolean firmwareSupported() {
        return SUPPORTED_FIRMWARE.equals(Build.DISPLAY) || SUPPORTED_FIRMWARE.equals(Build.ID);
    }

    /**
     * Turns the chain on when it is not up, then ADB. The lock and the trace are taken together, and the trace is
     * cleared only once we know what happened.
     */
    public Result rootAndAdb(OpRegistry.Key key, String execId, RootKit.Log log) {
        Executions.Grant grant = Executions.begin(execId, OpRegistry.RUNNING, ExecutorLock.PRIVILEGED);
        if (!grant.granted) return new Result("busy", grant.reason);
        registry.stage(key, OpRegistry.RUNNING);
        try {
            if (!rootAlive()) {
                Executions.lock().promote(execId);
                int code = RootKit.run(context, log);
                if (code != 0 && !rootAlive()) return finish(key, execId, "failed", "chain exited " + code);
            }
            RootKit.runAdbWifi(context, log, true);
            boolean listening = AdbState.listening();
            String status = AdbState.onResult("", listening);
            return finish(key, execId, status, listening ? "adb listening" : "adb not listening");
        } catch (Exception e) {
            return finish(key, execId, "failed", e.getClass().getSimpleName());
        }
    }

    public Result adbOn(OpRegistry.Key key, String execId, RootKit.Log log) {
        if (!rootAlive()) return new Result("unsupported", "no root channel");
        Executions.Grant grant = Executions.begin(execId, OpRegistry.RUNNING, ExecutorLock.PRIVILEGED);
        if (!grant.granted) return new Result("busy", grant.reason);
        registry.stage(key, OpRegistry.RUNNING);
        try {
            RootKit.runAdbWifi(context, log, true);
            boolean listening = AdbState.listening();
            return finish(key, execId, AdbState.onResult("", listening), listening ? "adb listening" : "adb not listening");
        } catch (Exception e) {
            return finish(key, execId, "failed", e.getClass().getSimpleName());
        }
    }

    /**
     * Turns ADB off and proves it. Zeroing the port is not enough on every build, so a port that still answers gets
     * the daemon stopped outright, and the result is whatever the socket says afterwards.
     */
    public Result adbOff(OpRegistry.Key key, String execId, RootKit.Log log) {
        if (!rootAlive()) return new Result("unsupported", "no root channel");
        Executions.Grant grant = Executions.begin(execId, OpRegistry.RUNNING, ExecutorLock.PRIVILEGED);
        if (!grant.granted) return new Result("busy", grant.reason);
        registry.stage(key, OpRegistry.RUNNING);
        try {
            RootKit.runAdbWifi(context, log, false);
            if (AdbState.listening()) {
                log.line("port still answers, stopping adbd");
                RootKit.channel(context, "stop adbd");
                SystemClock.sleep(1500);
            }
            boolean listening = AdbState.listening();
            return finish(key, execId, AdbState.offResult("", listening),
                    listening ? "adb still listening" : "adb silent");
        } catch (Exception e) {
            return finish(key, execId, "failed", e.getClass().getSimpleName());
        }
    }

    /**
     * Gives the caller a permission it declares and we allow. Everything about this is narrow on purpose: the
     * package is the caller, never an argument, and the permission comes from a list of one.
     */
    public Result grantPermission(OpRegistry.Key key, String execId, String callerPackage, String permission) {
        if (!Ops.GRANTABLE.contains(permission)) return new Result("unsupported", "permission not on the list");
        return shortOperation(key, execId, () -> {
            if (granted(callerPackage, permission)) return new Result("ok", "already granted");
            shell("pm grant " + callerPackage + " " + permission);
            return new Result(granted(callerPackage, permission) ? "ok" : "failed", "");
        });
    }

    /** Lets the caller write system settings, after which it changes brightness on its own, with no root at all. */
    public Result writeSettings(OpRegistry.Key key, String execId, String callerPackage) {
        return shortOperation(key, execId, () -> {
            if (writeSettingsAllowed(callerPackage)) return new Result("ok", "already allowed");
            shell("appops set " + callerPackage + " WRITE_SETTINGS allow");
            return new Result(writeSettingsAllowed(callerPackage) ? "ok" : "failed", "");
        });
    }

    /** Makes the caller the home app, so the clock comes back with it after a power cut. */
    public Result setHome(OpRegistry.Key key, String execId, String callerPackage) {
        return shortOperation(key, execId, () -> {
            String component = homeComponentOf(callerPackage);
            if (component == null) return new Result("unsupported", "no single home activity");
            shell("cmd package set-home-activity " + component);
            boolean isHome = callerPackage.equals(CallerFacts.currentHome(context.getPackageManager()));
            return new Result(isHome ? "ok" : "failed", isHome ? "" : "the system kept the previous home app");
        });
    }

    /**
     * Takes the microphone away from the factory shells and restarts them, so they let go of the open stream.
     * The record of what they had is written before anything changes (SPEC 0.12 pkt 5.7).
     */
    public Result micRelease(OpRegistry.Key key, String execId, MicState mic) {
        return shortOperation(key, execId, () -> {
            mic.rememberBefore(Ops.MIC_TARGETS, pkg -> installed(pkg) ? granted(pkg, RECORD_AUDIO) : null);
            boolean all = true;
            for (String pkg : Ops.MIC_TARGETS) {
                if (!installed(pkg)) continue;
                shell("appops set " + pkg + " RECORD_AUDIO deny");
                shell("am force-stop " + pkg);
                if (granted(pkg, RECORD_AUDIO)) all = false;
            }
            return new Result(all ? "ok" : "failed", all ? "" : "a shell kept the microphone");
        });
    }

    /** Puts the shells back exactly as they were; a package that had no permission never gets one here. */
    public Result micRestore(OpRegistry.Key key, String execId, MicState mic) {
        return shortOperation(key, execId, () -> {
            MicState.Outcome outcome = mic.restore(new MicState.Applier() {
                public boolean grant(String pkg) {
                    return apply(pkg, "allow", true);
                }

                public boolean deny(String pkg) {
                    return apply(pkg, "deny", false);
                }

                private boolean apply(String pkg, String mode, boolean wanted) {
                    try {
                        if (!installed(pkg)) return true;         // gone is as restored as it can get
                        shell("appops set " + pkg + " RECORD_AUDIO " + mode);
                        shell("am force-stop " + pkg);
                        return granted(pkg, RECORD_AUDIO) == wanted;
                    } catch (Exception e) {
                        return false;
                    }
                }
            });
            return new Result(outcome.status, outcome.left.isEmpty() ? "" : outcome.left.size() + " left to restore");
        });
    }

    /** What a copied APK turned out to be, so the consent screen can name it before anything is installed. */
    public static final class Candidate {
        public final String error;
        public final String pkg;
        public final String versionName;
        public final long versionCode;
        public final String digest;

        Candidate(String error, String pkg, String versionName, long versionCode, String digest) {
            this.error = error;
            this.pkg = pkg;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.digest = digest;
        }
    }

    public static final long MAX_APK_BYTES = 64L * 1024 * 1024;
    public static final long COPY_LIMIT_MS = 60_000;

    /**
     * Copies the caller's file into our own directory and reads what it is (SPEC 0.12 pkt 5.8).
     *
     * <p>Everything that decides comes from the copy, never from the arguments: the source can change under a URI
     * between the check and the install, the copy cannot. A copy that overruns its budget is deleted rather than
     * left behind.
     */
    public Candidate copyAndInspect(android.net.Uri source, java.io.File target, String callerPackage, String expectedSha) {
        long deadline = SystemClock.elapsedRealtime() + COPY_LIMIT_MS;
        java.security.MessageDigest sha;
        try {
            sha = java.security.MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            return bad("failed");
        }
        long copied = 0;
        try (java.io.InputStream in = context.getContentResolver().openInputStream(source);
             java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
            if (in == null) return bad("failed");
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (SystemClock.elapsedRealtime() > deadline) return cleanUp(target, "failed");
                copied += read;
                if (copied > MAX_APK_BYTES) return cleanUp(target, "failed");
                sha.update(chunk, 0, read);
                out.write(chunk, 0, read);
            }
        } catch (Exception e) {
            return cleanUp(target, "failed");
        }

        StringBuilder hex = new StringBuilder(64);
        for (byte b : sha.digest()) hex.append(String.format("%02x", b));
        String digest = hex.toString();
        if (expectedSha != null && !expectedSha.isEmpty() && !expectedSha.equals(digest)) {
            return cleanUp(target, "failed");
        }

        PackageManager pm = context.getPackageManager();
        android.content.pm.PackageInfo apk = pm.getPackageArchiveInfo(target.getPath(), 0);
        if (apk == null) return cleanUp(target, "failed");
        if (!apk.packageName.equals(callerPackage)) return cleanUp(target, "unsupported");

        android.content.pm.PackageInfo installedInfo;
        try {
            installedInfo = pm.getPackageInfo(callerPackage, 0);
        } catch (Exception e) {
            return cleanUp(target, "unsupported");          // the bridge updates an app, it does not introduce one
        }
        if (apk.versionCode <= installedInfo.versionCode) return cleanUp(target, "unsupported");
        if (!CallerFacts.fingerprintOfArchive(pm, target.getPath())
                .equals(CallerFacts.fingerprint(pm, callerPackage))) {
            return cleanUp(target, "unsupported");          // another author is not an update, it is a replacement
        }
        return new Candidate(null, apk.packageName, apk.versionName, apk.versionCode, digest);
    }

    private Candidate cleanUp(java.io.File target, String error) {
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        return bad(error);
    }

    private static Candidate bad(String error) {
        return new Candidate(error, "", "", 0, "");
    }

    /**
     * Installs the copy through the root channel. A session of our own would not be silent: without a system
     * permission it ends in the very dialog this operation exists to avoid, and holding a root channel does not
     * grant that permission to this process.
     */
    public Result install(OpRegistry.Key key, String execId, java.io.File copy, String callerPackage, RootKit.Log log) {
        if (!rootAlive()) return new Result("unsupported", "no root channel");
        Executions.Grant grant = Executions.begin(execId, OpRegistry.INSTALLING, ExecutorLock.PRIVILEGED);
        if (!grant.granted) return new Result("busy", grant.reason);
        registry.stage(key, OpRegistry.INSTALLING);
        try {
            String staged = "/data/local/tmp/" + execId + ".apk";
            RootKit.channel(context, "cp " + copy.getAbsolutePath() + " " + staged);   // plain file work, no service needed
            RootKit.channel(context, "chmod 644 " + staged);
            // written before the installer starts: the other order can lose the file under a running install
            Executions.lock().handOff(execId, "installer");
            String output = shell("pm install -r " + staged);
            log.line(output);
            boolean started = output.contains("Success") || output.contains("Failure");
            if (!started) Executions.lock().handOffFailed(execId);
            RootKit.channel(context, "rm -f " + staged);
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
            String status = output.contains("Success") ? "ok" : "failed";
            return finish(key, execId, status, output.contains("Success") ? "installed" : "installer refused");
        } catch (Exception e) {
            return finish(key, execId, "failed", e.getClass().getSimpleName());
        }
    }

    private static final String RECORD_AUDIO = "android.permission.RECORD_AUDIO";

    private interface Work {
        Result run() throws Exception;
    }

    /** The shape every short privileged operation shares: take the lock, do it, record what happened. */
    private Result shortOperation(OpRegistry.Key key, String execId, Work work) {
        if (!rootAlive()) return new Result("unsupported", "no root channel");
        Executions.Grant grant = Executions.begin(execId, OpRegistry.RUNNING, ExecutorLock.PRIVILEGED);
        if (!grant.granted) return new Result("busy", grant.reason);
        registry.stage(key, OpRegistry.RUNNING);
        try {
            Result result = work.run();
            return finish(key, execId, result.status, result.detail);
        } catch (Exception e) {
            return finish(key, execId, "failed", e.getClass().getSimpleName());
        }
    }

    private boolean granted(String pkg, String permission) {
        return context.getPackageManager().checkPermission(permission, pkg)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean installed(String pkg) {
        return CallerFacts.installed(context.getPackageManager(), pkg);
    }

    /** The one enabled home activity of a package, or null when there is none or more than one. */
    private String homeComponentOf(String pkg) {
        android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME);
        java.util.List<android.content.pm.ResolveInfo> all =
                context.getPackageManager().queryIntentActivities(home, 0);
        String found = null;
        for (android.content.pm.ResolveInfo info : all) {
            if (info.activityInfo == null || !pkg.equals(info.activityInfo.packageName)) continue;
            if (found != null) return null;
            found = info.activityInfo.packageName + "/" + info.activityInfo.name;
        }
        return found;
    }

    /**
     * Runs a command that needs the system services.
     *
     * <p>The root channel runs in the kernel SELinux context, where the service manager is not reachable at all:
     * {@code service list} comes back empty and every {@code cmd} call answers "Can't find service". Dropping into
     * the shell context first is what makes appops, pm and dumpsys work. Verified on the clock, 19 September 2026.
     */
    /**
     * Whether an app may write system settings, read the way the system itself would answer.
     *
     * <p>AppOpsManager refuses to answer about another package without a privileged permission, so asking it here
     * would say "no" no matter what was just set. The shell command is the honest reading.
     */
    private boolean writeSettingsAllowed(String pkg) {
        try {
            return shell("appops get " + pkg + " WRITE_SETTINGS").contains("allow");
        } catch (Exception e) {
            return false;
        }
    }

    private String shell(String command) throws Exception {
        String quoted = command.replace("\\", "\\\\").replace("\"", "\\\"");
        return RootKit.channel(context, "runcon u:r:shell:s0 /system/bin/sh -c \"" + quoted + "\"");
    }

    private Result finish(OpRegistry.Key key, String execId, String status, String detail) {
        registry.finish(key, status);
        Executions.end(execId);
        return new Result(status, detail);
    }

    /**
     * Whether the root channel answers, with a deadline. The channel itself blocks forever when the daemon is gone,
     * and this is called from places that must not hang, so the wait is bounded here rather than hoped about.
     */
    private boolean rootAlive() {
        java.util.concurrent.Future<Boolean> answer = pool.submit(() -> {
            try {
                return RootKit.channel(context, "id").contains("uid=0");
            } catch (Throwable t) {
                return false;
            }
        });
        try {
            return answer.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            answer.cancel(true);
            return false;
        }
    }

    /** The measurements behind a snapshot; each one may fail, and failing means "unknown", never a default. */
    private final class DeviceProbe implements Snapshot.Probe {
        private final String callerPackage;

        DeviceProbe(String callerPackage) {
            this.callerPackage = callerPackage;
        }

        public Boolean root() {
            return rootAlive();
        }

        public Boolean ssh() {
            try {
                return !RootKit.channel(context, "netstat -ltn | grep 2223").trim().isEmpty();
            } catch (Throwable t) {
                return null;
            }
        }

        public String adbProperty() {
            try {
                return RootKit.channel(context, "getprop service.adb.tcp.port").trim();
            } catch (Throwable t) {
                return null;
            }
        }

        public Boolean adbListening() {
            return AdbState.listening();
        }

        public String firmware() {
            return Build.DISPLAY;
        }

        public Boolean firmwareSupported() {
            return BridgeExecutor.firmwareSupported();
        }

        public List<String> micHolders() {
            try {
                if (!rootAlive()) return null;      // an unprivileged list is anonymised, so it would be a lie
                return AdbState.micHolders(shell("dumpsys audio"), Ops.MIC_TARGETS);
            } catch (Throwable t) {
                return null;
            }
        }

        public String micSavedState() {
            return mic == null ? "none" : mic.state();
        }

        public String toolVersion() {
            try {
                return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Throwable t) {
                return null;
            }
        }

        public JSONObject caller() {
            JSONObject out = new JSONObject();
            try {
                out.put("package", callerPackage == null ? "" : callerPackage);
                if (callerPackage == null) return out;
                PackageManager pm = context.getPackageManager();
                PackageInfo info = pm.getPackageInfo(callerPackage, PackageManager.GET_PERMISSIONS);
                out.put("version_code", info.versionCode);
                out.put("record_audio", pm.checkPermission("android.permission.RECORD_AUDIO", callerPackage)
                        == PackageManager.PERMISSION_GRANTED ? "granted" : "denied");
                out.put("declares_home", CallerFacts.declaresHome(pm, callerPackage));
                out.put("is_home", callerPackage.equals(CallerFacts.currentHome(pm)));
                out.put("write_settings", writeSettingsAllowed(callerPackage) ? "allowed" : "denied");
            } catch (Throwable t) {
                return out;
            }
            return out;
        }
    }

    private static final class UptimeClock implements Snapshot.Clock {
        public long uptimeMs() {
            return SystemClock.elapsedRealtime();
        }

        public String bootId() {
            ExecutorLock lock = Executions.lock();
            return lock == null ? "" : String.valueOf(lock.trace() == null ? "" : lock.trace().optString("boot_id"));
        }
    }
}
