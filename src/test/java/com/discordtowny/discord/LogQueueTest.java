package com.discordtowny.discord;

import com.discordtowny.model.AuditEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the log queue.
 *
 * <p>Demonstrates without network:
 * <ul>
 *   <li>that it drops with a count when full, instead of growing without bound,</li>
 *   <li>that it never blocks the event producer,</li>
 *   <li>that it batches and sends in groups,</li>
 *   <li>that it preserves the most relevant events when dropping.</li>
 * </ul>
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LogQueueTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    private AuditEvent event(AuditEvent.Severity severity, String action) {
        return new AuditEvent(
                Instant.now(), severity, "test", action, "target", true, Optional.empty());
    }

    @Test
    @DisplayName("Queue drops with a count when full, instead of growing without bound")
    void discardsWhenFull() {
        int maxSize = 3;
        List<LogQueue.LogBatch> batches = new CopyOnWriteArrayList<>();

        var queue = new LogQueue(maxSize, batches::add, LOGGER);
        // We do not start the scheduler; we flush manually

        // Fill the queue
        queue.enqueue(event(AuditEvent.Severity.INFO, "a1"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "a2"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "a3"));
        assertEquals(3, queue.size(), "Queue must be full");

        // One more: must drop
        queue.enqueue(event(AuditEvent.Severity.INFO, "a4"));
        assertEquals(3, queue.size(), "Queue must not grow beyond the maximum");
        assertTrue(queue.droppedCount() > 0, "Must have dropped at least one");
    }

    @Test
    @DisplayName("Preserves the most relevant events when dropping")
    void keepsMoreRelevantEvents() {
        int maxSize = 2;
        List<LogQueue.LogBatch> batches = new CopyOnWriteArrayList<>();

        var queue = new LogQueue(maxSize, batches::add, LOGGER);

        // Fill with an ERROR and a WARNING
        queue.enqueue(event(AuditEvent.Severity.ERROR, "error1"));
        queue.enqueue(event(AuditEvent.Severity.WARNING, "warn1"));
        assertEquals(2, queue.size());

        // Try to insert an INFO: must drop the INFO (not the ERROR or the WARNING)
        queue.enqueue(event(AuditEvent.Severity.INFO, "info1"));
        assertEquals(2, queue.size());
        assertEquals(1, queue.droppedCount());

        // Try to insert another ERROR: must drop the WARNING (less relevant than ERROR)
        queue.enqueue(event(AuditEvent.Severity.ERROR, "error2"));
        assertEquals(2, queue.size());
        assertEquals(2, queue.droppedCount());
    }

    @Test
    @DisplayName("enqueue never blocks")
    void enqueueNeverBlocks() {
        int maxSize = 2;
        var queue = new LogQueue(maxSize, batch -> {
            // Slow consumer to force queue to fill
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
        }, LOGGER);

        // We do not start the scheduler so the queue does not empty

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            queue.enqueue(event(AuditEvent.Severity.INFO, "evento-" + i));
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // 100 enqueues must take less than 1 second (no blocking)
        assertTrue(elapsed < 1000,
                "enqueue must not block; took " + elapsed + "ms for 100 insertions");
        assertEquals(2, queue.size(), "Queue must be at its maximum");
        assertTrue(queue.droppedCount() > 0, "Must have dropped");
    }

    @Test
    @DisplayName("Flush groups events and reports dropped count")
    void flushGroupsAndReportsDropped() throws Exception {
        int maxSize = 5;
        List<LogQueue.LogBatch> batches = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        var queue = new LogQueue(maxSize, batch -> {
            batches.add(batch);
            latch.countDown();
        }, LOGGER);

        // Insert 3 events and 2 that get dropped
        queue.enqueue(event(AuditEvent.Severity.INFO, "e1"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "e2"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "e3"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "e4"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "e5"));
        // Queue full, insert one more
        queue.enqueue(event(AuditEvent.Severity.WARNING, "w1"));

        // Flush with short interval
        queue.start(Duration.ofMillis(100));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Must have flushed");
        queue.shutdown();

        assertFalse(batches.isEmpty(), "Must have at least one batch");
        LogQueue.LogBatch first = batches.getFirst();
        assertTrue(first.events().size() <= maxSize, "Must not exceed the maximum");
        // Reports dropped ones
        assertTrue(first.droppedSinceLastFlush() >= 1,
                "Must report at least 1 dropped event");
    }

    @Test
    @DisplayName("If the sender fails, it does not block nor grow without bound")
    void senderFailureDoesNotBlock() {
        int maxSize = 5;
        var queue = new LogQueue(maxSize, batch -> {
            throw new RuntimeException("Discord is down");
        }, LOGGER);

        queue.enqueue(event(AuditEvent.Severity.INFO, "e1"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "e2"));

        // Flush with very short interval, so that it attempts to send
        queue.start(Duration.ofMillis(50));

        // Wait a bit for it to fail
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        // Insert more events: must not block
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            queue.enqueue(event(AuditEvent.Severity.INFO, "post-error-" + i));
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        queue.shutdown();

        assertTrue(elapsed < 500,
                "enqueue must not block even if sender fails; took " + elapsed + "ms");
    }

    @Test
    @DisplayName("Maximum size zero or negative throws IllegalArgumentException")
    void invalidMaxSizeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new LogQueue(0, batch -> {}, LOGGER));
        assertThrows(IllegalArgumentException.class,
                () -> new LogQueue(-1, batch -> {}, LOGGER));
    }

    @Test
    @DisplayName("Empty queue does not generate batches")
    void emptyQueueNoFlush() throws Exception {
        List<LogQueue.LogBatch> batches = new CopyOnWriteArrayList<>();
        var queue = new LogQueue(10, batches::add, LOGGER);

        queue.start(Duration.ofMillis(50));
        Thread.sleep(200);
        queue.shutdown();

        assertTrue(batches.isEmpty(), "Must not have batches if queue is empty");
    }
}
