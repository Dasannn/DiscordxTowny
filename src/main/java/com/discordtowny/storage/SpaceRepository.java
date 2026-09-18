package com.discordtowny.storage;

import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSpace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Espacios de Discord registrados. Bloquea: ver {@link Storage}. */
public interface SpaceRepository {

    Optional<TownSpace> findByTownUuid(UUID townUuid);

    Optional<TownSpace> findByChannelId(String channelId);

    List<TownSpace> findByState(SpaceState state);

    List<TownSpace> findAll();

    /**
     * Inserta o actualiza.
     *
     * <p>La creacion de un espacio guarda cada identificador en cuanto existe,
     * sin esperar a terminar: si el servidor cae a mitad, la reconciliacion
     * sabe que se creo y que falta.
     */
    void save(TownSpace space);

    void delete(UUID townUuid);

    void updateState(UUID townUuid, SpaceState state);

    void touchActivity(UUID townUuid, Instant at);

    /** Espacios activos. Se compara con {@code limits.max-towns}. */
    int countActive();
}
