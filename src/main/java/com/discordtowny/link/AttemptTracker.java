package com.discordtowny.link;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Control de intentos fallidos de canje por usuario de Discord.
 *
 * <p>Evita ataques de fuerza bruta sobre el espacio de codigos. Tras
 * alcanzar el limite de intentos fallidos dentro de la ventana temporal
 * configurada, el usuario queda bloqueado durante la duracion del bloqueo.
 *
 * <p>Nota sobre seguridad y riesgo residual de fuerza bruta distribuida (hallazgo 13):
 * El alfabeto de codigos utiliza 31 simbolos alfanumericos no ambiguos (excluyendo
 * 0, O, o, 1, I, i, l, L). Con codigos de longitud 6, el espacio total es de
 * 31^6 = 887.503.681 combinaciones (aproximadamente 29,73 bits de entropia).
 * Un limite individual de 5 intentos por usuario confiere una probabilidad de exito
 * de solo 5 / 887.503.681 ≈ 5,63 x 10^-9 contra un codigo vivo especifico.
 * Sin embargo, ante un atacante con capacidad de coordinar multiples cuentas de Discord
 * (por ejemplo 1.000 cuentas de Discord), el presupuesto agregado se eleva
 * a 5.000 intentos y la probabilidad asciende a ≈ 5,63 x 10^-6 contra un codigo
 * concreto (y crece aproximadamente con M codigos activos concurrentes).
 * Esta implementacion protege estrictamente el presupuesto por cuenta individual
 * durante la ventana temporal. Si se requiere mitigar ataques distribuidos masivos
 * entre multiples cuentas, debe someterse a aprobacion del arquitecto una defensa
 * agregada global por ventana.
 */
final class AttemptTracker {

    private static final class UserAttempts {
        final List<Instant> failureTimestamps = new ArrayList<>();
        Instant lockedUntil;
    }

    private final ConcurrentHashMap<String, UserAttempts> attempts = new ConcurrentHashMap<>();

    /**
     * Comprueba si el usuario de Discord esta bloqueado en este instante.
     */
    boolean isLocked(String discordId, Instant now) {
        if (discordId == null) {
            return false;
        }
        UserAttempts user = attempts.get(discordId);
        if (user == null) {
            return false;
        }
        synchronized (user) {
            if (user.lockedUntil != null) {
                if (now.isBefore(user.lockedUntil)) {
                    return true;
                }
                // El bloqueo expiro: se levanta el bloqueo y se limpian marcas previas
                user.lockedUntil = null;
                user.failureTimestamps.clear();
            }
            return false;
        }
    }

    /**
     * Registra un intento fallido y devuelve cierto si el usuario queda bloqueado.
     * Mantiene las marcas de fallo dentro de la ventana temporal de lockoutDuration
     * pase lo que pase, sin ser reiniciadas por vinculaciones o desvinculaciones.
     */
    boolean recordFailure(String discordId, Instant now, int maxAttempts, Duration lockoutDuration) {
        if (discordId == null) {
            return false;
        }
        UserAttempts user = attempts.computeIfAbsent(discordId, k -> new UserAttempts());
        synchronized (user) {
            if (user.lockedUntil != null && now.isBefore(user.lockedUntil)) {
                return true;
            }

            // Purgar marcas anteriores a la ventana temporal (now - lockoutDuration)
            Instant windowStart = now.minus(lockoutDuration);
            user.failureTimestamps.removeIf(t -> t.isBefore(windowStart));

            user.failureTimestamps.add(now);

            if (user.failureTimestamps.size() >= maxAttempts) {
                user.lockedUntil = now.plus(lockoutDuration);
                return true;
            }
            return false;
        }
    }

    /**
     * Ya no borra el presupuesto de fallos en canjes exitosos.
     * Se preserva el metodo sin efecto para garantizar que el presupuesto de fallos
     * se mantenga durante su ventana temporal pase lo que pase.
     */
    void clear(String discordId) {
        // Intencionalmente vacio: el presupuesto de fallos debe mantenerse
        // durante la ventana temporal independientemente de vinculaciones exitosas.
    }
}
