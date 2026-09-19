package com.discordtowny.sync;

import com.discordtowny.config.PluginConfig;

import java.util.List;
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

    /**
     * What was found and what was done.
     *
     * <p>Distinguishes confirmed changes applied to Discord from proposed changes
     * discovered in report-only mode.
     */
    record SyncReport(
            int spacesChecked,
            int rolesGranted,
            int rolesRevoked,
            int inconsistenciesFound,
            int inconsistenciesRepaired,
            List<String> problems,
            PluginConfig.Sync.Mode mode,
            int proposedRolesGranted,
            int proposedRolesRevoked) {

        public SyncReport {
            problems = problems != null ? List.copyOf(problems) : List.of();
            mode = mode != null ? mode : PluginConfig.Sync.Mode.REPAIR;
        }

        /**
         * Backward-compatible constructor for repair mode without proposed changes.
         */
        public SyncReport(
                int spacesChecked,
                int rolesGranted,
                int rolesRevoked,
                int inconsistenciesFound,
                int inconsistenciesRepaired,
                List<String> problems) {
            this(spacesChecked, rolesGranted, rolesRevoked, inconsistenciesFound,
                    inconsistenciesRepaired, problems, PluginConfig.Sync.Mode.REPAIR, 0, 0);
        }

        /** True if this report was generated in REPORT mode. */
        public boolean isReportMode() {
            return mode == PluginConfig.Sync.Mode.REPORT;
        }

        /** Alias for {@link #proposedRolesGranted()}. */
        public int proposedGrants() {
            return proposedRolesGranted;
        }

        /** Alias for {@link #proposedRolesRevoked()}. */
        public int proposedRevocations() {
            return proposedRolesRevoked;
        }
    }
}
