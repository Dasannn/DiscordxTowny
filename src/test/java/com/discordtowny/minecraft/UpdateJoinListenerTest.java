package com.discordtowny.minecraft;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.update.DefaultUpdateService;
import com.discordtowny.update.HttpTransport;
import com.discordtowny.update.UpdateService;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying {@link UpdateJoinListener}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>An administrator who joins receives the update notice from the service.</li>
 *   <li>A player without {@code discordtowny.admin} receives nothing on join.</li>
 *   <li>When {@code notify-admins-on-join} is disabled, nothing is sent even for administrators.</li>
 *   <li>When the update service is null or the plugin is degraded, nothing is sent and no warning logs are emitted.</li>
 *   <li>Player join never initiates an update check or network request.</li>
 * </ul>
 */
class UpdateJoinListenerTest {

    private final List<LogRecord> logRecords = new ArrayList<>();
    private Handler logHandler;
    private Logger logger;

    @BeforeEach
    void setUpLogger() {
        logRecords.clear();
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() throws SecurityException {}
        };
        logger = Logger.getLogger("DiscordTowny");
        logger.addHandler(logHandler);
    }

    @AfterEach
    void tearDownLogger() {
        if (logger != null && logHandler != null) {
            logger.removeHandler(logHandler);
        }
    }

    private static PluginConfig createTestConfig() {
        return new PluginConfig(
                new PluginConfig.Discord("token", "123456789012345678", Optional.empty()),
                new PluginConfig.Database(PluginConfig.Database.Type.SQLITE, "localhost", 3306, "db", "", "", "dt_", 1, 1, Duration.ofSeconds(5)),
                new PluginConfig.Structure("Cat", "Arch", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Alcalde", "{town}", Optional.empty(), false),
                new PluginConfig.Limits(200, 2, Duration.ofSeconds(60)),
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                new PluginConfig.Sync(Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPAIR, 20, Duration.ofSeconds(5)),
                new PluginConfig.Linking(Duration.ofMinutes(10), 3, Duration.ofMinutes(15), true),
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(true, Duration.ofHours(12), true, true),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );
    }

    private void assertNoWarningsLogged() {
        List<LogRecord> warnings = logRecords.stream()
                .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .toList();
        assertTrue(warnings.isEmpty(), () -> "Expected no warning-level logs, but got: "
                + warnings.stream().map(LogRecord::getMessage).toList());
    }

    @Test
    @DisplayName("Admin joins, service has update: notice reaches that player")
    void adminJoinsWithUpdateAvailable_noticeReachesPlayer() {
        Player player = mock(Player.class);
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(true);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        UpdateService updateService = mock(UpdateService.class);
        when(updateService.shouldNotifyAdminsOnJoin()).thenReturn(true);
        doAnswer(invocation -> {
            Consumer<String> sender = invocation.getArgument(0);
            sender.accept("[DT] A new version is available: 1.1.0");
            return null;
        }).when(updateService).notifyAdminOnJoin(any());

        UpdateJoinListener listener = new UpdateJoinListener(() -> updateService, () -> false);
        listener.onPlayerJoin(event);

        verify(updateService, times(1)).notifyAdminOnJoin(any());
        verify(player, times(1)).sendMessage("[DT] A new version is available: 1.1.0");
    }

    @Test
    @DisplayName("Admin joins, service has multiple notices: all notices reach player")
    void adminJoinsWithMultipleNotices_allNoticesReachPlayer() {
        Player player = mock(Player.class);
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(true);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        RecordingUpdateService standIn = new RecordingUpdateService();
        standIn.notices.add("[DT] A new version is available: 2.0.0");
        standIn.notices.add("[DT] Version 2.0.0 contains breaking changes.");

        UpdateJoinListener listener = new UpdateJoinListener(standIn);
        listener.onPlayerJoin(event);

        assertTrue(standIn.notifyCalled.get());
        verify(player, times(1)).sendMessage("[DT] A new version is available: 2.0.0");
        verify(player, times(1)).sendMessage("[DT] Version 2.0.0 contains breaking changes.");
    }

    @Test
    @DisplayName("Player without discordtowny.admin joins: nothing is sent")
    void nonAdminJoins_nothingIsSent() {
        Player player = mock(Player.class);
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(false);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        UpdateService updateService = mock(UpdateService.class);
        when(updateService.shouldNotifyAdminsOnJoin()).thenReturn(true);

        UpdateJoinListener listener = new UpdateJoinListener(() -> updateService, () -> false);
        listener.onPlayerJoin(event);

        verify(player, never()).sendMessage(anyString());
        verify(updateService, never()).notifyAdminOnJoin(any());
    }

    @Test
    @DisplayName("Config switch is off: nothing is sent, even for an admin")
    void configSwitchOff_nothingIsSentEvenForAdmin() {
        Player player = mock(Player.class);
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(true);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        UpdateService updateService = mock(UpdateService.class);
        when(updateService.shouldNotifyAdminsOnJoin()).thenReturn(false);

        UpdateJoinListener listener = new UpdateJoinListener(() -> updateService, () -> false);
        listener.onPlayerJoin(event);

        verify(player, never()).sendMessage(anyString());
        verify(updateService, never()).notifyAdminOnJoin(any());
    }

    @Test
    @DisplayName("Update service is null: nothing is sent and nothing is logged at warning level")
    void updateServiceNull_nothingSentAndNoWarningLogged() {
        Player player = mock(Player.class);
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(true);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        UpdateJoinListener listener = new UpdateJoinListener(() -> null, () -> false);
        listener.onPlayerJoin(event);

        verify(player, never()).sendMessage(anyString());
        assertNoWarningsLogged();
    }

    @Test
    @DisplayName("Plugin is degraded: nothing is sent and nothing is logged at warning level")
    void pluginDegraded_nothingSentAndNoWarningLogged() {
        Player player = mock(Player.class);
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(true);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        UpdateService updateService = mock(UpdateService.class);
        when(updateService.shouldNotifyAdminsOnJoin()).thenReturn(true);

        UpdateJoinListener listener = new UpdateJoinListener(() -> updateService, () -> true);
        listener.onPlayerJoin(event);

        verify(player, never()).sendMessage(anyString());
        verify(updateService, never()).notifyAdminOnJoin(any());
        assertNoWarningsLogged();
    }

    @Test
    @DisplayName("Join does not start a check: stand-in records zero check requests")
    void joinDoesNotStartACheck_standInRecordsZeroChecks() {
        Player player = mock(Player.class);
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(true);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        RecordingUpdateService standIn = new RecordingUpdateService();
        standIn.notices.add("[DT] Update available: 1.0.1");

        UpdateJoinListener listener = new UpdateJoinListener(standIn);
        listener.onPlayerJoin(event);

        assertTrue(standIn.notifyCalled.get(), "notifyAdminOnJoin must have been called");
        assertEquals(0, standIn.checkRequests.get(), "Player join must NEVER request an update check");
    }

    @Test
    @DisplayName("Integration with DefaultUpdateService sends cached update notice on join without initiating check")
    void integrationWithDefaultUpdateService_sendsCachedNoticeWithoutCheck(@TempDir Path tempDir) {
        Player player = mock(Player.class);
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(true);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        AtomicInteger transportCalls = new AtomicInteger(0);
        String releaseJson = """
                {
                  "tag_name": "v1.5.0",
                  "body": "SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "assets": [{"name": "DiscordTowny-1.5.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.5.0/DiscordTowny-1.5.0.jar"}]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) -> {
            transportCalls.incrementAndGet();
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                tempDir.resolve("update"),
                tempDir.resolve("active.jar"),
                "DiscordTowny.jar",
                transport,
                Logger.getLogger("test"),
                eventItem -> {},
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // Pre-populate cached result via initial check
        service.checkForUpdate().join();
        assertEquals(1, transportCalls.get(), "Initial check made 1 transport call");

        // Now admin joins
        UpdateJoinListener listener = new UpdateJoinListener(service);
        listener.onPlayerJoin(event);

        // Verify: notice was sent to player and NO additional transport calls were made
        assertEquals(1, transportCalls.get(), "Join must not have triggered any additional transport calls");
        verify(player, atLeastOnce()).sendMessage(argThat((String s) -> s != null && s.contains("1.5.0")));
    }

    @Test
    @DisplayName("Null event or null player safely ignored without error")
    void nullEventOrPlayer_safelyIgnored() {
        RecordingUpdateService standIn = new RecordingUpdateService();
        UpdateJoinListener listener = new UpdateJoinListener(standIn);

        assertDoesNotThrow(() -> listener.onPlayerJoin(null));

        PlayerJoinEvent eventWithNullPlayer = mock(PlayerJoinEvent.class);
        when(eventWithNullPlayer.getPlayer()).thenReturn(null);
        assertDoesNotThrow(() -> listener.onPlayerJoin(eventWithNullPlayer));

        assertFalse(standIn.notifyCalled.get());
        assertEquals(0, standIn.checkRequests.get());
    }

    @Test
    @DisplayName("Lazy supplier reflects changes to UpdateService after listener construction")
    void lazySupplierReflectsChangesToUpdateService() {
        Player player = mock(Player.class);
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(true);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        AtomicReference<UpdateService> holder = new AtomicReference<>(null);
        UpdateJoinListener listener = new UpdateJoinListener(holder::get);

        // Initially null: nothing sent
        listener.onPlayerJoin(event);
        verify(player, never()).sendMessage(anyString());

        // Later wired: notice sent
        RecordingUpdateService standIn = new RecordingUpdateService();
        standIn.notices.add("[DT] Update available");
        holder.set(standIn);

        listener.onPlayerJoin(event);
        verify(player, times(1)).sendMessage("[DT] Update available");
    }

    @Test
    @DisplayName("F2: Join path performs no filesystem access (stand-in throws if isUpdatePending is called)")
    void joinPathPerformsNoFilesystemAccess_standInThrowsIfDiskChecked() {
        Player player = mock(Player.class);
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(true);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        UpdateService standIn = new UpdateService() {
            @Override
            public CompletableFuture<CheckResult> checkForUpdate() {
                throw new AssertionError("checkForUpdate called on join thread!");
            }

            @Override
            public CompletableFuture<DownloadResult> download(Release release) {
                throw new AssertionError("download called on join thread!");
            }

            @Override
            public boolean isUpdatePending() {
                throw new AssertionError("F2 violation: isUpdatePending() touched filesystem on join thread!");
            }

            @Override
            public String currentVersion() {
                return "1.0.0";
            }

            @Override
            public Optional<Release> getAvailableUpdate() {
                return Optional.empty();
            }

            @Override
            public boolean isBreaking(Release release) {
                return false;
            }

            @Override
            public boolean isAwaitingConfirmation() {
                return false;
            }

            @Override
            public boolean shouldNotifyAdminsOnJoin() {
                return true;
            }

            @Override
            public void notifyAdminOnJoin(Consumer<String> messageSender) {
                // Relies strictly on cached in-memory state; does NOT touch isUpdatePending()
                messageSender.accept("[DT] Version 1.1.0 is downloaded and ready.");
            }

            @Override
            public void stop() {}
        };

        UpdateJoinListener listener = new UpdateJoinListener(standIn);
        assertDoesNotThrow(() -> listener.onPlayerJoin(event), "Join must not fail");
        verify(player, times(1)).sendMessage("[DT] Version 1.1.0 is downloaded and ready.");
    }

    @Test
    @DisplayName("F2: DefaultUpdateService notifyAdminOnJoin reads cached state without touching disk on join")
    void defaultUpdateService_notifyAdminOnJoinReadsCachedStateWithoutDisk(@TempDir Path tempDir) throws Exception {
        Player player = mock(Player.class);
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(true);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        Path updateFolder = tempDir.resolve("update");
        Path activeJar = tempDir.resolve("active.jar");
        Files.createDirectories(updateFolder);
        Files.writeString(activeJar, "CURRENT");

        // Write a real staged jar so constructor seeds stagedUpdatePending to true
        Path stagedJar = updateFolder.resolve("DiscordTowny.jar");
        Files.writeString(stagedJar, "STAGED_CONTENT");

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                Logger.getLogger("test"),
                eventItem -> {}
        );

        // Delete the staged jar from disk before player joins.
        // If notifyAdminOnJoin accessed the filesystem, it would find no jar and send nothing.
        // Reading strictly from cached in-memory state delivers the notice.
        Files.delete(stagedJar);
        assertFalse(Files.exists(stagedJar), "Staged jar must no longer exist on disk");

        UpdateJoinListener listener = new UpdateJoinListener(service);
        assertDoesNotThrow(() -> listener.onPlayerJoin(event), "Join must not trigger filesystem access");
        verify(player, times(1)).sendMessage(argThat((String s) -> s != null && s.contains("downloaded")));
    }

    @Test
    @DisplayName("F3: Failed notice logs warning with player name and failure, does not break join")
    void failedNoticeLogsWarningWithPlayerAndFailure_doesNotBreakJoin() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("AdminBob");
        UUID playerUuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(true);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        UpdateService service = mock(UpdateService.class);
        when(service.shouldNotifyAdminsOnJoin()).thenReturn(true);
        doThrow(new RuntimeException("Simulated chat packet delivery error")).when(service).notifyAdminOnJoin(any());

        UpdateJoinListener listener = new UpdateJoinListener(service);
        assertDoesNotThrow(() -> listener.onPlayerJoin(event), "Failed notice must never throw out of onPlayerJoin");

        List<LogRecord> warnings = logRecords.stream()
                .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .toList();
        assertEquals(1, warnings.size(), "Exactly 1 warning must be logged on notification failure");
        LogRecord record = warnings.get(0);
        assertTrue(record.getMessage().contains("AdminBob"), "Log message must name the player");
        assertTrue(record.getMessage().contains(playerUuid.toString()), "Log message must include player UUID");
        assertTrue(record.getMessage().contains("Simulated chat packet delivery error"), "Log message must describe failure");
        assertEquals(Level.WARNING, record.getLevel(), "Log level must be WARNING");
    }

    @Test
    @DisplayName("F3: Fatal JVM errors (subclasses of Error) propagate out of onPlayerJoin")
    void fatalJvmErrorPropagatesOut() {
        Player player = mock(Player.class);
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(true);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        UpdateService service = mock(UpdateService.class);
        when(service.shouldNotifyAdminsOnJoin()).thenReturn(true);
        doAnswer(inv -> { throw new OutOfMemoryError("Simulated OOM"); }).when(service).notifyAdminOnJoin(any());

        UpdateJoinListener listener = new UpdateJoinListener(service);
        assertThrows(OutOfMemoryError.class, () -> listener.onPlayerJoin(event),
                "Fatal JVM errors must NOT be swallowed by catch(Exception)");
    }

    @Test
    @DisplayName("F1: Lifecycle: degraded start, recovery registers listener, second recovery does not duplicate registration")
    void lifecycle_degradedStartThenRecoveryRegistersOnce_secondRecoveryDoesNotDuplicate() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("AdminPlayer");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission(MinecraftCommands.PERMISSION_ADMIN)).thenReturn(true);

        PlayerJoinEvent joinEvent = mock(PlayerJoinEvent.class);
        when(joinEvent.getPlayer()).thenReturn(player);

        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(pluginManager);

        List<UpdateJoinListener> registeredListeners = new ArrayList<>();
        doAnswer(invocation -> {
            org.bukkit.event.Listener listener = invocation.getArgument(0);
            if (listener instanceof UpdateJoinListener ujl) {
                registeredListeners.add(ujl);
            }
            return null;
        }).when(pluginManager).registerEvents(any(UpdateJoinListener.class), eq(plugin));

        try (var mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getServer).thenReturn(server);
            mockedBukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

            // Lifecycle flag held where the decision to register lives (as in DiscordTownyPlugin)
            AtomicBoolean updateListenerRegistered = new AtomicBoolean(false);
            AtomicBoolean degraded = new AtomicBoolean(true);
            AtomicReference<UpdateService> updateServiceRef = new AtomicReference<>(null);

            // Registration action guarded by the lifecycle flag
            Runnable registerCallback = () -> {
                if (!degraded.get() && updateServiceRef.get() != null) {
                    if (updateListenerRegistered.compareAndSet(false, true)) {
                        UpdateJoinListener.register(plugin, updateServiceRef::get, degraded::get);
                    }
                }
            };

            // 1. Degraded start: degraded = true, service = null
            registerCallback.run();
            assertEquals(0, registeredListeners.size(), "No listener registered during degraded start");

            // Player joins: 0 listeners -> nothing sent
            for (UpdateJoinListener l : registeredListeners) {
                l.onPlayerJoin(joinEvent);
            }
            verify(player, never()).sendMessage(anyString());

            // 2. Recovery: storage healthy, update service available
            degraded.set(false);
            RecordingUpdateService updateService = new RecordingUpdateService();
            updateService.notices.add("[DT] Update available: v1.1.0");
            updateServiceRef.set(updateService);

            registerCallback.run();
            assertEquals(1, registeredListeners.size(), "Listener registered exactly once upon recovery");
            verify(pluginManager, times(1)).registerEvents(any(UpdateJoinListener.class), eq(plugin));

            // Player joins: notice arrives!
            for (UpdateJoinListener l : registeredListeners) {
                l.onPlayerJoin(joinEvent);
            }
            verify(player, times(1)).sendMessage("[DT] Update available: v1.1.0");

            // 3. Second recovery / reload: register callback invoked again
            registerCallback.run();
            assertEquals(1, registeredListeners.size(), "Second recovery must not duplicate registration");
            verify(pluginManager, times(1)).registerEvents(any(UpdateJoinListener.class), eq(plugin));

            // Player joins again: notice arrives once for this join (cumulative 2 sends across 2 joins)
            for (UpdateJoinListener l : registeredListeners) {
                l.onPlayerJoin(joinEvent);
            }
            verify(player, times(2)).sendMessage("[DT] Update available: v1.1.0");
        }
    }

    static class RecordingUpdateService implements UpdateService {
        final AtomicInteger checkRequests = new AtomicInteger(0);
        final AtomicBoolean notifyCalled = new AtomicBoolean(false);
        boolean shouldNotify = true;
        final List<String> notices = new ArrayList<>();

        @Override
        public CompletableFuture<CheckResult> checkForUpdate() {
            checkRequests.incrementAndGet();
            return CompletableFuture.completedFuture(CheckResult.upToDate());
        }

        @Override
        public CompletableFuture<DownloadResult> download(Release release) {
            return CompletableFuture.completedFuture(DownloadResult.SUCCESS);
        }

        @Override
        public boolean isUpdatePending() {
            return false;
        }

        @Override
        public String currentVersion() {
            return "1.0.0";
        }

        @Override
        public Optional<Release> getAvailableUpdate() {
            return Optional.empty();
        }

        @Override
        public boolean isBreaking(Release release) {
            return false;
        }

        @Override
        public boolean isAwaitingConfirmation() {
            return false;
        }

        @Override
        public boolean shouldNotifyAdminsOnJoin() {
            return shouldNotify;
        }

        @Override
        public void notifyAdminOnJoin(Consumer<String> messageSender) {
            notifyCalled.set(true);
            for (String notice : notices) {
                messageSender.accept(notice);
            }
        }

        @Override
        public void stop() {}
    }
}
