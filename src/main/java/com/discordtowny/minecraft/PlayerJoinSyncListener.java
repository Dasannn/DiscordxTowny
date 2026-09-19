package com.discordtowny.minecraft;

import com.discordtowny.storage.LinkRepository;
import com.discordtowny.sync.SyncService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reconciles Discord roles and refreshes stored display name on player join.
 *
 * <p>The main thread captures the player UUID and current username.
 * Database name refresh and Discord role synchronization are performed asynchronously
 * off the server main thread.
 */
public final class PlayerJoinSyncListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger("DiscordTowny");

    private final SyncService syncService;
    private final LinkRepository linkRepository;
    private final Executor executor;

    public PlayerJoinSyncListener(SyncService syncService, LinkRepository linkRepository) {
        this(syncService, linkRepository, ForkJoinPool.commonPool());
    }

    public PlayerJoinSyncListener(SyncService syncService, LinkRepository linkRepository, Executor executor) {
        this.syncService = Objects.requireNonNull(syncService, "syncService cannot be null");
        this.linkRepository = Objects.requireNonNull(linkRepository, "linkRepository cannot be null");
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
    }

    public static void register(Plugin plugin, SyncService syncService, LinkRepository linkRepository) {
        register(plugin, syncService, linkRepository, task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    public static void register(Plugin plugin, SyncService syncService, LinkRepository linkRepository, Executor executor) {
        Objects.requireNonNull(plugin, "plugin cannot be null");
        Bukkit.getPluginManager().registerEvents(new PlayerJoinSyncListener(syncService, linkRepository, executor), plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        CompletableFuture.runAsync(() -> {
            try {
                linkRepository.updateLastKnownName(playerUuid, playerName);
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "[PlayerJoinSync] Failed to update last known name for " + playerUuid, ex);
            }
        }, executor).thenCompose(v -> syncService.syncPlayer(playerUuid))
        .exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "[PlayerJoinSync] Failed to sync player " + playerUuid + " on join", ex);
            return null;
        });
    }
}
