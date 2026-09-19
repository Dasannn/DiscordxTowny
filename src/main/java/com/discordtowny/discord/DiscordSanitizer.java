package com.discordtowny.discord;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Utility to sanitize messages and exception traces before sending them to the log.
 * Guarantees that the bot token is never leaked (P7 of the constitution).
 */
final class DiscordSanitizer {

    private DiscordSanitizer() {}

    /** Replaces the token with a secure mask if it appears in the text. */
    static String sanitize(String message, String token) {
        if (message == null) {
            return "unknown error";
        }
        if (token != null && !token.isBlank() && message.contains(token)) {
            return message.replace(token, "[TOKEN_OCULTO]");
        }
        return message;
    }

    /** Converts an exception to a text stack trace and masks the token if it appears. */
    static String sanitizeThrowable(Throwable t, String token) {
        if (t == null) {
            return "unknown error";
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sanitize(sw.toString(), token);
    }
}
