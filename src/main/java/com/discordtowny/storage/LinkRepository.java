package com.discordtowny.storage;

import com.discordtowny.model.AccountLink;
import com.discordtowny.model.LinkCode;
import java.util.Optional;
import java.util.UUID;

/** Vinculos verificados y codigos pendientes. Bloquea: ver {@link Storage}. */
public interface LinkRepository {

    Optional<AccountLink> findByUuid(UUID uuid);

    Optional<AccountLink> findByDiscordId(String discordId);

    /**
     * Guarda el vinculo.
     *
     * @throws StorageException si el UUID o el Discord ID ya estan vinculados.
     *     La unicidad se garantiza en el esquema, no solo comprobando antes:
     *     dos peticiones simultaneas pasarian la comprobacion a la vez.
     */
    void save(AccountLink link);

    /** @return cierto si habia algo que borrar. */
    boolean deleteByUuid(UUID uuid);

    /** Sustituye cualquier codigo vivo del jugador por este. */
    void saveCode(LinkCode code);

    Optional<LinkCode> findCode(String code);

    void deleteCode(String code);

    /** Suma uno a los intentos fallidos y devuelve el total. */
    int incrementAttempts(String code);

    /** Borra los codigos caducados. Lo llama el job periodico. */
    int purgeExpiredCodes();

    /** Cuantos vinculos hay. Para {@code /dt admin list}. */
    int count();
}
