package pl.mateusz.clockadbprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * Floating navigation bar for a device without system Back/Home controls:
 * draggable edge bar with Back / Home / collapse. Back and Home use the
 * accessibility global actions when the AutoTap service is connected; Home
 * falls back to a launcher intent so it works even without accessibility.
 */
public class OverlayService extends Service {

    public static volatile boolean running = false;
    static volatile OverlayService instance;

    private WindowManager wm;
    private View bar;
    private View dot;
    private int barX = 0, barY = 400; // offset from default gravity anchor

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startForeground(4321, buildNotification());
        // targetSdk 27 keeps foreground priority after removing the notification,
        // so the overlay leaves no visible trace in the status area.
        stopForeground(STOP_FOREGROUND_REMOVE);
        showBar();
        Report.get().log("COMMAND RESULTS", "OVERLAY: floating nav started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeViews();
        running = false;
        instance = null;
        Report.get().log("COMMAND RESULTS", "OVERLAY: floating nav stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static void toggle(Context ctx) {
        if (running) {
            ctx.stopService(new Intent(ctx, OverlayService.class));
        } else {
            if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(ctx)) {
                try {
                    ctx.startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + ctx.getPackageName())));
                } catch (Throwable t) {
                    Report.get().exception("OverlayService.permIntent", t);
                }
                Toast.makeText(ctx, "Enable the overlay permission for this app",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(new Intent(ctx, OverlayService.class));
            } else {
                ctx.startService(new Intent(ctx, OverlayService.class));
            }
        }
    }

    // ------------------------------------------------------------- views

    private void showBar() {
        removeViews();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xEE202020);

        Button handle = mkButton("≡", 44);
        Button back = mkButton("◀", 56);
        Button home = mkButton("⌂", 56);
        Button hide = mkButton("×", 44);

        handle.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    barX += (int) e.getX();
                    barY += (int) e.getY();
                    updateBarPosition();
                }
                return true;
            }
        });
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { goBack(); }
        });
        home.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { goHome(); }
        });
        hide.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { collapseToDot(); }
        });

        root.addView(handle);
        root.addView(back);
        root.addView(home);
        root.addView(hide);
        bar = root;

        WindowManager.LayoutParams lp = barParams();
        try { wm.addView(bar, lp); } catch (Throwable t) {
            Report.get().exception("OverlayService.showBar", t);
        }
    }

    private WindowManager.LayoutParams barParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = barX;
        lp.y = barY;
        return lp;
    }

    private void updateBarPosition() {
        if (bar == null) return;
        try {
            WindowManager.LayoutParams lp = barParams();
            wm.updateViewLayout(bar, lp);
        } catch (Throwable ignored) {}
    }

    private void collapseToDot() {
        removeBar();
        Button d = mkButton("◉", 48);
        d.setBackgroundColor(0x88303030);
        d.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showBar();
                removeDot();
            }
        });
        // Long-press on the dot stops the service completely: bar, dot and
        // notification all disappear — no trace left on the screen.
        d.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                stopSelf();
                return true;
            }
        });
        dot = d;
        WindowManager.LayoutParams lp = barParams();
        try { wm.addView(dot, lp); } catch (Throwable t) {
            Report.get().exception("OverlayService.dot", t);
        }
    }

    private Button mkButton(String label, int sizeDp) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(sizeDp >= 56 ? 20 : 16);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0x00000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(sizeDp), dp(sizeDp));
        lp.setMargins(2, 2, 2, 2);
        b.setLayoutParams(lp);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void goBack() {
        AutoTapService a = AutoTapService.instance;
        if (a != null) {
            a.doGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        } else {
            Toast.makeText(this, "Back needs the Accessibility service (AutoTap) enabled",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void goHome() {
        AutoTapService a = AutoTapService.instance;
        if (a != null) {
            a.doGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            return;
        }
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(home); } catch (Throwable t) {
            Report.get().exception("OverlayService.goHome", t);
        }
    }

    private void removeBar() {
        if (bar != null) { try { wm.removeView(bar); } catch (Throwable ignored) {} bar = null; }
    }

    private void removeDot() {
        if (dot != null) { try { wm.removeView(dot); } catch (Throwable ignored) {} dot = null; }
    }

    private void removeViews() {
        removeBar();
        removeDot();
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("float_nav", "Floating nav",
                    NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
            return new Notification.Builder(this, "float_nav").build();
        }
        return new Notification.Builder(this).build();
    }
}
