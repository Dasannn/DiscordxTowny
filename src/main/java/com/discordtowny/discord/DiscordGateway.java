package com.discordtowny.discord;

import com.discordtowny.model.AuditEvent;
import java.util.concurrent.CompletableFuture;

/**
 * Outbound gateway to Discord.
 *
 * <p>Every guild mutation passes through {@link #submit}, which enqueues it into a
 * serialized single-consumer queue. Creations are not parallelized: Discord's
 * rate limit would make it futile and would multiply partial failures.
 *
 * <p>Nothing blocks waiting on Discord. Whoever needs the result chains onto the
 * future; whoever does not, ignores it.
 */
public interface DiscordGateway {

    /**
     * True if the bot is connected and operational.
     *
     * <p>If false, the Minecraft server operates normally and commands
     * respond that Discord is unavailable.
     */
    boolean isAvailable();

    /**
     * Enqueues an operation on the guild.
     *
     * <p>The future never completes exceptionally: failures travel inside
     * {@link OperationOutcome}, because a Discord failure is an expected
     * outcome, not an exception.
     */
    CompletableFuture<OperationOutcome> submit(GuildOperation operation);

    /**
     * Enqueues an event to the log channel.
     *
     * <p>Never blocks. If the queue is full, it drops the least relevant
     * events and records how many were lost.
     */
    void log(AuditEvent event);

    /**
     * Checks that the bot can operate: role above those it manages and
     * required permissions in the guild.
     *
     * <p>Performs blocking database I/O. Do not invoke from the Paper main thread;
     * prefer {@link #verifyPermissionsAsync()}.
     *
     * @return empty if all is well, or the reason why it cannot operate.
     */
    java.util.Optional<String> verifyPermissions();

    /**
     * Checks asynchronously off the main thread that the bot can operate.
     *
     * @return future with empty if all is well, or the reason why it cannot operate.
     */
    default CompletableFuture<java.util.Optional<String>> verifyPermissionsAsync() {
        return CompletableFuture.supplyAsync(this::verifyPermissions);
    }

    /**
     * Returns the ID of the managed mayor role if it exists in the guild.
     *
     * @return empty if verified that it does not exist in the guild; present with its ID if it exists.
     * @throws IllegalStateException if the gateway is unavailable or could not be resolved.
     */
    java.util.Optional<String> mayorRoleId();

    /**
     * Returns the Discord user IDs of all members who currently hold the given role.
     *
     * <p>Plain Discord IDs are returned; internal gateway entities never escape
     * this package.
     *
     * <p><b>Important:</b> This operation depends on the bot being connected with the
     * {@code GUILD_MEMBERS} gateway intent and an active member cache. If a server owner
     * disables this intent in the Discord developer portal, the member cache will not be
     * populated and this method will silently return an empty set.
     *
     * @param roleId the Discord ID of the role to inspect.
     * @return an unmodifiable set of Discord user IDs currently holding the role;
     *         empty if no members hold the role or if the role does not exist.
     * @throws IllegalStateException if the gateway is unavailable or could not be resolved.
     */
    java.util.Set<String> roleHolders(String roleId);

    /**
     * Answers which of a set of stored Discord IDs still exist in the guild —
     * channels and roles alike.
     *
     * <p>Plain Discord IDs are returned; internal gateway entities never escape
     * this package.
     *
     * @param ids the IDs to check.
     * @return an unmodifiable set of IDs from the input that currently exist in the guild.
     * @throws IllegalStateException if the gateway is unavailable or could not be resolved.
     */
    java.util.Set<String> existingResourceIds(java.util.Collection<String> ids);

    default java.util.Set<String> existingIds(java.util.Collection<String> ids) {
        return existingResourceIds(ids);
    }

    /**
     * Registers slash command listeners and publishes command definitions to Discord.
     *
     * <p>Registration is performed inside the discord package without leaking JDA.
     * If the gateway is not yet connected, registration is performed once connection completes.
     *
     * @param townyFacade live Towny facade
     * @param linkService link service
     * @param messages messages catalog
     * @param mainThreadExecutor executor returning to server main thread
     */
    default void registerSlashCommands(
            com.discordtowny.towny.TownyFacade townyFacade,
            com.discordtowny.link.LinkService linkService,
            com.discordtowny.config.Messages messages,
            java.util.concurrent.Executor mainThreadExecutor) {}

    /**
     * Returns the registered LinkSlashCommands listener, if registered.
     */
    default java.util.Optional<LinkSlashCommands> linkSlashCommands() {
        return java.util.Optional.empty();
    }

    /**
     * Returns the registered TownySlashCommands listener, if registered.
     */
    default java.util.Optional<TownySlashCommands> townySlashCommands() {
        return java.util.Optional.empty();
    }
}

