package com.discordtowny.minecraft;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.update.DefaultUpdateService;
import com.discordtowny.update.HttpTransport;
import com.discordtowny.update.UpdateService;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
