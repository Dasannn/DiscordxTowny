package com.discordtowny.minecraft;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.link.LinkService;
import com.discordtowny.model.AccountLink;
import com.discordtowny.model.SpaceRequest;
import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSnapshot;
import com.discordtowny.model.TownSpace;
import com.discordtowny.space.SpaceService;
import com.discordtowny.space.SpaceService.CreateResult;
import com.discordtowny.sync.SyncService;
import com.discordtowny.towny.TownyFacade;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Unified registration and handling of all in-game commands under {@code /dt} (alias {@code /discordtowny}).
 *
 * <p>Registered using Paper's modern Brigadier lifecycle events.
 * Threading model:
 * <ul>
 *   <li>All Towny reads occur on the server main thread.</li>
 *   <li>All database and Discord calls occur off the main thread.</li>
 *   <li>Asynchronous operations immediately acknowledge the player and schedule
 *       the result back onto the server main thread.</li>
 *   <li>Console senders consistently receive English text per Spec 9.1.</li>
 * </ul>
 */
public final class MinecraftCommands {

    private static final Logger LOGGER = Logger.getLogger("DiscordTowny");
    private static final long CONFIRMATION_EXPIRY_SECONDS = 30L;

    private static final ConcurrentHashMap<UUID, Instant> pendingDeletes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Instant> pendingPurges = new ConcurrentHashMap<>();

    private MinecraftCommands() {}

    /**
     * Builds the Brigadier command node using static instances and default scheduler.
     */
    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            LinkService linkService,
            SpaceService spaceService,
            SyncService syncService,
            TownyFacade townyFacade,
            DiscordGateway discordGateway,
            PluginConfig config,
            Messages messages,
            Messages consoleMessages,
            Runnable reloadAction,
            Consumer<Runnable> syncScheduler) {
        return createCommandNode(
                linkService != null ? () -> linkService : () -> null,
                spaceService != null ? () -> spaceService : () -> null,
                syncService != null ? () -> syncService : () -> null,
                townyFacade != null ? () -> townyFacade : () -> null,
                discordGateway != null ? () -> discordGateway : () -> null,
                config != null ? () -> config : () -> null,
                messages != null ? () -> messages : () -> null,
                consoleMessages != null ? () -> consoleMessages : EnglishMessages::bundled,
                reloadAction,
                syncScheduler
        );
    }

    /**
     * Builds the complete Brigadier command tree for /dt using suppliers.
     */
    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            Supplier<LinkService> linkServiceSupplier,
            Supplier<SpaceService> spaceServiceSupplier,
            Supplier<SyncService> syncServiceSupplier,
            Supplier<TownyFacade> townyFacadeSupplier,
            Supplier<DiscordGateway> discordGatewaySupplier,
            Supplier<PluginConfig> configSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Runnable reloadAction,
            Consumer<Runnable> syncScheduler) {
        Objects.requireNonNull(messagesSupplier, "messagesSupplier cannot be null");
        Consumer<Runnable> scheduler = syncScheduler != null ? syncScheduler : defaultScheduler();

        LiteralArgumentBuilder<CommandSourceStack> dt = Commands.literal("dt");

        // 1. /dt help
        dt.then(createHelpSubcommand(messagesSupplier, consoleMessagesSupplier, townyFacadeSupplier));

        // 2. /dt status
        dt.then(createStatusSubcommand(linkServiceSupplier, spaceServiceSupplier, townyFacadeSupplier,
                messagesSupplier, consoleMessagesSupplier, scheduler));

        // 3. /dt link
        dt.then(createLinkSubcommand(linkServiceSupplier, configSupplier, messagesSupplier, consoleMessagesSupplier, scheduler));

        // 4. /dt unlink
        dt.then(createUnlinkSubcommand(linkServiceSupplier, messagesSupplier, consoleMessagesSupplier, scheduler));

        // 5. /dt create
        dt.then(createCreateSubcommand(spaceServiceSupplier, linkServiceSupplier, discordGatewaySupplier,
                configSupplier, townyFacadeSupplier, messagesSupplier, consoleMessagesSupplier, scheduler));

        // 6. /dt delete
        dt.then(createDeleteSubcommand(spaceServiceSupplier, townyFacadeSupplier,
                messagesSupplier, consoleMessagesSupplier, scheduler));

        // 7. /dt sync
        dt.then(createSyncSubcommand(syncServiceSupplier, configSupplier, messagesSupplier, consoleMessagesSupplier,
                townyFacadeSupplier, scheduler));

        // 8. /dt admin ...
        dt.then(createAdminNode(linkServiceSupplier, spaceServiceSupplier, syncServiceSupplier, townyFacadeSupplier,
                discordGatewaySupplier, configSupplier, messagesSupplier, consoleMessagesSupplier, reloadAction, scheduler));

        return dt.build();
    }

    /**
     * Builds /dt help, strictly filtered by what the executor can actually use.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createHelpSubcommand(
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Supplier<TownyFacade> townyFacadeSupplier) {
        return Commands.literal("help")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);

                    sender.sendMessage(msg.get("help.header"));

                    if (sender instanceof Player player) {
                        UUID uuid = player.getUniqueId();
                        TownyFacade tf = townyFacadeSupplier.get();
                        boolean isMayor = false;
                        if (tf != null && tf.isAvailable()) {
                            try {
                                Optional<TownSnapshot> town = tf.townOf(uuid);
                                if (town.isPresent() && town.get().isMayor(uuid)) {
                                    isMayor = true;
                                }
                            } catch (Exception ignored) {}
                        }
                        boolean isAdmin = player.hasPermission("discordtowny.admin");

                        // General player commands
                        sender.sendMessage(msg.get("help.cmd-help"));
                        sender.sendMessage(msg.get("help.cmd-link"));
                        sender.sendMessage(msg.get("help.cmd-unlink"));
                        sender.sendMessage(msg.get("help.cmd-status"));

                        // Mayor commands
                        if (isMayor) {
                            sender.sendMessage(msg.get("help.cmd-create"));
                            sender.sendMessage(msg.get("help.cmd-delete"));
                            sender.sendMessage(msg.get("help.cmd-sync"));
                        }

                        // Admin commands
                        if (isAdmin) {
                            sender.sendMessage(msg.get("help.cmd-admin-sync"));
                            sender.sendMessage(msg.get("help.cmd-admin-unlink"));
                            sender.sendMessage(msg.get("help.cmd-admin-reload"));
                            sender.sendMessage(msg.get("help.cmd-admin-list"));
                            sender.sendMessage(msg.get("help.cmd-admin-info"));
                            sender.sendMessage(msg.get("help.cmd-admin-purge"));
                        }
                    } else {
                        // Console sender
                        sender.sendMessage(msg.get("help.cmd-help"));
                        sender.sendMessage(msg.get("help.cmd-status"));
                        sender.sendMessage(msg.get("help.cmd-admin-sync"));
                        sender.sendMessage(msg.get("help.cmd-admin-unlink"));
                        sender.sendMessage(msg.get("help.cmd-admin-reload"));
                        sender.sendMessage(msg.get("help.cmd-admin-list"));
                        sender.sendMessage(msg.get("help.cmd-admin-info"));
                        sender.sendMessage(msg.get("help.cmd-admin-purge"));
                    }

                    return 1;
                });
    }

    /**
     * Builds /dt status.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createStatusSubcommand(
            Supplier<LinkService> linkServiceSupplier,
            Supplier<SpaceService> spaceServiceSupplier,
            Supplier<TownyFacade> townyFacadeSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Consumer<Runnable> scheduler) {
        return Commands.literal("status")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(msg.get("general.players-only"));
                        return 1;
                    }

                    // Acknowledge immediately on main thread
                    player.sendMessage(msg.get("general.working"));

                    UUID uuid = player.getUniqueId();
                    TownyFacade tf = townyFacadeSupplier.get();
                    TownSnapshot town = null;
                    if (tf != null && tf.isAvailable()) {
                        try {
                            town = tf.townOf(uuid).orElse(null);
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "[Status] Error reading player town for " + uuid, e);
                        }
                    }

                    LinkService linkService = linkServiceSupplier.get();
                    SpaceService spaceService = spaceServiceSupplier.get();

                    if (linkService == null) {
                        scheduler.accept(() -> player.sendMessage(msg.get("general.database-unavailable")));
                        return 1;
                    }

                    TownSnapshot finalTown = town;
                    CompletableFuture<Optional<AccountLink>> linkFuture = linkService.findByUuid(uuid);
                    CompletableFuture<Optional<TownSpace>> spaceFuture = (finalTown != null && spaceService != null)
                            ? spaceService.find(finalTown.uuid())
                            : CompletableFuture.completedFuture(Optional.empty());

                    linkFuture.thenCombine(spaceFuture, (linkOpt, spaceOpt) -> {
                        scheduler.accept(() -> {
                            player.sendMessage(msg.get("status.header"));

                            // Linking status
                            if (linkOpt.isPresent()) {
                                player.sendMessage(msg.get("status.linked", Map.of(
                                        "discord", linkOpt.get().discordId()
                                )));
                            } else {
                                player.sendMessage(msg.get("status.not-linked"));
                            }

                            // Town and space status
                            if (finalTown == null) {
                                player.sendMessage(msg.get("status.no-town"));
                            } else {
                                String townName = finalTown.name();
                                if (spaceOpt.isPresent()) {
                                    TownSpace space = spaceOpt.get();
                                    if (space.state() == SpaceState.ACTIVE) {
                                        player.sendMessage(msg.get("status.space-active", Map.of("town", townName)));
                                    } else if (space.state() == SpaceState.ARCHIVED) {
                                        player.sendMessage(msg.get("status.space-archived", Map.of("town", townName)));
                                    } else {
                                        player.sendMessage(msg.get("status.space-inconsistent", Map.of("town", townName)));
                                    }
                                } else {
                                    player.sendMessage(msg.get("status.no-space", Map.of("town", townName)));
                                }
                            }
                        });
                        return null;
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> player.sendMessage(msg.get("general.database-unavailable")));
                        return null;
                    });

                    return 1;
                });
    }

    /**
     * Builds /dt link.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createLinkSubcommand(
            Supplier<LinkService> linkServiceSupplier,
            Supplier<PluginConfig> configSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Consumer<Runnable> scheduler) {
        return Commands.literal("link")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(msg.get("general.players-only"));
                        return 1;
                    }

                    LinkService linkService = linkServiceSupplier.get();
                    PluginConfig config = configSupplier.get();
                    if (linkService == null || config == null) {
                        sender.sendMessage(msg.get("general.database-unavailable"));
                        return 1;
                    }

                    player.sendMessage(msg.get("general.working"));

                    linkService.generateCode(player.getUniqueId(), player.getName()).thenAccept(optCode -> {
                        scheduler.accept(() -> {
                            if (optCode.isEmpty()) {
                                player.sendMessage(msg.get("linking.already-linked"));
                            } else {
                                long minutes = config.linking().codeExpiry().toMinutes();
                                player.sendMessage(msg.get("linking.code-generated", Map.of(
                                        "code", optCode.get(),
                                        "minutes", String.valueOf(minutes)
                                )));
                            }
                        });
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> player.sendMessage(msg.get("general.database-unavailable")));
                        return null;
                    });

                    return 1;
                });
    }

    /**
     * Builds /dt unlink.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createUnlinkSubcommand(
            Supplier<LinkService> linkServiceSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Consumer<Runnable> scheduler) {
        return Commands.literal("unlink")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(msg.get("general.players-only"));
                        return 1;
                    }

                    LinkService linkService = linkServiceSupplier.get();
                    if (linkService == null) {
                        sender.sendMessage(msg.get("general.database-unavailable"));
                        return 1;
                    }

                    player.sendMessage(msg.get("general.working"));

                    linkService.unlink(player.getUniqueId()).thenAccept(unlinked -> {
                        scheduler.accept(() -> {
                            if (unlinked) {
                                player.sendMessage(msg.get("linking.unlink-success"));
                            } else {
                                player.sendMessage(msg.get("linking.not-linked"));
                            }
                        });
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> player.sendMessage(msg.get("general.database-unavailable")));
                        return null;
                    });

                    return 1;
                });
    }

    /**
     * Builds /dt create.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createCreateSubcommand(
            Supplier<SpaceService> spaceServiceSupplier,
            Supplier<LinkService> linkServiceSupplier,
            Supplier<DiscordGateway> discordGatewaySupplier,
            Supplier<PluginConfig> configSupplier,
            Supplier<TownyFacade> townyFacadeSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Consumer<Runnable> scheduler) {
        return Commands.literal("create")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(msg.get("general.players-only"));
                        return 1;
                    }

                    TownyFacade tf = townyFacadeSupplier.get();
                    if (tf == null || !tf.isAvailable()) {
                        player.sendMessage(msg.get("general.not-in-town"));
                        return 1;
                    }

                    UUID playerUuid = player.getUniqueId();
                    Optional<TownSnapshot> townOpt = tf.townOf(playerUuid);
                    if (townOpt.isEmpty()) {
                        player.sendMessage(msg.get("general.not-in-town"));
                        return 1;
                    }

                    TownSnapshot town = townOpt.get();
                    if (!town.isMayor(playerUuid)) {
                        player.sendMessage(msg.get("general.not-mayor"));
                        return 1;
                    }

                    DiscordGateway gateway = discordGatewaySupplier.get();
                    if (gateway == null || !gateway.isAvailable()) {
                        player.sendMessage(msg.get("general.discord-unavailable"));
                        return 1;
                    }

                    SpaceService spaceService = spaceServiceSupplier.get();
                    LinkService linkService = linkServiceSupplier.get();
                    PluginConfig config = configSupplier.get();
                    if (spaceService == null || linkService == null || config == null) {
                        player.sendMessage(msg.get("general.database-unavailable"));
                        return 1;
                    }

                    // Acknowledge immediately
                    player.sendMessage(msg.get("space.creating", Map.of("town", town.name())));

                    // Async check for mayor link and space creation
                    linkService.findByUuid(playerUuid).thenCompose(mayorLinkOpt -> {
                        if (mayorLinkOpt.isEmpty()) {
                            return CompletableFuture.completedFuture(CreateResult.MAYOR_NOT_LINKED);
                        }
                        String mayorDiscordId = mayorLinkOpt.get().discordId();

                        List<UUID> residentUuids = town.residentUuids() != null ? town.residentUuids() : List.of();
                        List<CompletableFuture<Optional<AccountLink>>> linkFutures = residentUuids.stream()
                                .map(linkService::findByUuid)
                                .toList();

                        return CompletableFuture.allOf(linkFutures.toArray(CompletableFuture[]::new))
                                .thenCompose(v -> {
                                    List<String> linkedResidentDiscordIds = linkFutures.stream()
                                            .map(CompletableFuture::join)
                                            .flatMap(Optional::stream)
                                            .map(AccountLink::discordId)
                                            .toList();

                                    UUID mayorUuid = town.mayorUuid() != null ? town.mayorUuid() : playerUuid;
                                    SpaceRequest request = new SpaceRequest(
                                            town.uuid(),
                                            town.name(),
                                            mayorUuid,
                                            linkedResidentDiscordIds,
                                            mayorDiscordId,
                                            town.residentCount()
                                    );
                                    return spaceService.create(request);
                                });
                    }).thenAccept(result -> {
                        scheduler.accept(() -> {
                            switch (result) {
                                case SUCCESS -> player.sendMessage(msg.get("space.created", Map.of("town", town.name())));
                                case ALREADY_EXISTS -> player.sendMessage(msg.get("space.already-exists", Map.of("town", town.name())));
                                case TOO_FEW_RESIDENTS -> player.sendMessage(msg.get("space.too-few-residents", Map.of(
                                        "min", String.valueOf(config.limits().minResidents())
                                )));
                                case LIMIT_REACHED -> player.sendMessage(msg.get("space.limit-reached", Map.of(
                                        "max", String.valueOf(config.limits().maxTowns())
                                )));
                                case ON_COOLDOWN -> player.sendMessage(msg.get("space.cooldown", Map.of(
                                        "seconds", String.valueOf(config.limits().creationCooldown().toSeconds())
                                )));
                                case MAYOR_NOT_LINKED -> player.sendMessage(msg.get("linking.link-required"));
                                case DISCORD_UNAVAILABLE -> player.sendMessage(msg.get("general.discord-unavailable"));
                                case FAILED -> player.sendMessage(msg.get("space.failed", Map.of("reason", msg.label("space.internal-error"))));
                            }
                        });
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> player.sendMessage(msg.get("general.database-unavailable")));
                        return null;
                    });

                    return 1;
                });
    }

    /**
     * Builds /dt delete, requiring confirmation.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createDeleteSubcommand(
            Supplier<SpaceService> spaceServiceSupplier,
            Supplier<TownyFacade> townyFacadeSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Consumer<Runnable> scheduler) {
        return Commands.literal("delete")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(msg.get("general.players-only"));
                        return 1;
                    }

                    TownyFacade tf = townyFacadeSupplier.get();
                    if (tf == null || !tf.isAvailable()) {
                        player.sendMessage(msg.get("general.not-in-town"));
                        return 1;
                    }

                    UUID playerUuid = player.getUniqueId();
                    Optional<TownSnapshot> townOpt = tf.townOf(playerUuid);
                    if (townOpt.isEmpty()) {
                        player.sendMessage(msg.get("general.not-in-town"));
                        return 1;
                    }

                    TownSnapshot town = townOpt.get();
                    if (!town.isMayor(playerUuid)) {
                        player.sendMessage(msg.get("general.not-mayor"));
                        return 1;
                    }

                    SpaceService spaceService = spaceServiceSupplier.get();
                    if (spaceService == null) {
                        player.sendMessage(msg.get("general.database-unavailable"));
                        return 1;
                    }

                    Instant pending = pendingDeletes.get(playerUuid);
                    if (pending == null || Instant.now().isAfter(pending)) {
                        pendingDeletes.put(playerUuid, Instant.now().plusSeconds(CONFIRMATION_EXPIRY_SECONDS));
                        player.sendMessage(msg.get("space.delete-confirm", Map.of("town", town.name())));
                        return 1;
                    }

                    // Confirmed: perform archive deletion
                    pendingDeletes.remove(playerUuid);
                    player.sendMessage(msg.get("general.working"));

                    spaceService.archive(town.uuid(), "mayor-deleted").thenAccept(v -> {
                        scheduler.accept(() -> player.sendMessage(msg.get("space.archived", Map.of("town", town.name()))));
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> player.sendMessage(msg.get("general.database-unavailable")));
                        return null;
                    });

                    return 1;
                });
    }

    /**
     * Builds /dt sync.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createSyncSubcommand(
            Supplier<SyncService> syncServiceSupplier,
            Supplier<PluginConfig> configSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Supplier<TownyFacade> townyFacadeSupplier,
            Consumer<Runnable> scheduler) {
        return Commands.literal("sync")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(msg.get("general.players-only"));
                        return 1;
                    }

                    TownyFacade tf = townyFacadeSupplier.get();
                    if (tf == null || !tf.isAvailable()) {
                        player.sendMessage(msg.get("general.not-in-town"));
                        return 1;
                    }

                    UUID playerUuid = player.getUniqueId();
                    Optional<TownSnapshot> townOpt = tf.townOf(playerUuid);
                    if (townOpt.isEmpty()) {
                        player.sendMessage(msg.get("general.not-in-town"));
                        return 1;
                    }

                    TownSnapshot town = townOpt.get();
                    if (!town.isMayor(playerUuid)) {
                        player.sendMessage(msg.get("general.not-mayor"));
                        return 1;
                    }

                    SyncService syncService = syncServiceSupplier.get();
                    if (syncService == null) {
                        player.sendMessage(msg.get("general.database-unavailable"));
                        return 1;
                    }

                    player.sendMessage(msg.get("sync.started"));

                    UUID townUuid = town.uuid();
                    syncService.syncTown(townUuid).thenAccept(report -> {
                        scheduler.accept(() -> replySyncReport(player, report, isReportMode(configSupplier), msg));
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> replySyncError(player, msg, ex));
                        return null;
                    });

                    return 1;
                });
    }

    /**
     * Builds /dt admin tree: reload, list, info <town>, purge, unlink <jugador>, sync [town].
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createAdminNode(
            Supplier<LinkService> linkServiceSupplier,
            Supplier<SpaceService> spaceServiceSupplier,
            Supplier<SyncService> syncServiceSupplier,
            Supplier<TownyFacade> townyFacadeSupplier,
            Supplier<DiscordGateway> discordGatewaySupplier,
            Supplier<PluginConfig> configSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Runnable reloadAction,
            Consumer<Runnable> scheduler) {
        LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin")
                .requires(source -> source.getSender().hasPermission("discordtowny.admin"));

        // /dt admin reload
        admin.then(Commands.literal("reload")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);
                    try {
                        if (reloadAction != null) {
                            reloadAction.run();
                        }
                        // Re-resolve messages in case reload updated texts or language
                        Messages updatedMsg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);
                        sender.sendMessage(updatedMsg.get("admin.reloaded"));
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "[AdminReload] Reload failed", ex);
                        sender.sendMessage(msg.get("admin.reload-failed", Map.of(
                                "reason", ex.getMessage() != null ? ex.getMessage() : "unknown"
                        )));
                    }
                    return 1;
                })
        );

        // /dt admin list
        admin.then(Commands.literal("list")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);

                    SpaceService spaceService = spaceServiceSupplier.get();
                    if (spaceService == null) {
                        sender.sendMessage(msg.get("general.database-unavailable"));
                        return 1;
                    }

                    sender.sendMessage(msg.get("general.working"));

                    spaceService.findAll().thenAccept(spaces -> {
                        scheduler.accept(() -> {
                            if (spaces.isEmpty()) {
                                sender.sendMessage(msg.get("admin.list-empty"));
                                return;
                            }

                            sender.sendMessage(msg.get("admin.list-header", Map.of(
                                    "count", String.valueOf(spaces.size())
                            )));

                            DiscordGateway gw = discordGatewaySupplier.get();
                            for (TownSpace space : spaces) {
                                String residents = (gw != null && gw.isAvailable() && space.roleId().isPresent())
                                        ? String.valueOf(gw.roleHolders(space.roleId().get()).size())
                                        : "N/A";

                                String textLabel = msg.label("admin.channel-text");
                                String voiceLabel = msg.label("admin.channel-voice");
                                String noneLabel = msg.label("admin.none");

                                String channels;
                                if (space.textChannelId().isPresent() && space.voiceChannelId().isPresent()) {
                                    channels = textLabel + " " + voiceLabel;
                                } else if (space.textChannelId().isPresent()) {
                                    channels = textLabel;
                                } else if (space.voiceChannelId().isPresent()) {
                                    channels = voiceLabel;
                                } else {
                                    channels = noneLabel;
                                }

                                String activity = space.lastActivityAt().map(Instant::toString).orElse(noneLabel);

                                sender.sendMessage(msg.get("admin.list-entry", Map.of(
                                        "town", space.townName(),
                                        "status", space.state().name(),
                                        "channels", channels.trim(),
                                        "residents", residents,
                                        "activity", activity
                                )));
                            }
                        });
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> sender.sendMessage(msg.get("general.database-unavailable")));
                        return null;
                    });

                    return 1;
                })
        );

        // /dt admin info <town>
        admin.then(Commands.literal("info")
                .then(Commands.argument("town", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String remaining = builder.getRemainingLowerCase();
                            try {
                                TownyFacade tf = townyFacadeSupplier.get();
                                if (tf != null && tf.isAvailable()) {
                                    for (TownSnapshot t : tf.allTowns()) {
                                        if (t.name().toLowerCase().startsWith(remaining)) {
                                            builder.suggest(t.name());
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);
                            String townName = StringArgumentType.getString(ctx, "town");

                            SpaceService spaceService = spaceServiceSupplier.get();
                            if (spaceService == null) {
                                sender.sendMessage(msg.get("general.database-unavailable"));
                                return 1;
                            }

                            sender.sendMessage(msg.get("general.working"));

                            TownyFacade tf = townyFacadeSupplier.get();
                            TownSnapshot townSnapshot = null;
                            if (tf != null && tf.isAvailable()) {
                                try {
                                    townSnapshot = tf.townByName(townName).orElse(null);
                                } catch (Exception ignored) {}
                            }

                            TownSnapshot finalTownSnapshot = townSnapshot;
                            CompletableFuture<Optional<TownSpace>> spaceFuture = (finalTownSnapshot != null)
                                    ? spaceService.find(finalTownSnapshot.uuid())
                                    : spaceService.findAll().thenApply(all ->
                                            all.stream().filter(s -> s.townName().equalsIgnoreCase(townName)).findFirst());

                            spaceFuture.thenAccept(spaceOpt -> {
                                scheduler.accept(() -> {
                                    if (spaceOpt.isEmpty()) {
                                        if (finalTownSnapshot == null) {
                                            sender.sendMessage(msg.get("general.town-not-found", Map.of("town", townName)));
                                        } else {
                                            sender.sendMessage(msg.get("admin.info-no-space", Map.of("town", townName)));
                                        }
                                        return;
                                    }

                                    TownSpace space = spaceOpt.get();
                                    DiscordGateway gw = discordGatewaySupplier.get();
                                    String residents = (gw != null && gw.isAvailable() && space.roleId().isPresent())
                                            ? String.valueOf(gw.roleHolders(space.roleId().get()).size())
                                            : "N/A";

                                    sender.sendMessage(msg.get("admin.info-header", Map.of("town", space.townName())));
                                    sender.sendMessage(msg.get("admin.info-status", Map.of("status", space.state().name())));
                                    sender.sendMessage(msg.get("admin.info-uuid", Map.of("uuid", space.townUuid().toString())));
                                    sender.sendMessage(msg.get("admin.info-channels", Map.of(
                                            "category", space.categoryId().orElse("-"),
                                            "text", space.textChannelId().orElse("-"),
                                            "voice", space.voiceChannelId().orElse("-")
                                    )));
                                    sender.sendMessage(msg.get("admin.info-role", Map.of(
                                            "role", space.roleId().orElse("-"),
                                            "residents", residents
                                    )));
                                    sender.sendMessage(msg.get("admin.info-created", Map.of("created", space.createdAt().toString())));
                                    sender.sendMessage(msg.get("admin.info-archived", Map.of("archived", space.archivedAt().map(Instant::toString).orElse("-"))));
                                    sender.sendMessage(msg.get("admin.info-activity", Map.of("activity", space.lastActivityAt().map(Instant::toString).orElseGet(() -> msg.label("admin.none")))));

                                    // Check inconsistencies
                                    List<String> problems = new ArrayList<>();
                                    if (space.state() == SpaceState.INCONSISTENT) {
                                        problems.add(msg.label("admin.problem-db-inconsistent"));
                                    }
                                    if (gw != null && gw.isAvailable()) {
                                        List<String> idsToCheck = new ArrayList<>();
                                        space.textChannelId().ifPresent(idsToCheck::add);
                                        space.voiceChannelId().ifPresent(idsToCheck::add);
                                        space.roleId().ifPresent(idsToCheck::add);
                                        var existing = gw.existingResourceIds(idsToCheck);
                                        space.textChannelId().ifPresent(id -> {
                                            if (!existing.contains(id)) problems.add(msg.label("admin.problem-missing-text-channel", Map.of("id", id)));
                                        });
                                        space.voiceChannelId().ifPresent(id -> {
                                            if (!existing.contains(id)) problems.add(msg.label("admin.problem-missing-voice-channel", Map.of("id", id)));
                                        });
                                        space.roleId().ifPresent(id -> {
                                            if (!existing.contains(id)) problems.add(msg.label("admin.problem-missing-role", Map.of("id", id)));
                                        });
                                    }
                                    if (finalTownSnapshot == null && space.state() == SpaceState.ACTIVE) {
                                        problems.add(msg.label("admin.problem-town-deleted"));
                                    } else if (finalTownSnapshot != null && finalTownSnapshot.ruined() && space.state() == SpaceState.ACTIVE) {
                                        problems.add(msg.label("admin.problem-town-ruined"));
                                    }

                                    if (problems.isEmpty()) {
                                        sender.sendMessage(msg.get("admin.info-consistent"));
                                    } else {
                                        sender.sendMessage(msg.get("admin.info-inconsistencies-header", Map.of(
                                                "count", String.valueOf(problems.size())
                                        )));
                                        for (String p : problems) {
                                            sender.sendMessage(msg.get("admin.info-inconsistency-entry", Map.of("problem", p)));
                                        }
                                    }
                                });
                            }).exceptionally(ex -> {
                                scheduler.accept(() -> sender.sendMessage(msg.get("general.database-unavailable")));
                                return null;
                            });

                            return 1;
                        })
                )
        );

        // /dt admin purge, requiring confirmation
        admin.then(Commands.literal("purge")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);

                    SpaceService spaceService = spaceServiceSupplier.get();
                    if (spaceService == null) {
                        sender.sendMessage(msg.get("general.database-unavailable"));
                        return 1;
                    }

                    String senderKey = (sender instanceof Player p) ? p.getUniqueId().toString() : "console";
                    Instant pending = pendingPurges.get(senderKey);

                    if (pending == null || Instant.now().isAfter(pending)) {
                        // First run: count archived spaces
                        sender.sendMessage(msg.get("general.working"));
                        spaceService.findAll().thenAccept(all -> {
                            long count = all.stream().filter(s -> s.state() == SpaceState.ARCHIVED).count();
                            scheduler.accept(() -> {
                                if (count == 0) {
                                    sender.sendMessage(msg.get("admin.purge-empty"));
                                    return;
                                }
                                pendingPurges.put(senderKey, Instant.now().plusSeconds(CONFIRMATION_EXPIRY_SECONDS));
                                sender.sendMessage(msg.get("admin.purge-confirm", Map.of("count", String.valueOf(count))));
                            });
                        }).exceptionally(ex -> {
                            scheduler.accept(() -> sender.sendMessage(msg.get("general.database-unavailable")));
                            return null;
                        });
                        return 1;
                    }

                    // Confirmed run
                    pendingPurges.remove(senderKey);
                    sender.sendMessage(msg.get("general.working"));

                    spaceService.purgeArchived().thenAccept(deleted -> {
                        scheduler.accept(() -> sender.sendMessage(msg.get("admin.purged", Map.of("count", String.valueOf(deleted)))));
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> sender.sendMessage(msg.get("general.database-unavailable")));
                        return null;
                    });

                    return 1;
                })
        );

        // /dt admin unlink <jugador>
        admin.then(LinkMinecraftCommands.createAdminUnlinkSubcommand(
                linkServiceSupplier,
                messagesSupplier,
                consoleMessagesSupplier,
                townyFacadeSupplier,
                scheduler
        ));

        // /dt admin sync [town]
        admin.then(SyncMinecraftCommands.createAdminSyncSubcommand(
                syncServiceSupplier,
                configSupplier,
                messagesSupplier,
                consoleMessagesSupplier,
                townyFacadeSupplier,
                scheduler
        ));

        return admin;
    }

    /**
     * Registers commands in Paper's lifecycle manager.
     */
    public static void register(
            Plugin plugin,
            Supplier<PluginConfig> configSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Supplier<LinkService> linkServiceSupplier,
            Supplier<SpaceService> spaceServiceSupplier,
            Supplier<SyncService> syncServiceSupplier,
            Supplier<TownyFacade> townyFacadeSupplier,
            Supplier<DiscordGateway> discordGatewaySupplier,
            Runnable reloadAction) {
        Consumer<Runnable> scheduler = task -> Bukkit.getScheduler().runTask(plugin, task);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            LiteralCommandNode<CommandSourceStack> node = createCommandNode(
                    linkServiceSupplier,
                    spaceServiceSupplier,
                    syncServiceSupplier,
                    townyFacadeSupplier,
                    discordGatewaySupplier,
                    configSupplier,
                    messagesSupplier,
                    consoleMessagesSupplier,
                    reloadAction,
                    scheduler
            );
            registrar.register(node, "DiscordTowny in-game commands", List.of("discordtowny"));
        });
    }

    private static Messages resolveMessages(CommandSender sender, Supplier<Messages> messagesSupplier, Supplier<Messages> consoleMessagesSupplier) {
        if (sender instanceof Player) {
            Messages m = messagesSupplier != null ? messagesSupplier.get() : null;
            return m != null ? m : EnglishMessages.bundled();
        }
        Messages cm = consoleMessagesSupplier != null ? consoleMessagesSupplier.get() : null;
        return cm != null ? cm : EnglishMessages.bundled();
    }

    private static boolean isReportMode(Supplier<PluginConfig> configSupplier) {
        if (configSupplier == null) return false;
        PluginConfig cfg = configSupplier.get();
        return cfg != null && cfg.sync() != null && cfg.sync().mode() == PluginConfig.Sync.Mode.REPORT;
    }

    private static void replySyncReport(CommandSender sender, SyncService.SyncReport report, boolean reportMode, Messages messages) {
        if (report == null) {
            sender.sendMessage(messages.get("sync.finished"));
            return;
        }

        if (reportMode) {
            if (report.inconsistenciesFound() > 0) {
                sender.sendMessage(messages.get("sync.report-found", Map.of("count", String.valueOf(report.inconsistenciesFound()))));
                // In report mode the confirmed counts are zero by definition:
                // what the operator needs to see is what the pass *would* do.
                if (report.proposedRolesGranted() > 0 || report.proposedRolesRevoked() > 0) {
                    sender.sendMessage(messages.get("sync.report-pending", Map.of(
                            "granted", String.valueOf(report.proposedRolesGranted()),
                            "revoked", String.valueOf(report.proposedRolesRevoked())
                    )));
                }
            } else {
                sender.sendMessage(messages.get("sync.report-clean", Map.of("spaces", String.valueOf(report.spacesChecked()))));
            }

            if (!report.problems().isEmpty()) {
                sender.sendMessage(messages.get("sync.problems-header", Map.of("count", String.valueOf(report.problems().size()))));
                for (String problem : report.problems()) {
                    sender.sendMessage(messages.get("sync.problem-entry", Map.of("problem", problem)));
                }
            }
            return;
        }

        // Repair mode: report repairs, problems, and any unrepaired discrepancies
        if (report.inconsistenciesRepaired() > 0 || report.rolesGranted() > 0 || report.rolesRevoked() > 0) {
            sender.sendMessage(messages.get("sync.repaired", Map.of(
                    "count", String.valueOf(report.inconsistenciesRepaired()),
                    "granted", String.valueOf(report.rolesGranted()),
                    "revoked", String.valueOf(report.rolesRevoked())
            )));
        }

        if (!report.problems().isEmpty()) {
            sender.sendMessage(messages.get("sync.problems-header", Map.of("count", String.valueOf(report.problems().size()))));
            for (String problem : report.problems()) {
                sender.sendMessage(messages.get("sync.problem-entry", Map.of("problem", problem)));
            }
        } else if (report.inconsistenciesFound() > report.inconsistenciesRepaired()) {
            int unhandled = report.inconsistenciesFound() - report.inconsistenciesRepaired();
            sender.sendMessage(messages.get("sync.unrepaired", Map.of(
                    "count", String.valueOf(unhandled)
            )));
        } else if (report.inconsistenciesRepaired() == 0 && report.rolesGranted() == 0 && report.rolesRevoked() == 0) {
            sender.sendMessage(messages.get("sync.finished"));
        }
    }

    private static void replySyncError(CommandSender sender, Messages messages, Throwable ex) {
        String msg = ex != null && ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if (msg.contains("discord")) {
            sender.sendMessage(messages.get("general.discord-unavailable"));
        } else {
            sender.sendMessage(messages.get("general.database-unavailable"));
        }
    }

    private static Consumer<Runnable> defaultScheduler() {
        return task -> {
            try {
                if (Bukkit.getServer() != null) {
                    Plugin plugin = Bukkit.getPluginManager().getPlugin("DiscordTowny");
                    if (plugin != null && plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, task);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        };
    }
}
