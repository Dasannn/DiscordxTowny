package com.discordtowny.minecraft;

import com.discordtowny.update.UpdateService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Notifies administrator players upon joining the server if an update is available,
 * downloaded, or if the latest check failed.
 *
 * <p>Execution reads cached check results from {@link UpdateService} without initiating
 * any network requests or blocking the server main thread.
 */
public final class UpdateJoinListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger("DiscordTowny");

    private final Supplier<UpdateService> updateServiceSupplier;
    private final BooleanSupplier degradedSupplier;

    public UpdateJoinListener(Supplier<UpdateService> updateServiceSupplier, BooleanSupplier degradedSupplier) {
        this.updateServiceSupplier = Objects.requireNonNull(updateServiceSupplier, "updateServiceSupplier cannot be null");
        this.degradedSupplier = degradedSupplier != null ? degradedSupplier : () -> false;
    }

    public UpdateJoinListener(Supplier<UpdateService> updateServiceSupplier) {
        this(updateServiceSupplier, () -> false);
    }

    public UpdateJoinListener(UpdateService updateService) {
        this(() -> updateService, () -> false);
    }
    public static void register(Plugin plugin, Supplier<UpdateService> updateServiceSupplier, BooleanSupplier degradedSupplier) {
        Objects.requireNonNull(plugin, "plugin cannot be null");
        Bukkit.getPluginManager().registerEvents(new UpdateJoinListener(updateServiceSupplier, degradedSupplier), plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (!player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)) {
            return;
        }
        if (degradedSupplier.getAsBoolean()) {
            return;
        }
        UpdateService updateService = updateServiceSupplier.get();
        if (updateService == null) {
            return;
        }
        if (!updateService.shouldNotifyAdminsOnJoin()) {
            return;
        }
        try {
            updateService.notifyAdminOnJoin(player::sendMessage);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to notify admin " + player.getName() + " (" + player.getUniqueId() + ") on join: " + e.getMessage(), e);
        }
    }
}
