package com.discordtowny.discord;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Utilidad para sanitizar mensajes y trazas de excepcion antes de enviarlos al log.
 * Garantiza que el token del bot nunca se filtre (P7 de la constitucion).
 */
final class DiscordSanitizer {

    private DiscordSanitizer() {}

    /** Reemplaza el token por una mascara segura si aparece en el texto. */
    static String sanitize(String message, String token) {
        if (message == null) {
            return "error desconocido";
        }
        if (token != null && !token.isBlank() && message.contains(token)) {
            return message.replace(token, "[TOKEN_OCULTO]");
        }
        return message;
    }

    /** Convierte una excepcion en traza de texto y oculta el token si aparece. */
    static String sanitizeThrowable(Throwable t, String token) {
        if (t == null) {
            return "error desconocido";
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sanitize(sw.toString(), token);
    }
}
