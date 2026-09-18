package com.discordtowny.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lectura inmutable de una town, tomada de Towny en el hilo principal.
 *
 * <p>Existe para que el resto del plugin no toque la API de Towny fuera del
 * hilo principal: el listener captura esto y lo pasa al pool. Es una foto, no
 * una vista viva; no se cachea entre operaciones.
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
