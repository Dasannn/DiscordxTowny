package com.discordtowny.model;

import java.util.UUID;

/**
 * What is needed to know to create a town's space, already read from Towny.
 *
 * <p>Constructed on the main thread and dispatched to the pool. Residents are
 * already filtered to those with a linked account: anyone who has not linked receives no role.
 *
 * <p>{@code townyResidentCount} is the town's whole population as Towny reports
 * it, which is what the configured minimum is measured against. It is a separate
 * number from {@code linkedResidentDiscordIds}, which only holds the residents
 * who have linked an account and is therefore always smaller or equal. Deriving
 * one from the other would reject towns that meet the minimum but have not
 * finished linking.
 */
public record SpaceRequest(
        UUID townUuid,
        String townName,
        UUID mayorUuid,
        java.util.List<String> linkedResidentDiscordIds,
        String mayorDiscordId,
        int townyResidentCount) {}
