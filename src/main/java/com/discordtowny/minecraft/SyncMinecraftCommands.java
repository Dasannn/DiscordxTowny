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
        return createCommandNode(syncService, messages, townyFacade, defaultScheduler());
    }

    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            SyncService syncService,
            PluginConfig config,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> syncScheduler) {
        return createCommandNode(syncService, messages, townyFacade, syncScheduler);
    }

    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            SyncService syncService,
            Messages messages,
            TownyFacade townyFacade) {
        return createCommandNode(syncService, messages, townyFacade, defaultScheduler());
    }

    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            SyncService syncService,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> syncScheduler) {
        Objects.requireNonNull(syncService, "syncService cannot be null");
        Objects.requireNonNull(messages, "messages cannot be null");
        Consumer<Runnable> scheduler = syncScheduler != null ? syncScheduler : defaultScheduler();

        LiteralArgumentBuilder<CommandSourceStack> dt = Commands.literal("dt");
        dt.then(createSyncSubcommand(syncService, messages, townyFacade, scheduler));
        dt.then(createAdminNode(syncService, messages, townyFacade, scheduler));
        return dt.build();
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createSyncSubcommand(
            SyncService syncService,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return Commands.literal("sync")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(messages.get("general.players-only"));
                        return 1;
                    }

                    Optional<TownSnapshot> townOpt = getPlayerTown(player.getUniqueId(), townyFacade);
                    if (townOpt.isEmpty()) {
                        player.sendMessage(messages.get("general.not-in-town"));
                        return 1;
                    }

                    TownSnapshot town = townOpt.get();
                    if (!town.isMayor(player.getUniqueId())) {
                        player.sendMessage(messages.get("general.not-mayor"));
                        return 1;
                    }

                    // Respond immediately on the main thread
                    player.sendMessage(messages.get("sync.started"));

                    UUID townUuid = town.uuid();
                    syncService.syncTown(townUuid).thenAccept(report -> {
                        scheduler.accept(() -> player.sendMessage(messages.get("sync.finished")));
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> replyError(player, messages, ex));
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
        return Commands.literal("admin")
                .requires(source -> source.getSender().hasPermission("discordtowny.admin"))
                .then(createAdminSyncSubcommand(syncService, messages, townyFacade, scheduler));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createAdminSyncSubcommand(
            SyncService syncService,
            Messages messages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return Commands.literal("sync")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    sender.sendMessage(messages.get("sync.started"));

                    syncService.reconcileAll().thenAccept(report -> {
                        scheduler.accept(() -> sender.sendMessage(messages.get("sync.finished")));
                    }).exceptionally(ex -> {
                        scheduler.accept(() -> replyError(sender, messages, ex));
                        return null;
                    });

                    return 1;
                })
                .then(Commands.argument("town", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String remaining = builder.getRemainingLowerCase();
                            if (townyFacade != null && townyFacade.isAvailable() && Bukkit.isPrimaryThread()) {
                                try {
                                    for (TownSnapshot t : townyFacade.allTowns()) {
                                        if (t.name().toLowerCase().startsWith(remaining)) {
                                            builder.suggest(t.name());
                                        }
                                    }
                                } catch (Exception ignored) {
                                    // Ignored during suggestion phase
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            String townName = StringArgumentType.getString(ctx, "town");

                            Optional<TownSnapshot> townOpt = getTownByName(townName, townyFacade);
                            if (townOpt.isEmpty()) {
                                sender.sendMessage(messages.get("general.town-not-found", Map.of("town", townName)));
                                return 1;
                            }

                            UUID townUuid = townOpt.get().uuid();
                            sender.sendMessage(messages.get("sync.started"));

                            syncService.syncTown(townUuid).thenAccept(report -> {
                                scheduler.accept(() -> sender.sendMessage(messages.get("sync.finished")));
                            }).exceptionally(ex -> {
                                scheduler.accept(() -> replyError(sender, messages, ex));
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
        if (townyFacade == null || !townyFacade.isAvailable()) {
            return Optional.empty();
        }
        try {
            return townyFacade.townOf(playerUuid);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SyncCommands] Error looking up town for player " + playerUuid, e);
            return Optional.empty();
        }
    }

    private static Optional<TownSnapshot> getTownByName(String name, TownyFacade townyFacade) {
        if (townyFacade == null || !townyFacade.isAvailable()) {
            return Optional.empty();
        }
        try {
            return townyFacade.townByName(name);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SyncCommands] Error looking up town by name '" + name + "'", e);
            return Optional.empty();
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
