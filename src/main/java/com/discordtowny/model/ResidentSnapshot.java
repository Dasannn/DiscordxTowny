package com.discordtowny.model;

import java.util.Optional;
import java.util.UUID;

/** Immutable snapshot of a resident, taken from Towny on the main thread. */
public record ResidentSnapshot(
        UUID uuid,
        String name,
        Optional<String> townName,
        Optional<UUID> townUuid,
        boolean mayor,
        boolean online,
        long lastOnlineMillis,
        double balance) {}
