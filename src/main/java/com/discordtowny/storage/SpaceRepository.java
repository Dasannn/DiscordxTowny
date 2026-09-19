package com.discordtowny.storage;

import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSpace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Registered Discord spaces. Blocks: see {@link Storage}. */
public interface SpaceRepository {

    Optional<TownSpace> findByTownUuid(UUID townUuid);

    Optional<TownSpace> findByChannelId(String channelId);

    List<TownSpace> findByState(SpaceState state);

    List<TownSpace> findAll();

    /**
     * Inserts or updates.
     *
     * <p>The creation of a space saves each identifier as soon as it exists,
     * without waiting until completion: if the server crashes halfway through,
     * reconciliation knows what was created and what is missing.
     */
    void save(TownSpace space);

    void delete(UUID townUuid);

    void updateState(UUID townUuid, SpaceState state);

    void touchActivity(UUID townUuid, Instant at);

    /** Active spaces. Compared against {@code limits.max-towns}. */
    int countActive();
}
