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

    /**
     * Borra los codigos caducados a la fecha indicada.
     *
     * <p>El instante se recibe, no se consulta aqui: si el repositorio leyera
     * el reloj del sistema por su cuenta, su nocion de "ahora" diferiria de la
     * del servicio que lo llama, y no habria forma de probarlo con un reloj
     * controlado.
     */
    int purgeExpiredCodes(java.time.Instant now);

    /** Cuantos vinculos hay. Para {@code /dt admin list}. */
    int count();
}
