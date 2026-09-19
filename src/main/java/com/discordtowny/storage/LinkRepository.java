package com.discordtowny.storage;

import com.discordtowny.model.AccountLink;
import com.discordtowny.model.LinkCode;
import java.util.Optional;
import java.util.UUID;

/** Verified links and pending codes. Blocks: see {@link Storage}. */
public interface LinkRepository {

    Optional<AccountLink> findByUuid(UUID uuid);

    Optional<AccountLink> findByDiscordId(String discordId);

    /**
     * Saves the link.
     *
     * @throws StorageException if the UUID or Discord ID is already linked.
     *     Uniqueness is guaranteed in the schema, not merely by checking beforehand:
     *     two simultaneous requests would pass the check at the same time.
     */
    void save(AccountLink link);

    /** @return true if there was something to delete. */
    boolean deleteByUuid(UUID uuid);

    /**
     * Deletes the link only if it is still exactly the one that was read.
     *
     * <p>Checking before and deleting after is not enough: between the two, the
     * link could have been broken and recreated, and then one different from the
     * authorized one would be deleted. The condition travels inside the DELETE itself.
     *
     * @return true if deleted; false if it no longer matches or does not exist
     */
    boolean deleteByUuidIfMatches(UUID uuid, String discordId, java.time.Instant linkedAt);

    /** Replaces any active code for the player with this one. */
    void saveCode(LinkCode code);

    Optional<LinkCode> findCode(String code);

    void deleteCode(String code);

    /** Adds one to the failed attempts and returns the total. */
    int incrementAttempts(String code);

    /**
     * Purges expired codes as of the given timestamp.
     *
     * <p>The instant is supplied, not queried here: if the repository read
     * the system clock on its own, its notion of "now" would differ from that
     * of the calling service, and there would be no way to test it with a
     * controlled clock.
     */
    int purgeExpiredCodes(java.time.Instant now);

    /**
     * Consumes the code and creates the link in a single transaction.
     *
     * <p>Exists because doing it in three separate steps (read the code, insert
     * the link, delete the code) leaves gaps that cannot be closed from above:
     * two concurrent redemptions read the same active code and both finish
     * successfully, and a failure when deleting leaves an already used code
     * that remains redeemable.
     *
     * <p>The code is claimed by deleting it: if the deletion affects zero rows,
     * someone else consumed it first. If the link insertion fails, the
     * transaction is rolled back and the code becomes available again, so that
     * a uniqueness conflict does not burn the player's code.
     *
     * <p>The player's UUID is taken from the code's row within the same
     * transaction, never from a prior read.
     *
     * @param now instant against which expiration is evaluated
     */
    ConsumeOutcome consumeCodeAndLink(String code, String discordId, String lastKnownName,
                                      java.time.Instant now);

    /**
     * Refreshes the display name stored with a link.
     *
     * <p>{@code lastKnownName} is captured when the code is generated and is
     * only ever shown, never matched against: a player who changes their name
     * would otherwise be listed under the old one forever. Synchronization
     * refreshes it when the player joins.
     *
     * <p>Does nothing when there is no link for that player.
     */
    void updateLastKnownName(UUID uuid, String lastKnownName);

    /** Result of {@link #consumeCodeAndLink}. */
    record ConsumeOutcome(ConsumeResult result, Optional<AccountLink> link) {

        public static ConsumeOutcome of(ConsumeResult result) {
            return new ConsumeOutcome(result, Optional.empty());
        }
    }

    enum ConsumeResult {
        OK,
        /** Does not exist, or someone consumed it first. */
        CODE_NOT_FOUND,
        CODE_EXPIRED,
        /** That player already has a link. */
        PLAYER_ALREADY_LINKED,
        /** That Discord account is already linked to another player. */
        DISCORD_ALREADY_LINKED
    }

    /** How many links exist. For {@code /dt admin list}. */
    int count();
}
