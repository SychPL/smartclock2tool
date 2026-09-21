package pl.mateusz.clockadbprobe;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

/**
 * One-time-enabled accessibility helper, in two roles:
 *   - when ARMED (agent: taparm) it clicks the confirm button of the system
 *     install/update dialog, so an install can be driven without hands;
 *   - it offers performGlobalAction() to the floating nav overlay (back/home).
 */
public class AutoTapService extends AccessibilityService {

    public static volatile boolean armed = false;
    /** Set by onServiceConnected — proves the system actually bound the service. */
    public static volatile boolean connected = false;
    /** Live instance for the floating nav overlay (global actions). */
    public static volatile AutoTapService instance;

    private static final String[] INSTALLERS = {
            "com.android.packageinstaller", "com.google.android.packageinstaller"
    };
    private static final String[] CONFIRM_TEXTS = {
            "install", "zainstaluj", "zaktualizuj", "update", "installieren"
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        connected = true;
        Report.get().log("COMMAND RESULTS", "AUTOTAP service connected (system bound)");
        AgentRuntime.ensureResident(getApplicationContext());
    }

    @Override
    public void onDestroy() {
        instance = null;
        connected = false;
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        connected = false;
        return super.onUnbind(intent);
    }

    /** Used by the overlay: performs a global accessibility action if bound. */
    public boolean doGlobalAction(int action) {
        try {
            return performGlobalAction(action);
        } catch (Throwable t) {
            Report.get().exception("AutoTap.doGlobalAction", t);
            return false;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !armed) return;
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        boolean isInstaller = false;
        for (String i : INSTALLERS) {
            if (i.equals(pkg)) { isInstaller = true; break; }
        }
        if (!isInstaller) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (clickButton(root)) {
                Report.get().log("COMMAND RESULTS", "AUTOTAP: clicked confirm in " + pkg);
            }
        } finally {
            root.recycle();
        }
    }

    private boolean clickButton(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence txt = node.getText();
        if (txt != null && node.isClickable()) {
            String t = txt.toString().trim().toLowerCase(Locale.ROOT);
            for (String c : CONFIRM_TEXTS) {
                if (t.equals(c) || t.startsWith(c + " ")) {
                    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            boolean clicked;
            try {
                clicked = clickButton(child);
            } finally {
                child.recycle();
            }
            if (clicked) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        // nothing to unwind: the service holds no state beyond `instance`
    }
}
