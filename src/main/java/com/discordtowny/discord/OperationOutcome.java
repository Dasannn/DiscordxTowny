package com.discordtowny.discord;

import java.util.Optional;

/**
 * Outcome of an operation on the guild.
 *
 * <p>The distinction between transient and permanent failure decides what the
 * queue does: transient failure is retried with increasing backoff, permanent
 * failure aborts the task and leaves the space as inconsistent for
 * reconciliation to pick up.
 */
public record OperationOutcome(Status status, Optional<String> reason) {

    public enum Status {
        SUCCESS,
        /** Network down, rate limit, Discord having a bad day. Retried. */
        TRANSIENT_FAILURE,
        /** Missing permissions, reached a Discord limit. Not retried. */
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
