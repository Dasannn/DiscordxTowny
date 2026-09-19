package com.discordtowny.discord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the serialized guild operation queue.
 *
 * <p>Demonstrates without network or JDA:
 * <ul>
 *   <li>that the queue serializes (does not execute two operations at the same time),</li>
 *   <li>that a retry after partial failure does not duplicate resources,</li>
 *   <li>that a permanent failure is not retried.</li>
 * </ul>
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class GuildOperationQueueTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    @Test
    @DisplayName("The queue serializes: does not execute two operations at the same time")
    void serializesOperations() throws Exception {
        // Concurrency tracking: if two executions ever overlap, we detect it
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        List<String> executionOrder = new CopyOnWriteArrayList<>();

        GuildOperationExecutor executor = op -> {
            int running = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(running, Math::max);
            try {
                // Short pause to verify concurrency
                Thread.sleep(10);
                executionOrder.add(op.describe());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrent.decrementAndGet();
            }
            return OperationOutcome.success();
        };

        var queue = new GuildOperationQueue(executor, LOGGER,
                3, java.time.Duration.ofMillis(5), 1.0);
        queue.start();

        // Submit multiple operations
        var futures = new ArrayList<CompletableFuture<OperationOutcome>>();
        for (int i = 0; i < 5; i++) {
            var op = new GuildOperation.DeleteSpace(
                    java.util.UUID.randomUUID(), "town-" + i);
            futures.add(queue.submit(op));
        }

        // Wait for all to finish
        for (var f : futures) {
            OperationOutcome result = f.get(5, TimeUnit.SECONDS);
            assertTrue(result.succeeded());
        }

        queue.shutdown();

        // Maximum concurrency must be 1
        assertEquals(1, maxConcurrent.get(),
                "There must never be more than one operation executing at a time");

        // All were executed
        assertEquals(5, executionOrder.size());
    }

    @Test
    @DisplayName("Retry after transient failure completes the task successfully")
    void retryOnTransientFailureCompletesSuccessfully() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        List<String> createdRoles = new ArrayList<>();
        List<String> createdChannels = new ArrayList<>();

        // Executor simulating idempotent creation:
        // Attempt 1: creates the role successfully, but fails before creating channels (partial failure).
        // Attempt 2: on retry, checks if the role already exists (does not duplicate it) and creates channels.
        GuildOperationExecutor executor = op -> {
            int attempt = attempts.incrementAndGet();

            // Step 1: Town role (idempotent: do not duplicate if it already exists)
            if (!createdRoles.contains("role-test-town")) {
                createdRoles.add("role-test-town");
            }

            // Partial failure on the first attempt after creating the role
            if (attempt == 1) {
                return OperationOutcome.transientFailure("Transient network error creating channels");
            }

            // Step 2: Town channels (idempotent)
            if (!createdChannels.contains("channel-test-town")) {
                createdChannels.add("channel-test-town");
            }

            return OperationOutcome.success();
        };

        // Fast retry parameters to avoid delaying tests
        var queue = new GuildOperationQueue(executor, LOGGER,
                3, java.time.Duration.ofMillis(10), 1.0);
        queue.start();

        var op = new GuildOperation.CreateSpace(new com.discordtowny.model.SpaceRequest(
                java.util.UUID.randomUUID(), "test-town", java.util.UUID.randomUUID(),
                List.of(), "discord-mayor", 2));
        OperationOutcome result = queue.submit(op).get(5, TimeUnit.SECONDS);

        queue.shutdown();

        assertTrue(result.succeeded(), "Must have succeeded after retry");
        assertEquals(2, attempts.get(), "Exactly 2 attempts: the original + 1 retry");
        assertEquals(1, createdRoles.size(), "Must not duplicate the role");
        assertEquals(1, createdChannels.size(), "Must not duplicate the channel");
        assertEquals("role-test-town", createdRoles.getFirst());
        assertEquals("channel-test-town", createdChannels.getFirst());
    }

    @Test
    @DisplayName("Permanent failure is not retried")
    void permanentFailureNoRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        GuildOperationExecutor executor = op -> {
            attempts.incrementAndGet();
            return OperationOutcome.permanentFailure("Insufficient permissions");
        };

        var queue = new GuildOperationQueue(executor, LOGGER,
                3, java.time.Duration.ofMillis(10), 1.0);
        queue.start();

        var op = new GuildOperation.DeleteSpace(
                java.util.UUID.randomUUID(), "test-town");
        OperationOutcome result = queue.submit(op).get(5, TimeUnit.SECONDS);

        queue.shutdown();

        assertFalse(result.succeeded());
        assertEquals(OperationOutcome.Status.PERMANENT_FAILURE, result.status());
        assertEquals(1, attempts.get(),
                "A permanent failure is only attempted once");
    }

    @Test
    @DisplayName("Transient failures are retried up to the maximum")
    void transientFailureRetriesUpToMax() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        GuildOperationExecutor executor = op -> {
            attempts.incrementAndGet();
            return OperationOutcome.transientFailure("Always fails");
        };

        // Fast retries for the test (10ms base, no multiplier)
        var queue = new GuildOperationQueue(executor, LOGGER,
                5, java.time.Duration.ofMillis(10), 1.0);
        queue.start();

        var op = new GuildOperation.DeleteSpace(
                java.util.UUID.randomUUID(), "test-town");
        OperationOutcome result = queue.submit(op).get(5, TimeUnit.SECONDS);

        queue.shutdown();

        assertFalse(result.succeeded());
        assertEquals(OperationOutcome.Status.TRANSIENT_FAILURE, result.status());
        // 1 original attempt + 5 retries = 6
        assertEquals(6, attempts.get(),
                "All retries must be exhausted (1 + MAX_RETRIES)");
    }

    @Test
    @DisplayName("submit() never completes the future exceptionally")
    void submitNeverCompletesExceptionally() throws Exception {
        // Case 1: Throws exception on attempt 1 and then succeeds
        AtomicInteger calls1 = new AtomicInteger(0);
        GuildOperationExecutor executor1 = op -> {
            if (calls1.incrementAndGet() == 1) {
                throw new RuntimeException("Unexpected explosion");
            }
            return OperationOutcome.success();
        };

        var queue1 = new GuildOperationQueue(executor1, LOGGER,
                3, java.time.Duration.ofMillis(10), 1.0);
        queue1.start();

        var op1 = new GuildOperation.DeleteSpace(
                java.util.UUID.randomUUID(), "test-town");

        CompletableFuture<OperationOutcome> f1 = queue1.submit(op1);
        OperationOutcome result1 = f1.get(5, TimeUnit.SECONDS);

        queue1.shutdown();

        assertFalse(f1.isCompletedExceptionally());
        assertNotNull(result1);
        assertTrue(result1.succeeded());
        assertEquals(2, calls1.get());

        // Case 2: Throws exception on all attempts (exhausts retries without completing exceptionally)
        GuildOperationExecutor executor2 = op -> {
            throw new IllegalStateException("Constant error");
        };

        var queue2 = new GuildOperationQueue(executor2, LOGGER,
                2, java.time.Duration.ofMillis(5), 1.0);
        queue2.start();

        CompletableFuture<OperationOutcome> f2 = queue2.submit(op1);
        OperationOutcome result2 = f2.get(5, TimeUnit.SECONDS);

        queue2.shutdown();

        assertFalse(f2.isCompletedExceptionally());
        assertNotNull(result2);
        assertFalse(result2.succeeded());
        assertEquals(OperationOutcome.Status.TRANSIENT_FAILURE, result2.status());

        // Case 3: The executor returns null
        GuildOperationExecutor executor3 = op -> null;
        var queue3 = new GuildOperationQueue(executor3, LOGGER,
                1, java.time.Duration.ofMillis(5), 1.0);
        queue3.start();

        CompletableFuture<OperationOutcome> f3 = queue3.submit(op1);
        OperationOutcome result3 = f3.get(5, TimeUnit.SECONDS);

        queue3.shutdown();

        assertFalse(f3.isCompletedExceptionally());
        assertNotNull(result3);
        assertFalse(result3.succeeded());

        // Case 4: A null operation is submitted
        var queue4 = new GuildOperationQueue(op -> OperationOutcome.success(), LOGGER);
        CompletableFuture<OperationOutcome> f4 = queue4.submit(null);
        OperationOutcome result4 = f4.get(5, TimeUnit.SECONDS);

        assertFalse(f4.isCompletedExceptionally());
        assertNotNull(result4);
        assertFalse(result4.succeeded());
        assertEquals(OperationOutcome.Status.PERMANENT_FAILURE, result4.status());
    }

    @Test
    @DisplayName("Execution order respects submission order")
    void executionRespectsSubmissionOrder() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        GuildOperationExecutor executor = op -> {
            order.add(op.describe());
            return OperationOutcome.success();
        };

        var queue = new GuildOperationQueue(executor, LOGGER,
                3, java.time.Duration.ofMillis(5), 1.0);
        queue.start();

        var futures = new ArrayList<CompletableFuture<OperationOutcome>>();
        for (int i = 0; i < 10; i++) {
            var op = new GuildOperation.DeleteSpace(
                    java.util.UUID.randomUUID(), "town-" + i);
            futures.add(queue.submit(op));
        }

        for (var f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        queue.shutdown();

        // Verify that execution order is sequential
        assertEquals(10, order.size());
        for (int i = 0; i < 10; i++) {
            assertEquals("permanently delete space for town-" + i, order.get(i));
        }
    }

    @Test
    @DisplayName("Shutdown completes pending futures without hanging")
    void shutdownCompletesPendingFutures() throws Exception {
        // Executor that takes a bit of time so there are pending operations on shutdown
        CountDownLatch started = new CountDownLatch(1);
        GuildOperationExecutor executor = op -> {
            started.countDown();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OperationOutcome.success();
        };

        var queue = new GuildOperationQueue(executor, LOGGER,
                3, java.time.Duration.ofMillis(5), 1.0);
        queue.start();

        // The first will execute (and take time)
        var f1 = queue.submit(new GuildOperation.DeleteSpace(
                java.util.UUID.randomUUID(), "slow"));
        // The others will remain queued
        var f2 = queue.submit(new GuildOperation.DeleteSpace(
                java.util.UUID.randomUUID(), "pending-1"));
        var f3 = queue.submit(new GuildOperation.DeleteSpace(
                java.util.UUID.randomUUID(), "pending-2"));

        // Wait for the first to start
        assertTrue(started.await(5, TimeUnit.SECONDS));

        // Shut down the queue
        queue.shutdown();

        // Pending operations must complete (with transient failure, not hang)
        OperationOutcome r2 = f2.get(5, TimeUnit.SECONDS);
        OperationOutcome r3 = f3.get(5, TimeUnit.SECONDS);

        assertNotNull(r2);
        assertNotNull(r3);
        assertEquals(OperationOutcome.Status.TRANSIENT_FAILURE, r2.status());
        assertEquals(OperationOutcome.Status.TRANSIENT_FAILURE, r3.status());
    }

    @Test
    @DisplayName("submit() after shutdown() returns transient failure immediately")
    void submitAfterShutdownReturnsTransientFailureImmediately() throws Exception {
        var queue = new GuildOperationQueue(op -> OperationOutcome.success(), LOGGER);
        queue.start();
        queue.shutdown();

        var op = new GuildOperation.DeleteSpace(java.util.UUID.randomUUID(), "town");
        CompletableFuture<OperationOutcome> future = queue.submit(op);

        assertNotNull(future);
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        OperationOutcome outcome = future.get(1, TimeUnit.SECONDS);
        assertFalse(outcome.succeeded());
        assertEquals(OperationOutcome.Status.TRANSIENT_FAILURE, outcome.status());
        assertTrue(outcome.reason().orElse("").contains("Queue stopped"));
    }

    @Test
    @DisplayName("The queue sanitizes the token if an unhandled exception contains it")
    void sanitizerMasksTokenWhenExceptionOccurs() throws Exception {
        String secretToken = "super-secret-bot-token-999";
        List<String> loggedWarnings = new ArrayList<>();
        Logger testLogger = new Logger("test-sanitizer", null) {
            @Override
            public void warning(String msg) {
                loggedWarnings.add(msg);
            }
        };

        GuildOperationExecutor executor = op -> {
            throw new RuntimeException("Fatal error with token " + secretToken);
        };

        var queue = new GuildOperationQueue(executor, testLogger, 1, java.time.Duration.ofMillis(5), 1.0,
                msg -> msg.replace(secretToken, "[HIDDEN_TOKEN]"));
        queue.start();

        var op = new GuildOperation.DeleteSpace(java.util.UUID.randomUUID(), "town");
        OperationOutcome outcome = queue.submit(op).get(5, TimeUnit.SECONDS);
        queue.shutdown();

        assertFalse(outcome.succeeded());
        assertFalse(outcome.reason().orElse("").contains(secretToken));
        assertTrue(outcome.reason().orElse("").contains("[HIDDEN_TOKEN]"));
        assertFalse(loggedWarnings.isEmpty());
        for (String logMsg : loggedWarnings) {
            assertFalse(logMsg.contains(secretToken), "The log must never contain the token");
        }
    }
}
