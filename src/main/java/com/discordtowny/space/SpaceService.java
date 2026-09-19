package com.discordtowny.space;

import com.discordtowny.model.SpaceRequest;
import com.discordtowny.model.TownSpace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Lifecycle of a town's Discord space.
 *
 * <p>Nothing is deleted on its own: a town that disappears leaves its space
 * archived, with readable history. Permanent deletion is ordered by an
 * administrator.
 */
public interface SpaceService {

    /**
     * Creates a town's space.
     *
     * <p>The caller already verified on the main thread that they are the mayor.
     * Plugin conditions are checked here: that it does not already exist, quota,
     * minimum resident count, and cooldown.
     */
    CompletableFuture<CreateResult> create(SpaceRequest request);

    CompletableFuture<Void> rename(UUID townUuid, String newName);

    CompletableFuture<Void> archive(UUID townUuid, String reason);

    /** Returns an archived space to active, preserving its history. */
    CompletableFuture<Void> restore(SpaceRequest request);

    /** Permanent deletion of archived spaces. Administrators only. */
    CompletableFuture<Integer> purgeArchived();

    CompletableFuture<Optional<TownSpace>> find(UUID townUuid);

    CompletableFuture<List<TownSpace>> findAll();

    /** Why it could not be created, or that it was created. */
    enum CreateResult {
        SUCCESS,
        ALREADY_EXISTS,
        TOO_FEW_RESIDENTS,
        LIMIT_REACHED,
        ON_COOLDOWN,
        DISCORD_UNAVAILABLE,
        MAYOR_NOT_LINKED,
        FAILED
    }
}
