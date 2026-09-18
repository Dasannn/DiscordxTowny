package com.discordtowny.model;

import java.util.UUID;

/**
 * Lo que hace falta saber para crear el espacio de una town, ya leido de Towny.
 *
 * <p>Se construye en el hilo principal y viaja al pool. Los residentes ya vienen
 * filtrados a los que tienen cuenta vinculada: quien no vincula no recibe rol.
 */
public record SpaceRequest(
        UUID townUuid,
        String townName,
        UUID mayorUuid,
        java.util.List<String> linkedResidentDiscordIds,
        String mayorDiscordId) {}
