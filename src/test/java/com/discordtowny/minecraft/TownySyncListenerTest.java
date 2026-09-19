package com.discordtowny.minecraft;

import com.discordtowny.space.SpaceService;
import com.discordtowny.sync.SyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TownySyncListener} delegation methods.
 *
 * <p>Uses a package-private seam to test delegation logic with plain values (UUIDs, names)
 * without mocking any Towny classes. Towny classes cannot initialise outside of a running
 * Bukkit server, so mocking them in unit tests causes initialization failures and classloader
 * poisoning across the shared JVM.
 *
 * <p>Accepted limit: Extraction of plain values from Towny event instances in the
 * thin {@code @EventHandler} wrapper methods remains untested at the unit test level
 * as it requires a live Bukkit server environment.
 */
class TownySyncListenerTest {

    private SyncService syncService;
    private SpaceService spaceService;
    private TownySyncListener listener;

    @BeforeEach
    void setUp() {
        syncService = mock(SyncService.class);
        spaceService = mock(SpaceService.class);
        listener = new TownySyncListener(syncService, spaceService);

        when(syncService.syncPlayer(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(spaceService.rename(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(spaceService.archive(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void residentJoiningTownDelegatesWithResidentUuid() {
        UUID residentUuid = UUID.randomUUID();

        listener.handleResidentJoin(residentUuid);

        verify(syncService, times(1)).syncPlayer(residentUuid);
    }

    @Test
    void residentJoiningTownWithNullUuidDoesNotDelegate() {
        listener.handleResidentJoin(null);

        verify(syncService, never()).syncPlayer(any());
    }

    @Test
    void residentLeavingTownDelegatesWithResidentUuid() {
        UUID residentUuid = UUID.randomUUID();

        listener.handleResidentLeave(residentUuid);

        verify(syncService, times(1)).syncPlayer(residentUuid);
    }

    @Test
    void residentLeavingTownWithNullUuidDoesNotDelegate() {
        listener.handleResidentLeave(null);

        verify(syncService, never()).syncPlayer(any());
    }

    @Test
    void residentKickedFromTownDelegatesWithKickedResidentUuid() {
        UUID residentUuid = UUID.randomUUID();

        listener.handleResidentKick(residentUuid);

        verify(syncService, times(1)).syncPlayer(residentUuid);
    }

    @Test
    void residentKickedFromTownWithNullUuidDoesNotDelegate() {
        listener.handleResidentKick(null);

        verify(syncService, never()).syncPlayer(any());
    }

    @Test
    void mayorChangeDelegatesWithOldAndNewMayorUuids() {
        UUID oldMayorUuid = UUID.randomUUID();
        UUID newMayorUuid = UUID.randomUUID();

        listener.handleMayorChange(oldMayorUuid, newMayorUuid);

        verify(syncService, times(1)).syncPlayer(oldMayorUuid);
        verify(syncService, times(1)).syncPlayer(newMayorUuid);
    }

    @Test
    void mayorChangeWithNullOldMayorOnlyDelegatesNewMayor() {
        UUID newMayorUuid = UUID.randomUUID();

        listener.handleMayorChange(null, newMayorUuid);

        verify(syncService, never()).syncPlayer(null);
        verify(syncService, times(1)).syncPlayer(newMayorUuid);
    }

    @Test
    void mayorChangeWithNullNewMayorOnlyDelegatesOldMayor() {
        UUID oldMayorUuid = UUID.randomUUID();

        listener.handleMayorChange(oldMayorUuid, null);

        verify(syncService, times(1)).syncPlayer(oldMayorUuid);
        verify(syncService, never()).syncPlayer(null);
    }

    @Test
    void mayorChangeWithBothNullDoesNotDelegate() {
        listener.handleMayorChange(null, null);

        verify(syncService, never()).syncPlayer(any());
    }

    @Test
    void townRenameDelegatesToSpaceServiceRenameWithTownUuidAndNewName() {
        UUID townUuid = UUID.randomUUID();
        String newName = "NovaRoma";

        listener.handleTownRename(townUuid, newName);

        verify(spaceService, times(1)).rename(townUuid, newName);
    }

    @Test
    void townRenameWithNullUuidOrNameDoesNotDelegate() {
        UUID townUuid = UUID.randomUUID();

        listener.handleTownRename(null, "NovaRoma");
        listener.handleTownRename(townUuid, null);

        verify(spaceService, never()).rename(any(), any());
    }

    @Test
    void townDeletionArchivesRatherThanDeletes() {
        UUID townUuid = UUID.randomUUID();

        listener.handleTownDelete(townUuid);

        verify(spaceService, times(1)).archive(eq(townUuid), eq("town_deleted"));
        verify(spaceService, never()).purgeArchived();
    }

    @Test
    void townDeletionWithNullUuidDoesNotDelegate() {
        listener.handleTownDelete(null);

        verify(spaceService, never()).archive(any(), any());
    }

    @Test
    void townRuinedArchivesRatherThanDeletes() {
        UUID townUuid = UUID.randomUUID();

        listener.handleTownRuined(townUuid);

        verify(spaceService, times(1)).archive(eq(townUuid), eq("town_ruined"));
        verify(spaceService, never()).purgeArchived();
    }

    @Test
    void townRuinedWithNullUuidDoesNotDelegate() {
        listener.handleTownRuined(null);

        verify(spaceService, never()).archive(any(), any());
    }

    @Test
    void noHandlerBlocksWaitingOnDispatchedFuture() {
        CompletableFuture<Void> uncompletedSync = new CompletableFuture<>();
        CompletableFuture<Void> uncompletedSpace = new CompletableFuture<>();

        when(syncService.syncPlayer(any())).thenReturn(uncompletedSync);
        when(spaceService.rename(any(), any())).thenReturn(uncompletedSpace);
        when(spaceService.archive(any(), any())).thenReturn(uncompletedSpace);

        UUID residentUuid = UUID.randomUUID();
        UUID townUuid = UUID.randomUUID();

        // Prove all handlers return immediately without blocking on uncompleted future
        assertTimeoutPreemptively(Duration.ofMillis(200), () -> {
            listener.handleResidentJoin(residentUuid);
            listener.handleResidentLeave(residentUuid);
            listener.handleResidentKick(residentUuid);
            listener.handleMayorChange(residentUuid, residentUuid);
            listener.handleTownRename(townUuid, "TestTown");
            listener.handleTownDelete(townUuid);
            listener.handleTownRuined(townUuid);
        });

        assertFalse(uncompletedSync.isDone(), "Dispatched sync future must remain uncompleted");
        assertFalse(uncompletedSpace.isDone(), "Dispatched space future must remain uncompleted");
    }
}
