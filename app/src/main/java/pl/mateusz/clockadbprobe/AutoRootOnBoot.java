package pl.mateusz.clockadbprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.io.RandomAccessFile;

import pl.mateusz.clockadbprobe.bridge.BridgeExecutor;

/** Android storage and execution around the durable auto-root gate. */
final class AutoRootOnBoot {
    private static final String PREFS = "auto_root_on_boot";
    private static final String KEY_STATE = "state";
    private static final String KEY_BOOT = "boot_id";

    private AutoRootOnBoot() {}

    static synchronized String state(Context context) {
        return policy(context).state();
    }

    static synchronized boolean enable(Context context) {
        return policy(context).enable(bootId(context, true));
    }

    static synchronized boolean disable(Context context) {
        return policy(context).disable();
    }

    static void onBoot(final Context context) {
        try {
            onBootChecked(context);
        } catch (Throwable t) {
            Report.get().exception("AutoRootOnBoot.onBoot", t);
            note("automatic run skipped after an unexpected error");
        }
    }

    private static void onBootChecked(final Context context) {
        final Context application = context.getApplicationContext();
        final String bootId = bootId(application, false);
        final AutoRootPolicy.Decision decision;
        synchronized (AutoRootOnBoot.class) {
            decision = policy(application).claim(bootId);
        }
        if (decision != AutoRootPolicy.Decision.START) {
            if (decision == AutoRootPolicy.Decision.BLOCKED) {
                note("previous attempt did not finish; automatic run blocked");
            } else if (decision == AutoRootPolicy.Decision.STORAGE_FAILED) {
                note("cannot save the safety marker; automatic run skipped");
            }
            return;
        }

        try {
            // A firmware update must never run a kernel write calibrated for the old build.
            if (!BridgeExecutor.firmwareSupported()) {
                finish(application, bootId, false);
                note("unsupported firmware; automatic run blocked");
                return;
            }
            note("starting ROOT + ADB after boot");
            boolean started = AgentRuntime.startChain(application, null, new AgentRuntime.ChainListener() {
                @Override public void finished(int exitCode, boolean adbListening) {
                    boolean success = exitCode == 0 && adbListening
                            && RootKit.rootChannelAlive(application);
                    boolean saved = finish(application, bootId, success);
                    if (saved) {
                        note(success ? "ROOT + ADB finished; root channel and ADB listening"
                                : "ROOT + ADB did not finish successfully; automatic run blocked");
                    }
                }
            });
            if (started) return;
            finish(application, bootId, false);
            note("chain was busy; automatic run blocked");
        } catch (Throwable t) {
            Report.get().exception("AutoRootOnBoot.onBoot", t);
            finish(application, bootId, false);
            note("automatic run failed before completion; blocked");
        }
    }

    private static synchronized boolean finish(Context context, String bootId, boolean success) {
        boolean saved = policy(context).finish(bootId, success);
        if (!saved) {
            note("could not save completion; next boot will skip the automatic run");
        }
        return saved;
    }

    private static AutoRootPolicy policy(Context context) {
        final SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new AutoRootPolicy(new AutoRootPolicy.Store() {
            @Override public String state() { return prefs.getString(KEY_STATE, AutoRootPolicy.OFF); }
            @Override public String bootId() { return prefs.getString(KEY_BOOT, ""); }
            @Override public boolean save(String state, String bootId) {
                // commit() is required: the marker must reach storage before the kernel write.
                return prefs.edit().putString(KEY_STATE, state).putString(KEY_BOOT, bootId).commit();
            }
        });
    }

    /** Use only boot identifiers that cannot be mistaken for a later boot. */
    private static String bootId(Context context, boolean allowSourceChange) {
        String previous = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BOOT, "");
        if (previous == null) previous = "";
        // Keep the same source for every attempt. Switching sources during one
        // boot could otherwise make a duplicate BOOT_COMPLETED look like a reboot.
        // A user's explicit re-arm may choose the other source if the old one vanished.
        if (previous.startsWith("count:")) {
            String count = bootCount(context);
            return count.isEmpty() && allowSourceChange ? kernelBootId() : count;
        }
        if (previous.startsWith("kernel:")) {
            String kernel = kernelBootId();
            return kernel.isEmpty() && allowSourceChange ? bootCount(context) : kernel;
        }
        String count = bootCount(context);
        return count.isEmpty() ? kernelBootId() : count;
    }

    private static String bootCount(Context context) {
        try {
            int count = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
            if (count >= 0) return "count:" + count;
        } catch (Exception ignored) {
            // A missing boot counter is not a reason to guess a boot identity.
        }
        return "";
    }

    private static String kernelBootId() {
        try (RandomAccessFile file = new RandomAccessFile(
                "/proc/sys/kernel/random/boot_id", "r")) {
            String id = file.readLine();
            return id == null || id.trim().isEmpty() ? "" : "kernel:" + id.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void note(String text) {
        Report.get().log("CONCLUSIONS", "AUTO ROOT: " + text);
    }
}
