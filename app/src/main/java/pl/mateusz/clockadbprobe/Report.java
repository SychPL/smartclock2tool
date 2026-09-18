package pl.mateusz.clockadbprobe;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Central report collector. Every line is timestamped. */
public final class Report {
    private static final Report INSTANCE = new Report();

    public static Report get() { return INSTANCE; }
    private Report() {}

    public static final String[] SECTIONS = {
            "DEVICE", "BUILD", "ANDROID", "SECURITY", "NETWORK", "AUDIO",
            "DEVELOPER SETTINGS", "ADB", "SYSTEM PROPERTIES", "COMMAND RESULTS",
            "INSTALLED PACKAGES OF INTEREST", "EXPORTED ACTIVITIES",
            "EXPORTED SERVICES", "EXPORTED RECEIVERS", "EXPORTED PROVIDERS",
            "SYSTEM / BINDER SERVICES", "VENDOR / LENOVO / MTK FINDINGS",
            "ADB TCP ATTEMPTS", "MICROPHONE TEST", "SPEAKER TEST",
            "REPORT UPLOAD", "ASSISTANT FUZZ",
            "EXCEPTIONS", "CONCLUSIONS"
    };

    private final Map<String, StringBuilder> sections = new LinkedHashMap<>();
    private final List<String> exceptions = new ArrayList<>();
    /** Set by MainActivity for renderers that run without an Activity context. */
    public static volatile String APP_VERSION = "?";

    public String renderAppVersion() { return APP_VERSION; }

    static {
        for (String s : SECTIONS) get().sections.put(s, new StringBuilder());
    }

    public static String ts() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date());
    }

    public synchronized void log(String section, String line) {
        StringBuilder sb = sections.get(section);
        if (sb == null) sb = sections.get("EXCEPTIONS");
        sb.append('[').append(ts()).append("] ").append(line).append('\n');
    }

    public synchronized void line(String section, String line) {
        sections.get(section).append(line).append('\n');
    }

    public synchronized void exception(String where, Throwable t) {
        String msg = "[" + ts() + "] EXCEPTION in " + where + ": " + t;
        exceptions.add(msg);
    }

    public synchronized boolean hasExceptions() { return !exceptions.isEmpty(); }

    public synchronized String render(String appVersion) {
        StringBuilder out = new StringBuilder();
        out.append("SMART CLOCK 2 TOOLS REPORT\n");
        out.append("APP VERSION: ").append(appVersion).append('\n');
        out.append("TIMESTAMP: ")
           .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append('\n');
        for (String s : SECTIONS) {
            out.append("\n=== ").append(s).append(" ===\n");
            StringBuilder sb = sections.get(s);
            if (sb.length() == 0) out.append("(no data collected yet)\n");
            else out.append(sb);
        }
        return out.toString();
    }
}
