package com.discordtowny.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Vinculo verificado entre una cuenta de Minecraft y una de Discord.
 *
 * <p>Relacion uno a uno en ambos sentidos. {@code lastKnownName} es solo para
 * mostrar: identificar por nombre de jugador es un error, los nombres cambian.
 */
public record AccountLink(UUID uuid, String discordId, Instant linkedAt, String lastKnownName) {}
