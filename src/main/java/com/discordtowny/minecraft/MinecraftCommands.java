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
import com.discordtowny.update.DefaultUpdateService;
import com.discordtowny.update.UpdateService;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.discordtowny.config.YamlMessages;
import com.discordtowny.model.AuditEvent;
import com.discordtowny.storage.SettingsRepository;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

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

    public static final String PERMISSION_USE = "discordtowny.use";
    public static final String PERMISSION_ADMIN = "discordtowny.admin";
    public static final int PAGE_SIZE = 10;
    public static final int MAX_VISIBLE_PREFIX_LENGTH = 32;
    public static final int MAX_RAW_PREFIX_LENGTH = 255;
    public static final int MAX_PREFIX_LENGTH = MAX_VISIBLE_PREFIX_LENGTH;

    private static final Logger LOGGER = Logger.getLogger("DiscordTowny");
    private static final long CONFIRMATION_EXPIRY_SECONDS = 30L;

    private static final ConcurrentHashMap<UUID, Instant> pendingDeletes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Instant> pendingPurges = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Instant> pendingUpdateConfirmations = new ConcurrentHashMap<>();

    private MinecraftCommands() {}


    /**
     * Builds the complete Brigadier command tree for /dt including Settings, Audit Supplier, and explicit Executor.
     */
    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            Supplier<LinkService> linkServiceSupplier,
            Supplier<SpaceService> spaceServiceSupplier,
            Supplier<SyncService> syncServiceSupplier,
            Supplier<TownyFacade> townyFacadeSupplier,
            Supplier<DiscordGateway> discordGatewaySupplier,
            Supplier<UpdateService> updateServiceSupplier,
            Supplier<SettingsRepository> settingsSupplier,
            Supplier<Consumer<AuditEvent>> auditConsumerSupplier,
            Supplier<PluginConfig> configSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Runnable reloadAction,
            Consumer<Runnable> syncScheduler,
            Executor asyncExecutor) {
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
                discordGatewaySupplier, updateServiceSupplier, settingsSupplier, auditConsumerSupplier, configSupplier,
                messagesSupplier, consoleMessagesSupplier, reloadAction, scheduler, asyncExecutor));

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

                    if (sender instanceof Player player) {
                        if (!player.hasPermission(PERMISSION_USE)) {
                            sender.sendMessage(msg.get("general.no-permission"));
                            return 1;
                        }

                        sender.sendMessage(msg.get("help.header"));

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

                        // General player commands (only if executor has discordtowny.use)
                        sender.sendMessage(msg.get("help.cmd-help"));
                        sender.sendMessage(msg.get("help.cmd-link"));
                        sender.sendMessage(msg.get("help.cmd-unlink"));
                        sender.sendMessage(msg.get("help.cmd-status"));

                        // Mayor commands (only if executor has discordtowny.use and is mayor)
                        if (isMayor) {
                            sender.sendMessage(msg.get("help.cmd-create"));
                            sender.sendMessage(msg.get("help.cmd-delete"));
                            sender.sendMessage(msg.get("help.cmd-sync"));
                        }

                        // Admin commands (only if executor has discordtowny.admin)
                        if (player.hasPermission(PERMISSION_ADMIN)) {
                            sendAdminHelp(sender, msg);
                        }
                    } else {
                        // Console sender
                        sender.sendMessage(msg.get("help.header"));
                        sender.sendMessage(msg.get("help.cmd-help"));
                        if (sender.hasPermission(PERMISSION_ADMIN)) {
                            sendAdminHelp(sender, msg);
                        }
                    }

                    return 1;
                });
    }

    private static void sendAdminHelp(CommandSender sender, Messages msg) {
        sender.sendMessage(msg.get("help.cmd-admin-sync"));
        sender.sendMessage(msg.get("help.cmd-admin-unlink"));
        sender.sendMessage(msg.get("help.cmd-admin-reload"));
        sender.sendMessage(msg.get("help.cmd-admin-list"));
        sender.sendMessage(msg.get("help.cmd-admin-info"));
        sender.sendMessage(msg.get("help.cmd-admin-purge"));
        sender.sendMessage(msg.get("help.cmd-admin-update"));
        sender.sendMessage(msg.get("help.cmd-admin-update-status"));
        sender.sendMessage(msg.get("help.cmd-admin-update-confirm"));
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
                    if (!player.hasPermission(PERMISSION_USE)) {
                        player.sendMessage(msg.get("general.no-permission"));
                        return 1;
                    }

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
                    if (!player.hasPermission(PERMISSION_USE)) {
                        player.sendMessage(msg.get("general.no-permission"));
                        return 1;
                    }

                    LinkService linkService = linkServiceSupplier.get();
                    PluginConfig config = configSupplier.get();
                    if (linkService == null || config == null) {
                        sender.sendMessage(msg.get("general.database-unavailable"));
                        return 1;
                    }

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
                    if (!player.hasPermission(PERMISSION_USE)) {
                        player.sendMessage(msg.get("general.no-permission"));
                        return 1;
                    }

                    LinkService linkService = linkServiceSupplier.get();
                    if (linkService == null) {
                        sender.sendMessage(msg.get("general.database-unavailable"));
                        return 1;
                    }

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
                    if (!player.hasPermission(PERMISSION_USE)) {
                        player.sendMessage(msg.get("general.no-permission"));
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

                    // The gateway is deliberately NOT checked here. SpaceService.create makes
                    // the same admission decision, returns DISCORD_UNAVAILABLE, and records the
                    // refusal in the audit log. Checking here as well refused before the service
                    // was ever reached, so the one run an operator most needs in dt_audit_log -
                    // someone trying to create a space while the bot is down - left no trace.
                    // One admission point, one audit.

                    SpaceService spaceService = spaceServiceSupplier.get();
                    LinkService linkService = linkServiceSupplier.get();
                    PluginConfig config = configSupplier.get();
                    if (spaceService == null || linkService == null || config == null) {
                        player.sendMessage(msg.get("general.database-unavailable"));
                        return 1;
                    }

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
                    if (!player.hasPermission(PERMISSION_USE)) {
                        player.sendMessage(msg.get("general.no-permission"));
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
                    if (!player.hasPermission(PERMISSION_USE)) {
                        player.sendMessage(msg.get("general.no-permission"));
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

    public static LiteralArgumentBuilder<CommandSourceStack> createAdminNode(
            Supplier<LinkService> linkServiceSupplier,
            Supplier<SpaceService> spaceServiceSupplier,
            Supplier<SyncService> syncServiceSupplier,
            Supplier<TownyFacade> townyFacadeSupplier,
            Supplier<DiscordGateway> discordGatewaySupplier,
            Supplier<UpdateService> updateServiceSupplier,
            Supplier<SettingsRepository> settingsSupplier,
            Supplier<Consumer<AuditEvent>> auditConsumerSupplier,
            Supplier<PluginConfig> configSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Runnable reloadAction,
            Consumer<Runnable> scheduler,
            Executor asyncExecutor) {
        LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin")
                .requires(source -> source.getSender().hasPermission(PERMISSION_ADMIN));

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
                        updateHelpDescription(messagesSupplier);
                        sender.sendMessage(updatedMsg.get("admin.reloaded"));
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "[AdminReload] Reload failed", ex);
                        sender.sendMessage(msg.get("admin.reload-failed", Map.of(
                                "reason", ex.getMessage() != null && !ex.getMessage().isBlank()
                                        ? ex.getMessage()
                                        : msg.label("general.unknown")
                        )));
                    }
                    return 1;
                })
        );

        // /dt admin list [page]
        admin.then(Commands.literal("list")
                .executes(ctx -> executeAdminList(ctx, 1, spaceServiceSupplier, discordGatewaySupplier, messagesSupplier, consoleMessagesSupplier, scheduler))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> executeAdminList(ctx, IntegerArgumentType.getInteger(ctx, "page"), spaceServiceSupplier, discordGatewaySupplier, messagesSupplier, consoleMessagesSupplier, scheduler)))
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
                                            : msg.label("admin.not-applicable");

                                    sender.sendMessage(msg.get("admin.info-header", Map.of("town", space.townName())));
                                    sender.sendMessage(msg.get("admin.info-status", Map.of("status", formatSpaceState(space.state(), msg))));
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
                        // First run: count archived and inconsistent spaces
                        spaceService.findAll().thenAccept(all -> {
                            long count = all.stream().filter(s -> s.state() == SpaceState.ARCHIVED).count();
                            long inconsistent = all.stream().filter(s -> s.state() == SpaceState.INCONSISTENT).count();
                            scheduler.accept(() -> {
                                if (count == 0) {
                                    if (inconsistent > 0) {
                                        sender.sendMessage(msg.get("admin.purge-skipped", Map.of("count", String.valueOf(inconsistent))));
                                    } else {
                                        sender.sendMessage(msg.get("admin.purge-empty"));
                                    }
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

                    spaceService.findAll().thenCompose(all -> {
                        long inconsistent = all.stream().filter(s -> s.state() == SpaceState.INCONSISTENT).count();
                        return spaceService.purgeArchived().thenAccept(deleted -> {
                            scheduler.accept(() -> {
                                sender.sendMessage(msg.get("admin.purged", Map.of("count", String.valueOf(deleted))));
                                if (inconsistent > 0) {
                                    sender.sendMessage(msg.get("admin.purge-skipped", Map.of("count", String.valueOf(inconsistent))));
                                }
                            });
                        });
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

        // /dt admin update
        admin.then(createAdminUpdateNode(
                updateServiceSupplier,
                messagesSupplier,
                consoleMessagesSupplier,
                scheduler
        ));

        // /dt admin prefix
        admin.then(createAdminPrefixNode(
                settingsSupplier,
                auditConsumerSupplier,
                spaceServiceSupplier,
                messagesSupplier,
                consoleMessagesSupplier,
                scheduler,
                asyncExecutor
        ));

        return admin;
    }

    /**
     * Builds /dt admin update subcommand tree: /dt admin update, /dt admin update status, /dt admin update confirm.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createAdminUpdateNode(
            Supplier<UpdateService> updateServiceSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Consumer<Runnable> scheduler) {
        LiteralArgumentBuilder<CommandSourceStack> update = Commands.literal("update");

        // /dt admin update
        update.executes(ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);

            UpdateService updateService = updateServiceSupplier != null ? updateServiceSupplier.get() : null;
            if (updateService == null) {
                sender.sendMessage(msg.get("updates.disabled"));
                return 1;
            }

            if (updateService.isUpdatePending()) {
                String ver = updateService.getAvailableUpdate()
                        .map(UpdateService.Release::version)
                        .orElse(updateService.currentVersion());
                sender.sendMessage(msg.get("updates.downloaded", Map.of("latest", ver)));
                discloseFailedCheckIfAny(updateService, msg, sender);
                return 1;
            }

            String senderKey = (sender instanceof Player p) ? p.getUniqueId().toString() : "console";
            Instant pending = pendingUpdateConfirmations.get(senderKey);
            boolean isConfirmed = pending != null && Instant.now().isBefore(pending);

            updateService.checkForUpdate().thenAccept(checkResult -> {
                scheduler.accept(() -> {
                    if (checkResult.status() == UpdateService.CheckStatus.CHECK_FAILED) {
                        String reason = DefaultUpdateService.formatCheckFailureReason(
                                checkResult.error().orElse(null), msg);
                        sender.sendMessage(msg.get("updates.check-failed", Map.of("reason", reason)));
                        return;
                    }

                    if (checkResult.status() == UpdateService.CheckStatus.NOT_CHECKED) {
                        sender.sendMessage(msg.get("updates.not-checked"));
                        return;
                    }

                    if (checkResult.release().isEmpty()) {
                        sender.sendMessage(msg.get("updates.up-to-date"));
                        return;
                    }

                    UpdateService.Release release = checkResult.release().get();
                    if (updateService.isBreaking(release) && !isConfirmed) {
                        pendingUpdateConfirmations.put(senderKey, Instant.now().plusSeconds(CONFIRMATION_EXPIRY_SECONDS));
                        sender.sendMessage(msg.get("updates.confirm-breaking", Map.of("latest", release.version())));
                        return;
                    }

                    // Confirmed or non-breaking: proceed with download
                    pendingUpdateConfirmations.remove(senderKey);
                    updateService.download(release).thenAccept(result -> {
                        scheduler.accept(() -> {
                            switch (result) {
                                case SUCCESS -> sender.sendMessage(msg.get("updates.downloaded", Map.of("latest", release.version())));
                                case CHECKSUM_MISMATCH -> sender.sendMessage(msg.get("updates.checksum-mismatch"));
                                default -> sender.sendMessage(msg.get("updates.download-failed", Map.of("reason", formatDownloadResult(result, msg))));
                            }
                        });
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> sender.sendMessage(msg.get("updates.download-failed", Map.of(
                                "reason", ex.getMessage() != null && !ex.getMessage().isBlank()
                                        ? ex.getMessage()
                                        : msg.label("general.unknown")
                        ))));
                        return null;
                    });
                });
            }).exceptionally(ex -> {
                scheduler.accept(() -> {
                    String reason = DefaultUpdateService.formatCheckFailureReason(
                            ex.getMessage(), msg);
                    sender.sendMessage(msg.get("updates.check-failed", Map.of("reason", reason)));
                });
                return null;
            });

            return 1;
        });

        // /dt admin update status
        update.then(Commands.literal("status")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);

                    UpdateService updateService = updateServiceSupplier != null ? updateServiceSupplier.get() : null;
                    if (updateService == null) {
                        sender.sendMessage(msg.get("updates.disabled"));
                        return 1;
                    }

                    String current = updateService.currentVersion();
                    sender.sendMessage(msg.get("updates.status-current", Map.of("current", current)));

                    UpdateService.CheckResult lastResult = updateService.getLastCheckResult();
                    UpdateService.CheckStatus status = (lastResult != null)
                            ? lastResult.status()
                            : updateService.checkStatus();
                    Optional<UpdateService.Release> availableOpt = (lastResult != null && lastResult.release().isPresent())
                            ? lastResult.release()
                            : updateService.getAvailableUpdate();

                    boolean hasUpdateInfo = false;
                    if (updateService.isUpdatePending()) {
                        hasUpdateInfo = true;
                        String ver = availableOpt
                                .map(UpdateService.Release::version)
                                .orElse(current);
                        sender.sendMessage(msg.get("updates.downloaded", Map.of("latest", ver)));
                    } else if (availableOpt.isPresent()) {
                        hasUpdateInfo = true;
                        UpdateService.Release release = availableOpt.get();
                        sender.sendMessage(msg.get("updates.available", Map.of("latest", release.version(), "current", current)));
                        if (updateService.isBreaking(release)) {
                            sender.sendMessage(msg.get("updates.breaking", Map.of("latest", release.version())));
                        }
                        String summary = DefaultUpdateService.extractSummary(release.notes());
                        if (!summary.isBlank()) {
                            sender.sendMessage(msg.get("updates.summary", Map.of("summary", summary)));
                        }
                    }

                    if (status == UpdateService.CheckStatus.CHECK_FAILED) {
                        String rawError = (lastResult != null && lastResult.error().isPresent())
                                ? lastResult.error().get()
                                : updateService.getLastCheckError().orElse(null);
                        String reason = DefaultUpdateService.formatCheckFailureReason(rawError, msg);
                        sender.sendMessage(msg.get("updates.check-failed", Map.of("reason", reason)));
                    } else if (!hasUpdateInfo) {
                        if (status == UpdateService.CheckStatus.NOT_CHECKED) {
                            sender.sendMessage(msg.get("updates.not-checked"));
                        } else if (status == UpdateService.CheckStatus.UP_TO_DATE) {
                            sender.sendMessage(msg.get("updates.up-to-date"));
                        }
                    }

                    return 1;
                })
        );

        // /dt admin update confirm
        update.then(Commands.literal("confirm")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);

                    UpdateService updateService = updateServiceSupplier != null ? updateServiceSupplier.get() : null;
                    if (updateService == null) {
                        sender.sendMessage(msg.get("updates.disabled"));
                        return 1;
                    }

                    if (updateService.isUpdatePending()) {
                        String ver = updateService.getAvailableUpdate()
                                .map(UpdateService.Release::version)
                                .orElse(updateService.currentVersion());
                        sender.sendMessage(msg.get("updates.downloaded", Map.of("latest", ver)));
                        discloseFailedCheckIfAny(updateService, msg, sender);
                        return 1;
                    }

                    Optional<UpdateService.Release> opt = updateService.getAvailableUpdate();
                    if (opt.isEmpty() || !updateService.isBreaking(opt.get())) {
                        sender.sendMessage(msg.get("updates.no-confirmation-needed"));
                        discloseFailedCheckIfAny(updateService, msg, sender);
                        return 1;
                    }

                    UpdateService.Release release = opt.get();
                    String senderKey = (sender instanceof Player p) ? p.getUniqueId().toString() : "console";
                    pendingUpdateConfirmations.remove(senderKey);

                    updateService.download(release).thenAccept(result -> {
                        scheduler.accept(() -> {
                            switch (result) {
                                case SUCCESS -> {
                                    sender.sendMessage(msg.get("updates.downloaded", Map.of("latest", release.version())));
                                    discloseFailedCheckIfAny(updateService, msg, sender);
                                }
                                case CHECKSUM_MISMATCH -> sender.sendMessage(msg.get("updates.checksum-mismatch"));
                                default -> sender.sendMessage(msg.get("updates.download-failed", Map.of("reason", formatDownloadResult(result, msg))));
                            }
                        });
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> sender.sendMessage(msg.get("updates.download-failed", Map.of(
                                "reason", ex.getMessage() != null && !ex.getMessage().isBlank()
                                        ? ex.getMessage()
                                        : msg.label("general.unknown")
                        ))));
                        return null;
                    });

                    return 1;
                })
        );

        return update;
    }


    /**
     * Registers commands in Paper's lifecycle manager with audit consumer supplier.
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
            Supplier<UpdateService> updateServiceSupplier,
            Supplier<SettingsRepository> settingsSupplier,
            Supplier<Consumer<AuditEvent>> auditConsumerSupplier,
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
                    updateServiceSupplier,
                    settingsSupplier,
                    auditConsumerSupplier,
                    configSupplier,
                    messagesSupplier,
                    consoleMessagesSupplier,
                    reloadAction,
                    scheduler,
                    null
            );
            Messages m = messagesSupplier != null ? messagesSupplier.get() : null;
            String description = (m != null) ? m.label("help.description") : "DiscordTowny in-game commands";
            registrar.register(node, description, List.of("discordtowny"));
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

            if (!report.problemKeys().isEmpty()) {
                sender.sendMessage(messages.get("sync.problems-header", Map.of("count", String.valueOf(report.problemKeys().size()))));
                for (String problem : report.problems(messages)) {
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

        if (!report.problemKeys().isEmpty()) {
            sender.sendMessage(messages.get("sync.problems-header", Map.of("count", String.valueOf(report.problemKeys().size()))));
            for (String problem : report.problems(messages)) {
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

    static void clearPendingConfirmationsForTest() {
        pendingDeletes.clear();
        pendingPurges.clear();
        pendingUpdateConfirmations.clear();
    }

    private static String formatSpaceState(SpaceState state, Messages msg) {
        if (state == null) {
            return msg.label("general.unknown");
        }
        return switch (state) {
            case ACTIVE -> msg.label("admin.state-active");
            case ARCHIVED -> msg.label("admin.state-archived");
            case INCONSISTENT -> msg.label("admin.state-inconsistent");
        };
    }

    private static String formatDownloadResult(UpdateService.DownloadResult result, Messages msg) {
        if (result == null) {
            return msg.label("general.unknown");
        }
        return switch (result) {
            case CHECKSUM_MISMATCH -> msg.label("updates.result-checksum-mismatch");
            case NETWORK_ERROR -> msg.label("updates.result-network-error");
            case TOO_LARGE -> msg.label("updates.result-too-large");
            case IO_ERROR -> msg.label("updates.result-io-error");
            case SUCCESS -> msg.label("updates.downloaded");
        };
    }

    private static void discloseFailedCheckIfAny(UpdateService updateService, Messages msg, CommandSender sender) {
        if (updateService == null) return;
        UpdateService.CheckResult lastResult = updateService.getLastCheckResult();
        if (lastResult != null && lastResult.status() == UpdateService.CheckStatus.CHECK_FAILED) {
            String rawError = lastResult.error().orElse(null);
            String reason = DefaultUpdateService.formatCheckFailureReason(rawError, msg);
            sender.sendMessage(msg.get("updates.check-failed", Map.of("reason", reason)));
        } else if (lastResult == null && (updateService.checkStatus() == UpdateService.CheckStatus.CHECK_FAILED || updateService.isLastCheckFailed())) {
            String rawError = updateService.getLastCheckError().orElse(null);
            String reason = DefaultUpdateService.formatCheckFailureReason(rawError, msg);
            sender.sendMessage(msg.get("updates.check-failed", Map.of("reason", reason)));
        }
    }

    static void updateHelpDescription(Supplier<Messages> messagesSupplier) {
        if (messagesSupplier == null) return;
        Messages m = messagesSupplier.get();
        if (m == null) return;
        String desc = m.label("help.description");
        if (desc == null || desc.isBlank()) return;
        try {
            if (Bukkit.getServer() != null && Bukkit.getCommandMap() != null) {
                org.bukkit.command.Command cmd = Bukkit.getCommandMap().getCommand("dt");
                if (cmd != null) {
                    cmd.setDescription(desc);
                }
                org.bukkit.command.Command alias = Bukkit.getCommandMap().getCommand("discordtowny");
                if (alias != null) {
                    alias.setDescription(desc);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static int executeAdminList(
            CommandContext<CommandSourceStack> ctx,
            int requestedPage,
            Supplier<SpaceService> spaceServiceSupplier,
            Supplier<DiscordGateway> discordGatewaySupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Consumer<Runnable> scheduler) {
        CommandSender sender = ctx.getSource().getSender();
        Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);

        SpaceService spaceService = spaceServiceSupplier.get();
        if (spaceService == null) {
            sender.sendMessage(msg.get("general.database-unavailable"));
            return 1;
        }

        spaceService.findAll().thenAccept(spaces -> {
            if (spaces.isEmpty()) {
                scheduler.accept(() -> sender.sendMessage(msg.get("admin.list-empty")));
                return;
            }

            int totalSpaces = spaces.size();
            int totalPages = Math.max(1, (int) Math.ceil((double) totalSpaces / PAGE_SIZE));
            int page = Math.max(1, Math.min(requestedPage, totalPages));
            int start = (page - 1) * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, totalSpaces);
            List<TownSpace> pageSpaces = spaces.subList(start, end);

            DiscordGateway gw = discordGatewaySupplier.get();
            boolean discordAvailable = gw != null && gw.isAvailable();

            List<String> archivedChannelIdsToCheck = new ArrayList<>();
            for (TownSpace s : pageSpaces) {
                if (s.state() == SpaceState.ARCHIVED) {
                    s.textChannelId().ifPresent(archivedChannelIdsToCheck::add);
                    s.voiceChannelId().ifPresent(archivedChannelIdsToCheck::add);
                }
            }

            Set<String> existingResourceIds = null;
            if (discordAvailable && !archivedChannelIdsToCheck.isEmpty()) {
                try {
                    existingResourceIds = gw.existingResourceIds(archivedChannelIdsToCheck);
                } catch (Exception ignored) {
                    // A failed read is not proof of absence
                    existingResourceIds = null;
                }
            }

            String naLabel = msg.label("admin.not-applicable");
            String textLabel = msg.label("admin.channel-text");
            String voiceLabel = msg.label("admin.channel-voice");
            String noneLabel = msg.label("admin.none");
            String deletedLabel = msg.label("admin.channel-deleted");

            List<Component> entryMessages = new ArrayList<>(pageSpaces.size());
            for (TownSpace space : pageSpaces) {
                String residents = naLabel;
                if (discordAvailable && space.roleId().isPresent()) {
                    try {
                        residents = String.valueOf(gw.roleHolders(space.roleId().get()).size());
                    } catch (Exception ignored) {
                        residents = naLabel;
                    }
                }

                String channels;
                if (space.state() == SpaceState.ARCHIVED) {
                    boolean hasText = space.textChannelId().isPresent();
                    boolean hasVoice = space.voiceChannelId().isPresent();
                    if (!hasText && !hasVoice) {
                        channels = noneLabel;
                    } else if (existingResourceIds != null) {
                        boolean textExists = hasText && existingResourceIds.contains(space.textChannelId().get());
                        boolean voiceExists = hasVoice && existingResourceIds.contains(space.voiceChannelId().get());
                        if (textExists && voiceExists) {
                            channels = textLabel + " " + voiceLabel;
                        } else if (textExists) {
                            channels = textLabel;
                        } else if (voiceExists) {
                            channels = voiceLabel;
                        } else {
                            channels = deletedLabel;
                        }
                    } else {
                        // Failed read or Discord unavailable: report database state, never "deleted"
                        if (hasText && hasVoice) {
                            channels = textLabel + " " + voiceLabel;
                        } else if (hasText) {
                            channels = textLabel;
                        } else {
                            channels = voiceLabel;
                        }
                    }
                } else {
                    if (space.textChannelId().isPresent() && space.voiceChannelId().isPresent()) {
                        channels = textLabel + " " + voiceLabel;
                    } else if (space.textChannelId().isPresent()) {
                        channels = textLabel;
                    } else if (space.voiceChannelId().isPresent()) {
                        channels = voiceLabel;
                    } else {
                        channels = noneLabel;
                    }
                }

                String activity = space.lastActivityAt().map(Instant::toString).orElse(noneLabel);

                entryMessages.add(msg.get("admin.list-entry", Map.of(
                        "town", space.townName(),
                        "status", formatSpaceState(space.state(), msg),
                        "channels", channels.trim(),
                        "residents", residents,
                        "activity", activity
                )));
            }

            Component headerMessage = msg.get("admin.list-header", Map.of(
                    "count", String.valueOf(totalSpaces)
            ));

            Component pageMessage = totalPages > 1
                    ? msg.get("admin.list-page", Map.of(
                            "current", String.valueOf(page),
                            "total", String.valueOf(totalPages)))
                    : null;

            Component nextMessage = (totalPages > 1 && page < totalPages)
                    ? msg.get("admin.list-next", Map.of(
                            "next", String.valueOf(page + 1)))
                    : null;

            scheduler.accept(() -> {
                sender.sendMessage(headerMessage);
                for (Component entry : entryMessages) {
                    sender.sendMessage(entry);
                }
                if (pageMessage != null) {
                    sender.sendMessage(pageMessage);
                }
                if (nextMessage != null) {
                    sender.sendMessage(nextMessage);
                }
            });
        }).exceptionally(ex -> {
            scheduler.accept(() -> sender.sendMessage(msg.get("general.database-unavailable")));
            return null;
        });

        return 1;
    }


    public static LiteralArgumentBuilder<CommandSourceStack> createAdminPrefixNode(
            Supplier<SettingsRepository> settingsSupplier,
            Supplier<Consumer<AuditEvent>> auditConsumerSupplier,
            Supplier<SpaceService> spaceServiceSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Consumer<Runnable> scheduler,
            Executor asyncExecutor) {
        LiteralArgumentBuilder<CommandSourceStack> prefix = Commands.literal("prefix");

        // 1. /dt admin prefix (no argument) -> show prefix twice: rendered and raw
        prefix.executes(ctx -> executeShowPrefix(ctx, messagesSupplier, consoleMessagesSupplier));

        // 3. /dt admin prefix reset -> restore catalog prefix
        prefix.then(Commands.literal("reset")
                .executes(ctx -> executeResetPrefix(
                        ctx,
                        settingsSupplier,
                        auditConsumerSupplier,
                        spaceServiceSupplier,
                        messagesSupplier,
                        consoleMessagesSupplier,
                        scheduler,
                        asyncExecutor
                )));

        // 2. /dt admin prefix <texto...> -> greedy string captures the rest of the line
        prefix.then(Commands.argument("texto", StringArgumentType.greedyString())
                .executes(ctx -> executeSetPrefix(
                        ctx,
                        StringArgumentType.getString(ctx, "texto"),
                        settingsSupplier,
                        auditConsumerSupplier,
                        spaceServiceSupplier,
                        messagesSupplier,
                        consoleMessagesSupplier,
                        scheduler,
                        asyncExecutor
                )));

        return prefix;
    }

    private static Consumer<AuditEvent> resolveAuditConsumer(Supplier<Consumer<AuditEvent>> auditConsumerSupplier) {
        if (auditConsumerSupplier == null) {
            return null;
        }
        try {
            return auditConsumerSupplier.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int executeShowPrefix(
            CommandContext<CommandSourceStack> ctx,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier) {
        CommandSender sender = ctx.getSource().getSender();
        Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);
        Messages activeMessages = messagesSupplier != null ? messagesSupplier.get() : msg;

        String rawPrefix = activeMessages != null ? activeMessages.rawPrefix() : "";
        Component renderedPrefix = LegacyComponentSerializer.legacyAmpersand().deserialize(rawPrefix);

        Component renderedBase = msg.get("admin.prefix-rendered");
        Component renderedLine = renderedBase.replaceText(b -> b.matchLiteral("{prefix}").replacement(renderedPrefix));

        Component rawLine = msg.get("admin.prefix-raw", Map.of("raw", rawPrefix));

        sender.sendMessage(renderedLine);
        sender.sendMessage(rawLine);
        return 1;
    }

    private static int executeResetPrefix(
            CommandContext<CommandSourceStack> ctx,
            Supplier<SettingsRepository> settingsSupplier,
            Supplier<Consumer<AuditEvent>> auditConsumerSupplier,
            Supplier<SpaceService> spaceServiceSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Consumer<Runnable> scheduler,
            Executor asyncExecutor) {
        CommandSender sender = ctx.getSource().getSender();
        Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);

        Consumer<AuditEvent> auditConsumer = resolveAuditConsumer(auditConsumerSupplier);
        if (auditConsumer == null) {
            sender.sendMessage(msg.get("admin.prefix-starting"));
            return 1;
        }

        Executor exec = asyncExecutor != null ? asyncExecutor : ForkJoinPool.commonPool();
        CompletableFuture.runAsync(() -> {
            if (resolveAuditConsumer(auditConsumerSupplier) == null) {
                throw new IllegalStateException("Audit sink unavailable during startup");
            }
            SettingsRepository settings = resolveSettings(settingsSupplier);
            if (settings == null) {
                throw new IllegalStateException("Database settings repository unavailable");
            }
            settings.delete(SettingsRepository.KEY_CHAT_PREFIX);
        }, exec).thenRun(() -> {
            try {
                Consumer<AuditEvent> activeAudit = resolveAuditConsumer(auditConsumerSupplier);
                if (activeAudit != null) {
                    String catPrefix = (messagesSupplier != null && messagesSupplier.get() != null)
                            ? messagesSupplier.get().catalogPrefix()
                            : "";
                    activeAudit.accept(new AuditEvent(
                            Instant.now(),
                            AuditEvent.Severity.INFO,
                            sender.getName(),
                            "prefix",
                            catPrefix.isEmpty() ? "reset" : catPrefix,
                            true,
                            Optional.of("reset")
                    ));
                }
                if (messagesSupplier != null && messagesSupplier.get() != null) {
                    messagesSupplier.get().resetPrefix();
                }
                scheduler.accept(() -> sender.sendMessage(msg.get("admin.prefix-reset")));
            } catch (Throwable t) {
                scheduler.accept(() -> sender.sendMessage(msg.get("admin.prefix-saved-incomplete")));
            }
        }).exceptionally(ex -> {
            scheduler.accept(() -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof IllegalStateException && "Audit sink unavailable during startup".equals(cause.getMessage())) {
                    sender.sendMessage(msg.get("admin.prefix-starting"));
                } else {
                    sender.sendMessage(msg.get("general.database-unavailable"));
                }
            });
            return null;
        });

        return 1;
    }

    private static int executeSetPrefix(
            CommandContext<CommandSourceStack> ctx,
            String inputTexto,
            Supplier<SettingsRepository> settingsSupplier,
            Supplier<Consumer<AuditEvent>> auditConsumerSupplier,
            Supplier<SpaceService> spaceServiceSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Consumer<Runnable> scheduler,
            Executor asyncExecutor) {
        CommandSender sender = ctx.getSource().getSender();
        Messages msg = resolveMessages(sender, messagesSupplier, consoleMessagesSupplier);

        String targetPrefix = inputTexto;
        if ("\"\"".equals(targetPrefix) || "''".equals(targetPrefix)) {
            targetPrefix = "";
        }

        // Refusals:
        // 1. Placeholder delimiters
        if (targetPrefix.contains("{") || targetPrefix.contains("}")) {
            sender.sendMessage(msg.get("admin.prefix-placeholders"));
            return 1;
        }

        // 2. Line breaks
        if (targetPrefix.contains("\n") || targetPrefix.contains("\r")) {
            sender.sendMessage(msg.get("admin.prefix-line-break"));
            return 1;
        }

        // 3. Raw cap limit
        if (targetPrefix.length() > MAX_RAW_PREFIX_LENGTH) {
            sender.sendMessage(msg.get("admin.prefix-raw-too-long", Map.of("max", String.valueOf(MAX_RAW_PREFIX_LENGTH))));
            return 1;
        }

        // 4. Visible length limit (counting characters with & codes removed)
        int visLen = visibleLength(targetPrefix);
        if (visLen > MAX_VISIBLE_PREFIX_LENGTH) {
            sender.sendMessage(msg.get("admin.prefix-too-long", Map.of("max", String.valueOf(MAX_VISIBLE_PREFIX_LENGTH))));
            return 1;
        }

        // 5. Startup window: audit sink unavailable
        Consumer<AuditEvent> auditConsumer = resolveAuditConsumer(auditConsumerSupplier);
        if (auditConsumer == null) {
            sender.sendMessage(msg.get("admin.prefix-starting"));
            return 1;
        }

        final String finalPrefix = targetPrefix;
        Executor exec = asyncExecutor != null ? asyncExecutor : ForkJoinPool.commonPool();

        CompletableFuture.runAsync(() -> {
            if (resolveAuditConsumer(auditConsumerSupplier) == null) {
                throw new IllegalStateException("Audit sink unavailable during startup");
            }
            SettingsRepository settings = resolveSettings(settingsSupplier);
            if (settings == null) {
                throw new IllegalStateException("Database settings repository unavailable");
            }
            settings.put(SettingsRepository.KEY_CHAT_PREFIX, finalPrefix);
        }, exec).thenRun(() -> {
            try {
                Consumer<AuditEvent> activeAudit = resolveAuditConsumer(auditConsumerSupplier);
                if (activeAudit != null) {
                    activeAudit.accept(new AuditEvent(
                            Instant.now(),
                            AuditEvent.Severity.INFO,
                            sender.getName(),
                            "prefix",
                            finalPrefix,
                            true,
                            Optional.empty()
                    ));
                }
                if (messagesSupplier != null && messagesSupplier.get() != null) {
                    messagesSupplier.get().setCustomPrefix(finalPrefix);
                }
                scheduler.accept(() -> {
                    Component base = msg.get("admin.prefix-set");
                    Component rendered = LegacyComponentSerializer.legacyAmpersand().deserialize(finalPrefix);
                    Component reply = base.replaceText(b -> b.matchLiteral("{prefix}").replacement(rendered));
                    sender.sendMessage(reply);
                });
            } catch (Throwable t) {
                scheduler.accept(() -> sender.sendMessage(msg.get("admin.prefix-saved-incomplete")));
            }
        }).exceptionally(ex -> {
            scheduler.accept(() -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof IllegalStateException && "Audit sink unavailable during startup".equals(cause.getMessage())) {
                    sender.sendMessage(msg.get("admin.prefix-starting"));
                } else {
                    sender.sendMessage(msg.get("general.database-unavailable"));
                }
            });
            return null;
        });

        return 1;
    }

    public static String stripFormatting(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(input)
        );
    }

    public static int visibleLength(String input) {
        String stripped = stripFormatting(input);
        return stripped.codePointCount(0, stripped.length());
    }

    private static SettingsRepository resolveSettings(
            Supplier<SettingsRepository> settingsSupplier) {
        if (settingsSupplier != null) {
            try {
                SettingsRepository sr = settingsSupplier.get();
                if (sr != null) return sr;
            } catch (Throwable t) {
                if (t instanceof RuntimeException re) throw re;
                throw new RuntimeException(t);
            }
        }
        return null;
    }
}
