package com.discordtowny.discord;

/**
 * Ejecuta una {@link GuildOperation} contra el guild de Discord.
 *
 * <p>Esta interfaz separa la logica de la cola (reintentos, serializacion) de
 * las llamadas reales a JDA, lo que permite probar la cola sin red.
 *
 * <p>Cada implementacion debe ser <b>idempotente</b>: antes de crear algo se
 * comprueba si ya existe por su identificador guardado. Reintentar una
 * operacion a medias no puede duplicar canales ni roles.
 */
@FunctionalInterface
interface GuildOperationExecutor {

    /**
     * Ejecuta la operacion y devuelve el resultado.
     *
     * <p>Nunca lanza excepcion: los fallos se representan en
     * {@link OperationOutcome}. El llamador decide si reintentar.
     */
    OperationOutcome execute(GuildOperation operation);

    /**
     * Notifica cuando una operacion ha fallado definitivamente o ha agotado
     * sus reintentos. Permite marcar espacios como inconsistentes.
     */
    default void onOperationFailed(GuildOperation operation, OperationOutcome outcome) {}
}
