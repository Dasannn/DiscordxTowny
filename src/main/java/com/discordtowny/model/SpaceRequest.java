package com.discordtowny.model;

import java.util.UUID;

/**
 * What is needed to know to create a town's space, already read from Towny.
 *
 * <p>Constructed on the main thread and dispatched to the pool. Residents are
 * already filtered to those with a linked account: anyone who has not linked receives no role.
 */
public record SpaceRequest(
        UUID townUuid,
        String townName,
        UUID mayorUuid,
        java.util.List<String> linkedResidentDiscordIds,
        String mayorDiscordId) {}
