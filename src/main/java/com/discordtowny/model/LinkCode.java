package com.discordtowny.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Codigo de un solo uso para verificar una vinculacion.
 *
 * <p>Se genera en el juego y se consume en Discord. Un jugador tiene como mucho
 * un codigo vivo: generar uno nuevo invalida el anterior.
 */
public record LinkCode(String code, UUID uuid, Instant expiresAt, int attempts) {

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
