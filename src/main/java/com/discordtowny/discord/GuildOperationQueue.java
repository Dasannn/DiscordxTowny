package com.discordtowny.discord;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

/**
 * Serialized guild mutation queue.
 *
 * <p>Single consumer, nothing in parallel. Each operation is executed with
 * {@link GuildOperationExecutor}, and transient failures are retried with
 * exponential backoff. A permanent failure aborts the task.
 *
 * <p>The future returned by {@link #submit} never completes
 * exceptionally: failures travel inside {@link OperationOutcome}.
 *
 * <p>Separated from JDA so that it can be tested without a network.
 */
final class GuildOperationQueue {

    // -- Retries with exponential backoff --
    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(2);
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    private final GuildOperationExecutor executor;
    private final Logger logger;
    private final BlockingQueue<QueuedOperation> queue;
    private final Thread consumerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final UnaryOperator<String> sanitizer;

    private final int maxRetries;
    private final Duration baseDelay;
    private final double backoffMultiplier;

    /** Element in the queue: the operation plus the future awaiting its outcome. */
    record QueuedOperation(GuildOperation operation, CompletableFuture<OperationOutcome> future) {}

    GuildOperationQueue(GuildOperationExecutor executor, Logger logger) {
        this(executor, logger, DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY, DEFAULT_BACKOFF_MULTIPLIER, s -> s);
    }

    GuildOperationQueue(GuildOperationExecutor executor, Logger logger, UnaryOperator<String> sanitizer) {
        this(executor, logger, DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY, DEFAULT_BACKOFF_MULTIPLIER, sanitizer);
    }

    /** Constructor with retry parameters, for tests. */
    GuildOperationQueue(GuildOperationExecutor executor, Logger logger,
                        int maxRetries, Duration baseDelay, double backoffMultiplier) {
        this(executor, logger, maxRetries, baseDelay, backoffMultiplier, s -> s);
    }

    GuildOperationQueue(GuildOperationExecutor executor, Logger logger,
                        int maxRetries, Duration baseDelay, double backoffMultiplier,
                        UnaryOperator<String> sanitizer) {
        this.executor = executor;
        this.logger = logger;
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
        this.backoffMultiplier = backoffMultiplier;
        this.sanitizer = sanitizer != null ? sanitizer : s -> s;
        this.queue = new LinkedBlockingQueue<>();
        this.consumerThread = Thread.ofVirtual().name("dt-guild-queue").unstarted(this::consume);
    }

    /** Starts the consumer. Call only once. */
    void start() {
        if (running.compareAndSet(false, true)) {
            consumerThread.start();
        }
    }

    /** Shuts down the queue cleanly, completing the ongoing operation. */
    void shutdown() {
        running.set(false);
        consumerThread.interrupt();
        // Complete pending items as transient failure so they do not hang
        QueuedOperation pending;
        while ((pending = queue.poll()) != null) {
            pending.future().complete(
                    OperationOutcome.transientFailure("Queue stopped: plugin is shutting down"));
        }
    }

    /**
     * Enqueues an operation. Does not block.
     *
     * <p>The future never completes exceptionally: failures travel inside
     * {@link OperationOutcome}.
     */
    CompletableFuture<OperationOutcome> submit(GuildOperation operation) {
        if (operation == null) {
            return CompletableFuture.completedFuture(
                    OperationOutcome.permanentFailure("Null operation"));
        }
        if (!running.get()) {
            return CompletableFuture.completedFuture(
                    OperationOutcome.transientFailure("Queue stopped"));
        }
        var future = new CompletableFuture<OperationOutcome>();
        try {
            queue.offer(new QueuedOperation(operation, future));
        } catch (Exception e) {
            future.complete(OperationOutcome.transientFailure(
                    "Failed to enqueue operation: " + sanitize(e.getMessage())));
        }
        // Check if stopped concurrently
        if (!running.get()) {
            if (queue.removeIf(item -> item.future() == future)) {
                future.complete(OperationOutcome.transientFailure("Queue stopped"));
            }
        }
        return future;
    }

    /** Visible only for tests: how many operations are waiting in the queue. */
    int pendingCount() {
        return queue.size();
    }

    // -- Consumer --

    private void consume() {
        while (running.get()) {
            QueuedOperation item = null;
            try {
                item = queue.take();
                OperationOutcome outcome = executeWithRetries(item.operation());
                item.future().complete(outcome);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Shutdown interrupts the consumer; simply exit the loop
                break;
            } catch (Throwable t) {
                String safeTrace = sanitizeThrowable(t);
                logger.severe("[Queue] Unexpected error in consumer: " + safeTrace);
                if (item != null && !item.future().isDone()) {
                    item.future().complete(OperationOutcome.transientFailure(
                            "Critical execution failure: " + sanitize(t.getMessage())));
                }
            }
        }
    }

    private OperationOutcome executeWithRetries(GuildOperation operation) {
        int attempt = 0;
        OperationOutcome lastOutcome = null;

        while (attempt <= maxRetries) {
            if (!running.get()) {
                return OperationOutcome.transientFailure("Queue stopped during retry");
            }

            try {
                lastOutcome = executor.execute(operation);
                if (lastOutcome == null) {
                    lastOutcome = OperationOutcome.transientFailure(
                            "Executor returned a null outcome");
                }
            } catch (Throwable t) {
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return OperationOutcome.transientFailure("Interrupted during execution");
                }
                String safeMsg = sanitize(t.getMessage());
                logger.warning("[Queue] Unhandled exception in '"
                        + operation.describe() + "': " + safeMsg);
                lastOutcome = OperationOutcome.transientFailure(
                        "Exception: " + safeMsg);
            }

            switch (lastOutcome.status()) {
                case SUCCESS -> {
                    return lastOutcome;
                }
                case PERMANENT_FAILURE -> {
                    String safeReason = sanitize(lastOutcome.reason().orElse("no details"));
                    logger.warning("[Queue] Permanent failure in '" + operation.describe()
                            + "': " + safeReason);
                    executor.onOperationFailed(operation, lastOutcome);
                    return lastOutcome;
                }
                case TRANSIENT_FAILURE -> {
                    attempt++;
                    if (attempt > maxRetries) {
                        String safeReason = sanitize(lastOutcome.reason().orElse("no details"));
                        logger.warning("[Queue] Exhausted retries for '"
                                + operation.describe() + "': "
                                + safeReason);
                        executor.onOperationFailed(operation, lastOutcome);
                        return lastOutcome;
                    }
                    long delayMs = (long) (baseDelay.toMillis()
                            * Math.pow(backoffMultiplier, attempt - 1));
                    logger.info("[Queue] Retry " + attempt + "/" + maxRetries
                            + " for '" + operation.describe()
                            + "' in " + delayMs + "ms");
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return OperationOutcome.transientFailure(
                                "Interrupted while waiting for retry");
                    }
                }
            }
        }
        // Should never reach here, but for safety
        return lastOutcome != null ? lastOutcome
                : OperationOutcome.transientFailure("Unexpected error in queue");
    }

    private String sanitize(String message) {
        if (message == null) return "unknown error";
        return sanitizer.apply(message);
    }

    private String sanitizeThrowable(Throwable t) {
        if (t == null) return "unknown error";
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sanitize(sw.toString());
    }
}
