package com.discordtowny.minecraft;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.storage.LinkRepository;
import com.discordtowny.sync.SyncService;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PlayerJoinSyncListener}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Player join refreshes the stored display name in {@link LinkRepository}.</li>
 *   <li>Player join triggers player synchronization via {@link SyncService}.</li>
 *   <li>Execution is off the main thread and never blocks the main thread.</li>
 *   <li>Failure in updating display name does not prevent synchronization.</li>
 *   <li>In report mode, zero writes are made to the database.</li>
 * </ul>
 */
class PlayerJoinSyncListenerTest {

    private SyncService syncService;
    private LinkRepository linkRepository;

    @BeforeEach
    void setUp() {
        syncService = mock(SyncService.class);
        linkRepository = mock(LinkRepository.class);

        when(syncService.syncPlayer(any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private static PluginConfig createConfig(PluginConfig.Sync.Mode mode) {
        return new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty()),
                new PluginConfig.Database(PluginConfig.Database.Type.SQLITE, "localhost", 3306, "db", "", "", "dt_", 1, 1, Duration.ofSeconds(5)),
                new PluginConfig.Structure("Cat", "Arch", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Alcalde", "{town}", Optional.empty(), false),
                new PluginConfig.Limits(200, 2, Duration.ofSeconds(60)),
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                new PluginConfig.Sync(Duration.ofMinutes(30), mode, 20, Duration.ofSeconds(5)),
                new PluginConfig.Linking(Duration.ofMinutes(10), 3, Duration.ofMinutes(15), true),
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );
    }

    @Test
    void playerJoinRefreshesStoredDisplayNameInLinkRepository() {
        Executor directExecutor = Runnable::run;
        PlayerJoinSyncListener listener = new PlayerJoinSyncListener(syncService, linkRepository, directExecutor);

        UUID playerUuid = UUID.randomUUID();
        String playerName = "SteveTheGreat";

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn(playerName);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerJoin(event);

        verify(linkRepository, times(1)).updateLastKnownName(playerUuid, playerName);
    }

    @Test
    void playerJoinTriggersPlayerSynchronization() {
        Executor directExecutor = Runnable::run;
        PlayerJoinSyncListener listener = new PlayerJoinSyncListener(syncService, linkRepository, directExecutor);

        UUID playerUuid = UUID.randomUUID();
        String playerName = "Steve";

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn(playerName);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerJoin(event);

        verify(syncService, times(1)).syncPlayer(playerUuid);
    }

    @Test
    void playerJoinDoesNotBlockMainThreadWhileRefreshingName() throws InterruptedException {
        CountDownLatch startDbLatch = new CountDownLatch(1);
        CountDownLatch finishDbLatch = new CountDownLatch(1);
        AtomicBoolean dbCompleted = new AtomicBoolean(false);

        doAnswer(invocation -> {
            startDbLatch.countDown();
            finishDbLatch.await(2, TimeUnit.SECONDS);
            dbCompleted.set(true);
            return null;
        }).when(linkRepository).updateLastKnownName(any(), any());

        // Use a background async executor
        Executor asyncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        PlayerJoinSyncListener listener = new PlayerJoinSyncListener(syncService, linkRepository, asyncExecutor);

        UUID playerUuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("AsyncPlayer");

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        // Prove main thread returns immediately even while DB is waiting on latch
        assertTimeoutPreemptively(Duration.ofMillis(200), () -> {
            listener.onPlayerJoin(event);
        });

        // Wait until async task started
        assertTrue(startDbLatch.await(1, TimeUnit.SECONDS), "Async DB task must have started in background");
        assertFalse(dbCompleted.get(), "DB task must still be running in background while main thread already returned");

        // Release latch to let task finish
        finishDbLatch.countDown();
    }

    @Test
    void playerJoinStillSyncsIfUpdateLastKnownNameFails() {
        Executor directExecutor = Runnable::run;
        PlayerJoinSyncListener listener = new PlayerJoinSyncListener(syncService, linkRepository, directExecutor);

        UUID playerUuid = UUID.randomUUID();
        doThrow(new RuntimeException("Database error")).when(linkRepository).updateLastKnownName(any(), any());

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("ErrorPlayer");

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerJoin(event);

        // Even though updateLastKnownName threw, syncPlayer must still be called
        verify(syncService, times(1)).syncPlayer(playerUuid);
    }

    @Test
    void playerJoinInReportModePerformsZeroWritesToLinkRepository() {
        PluginConfig reportConfig = createConfig(PluginConfig.Sync.Mode.REPORT);
        Executor directExecutor = Runnable::run;
        PlayerJoinSyncListener listener = new PlayerJoinSyncListener(syncService, linkRepository, reportConfig, directExecutor);

        UUID playerUuid = UUID.randomUUID();
        String playerName = "SteveReport";

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn(playerName);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerJoin(event);

        // Prove zero writes to LinkRepository in report mode
        verify(linkRepository, never()).updateLastKnownName(any(), any());
        verifyNoInteractions(linkRepository);

        // Verify syncPlayer was still triggered for reconciliation
        verify(syncService, times(1)).syncPlayer(playerUuid);
    }

    @Test
    void playerJoinInRepairModeUpdatesLastKnownNameAndSyncs() {
        PluginConfig repairConfig = createConfig(PluginConfig.Sync.Mode.REPAIR);
        Executor directExecutor = Runnable::run;
        PlayerJoinSyncListener listener = new PlayerJoinSyncListener(syncService, linkRepository, repairConfig, directExecutor);

        UUID playerUuid = UUID.randomUUID();
        String playerName = "SteveRepair";

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn(playerName);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerJoin(event);

        verify(linkRepository, times(1)).updateLastKnownName(playerUuid, playerName);
        verify(syncService, times(1)).syncPlayer(playerUuid);
    }
}
