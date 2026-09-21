package com.discordtowny;

import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.model.AuditEvent;
import com.discordtowny.storage.AuditRepository;
import com.discordtowny.storage.StorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CompositeAuditSink} enforcing the requirements of T16:
 * <ul>
 *   <li>Every audit event reaches both database and Discord log channel.</li>
 *   <li>One sink failing does not cost the other.</li>
 *   <li>Work is dispatched off the server main thread.</li>
 *   <li>Database write never waits on Discord.</li>
 *   <li>Audit operations return an observable completion future.</li>
 *   <li>Pending writes can be drained within a bounded timeout on shutdown.</li>
 * </ul>
 */
class CompositeAuditSinkTest {

    private final AuditEvent sampleEvent = new AuditEvent(
            Instant.now(),
            AuditEvent.Severity.INFO,
            "admin-user",
            "space_create",
            "town-uuid-123",
            true,
            Optional.of("Space created successfully")
    );

    @Test
    @DisplayName("Audit event reaches both the database and the Discord gateway")
    void auditEventReachesBothDatabaseAndDiscord() {
        AuditRepository auditRepo = mock(AuditRepository.class);
        DiscordGateway discordGateway = mock(DiscordGateway.class);

        // Using direct synchronous executor to inspect results deterministically
        CompositeAuditSink sink = new CompositeAuditSink(auditRepo, discordGateway, Runnable::run);

        sink.accept(sampleEvent);

        verify(auditRepo, times(1)).record(sampleEvent);
        verify(discordGateway, times(1)).log(sampleEvent);
    }

    @Test
    @DisplayName("Failure publishing to Discord still leaves the database row recorded")
    void discordFailureStillLeavesDatabaseRow() {
        AuditRepository auditRepo = mock(AuditRepository.class);
        DiscordGateway discordGateway = mock(DiscordGateway.class);

        // Discord gateway throws an unexpected network or state exception
        doThrow(new RuntimeException("Discord gateway connection lost")).when(discordGateway).log(any());

        CompositeAuditSink sink = new CompositeAuditSink(auditRepo, discordGateway, Runnable::run);

        assertDoesNotThrow(() -> sink.accept(sampleEvent));

        // The database record must have succeeded
        verify(auditRepo, times(1)).record(sampleEvent);
    }

    @Test
    @DisplayName("Database failure does not prevent the Discord notice from being sent")
    void databaseFailureDoesNotPreventDiscordNotice() {
        AuditRepository auditRepo = mock(AuditRepository.class);
        DiscordGateway discordGateway = mock(DiscordGateway.class);

        // Database throws StorageException (e.g. disk full or pool timeout)
        doThrow(new StorageException("HikariCP pool acquisition timeout")).when(auditRepo).record(any());

        CompositeAuditSink sink = new CompositeAuditSink(auditRepo, discordGateway, Runnable::run);

        assertDoesNotThrow(() -> sink.accept(sampleEvent));

        // The Discord notice must still be dispatched
        verify(discordGateway, times(1)).log(sampleEvent);
    }

    @Test
    @DisplayName("With no log channel configured (or null gateway), the database row is still recorded")
    void unconfiguredOrNullDiscordStillWritesDatabaseRow() {
        AuditRepository auditRepo = mock(AuditRepository.class);

        // When Discord gateway is null (or degraded / unconfigured)
        CompositeAuditSink sink = new CompositeAuditSink(() -> auditRepo, () -> null, Runnable::run, Logger.getLogger("Test"));

        assertDoesNotThrow(() -> sink.accept(sampleEvent));

        verify(auditRepo, times(1)).record(sampleEvent);
    }

    @Test
    @DisplayName("Null audit repository still forwards event to Discord notice")
    void nullAuditRepositoryStillForwardsToDiscord() {
        DiscordGateway discordGateway = mock(DiscordGateway.class);

        CompositeAuditSink sink = new CompositeAuditSink(() -> null, () -> discordGateway, Runnable::run, Logger.getLogger("Test"));

        assertDoesNotThrow(() -> sink.accept(sampleEvent));

        verify(discordGateway, times(1)).log(sampleEvent);
    }

    @Test
    @DisplayName("Null event is safely ignored without invoking sinks or throwing NPE")
    void nullEventSafelyIgnored() {
        AuditRepository auditRepo = mock(AuditRepository.class);
        DiscordGateway discordGateway = mock(DiscordGateway.class);

        CompositeAuditSink sink = new CompositeAuditSink(auditRepo, discordGateway, Runnable::run);

        assertDoesNotThrow(() -> sink.accept(null));

        verify(auditRepo, never()).record(any());
        verify(discordGateway, never()).log(any());
    }

    @Test
    @DisplayName("Neither sink runs on the calling thread when background executor is used")
    void neitherSinkRunsOnCallingThread() throws Exception {
        AuditRepository auditRepo = mock(AuditRepository.class);
        DiscordGateway discordGateway = mock(DiscordGateway.class);

        AtomicReference<String> dbThreadName = new AtomicReference<>();
        AtomicReference<String> discordThreadName = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(2);

        doAnswer(inv -> {
            dbThreadName.set(Thread.currentThread().getName());
            latch.countDown();
            return null;
        }).when(auditRepo).record(any());

        doAnswer(inv -> {
            discordThreadName.set(Thread.currentThread().getName());
            latch.countDown();
            return null;
        }).when(discordGateway).log(any());

        ExecutorService asyncPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "dt-test-worker");
            t.setDaemon(true);
            return t;
        });
        try {
            CompositeAuditSink sink = new CompositeAuditSink(auditRepo, discordGateway, asyncPool);

            // Simulate execution from a "Server thread"
            Thread serverThread = new Thread(() -> sink.accept(sampleEvent), "Server thread");
        serverThread.setDaemon(true);
            serverThread.start();
            serverThread.join();

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Both sinks must complete within timeout");
            assertNotNull(dbThreadName.get());
            assertNotNull(discordThreadName.get());
            assertNotEquals("Server thread", dbThreadName.get(), "Database write must not run on Server thread");
            assertNotEquals("Server thread", discordThreadName.get(), "Discord notice must not run on Server thread");
        } finally {
            asyncPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Database write does not wait for Discord to complete")
    void databaseWriteDoesNotWaitForDiscord() throws Exception {
        AuditRepository auditRepo = mock(AuditRepository.class);
        DiscordGateway discordGateway = mock(DiscordGateway.class);

        CountDownLatch discordStarted = new CountDownLatch(1);
        CountDownLatch discordBlocker = new CountDownLatch(1);
        CountDownLatch dbCompleted = new CountDownLatch(1);

        doAnswer(inv -> {
            discordStarted.countDown();
            // Block Discord until released
            discordBlocker.await(5, TimeUnit.SECONDS);
            return null;
        }).when(discordGateway).log(any());

        doAnswer(inv -> {
            dbCompleted.countDown();
            return null;
        }).when(auditRepo).record(any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompositeAuditSink sink = new CompositeAuditSink(auditRepo, discordGateway, pool);

            sink.accept(sampleEvent);

            // Wait for Discord to start blocking
            assertTrue(discordStarted.await(5, TimeUnit.SECONDS));

            // Database write must complete even while Discord is blocked
            assertTrue(dbCompleted.await(5, TimeUnit.SECONDS),
                    "Database write must complete independently without waiting on Discord");
        } finally {
            discordBlocker.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("record() returns an observable completion future that completes when database persistence succeeds")
    void recordReturnsObservableFutureOnSuccess() throws Exception {
        AuditRepository auditRepo = mock(AuditRepository.class);
        DiscordGateway discordGateway = mock(DiscordGateway.class);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompositeAuditSink sink = new CompositeAuditSink(auditRepo, discordGateway, pool);

            CompletableFuture<Void> future = sink.record(sampleEvent);
            assertNotNull(future);
            assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));

            verify(auditRepo, times(1)).record(sampleEvent);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("record() future completes exceptionally when database persistence fails")
    void recordFutureCompletesExceptionallyOnDatabaseFailure() {
        AuditRepository auditRepo = mock(AuditRepository.class);
        DiscordGateway discordGateway = mock(DiscordGateway.class);
        doThrow(new StorageException("DB down")).when(auditRepo).record(any());

        CompositeAuditSink sink = new CompositeAuditSink(auditRepo, discordGateway, Runnable::run);

        CompletableFuture<Void> future = sink.record(sampleEvent);
        assertNotNull(future);
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(StorageException.class, ex.getCause());
    }

    @Test
    @DisplayName("drain() waits for pending database writes to complete within timeout")
    void drainWaitsForPendingWrites() throws Exception {
        AuditRepository auditRepo = mock(AuditRepository.class);
        DiscordGateway discordGateway = mock(DiscordGateway.class);

        CountDownLatch dbStarted = new CountDownLatch(1);
        CountDownLatch dbBlocker = new CountDownLatch(1);

        doAnswer(inv -> {
            dbStarted.countDown();
            dbBlocker.await(5, TimeUnit.SECONDS);
            return null;
        }).when(auditRepo).record(any());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompositeAuditSink sink = new CompositeAuditSink(auditRepo, discordGateway, pool);

            sink.record(sampleEvent);
            assertTrue(dbStarted.await(5, TimeUnit.SECONDS));

            // Release the blocker so the task can finish
            dbBlocker.countDown();

            boolean drained = sink.drain(Duration.ofSeconds(5));
            assertTrue(drained, "Sink must successfully drain within timeout");
            verify(auditRepo, times(1)).record(sampleEvent);
        } finally {
            dbBlocker.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("drain() logs warning with dropped count when timeout expires")
    void drainLogsDroppedCountOnTimeout() throws Exception {
        AuditRepository auditRepo = mock(AuditRepository.class);
        DiscordGateway discordGateway = mock(DiscordGateway.class);

        CountDownLatch blocker = new CountDownLatch(1);
        doAnswer(inv -> {
            blocker.await(10, TimeUnit.SECONDS);
            return null;
        }).when(auditRepo).record(any());

        List<LogRecord> logs = new CopyOnWriteArrayList<>();
        Logger testLogger = Logger.getLogger("DrainTestLogger");
        testLogger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompositeAuditSink sink = new CompositeAuditSink(() -> auditRepo, () -> discordGateway, pool, testLogger);

            sink.record(sampleEvent);

            boolean drained = sink.drain(Duration.ofMillis(100));
            assertFalse(drained, "Drain must return false when wait expires");

            boolean logFound = logs.stream().anyMatch(l ->
                    l.getLevel() == Level.WARNING
                            && l.getMessage() != null
                            && l.getMessage().contains("dropped")
                            && l.getMessage().contains("1 queued audit event"));
            assertTrue(logFound, "Warning must be logged stating the dropped count");
        } finally {
            blocker.countDown();
            pool.shutdownNow();
        }
    }
}
