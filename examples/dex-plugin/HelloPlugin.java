package pl.mateusz.plugin;

import android.content.Context;
import android.os.Build;

import java.lang.reflect.Method;

/**
 * Minimal plugin for the agent's /agent/dex route.
 *
 * The route downloads a .dex over HTTP, loads it with DexClassLoader (whose
 * parent is the app's own class loader) and calls
 *     public static String run(Context ctx, String arg)
 * on the entry class you name. Whatever it returns is the HTTP response body.
 *
 * That matters because the plugin runs INSIDE the app's process: same uid, same
 * SELinux context, same class loader - so it can call the app's classes, use
 * hidden framework APIs (the app targets SDK 27 on purpose, so reflection is not
 * blocked) and be iterated on without rebuilding or reinstalling the APK
 * (which would disable the accessibility service every time).
 *
 * This example prints a few facts about its own execution context and reads a
 * system property through a hidden API, which is exactly the sort of thing the
 * route is for. Build it with ./build.sh, then:
 *
 *   curl "http://<clock-ip>:8555/agent/dex?token=<token>&url=http://<pc-ip>:8000/HelloPlugin.dex&entry=pl.mateusz.plugin.HelloPlugin&arg=hello"
 */
public final class HelloPlugin {

    private HelloPlugin() {
    }

    public static String run(Context context, String arg) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("arg=").append(arg).append('\n');
        out.append("uid=").append(android.os.Process.myUid()).append('\n');
        out.append("package=").append(context.getPackageName()).append('\n');
        out.append("model=").append(Build.MODEL).append('\n');
        out.append("sdk=").append(Build.VERSION.SDK_INT).append('\n');

        // Hidden API on purpose: android.os.SystemProperties is not in the public
        // SDK, but the app's targetSdk 27 keeps reflection on it allowed.
        Class<?> systemProperties = Class.forName("android.os.SystemProperties");
        Method get = systemProperties.getMethod("get", String.class, String.class);
        out.append("ro.build.display.id=")
           .append(get.invoke(null, "ro.build.display.id", "?"))
           .append('\n');

        return out.toString();
    }
}
