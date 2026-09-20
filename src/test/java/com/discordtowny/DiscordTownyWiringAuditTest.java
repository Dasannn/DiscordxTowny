package com.discordtowny;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.config.YamlConfigLoader;
import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.discord.OperationOutcome;
import com.discordtowny.model.AuditEvent;
import com.discordtowny.model.SpaceRequest;
import com.discordtowny.space.SpaceService;
import com.discordtowny.storage.HikariStorage;
import com.discordtowny.storage.Storage;
import com.discordtowny.towny.TownyFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests verifying that {@link DiscordTownyWiring} orchestrates
 * the audit pipeline such that both database and Discord sinks receive events,
 * completions are observable, and queued writes are cleanly drained on shutdown.
 */
class DiscordTownyWiringAuditTest {

    private Path tempFolder;
    private Path dbFile;
    private Storage storage;
    private DiscordGateway discordGateway;
    private PluginConfig config;

    @BeforeEach
    void setUp() throws IOException {
        tempFolder = Files.createTempDirectory("dt-wiring-audit-test-");
        dbFile = tempFolder.resolve("test.db");

        PluginConfig.Database dbConfig = new PluginConfig.Database(
                PluginConfig.Database.Type.SQLITE,
                "localhost",
                3306,
                dbFile.toAbsolutePath().toString(),
                "",
                "",
                "dt_",
                1,
                1,
                Duration.ofSeconds(5)
        );

        storage = new HikariStorage(dbConfig, Logger.getLogger("WiringAuditTest"));
        storage.initialize();

        config = new PluginConfig(
                new PluginConfig.Discord("test-token", "guild-12345", Optional.empty()), // No log channel configured (default)
                dbConfig,
                new PluginConfig.Structure("Communities", "Archive", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Mayor", "{town}", Optional.empty(), false),
                new PluginConfig.Limits(2, 2, Duration.ofSeconds(60)),
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                new PluginConfig.Sync(Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPAIR, 20, Duration.ofSeconds(5)),
                new PluginConfig.Linking(Duration.ofMinutes(10), 3, Duration.ofMinutes(15), true),
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );

        discordGateway = mock(DiscordGateway.class);
        when(discordGateway.isAvailable()).thenReturn(true);
        when(discordGateway.verifyPermissions()).thenReturn(Optional.empty());
        when(discordGateway.submit(any())).thenReturn(CompletableFuture.completedFuture(OperationOutcome.success()));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (storage != null) {
            try {
                storage.close();
            } catch (Throwable ignored) {}
        }
        if (tempFolder != null) {
            try (var stream = Files.walk(tempFolder)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {}
                });
            }
        }
    }

    @Test
    @DisplayName("Wiring records no audit row on DISCORD_UNAVAILABLE space creation, but composite audit sink persists direct events with observable completion")
    void wiringWritesAuditRowToDatabaseWithUnconfiguredLogChannel() {
        YamlConfigLoader loader = mock(YamlConfigLoader.class);
        when(loader.load()).thenReturn(config);
        when(loader.messages()).thenReturn(mock(Messages.class));

        DiscordTownyWiring wiring = new DiscordTownyWiring(tempFolder, Logger.getLogger("Test"), Runnable::run, null, () -> {}, null, "1.0.0");
        wiring.setConfigLoaderForTest(loader);
        wiring.setConfigForTest(config);
        wiring.setStorageForTest(storage);
        wiring.setTownyFacadeForTest(mock(TownyFacade.class));

        // Wiring is built with no Discord gateway, falling back to DegradedDiscordGateway
        wiring.reload();

        assertNotNull(wiring.getAuditSink(), "Wiring must instantiate and expose the audit sink");
        assertNotNull(wiring.getSpaceService(), "SpaceService must be instantiated");

        UUID townUuid = UUID.randomUUID();
        SpaceRequest req = new SpaceRequest(
                townUuid, "Solitude", UUID.randomUUID(), List.of("res1", "res2"), "mayor-user-id", 2);

        // Path 1: When Discord is unavailable, DefaultSpaceService refuses creation at the
        // pre-admission check (!isDiscordReady()) before an operation is submitted to Discord.
        // Because creation fails admission, no audit event is emitted on this path.
        SpaceService.CreateResult result = wiring.getSpaceService().create(req).join();
        assertEquals(SpaceService.CreateResult.DISCORD_UNAVAILABLE, result);

        List<AuditEvent> events = storage.audit().recent("Solitude", 10);
        assertTrue(events.isEmpty(),
                "dt_audit_log must contain no row: admission refusal when Discord is down occurs before audit event emission");

        // Path 2: Verify that the wiring's composite audit sink itself remains functional while Discord is down.
        // Direct audit events delivered to the sink return an observable completion future that guarantees persistence.
        AuditEvent directEvent = new AuditEvent(
                Instant.now(), AuditEvent.Severity.ERROR, "mayor-user-id", "space_create_rejected",
                "Solitude", false, Optional.of("Discord is unavailable"));
        CompletableFuture<Void> completion = wiring.getAuditSink().record(directEvent);
        assertNotNull(completion, "Audit sink record must return an observable completion future");
        assertDoesNotThrow(() -> completion.get(5, TimeUnit.SECONDS),
                "Audit write completion future must complete without error");

        List<AuditEvent> recordedEvents = storage.audit().recent("Solitude", 10);
        assertEquals(1, recordedEvents.size(),
                "Direct audit events sent to the sink must be persisted to dt_audit_log even when Discord is unavailable");
        assertEquals("space_create_rejected", recordedEvents.get(0).action());
        assertEquals("Solitude", recordedEvents.get(0).target());
    }

    @Test
    @DisplayName("Wiring reload preserves audit sink functionality and persists events with succeeding gateway")
    void wiringReloadPreservesAuditSinkFunctionality() throws Exception {
        YamlConfigLoader loader = mock(YamlConfigLoader.class);
        when(loader.load()).thenReturn(config);
        when(loader.messages()).thenReturn(mock(Messages.class));

        DiscordTownyWiring wiring = new DiscordTownyWiring(tempFolder, Logger.getLogger("Test"), Runnable::run, null, () -> {}, null, "1.0.0");
        wiring.setConfigLoaderForTest(loader);
        wiring.setConfigForTest(config);
        wiring.setStorageForTest(storage);
        wiring.setTownyFacadeForTest(mock(TownyFacade.class));
        wiring.setDiscordGatewayForTest(discordGateway);

        wiring.reload();
        assertNotNull(wiring.getAuditSink());

        // Reload a second time to ensure state and services survive reload cycles
        wiring.reload();
        assertNotNull(wiring.getAuditSink());

        UUID townUuid = UUID.randomUUID();
        SpaceRequest req = new SpaceRequest(
                townUuid, "Whiterun", UUID.randomUUID(), List.of("res1", "res2"), "mayor-whiterun", 2);

        SpaceService.CreateResult result = wiring.getSpaceService().create(req).join();
        assertEquals(SpaceService.CreateResult.SUCCESS, result);

        // Bounded drain ensures the in-flight audit write is committed to the database
        assertTrue(wiring.getAuditSink().drain(Duration.ofSeconds(5)),
                "Audit sink must drain in-flight writes from space creation");

        List<AuditEvent> events = storage.audit().recent("Whiterun", 10);
        assertEquals(1, events.size(), "dt_audit_log must contain exactly 1 row for created space after reload");
        AuditEvent event = events.get(0);
        assertEquals("space_create", event.action());
        assertEquals("Whiterun", event.target());
        assertEquals("mayor-whiterun", event.actor());
        assertTrue(event.success());
        verify(discordGateway, times(1)).log(any(AuditEvent.class));
    }

    @Test
    @DisplayName("stop() drains queued audit write held in executor before database storage is closed")
    void stopDrainsQueuedAuditWriteBeforeStorageCloses() throws Exception {
        YamlConfigLoader loader = mock(YamlConfigLoader.class);
        when(loader.load()).thenReturn(config);
        when(loader.messages()).thenReturn(mock(Messages.class));

        DiscordTownyWiring wiring = new DiscordTownyWiring(tempFolder, Logger.getLogger("Test"), Runnable::run, null, () -> {}, null, "1.0.0");
        wiring.setConfigLoaderForTest(loader);
        wiring.setConfigForTest(config);
        wiring.setStorageForTest(storage);
        wiring.setTownyFacadeForTest(mock(TownyFacade.class));
        wiring.setDiscordGatewayForTest(discordGateway);

        wiring.reload();

        ExecutorService executor = wiring.getAuditExecutorForTest();
        assertNotNull(executor, "Wiring must have an owned audit executor");

        // Occupy the single executor thread with a blocking task so subsequent writes are queued
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        executor.execute(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        });

        assertTrue(workerStarted.await(5, TimeUnit.SECONDS), "Worker thread must start and hold the blocker task");

        // Submit an audit event while the executor worker is busy: this event is guaranteed to be
        // held in the executor queue without having commenced execution
        AuditEvent queuedEvent = new AuditEvent(
                Instant.now(), AuditEvent.Severity.WARNING, "operator-user", "server_shutdown_alert",
                "Falkreath", true, Optional.of("Emergency shutdown initiated"));
        CompletableFuture<Void> writeFuture = wiring.getAuditSink().record(queuedEvent);

        assertFalse(writeFuture.isDone(),
                "The audit write must still be held in the executor queue while the worker is occupied");

        // Release the worker and immediately call stop() while the audit write is queued.
        // stop() must drain the executor before closing storage.
        releaseWorker.countDown();
        wiring.stop();

        // The future must now be completed because stop() drained the executor
        assertTrue(writeFuture.isDone(),
                "The audit write future must be completed after stop() drains the executor");

        // Verify against SQLite on disk: storage was closed by stop(), so we open a new reader
        PluginConfig.Database dbConfig = config.database();
        Storage verifyStorage = new HikariStorage(dbConfig, Logger.getLogger("VerifyStorage"));
        verifyStorage.initialize();
        try {
            List<AuditEvent> recorded = verifyStorage.audit().recent("Falkreath", 10);
            assertEquals(1, recorded.size(),
                    "Queued audit write held by executor at stop() time must be persisted to dt_audit_log");
            assertEquals("server_shutdown_alert", recorded.get(0).action());
            assertEquals("Falkreath", recorded.get(0).target());
            assertEquals("operator-user", recorded.get(0).actor());
        } finally {
            verifyStorage.close();
        }
    }

    @Test
    @DisplayName("stop() bounded wait expires safely and logs dropped event count without hanging")
    void stopLogsDroppedCountWhenDrainTimeoutExpires() throws Exception {
        YamlConfigLoader loader = mock(YamlConfigLoader.class);
        when(loader.load()).thenReturn(config);
        when(loader.messages()).thenReturn(mock(Messages.class));

        // Use custom logger handler to capture warnings
        List<LogRecord> capturedLogs = new CopyOnWriteArrayList<>();
        Logger testLogger = Logger.getLogger("DrainTimeoutTest");
        testLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                capturedLogs.add(record);
            }
            @Override public void flush() {}
            @Override public void close() {}
        });

        DiscordTownyWiring wiring = new DiscordTownyWiring(tempFolder, testLogger, Runnable::run, null, () -> {}, null, "1.0.0");
        wiring.setConfigLoaderForTest(loader);
        wiring.setConfigForTest(config);
        wiring.setStorageForTest(storage);
        wiring.setTownyFacadeForTest(mock(TownyFacade.class));

        wiring.reload();

        // Configure a short 100ms drain timeout for test responsiveness
        wiring.setAuditDrainTimeoutForTest(Duration.ofMillis(100));

        // Permanently block the worker thread so the queue cannot drain
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch holdForever = new CountDownLatch(1);
        wiring.getAuditExecutorForTest().execute(() -> {
            blockerStarted.countDown();
            try {
                holdForever.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        });
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

        // Enqueue an event that will be stuck behind the permanent blocker
        AuditEvent stuckEvent = new AuditEvent(
                Instant.now(), AuditEvent.Severity.ERROR, "admin", "stuck_action",
                "Markarth", false, Optional.of("Timeout test"));
        wiring.getAuditSink().accept(stuckEvent);

        long start = System.currentTimeMillis();
        wiring.stop();
        long elapsed = System.currentTimeMillis() - start;

        holdForever.countDown();

        // Must not hang: should complete quickly (around the 100ms timeout)
        assertTrue(elapsed < 2000, "stop() must not hang when drain timeout expires");

        // Verify warning log exists and reports dropped count
        boolean warningFound = capturedLogs.stream().anyMatch(record ->
                record.getLevel() == Level.WARNING
                        && record.getMessage() != null
                        && record.getMessage().contains("dropped")
                        && record.getMessage().contains("1 queued audit event"));
        assertTrue(warningFound, "A warning stating the dropped event count must be logged on timeout");
    }
}
