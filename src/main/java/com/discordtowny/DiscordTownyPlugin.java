package com.discordtowny;

import com.discordtowny.minecraft.MinecraftCommands;
import com.discordtowny.minecraft.PlayerJoinSyncListener;
import com.discordtowny.minecraft.TownySyncListener;
import com.discordtowny.minecraft.UpdateJoinListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.RejectedExecutionException;

/**
 * Plugin entry point and Paper adapter.
 *
 * <p>Component lifecycle orchestration and wiring are delegated to {@link DiscordTownyWiring},
 * a plain Java class testable without a live Paper server.
 */
public final class DiscordTownyPlugin extends JavaPlugin {

    private DiscordTownyWiring wiring;
    private final java.util.concurrent.atomic.AtomicBoolean syncListenersRegistered = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean updateListenerRegistered = new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();

        java.nio.file.Path updateFolder = null;
        try {
            if (getServer() != null && getServer().getUpdateFolderFile() != null) {
                updateFolder = getServer().getUpdateFolderFile().toPath();
            }
        } catch (Throwable ignored) {}

        wiring = new DiscordTownyWiring(
                getDataFolder().toPath(),
                updateFolder,
                getLogger(),
                runnable -> {
                    if (!isEnabled() || Bukkit.getServer() == null) {
                        throw new RejectedExecutionException("DiscordTowny is disabled");
                    }
                    try {
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (!isEnabled()) {
                                throw new RejectedExecutionException("DiscordTowny was disabled before task could run");
                            }
                            runnable.run();
                        });
                    } catch (Throwable t) {
                        throw new RejectedExecutionException("Failed to schedule task on server thread", t);
                    }
                },
                (task, interval) -> {
                    long ticks = Math.max(1L, interval.toSeconds() * 20L);
                    BukkitTask bt = Bukkit.getScheduler().runTaskTimerAsynchronously(this, task, ticks, ticks);
                    return bt::cancel;
                },
                () -> {
                    if (Bukkit.getServer() != null) {
                        Bukkit.getScheduler().cancelTasks(this);
                    }
                },
                this::registerListeners,
                getPluginMeta().getVersion()
        );

        try {
            MinecraftCommands.register(
                    this,
                    () -> wiring != null ? wiring.getConfig() : null,
                    () -> wiring != null ? wiring.getMessages() : null,
                    () -> wiring != null ? wiring.getConsoleMessages() : null,
                    () -> wiring != null ? wiring.getLinkService() : null,
                    () -> wiring != null ? wiring.getSpaceService() : null,
                    () -> wiring != null ? wiring.getSyncService() : null,
                    () -> wiring != null ? wiring.getTownyFacade() : null,
                    () -> wiring != null ? wiring.getDiscordGateway() : null,
                    () -> wiring != null ? wiring.getUpdateService() : null,
                    () -> { if (wiring != null) wiring.reload(); }
            );
        } catch (Throwable t) {
            getLogger().severe("Failed to register in-game commands: " + t.getMessage());
        }

        wiring.start();
    }

    void registerListeners(DiscordTownyWiring w) {
        if (w != null && !w.isDegraded() && w.getStorage() != null) {
            try {
                if (syncListenersRegistered.compareAndSet(false, true)) {
                    PlayerJoinSyncListener.register(this, w::getSyncService, w.getStorage().links(), w::getConfig);
                    TownySyncListener.register(this, w::getSyncService, w::getSpaceService);
                }
                if (updateListenerRegistered.compareAndSet(false, true)) {
                    UpdateJoinListener.register(this, w::getUpdateService, w::isDegraded);
                }
            } catch (Throwable t) {
                getLogger().warning("Failed to register listeners: " + t.getMessage());
            }
        }
    }

    @Override
    public void onDisable() {
        if (wiring != null) {
            wiring.stop();
            wiring = null;
        }
        syncListenersRegistered.set(false);
        updateListenerRegistered.set(false);
    }

    boolean isUpdateListenerRegistered() {
        return updateListenerRegistered.get();
    }

    boolean isSyncListenersRegistered() {
        return syncListenersRegistered.get();
    }

    public void reloadPlugin() {
        if (wiring != null) {
            wiring.reload();
        }
    }

    public DiscordTownyWiring getWiring() {
        return wiring;
    }

    // Accessors delegating to wiring for diagnostics
    public com.discordtowny.storage.Storage getStorage() {
        return wiring != null ? wiring.getStorage() : null;
    }

    public com.discordtowny.discord.JdaDiscordGateway getDiscordGateway() {
        return wiring != null ? wiring.getDiscordGateway() : null;
    }

    public com.discordtowny.space.SpaceService getSpaceService() {
        return wiring != null ? wiring.getSpaceService() : null;
    }

    public com.discordtowny.sync.SyncService getSyncService() {
        return wiring != null ? wiring.getSyncService() : null;
    }

    public com.discordtowny.link.LinkService getLinkService() {
        return wiring != null ? wiring.getLinkService() : null;
    }

    public com.discordtowny.towny.TownyFacade getTownyFacade() {
        return wiring != null ? wiring.getTownyFacade() : null;
    }

    public com.discordtowny.sync.PeriodicSyncJob getPeriodicSyncJob() {
        return wiring != null ? wiring.getPeriodicSyncJob() : null;
    }

    public boolean isDegraded() {
        return wiring != null && wiring.isDegraded();
    }

    public com.discordtowny.update.UpdateService getUpdateService() {
        return wiring != null ? wiring.getUpdateService() : null;
    }

    public com.discordtowny.discord.LinkSlashCommands getLinkSlashCommands() {
        return wiring != null ? wiring.getLinkSlashCommands() : null;
    }

    public com.discordtowny.discord.TownySlashCommands getTownySlashCommands() {
        return wiring != null ? wiring.getTownySlashCommands() : null;
    }
}
