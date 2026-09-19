package com.discordtowny.link;

import com.discordtowny.model.AccountLink;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Verified linking between a Minecraft account and a Discord account.
 *
 * <p>No permission is granted on an unverified identity: this service is
 * the sole means through which a Discord account becomes associated with a player.
 *
 * <p>Methods return futures because they touch the database. They can be
 * called from the main thread without blocking it.
 */
public interface LinkService {

    /**
     * Generates a code for the player, invalidating the previous one if they had one.
     *
     * @return the code, or empty if the player is already linked
     */
    default CompletableFuture<Optional<String>> generateCode(UUID uuid) {
        return generateCode(uuid, "");
    }

    /**
     * Generates a code for the player saving their last known name,
     * invalidating the previous one if they had one.
     *
     * @param uuid player identifier
     * @param lastKnownName player's last known name for display
     * @return the code, or empty if the player is already linked
     */
    CompletableFuture<Optional<String>> generateCode(UUID uuid, String lastKnownName);

    /** Consumes the code and creates the link. */
    CompletableFuture<LinkResult> redeem(String code, String discordId);

    CompletableFuture<Optional<AccountLink>> findByUuid(UUID uuid);

    CompletableFuture<Optional<AccountLink>> findByDiscordId(String discordId);

    /**
     * Breaks the link and removes all roles granted by the plugin.
     *
     * @return true if there was a link to break
     */
    CompletableFuture<Boolean> unlink(UUID uuid);

    /**
     * Breaks the link conditioned on the expected Discord ID and removes roles.
     *
     * @param uuid player identifier
     * @param expectedDiscordId expected Discord account, or null if unconditioned
     * @return true if there was a matching link to break
     */
    CompletableFuture<Boolean> unlink(UUID uuid, String expectedDiscordId);

    /**
     * Breaks the link conditioned on the expected Discord ID and link timestamp and removes roles.
     *
     * @param uuid player identifier
     * @param expectedDiscordId expected Discord account, or null if unconditioned
     * @param expectedLinkedAt expected link timestamp, or null if unconditioned
     * @return true if there was a matching link to break
     */
    CompletableFuture<Boolean> unlink(UUID uuid, String expectedDiscordId, java.time.Instant expectedLinkedAt);

    /** Result of redeeming a code. */
    enum LinkResult {
        SUCCESS,
        CODE_INVALID,
        CODE_EXPIRED,
        /** That Discord account is already linked to another player. */
        DISCORD_ALREADY_LINKED,
        /** That player already has another Discord account. */
        PLAYER_ALREADY_LINKED,
        /** Too many failed attempts: temporarily locked out. */
        TOO_MANY_ATTEMPTS
    }
}
