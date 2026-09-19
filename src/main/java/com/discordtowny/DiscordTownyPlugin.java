package com.discordtowny;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point.
 *
 * <p>Its sole responsibility is wiring: creating components in order, making
 * them available, and shutting them down in reverse. Logic lives in domain
 * packages, never here.
 *
 * <p>Shutdown must be tolerant of an incomplete startup: if the database
 * failed, Discord never managed to connect and {@code onDisable} still runs.
 */
public final class DiscordTownyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);

        getLogger().info("DiscordTowny " + getPluginMeta().getVersion() + " started.");
    }

    @Override
    public void onDisable() {
        getLogger().info("DiscordTowny stopped.");
    }
}
