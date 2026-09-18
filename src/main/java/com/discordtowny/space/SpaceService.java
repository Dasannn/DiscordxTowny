package com.discordtowny.space;

import com.discordtowny.model.SpaceRequest;
import com.discordtowny.model.TownSpace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Ciclo de vida del espacio de Discord de una town.
 *
 * <p>Nada se borra por si solo: una town que desaparece deja su espacio
 * archivado, con el historial legible. El borrado definitivo lo ordena un
 * administrador.
 */
public interface SpaceService {

    /**
     * Crea el espacio de una town.
     *
     * <p>Quien llama ya comprobo en el hilo principal que es el alcalde. Aqui
     * se comprueban las condiciones del plugin: que no exista ya, cupo, minimo
     * de residentes y cooldown.
     */
    CompletableFuture<CreateResult> create(SpaceRequest request);

    CompletableFuture<Void> rename(UUID townUuid, String newName);

    CompletableFuture<Void> archive(UUID townUuid, String reason);

    /** Devuelve al activo un espacio archivado, conservando su historial. */
    CompletableFuture<Void> restore(SpaceRequest request);

    /** Borrado definitivo de los espacios archivados. Solo administradores. */
    CompletableFuture<Integer> purgeArchived();

    CompletableFuture<Optional<TownSpace>> find(UUID townUuid);

    CompletableFuture<List<TownSpace>> findAll();

    /** Por que no se pudo crear, o que se creo. */
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
