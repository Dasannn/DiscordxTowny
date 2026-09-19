package com.discordtowny.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable snapshot of a town, taken from Towny on the main thread.
 *
 * <p>Exists so the rest of the plugin does not touch the Towny API off the
 * main thread: the listener captures this and passes it to the pool. It is a
 * snapshot, not a live view; it is not cached between operations.
 */
public record TownSnapshot(
        UUID uuid,
        String name,
        UUID mayorUuid,
        List<UUID> residentUuids,
        boolean ruined,
        Optional<String> nationName,
        int townBlocks,
        double bankBalance,
        long registeredMillis) {

    public int residentCount() {
        return residentUuids.size();
    }

    public boolean isMayor(UUID candidate) {
        return mayorUuid.equals(candidate);
    }
}
