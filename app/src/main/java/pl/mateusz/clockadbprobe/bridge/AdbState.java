package pl.mateusz.clockadbprobe.bridge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether ADB is actually listening, and who is holding the microphone (SPEC 0.12 pkt 3, 5.1, 5.3).
 *
 * <p>The property that configures the TCP port is not evidence: the daemon reads it when it starts, so the two
 * disagree until it restarts. Every judgement here is made from a connection attempt or from a privileged read,
 * never from configuration.
 */
public final class AdbState {
    public static final int PORT = 5555;

    private AdbState() {}

    /** A connection attempt to the loopback address; short timeout, because an answer is either there or not. */
    public static boolean listening() {
        return listening(PORT, 300);
    }

    static boolean listening(int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * What an attempt to turn ADB off amounts to. The script's own words do not decide: only a port that stopped
     * answering does, because on this platform a zeroed port can still end up with a default TCP listener.
     */
    public static String offResult(String scriptOutput, boolean stillListening) {
        return stillListening ? "failed" : "ok";
    }

    /** Same rule the other way round: ADB is on when something answers, whatever the property says. */
    public static String onResult(String scriptOutput, boolean listening) {
        return listening ? "ok" : "failed";
    }

    private static final Pattern RECORDER = Pattern.compile("pack:([A-Za-z0-9_.]+)");

    /**
     * The packages recording audio right now, parsed from a privileged dump. Callers must treat a null return as
     * "unknown": an unprivileged reader is given an anonymised list, and an empty list would be a lie.
     */
    public static List<String> micHolders(String dumpsysAudio, List<String> watched) {
        if (dumpsysAudio == null || dumpsysAudio.isEmpty()) return null;
        List<String> found = new ArrayList<>();
        for (String line : dumpsysAudio.split("\n")) {
            if (!line.contains("session:") || !line.contains("source client=")) continue;
            Matcher matcher = RECORDER.matcher(line);
            while (matcher.find()) {
                String pkg = matcher.group(1);
                if (watched.contains(pkg) && !found.contains(pkg)) found.add(pkg);
            }
        }
        return found;
    }
}
