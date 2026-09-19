package com.discordtowny.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Discord space of a town.
 *
 * <p>Identified by the town's UUID, never by its name: names change and would
 * break mapping. {@code townName} is the last known name, used for display and
 * for detecting renames.
 *
 * <p>Discord identifiers are optional because a creation may be midway
 * through: each one is persisted as soon as it exists.
 */
public record TownSpace(
        UUID townUuid,
        String townName,
        Optional<String> categoryId,
        Optional<String> textChannelId,
        Optional<String> voiceChannelId,
        Optional<String> roleId,
        SpaceState state,
        Instant createdAt,
        Optional<Instant> archivedAt,
        Optional<Instant> lastActivityAt) {

    /** True if everything the configuration requests to create already exists. */
    public boolean isComplete(boolean wantsText, boolean wantsVoice) {
        return roleId.isPresent()
                && (!wantsText || textChannelId.isPresent())
                && (!wantsVoice || voiceChannelId.isPresent());
    }
}
