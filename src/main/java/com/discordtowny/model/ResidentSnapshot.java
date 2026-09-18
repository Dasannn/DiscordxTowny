package com.discordtowny.model;

import java.util.Optional;
import java.util.UUID;

/** Lectura inmutable de un residente, tomada de Towny en el hilo principal. */
public record ResidentSnapshot(
        UUID uuid,
        String name,
        Optional<String> townName,
        Optional<UUID> townUuid,
        boolean mayor,
        boolean online,
        long lastOnlineMillis,
        double balance) {}
