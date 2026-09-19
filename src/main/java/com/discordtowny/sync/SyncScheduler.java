package com.discordtowny.sync;

import java.time.Duration;

/**
 * Scheduling seam for recurring synchronization tasks.
 *
 * <p>Allows unit and integration tests to drive execution directly without
 * depending on Bukkit or real timers, while the production bootstrap can adapt
 * Bukkit's scheduler or a {@link java.util.concurrent.ScheduledExecutorService}.
 */
@FunctionalInterface
public interface SyncScheduler {

    /**
     * Schedules a recurring task at the specified interval.
     *
     * @param task the runnable to execute periodically
     * @param interval the period between executions
     * @return a cancellable handle to stop the scheduled execution
     */
    Cancellable schedule(Runnable task, Duration interval);

    /**
     * Handle that cancels a scheduled execution.
     */
    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }
}
