package com.discordtowny.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Single-use code to verify a link.
 *
 * <p>Generated in-game and consumed on Discord. A player has at most
 * one active code: generating a new one invalidates the previous one.
 */
public record LinkCode(String code, UUID uuid, Instant expiresAt, int attempts) {

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
