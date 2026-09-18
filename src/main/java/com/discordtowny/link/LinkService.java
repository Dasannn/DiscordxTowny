package com.discordtowny.link;

import com.discordtowny.model.AccountLink;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Vinculacion verificada entre cuenta de Minecraft y cuenta de Discord.
 *
 * <p>Ningun permiso se concede sobre una identidad no verificada: este servicio
 * es la unica via por la que una cuenta de Discord queda asociada a un jugador.
 *
 * <p>Los metodos devuelven futuros porque tocan la base de datos. Se pueden
 * llamar desde el hilo principal sin bloquearlo.
 */
public interface LinkService {

    /**
     * Genera un codigo para el jugador, invalidando el anterior si lo tenia.
     *
     * @return el codigo, o vacio si el jugador ya esta vinculado
     */
    default CompletableFuture<Optional<String>> generateCode(UUID uuid) {
        return generateCode(uuid, "");
    }

    /**
     * Genera un codigo para el jugador guardando su ultimo nombre conocido,
     * invalidando el anterior si lo tenia.
     *
     * @param uuid identificador del jugador
     * @param lastKnownName ultimo nombre conocido del jugador para mostrar
     * @return el codigo, o vacio si el jugador ya esta vinculado
     */
    CompletableFuture<Optional<String>> generateCode(UUID uuid, String lastKnownName);

    /** Consume el codigo y crea el vinculo. */
    CompletableFuture<LinkResult> redeem(String code, String discordId);

    CompletableFuture<Optional<AccountLink>> findByUuid(UUID uuid);

    CompletableFuture<Optional<AccountLink>> findByDiscordId(String discordId);

    /**
     * Rompe el vinculo y retira todos los roles que dio el plugin.
     *
     * @return cierto si habia un vinculo que romper
     */
    CompletableFuture<Boolean> unlink(UUID uuid);

    /** Resultado de canjear un codigo. */
    enum LinkResult {
        SUCCESS,
        CODE_INVALID,
        CODE_EXPIRED,
        /** Esa cuenta de Discord ya esta vinculada a otro jugador. */
        DISCORD_ALREADY_LINKED,
        /** Ese jugador ya tiene otra cuenta de Discord. */
        PLAYER_ALREADY_LINKED,
        /** Demasiados intentos fallidos: bloqueado temporalmente. */
        TOO_MANY_ATTEMPTS
    }
}
