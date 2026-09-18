package com.discordtowny.towny;

import com.discordtowny.model.ResidentSnapshot;
import com.discordtowny.model.TownSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Unica puerta a la API de Towny. Solo lectura.
 *
 * <p><b>Todos los metodos se llaman en el hilo principal del servidor.</b> La
 * API de Towny no es segura fuera de el. Lo que devuelven son copias
 * inmutables, aptas para viajar al pool.
 *
 * <p>Existe para que un cambio de API de Towny se arregle en un solo paquete.
 * Ninguna clase fuera de aqui importa nada de {@code com.palmergames}.
 */
public interface TownyFacade {

    /** Cierto si Towny esta cargado y respondiendo. */
    boolean isAvailable();

    Optional<TownSnapshot> town(UUID townUuid);

    Optional<TownSnapshot> townByName(String name);

    /** La town del jugador, si pertenece a alguna. */
    Optional<TownSnapshot> townOf(UUID playerUuid);

    Optional<ResidentSnapshot> resident(UUID playerUuid);

    Optional<ResidentSnapshot> residentByName(String name);

    /** Todas las towns, para listados y para la reconciliacion. */
    List<TownSnapshot> allTowns();

    int townCount();
}
