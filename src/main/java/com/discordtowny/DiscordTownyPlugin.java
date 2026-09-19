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
        // The message files are written by the config loader, which knows there
        // is one per language. Doing it here too would mean naming them twice.

        getLogger().info("DiscordTowny " + getPluginMeta().getVersion() + " started.");
    }

    @Override
    public void onDisable() {
        getLogger().info("DiscordTowny stopped.");
    }
}
