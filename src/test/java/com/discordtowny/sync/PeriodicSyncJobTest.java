package com.discordtowny.sync;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.sync.SyncService.SyncReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link PeriodicSyncJob}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>The job starts and schedules on the configured interval from {@code config.sync().interval()}.</li>
 *   <li>Stopping the job cancels the scheduled task handle cleanly.</li>
 *   <li>Passes never overlap: if a previous pass is pending, incoming ticks are skipped.</li>
 *   <li>A failed pass logs the error and keeps the schedule alive for subsequent ticks.</li>
 *   <li>Pass results are observable via {@link PeriodicSyncJob#lastReport()}.</li>
 * </ul>
 */
class PeriodicSyncJobTest {

    private SyncService syncService;
    private PluginConfig config;
    private TestScheduler testScheduler;
    private PeriodicSyncJob job;

    @BeforeEach
    void setUp() {
        syncService = mock(SyncService.class);

        PluginConfig.Sync syncConfig = new PluginConfig.Sync(
                Duration.ofMinutes(15), PluginConfig.Sync.Mode.REPAIR, 10, Duration.ofSeconds(5));

        config = new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty()),
                new PluginConfig.Database(PluginConfig.Database.Type.SQLITE, "localhost", 3306, "db", "", "", "dt_", 1, 1, Duration.ofSeconds(5)),
                new PluginConfig.Structure("Cat", "Arch", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Alcalde", "{town}", Optional.empty(), false),
                new PluginConfig.Limits(200, 2, Duration.ofSeconds(60)),
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                syncConfig,
                new PluginConfig.Linking(Duration.ofMinutes(10), 3, Duration.ofMinutes(15), true),
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );

        testScheduler = new TestScheduler();
        job = new PeriodicSyncJob(syncService, config, testScheduler, Logger.getLogger("PeriodicSyncJobTest"));
    }

    @Test
    @DisplayName("Starts and schedules recurring task at configured interval")
    void startsAndSchedulesAtConfiguredInterval() {
        assertFalse(job.isStarted());
        assertNull(testScheduler.scheduledTask);

        job.start();

        assertTrue(job.isStarted());
        assertNotNull(testScheduler.scheduledTask);
        assertEquals(Duration.ofMinutes(15), testScheduler.scheduledInterval);
    }

    @Test
    @DisplayName("Stops cleanly and cancels scheduled task handle")
    void stopsCleanlyAndCancelsScheduledHandle() {
        job.start();
        assertTrue(job.isStarted());
        assertFalse(testScheduler.cancellable.isCancelled());

        job.stop();

        assertFalse(job.isStarted());
        assertTrue(testScheduler.cancellable.isCancelled());
    }

    @Test
    @DisplayName("Runs reconciliation on tick and exposes observable report")
    void runsReconciliationOnTickAndExposesObservableReport() {
        SyncReport expectedReport = new SyncReport(
                3, 2, 1, 0, 0, List.of(), PluginConfig.Sync.Mode.REPAIR, 0, 0);
        when(syncService.reconcileAll()).thenReturn(CompletableFuture.completedFuture(expectedReport));

        job.start();
        testScheduler.triggerTick();

        assertEquals(1, job.completedPasses());
        assertEquals(0, job.skippedPasses());
        assertEquals(0, job.failedPasses());
        assertFalse(job.isRunning());

        assertTrue(job.lastReport().isPresent());
        SyncReport actualReport = job.lastReport().get();
        assertEquals(3, actualReport.spacesChecked());
        assertEquals(2, actualReport.rolesGranted());
        assertEquals(1, actualReport.rolesRevoked());
    }

    @Test
    @DisplayName("Never overlaps passes: skips tick if previous reconciliation is still running")
    void neverOverlapsPassesAndSkipsTickWhenPreviousIsRunning() {
        CompletableFuture<SyncReport> pendingPass = new CompletableFuture<>();
        when(syncService.reconcileAll()).thenReturn(pendingPass);

        job.start();

        // Tick 1 starts and stays pending
        testScheduler.triggerTick();
        assertTrue(job.isRunning(), "Job must report running while pass future is pending");
        assertEquals(0, job.completedPasses());
        assertEquals(0, job.skippedPasses());

        // Tick 2 arrives while pass 1 is still running: must be skipped
        testScheduler.triggerTick();
        assertEquals(1, job.skippedPasses(), "Tick 2 must be skipped to avoid overlapping passes");
        assertTrue(job.isRunning(), "Job must still be running pass 1");

        // Now pass 1 completes
        SyncReport report = new SyncReport(1, 0, 0, 0, 0, List.of());
        pendingPass.complete(report);

        assertFalse(job.isRunning(), "Job must not be running after future completes");
        assertEquals(1, job.completedPasses());
        assertEquals(1, job.skippedPasses());
        assertEquals(Optional.of(report), job.lastReport());
    }

    @Test
    @DisplayName("Failed pass logs failure and keeps schedule for subsequent ticks")
    void failedPassKeepsScheduleForSubsequentTicks() {
        CompletableFuture<SyncReport> failedPass = new CompletableFuture<>();
        failedPass.completeExceptionally(new RuntimeException("Simulated Discord outage"));

        SyncReport successfulReport = new SyncReport(2, 1, 0, 0, 0, List.of());
        CompletableFuture<SyncReport> successfulPass = CompletableFuture.completedFuture(successfulReport);

        when(syncService.reconcileAll())
                .thenReturn(failedPass)
                .thenReturn(successfulPass);

        job.start();

        // Tick 1 fails exceptionally
        testScheduler.triggerTick();

        assertEquals(1, job.failedPasses(), "Must record failed pass");
        assertEquals(0, job.completedPasses());
        assertFalse(job.isRunning(), "Must reset running state after failure");
        assertTrue(job.isStarted(), "Job must remain started and scheduled after a failure");

        // Tick 2 runs and succeeds
        testScheduler.triggerTick();

        assertEquals(1, job.failedPasses());
        assertEquals(1, job.completedPasses(), "Subsequent tick must execute normally");
        assertFalse(job.isRunning());
        assertEquals(Optional.of(successfulReport), job.lastReport());
    }

    @Test
    @DisplayName("Does not schedule if interval is null, zero, or negative")
    void doesNotScheduleIfIntervalIsInvalid() {
        PluginConfig.Sync zeroSync = new PluginConfig.Sync(
                Duration.ZERO, PluginConfig.Sync.Mode.REPAIR, 10, Duration.ofSeconds(5));
        PluginConfig zeroConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), zeroSync, config.linking(),
                config.logging(), config.updates(), config.commands());

        PeriodicSyncJob zeroJob = new PeriodicSyncJob(
                syncService, zeroConfig, testScheduler, Logger.getLogger("PeriodicSyncJobTest"));

        zeroJob.start();
        assertFalse(zeroJob.isStarted());
        assertNull(testScheduler.scheduledTask);
    }

    private static final class TestScheduler implements SyncScheduler {
        Runnable scheduledTask;
        Duration scheduledInterval;
        final TestCancellable cancellable = new TestCancellable();

        @Override
        public Cancellable schedule(Runnable task, Duration interval) {
            this.scheduledTask = task;
            this.scheduledInterval = interval;
            return cancellable;
        }

        void triggerTick() {
            if (scheduledTask != null) {
                scheduledTask.run();
            }
        }
    }

    private static final class TestCancellable implements SyncScheduler.Cancellable {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        boolean isCancelled() {
            return cancelled.get();
        }
    }
}
