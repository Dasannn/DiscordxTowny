package com.discordtowny.discord;

import com.discordtowny.model.AuditEvent;
import java.util.concurrent.CompletableFuture;

/**
 * Salida hacia Discord.
 *
 * <p>Toda mutacion del guild pasa por {@link #submit}, que la encola en una
 * cola serializada de un solo consumidor. No se paralelizan creaciones: el
 * limite de peticiones de Discord lo haria inutil y multiplicaria los fallos
 * parciales.
 *
 * <p>Nada bloquea esperando a Discord. Quien necesite el resultado encadena al
 * futuro; quien no, lo ignora.
 */
public interface DiscordGateway {

    /**
     * Cierto si el bot esta conectado y operativo.
     *
     * <p>Si es falso, el servidor de Minecraft funciona con normalidad y los
     * comandos responden que Discord no esta disponible.
     */
    boolean isAvailable();

    /**
     * Encola una operacion sobre el guild.
     *
     * <p>El futuro nunca se completa de forma excepcional: los fallos viajan
     * dentro de {@link OperationOutcome}, porque un fallo de Discord es un
     * resultado esperado, no una excepcion.
     */
    CompletableFuture<OperationOutcome> submit(GuildOperation operation);

    /**
     * Encola un evento hacia el canal de logs.
     *
     * <p>No bloquea nunca. Si la cola esta llena descarta los eventos menos
     * relevantes y anota cuantos se perdieron.
     */
    void log(AuditEvent event);

    /**
     * Comprueba que el bot puede operar: rol por encima de los que gestiona y
     * permisos necesarios en el guild.
     *
     * <p>Realiza I/O bloqueante de base de datos. No invocar desde el hilo principal
     * de Paper; preferir {@link #verifyPermissionsAsync()}.
     *
     * @return vacio si todo esta bien, o la razon por la que no puede operar.
     */
    java.util.Optional<String> verifyPermissions();

    /**
     * Comprueba de forma asincrona fuera del hilo principal que el bot puede operar.
     *
     * @return futuro con vacio si todo esta bien, o la razon por la que no puede operar.
     */
    default CompletableFuture<java.util.Optional<String>> verifyPermissionsAsync() {
        return CompletableFuture.supplyAsync(this::verifyPermissions);
    }

    /**
     * Devuelve el ID del rol de alcalde gestionado si existe en el guild.
     *
     * @return vacio si se comprobo que no existe en el guild; presente con su ID si existe.
     * @throws IllegalStateException si el gateway no esta disponible o no se pudo resolver.
     */
    java.util.Optional<String> mayorRoleId();
}
