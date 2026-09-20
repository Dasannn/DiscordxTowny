package com.discordtowny.sync;

import com.discordtowny.config.PluginConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    public static final class SyncReport {
        private final int spacesChecked;
        private final int rolesGranted;
        private final int rolesRevoked;
        private final int inconsistenciesFound;
        private final int inconsistenciesRepaired;
        private final List<Problem> problemDetails;
        private final PluginConfig.Sync.Mode mode;
        private final int proposedRolesGranted;
        private final int proposedRolesRevoked;

        /**
         * Structured representation of a problem discovered during synchronization,
         * carrying the message catalog key and its placeholders.
         */
        public record Problem(
                String key,
                Map<String, String> placeholders) {

            public Problem {
                key = Objects.requireNonNull(key, "key cannot be null");
                placeholders = placeholders != null ? Map.copyOf(placeholders) : Map.of();
            }

            public Problem(String key) {
                this(key, Map.of());
            }

            /**
             * Renders this problem into a localized string using the provided messages catalog.
             */
            public String render(com.discordtowny.config.Messages messages) {
                Objects.requireNonNull(messages, "messages cannot be null");
                if (placeholders.isEmpty()) {
                    return messages.label(key, placeholders);
                }
                Map<String, String> resolved = new LinkedHashMap<>(placeholders.size());
                for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                    resolved.put(entry.getKey(), resolvePlaceholder(entry.getValue(), messages));
                }
                return messages.label(key, resolved);
            }

            private static String resolvePlaceholder(String val, com.discordtowny.config.Messages messages) {
                if (val == null || val.isBlank() || val.equals("unknown") || val.equals("general.unknown") || val.equals("sync.cause-unknown")) {
                    return messages.label("general.unknown");
                }
                if (val.equals("ACTIVE") || val.equals("admin.state-active")) {
                    return messages.label("admin.state-active");
                }
                if (val.equals("ARCHIVED") || val.equals("admin.state-archived")) {
                    return messages.label("admin.state-archived");
                }
                if (val.equals("INCONSISTENT") || val.equals("admin.state-inconsistent")) {
                    return messages.label("admin.state-inconsistent");
                }
                if (val.startsWith("sync.cause-") || val.startsWith("sync.problem-")
                        || val.startsWith("general.") || val.startsWith("admin.")
                        || val.startsWith("space.") || val.startsWith("updates.")) {
                    return messages.label(val);
                }
                return val;
            }

            @Override
            public String toString() {
                return key + (placeholders.isEmpty() ? "" : placeholders);
            }
        }

        public SyncReport(
                int spacesChecked,
                int rolesGranted,
                int rolesRevoked,
                int inconsistenciesFound,
                int inconsistenciesRepaired,
                List<Problem> problems,
                PluginConfig.Sync.Mode mode,
                int proposedRolesGranted,
                int proposedRolesRevoked) {
            this.spacesChecked = spacesChecked;
            this.rolesGranted = rolesGranted;
            this.rolesRevoked = rolesRevoked;
            this.inconsistenciesFound = inconsistenciesFound;
            this.inconsistenciesRepaired = inconsistenciesRepaired;
            this.problemDetails = problems != null ? List.copyOf(problems) : List.of();
            this.mode = mode != null ? mode : PluginConfig.Sync.Mode.REPAIR;
            this.proposedRolesGranted = proposedRolesGranted;
            this.proposedRolesRevoked = proposedRolesRevoked;
        }

        /**
         * Constructor for repair mode without proposed changes.
         */
        public SyncReport(
                int spacesChecked,
                int rolesGranted,
                int rolesRevoked,
                int inconsistenciesFound,
                int inconsistenciesRepaired,
                List<Problem> problems) {
            this(spacesChecked, rolesGranted, rolesRevoked, inconsistenciesFound,
                    inconsistenciesRepaired, problems, PluginConfig.Sync.Mode.REPAIR, 0, 0);
        }

        public int spacesChecked() {
            return spacesChecked;
        }

        public int rolesGranted() {
            return rolesGranted;
        }

        public int rolesRevoked() {
            return rolesRevoked;
        }

        public int inconsistenciesFound() {
            return inconsistenciesFound;
        }

        public int inconsistenciesRepaired() {
            return inconsistenciesRepaired;
        }

        public List<Problem> problemDetails() {
            return problemDetails;
        }

        /**
         * Returns the raw message catalog keys of the discovered problems.
         * For localized display text, callers must use {@link #problems(com.discordtowny.config.Messages)}.
         */
        public List<String> problemKeys() {
            if (problemDetails.isEmpty()) {
                return List.of();
            }
            return problemDetails.stream()
                    .map(Problem::key)
                    .toList();
        }

        /**
         * Renders problems as localized strings using the given message catalog.
         */
        public List<String> problems(com.discordtowny.config.Messages messages) {
            Objects.requireNonNull(messages, "messages cannot be null");
            if (problemDetails.isEmpty()) {
                return List.of();
            }
            return problemDetails.stream()
                    .map(p -> p.render(messages))
                    .toList();
        }

        public PluginConfig.Sync.Mode mode() {
            return mode;
        }

        public int proposedRolesGranted() {
            return proposedRolesGranted;
        }

        public int proposedRolesRevoked() {
            return proposedRolesRevoked;
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SyncReport that)) return false;
            return spacesChecked == that.spacesChecked
                    && rolesGranted == that.rolesGranted
                    && rolesRevoked == that.rolesRevoked
                    && inconsistenciesFound == that.inconsistenciesFound
                    && inconsistenciesRepaired == that.inconsistenciesRepaired
                    && proposedRolesGranted == that.proposedRolesGranted
                    && proposedRolesRevoked == that.proposedRolesRevoked
                    && Objects.equals(problemDetails, that.problemDetails)
                    && mode == that.mode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(spacesChecked, rolesGranted, rolesRevoked,
                    inconsistenciesFound, inconsistenciesRepaired, problemDetails,
                    mode, proposedRolesGranted, proposedRolesRevoked);
        }

        @Override
        public String toString() {
            return "SyncReport["
                    + "spacesChecked=" + spacesChecked
                    + ", rolesGranted=" + rolesGranted
                    + ", rolesRevoked=" + rolesRevoked
                    + ", inconsistenciesFound=" + inconsistenciesFound
                    + ", inconsistenciesRepaired=" + inconsistenciesRepaired
                    + ", problems=" + problemDetails
                    + ", mode=" + mode
                    + ", proposedRolesGranted=" + proposedRolesGranted
                    + ", proposedRolesRevoked=" + proposedRolesRevoked
                    + "]";
        }
    }
}
