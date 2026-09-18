package pl.mateusz.clockadbprobe;

import java.security.SecureRandom;
import java.util.Locale;

/** Generates and validates the app-private authentication token for the LAN agent. */
final class AgentToken {
    private static final int TOKEN_BYTES = 24;

    private AgentToken() {}

    static String generate(SecureRandom random) {
        if (random == null) throw new IllegalArgumentException("random is required");
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        StringBuilder token = new StringBuilder(TOKEN_BYTES * 2);
        for (byte value : bytes) {
            token.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return token.toString();
    }

    static boolean isValid(String value) {
        return value != null && value.matches("[0-9a-f]{48}");
    }
}
