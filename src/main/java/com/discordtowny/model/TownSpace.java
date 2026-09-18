package com.discordtowny.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Espacio de Discord de una town.
 *
 * <p>Se identifica por el UUID de la town, nunca por su nombre: los nombres
 * cambian y romperian la correspondencia. {@code townName} es el ultimo nombre
 * conocido, sirve para mostrar y para detectar renombrados.
 *
 * <p>Los identificadores de Discord son opcionales porque una creacion puede
 * estar a medias: cada uno se persiste en cuanto existe.
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

    /** Cierto si todo lo que la configuracion pide crear ya existe. */
    public boolean isComplete(boolean wantsText, boolean wantsVoice) {
        return roleId.isPresent()
                && (!wantsText || textChannelId.isPresent())
                && (!wantsVoice || voiceChannelId.isPresent());
    }
}
