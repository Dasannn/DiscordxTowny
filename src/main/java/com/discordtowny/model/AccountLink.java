package com.discordtowny.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Verified link between a Minecraft account and a Discord account.
 *
 * <p>One-to-one relationship in both directions. {@code lastKnownName} is for
 * display only: identifying by player name is an error, names change.
 */
public record AccountLink(UUID uuid, String discordId, Instant linkedAt, String lastKnownName) {}
