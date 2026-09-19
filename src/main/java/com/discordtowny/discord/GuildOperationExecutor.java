package com.discordtowny.discord;

/**
 * Executes a {@link GuildOperation} against the Discord guild.
 *
 * <p>This interface separates the queue logic (retries, serialization) from
 * the actual calls to JDA, allowing the queue to be tested without a network.
 *
 * <p>Each implementation must be <b>idempotent</b>: before creating anything,
 * it checks whether it already exists by its saved identifier. Retrying a
 * half-finished operation cannot duplicate channels or roles.
 */
@FunctionalInterface
interface GuildOperationExecutor {

    /**
     * Executes the operation and returns the outcome.
     *
     * <p>Never throws an exception: failures are represented in
     * {@link OperationOutcome}. The caller decides whether to retry.
     */
    OperationOutcome execute(GuildOperation operation);

    /**
     * Notifies when an operation has failed permanently or has exhausted
     * its retries. Allows marking spaces as inconsistent.
     */
    default void onOperationFailed(GuildOperation operation, OperationOutcome outcome) {}
}
