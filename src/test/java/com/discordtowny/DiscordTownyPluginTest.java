package com.discordtowny;

import com.discordtowny.discord.JdaDiscordGateway;
import com.discordtowny.storage.Storage;
import com.discordtowny.sync.PeriodicSyncJob;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
