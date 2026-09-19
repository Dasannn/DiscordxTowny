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
 *
 * <p><b>An empty result means the entity is confirmed not to exist.</b> A read
 * that could not be completed throws {@link TownyReadException} instead, so
 * that a failure is never mistaken for an answer. A caller that reconciles must
 * treat the exception as "unknown" and leave access alone: removing a role or
 * archiving a town on a failed read punishes a town that is perfectly alive.
 */
public interface TownyFacade {

    /**
     * True if Towny is loaded and responding.
     *
     * <p>The only method here that never throws: answering "not available" is
     * the whole point of asking.
     */
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
