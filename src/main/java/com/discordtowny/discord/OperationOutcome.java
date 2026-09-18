package com.discordtowny.discord;

import java.util.Optional;

/**
 * Resultado de una operacion sobre el guild.
 *
 * <p>La distincion entre fallo transitorio y permanente decide que hace la cola:
 * el transitorio se reintenta con espera creciente, el permanente corta la
 * tarea y deja el espacio como inconsistente para que lo retome la
 * reconciliacion.
 */
public record OperationOutcome(Status status, Optional<String> reason) {

    public enum Status {
        SUCCESS,
        /** Red caida, limite de peticiones, Discord de mal humor. Se reintenta. */
        TRANSIENT_FAILURE,
        /** Faltan permisos, se alcanzo un limite de Discord. No se reintenta. */
        PERMANENT_FAILURE
    }

    public static OperationOutcome success() {
        return new OperationOutcome(Status.SUCCESS, Optional.empty());
    }

    public static OperationOutcome transientFailure(String reason) {
        return new OperationOutcome(Status.TRANSIENT_FAILURE, Optional.of(reason));
    }

    public static OperationOutcome permanentFailure(String reason) {
        return new OperationOutcome(Status.PERMANENT_FAILURE, Optional.of(reason));
    }

    public boolean succeeded() {
        return status == Status.SUCCESS;
    }
}
