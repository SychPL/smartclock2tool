package pl.mateusz.clockadbprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Starts the agent keep-alive service after boot so the LAN agent
 *  is reachable without manually opening the app. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!"android.intent.action.BOOT_COMPLETED".equals(action)) return;
        Intent svc = new Intent(context, AgentKeepAliveService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
