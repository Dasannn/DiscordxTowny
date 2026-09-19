package com.discordtowny.towny;

import com.discordtowny.model.ResidentSnapshot;
import com.discordtowny.model.TownSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sole gateway to the Towny API. Read-only.
 *
 * <p><b>All methods are called on the server's main thread.</b> The Towny
 * API is not thread-safe off of it. What they return are immutable copies,
 * suitable for dispatching to the pool.
 *
 * <p>Exists so that a Towny API change is addressed in a single package.
 * No class outside of here imports anything from {@code com.palmergames}.
 */
public interface TownyFacade {

    /** True if Towny is loaded and responding. */
    boolean isAvailable();

    Optional<TownSnapshot> town(UUID townUuid);

    Optional<TownSnapshot> townByName(String name);

    /** The player's town, if they belong to any. */
    Optional<TownSnapshot> townOf(UUID playerUuid);

    Optional<ResidentSnapshot> resident(UUID playerUuid);

    Optional<ResidentSnapshot> residentByName(String name);

    /** All towns, for listings and for reconciliation. */
    List<TownSnapshot> allTowns();

    int townCount();
}
