package com.discordtowny.minecraft;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.link.LinkService;
import com.discordtowny.towny.TownyFacade;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import java.util.UUID;

/**
 * Registration of in-game commands for account linking.
 *
 * <p>Registered using Brigadier, modern Paper's system.
 * No call to the database blocks the main thread:
 * the player is answered immediately and confirmed when the
 * asynchronous operation completes.
 */
public final class LinkMinecraftCommands {

    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger("DiscordTowny");

    private LinkMinecraftCommands() {}

    /**
     * Builds the Brigadier command tree for /dt (and /discordtowny).
     */
    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            TownyFacade townyFacade) {
        return createCommandNode(linkService, config, messages, townyFacade, defaultScheduler());
    }

    /**
     * Builds the Brigadier command tree allowing specification of the scheduler
     * to return to the main thread before interacting with players.
     */
    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            TownyFacade townyFacade,
            java.util.function.Consumer<Runnable> syncScheduler) {
        Objects.requireNonNull(linkService, "linkService");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(messages, "messages");
        java.util.function.Consumer<Runnable> scheduler = syncScheduler != null ? syncScheduler : defaultScheduler();

        return Commands.literal("dt")
                .then(Commands.literal("link")
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            if (!(sender instanceof Player player)) {
                                sender.sendMessage(messages.get("general.players-only"));
                                return 1;
                            }

                            // Respond immediately on the main thread
                            player.sendMessage(messages.get("general.working"));

                            // Asynchronous operation off the main thread passing the captured name
                            linkService.generateCode(player.getUniqueId(), player.getName()).thenAccept(optCode -> {
                                scheduler.accept(() -> {
                                    if (optCode.isEmpty()) {
                                        player.sendMessage(messages.get("linking.already-linked"));
                                    } else {
                                        long minutes = config.linking().codeExpiry().toMinutes();
                                        player.sendMessage(messages.get("linking.code-generated", Map.of(
                                                "code", optCode.get(),
                                                "minutes", String.valueOf(minutes)
                                        )));
                                    }
                                });
                            }).exceptionally(ex -> {
                                scheduler.accept(() -> player.sendMessage(messages.get("general.database-unavailable")));
                                return null;
                            });

                            return 1;
                        })
                )
                .then(Commands.literal("unlink")
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            if (!(sender instanceof Player player)) {
                                sender.sendMessage(messages.get("general.players-only"));
                                return 1;
                            }

                            // Respond immediately
                            player.sendMessage(messages.get("general.working"));

                            // Asynchronous operation
                            linkService.unlink(player.getUniqueId()).thenAccept(unlinked -> {
                                scheduler.accept(() -> {
                                    if (unlinked) {
                                        player.sendMessage(messages.get("linking.unlink-success"));
                                    } else {
                                        player.sendMessage(messages.get("linking.not-linked"));
                                    }
                                });
                            }).exceptionally(ex -> {
                                scheduler.accept(() -> player.sendMessage(messages.get("general.database-unavailable")));
                                return null;
                            });

                            return 1;
                        })
                )
                .then(Commands.literal("admin")
                        .requires(source -> source.getSender().hasPermission("discordtowny.admin"))
                        .then(Commands.literal("unlink")
                                .then(Commands.argument("jugador", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            String remaining = builder.getRemainingLowerCase();
                                            for (Player p : Bukkit.getOnlinePlayers()) {
                                                if (p.getName().toLowerCase().startsWith(remaining)) {
                                                    builder.suggest(p.getName());
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            CommandSender sender = ctx.getSource().getSender();
                                            String targetName = StringArgumentType.getString(ctx, "jugador");

                                            UUID targetUuid = resolveTargetUuid(targetName, townyFacade);
                                            if (targetUuid == null) {
                                                sender.sendMessage(messages.get("linking.not-linked"));
                                                return 1;
                                            }

                                            sender.sendMessage(messages.get("general.working"));

                                            linkService.unlink(targetUuid).thenAccept(unlinked -> {
                                                scheduler.accept(() -> {
                                                    if (unlinked) {
                                                        sender.sendMessage(messages.get("admin.unlinked", Map.of(
                                                                "player", targetName
                                                        )));
                                                    } else {
                                                        sender.sendMessage(messages.get("linking.not-linked"));
                                                    }
                                                });
                                            }).exceptionally(ex -> {
                                                scheduler.accept(() -> sender.sendMessage(messages.get("general.database-unavailable")));
                                                return null;
                                            });

                                            return 1;
                                        })
                                )
                        )
                )
                .build();
    }

    /**
     * Registers commands in Paper's lifecycle manager.
     */
    public static void register(
            Plugin plugin,
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            TownyFacade townyFacade) {
        java.util.function.Consumer<Runnable> scheduler = task -> Bukkit.getScheduler().runTask(plugin, task);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            LiteralCommandNode<CommandSourceStack> node = createCommandNode(
                    linkService, config, messages, townyFacade, scheduler);
            registrar.register(node, "DiscordTowny linking commands", List.of("discordtowny"));
        });
    }

    private static java.util.function.Consumer<Runnable> defaultScheduler() {
        return task -> {
            try {
                if (Bukkit.getServer() != null) {
                    Plugin plugin = Bukkit.getPluginManager().getPlugin("DiscordTowny");
                    if (plugin != null && plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, task);
                        return;
                    }
                }
                LOGGER.warning("[LinkCommands] Could not schedule response: DiscordTowny plugin unavailable or disabled");
            } catch (Throwable t) {
                LOGGER.log(java.util.logging.Level.SEVERE,
                        "[LinkCommands] Error scheduling response on the main scheduler", t);
            }
        };
    }

    private static UUID resolveTargetUuid(String targetName, TownyFacade townyFacade) {
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            return online.getUniqueId();
        }
        if (townyFacade != null && townyFacade.isAvailable()) {
            try {
                var resident = townyFacade.residentByName(targetName);
                if (resident.isPresent()) {
                    return resident.get().uuid();
                }
            } catch (Exception e) {
                LOGGER.warning("[LinkCommands] Error looking up resident in Towny for '"
                        + targetName + "': " + e.getMessage());
            }
        }
        try {
            var offline = Bukkit.getOfflinePlayerIfCached(targetName);
            if (offline != null) {
                return offline.getUniqueId();
            }
        } catch (Exception ignored) {
            // Test environments without full Bukkit
        }
        return null;
    }
}
