package com.discordtowny.model;

import java.time.Instant;
import java.util.Optional;

/**
 * Algo que el plugin hizo o intento hacer.
 *
 * <p>Se persiste y, en paralelo, se encola hacia el canal de logs de Discord.
 * El detalle NUNCA debe contener el token ni credenciales.
 */
public record AuditEvent(
        Instant at,
        Severity severity,
        String actor,
        String action,
        String target,
        boolean success,
        Optional<String> detail) {

    public enum Severity {
        /** Operacion normal: creaciones, cambios de rol, vinculaciones. */
        INFO,
        /** Algo inesperado que no impide operar: reintento, estado corregido. */
        WARNING,
        /** Operacion fallida. */
        ERROR
    }
}
