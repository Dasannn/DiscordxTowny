package com.discordtowny.sync;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reconciliation between Towny and Discord.
 *
 * <p><b>In case of discrepancy, Towny wins.</b> If someone was manually given
 * the role of a town they do not belong to, synchronization removes it.
 *
 * <p>Only roles managed by the plugin are touched. Other roles of a Discord
 * user are neither inspected nor modified.
 */
public interface SyncService {

    /** Adjusts a player's roles to what Towny says. */
    CompletableFuture<Void> syncPlayer(UUID uuid);

    /** Adjusts the roles of all residents of a town. */
    CompletableFuture<SyncReport> syncTown(UUID townUuid);

    /**
     * Walks everything: missing channels, deleted roles, registered spaces
     * without channels, members with roles they should not have, and
     * inconsistent spaces.
     *
     * <p>Runs in batches with pauses, so as not to saturate rate limits.
     * Depending on configuration, repairs or only reports.
     */
    CompletableFuture<SyncReport> reconcileAll();

    /** What was found and what was done. */
    record SyncReport(
            int spacesChecked,
            int rolesGranted,
            int rolesRevoked,
            int inconsistenciesFound,
            int inconsistenciesRepaired,
            java.util.List<String> problems) {}
}
