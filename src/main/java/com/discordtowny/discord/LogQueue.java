package com.discordtowny.discord;

import com.discordtowny.model.AuditEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

/**
 * Audit event queue for the Discord log channel.
 *
 * <p>Batches messages and sends them at a configurable interval in a single batch.
 * Has a maximum size: if it fills up, it drops the least relevant events
 * (INFO before WARNING before ERROR) and notes how many were lost.
 *
 * <p><b>Never blocks whoever produces the event.</b> The call to
 * {@link #enqueue} returns immediately, even if the queue is full.
 *
 * <p>Separated from JDA: receives a {@code Consumer<LogBatch>} as destination,
 * which allows testing it without a network.
 */
final class LogQueue {

    private final ArrayBlockingQueue<AuditEvent> buffer;
    private final int maxSize;
    private final Consumer<LogBatch> sender;
    private final Logger logger;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger droppedCount = new AtomicInteger(0);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean senderFailed = new AtomicBoolean(false);
    private final UnaryOperator<String> sanitizer;

    /** Batch of events ready to send. */
    record LogBatch(List<AuditEvent> events, int droppedSinceLastFlush) {}

    /**
     * @param maxSize     maximum queue size; excess events are dropped
     * @param sender      destination: receives a batch and sends it to Discord
     * @param logger      plugin logger
     */
    LogQueue(int maxSize, Consumer<LogBatch> sender, Logger logger) {
        this(maxSize, sender, logger, s -> s);
    }

    LogQueue(int maxSize, Consumer<LogBatch> sender, Logger logger, UnaryOperator<String> sanitizer) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        }
        this.maxSize = maxSize;
        this.buffer = new ArrayBlockingQueue<>(maxSize);
        this.sender = sender;
        this.logger = logger;
        this.sanitizer = sanitizer != null ? sanitizer : s -> s;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().name("dt-log-queue").unstarted(r);
            return t;
        });
    }

    /** Starts the periodic flush. */
    void start(Duration flushInterval) {
        if (started.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(
                    this::flush,
                    flushInterval.toMillis(),
                    flushInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    /** Stops the scheduler and performs a final flush. */
    void shutdown() {
        scheduler.shutdown();
        flush();
    }

    /**
     * Enqueues an event. Never blocks.
     *
     * <p>If the queue is full, it drops the least relevant event (INFO before
     * WARNING before ERROR) among the existing ones, or the new event
     * if it is the least relevant, and notes how many were lost.
     */
    void enqueue(AuditEvent event) {
        boolean offered = buffer.offer(event);
        if (!offered) {
            // Queue full: decide what to drop
            discardLeastRelevant(event);
        }
    }

    /** How many events were dropped since the last flush. */
    int droppedCount() {
        return droppedCount.get();
    }

    /** Current size of the queue. */
    int size() {
        return buffer.size();
    }

    // -- Dropping --

    private void discardLeastRelevant(AuditEvent incoming) {
        // We look for the least relevant event in the current queue
        // If incoming is less relevant than all, we drop it
        // If there is a less relevant one, we remove it and add incoming

        // Convert the queue to a list to inspect
        List<AuditEvent> snapshot = new ArrayList<>(buffer);
        if (snapshot.isEmpty()) {
            // In the meantime it emptied; try again
            if (!buffer.offer(incoming)) {
                droppedCount.incrementAndGet();
            }
            return;
        }

        // Find the one with lowest severity
        AuditEvent leastRelevant = Collections.min(snapshot, SEVERITY_COMPARATOR);

        if (SEVERITY_COMPARATOR.compare(incoming, leastRelevant) > 0) {
            // Incoming is more relevant: remove the least relevant
            boolean removed = buffer.remove(leastRelevant);
            if (removed) {
                if (!buffer.offer(incoming)) {
                    // Rare concurrency: could not insert. Lose the incoming
                    droppedCount.incrementAndGet();
                }
            }
            droppedCount.incrementAndGet();
        } else {
            // Incoming is equal or less relevant: drop it
            droppedCount.incrementAndGet();
        }
    }

    /**
     * Compares by severity: ERROR > WARNING > INFO.
     * Higher severity ones are "more relevant" and are kept.
     */
    private static final Comparator<AuditEvent> SEVERITY_COMPARATOR =
            Comparator.comparingInt(e -> e.severity().ordinal());

    // -- Flush --

    private void flush() {
        if (buffer.isEmpty() && droppedCount.get() == 0) {
            return;
        }

        List<AuditEvent> batch = new ArrayList<>();
        buffer.drainTo(batch);
        int dropped = droppedCount.getAndSet(0);

        if (batch.isEmpty() && dropped == 0) {
            return;
        }

        try {
            sender.accept(new LogBatch(Collections.unmodifiableList(batch), dropped));
            senderFailed.set(false);
        } catch (Exception e) {
            // A failure sending to Discord must not affect anything.
            // Log to console once so as not to spam.
            if (senderFailed.compareAndSet(false, true)) {
                String safeMsg = sanitizer.apply(e.getMessage() != null ? e.getMessage() : "unknown error");
                logger.warning("[Logs] Failed to send logs to Discord: " + safeMsg
                        + ". Further errors are suppressed until it works.");
            }
        }
    }
}
