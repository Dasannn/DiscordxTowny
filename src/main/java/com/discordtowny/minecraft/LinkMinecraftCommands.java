package com.discordtowny.minecraft;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.link.LinkService;
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
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Registration of in-game commands for account linking.
 *
 * <p>Registered using Brigadier, modern Paper's system.
 * No call to the database blocks the main thread:
 * the player is answered immediately and confirmed when the
 * asynchronous operation completes.
 *
 * <p>Console responses remain in English per Spec 9.1.
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
        return createCommandNode(linkService, config, messages, null, townyFacade, defaultScheduler(), defaultPlayerLookup());
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
            Consumer<Runnable> syncScheduler) {
        return createCommandNode(linkService, config, messages, null, townyFacade, syncScheduler, defaultPlayerLookup());
    }

    /**
     * Builds the Brigadier command tree with explicit console messages support.
     */
    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            Messages consoleMessages,
            TownyFacade townyFacade) {
        return createCommandNode(linkService, config, messages, consoleMessages, townyFacade, defaultScheduler(), defaultPlayerLookup());
    }

    /**
     * Builds the Brigadier command tree with explicit console messages support and scheduler.
     */
    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            Messages consoleMessages,
            TownyFacade townyFacade,
            Consumer<Runnable> syncScheduler) {
        return createCommandNode(linkService, config, messages, consoleMessages, townyFacade, syncScheduler, defaultPlayerLookup());
    }

    /**
     * Builds the Brigadier command tree with injected player lookup function for testability.
     */
    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            Messages consoleMessages,
            TownyFacade townyFacade,
            Consumer<Runnable> syncScheduler,
            Function<String, UUID> playerLookup) {
        Objects.requireNonNull(linkService, "linkService");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(messages, "messages");
        Consumer<Runnable> scheduler = syncScheduler != null ? syncScheduler : defaultScheduler();
        Function<String, UUID> lookup = playerLookup != null ? playerLookup : defaultPlayerLookup();

        return Commands.literal("dt")
                .then(createLinkSubcommand(linkService, config, messages, consoleMessages, scheduler))
                .then(createUnlinkSubcommand(linkService, messages, consoleMessages, scheduler))
                .then(Commands.literal("admin")
                        .requires(source -> source.getSender().hasPermission(MinecraftCommands.PERMISSION_ADMIN))
                        .then(createAdminUnlinkSubcommand(linkService, messages, consoleMessages, townyFacade, scheduler, lookup))
                )
                .build();
    }

    /**
     * Builds the /dt link subcommand.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createLinkSubcommand(
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            Messages consoleMessages,
            Consumer<Runnable> scheduler) {
        return Commands.literal("link")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messages, consoleMessages);
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(msg.get("general.players-only"));
                        return 1;
                    }
                    if (!player.hasPermission(MinecraftCommands.PERMISSION_USE)) {
                        player.sendMessage(msg.get("general.no-permission"));
                        return 1;
                    }

                    // Asynchronous operation off the main thread passing the captured name
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
     * Builds the /dt unlink subcommand.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createUnlinkSubcommand(
            LinkService linkService,
            Messages messages,
            Messages consoleMessages,
            Consumer<Runnable> scheduler) {
        return Commands.literal("unlink")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Messages msg = resolveMessages(sender, messages, consoleMessages);
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(msg.get("general.players-only"));
                        return 1;
                    }
                    if (!player.hasPermission(MinecraftCommands.PERMISSION_USE)) {
                        player.sendMessage(msg.get("general.no-permission"));
                        return 1;
                    }

                    // Asynchronous operation
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
     * Builds the /dt admin unlink <jugador> subcommand with direct instances.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createAdminUnlinkSubcommand(
            LinkService linkService,
            Messages messages,
            Messages consoleMessages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler) {
        return createAdminUnlinkSubcommand(
                () -> linkService,
                () -> messages,
                () -> consoleMessages,
                () -> townyFacade,
                scheduler,
                defaultPlayerLookup()
        );
    }

    /**
     * Builds the /dt admin unlink <jugador> subcommand with direct instances and player lookup.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createAdminUnlinkSubcommand(
            LinkService linkService,
            Messages messages,
            Messages consoleMessages,
            TownyFacade townyFacade,
            Consumer<Runnable> scheduler,
            Function<String, UUID> playerLookup) {
        return createAdminUnlinkSubcommand(
                () -> linkService,
                () -> messages,
                () -> consoleMessages,
                () -> townyFacade,
                scheduler,
                playerLookup
        );
    }

    /**
     * Builds the /dt admin unlink <jugador> subcommand with dynamic suppliers.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createAdminUnlinkSubcommand(
            java.util.function.Supplier<LinkService> linkServiceSupplier,
            java.util.function.Supplier<Messages> messagesSupplier,
            java.util.function.Supplier<Messages> consoleMessagesSupplier,
            java.util.function.Supplier<TownyFacade> townyFacadeSupplier,
            Consumer<Runnable> scheduler) {
        return createAdminUnlinkSubcommand(
                linkServiceSupplier,
                messagesSupplier,
                consoleMessagesSupplier,
                townyFacadeSupplier,
                scheduler,
                defaultPlayerLookup()
        );
    }

    /**
     * Builds the /dt admin unlink <jugador> subcommand with dynamic suppliers and injected player lookup.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> createAdminUnlinkSubcommand(
            java.util.function.Supplier<LinkService> linkServiceSupplier,
            java.util.function.Supplier<Messages> messagesSupplier,
            java.util.function.Supplier<Messages> consoleMessagesSupplier,
            java.util.function.Supplier<TownyFacade> townyFacadeSupplier,
            Consumer<Runnable> scheduler,
            Function<String, UUID> playerLookup) {
        Function<String, UUID> lookup = playerLookup != null ? playerLookup : defaultPlayerLookup();
        return Commands.literal("unlink")
                .then(Commands.argument("jugador", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String remaining = builder.getRemainingLowerCase();
                            try {
                                if (Bukkit.getServer() != null) {
                                    for (Player p : Bukkit.getOnlinePlayers()) {
                                        if (p.getName().toLowerCase().startsWith(remaining)) {
                                            builder.suggest(p.getName());
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            Messages messages = messagesSupplier != null ? messagesSupplier.get() : null;
                            Messages consoleMessages = consoleMessagesSupplier != null ? consoleMessagesSupplier.get() : null;
                            Messages msg = resolveMessages(sender, messages, consoleMessages);

                            LinkService linkService = linkServiceSupplier != null ? linkServiceSupplier.get() : null;
                            if (linkService == null) {
                                sender.sendMessage(msg.get("general.database-unavailable"));
                                return 1;
                            }

                            String targetName = StringArgumentType.getString(ctx, "jugador");
                            TownyFacade townyFacade = townyFacadeSupplier != null ? townyFacadeSupplier.get() : null;
                            UUID targetUuid = resolveTargetUuid(targetName, townyFacade, lookup);
                            if (targetUuid == null) {
                                sender.sendMessage(msg.get("linking.not-linked"));
                                return 1;
                            }

                            linkService.unlink(targetUuid).thenAccept(unlinked -> {
                                scheduler.accept(() -> {
                                    if (unlinked) {
                                        sender.sendMessage(msg.get("admin.unlinked", Map.of(
                                                "player", targetName
                                        )));
                                    } else {
                                        sender.sendMessage(msg.get("linking.not-linked"));
                                    }
                                });
                            }).exceptionally(ex -> {
                                scheduler.accept(() -> sender.sendMessage(msg.get("general.database-unavailable")));
                                return null;
                            });

                            return 1;
                        })
                );
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
        register(plugin, linkService, config, messages, EnglishMessages.bundled(), townyFacade);
    }

    /**
     * Registers commands in Paper's lifecycle manager with separate console messages.
     */
    public static void register(
            Plugin plugin,
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            Messages consoleMessages,
            TownyFacade townyFacade) {
        Consumer<Runnable> scheduler = task -> Bukkit.getScheduler().runTask(plugin, task);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            LiteralCommandNode<CommandSourceStack> node = createCommandNode(
                    linkService, config, messages, consoleMessages, townyFacade, scheduler);
            String description = (messages != null) ? messages.label("help.link-description") : "DiscordTowny linking commands";
            registrar.register(node, description, List.of("discordtowny"));
        });
    }

    private static Messages resolveMessages(CommandSender sender, Messages messages, Messages consoleMessages) {
        if (sender instanceof Player) {
            return messages;
        }
        return consoleMessages != null ? consoleMessages : EnglishMessages.bundled();
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
                LOGGER.warning("[LinkCommands] Could not schedule response: DiscordTowny plugin unavailable or disabled");
            } catch (Throwable t) {
                LOGGER.log(java.util.logging.Level.SEVERE,
                        "[LinkCommands] Error scheduling response on the main scheduler", t);
            }
        };
    }

    private static Function<String, UUID> defaultPlayerLookup() {
        return name -> {
            try {
                if (Bukkit.getServer() != null) {
                    Player online = Bukkit.getPlayerExact(name);
                    if (online != null) {
                        return online.getUniqueId();
                    }
                }
            } catch (Throwable ignored) {}
            return null;
        };
    }

    private static UUID resolveTargetUuid(
            String targetName,
            TownyFacade townyFacade,
            Function<String, UUID> playerLookup) {
        if (playerLookup != null) {
            try {
                UUID uuid = playerLookup.apply(targetName);
                if (uuid != null) {
                    return uuid;
                }
            } catch (Throwable ignored) {}
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
            if (Bukkit.getServer() != null) {
                var offline = Bukkit.getOfflinePlayerIfCached(targetName);
                if (offline != null) {
                    return offline.getUniqueId();
                }
            }
        } catch (Throwable ignored) {
            // Test environments without full Bukkit
        }
        return null;
    }
}
