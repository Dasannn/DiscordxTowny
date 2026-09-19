package com.discordtowny;

import com.discordtowny.minecraft.MinecraftCommands;
import com.discordtowny.minecraft.PlayerJoinSyncListener;
import com.discordtowny.minecraft.TownySyncListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Plugin entry point and Paper adapter.
 *
 * <p>Component lifecycle orchestration and wiring are delegated to {@link DiscordTownyWiring},
 * a plain Java class testable without a live Paper server.
 */
public final class DiscordTownyPlugin extends JavaPlugin {

    private DiscordTownyWiring wiring;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        wiring = new DiscordTownyWiring(
                getDataFolder().toPath(),
                getLogger(),
                runnable -> {
                    if (isEnabled() && Bukkit.getServer() != null) {
                        Bukkit.getScheduler().runTask(this, runnable);
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
                w -> {
                    if (!w.isDegraded() && w.getStorage() != null) {
                        try {
                            PlayerJoinSyncListener.register(this, w.getSyncService(), w.getStorage().links(), w.getConfig());
                            TownySyncListener.register(this, w.getSyncService(), w.getSpaceService());
                        } catch (Throwable t) {
                            getLogger().warning("Failed to register sync listeners: " + t.getMessage());
                        }
                    }
                    try {
                        MinecraftCommands.register(
                                this,
                                w::getConfig,
                                w::getMessages,
                                w::getConsoleMessages,
                                w::getLinkService,
                                w::getSpaceService,
                                w::getSyncService,
                                w::getTownyFacade,
                                w::getDiscordGateway,
                                w::reload
                        );
                    } catch (Throwable t) {
                        getLogger().severe("Failed to register in-game commands: " + t.getMessage());
                    }
                },
                getPluginMeta().getVersion()
        );

        wiring.start();
    }

    @Override
    public void onDisable() {
        if (wiring != null) {
            wiring.stop();
            wiring = null;
        }
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
}
