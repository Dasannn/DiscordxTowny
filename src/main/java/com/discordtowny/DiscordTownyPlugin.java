package com.discordtowny;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Punto de entrada del plugin.
 *
 * <p>Su unica responsabilidad es el cableado: crear los componentes en orden,
 * dejarlos disponibles y apagarlos al reves. La logica vive en los paquetes de
 * dominio, nunca aqui.
 *
 * <p>El apagado debe ser tolerante a un arranque incompleto: si la base de
 * datos fallo, Discord nunca llego a conectarse y {@code onDisable} igualmente
 * se ejecuta.
 */
public final class DiscordTownyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);

        getLogger().info("DiscordTowny " + getPluginMeta().getVersion() + " iniciado.");
    }

    @Override
    public void onDisable() {
        getLogger().info("DiscordTowny detenido.");
    }
}
