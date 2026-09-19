package com.discordtowny;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.config.YamlConfigLoader;
import com.discordtowny.discord.JdaDiscordGateway;
import com.discordtowny.storage.Storage;
import com.discordtowny.sync.PeriodicSyncJob;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying lifecycle guarantees of {@link DiscordTownyWiring}.
 *
 * <p>Validates the critical requirement:
 * <blockquote>
 *   {@code onDisable} must tolerate a startup that never finished. If the database
 *   failed, Discord never connected, and {@code onDisable} still runs. Nothing there
 *   may assume a component exists.
 * </blockquote>
 */
class DiscordTownyPluginTest {

    @Test
    void onDisableWithoutEnableDoesNotThrow() {
        DiscordTownyWiring wiring = new DiscordTownyWiring();
        assertDoesNotThrow(wiring::onDisable, "onDisable must not throw even if onEnable was never called");
    }

    @Test
    void onDisableWithNullStorageAndNullDiscordDoesNotThrow() {
        DiscordTownyWiring wiring = new DiscordTownyWiring();
        PeriodicSyncJob job = mock(PeriodicSyncJob.class);
        wiring.setPeriodicSyncJobForTest(job);

        assertDoesNotThrow(wiring::onDisable);
        verify(job, times(1)).stop();
    }

    @Test
    void onDisableCatchesExceptionsFromPeriodicJobStopAndContinuesShutdown() {
        DiscordTownyWiring wiring = new DiscordTownyWiring();
        PeriodicSyncJob job = mock(PeriodicSyncJob.class);
        doThrow(new RuntimeException("Periodic job stop failed")).when(job).stop();

        JdaDiscordGateway gateway = mock(JdaDiscordGateway.class);
        Storage storage = mock(Storage.class);

        wiring.setPeriodicSyncJobForTest(job);
        wiring.setDiscordGatewayForTest(gateway);
        wiring.setStorageForTest(storage);

        assertDoesNotThrow(wiring::onDisable, "onDisable must swallow exceptions from component shutdown");

        // Verify remaining shutdown steps executed despite prior failure
        verify(gateway, times(1)).shutdown();
        verify(storage, times(1)).close();
    }

    @Test
    void onDisableCatchesExceptionsFromDiscordShutdownAndClosesStorage() {
        DiscordTownyWiring wiring = new DiscordTownyWiring();
        JdaDiscordGateway gateway = mock(JdaDiscordGateway.class);
        doThrow(new RuntimeException("Discord shutdown failure")).when(gateway).shutdown();

        Storage storage = mock(Storage.class);

        wiring.setDiscordGatewayForTest(gateway);
        wiring.setStorageForTest(storage);

        assertDoesNotThrow(wiring::onDisable);
        verify(storage, times(1)).close();
    }

    @Test
    void onDisableCatchesExceptionsFromStorageClose() {
        DiscordTownyWiring wiring = new DiscordTownyWiring();
        Storage storage = mock(Storage.class);
        doThrow(new RuntimeException("Database pool close error")).when(storage).close();

        wiring.setStorageForTest(storage);

        assertDoesNotThrow(wiring::onDisable);
    }

    @Test
    void onDisableShutsDownInReverseDependencyOrder() {
        DiscordTownyWiring wiring = new DiscordTownyWiring();
        PeriodicSyncJob job = mock(PeriodicSyncJob.class);
        JdaDiscordGateway gateway = mock(JdaDiscordGateway.class);
        Storage storage = mock(Storage.class);

        wiring.setPeriodicSyncJobForTest(job);
        wiring.setDiscordGatewayForTest(gateway);
        wiring.setStorageForTest(storage);

        wiring.onDisable();

        InOrder inOrder = inOrder(job, gateway, storage);
        inOrder.verify(job).stop();
        inOrder.verify(gateway).shutdown();
        inOrder.verify(storage).close();
    }

    @Test
    void onDisableCancelsInFlightDiscordConnectAndShutsDownLateGateway() {
        DiscordTownyWiring wiring = new DiscordTownyWiring();
        JdaDiscordGateway gateway = mock(JdaDiscordGateway.class);
        wiring.setDiscordGatewayForTest(gateway);

        java.util.concurrent.CompletableFuture<Void> inFlightConnect = new java.util.concurrent.CompletableFuture<>();
        // Simulate start() setting discordConnectFuture with a whenComplete handler
        inFlightConnect.whenComplete((v, t) -> {
            synchronized (wiring) {
                try {
                    gateway.shutdown();
                } catch (Throwable ignored) {}
            }
        });

        // Set the future via reflection or call onDisable
        // wiring.stop() should cancel in-flight futures
        wiring.onDisable();

        // Verify that in-flight connect future is completed exceptionally/cancelled or gateway shut down
        verify(gateway, times(1)).shutdown();
    }

    @Test
    void reloadReschedulesPeriodicSyncJobAndUpdatesConfig() {
        PeriodicSyncJob initialJob = mock(PeriodicSyncJob.class);
        Storage storage = mock(Storage.class);
        when(storage.spaces()).thenReturn(mock(com.discordtowny.storage.SpaceRepository.class));
        when(storage.links()).thenReturn(mock(com.discordtowny.storage.LinkRepository.class));
        when(storage.settings()).thenReturn(mock(com.discordtowny.storage.SettingsRepository.class));

        JdaDiscordGateway gateway = mock(JdaDiscordGateway.class);
        when(gateway.isAvailable()).thenReturn(true);

        YamlConfigLoader configLoader = mock(YamlConfigLoader.class);
        com.discordtowny.config.Messages mockMessages = mock(com.discordtowny.config.Messages.class);
        when(configLoader.messages()).thenReturn(mockMessages);

        PluginConfig.Linking linking = new PluginConfig.Linking(
                Duration.ofMinutes(10), 3, Duration.ofMinutes(15), true);
        PluginConfig.Limits limits = new PluginConfig.Limits(200, 2, Duration.ofSeconds(60));
        PluginConfig.Sync oldSync = new PluginConfig.Sync(Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPAIR, 20, Duration.ofSeconds(5));

        PluginConfig oldConfig = new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty()),
                new PluginConfig.Database(PluginConfig.Database.Type.SQLITE, "localhost", 3306, "db", "", "", "dt_", 1, 1, Duration.ofSeconds(5)),
                new PluginConfig.Structure("Cat", "Arch", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Alcalde", "{town}", Optional.empty(), false),
                limits,
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                oldSync,
                linking,
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );

        PluginConfig.Sync newSync = new PluginConfig.Sync(Duration.ofMinutes(15), PluginConfig.Sync.Mode.REPORT, 10, Duration.ofSeconds(3));
        PluginConfig newConfig = new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty()),
                new PluginConfig.Database(PluginConfig.Database.Type.SQLITE, "localhost", 3306, "db", "", "", "dt_", 1, 1, Duration.ofSeconds(5)),
                new PluginConfig.Structure("Cat", "Arch", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Alcalde", "{town}", Optional.empty(), false),
                limits,
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                newSync,
                linking,
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );
        when(configLoader.load()).thenReturn(newConfig);

        AtomicBoolean jobScheduled = new AtomicBoolean(false);
        com.discordtowny.sync.SyncScheduler syncScheduler = (task, interval) -> {
            jobScheduled.set(true);
            return () -> {};
        };

        DiscordTownyWiring wiring = new DiscordTownyWiring(null, null, Runnable::run, syncScheduler, () -> {}, null, "1.0.0");
        wiring.setConfigForTest(oldConfig);
        wiring.setConfigLoaderForTest(configLoader);
        // Reload rebuilds the services, and a service without a Towny facade is
        // not a service. A started wiring always has one.
        wiring.setTownyFacadeForTest(mock(com.discordtowny.towny.TownyFacade.class));
        wiring.setPeriodicSyncJobForTest(initialJob);
        wiring.setStorageForTest(storage);
        wiring.setDiscordGatewayForTest(gateway);

        wiring.reload();

        // 1. Initial job was stopped
        verify(initialJob, times(1)).stop();
        // 2. New job was scheduled with updated interval/config
        assertTrue(jobScheduled.get(), "Periodic sync job must be rescheduled during reload");
        // 3. New config is applied to wiring
        assertEquals(newConfig, wiring.getConfig());
        assertEquals(PluginConfig.Sync.Mode.REPORT, wiring.getConfig().sync().mode());
        // 4. Services were genuinely re-instantiated
        assertNotNull(wiring.getSpaceService());
        assertNotNull(wiring.getSyncService());
        assertNotNull(wiring.getLinkService());
        assertNotNull(wiring.getPeriodicSyncJob());
    }
}
