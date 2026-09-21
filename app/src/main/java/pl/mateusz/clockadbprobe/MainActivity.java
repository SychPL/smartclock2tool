package pl.mateusz.clockadbprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Two-column UI: the buttons that are still used day to day on the left, a live
 * log on the right. The probe/report machinery behind GET ALL / SCAN / mic /
 * speaker stays reachable over the LAN agent (AgentRuntime + /agent/probe), it
 * just no longer takes screen space on a 480x800 panel.
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private ScrollView logScroll;
    private volatile boolean running = false;
    private boolean pendingGetAll = false;
    private AgentServer.ProbeTrigger activityProbeTrigger;

    /**
     * Leaves the tools the way the user expects: back to the launcher that started them (Helios is the home
     * app on this clock), and only if that fails, by finishing this activity.
     */
    private void leave() {
        try {
            android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            home.addCategory(android.content.Intent.CATEGORY_HOME);
            home.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Throwable t) {
            Report.get().exception("MainActivity.leave", t);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        leave();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.statusText);
        logScroll = findViewById(R.id.logScroll);
        // Version visible on the main screen: no more guessing which build is on.
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            android.widget.TextView ver = findViewById(R.id.appVersion);
            if (ver != null) ver.setText("v" + pi.versionName + " (" + pi.versionCode + ")");
        } catch (Throwable t) {
            Report.get().exception("MainActivity.versionLabel", t);
        }
        // The clock has no navigation bar and no recents: whoever opens these tools has to be able to leave
        // them, so the way out is a visible button rather than a gesture nobody can perform on this device.
        android.widget.Button exit = findViewById(R.id.btnExit);
        if (exit != null) exit.setOnClickListener(v -> leave());

        wire(R.id.btnRootSsh, new Runnable() { public void run() { rootAll(); }});
        wire(R.id.btnStatus, new Runnable() { public void run() { statusCheck(); }});
        wire(R.id.btnAdbWifi, new Runnable() { public void run() { adbWifiAction(null); }});
        wire(R.id.btnReport, new Runnable() { public void run() { fullReport(); }});
        wire(R.id.btnInstallApk, new Runnable() { public void run() {
            startActivity(new Intent(MainActivity.this, InstallActivity.class));
        }});
        wire(R.id.btnOpenDevSettings, new Runnable() { public void run() { openDevSettings(); }});
        wire(R.id.btnClearLog, new Runnable() { public void run() { clearLog(); }});

        // Remote-control agent: starts with the app so the research PC can
        // drive probes and read the report over the LAN.
        Report.APP_VERSION = appVersion();
        final Context agentContext = getApplicationContext();
        AgentRuntime.ensureResident(agentContext);
        new Thread(new Runnable() { public void run() {
            AgentRuntime.start(agentContext);
            NetProbe.probe(MainActivity.this, Report.get());
            final String ip = NetProbe.wifiIp != null ? NetProbe.wifiIp : "<clock-ip>";
            final int p = AgentServer.port();
            final String agentToken = AgentServer.token();
            if (p > 0) ui.post(new Runnable() { public void run() {
                logLine("agent: http://" + ip + ":" + p + "/agent/status");
                logLine("token: " + agentToken);
            }});
            // Paint the ADB toggle with whatever state the clock is in now.
            refreshAdbButton();
        }}, "agent-start").start();
        activityProbeTrigger = new AgentServer.ProbeTrigger() {
            public boolean trigger(final String name) {
                if ("openmenu".equals(name)) {
                    return AgentRuntime.openMenu(getApplicationContext());
                }
                if ("home".equals(name)) {
                    return AgentRuntime.goHome();
                }
                if ("execstop".equals(name)) {
                    return tryStopCurrentExec(name);
                }
                if (OperationGate.isBusy()) {
                    return false;
                }
                ui.post(new Runnable() { public void run() { handleAgentProbe(name); }});
                return true;
            }
        };
        AgentServer.setProbeTrigger(activityProbeTrigger);
    }

    static boolean tryStopCurrentExec(String name) {
        return "execstop".equals(name) && ExecUtil.cancelRemoteShell();
    }

    @Override
    protected void onDestroy() {
        AgentServer.clearProbeTrigger(activityProbeTrigger);
        activityProbeTrigger = null;
        super.onDestroy();
    }

    /** Appends to the log pane and keeps it pinned to the newest line. */
    void logLine(final String text) {
        ui.post(new Runnable() { public void run() {
            status.append(text + "\n");
            logScroll.post(new Runnable() { public void run() {
                logScroll.fullScroll(View.FOCUS_DOWN);
            }});
        }});
    }

    private void clearLog() {
        status.setText("");
        logLine("Ready. Press ROOT + ADB.");
    }

    // ---------- root / adb ----------

    /** One press: unpack the payloads, run the whole root chain (which ends by
     *  bringing up SSH 2223 and ADB over Wi-Fi on 5555) and then read the state
     *  back so the log says what actually works. */
    private void rootAll() {
        if (running) {
            Toast.makeText(this, "Already running", Toast.LENGTH_SHORT).show();
            return;
        }
        running = true;
        status.setText("");
        logLine("== ROOT + SSH + ADB");
        new Thread(new Runnable() { public void run() {
            int rc = -1;
            try {
                rc = RootKit.run(getApplicationContext(), new RootKit.Log() {
                    public void line(String text) { logLine(text); }
                });
            } catch (Throwable t) {
                Report.get().exception("MainActivity.rootAll", t);
                logLine("FAILED: " + t);
            } finally {
                running = false;
            }
            logLine(rc == 0 ? "bootstrap exit 0" : "bootstrap exit " + rc);
            for (String line : RootKit.stateLines(getApplicationContext())) logLine(line);
            refreshAdbButton();
        }}, "rootkit").start();
    }

    /** Read-only check of what is up: root channel, SSH, ADB. */
    private void statusCheck() {
        new Thread(new Runnable() { public void run() {
            for (String line : RootKit.stateLines(getApplicationContext())) logLine(line);
        }}, "rootkit-status").start();
    }

    /**
     * ADB over Wi-Fi, on or off: null flips the current state, true/false forces
     * it. The button label mirrors the live state, so ON/OFF is visible without
     * reading the log.
     */
    private void adbWifiAction(final Boolean want) {
        if (running) {
            Toast.makeText(this, "Already running", Toast.LENGTH_SHORT).show();
            return;
        }
        running = true;
        logLine("== ADB over Wi-Fi");
        new Thread(new Runnable() { public void run() {
            final boolean on = (want != null) ? want.booleanValue()
                    : !RootKit.adbWifiOn(getApplicationContext());
            logLine("   state: turning " + (on ? "ON" : "OFF") + " (was "
                    + (on ? "OFF" : "ON") + ")");
            try {
                RootKit.runAdbWifi(getApplicationContext(), new RootKit.Log() {
                    public void line(String text) { logLine(text); }
                }, on);
            } catch (Throwable t) {
                Report.get().exception("MainActivity.adbWifi", t);
                logLine("FAILED: " + t);
            } finally {
                running = false;
            }
            for (String line : RootKit.stateLines(getApplicationContext())) logLine(line);
            refreshAdbButton();
        }}, "adbwifi").start();
    }

    /** Reads the live state off the UI thread and paints it on the button. */
    private void refreshAdbButton() {
        final boolean on = RootKit.adbWifiOn(getApplicationContext());
        ui.post(new Runnable() { public void run() {
            Button b = findViewById(R.id.btnAdbWifi);
            if (b != null) b.setText(on ? "ADB WI-FI: ON" : "ADB WI-FI: OFF");
        }});
    }

    private void wire(int id, final Runnable r) {
        Button b = findViewById(id);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }
        });
    }

    private void say(final String msg) {
        ui.post(new Runnable() { public void run() {
            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
        }});
        logLine(msg);
    }

    /** Runs probe on a worker thread; disables buttons while running. */
    private void runProbe(final String name, final Runnable body) {
        if (!OperationGate.tryStartProbe()) {
            Toast.makeText(this, "Busy — wait for current test", Toast.LENGTH_SHORT).show();
            return;
        }
        running = true;
        logLine("== " + name);
        Thread probeThread = new Thread(new Runnable() {
            public void run() {
                try {
                    body.run();
                    Report.get().line("CONCLUSIONS", "[" + Report.ts() + "] " + name + " finished.");
                    ui.post(new Runnable() { public void run() {
                        logLine(name + " — done. Opening report.");
                        openReportIfDesired(name);
                    }});
                } catch (final Throwable t) {
                    Report.get().exception(name, t);
                    say(name + " failed: " + t);
                } finally {
                    running = false;
                    OperationGate.finishProbe();
                }
            }
        }, "probe");
        try {
            probeThread.start();
        } catch (Throwable failure) {
            running = false;
            OperationGate.finishProbe();
            Report.get().exception(name + ".thread.start", failure);
            say(name + " failed to start: " + failure);
        }
    }

    private void openReportIfDesired(String name) {
        // Tests other than mic/speaker jump straight to the report so results
        // are visible on the small screen.
        if (name.contains("MICROPHONE") || name.contains("SPEAKER") || name.contains("PLAY")) return;
        startActivity(new Intent(this, ReportActivity.class));
    }

    // ---------- probes (agent-driven) ----------

    /** Dispatches agent-triggered probes by name (already on UI thread). */
    private void handleAgentProbe(String name) {
        Report.get().log("COMMAND RESULTS", "AGENT probe: " + name);
        if ("rootssh".equals(name)) { rootAll(); return; }
        if ("adbwifi".equals(name)) { adbWifiAction(null); return; }
        if ("adbwifion".equals(name)) { adbWifiAction(Boolean.TRUE); return; }
        if ("adbwifioff".equals(name)) { adbWifiAction(Boolean.FALSE); return; }
        if ("status".equals(name)) { statusCheck(); return; }
        if ("getall".equals(name)) { getAll(false); return; }
        if ("fullreport".equals(name)) { fullReport(); return; }
        if ("devinfo".equals(name)) { deviceInfo(); return; }
        if ("scan".equals(name)) { scanFirmware(); return; }
        if ("checkadb".equals(name)) { checkAdb(); return; }
        if ("tryadb".equals(name)) {
            runProbe("TRY ADB WIFI (agent)", new Runnable() { public void run() {
                tryAdbWifiBody(Report.get());
            }});
            return;
        }
        if ("mic".equals(name)) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                runProbe("TEST MICROPHONE (agent)", new Runnable() { public void run() {
                    MicTester.test(MainActivity.this, Report.get());
                }});
            } else {
                Report.get().line("MICROPHONE TEST", "AGENT: RECORD_AUDIO not granted — mic skipped");
            }
            return;
        }
        if ("speaker".equals(name)) {
            runProbe("TEST SPEAKER (agent)", new Runnable() { public void run() {
                SpeakerTester.playTone(MainActivity.this, Report.get());
            }});
            return;
        }
        if ("play".equals(name)) {
            runProbe("PLAY RECORDING (agent)", new Runnable() { public void run() {
                SpeakerTester.playRecording(MainActivity.this, Report.get());
            }});
            return;
        }
        if ("pull".equals(name)) { pullApks(); return; }
        if ("apkserver".equals(name)) { toggleApkServer(); return; }
        if ("sendreport".equals(name)) { sendReport(); return; }
        if ("devsettings".equals(name)) { openDevSettings(); return; }
        if ("settings".equals(name)) { openSettings(); return; }
        if ("floaton".equals(name)) {
            if (!OperationGate.tryStartProbe()) {
                Report.get().line("COMMAND RESULTS", "FLOAT NAV rejected: another operation is active");
                return;
            }
            try {
                OverlayService.toggle(MainActivity.this);
            } finally {
                OperationGate.finishProbe();
            }
            return;
        }
        if ("taparm".equals(name)) {
            AutoTapService.armed = true;
            Report.get().line("COMMAND RESULTS", "AUTOTAP armed — install dialogs will be auto-confirmed");
            return;
        }
        if ("tapdisarm".equals(name)) {
            AutoTapService.armed = false;
            Report.get().line("COMMAND RESULTS", "AUTOTAP disarmed");
            return;
        }
        if ("openaccessibility".equals(name)) {
            if (!OperationGate.tryStartProbe()) {
                Report.get().line("COMMAND RESULTS",
                        "OPEN ACCESSIBILITY rejected: another operation is active");
                return;
            }
            try {
                startActivity(new Intent("android.settings.ACCESSIBILITY_SETTINGS"));
                Report.get().log("COMMAND RESULTS", "Opened accessibility settings (enable Smart Clock 2 Tools AutoTap)");
            } catch (Throwable t) {
                Report.get().exception("openaccessibility", t);
            } finally {
                OperationGate.finishProbe();
            }
            return;
        }
        if ("selfupdate".equals(name)) {
            InstallActivity.startInstall(MainActivity.this,
                    InstallActivity.DEFAULT_URL, null);
            return;
        }
        Report.get().line("COMMAND RESULTS", "AGENT: unknown probe '" + name + "'");
    }

    private void confirmGetAll() {
        new AlertDialog.Builder(this)
                .setTitle("GET ALL")
                .setMessage("Runs every module end-to-end:\n\n"
                        + "- device info, network, audio hardware\n"
                        + "- firmware scan (packages, components, binder, settings, properties)\n"
                        + "- ADB state check + safe shell commands\n"
                        + "- microphone test (5 s recording)\n"
                        + "- playback of the recording\n"
                        + "- speaker tone\n\n"
                        + "This test will attempt to change debugging state.\n"
                        + "It will not modify bootloader or partitions.\n"
                        + "The report is NOT uploaded automatically — use SEND REPORT afterwards.")
                .setPositiveButton("Run all", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { getAll(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void getAll() { getAll(true); }

    /** @param allowPermissionRequest false when triggered remotely (agent):
     *  skips the mic permission dialog instead of blocking on it. */
    private void getAll(boolean allowPermissionRequest) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (allowPermissionRequest) {
                pendingGetAll = true;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                return;
            }
            Report.get().line("MICROPHONE TEST", "AGENT: RECORD_AUDIO not granted — mic part skipped");
            runGetAllBody(false);
            return;
        }
        runGetAllBody(true);
    }

    private void runGetAllBody(final boolean withMic) {
        runProbe("GET ALL", new Runnable() { public void run() {
            Report rep = Report.get();
            Context ctx = MainActivity.this;
            say("Device info…");
            DeviceInfo.probe(ctx, rep);
            NetProbe.probe(ctx, rep);
            MicTester.audioHardwareInfo(ctx, rep);
            say("Settings & properties…");
            SettingsProbe.probe(ctx, rep);
            Props.collect(rep);
            say("Packages & binder…");
            ComponentScanner.scan(ctx, rep);
            BinderProbe.probe(ctx, rep);
            PortMap.probe(rep);
            say("ADB state…");
            AdbProbe.checkAdbState(ctx, rep);
            safeCommands(rep);
            say("ADB over Wi-Fi (via the root channel)…");
            try {
                RootKit.runAdbWifi(ctx, new RootKit.Log() {
                    public void line(String text) { Report.get().log("ADB WIFI", text); }
                }, true);
            } catch (Throwable t) {
                Report.get().exception("GET ALL/adbWifi", t);
            }
            if (withMic) {
                say("Microphone (5 s)…");
                MicTester.test(ctx, rep);
                say("Playing recording…");
                SpeakerTester.playRecording(ctx, rep);
            }
            say("Speaker tone…");
            SpeakerTester.playTone(ctx, rep);
            rep.line("CONCLUSIONS", "GET ALL: all modules executed in sequence.");
        }});
    }

    private void deviceInfo() {
        runProbe("DEVICE INFO", new Runnable() { public void run() {
            Context ctx = MainActivity.this;
            DeviceInfo.probe(ctx, Report.get());
            NetProbe.probe(ctx, Report.get());
            MicTester.audioHardwareInfo(ctx, Report.get());
            Report.get().line("CONCLUSIONS", "Device info collected (device/build/android/network/audio hw).");
        }});
    }

    private void scanFirmware() {
        runProbe("SCAN FIRMWARE", new Runnable() { public void run() {
            Report rep = Report.get();
            say("Enumerating packages…");
            ComponentScanner.scan(MainActivity.this, rep);
            say("Binder services…");
            BinderProbe.probe(MainActivity.this, rep);
            SettingsProbe.probe(MainActivity.this, rep);
            Props.collect(rep);
            rep.line("CONCLUSIONS", "Firmware scan done. Check VENDOR / LENOVO / MTK FINDINGS for exported components.");
        }});
    }

    private void checkAdb() {
        runProbe("CHECK ADB", new Runnable() { public void run() {
            Report rep = Report.get();
            NetProbe.probe(MainActivity.this, rep);
            AdbProbe.checkAdbState(MainActivity.this, rep);
            Props.collect(rep);
            SettingsProbe.probe(MainActivity.this, rep);
            safeCommands(rep);
            boolean listening = AdbProbe.adbListening(rep);
            rep.line("CONCLUSIONS", "ADB TCP 5555 listening: " + listening);
        }});
    }

    /** Legacy attempt: setprop service.adb.tcp.port. Kept for the agent only —
     *  the property service refuses it, which is why the real path writes the
     *  property area directly (RootKit.runAdbWifi). */
    private void tryAdbWifiBody(Report rep) {
        NetProbe.probe(MainActivity.this, rep);
        rep.log("ADB TCP ATTEMPTS", "Attempting setprop service.adb.tcp.port 5555…");
        execAndLog(rep, "setprop", "service.adb.tcp.port", "5555");
        execAndLog(rep, "setprop", "persist.adb.tcp.port", "5555");
        rep.log("ADB TCP ATTEMPTS", "Re-reading properties after attempt:");
        String p1 = Props.get("service.adb.tcp.port");
        rep.line("ADB TCP ATTEMPTS", "service.adb.tcp.port now = " + p1);
        boolean listening = AdbProbe.adbListening(rep);
        rep.line("CONCLUSIONS", "ADB WiFi setprop attempt finished; 5555 listening=" + listening);
    }

    private void execAndLog(Report rep, String... cmd) {
        StringBuilder cmdStr = new StringBuilder();
        for (String c : cmd) cmdStr.append(c).append(' ');
        String[] r = ExecUtil.run(cmd);
        rep.line("COMMAND RESULTS", "COMMAND: " + cmdStr.toString().trim());
        rep.line("COMMAND RESULTS", "EXIT CODE: " + r[0]);
        if (!r[1].isEmpty()) rep.line("COMMAND RESULTS", "STDOUT:\n" + r[1].trim());
        if (!r[2].isEmpty()) rep.line("COMMAND RESULTS", "STDERR/EXCEPTION:\n" + r[2].trim());
    }

    private void safeCommands(Report rep) {
        execAndLog(rep, "getprop");
        execAndLog(rep, "id");
        execAndLog(rep, "whoami");
        execAndLog(rep, "ps");
        execAndLog(rep, "ip", "addr");
        execAndLog(rep, "ip", "route");
        execAndLog(rep, "uname", "-a");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_MIC || grantResults.length == 0) return;
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingGetAll) { pendingGetAll = false; getAll(); }
        } else {
            pendingGetAll = false;
            Report.get().line("MICROPHONE TEST", "RECORD_AUDIO permission DENIED by user");
        }
    }

    /** Copies world-readable system APKs + property contexts into app storage. */
    private void pullApks() {
        runProbe("PULL SYSTEM APKS", new Runnable() { public void run() {
            ApkPuller.pull(MainActivity.this, Report.get());
        }});
    }

    /** Shared with AgentServer so /agent/status can report the file port. */
    static FileServer fileServerShared;

    private final FileServer fileServer = new FileServer();
    { fileServerShared = fileServer; }

    /** Exposes the pulled files on http://<clock-ip>:8443/ for PC download.
     *  Socket bind happens on a worker thread — StrictMode forbids it on main. */
    private void toggleApkServer() {
        if (!OperationGate.tryStartProbe()) {
            Toast.makeText(this, "Busy — wait for current test", Toast.LENGTH_SHORT).show();
            return;
        }
        if (fileServer.isRunning()) {
            try {
                fileServer.stop(new FileServer.Log() { public void log(String l) {
                    Report.get().log("COMMAND RESULTS", l);
                }});
                logLine("APK server stopped");
            } finally {
                OperationGate.finishProbe();
            }
            return;
        }
        try {
            final File root = ApkPuller.pullDir(MainActivity.this);
            final int[] ports = {8443, 8444, 8080, 8888, 9000};
            logLine("Starting APK server…");
            new Thread(new Runnable() { public void run() {
                try {
                    final int port = fileServer.start(root, ports,
                            new FileServer.Log() { public void log(String l) {
                                Report.get().log("COMMAND RESULTS", l);
                            }});
                    NetProbe.probe(MainActivity.this, Report.get());
                    final String ip = NetProbe.wifiIp != null
                            ? NetProbe.wifiIp : "<clock-ip>";
                    final String msg = port > 0
                            ? "Serving: http://" + ip + ":" + port + "/"
                            : "Server failed on all ports: " + fileServer.lastError;
                    Report.get().line("CONCLUSIONS", "APK server " + (port > 0
                            ? "UP at http://" + ip + ":" + port + "/"
                            : "FAILED: " + fileServer.lastError));
                    ui.post(new Runnable() { public void run() {
                        logLine(msg);
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    }});
                } finally {
                    OperationGate.finishProbe();
                }
            }}, "apk-server").start();
        } catch (Throwable failure) {
            OperationGate.finishProbe();
            Report.get().exception("APK server start", failure);
            logLine("APK server failed to start");
        }
    }

    private void openDevSettings() {
        Report rep = Report.get();
        try {
            startActivity(new Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"));
            rep.log("CONCLUSIONS", "Launched Developer options (user check for vendor ADB/network toggles)");
        } catch (Throwable t) {
            rep.exception("openDevSettings", t);
            Toast.makeText(this, "Dev settings unavailable: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private void openSettings() {
        Report rep = Report.get();
        try {
            startActivity(new Intent("android.settings.SETTINGS"));
            rep.log("CONCLUSIONS", "Launched Android Settings");
        } catch (Throwable t) {
            rep.exception("openSettings", t);
            Toast.makeText(this, "Settings unavailable: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private void sendReport() {
        runProbe("SEND REPORT", new Runnable() { public void run() {
            Report rep = Report.get();
            // Fresh network snapshot so the uploaded IP is current.
            NetProbe.probe(MainActivity.this, rep);
            String body = rep.render(appVersion());
            rep.log("REPORT UPLOAD", "POST " + ReportUploader.ENDPOINT
                    + " body=" + body.length() + " chars…");
            try {
                String r = ReportUploader.upload(body, appVersion());
                rep.line("REPORT UPLOAD", "Server response: " + r);
                rep.line("CONCLUSIONS", "Report uploaded to " + ReportUploader.ENDPOINT + " -> " + r);
            } catch (Throwable t) {
                rep.exception("ReportUploader.upload", t);
                rep.line("REPORT UPLOAD", "Upload FAILED: " + t);
            }
        }});
    }

    private String appVersion() {
        try {
            return getPackageName() + " v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private void fullReport() {
        runProbe("FULL REPORT", new Runnable() { public void run() {
            Report rep = Report.get();
            say("Device info…");
            DeviceInfo.probe(MainActivity.this, rep);
            NetProbe.probe(MainActivity.this, rep);
            MicTester.audioHardwareInfo(MainActivity.this, rep);
            say("Settings & properties…");
            SettingsProbe.probe(MainActivity.this, rep);
            Props.collect(rep);
            say("Packages…");
            ComponentScanner.scan(MainActivity.this, rep);
            BinderProbe.probe(MainActivity.this, rep);
            say("ADB state…");
            AdbProbe.checkAdbState(MainActivity.this, rep);
            safeCommands(rep);
            AdbProbe.adbListening(rep);
            rep.line("CONCLUSIONS", "Full report generated. Export via COPY REPORT / SAVE REPORT.");
        }});
    }
}
