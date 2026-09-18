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

    /**
     * Consume el codigo y crea el vinculo en una sola transaccion.
     *
     * <p>Existe porque hacerlo en tres pasos sueltos (leer el codigo, insertar
     * el vinculo, borrar el codigo) deja huecos que no se pueden cerrar desde
     * arriba: dos canjes simultaneos leen el mismo codigo vigente y ambos
     * terminan con exito, y un fallo al borrar deja un codigo ya usado que
     * sigue siendo canjeable.
     *
     * <p>El codigo se reclama borrandolo: si el borrado no afecta a ninguna
     * fila, otro lo consumio primero. Si la insercion del vinculo falla, la
     * transaccion se deshace y el codigo vuelve a estar disponible, de modo que
     * un choque de unicidad no quema el codigo del jugador.
     *
     * <p>El UUID del jugador se toma de la fila del codigo dentro de la misma
     * transaccion, nunca de una lectura previa.
     *
     * @param now instante con el que se juzga la caducidad
     */
    ConsumeOutcome consumeCodeAndLink(String code, String discordId, String lastKnownName,
                                      java.time.Instant now);

    /** Resultado de {@link #consumeCodeAndLink}. */
    record ConsumeOutcome(ConsumeResult result, Optional<AccountLink> link) {

        public static ConsumeOutcome of(ConsumeResult result) {
            return new ConsumeOutcome(result, Optional.empty());
        }
    }

    enum ConsumeResult {
        OK,
        /** No existe, o alguien lo consumio antes. */
        CODE_NOT_FOUND,
        CODE_EXPIRED,
        /** Ese jugador ya tiene vinculo. */
        PLAYER_ALREADY_LINKED,
        /** Esa cuenta de Discord ya esta vinculada a otro jugador. */
        DISCORD_ALREADY_LINKED
    }

    /** Cuantos vinculos hay. Para {@code /dt admin list}. */
    int count();
}
