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
 * Registro de comandos del juego para la vinculacion de cuentas.
 *
 * <p>Registrados mediante Brigadier, el sistema de Paper moderno.
 * Ninguna llamada a la base de datos bloquea el hilo principal:
 * se responde de inmediato al jugador y se confirma cuando la operacion
 * asincrona termina.
 */
public final class LinkMinecraftCommands {

    private LinkMinecraftCommands() {}

    /**
     * Construye el arbol de comandos de Brigadier para /dt (y /discordtowny).
     */
    public static LiteralCommandNode<CommandSourceStack> createCommandNode(
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            TownyFacade townyFacade) {
        return createCommandNode(linkService, config, messages, townyFacade, defaultScheduler());
    }

    /**
     * Construye el arbol de comandos de Brigadier permitiendo especificar el scheduler
     * para retornar al hilo principal antes de interactuar con jugadores.
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

                            // Responder de inmediato en el hilo principal
                            player.sendMessage(messages.get("general.working"));

                            // Operacion asincrona fuera del hilo principal pasando el nombre capturado
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

                            // Responder de inmediato
                            player.sendMessage(messages.get("general.working"));

                            // Operacion asincrona
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
     * Registra los comandos en el gestor de ciclo de vida de Paper.
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
            registrar.register(node, "Comandos de vinculacion de DiscordTowny", List.of("discordtowny"));
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
            } catch (Throwable ignored) {
                // Entornos de prueba sin Bukkit en ejecucion
            }
            task.run();
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
            } catch (Exception ignored) {
                // Si no se esta en hilo principal o Towny falla
            }
        }
        try {
            var offline = Bukkit.getOfflinePlayerIfCached(targetName);
            if (offline != null) {
                return offline.getUniqueId();
            }
        } catch (Exception ignored) {
            // Entornos de prueba sin Bukkit completo
        }
        return null;
    }
}
