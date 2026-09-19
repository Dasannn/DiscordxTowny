package com.discordtowny.minecraft;

import com.discordtowny.config.PluginConfig;
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
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reconciles Discord roles and refreshes stored display name on player join.
 *
 * <p>The main thread captures the player UUID and current username.
 * Database name refresh and Discord role synchronization are performed asynchronously
 * off the server main thread.
 *
 * <p>In report mode, no writes or mutations are performed against the database.
 */
public final class PlayerJoinSyncListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger("DiscordTowny");

    private final SyncService syncService;
    private final Supplier<SyncService> syncServiceSupplier;
    private final LinkRepository linkRepository;
    private final Supplier<PluginConfig> configSupplier;
    private final Executor executor;

    public PlayerJoinSyncListener(SyncService syncService, LinkRepository linkRepository) {
        this(syncService, linkRepository, (Supplier<PluginConfig>) null, ForkJoinPool.commonPool());
    }

    public PlayerJoinSyncListener(SyncService syncService, LinkRepository linkRepository, Executor executor) {
        this(syncService, linkRepository, (Supplier<PluginConfig>) null, executor);
    }

    public PlayerJoinSyncListener(SyncService syncService, LinkRepository linkRepository, PluginConfig config) {
        this(syncService, linkRepository, config != null ? () -> config : null, ForkJoinPool.commonPool());
    }

    public PlayerJoinSyncListener(SyncService syncService, LinkRepository linkRepository, PluginConfig config, Executor executor) {
        this(syncService, linkRepository, config != null ? () -> config : null, executor);
    }

    public PlayerJoinSyncListener(SyncService syncService, LinkRepository linkRepository, Supplier<PluginConfig> configSupplier, Executor executor) {
        this.syncService = Objects.requireNonNull(syncService, "syncService cannot be null");
        this.syncServiceSupplier = () -> this.syncService;
        this.linkRepository = Objects.requireNonNull(linkRepository, "linkRepository cannot be null");
        this.configSupplier = configSupplier != null ? configSupplier : () -> null;
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
    }

    public PlayerJoinSyncListener(Supplier<SyncService> syncServiceSupplier, LinkRepository linkRepository, Supplier<PluginConfig> configSupplier, Executor executor) {
        this.syncServiceSupplier = Objects.requireNonNull(syncServiceSupplier, "syncServiceSupplier cannot be null");
        this.syncService = null;
        this.linkRepository = Objects.requireNonNull(linkRepository, "linkRepository cannot be null");
        this.configSupplier = configSupplier != null ? configSupplier : () -> null;
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
    }

    public static void register(Plugin plugin, SyncService syncService, LinkRepository linkRepository) {
        register(plugin, syncService, linkRepository, (Supplier<PluginConfig>) null, task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    public static void register(Plugin plugin, SyncService syncService, LinkRepository linkRepository, PluginConfig config) {
        register(plugin, syncService, linkRepository, config != null ? () -> config : null, task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    public static void register(Plugin plugin, SyncService syncService, LinkRepository linkRepository, Executor executor) {
        register(plugin, syncService, linkRepository, (Supplier<PluginConfig>) null, executor);
    }

    public static void register(Plugin plugin, SyncService syncService, LinkRepository linkRepository, PluginConfig config, Executor executor) {
        register(plugin, syncService, linkRepository, config != null ? () -> config : null, executor);
    }

    public static void register(Plugin plugin, SyncService syncService, LinkRepository linkRepository, Supplier<PluginConfig> configSupplier, Executor executor) {
        register(plugin, () -> syncService, linkRepository, configSupplier, executor);
    }

    public static void register(Plugin plugin, Supplier<SyncService> syncServiceSupplier, LinkRepository linkRepository, Supplier<PluginConfig> configSupplier, Executor executor) {
        Objects.requireNonNull(plugin, "plugin cannot be null");
        Bukkit.getPluginManager().registerEvents(new PlayerJoinSyncListener(syncServiceSupplier, linkRepository, configSupplier, executor), plugin);
    }

    public static void register(Plugin plugin, Supplier<SyncService> syncServiceSupplier, LinkRepository linkRepository, Supplier<PluginConfig> configSupplier) {
        register(plugin, syncServiceSupplier, linkRepository, configSupplier, task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        SyncService service = syncServiceSupplier != null ? syncServiceSupplier.get() : syncService;
        if (service == null) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            if (!isReportMode()) {
                try {
                    linkRepository.updateLastKnownName(playerUuid, playerName);
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "[PlayerJoinSync] Failed to update last known name for " + playerUuid, ex);
                }
            }
        }, executor).thenCompose(v -> service.syncPlayer(playerUuid))
        .exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "[PlayerJoinSync] Failed to sync player " + playerUuid + " on join", ex);
            return null;
        });
    }

    private boolean isReportMode() {
        PluginConfig cfg = configSupplier.get();
        return cfg != null && cfg.sync() != null && cfg.sync().mode() == PluginConfig.Sync.Mode.REPORT;
    }
}
