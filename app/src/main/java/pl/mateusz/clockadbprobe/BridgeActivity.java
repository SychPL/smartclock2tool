package pl.mateusz.clockadbprobe;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import pl.mateusz.clockadbprobe.bridge.BridgeExecutor;
import pl.mateusz.clockadbprobe.bridge.BridgeFiles;
import pl.mateusz.clockadbprobe.bridge.BridgeFlow;
import pl.mateusz.clockadbprobe.bridge.BridgeRequest;
import pl.mateusz.clockadbprobe.bridge.CallerFacts;
import pl.mateusz.clockadbprobe.bridge.Detail;
import pl.mateusz.clockadbprobe.bridge.ExecutorLock;
import pl.mateusz.clockadbprobe.bridge.Executions;
import pl.mateusz.clockadbprobe.bridge.OpRegistry;
import pl.mateusz.clockadbprobe.bridge.Ops;
import pl.mateusz.clockadbprobe.bridge.TrustStore;

/**
 * The only way in from another app (SPEC 0.12 pkt 4).
 *
 * <p>Three screens live here: trust ("may this app talk to the bridge at all"), consent ("may it do this"), and
 * progress. Which one appears is decided by {@link BridgeFlow}; this class does the asking, the running and the
 * answering, and nothing else.
 *
 * <p>Launch mode is deliberately {@code standard}: in another task the system would hand the caller an immediate
 * cancellation and {@code getCallingPackage()} would return nothing, which is the whole basis of the identity check.
 */
public final class BridgeActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());

    private BridgeFiles files;
    private OpRegistry registry;
    private TrustStore trust;
    private BridgeExecutor executor;

    private BridgeRequest request;
    private BridgeFlow.Identity identity;
    private OpRegistry.Key key;
    private String execId;
    private boolean answered;
    private boolean running;

    private TextView log;
    private ScrollView logScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        files = BridgeFiles.get(this);
        registry = files.registry();
        registry.recoverOnce();
        trust = files.trust();
        executor = new BridgeExecutor(this, registry, files.micState());

        request = BridgeRequest.parse(inputOf(getIntent(), false));
        identity = identityOf(getCallingPackage());
        android.util.Log.i("HeliosBridge", "caller=" + getCallingPackage() + " op=" + request.op
                + " error=" + request.error);
        if (identity != null) {
            key = new OpRegistry.Key(identity.pkg, identity.fingerprint, request.opId);
            execId = Executions.execId(identity.pkg, identity.fingerprint, request.opId);
        }

        // deciding needs facts about the device, and reading those can block; the UI thread never waits for them
        showChecking();
        new Thread(() -> {
            BridgeFlow.Facts facts = facts();
            ui.post(() -> apply(BridgeFlow.decide(request, identity, trust, registry, facts)));
        }, "bridge-facts").start();
    }

    private void apply(BridgeFlow.Decision decision) {

        switch (decision.kind) {
            case ASK_TRUST:
                askTrust();
                break;
            case ASK_CONSENT:
                accept();
                askConsent();
                break;
            case RUN:
                accept();
                run(decision);
                break;
            default:
                answer(decision.status, decision.detail);
        }
    }

    /** A redelivered intent never takes over a screen that already carries an identity and a consent. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        answer("denied", "this screen already belongs to another request");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (answered) return;
        // the window went away before we answered: a consent screen is a refusal, a progress screen is not
        if (running) {
            setResult(RESULT_CANCELED);
            return;
        }
        if (key != null) registry.finish(key, "denied");
        setResult(RESULT_CANCELED);
    }

    private BridgeFlow.Facts facts() {
        return new BridgeFlow.Facts(BridgeExecutor.firmwareSupported(), executor.chain(), precondition());
    }

    /** Conditions that make an operation impossible here, checked before any consent is asked. */
    private String precondition() {
        if (identity == null || request.error != null) return null;
        PackageManager pm = getPackageManager();
        if (Ops.GRANT_PERMISSION.equals(request.op)) {
            String permission = Ops.consentScope(Ops.GRANT_PERMISSION, request.args);
            if (!Ops.GRANTABLE.contains(permission)) return "unsupported";
            if (!CallerFacts.declaresPermission(pm, identity.pkg, permission)) return "unsupported";
        }
        if (Ops.WRITE_SETTINGS.equals(request.op)
                && !CallerFacts.declaresPermission(pm, identity.pkg, "android.permission.WRITE_SETTINGS")) {
            return "unsupported";
        }
        if (Ops.SET_HOME.equals(request.op) && CallerFacts.homeActivities(pm, identity.pkg) != 1) {
            return "unsupported";
        }
        if ((Ops.ADB_ON.equals(request.op) || Ops.ADB_OFF.equals(request.op))
                && !ExecutorLock.RUNNING.equals(executor.chain()) && !hasRoot()) {
            return "unsupported";
        }
        return null;
    }

    private boolean hasRoot() {
        return executor.snapshot(1500, null, identity == null ? null : identity.pkg).contains("\"root\":true");
    }

    private BridgeFlow.Identity identityOf(String callerPackage) {
        if (callerPackage == null || callerPackage.isEmpty()) return null;
        PackageManager pm = getPackageManager();
        return new BridgeFlow.Identity(callerPackage, CallerFacts.fingerprint(pm, callerPackage),
                CallerFacts.installed(pm, callerPackage));
    }

    private BridgeRequest.Input inputOf(Intent intent, boolean viaNewIntent) {
        BridgeRequest.Input in = new BridgeRequest.Input();
        if (intent == null) return in;
        in.action = intent.getAction();
        in.hasApi = intent.hasExtra("api") && intent.getExtras() != null
                && intent.getExtras().get("api") instanceof Integer;
        in.api = intent.getIntExtra("api", -1);
        in.op = stringExtra(intent, "op");
        in.args = stringExtra(intent, "args");
        in.opId = stringExtra(intent, "op_id");
        in.flags = intent.getFlags() & ~Intent.FLAG_ACTIVITY_FORWARD_RESULT;
        in.forwardResult = (intent.getFlags() & Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0;
        in.hasCaller = getCallingPackage() != null;
        in.viaNewIntent = viaNewIntent;
        in.userId = CallerFacts.ownUserId();
        in.hasUri = intent.getData() != null;
        return in;
    }

    private static String stringExtra(Intent intent, String name) {
        Object value = intent.getExtras() == null ? null : intent.getExtras().get(name);
        return value instanceof String ? (String) value : "";
    }

    private void accept() {
        if (key != null) registry.accept(key, request.op, digest());
    }

    private String digest() {
        return Ops.requestDigest(request.op, request.args, null);
    }

    private void askTrust() {
        String fingerprint = identity == null ? "" : identity.fingerprint;
        ask("Allow " + (identity == null ? "this app" : identity.pkg) + " to use the bridge?",
                "Signature " + shortened(fingerprint) + "\n\n"
                        + "The bridge can grant this app permissions and switch ADB on. Only allow an app you built "
                        + "or installed yourself.",
                () -> {
                    trust.trust(identity.pkg, identity.fingerprint);
                    recreateDecision();
                },
                () -> answer("denied", "the user refused"));
    }

    private void askConsent() {
        ask(consentTitle(), consentBody(), () -> {
            trust.consent(identity.pkg, identity.fingerprint, request.op, Ops.consentScope(request.op, request.args));
            run(BridgeFlow.runNow(identity, trust.revision()));
        }, () -> answer("denied", "the user refused"));
    }

    private void recreateDecision() {
        showChecking();
        new Thread(() -> {
            BridgeFlow.Facts facts = facts();
            ui.post(() -> apply(BridgeFlow.decide(request, identity, trust, registry, facts)));
        }, "bridge-facts").start();
    }

    private String consentTitle() {
        if (Ops.ROOT_ADB_ON.equals(request.op)) return "Root the clock and turn ADB on?";
        if (Ops.ADB_ON.equals(request.op)) return "Turn ADB over Wi-Fi on?";
        if (Ops.ADB_OFF.equals(request.op)) return "Turn ADB over Wi-Fi off?";
        if (Ops.GRANT_PERMISSION.equals(request.op)) return "Grant the microphone permission?";
        if (Ops.WRITE_SETTINGS.equals(request.op)) return "Let this app change system brightness?";
        if (Ops.SET_HOME.equals(request.op)) return "Make this app the home screen?";
        if (Ops.MIC_RELEASE.equals(request.op)) return "Take the microphone from the Google shell?";
        if (Ops.MIC_RESTORE.equals(request.op)) return "Give the microphone back to the Google shell?";
        return "Allow " + request.op + "?";
    }

    private String consentBody() {
        StringBuilder body = new StringBuilder(identity.pkg).append(" is asking.\n\n");
        if (Ops.ROOT_ADB_ON.equals(request.op)) {
            body.append("This runs a kernel exploit. It can hang the clock, and you would have to cut the power.\n\n");
        }
        if (Ops.ROOT_ADB_ON.equals(request.op) || Ops.ADB_ON.equals(request.op)) {
            body.append("With ADB over Wi-Fi on, anyone on your network can reach this clock without a password, "
                    + "and the consents this screen asks for can be bypassed entirely.");
        }
        if (Ops.MIC_RELEASE.equals(request.op)) {
            body.append("The factory shell keeps the microphone open at 48 kHz stereo, which is why every other app "
                    + "gets six times the samples it asked for. "
                    + "\"Hey Google\" stops working on this clock until you give the microphone back.");
        }
        if (Ops.SET_HOME.equals(request.op)) {
            body.append("Keep a second launcher installed. If this app ever fails to start, the home button is how "
                    + "you get back.");
        }
        return body.toString();
    }

    private static String shortened(String fingerprint) {
        return fingerprint.length() <= 16 ? fingerprint : fingerprint.substring(0, 16) + "...";
    }

    private void run(BridgeFlow.Decision decision) {
        BridgeFlow.Decision confirmed = decision.recheck(identityOf(getCallingPackage()), trust.revision());
        if (confirmed.kind != BridgeFlow.Kind.RUN) {
            answer(confirmed.status, confirmed.detail);
            return;
        }
        running = true;
        showProgress();
        new Thread(() -> {
            BridgeExecutor.Result result = execute();
            ui.post(() -> answer(result.status, result.detail));
        }, "bridge-op").start();
    }

    private BridgeExecutor.Result execute() {
        RootKit.Log log = line -> ui.post(() -> appendLog(line));
        if (Ops.ROOT_ADB_ON.equals(request.op)) return executor.rootAndAdb(key, execId, log);
        if (Ops.ADB_ON.equals(request.op)) return executor.adbOn(key, execId, log);
        if (Ops.ADB_OFF.equals(request.op)) return executor.adbOff(key, execId, log);
        if (Ops.GRANT_PERMISSION.equals(request.op)) {
            return executor.grantPermission(key, execId, identity.pkg, Ops.consentScope(Ops.GRANT_PERMISSION, request.args));
        }
        if (Ops.WRITE_SETTINGS.equals(request.op)) return executor.writeSettings(key, execId, identity.pkg);
        if (Ops.SET_HOME.equals(request.op)) return executor.setHome(key, execId, identity.pkg);
        if (Ops.MIC_RELEASE.equals(request.op)) return executor.micRelease(key, execId, files.mic());
        if (Ops.MIC_RESTORE.equals(request.op)) return executor.micRestore(key, execId, files.mic());
        if (Ops.INSTALL_APK.equals(request.op)) return installApk(log);
        return new BridgeExecutor.Result("unsupported", "not implemented yet");
    }


    /**
     * Copies the caller's file first, then decides from the copy (SPEC 0.12 pkt 5.8). Everything the consent screen
     * shows comes from what was copied, never from what the caller claimed.
     */
    private BridgeExecutor.Result installApk(RootKit.Log log) {
        android.net.Uri source = getIntent().getData();
        if (source == null) return new BridgeExecutor.Result("unsupported", "no file");
        java.io.File copy = files.apkFor(execId);
        registry.stage(key, OpRegistry.COPYING);
        BridgeExecutor.Candidate candidate = executor.copyAndInspect(source, copy, identity.pkg, expectedSha());
        if (candidate.error != null) {
            registry.finish(key, candidate.error);
            return new BridgeExecutor.Result(candidate.error, "the file is not an update of this app");
        }
        registry.bindDigest(key, candidate.digest);
        appendLog(candidate.pkg + " " + candidate.versionName + " (" + candidate.versionCode + ")");
        return executor.install(key, execId, copy, identity.pkg, log);
    }

    private String expectedSha() {
        try {
            return request.args.isEmpty() ? "" : new org.json.JSONObject(request.args).optString("expect", "");
        } catch (Exception e) {
            return "";
        }
    }

    private void answer(String status, String detail) {
        android.util.Log.i("HeliosBridge", "answer=" + status + " detail=" + detail);
        if (answered) return;
        answered = true;
        Intent data = new Intent();
        data.putExtra("status", status);
        data.putExtra("detail", Detail.clean(detail));
        data.putExtra("op_id", request.opId);
        OpRegistry.Entry about = key == null ? null : registry.about(key);
        data.putExtra("state", executor.snapshot(3000, about, identity == null ? null : identity.pkg));
        setResult(RESULT_OK, data);
        finish();
    }

    // -- screens ---------------------------------------------------------------------------------------------

    private void ask(String title, String body, Runnable onYes, Runnable onNo) {
        LinearLayout column = column();
        column.addView(text(title, 20, true));
        column.addView(text(body, 15, false));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button no = button("No", v -> onNo.run());
        Button yes = button("Yes", v -> onYes.run());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(56), 1);
        half.rightMargin = dp(8);
        row.addView(no, half);
        row.addView(yes, new LinearLayout.LayoutParams(0, dp(56), 1));
        column.addView(row);
        setContentView(column);
    }

    /** Something has to be on screen while the device is being measured; this is that something. */
    private void showChecking() {
        LinearLayout column = column();
        column.addView(text("Checking the clock...", 18, true));
        setContentView(column);
    }

    private void showProgress() {
        LinearLayout column = column();
        column.addView(text(consentTitle(), 18, true));
        log = text("", 13, false);
        logScroll = new ScrollView(this);
        logScroll.addView(log);
        column.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(column);
    }

    private void appendLog(String line) {
        if (log == null) return;
        log.append(Detail.clean(line) + "\n");
        if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(20), dp(20), dp(20));
        column.setBackgroundColor(Color.parseColor("#101418"));
        return column;
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.parseColor("#F2EFE9"));
        view.setPadding(0, 0, 0, dp(12));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(listener);
        // a consent must never be tapped through an overlay drawn by somebody else
        button.setFilterTouchesWhenObscured(true);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
