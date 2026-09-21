package pl.mateusz.clockadbprobe.bridge;

import java.util.regex.Pattern;

/**
 * The one sentence a caller gets to show a human (SPEC 0.12 pkt 7.5).
 *
 * <p>Chain logs and exception messages are useful and also full of things that must not leave this app: tokens,
 * private paths, long hex blobs. Everything that reaches {@code detail} goes through here first, so the rule is a
 * piece of code rather than a promise.
 */
public final class Detail {
    private static final int MAX = 160;

    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_\\-]{4,}(\\.[A-Za-z0-9_\\-]+){0,2}");
    private static final Pattern LONG_HEX = Pattern.compile("(?i)\\b[0-9a-f]{16,}\\b");
    private static final Pattern PRIVATE_PATH = Pattern.compile("(/data|/storage|/sdcard|/proc)/\\S*");
    private static final Pattern TOKEN_ASSIGNMENT = Pattern.compile("(?i)\\b(token|secret|password|key)\\s*[=:]\\s*\\S+");

    private Detail() {}

    public static String clean(String text) {
        if (text == null) return "";
        String out = text.replace('\n', ' ').replace('\r', ' ');
        out = TOKEN_ASSIGNMENT.matcher(out).replaceAll("$1 [hidden]");
        out = JWT.matcher(out).replaceAll("[hidden]");
        out = LONG_HEX.matcher(out).replaceAll("[hidden]");
        out = PRIVATE_PATH.matcher(out).replaceAll("[path]");
        out = out.replaceAll("\\s+", " ").trim();
        if (out.length() > MAX) out = out.substring(0, MAX - 3).trim() + "...";
        return out;
    }
}
