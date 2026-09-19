package com.discordtowny.sync;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.sync.SyncService.SyncReport;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Recurring background job that executes {@link SyncService#reconcileAll()} at the
 * configured interval.
 *
 * <p>Key guarantees:
 * <ul>
 *   <li><b>Non-overlapping passes:</b> if a reconciliation pass is still in progress when the
 *       next scheduled tick arrives, that tick is skipped to avoid accumulating passes and
 *       saturating Discord rate limits.</li>
 *   <li><b>Fault resilience:</b> exceptions or failed futures during a pass are logged and
 *       do not terminate the recurring schedule.</li>
 *   <li><b>Testability:</b> the scheduling mechanism is injected via {@link SyncScheduler},
 *       allowing tests to drive ticks deterministically without depending on server runtimes.</li>
 * </ul>
 */
public final class PeriodicSyncJob implements AutoCloseable {

    private static final Logger DEFAULT_LOGGER = Logger.getLogger(PeriodicSyncJob.class.getName());

    private final SyncService syncService;
    private final Supplier<PluginConfig> configSupplier;
    private final SyncScheduler scheduler;
    private final Logger logger;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<SyncReport> lastReportRef = new AtomicReference<>();
    private final AtomicInteger completedPasses = new AtomicInteger(0);
    private final AtomicInteger skippedPasses = new AtomicInteger(0);
    private final AtomicInteger failedPasses = new AtomicInteger(0);

    private boolean started = false;
    private SyncScheduler.Cancellable cancellable;

    public PeriodicSyncJob(
            SyncService syncService,
            Supplier<PluginConfig> configSupplier,
            SyncScheduler scheduler,
            Logger logger) {
        this.syncService = Objects.requireNonNull(syncService, "syncService cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.logger = logger != null ? logger : DEFAULT_LOGGER;
    }

    public PeriodicSyncJob(
            SyncService syncService,
            PluginConfig config,
            SyncScheduler scheduler,
            Logger logger) {
        this(syncService, () -> Objects.requireNonNull(config, "config cannot be null"), scheduler, logger);
    }

    public PeriodicSyncJob(
            SyncService syncService,
            PluginConfig config,
            SyncScheduler scheduler) {
        this(syncService, config, scheduler, DEFAULT_LOGGER);
    }

    public PeriodicSyncJob(
            SyncService syncService,
            Supplier<PluginConfig> configSupplier,
            SyncScheduler scheduler) {
        this(syncService, configSupplier, scheduler, DEFAULT_LOGGER);
    }

    /**
     * Starts the periodic synchronization job.
     *
     * <p>If already started, this call is a no-op.
     */
    public synchronized void start() {
        if (started) {
            return;
        }

        PluginConfig cfg = configSupplier.get();
        Duration interval = cfg != null && cfg.sync() != null ? cfg.sync().interval() : null;
        if (interval == null || interval.isZero() || interval.isNegative()) {
            logger.warning("Periodic synchronization job disabled: interval is null or non-positive (" + interval + ")");
            return;
        }

        this.started = true;
        this.cancellable = scheduler.schedule(this::runTick, interval);
        logger.info("Periodic synchronization job started with interval " + interval);
    }

    /**
     * Stops the periodic synchronization job cleanly.
     *
     * <p>Cancels the scheduled task handle. If not currently started, this call is a no-op.
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (cancellable != null) {
            try {
                cancellable.cancel();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Exception while cancelling periodic sync task", e);
            }
            cancellable = null;
        }
        logger.info("Periodic synchronization job stopped");
    }

    /**
     * Executes a single reconciliation pass if one is not already running.
     *
     * <p>If a pass is already in progress, the tick is skipped and {@code null} is returned.
     *
     * @return a future completing with the {@link SyncReport}, or completed with null if skipped
     */
    public CompletableFuture<SyncReport> runTick() {
        if (!running.compareAndSet(false, true)) {
            skippedPasses.incrementAndGet();
            logger.warning("Periodic synchronization tick skipped: previous pass is still in progress");
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<SyncReport> passFuture;
        try {
            passFuture = syncService.reconcileAll();
        } catch (Throwable t) {
            running.set(false);
            failedPasses.incrementAndGet();
            logger.log(Level.WARNING, "Failed to launch periodic synchronization pass", t);
            return CompletableFuture.failedFuture(t);
        }

        if (passFuture == null) {
            running.set(false);
            return CompletableFuture.completedFuture(null);
        }

        return passFuture.whenComplete((report, ex) -> {
            try {
                if (ex != null) {
                    failedPasses.incrementAndGet();
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    logger.log(Level.WARNING, "Periodic synchronization pass completed exceptionally: " + cause.getMessage(), cause);
                } else {
                    completedPasses.incrementAndGet();
                    if (report != null) {
                        lastReportRef.set(report);
                    }
                }
            } finally {
                running.set(false);
            }
        });
    }

    @Override
    public void close() {
        stop();
    }

    /** True if the job has been started and not yet stopped. */
    public synchronized boolean isStarted() {
        return started;
    }

    /** True if a reconciliation pass is currently executing. */
    public boolean isRunning() {
        return running.get();
    }

    /** The most recently completed pass report, if any pass has completed. */
    public Optional<SyncReport> lastReport() {
        return Optional.ofNullable(lastReportRef.get());
    }

    /** Total number of successfully completed passes. */
    public int completedPasses() {
        return completedPasses.get();
    }

    /** Total number of ticks skipped due to an overlapping pass. */
    public int skippedPasses() {
        return skippedPasses.get();
    }

    /** Total number of passes that failed exceptionally. */
    public int failedPasses() {
        return failedPasses.get();
    }
}
