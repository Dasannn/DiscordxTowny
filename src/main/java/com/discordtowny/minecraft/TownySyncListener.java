package com.discordtowny.minecraft;

import com.discordtowny.space.SpaceService;
import com.discordtowny.sync.SyncService;
import com.palmergames.bukkit.towny.event.DeleteTownEvent;
import com.palmergames.bukkit.towny.event.RenameTownEvent;
import com.palmergames.bukkit.towny.event.TownAddResidentEvent;
import com.palmergames.bukkit.towny.event.town.TownKickEvent;
import com.palmergames.bukkit.towny.event.town.TownLeaveEvent;
import com.palmergames.bukkit.towny.event.town.TownMayorChangeEvent;
import com.palmergames.bukkit.towny.event.town.TownMayorChangedEvent;
import com.palmergames.bukkit.towny.event.town.TownRuinedEvent;
import com.palmergames.bukkit.towny.object.Resident;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Towny event listeners for synchronization and space lifecycle.
 *
 * <p>Follows the project threading rules:
 * <ul>
 *   <li>Reads the Towny event on the main thread and extracts plain values.</li>
 *   <li>Never hands live Towny objects to the asynchronous services.</li>
 *   <li>Never blocks the main thread waiting on a future or doing I/O.</li>
 *   <li>Town deletion and ruin lead to space archiving, never deletion.</li>
 * </ul>
 */
public final class TownySyncListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger("DiscordTowny");

    private final SyncService syncService;
    private final SpaceService spaceService;

    public TownySyncListener(SyncService syncService, SpaceService spaceService) {
        this.syncService = Objects.requireNonNull(syncService, "syncService cannot be null");
        this.spaceService = Objects.requireNonNull(spaceService, "spaceService cannot be null");
    }

    public static void register(Plugin plugin, SyncService syncService, SpaceService spaceService) {
        Objects.requireNonNull(plugin, "plugin cannot be null");
        Bukkit.getPluginManager().registerEvents(new TownySyncListener(syncService, spaceService), plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidentJoin(TownAddResidentEvent event) {
        if (event.getResident() != null) {
            handleResidentJoin(event.getResident().getUUID());
        }
    }

    void handleResidentJoin(UUID residentUuid) {
        if (residentUuid == null) {
            return;
        }
        syncService.syncPlayer(residentUuid).exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "[TownySyncListener] Failed to sync resident join for " + residentUuid, ex);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidentLeave(TownLeaveEvent event) {
        if (event.getResident() != null) {
            handleResidentLeave(event.getResident().getUUID());
        }
    }

    void handleResidentLeave(UUID residentUuid) {
        if (residentUuid == null) {
            return;
        }
        syncService.syncPlayer(residentUuid).exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "[TownySyncListener] Failed to sync resident leave for " + residentUuid, ex);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidentKick(TownKickEvent event) {
        if (event.getKickedResident() != null) {
            handleResidentKick(event.getKickedResident().getUUID());
        }
    }

    void handleResidentKick(UUID residentUuid) {
        if (residentUuid == null) {
            return;
        }
        syncService.syncPlayer(residentUuid).exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "[TownySyncListener] Failed to sync resident kick for " + residentUuid, ex);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMayorChange(TownMayorChangeEvent event) {
        UUID oldUuid = event.getOldMayor() != null ? event.getOldMayor().getUUID() : null;
        UUID newUuid = event.getNewMayor() != null ? event.getNewMayor().getUUID() : null;
        handleMayorChange(oldUuid, newUuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMayorChanged(TownMayorChangedEvent event) {
        UUID oldUuid = event.getOldMayor() != null ? event.getOldMayor().getUUID() : null;
        UUID newUuid = event.getNewMayor() != null ? event.getNewMayor().getUUID() : null;
        handleMayorChange(oldUuid, newUuid);
    }

    void handleMayorChange(UUID oldMayorUuid, UUID newMayorUuid) {
        if (oldMayorUuid != null) {
            syncService.syncPlayer(oldMayorUuid).exceptionally(ex -> {
                LOGGER.log(Level.WARNING, "[TownySyncListener] Failed to sync old mayor " + oldMayorUuid, ex);
                return null;
            });
        }
        if (newMayorUuid != null) {
            syncService.syncPlayer(newMayorUuid).exceptionally(ex -> {
                LOGGER.log(Level.WARNING, "[TownySyncListener] Failed to sync new mayor " + newMayorUuid, ex);
                return null;
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTownRename(RenameTownEvent event) {
        if (event.getTown() != null) {
            handleTownRename(event.getTown().getUUID(), event.getTown().getName());
        }
    }

    void handleTownRename(UUID townUuid, String newName) {
        if (townUuid == null || newName == null) {
            return;
        }
        spaceService.rename(townUuid, newName).exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "[TownySyncListener] Failed to rename town " + townUuid + " to " + newName, ex);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTownDelete(DeleteTownEvent event) {
        handleTownDelete(event.getTownUUID());
    }

    void handleTownDelete(UUID townUuid) {
        if (townUuid == null) {
            return;
        }
        spaceService.archive(townUuid, "town_deleted").exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "[TownySyncListener] Failed to archive deleted town " + townUuid, ex);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTownRuined(TownRuinedEvent event) {
        if (event.getTown() != null) {
            handleTownRuined(event.getTown().getUUID());
        }
    }

    void handleTownRuined(UUID townUuid) {
        if (townUuid == null) {
            return;
        }
        spaceService.archive(townUuid, "town_ruined").exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "[TownySyncListener] Failed to archive ruined town " + townUuid, ex);
            return null;
        });
    }
}
