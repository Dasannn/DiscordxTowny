package com.discordtowny.discord;

import com.discordtowny.model.SpaceRequest;
import java.util.List;
import java.util.UUID;

/**
 * Guild mutation, described as data.
 *
 * <p>Each implementation must be <b>idempotent</b>: before creating anything,
 * it checks whether it already exists by its saved identifier. Retrying a
 * half-finished operation cannot duplicate channels or roles.
 *
 * <p>Sealed on purpose: the queue knows how to handle exactly these cases, and adding
 * a new one forces deciding what to do with it.
 */
public sealed interface GuildOperation {

    /** For logging and error messages. */
    String describe();

    /** Creates whatever is missing from the space: category, role, channels, permissions, roles. */
    record CreateSpace(SpaceRequest request) implements GuildOperation {
        @Override
        public String describe() {
            return "create space for " + request.townName();
        }
    }

    /** Renames channels and role following a town rename. */
    record RenameSpace(UUID townUuid, String oldName, String newName) implements GuildOperation {
        @Override
        public String describe() {
            return "rename " + oldName + " to " + newName;
        }
    }

    /** Channels to read-only, moved to archive, role deleted. */
    record ArchiveSpace(UUID townUuid, String townName) implements GuildOperation {
        @Override
        public String describe() {
            return "archive space for " + townName;
        }
    }

    /** Returns an archived space to active, with its history. */
    record RestoreSpace(SpaceRequest request) implements GuildOperation {
        @Override
        public String describe() {
            return "restore space for " + request.townName();
        }
    }

    /** Permanent deletion. Only ordered by an administrator. */
    record DeleteSpace(UUID townUuid, String townName) implements GuildOperation {
        @Override
        public String describe() {
            return "permanently delete space for " + townName;
        }
    }

    /**
     * Adjusts a member's roles to those they should have.
     *
     * <p>Only roles managed by the plugin are touched: other roles of the
     * user are neither inspected nor modified.
     */
    record ApplyMemberRoles(String discordId, List<String> grantRoleIds, List<String> revokeRoleIds)
            implements GuildOperation {
        @Override
        public String describe() {
            return "adjust roles for " + discordId;
        }
    }
}
