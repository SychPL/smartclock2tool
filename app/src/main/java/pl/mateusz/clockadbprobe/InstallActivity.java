package pl.mateusz.clockadbprobe;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * No-cable deployment: downloads an APK over the LAN (plain HTTP) and installs
 * it via PackageInstaller. The on-screen confirmation is clicked by
 * AutoTapService (accessibility, armed remotely), and a self-relaunch alarm
 * brings the app back after the update kills the process.
 */
public class InstallActivity extends Activity {

    static final String DEFAULT_URL = "https://github.com/SychPL/smartclock2tool/releases/latest/download/smartclock2tool-debug.apk";
    private static volatile boolean installPending;

    private EditText urlField;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_install);
        urlField = findViewById(R.id.urlField);
        urlField.setText(DEFAULT_URL);
        status = findViewById(R.id.installStatus);
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        Button go = findViewById(R.id.btnInstall);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startInstall(InstallActivity.this, urlField.getText().toString().trim(), ui); }
        });
        // Fallback if the PackageInstaller flow misbehaves: hand the URL to
        // whatever browser the clock has (the one from the TalkBack trick).
        Button browse = findViewById(R.id.btnBrowse);
        browse.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(urlField.getText().toString().trim())));
                } catch (Throwable t) {
                    Report.get().exception("InstallActivity.browse", t);
                    Toast.makeText(InstallActivity.this, "No browser: " + t, Toast.LENGTH_LONG).show();
                }
            }
        });
        onNewIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !intent.hasExtra(PackageInstaller.EXTRA_STATUS)) return;
        int s = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (s == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The confirm dialog arrives as EXTRA_INTENT — the app itself must
            // launch it, otherwise nothing ever shows on screen.
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(confirm);
                    setStatus("Confirm the installation on screen (system dialog).");
                    Report.get().log("COMMAND RESULTS", "Installer: launched confirmation dialog");
                    return;
                }
                setStatus("Pending user action but no dialog intent received.");
            } catch (Throwable t) {
                Report.get().exception("InstallActivity.confirmDialog", t);
                setStatus("Could not show confirm dialog: " + t);
            }
            return;
        }
        installPending = false;
        setStatus("Install result: status=" + s + (m != null ? " " + m : ""));
        Report.get().log("COMMAND RESULTS", "PackageInstaller: " + s + " " + m);
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() { public void run() { status.setText(s); } });
        Report.get().log("COMMAND RESULTS", "INSTALLER: " + s);
    }

    /** Entry point for both the button and the agent's selfupdate command. */
    static void startInstall(final Context ctx, final String url, final Handler uiOrNull) {
        if (!OperationGate.tryStartProbe()) {
            Report.get().log("COMMAND RESULTS", "INSTALLER: rejected while another operation is active");
            return;
        }
        installPending = true;
        if (Build.VERSION.SDK_INT >= 26
                && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Report.get().log("COMMAND RESULTS", "INSTALLER: needs 'allow from this source' once");
            try {
                ctx.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.getPackageName())));
            } catch (Throwable t) {
                Report.get().exception("startInstall.permIntent", t);
            } finally {
                installPending = false;
                OperationGate.finishProbe();
            }
            return;
        }
        final Handler ui = uiOrNull != null ? uiOrNull : new Handler(Looper.getMainLooper());
        Report.get().log("COMMAND RESULTS", "INSTALLER: downloading " + url);
        Thread installer = new Thread(new Runnable() {
            public void run() {
                boolean committed = false;
                try {
                    File apk = download(ctx, url);
                    scheduleRelaunch(ctx);
                    install(ctx, apk);
                    committed = true;
                    Report.get().log("COMMAND RESULTS", "INSTALLER: downloaded " + apk.length()
                            + " B, committed; awaiting confirm dialog (AutoTap)");
                } catch (final Throwable t) {
                    if (!committed) installPending = false;
                    Report.get().exception("startInstall", t);
                    Report.get().log("COMMAND RESULTS", "INSTALLER FAILED: " + t);
                } finally {
                    OperationGate.finishProbe();
                }
            }
        }, "installer");
        try {
            installer.start();
        } catch (Throwable failure) {
            installPending = false;
            OperationGate.finishProbe();
            Report.get().exception("startInstall.thread", failure);
        }
    }

    static boolean isInstallPending() {
        return installPending;
    }

    private static void scheduleRelaunch(Context ctx) {
        // The update kills our process; an alarm brings MainActivity back so
        // the agent returns without any manual tap.
        Intent i = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 4242, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.set(AlarmManager.RTC, System.currentTimeMillis() + 150_000, pi);
    }

    static File download(Context ctx, String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code + " for " + urlStr);
        File out = new File(ctx.getCacheDir(), "download.apk");
        InputStream is = c.getInputStream();
        OutputStream os = new java.io.FileOutputStream(out);
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        os.close();
        is.close();
        c.disconnect();
        if (out.length() < 10000) throw new IllegalStateException("suspiciously small APK: " + out.length());
        return out;
    }

    @SuppressLint("InlinedApi")
    static void install(Context ctx, File apk) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sid = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(sid);
        OutputStream os = session.openWrite("clock-adb-probe", 0, apk.length());
        FileInputStream is = new FileInputStream(apk);
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
        session.fsync(os);
        os.close();
        is.close();
        Intent result = new Intent(ctx, InstallActivity.class)
                .setAction("pl.mateusz.clockadbprobe.INSTALL_RESULT");
        PendingIntent piResult = PendingIntent.getActivity(ctx, sid, result,
                installerCallbackFlags(Build.VERSION.SDK_INT));
        session.commit(piResult.getIntentSender());
        session.close();
    }

    @SuppressLint("InlinedApi")
    static int installerCallbackFlags(int sdkInt) {
        // PackageInstaller supplies EXTRA_STATUS and EXTRA_INTENT as fill-in
        // data. An immutable PendingIntent silently discards those values.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (sdkInt >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return flags;
    }
}
