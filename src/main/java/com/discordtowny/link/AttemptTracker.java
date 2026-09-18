package com.discordtowny.link;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Control de intentos fallidos de canje por usuario de Discord.
 *
 * <p>Evita ataques de fuerza bruta sobre el espacio de codigos. Tras
 * alcanzar el limite de intentos fallidos, el usuario queda bloqueado
 * temporalmente durante la duracion configurada.
 */
final class AttemptTracker {

    private final ConcurrentHashMap<String, UserAttempts> attempts = new ConcurrentHashMap<>();

    record UserAttempts(int failures, Instant lockedUntil) {}

    /**
     * Comprueba si el usuario de Discord esta bloqueado en este instante.
     */
    boolean isLocked(String discordId, Instant now) {
        if (discordId == null) return false;
        UserAttempts current = attempts.get(discordId);
        if (current == null) {
            return false;
        }
        if (current.lockedUntil() != null) {
            if (now.isBefore(current.lockedUntil())) {
                return true;
            }
            // El bloqueo ya expiro: limpiamos el contador del usuario
            attempts.remove(discordId);
        }
        return false;
    }

    /**
     * Registra un intento fallido y devuelve cierto si el usuario queda bloqueado.
     */
    boolean recordFailure(String discordId, Instant now, int maxAttempts, Duration lockoutDuration) {
        if (discordId == null) return false;
        UserAttempts updated = attempts.compute(discordId, (id, current) -> {
            if (current == null || (current.lockedUntil() != null && !now.isBefore(current.lockedUntil()))) {
                int newFailures = 1;
                Instant lockedUntil = (newFailures >= maxAttempts) ? now.plus(lockoutDuration) : null;
                return new UserAttempts(newFailures, lockedUntil);
            }
            int newFailures = current.failures() + 1;
            Instant lockedUntil = (newFailures >= maxAttempts) ? now.plus(lockoutDuration) : current.lockedUntil();
            return new UserAttempts(newFailures, lockedUntil);
        });
        return updated.lockedUntil() != null && now.isBefore(updated.lockedUntil());
    }

    /**
     * Limpia los intentos fallidos al completar una vinculacion con exito.
     */
    void clear(String discordId) {
        if (discordId != null) {
            attempts.remove(discordId);
        }
    }
}
