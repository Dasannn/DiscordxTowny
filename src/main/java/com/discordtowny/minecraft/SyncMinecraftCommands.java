package com.discordtowny.minecraft;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.model.TownSnapshot;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registration of in-game commands for synchronization and reconciliation:
 * {@code /dt sync} and {@code /dt admin sync [town]}.
 *
 * <p>Registered using Paper's modern Brigadier command system.
 * Main thread reads Towny state, delegates the heavy work asynchronously,
 * and schedules the player-facing reply back onto the main thread.
 */
public final class SyncMinecraftCommands {

    private static final Logger LOGGER = Logger.getLogger("DiscordTowny");

    private SyncMinecraftCommands() {}

    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            SyncService syncService,
            PluginConfig config,
            Messages messages,
            TownyFacade townyFacade) {
        return createCommandNode(syncService, config, messages, townyFacade, defaultScheduler());
    }

    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            SyncService syncService,
            PluginConfig config,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> syncScheduler) {
        return createCommandNode(syncService, config != null ? () -> config : null, messages, townyFacade, syncScheduler);
    }

    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            SyncService syncService,
            Messages messages,
            TownyFacade townyFacade) {
        return createCommandNode(syncService, (Supplier<PluginConfig>) null, messages, townyFacade, defaultScheduler());
    }

    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            SyncService syncService,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> syncScheduler) {
        return createCommandNode(syncService, (Supplier<PluginConfig>) null, messages, townyFacade, syncScheduler);
    }

    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            SyncService syncService,
            Supplier<PluginConfig> configSupplier,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> syncScheduler) {
        return createCommandNode(syncService, configSupplier, messages, null, townyFacade, syncScheduler);
    }

    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            SyncService syncService,
            Supplier<PluginConfig> configSupplier,
            Messages messages,
            Messages consoleMessages,
            TownyFacade townyFacade,
            Consumer<Runnable> syncScheduler) {
        Objects.requireNonNull(syncService, "syncService cannot be null");
        Objects.requireNonNull(messages, "messages cannot be null");
        Consumer<Runnable> scheduler = syncScheduler != null ? syncScheduler : defaultScheduler();

        LiteralArgumentBuilder<CommandSourceStack> dt = Commands.literal("dt");
        dt.then(createSyncSubcommand(syncService, configSupplier, messages, consoleMessages, townyFacade, scheduler));
        dt.then(createAdminNode(syncService, configSupplier, messages, consoleMessages, townyFacade, scheduler));
        return dt.build();
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createSyncSubcommand(
            SyncService syncService,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return createSyncSubcommand(syncService, (Supplier<PluginConfig>) null, messages, null, townyFacade, scheduler);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createSyncSubcommand(
            SyncService syncService,
            PluginConfig config,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return createSyncSubcommand(syncService, config != null ? () -> config : null, messages, null, townyFacade, scheduler);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createSyncSubcommand(
            SyncService syncService,
            Supplier<PluginConfig> configSupplier,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return createSyncSubcommand(syncService, configSupplier, messages, null, townyFacade, scheduler);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createSyncSubcommand(
            SyncService syncService,
            Supplier<PluginConfig> configSupplier,
            Messages messages,
            Messages consoleMessages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return Commands.literal("sync")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = (sender instanceof Player || consoleMessages == null) ? messages : consoleMessages;
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(msg.get("general.players-only"));
                        return 1;
                    }

                    Optional<TownSnapshot> townOpt = getPlayerTown(player.getUniqueId(), townyFacade);
                    if (townOpt.isEmpty()) {
                        player.sendMessage(msg.get("general.not-in-town"));
                        return 1;
                    }

                    TownSnapshot town = townOpt.get();
                    if (!town.isMayor(player.getUniqueId())) {
                        player.sendMessage(msg.get("general.not-mayor"));
                        return 1;
                    }

                    // Respond immediately on the main thread
                    player.sendMessage(msg.get("sync.started"));

                    UUID townUuid = town.uuid();
                    syncService.syncTown(townUuid).thenAccept(report -> {
                        scheduler.accept(() -> replyReport(player, report, isReportMode(configSupplier), msg));
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> replyError(player, msg, ex));
                        return null;
                    });

                    return 1;
                });
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createAdminNode(
            SyncService syncService,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return createAdminNode(syncService, (Supplier<PluginConfig>) null, messages, null, townyFacade, scheduler);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createAdminNode(
            SyncService syncService,
            PluginConfig config,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return createAdminNode(syncService, config != null ? () -> config : null, messages, null, townyFacade, scheduler);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createAdminNode(
            SyncService syncService,
            Supplier<PluginConfig> configSupplier,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return createAdminNode(syncService, configSupplier, messages, null, townyFacade, scheduler);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createAdminNode(
            SyncService syncService,
            Supplier<PluginConfig> configSupplier,
            Messages messages,
            Messages consoleMessages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return Commands.literal("admin")
                .requires(source -> source.getSender().hasPermission("discordtowny.admin"))
                .then(createAdminSyncSubcommand(syncService, configSupplier, messages, consoleMessages, townyFacade, scheduler));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createAdminSyncSubcommand(
            SyncService syncService,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return createAdminSyncSubcommand(syncService, (Supplier<PluginConfig>) null, messages, null, townyFacade, scheduler);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createAdminSyncSubcommand(
            SyncService syncService,
            PluginConfig config,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return createAdminSyncSubcommand(syncService, config != null ? () -> config : null, messages, null, townyFacade, scheduler);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createAdminSyncSubcommand(
            SyncService syncService,
            Supplier<PluginConfig> configSupplier,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return createAdminSyncSubcommand(syncService, configSupplier, messages, null, townyFacade, scheduler);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createAdminSyncSubcommand(
            SyncService syncService,
            Supplier<PluginConfig> configSupplier,
            Messages messages,
            Messages consoleMessages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return createAdminSyncSubcommand(
                () -> syncService,
                configSupplier,
                () -> messages,
                () -> consoleMessages,
                () -> townyFacade,
                scheduler
        );
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createAdminSyncSubcommand(
            Supplier<SyncService> syncServiceSupplier,
            Supplier<PluginConfig> configSupplier,
            Supplier<Messages> messagesSupplier,
            Supplier<Messages> consoleMessagesSupplier,
            Supplier<TownyFacade> townyFacadeSupplier,
            Consumer<Runnable> scheduler) {
        return Commands.literal("sync")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages messages = messagesSupplier != null ? messagesSupplier.get() : null;
                    Messages consoleMessages = consoleMessagesSupplier != null ? consoleMessagesSupplier.get() : null;
                    Messages msg = (sender instanceof Player || consoleMessages == null) ? messages : consoleMessages;

                    SyncService syncService = syncServiceSupplier != null ? syncServiceSupplier.get() : null;
                    if (syncService == null) {
                        sender.sendMessage(msg.get("general.database-unavailable"));
                        return 1;
                    }

                    sender.sendMessage(msg.get("sync.started"));

                    syncService.reconcileAll().thenAccept(report -> {
                        scheduler.accept(() -> replyReport(sender, report, isReportMode(configSupplier), msg));
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> replyError(sender, msg, ex));
                        return null;
                    });

                    return 1;
                })
                .then(Commands.argument("town", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String remaining = builder.getRemainingLowerCase();
                            try {
                                TownyFacade tf = townyFacadeSupplier != null ? townyFacadeSupplier.get() : null;
                                if (Bukkit.getServer() != null && Bukkit.isPrimaryThread() && tf != null && tf.isAvailable()) {
                                    for (TownSnapshot t : tf.allTowns()) {
                                        if (t.name().toLowerCase().startsWith(remaining)) {
                                            builder.suggest(t.name());
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                                // Ignored during suggestion phase
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            Messages messages = messagesSupplier != null ? messagesSupplier.get() : null;
                            Messages consoleMessages = consoleMessagesSupplier != null ? consoleMessagesSupplier.get() : null;
                            Messages msg = (sender instanceof Player || consoleMessages == null) ? messages : consoleMessages;

                            SyncService syncService = syncServiceSupplier != null ? syncServiceSupplier.get() : null;
                            if (syncService == null) {
                                sender.sendMessage(msg.get("general.database-unavailable"));
                                return 1;
                            }

                            TownyFacade tf = townyFacadeSupplier != null ? townyFacadeSupplier.get() : null;
                            String townName = StringArgumentType.getString(ctx, "town");

                            Optional<TownSnapshot> townOpt = getTownByName(townName, tf);
                            if (townOpt.isEmpty()) {
                                sender.sendMessage(msg.get("general.town-not-found", Map.of("town", townName)));
                                return 1;
                            }

                            UUID townUuid = townOpt.get().uuid();
                            sender.sendMessage(msg.get("sync.started"));

                            syncService.syncTown(townUuid).thenAccept(report -> {
                                scheduler.accept(() -> replyReport(sender, report, isReportMode(configSupplier), msg));
                            }).exceptionally(ex -> {
                                scheduler.accept(() -> replyError(sender, msg, ex));
                                return null;
                            });

                            return 1;
                        })
                );
    }

    public static void register(
            Plugin plugin,
            SyncService syncService,
            Messages messages,
            TownyFacade townyFacade) {
        register(plugin, syncService, null, messages, townyFacade);
    }

    public static void register(
            Plugin plugin,
            SyncService syncService,
            PluginConfig config,
            Messages messages,
            TownyFacade townyFacade) {
        Consumer<Runnable> scheduler = task -> Bukkit.getScheduler().runTask(plugin, task);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            LiteralCommandNode<CommandSourceStack> node = createCommandNode(
                    syncService, config, messages, townyFacade, scheduler);
            registrar.register(node, "DiscordTowny synchronization commands", List.of("discordtowny"));
        });
    }

    private static Optional<TownSnapshot> getPlayerTown(UUID playerUuid, TownyFacade townyFacade) {
        if (townyFacade == null) {
            return Optional.empty();
        }
        try {
            if (!townyFacade.isAvailable()) {
                return Optional.empty();
            }
            return townyFacade.townOf(playerUuid);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SyncCommands] Error looking up town for player " + playerUuid, e);
            return Optional.empty();
        }
    }

    private static Optional<TownSnapshot> getTownByName(String name, TownyFacade townyFacade) {
        if (townyFacade == null) {
            return Optional.empty();
        }
        try {
            if (!townyFacade.isAvailable()) {
                return Optional.empty();
            }
            return townyFacade.townByName(name);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SyncCommands] Error looking up town by name '" + name + "'", e);
            return Optional.empty();
        }
    }

    private static boolean isReportMode(Supplier<PluginConfig> configSupplier) {
        if (configSupplier == null) {
            return false;
        }
        PluginConfig config = configSupplier.get();
        return config != null && config.sync() != null && config.sync().mode() == PluginConfig.Sync.Mode.REPORT;
    }

    private static void replyReport(
            CommandSender sender,
            SyncService.SyncReport report,
            boolean isReportMode,
            Messages messages) {
        if (report == null) {
            sender.sendMessage(messages.get("sync.finished"));
            return;
        }

        if (isReportMode) {
            replyReportMode(sender, report, messages);
            return;
        }

        if (!report.problems().isEmpty() || report.inconsistenciesFound() > report.inconsistenciesRepaired()) {
            replyFailures(sender, report, messages);
            return;
        }

        sender.sendMessage(messages.get("sync.finished"));
    }

    private static void replyReportMode(CommandSender sender, SyncService.SyncReport report, Messages messages) {
        if (report.inconsistenciesFound() > 0) {
            sender.sendMessage(messages.get("sync.report-found", Map.of(
                    "count", String.valueOf(report.inconsistenciesFound())
            )));
            if (report.rolesGranted() > 0 || report.rolesRevoked() > 0) {
                sender.sendMessage(messages.get("sync.report-pending", Map.of(
                        "granted", String.valueOf(report.rolesGranted()),
                        "revoked", String.valueOf(report.rolesRevoked())
                )));
            }
        } else {
            sender.sendMessage(messages.get("sync.report-clean", Map.of(
                    "spaces", String.valueOf(report.spacesChecked())
            )));
        }

        if (!report.problems().isEmpty()) {
            sender.sendMessage(messages.get("sync.problems-header", Map.of(
                    "count", String.valueOf(report.problems().size())
            )));
            for (String problem : report.problems()) {
                sender.sendMessage(messages.get("sync.problem-entry", Map.of(
                        "problem", problem
                )));
            }
        }
    }

    private static void replyFailures(CommandSender sender, SyncService.SyncReport report, Messages messages) {
        if (report.inconsistenciesRepaired() > 0 || report.rolesGranted() > 0 || report.rolesRevoked() > 0) {
            sender.sendMessage(messages.get("sync.repaired", Map.of(
                    "count", String.valueOf(report.inconsistenciesRepaired()),
                    "granted", String.valueOf(report.rolesGranted()),
                    "revoked", String.valueOf(report.rolesRevoked())
            )));
        }

        if (!report.problems().isEmpty()) {
            sender.sendMessage(messages.get("sync.problems-header", Map.of(
                    "count", String.valueOf(report.problems().size())
            )));
            for (String problem : report.problems()) {
                sender.sendMessage(messages.get("sync.problem-entry", Map.of(
                        "problem", problem
                )));
            }
        } else if (report.inconsistenciesFound() > report.inconsistenciesRepaired()) {
            int unhandled = report.inconsistenciesFound() - report.inconsistenciesRepaired();
            sender.sendMessage(messages.get("sync.unrepaired", Map.of(
                    "count", String.valueOf(unhandled)
            )));
        }
    }

    private static void replyError(CommandSender sender, Messages messages, Throwable ex) {
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
                LOGGER.warning("[SyncCommands] Could not schedule response: DiscordTowny plugin unavailable or disabled");
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "[SyncCommands] Error scheduling response on the main scheduler", t);
            }
        };
    }
}
