package pl.mateusz.clockadbprobe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.util.concurrent.atomic.AtomicBoolean;
import pl.mateusz.clockadbprobe.bridge.AdbState;
import pl.mateusz.clockadbprobe.bridge.BridgeFiles;
import pl.mateusz.clockadbprobe.bridge.ExecutorLock;
import pl.mateusz.clockadbprobe.bridge.Executions;

/** Idempotent process-level bootstrap for the authenticated LAN agent. */
final class AgentRuntime {
    private static final int[] PORTS = {8555, 8556, 8557, 8558};
    private static final AtomicBoolean STARTING = new AtomicBoolean(false);

    private AgentRuntime() {}

    static void configure(final Context context) {
        final Context application = context.getApplicationContext();
        Report.APP_VERSION = describeVersion(application);
        AgentServer.setDefaultProbeTrigger(new AgentServer.ProbeTrigger() {
            @Override public boolean trigger(String name) {
                return AgentRuntime.trigger(application, name);
            }
        });
    }


    /** Replaceable so a test can hold the chain still instead of running an exploit. */
    interface ChainStarter {
        int start(Context application, RootKit.Log log) throws Exception;
    }

    private static volatile ChainStarter chainStarter = new ChainStarter() {
        public int start(Context application, RootKit.Log log) throws Exception {
            return RootKit.run(application, log);
        }
    };

    static void setChainStarter(ChainStarter starter) {
        chainStarter = starter == null ? new ChainStarter() {
            public int start(Context application, RootKit.Log log) throws Exception {
                return RootKit.run(application, log);
            }
        } : starter;
    }

    /** The trace is installed by whoever touches the bridge first; tests install their own. */
    private static void ensureBridgeState(Context application) {
        if (Executions.lock() == null && application != null) BridgeFiles.get(application);
    }

    /** One chain at a time, whoever asks: the lock and the executor trace are taken together. */
    static boolean startChain(final Context application) {
        ensureBridgeState(application);
        final String execId = Executions.execId("agent", "local", "rootssh");
        Executions.Grant grant = Executions.begin(execId, "running", ExecutorLock.PRIVILEGED);
        if (!grant.granted) {
            Report.get().log("ROOTKIT", "refused: " + grant.reason);
            return false;
        }
        new Thread(new Runnable() { public void run() {
            try {
                Executions.lock().promote(execId);
                int rc = chainStarter.start(application, new RootKit.Log() {
                    public void line(String text) {
                        Report.get().log("ROOTKIT", text);
                    }
                });
                Report.get().log("ROOTKIT", "bootstrap exit " + rc + " | " + RootKit.sshHint());
            } catch (Throwable t) {
                Report.get().exception("AgentRuntime.rootssh", t);
            } finally {
                Executions.end(execId);
            }
        }}).start();
        return true;
    }

    /** The ADB toggle without an Activity; until now it existed only behind the screen. */
    static boolean switchAdb(final Context application, final boolean on) {
        ensureBridgeState(application);
        final String execId = Executions.execId("agent", "local", on ? "adbwifion" : "adbwifioff");
        Executions.Grant grant = Executions.begin(execId, "running", ExecutorLock.PRIVILEGED);
        if (!grant.granted) {
            Report.get().log("ADBWIFI", "refused: " + grant.reason);
            return false;
        }
        new Thread(new Runnable() { public void run() {
            try {
                RootKit.runAdbWifi(application, new RootKit.Log() {
                    public void line(String text) {
                        Report.get().log("ADBWIFI", text);
                    }
                }, on);
                boolean listening = AdbState.listening();
                Report.get().log("ADBWIFI", on ? AdbState.onResult("", listening) : AdbState.offResult("", listening));
            } catch (Throwable t) {
                Report.get().exception("AgentRuntime.adbwifi", t);
            } finally {
                Executions.end(execId);
            }
        }}).start();
        return true;
    }

    /** Handles agent probes that remain available when no Activity is foregrounded. */
    static boolean trigger(Context application, String name) {
        if ("execstop".equals(name)) return ExecUtil.cancelRemoteShell();
        if ("openmenu".equals(name) || "showapp".equals(name)) return openMenu(application);
        if ("home".equals(name)) return goHome();
        if ("selfupdate".equals(name)) {
            InstallActivity.startInstall(application,
                    InstallActivity.DEFAULT_URL, null);
            return true;
        }
        if ("rootssh".equals(name)) {
            // Same code path as the ROOT + SSH button, reachable over the LAN so
            // the chain can be driven and watched without touching the screen.
            // It goes through Executions like every other entry point: without
            // that, two LAN calls could run two chains at once (SPEC 0.12 pkt 8.4).
            return startChain(application);
        }
        if ("adbwifion".equals(name) || "adbwifioff".equals(name)) {
            return switchAdb(application, "adbwifion".equals(name));
        }
        if ("floaton".equals(name)) {
            // Toggle the floating nav overlay
            try {
                application.startService(new Intent(application, OverlayService.class));
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        if ("taparm".equals(name)) { AutoTapService.armed = true; return true; }
        if ("tapdisarm".equals(name)) { AutoTapService.armed = false; return true; }
        return false;
    }

    /** Synchronous entry point for an existing worker thread. */
    static void start(Context context) {
        Context application = context.getApplicationContext();
        configure(application);
        AgentServer.start(application, PORTS);
    }

    /** Safe to call from AccessibilityService.onServiceConnected(). */
    static void ensureStarted(final Context context) {
        final Context application = context.getApplicationContext();
        configure(application);
        if (AgentServer.isRunning() || !STARTING.compareAndSet(false, true)) return;
        Thread bootstrap = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    AgentServer.start(application, PORTS);
                } finally {
                    STARTING.set(false);
                }
            }
        }, "agent-bootstrap");
        bootstrap.setDaemon(true);
        try {
            bootstrap.start();
        } catch (Throwable failure) {
            STARTING.set(false);
            Report.get().exception("AgentRuntime.start", failure);
        }
    }

    /** Keep the agent alive after HOME moves the diagnostic Activity to the background. */
    static void ensureResident(Context context) {
        Context application = context.getApplicationContext();
        Intent service = new Intent(application, AgentKeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                application.startForegroundService(service);
            } else {
                application.startService(service);
            }
        } catch (Throwable failure) {
            Report.get().exception("AgentRuntime.ensureResident", failure);
            ensureStarted(application);
        }
    }

    /** Bring the diagnostic UI forward. Uses a full-screen intent
     *  notification on Android 10+, which is exempt from the background
     *  activity start restriction (same mechanism as incoming calls). */
    static boolean openMenu(Context context) {
        Context application = context.getApplicationContext();
        Intent intent = new Intent(application, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // Try direct startActivity first (works when app is foreground)
        try {
            application.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            // Fall through to full-screen intent
        }
        // Full-screen intent: fires the activity even from background
        try {
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    application, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    application.getSystemService(Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch =
                        new android.app.NotificationChannel(
                                "showapp", "Show App",
                                android.app.NotificationManager.IMPORTANCE_HIGH);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
            android.app.Notification notification =
                    new android.app.Notification.Builder(application, "showapp")
                            .setSmallIcon(android.R.drawable.ic_menu_view)
                            .setContentTitle("Smart Clock 2 Tools")
                            .setContentText("Tap to open")
                            .setFullScreenIntent(pi, true)
                            .setAutoCancel(true)
                            .build();
            nm.notify(9999, notification);
            return true;
        } catch (Throwable failure) {
            Report.get().exception("AgentRuntime.openMenu/fsi", failure);
            return false;
        }
    }

    /** Return to the stock launcher/clock through the already-enabled service. */
    static boolean goHome() {
        AutoTapService service = AutoTapService.instance;
        if (!AutoTapService.connected || service == null) return false;
        return service.doGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
    }

    private static String describeVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
            return context.getPackageName() + " v" + info.versionName;
        } catch (Throwable failure) {
            return "?";
        }
    }
}
