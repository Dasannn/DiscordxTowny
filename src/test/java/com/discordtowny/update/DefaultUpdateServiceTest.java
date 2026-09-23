package com.discordtowny.update;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.config.YamlMessages;
import com.discordtowny.minecraft.EnglishMessages;
import com.discordtowny.model.AuditEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import java.net.http.HttpRequest;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class DefaultUpdateServiceTest {

    @TempDir
    Path tempDir;

    private Path activeJar;
    private Path updateFolder;
    private Logger testLogger;
    private List<LogRecord> logRecords;
    private List<AuditEvent> auditEvents;

    @BeforeEach
    void setUp() throws IOException {
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);

        activeJar = pluginsDir.resolve("DiscordTowny-0.1.0.jar");
        Files.writeString(activeJar, "LIVE_JAR_CURRENT_VERSION_BYTES", StandardCharsets.UTF_8);

        updateFolder = pluginsDir.resolve("update");
        // updateFolder may or may not exist initially

        logRecords = new CopyOnWriteArrayList<>();
        testLogger = Logger.getLogger("DefaultUpdateServiceTest-" + UUID.randomUUID());
        testLogger.setUseParentHandlers(false);
        testLogger.setLevel(Level.ALL);
        testLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });

        auditEvents = new CopyOnWriteArrayList<>();
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("A mismatched checksum leaves no file anywhere, and the active jar is untouched")
    void mismatchedChecksumLeavesNoFileAnywhereAndActiveJarUntouched() throws IOException {
        byte[] serverPayload = "MALICIOUS_OR_CORRUPT_PAYLOAD".getBytes(StandardCharsets.UTF_8);
        String expectedChecksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // does not match payload

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of("Content-Length", String.valueOf(serverPayload.length)), new ByteArrayInputStream(serverPayload));

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.Release release = new UpdateService.Release(
                "1.10.0",
                "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar",
                expectedChecksum,
                "Release notes"
        );

        UpdateService.DownloadResult result = service.download(release).join();

        // Claim 1: Download result is CHECKSUM_MISMATCH
        assertEquals(UpdateService.DownloadResult.CHECKSUM_MISMATCH, result);

        // Claim 2: The active jar is completely untouched
        assertTrue(Files.exists(activeJar), "Active jar must still exist");
        assertEquals("LIVE_JAR_CURRENT_VERSION_BYTES", Files.readString(activeJar), "Active jar content must not be altered");

        // Claim 3: No file left anywhere in updateFolder (no temp files, no final jar)
        if (Files.exists(updateFolder)) {
            try (Stream<Path> stream = Files.list(updateFolder)) {
                List<Path> files = stream.toList();
                assertTrue(files.isEmpty(), "Update folder must contain no files after checksum mismatch, but found: " + files);
            }
        }

        // Claim 4: isUpdatePending is false
        assertFalse(service.isUpdatePending());
    }

    @Test
    @DisplayName("A release with no checksum is refused")
    void releaseWithNoChecksumIsRefused() throws IOException {
        AtomicInteger networkHits = new AtomicInteger(0);
        HttpTransport transport = (uri, headers, timeout) -> {
            networkHits.incrementAndGet();
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(new byte[0]));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // Test with null checksum
        UpdateService.Release releaseNullSha = new UpdateService.Release("1.10.0", "https://example.com/dl.jar", null, "Notes");
        UpdateService.DownloadResult resultNull = service.download(releaseNullSha).join();
        assertEquals(UpdateService.DownloadResult.CHECKSUM_MISMATCH, resultNull);

        // Test with blank checksum
        UpdateService.Release releaseBlankSha = new UpdateService.Release("1.10.0", "https://example.com/dl.jar", "   ", "Notes");
        UpdateService.DownloadResult resultBlank = service.download(releaseBlankSha).join();
        assertEquals(UpdateService.DownloadResult.CHECKSUM_MISMATCH, resultBlank);

        // Claim: No network download was even attempted
        assertEquals(0, networkHits.get(), "No download network request should be initiated when checksum is missing");

        // Claim: No files left behind, active jar untouched
        assertEquals("LIVE_JAR_CURRENT_VERSION_BYTES", Files.readString(activeJar));
        if (Files.exists(updateFolder)) {
            try (Stream<Path> stream = Files.list(updateFolder)) {
                assertTrue(stream.toList().isEmpty());
            }
        }
        assertFalse(service.isUpdatePending());
    }

    @Test
    @DisplayName("A response larger than the cap is abandoned while downloading (F13)")
    void responseLargerThanCapIsAbandonedWhileDownloading() throws IOException {
        long capBytes = 1024; // 1 KB cap
        byte[] oversizedData = new byte[100 * 1024]; // 100 KB payload
        Arrays.fill(oversizedData, (byte) 0x41);
        String expectedChecksum = sha256Hex(oversizedData);
        String downloadUrl = "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar";

        // Case A: Absent Content-Length header
        AtomicInteger bytesConsumedNoHeader = new AtomicInteger(0);
        AtomicBoolean closedNoHeader = new AtomicBoolean(false);
        HttpTransport transportNoHeader = (uri, headers, timeout) -> {
            InputStream in = new ByteArrayInputStream(oversizedData) {
                @Override
                public synchronized int read(byte[] b, int off, int len) {
                    int read = super.read(b, off, Math.min(len, 512));
                    if (read > 0) {
                        bytesConsumedNoHeader.addAndGet(read);
                    }
                    return read;
                }

                @Override
                public void close() throws IOException {
                    closedNoHeader.set(true);
                    super.close();
                }
            };
            return new HttpTransport.HttpResponse(200, Map.of(), in);
        };

        DefaultUpdateService serviceA = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transportNoHeader,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                capBytes,
                Duration.ofSeconds(5)
        );

        UpdateService.Release releaseA = new UpdateService.Release("1.10.0", downloadUrl, expectedChecksum, "Notes");
        UpdateService.DownloadResult resultA = serviceA.download(releaseA).join();

        assertEquals(UpdateService.DownloadResult.TOO_LARGE, resultA);
        assertTrue(closedNoHeader.get(), "Stream must be closed and abandoned before EOF");
        assertTrue(bytesConsumedNoHeader.get() < oversizedData.length,
                "Must abandon stream well before consuming full 100KB: read " + bytesConsumedNoHeader.get());
        assertTrue(bytesConsumedNoHeader.get() <= capBytes + 8192,
                "Bytes read must be bounded around the cap plus single buffer: read " + bytesConsumedNoHeader.get());
        if (Files.exists(updateFolder)) {
            try (Stream<Path> stream = Files.list(updateFolder)) {
                assertTrue(stream.toList().isEmpty(), "No partial or temporary file must remain");
            }
        }
        assertEquals("LIVE_JAR_CURRENT_VERSION_BYTES", Files.readString(activeJar));
        assertFalse(serviceA.isUpdatePending());

        // Case B: Lying Content-Length header (claims 512 bytes, sends 100 KB)
        AtomicInteger bytesConsumedLying = new AtomicInteger(0);
        AtomicBoolean closedLying = new AtomicBoolean(false);
        HttpTransport transportLying = (uri, headers, timeout) -> {
            InputStream in = new ByteArrayInputStream(oversizedData) {
                @Override
                public synchronized int read(byte[] b, int off, int len) {
                    int read = super.read(b, off, Math.min(len, 512));
                    if (read > 0) {
                        bytesConsumedLying.addAndGet(read);
                    }
                    return read;
                }

                @Override
                public void close() throws IOException {
                    closedLying.set(true);
                    super.close();
                }
            };
            return new HttpTransport.HttpResponse(200, Map.of("Content-Length", "512"), in);
        };

        DefaultUpdateService serviceB = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transportLying,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                capBytes,
                Duration.ofSeconds(5)
        );

        UpdateService.Release releaseB = new UpdateService.Release("1.10.0", downloadUrl, expectedChecksum, "Notes");
        UpdateService.DownloadResult resultB = serviceB.download(releaseB).join();

        assertEquals(UpdateService.DownloadResult.TOO_LARGE, resultB);
        assertTrue(closedLying.get(), "Stream must be closed and abandoned before EOF when length header lies");
        assertTrue(bytesConsumedLying.get() < oversizedData.length,
                "Must abandon stream when length lies: read " + bytesConsumedLying.get());
        if (Files.exists(updateFolder)) {
            try (Stream<Path> stream = Files.list(updateFolder)) {
                assertTrue(stream.toList().isEmpty());
            }
        }
    }

    /**
     * Counts only the warnings about the outage itself. Counting every warning
     * would make this test fail for unrelated notices — a breaking release, for
     * one — which say nothing about whether the outage was logged once.
     */
    private long countOutageWarnings() {
        return logRecords.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .filter(r -> {
                    String m = r.getMessage() == null ? "" : r.getMessage().toLowerCase();
                    return m.contains("network") || m.contains("connection") || m.contains("unreachable")
                            || m.contains("check failed") || m.contains("could not");
                })
                .count();
    }

    @Test
    @DisplayName("No network: normal operation, one log line, and the second check does not log again")
    void noNetworkOperatesNormallyLogsOnceAndSecondCheckDoesNotLogAgain() {
        AtomicBoolean throwNetworkError = new AtomicBoolean(true);

        HttpTransport transport = (uri, headers, timeout) -> {
            if (throwNetworkError.get()) {
                throw new IOException("Connection refused: no route to host");
            }
            String validJson = """
                    {
                      "tag_name": "v1.10.0",
                      "body": "SHA-256: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                      "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"}]
                    }
                    """;
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(validJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // First check with network failure
        Optional<UpdateService.Release> firstCheck = service.checkForUpdate().join().release();
        assertTrue(firstCheck.isEmpty(), "Normal operation under network failure: returns Optional.empty()");

        long warningCountAfterFirst = countOutageWarnings();
        assertEquals(1, warningCountAfterFirst, "Must log network failure exactly once");

        // Second check with network failure still active
        Optional<UpdateService.Release> secondCheck = service.checkForUpdate().join().release();
        assertTrue(secondCheck.isEmpty(), "Second check returns Optional.empty()");

        long warningCountAfterSecond = countOutageWarnings();
        assertEquals(1, warningCountAfterSecond, "Second check must not log again while network failure persists");

        // Now restore network
        throwNetworkError.set(false);
        Optional<UpdateService.Release> thirdCheck = service.checkForUpdate().join().release();
        assertTrue(thirdCheck.isPresent(), "Check succeeds once network is restored");

        // Now simulate network failure dropping again
        throwNetworkError.set(true);
        Optional<UpdateService.Release> fourthCheck = service.checkForUpdate().join().release();
        assertTrue(fourthCheck.isEmpty());

        long warningCountAfterFourth = countOutageWarnings();
        assertEquals(2, warningCountAfterFourth, "Once network was restored, a new outage logs once again");
    }

    @Test
    @DisplayName("Version comparison, including equal, older, and a two-digit minor")
    void versionComparisonHandlesEqualOlderAndTwoDigitMinor() {
        // Two-digit minor test: 1.10.0 is newer than 1.9.0
        String releaseJson110 = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"}]
                }
                """;

        HttpTransport transport110 = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson110.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService serviceAgainst190 = new DefaultUpdateService(
                "1.9.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport110,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> updateOpt = serviceAgainst190.checkForUpdate().join().release();
        assertTrue(updateOpt.isPresent(), "1.10.0 must be recognized as newer than 1.9.0 (two-digit minor)");
        assertEquals("1.10.0", updateOpt.get().version());

        // Equal version test: running 1.10.0 and release is 1.10.0
        DefaultUpdateService serviceEqual = new DefaultUpdateService(
                "1.10.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport110,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> equalOpt = serviceEqual.checkForUpdate().join().release();
        assertTrue(equalOpt.isEmpty(), "Equal version 1.10.0 vs 1.10.0 is not an update");

        // Older version test: running 1.10.0 and release is 1.9.0
        String releaseJson190 = """
                {
                  "tag_name": "v1.9.0",
                  "body": "SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "assets": [{"name": "DiscordTowny-1.9.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.9.0/DiscordTowny-1.9.0.jar"}]
                }
                """;

        HttpTransport transport190 = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson190.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService serviceOlder = new DefaultUpdateService(
                "1.10.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport190,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> olderOpt = serviceOlder.checkForUpdate().join().release();
        assertTrue(olderOpt.isEmpty(), "Older version 1.9.0 vs 1.10.0 is not an update");
    }

    @Test
    @DisplayName("A malformed API response does not throw out of the service")
    void malformedApiResponseDoesNotThrowOutOfService() {
        List<String> malformedResponses = List.of(
                "<html><body>502 Bad Gateway</body></html>",
                "{\"tag_name\": \"v1.0.0\", \"incomplete",
                "",
                "[1, 2, 3]",
                "{\"unexpected_structure\": true}",
                "{\"tag_name\": \"v2.0.0\", \"assets\": []}"
        );

        for (String badJson : malformedResponses) {
            HttpTransport transport = (uri, headers, timeout) ->
                    new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(badJson.getBytes(StandardCharsets.UTF_8)));

            DefaultUpdateService service = new DefaultUpdateService(
                    "1.0.0",
                    new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                    updateFolder,
                    activeJar,
                    "DiscordTowny.jar",
                    transport,
                    testLogger,
                    auditEvents::add,
                    ForkJoinPool.commonPool(),
                    null,
                    false,
                    50 * 1024 * 1024L,
                    Duration.ofSeconds(5)
            );

            assertDoesNotThrow(() -> {
                Optional<UpdateService.Release> result = service.checkForUpdate().join().release();
                assertTrue(result.isEmpty(), "Malformed response should yield empty Optional rather than crashing");
            });
        }
    }

    @Test
    @DisplayName("Successful download verifies SHA-256 and atomically places jar into update folder (F3, F13)")
    void successfulDownloadVerifiesSha256AndMovesToUpdateFolder() throws IOException {
        byte[] payload = "NEW_VERSION_1_10_0_JAR_CONTENT".getBytes(StandardCharsets.UTF_8);
        String correctSha256 = sha256Hex(payload);
        String downloadUrl = "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar";

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of("Content-Length", String.valueOf(payload.length)), new ByteArrayInputStream(payload));

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.Release release = new UpdateService.Release(
                "1.10.0",
                downloadUrl,
                correctSha256,
                "Notes"
        );

        UpdateService.DownloadResult result = service.download(release).join();

        assertEquals(UpdateService.DownloadResult.SUCCESS, result);
        assertTrue(service.isUpdatePending(), "Update must be reported as pending restart");

        Path target = updateFolder.resolve("DiscordTowny.jar");
        assertTrue(Files.isRegularFile(target), "Downloaded file must be located in update folder");
        assertEquals("NEW_VERSION_1_10_0_JAR_CONTENT", Files.readString(target));

        // Active jar is completely untouched
        assertEquals("LIVE_JAR_CURRENT_VERSION_BYTES", Files.readString(activeJar));

        // Audit log event generated for download completion
        assertTrue(auditEvents.stream().anyMatch(e -> "update_downloaded".equals(e.action())),
                "AuditEvent for update_downloaded must be published to log channel");

        // F3 & F13: Observe filesystem state after failed publication - previous pending update is preserved
        String prevPendingContent = "PREVIOUSLY_VERIFIED_PENDING_UPDATE_CONTENT";
        Files.writeString(target, prevPendingContent, StandardCharsets.UTF_8);

        // Simulate transport returning corrupted payload for next download
        byte[] corruptPayload = "CORRUPT_PAYLOAD".getBytes(StandardCharsets.UTF_8);
        HttpTransport corruptTransport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(corruptPayload));

        DefaultUpdateService serviceNext = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                corruptTransport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.Release corruptRelease = new UpdateService.Release(
                "1.11.0",
                "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.11.0/DiscordTowny-1.11.0.jar",
                correctSha256, // mismatch with corruptPayload
                "Notes"
        );

        UpdateService.DownloadResult corruptResult = serviceNext.download(corruptRelease).join();
        assertEquals(UpdateService.DownloadResult.CHECKSUM_MISMATCH, corruptResult);

        // Crucial F3 guarantee: previously verified pending update was NOT destroyed or partially overwritten!
        assertTrue(Files.isRegularFile(target), "Previously verified update must still exist");
        assertEquals(prevPendingContent, Files.readString(target), "Previously verified update must remain completely intact");

        // F3 & F13: Exercise publishExecutable failure rollback when previous pending jar exists
        Path nonExistentStaging = updateFolder.resolve("non_existent_staging.tmp");
        assertThrows(IOException.class, () -> serviceNext.publishExecutable(nonExistentStaging, target));
        assertTrue(Files.isRegularFile(target), "Previous verified pending update must remain restored after publication failure");
        assertEquals(prevPendingContent, Files.readString(target), "Content of pending update must be intact after publication failure");

        try (Stream<Path> stream = Files.list(updateFolder)) {
            List<Path> leftOvers = stream.filter(p -> p.getFileName().toString().contains(".backup")).toList();
            assertTrue(leftOvers.isEmpty(), "No backup files must remain in update folder after rollback");
        }

        // F3 & F13: Destination cleanup - if destination did not exist prior to publication, failure leaves no partial file
        Path nonExistentTarget = updateFolder.resolve("UnpublishedDiscordTowny.jar");
        assertThrows(IOException.class, () -> serviceNext.publishExecutable(nonExistentStaging, nonExistentTarget));
        assertFalse(Files.exists(nonExistentTarget), "Destination file must not exist if publication failed and no prior file existed");
    }

    @Test
    @DisplayName("Notifications: console on startup, log channel once per version, and once on download (F13)")
    void notificationsConsoleOnStartupAndLogChannelOncePerVersion() {
        byte[] payload = "NEW_JAR_CONTENT".getBytes(StandardCharsets.UTF_8);
        String correctSha256 = sha256Hex(payload);
        String downloadUrl = "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: %s",
                  "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "%s"}]
                }
                """.formatted(correctSha256, downloadUrl);

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().contains("releases/latest")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            } else {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(payload));
            }
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.9.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), true, true), // autoDownload = true
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // First check: detects version and auto-downloads
        Optional<UpdateService.Release> release = service.checkForUpdate().join().release();
        assertTrue(release.isPresent());

        // F13: Synchronize with observed operation and notification completion (no race condition)
        for (int i = 0; i < 150; i++) {
            if (service.isUpdatePending() && auditEvents.stream().anyMatch(e -> "update_downloaded".equals(e.action()))) {
                break;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {}
        }
        assertTrue(service.isUpdatePending(), "Auto-download should complete and leave update pending");

        // Verify console message
        boolean consoleHasAvailable = logRecords.stream().anyMatch(r -> r.getMessage().contains("There is a new version of DiscordTowny"));
        assertTrue(consoleHasAvailable, "Console must be notified of available update");

        // Verify log channel audit event
        long availableAuditCount = auditEvents.stream().filter(e -> "update_available".equals(e.action())).count();
        assertEquals(1, availableAuditCount, "Log channel must be notified once per version for update_available");

        long downloadedAuditCount = auditEvents.stream().filter(e -> "update_downloaded".equals(e.action())).count();
        assertEquals(1, downloadedAuditCount, "Log channel must be notified once when download completes");

        // Check again: should NOT duplicate log channel events for the same version
        service.checkForUpdate().join();
        long availableAuditCountAfterSecond = auditEvents.stream().filter(e -> "update_available".equals(e.action())).count();
        assertEquals(1, availableAuditCountAfterSecond, "Log channel must NOT be notified multiple times for the same version");
    }

    @Test
    @DisplayName("Admin notification on join reports pending update or available version")
    void adminNotificationOnJoin() {
        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                testLogger,
                auditEvents::add
        );

        List<String> adminMessages = new ArrayList<>();

        // Case 1: No update available yet
        service.notifyAdminOnJoin(adminMessages::add);
        assertTrue(adminMessages.isEmpty());

        // Case 2: Update available detected
        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"}]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService serviceWithUpdate = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        serviceWithUpdate.checkForUpdate().join();
        serviceWithUpdate.notifyAdminOnJoin(adminMessages::add);

        assertEquals(1, adminMessages.size());
        assertTrue(adminMessages.getFirst().contains("1.10.0"));
    }

    @Test
    @DisplayName("Rate limit 403 or 429 respects reset header and uses cached release")
    void rateLimitRespectedAndCachedReleaseUsed() {
        AtomicInteger checkCount = new AtomicInteger(0);

        HttpTransport transport = (uri, headers, timeout) -> {
            int count = checkCount.incrementAndGet();
            if (count == 1) {
                String releaseJson = """
                        {
                          "tag_name": "v1.10.0",
                          "body": "SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                          "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"}]
                        }
                        """;
                return new HttpTransport.HttpResponse(200, Map.of("ETag", "\"tag123\""), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            } else {
                long futureReset = (System.currentTimeMillis() / 1000) + 3600;
                return new HttpTransport.HttpResponse(403, Map.of(
                        "X-RateLimit-Remaining", "0",
                        "X-RateLimit-Reset", String.valueOf(futureReset)
                ), new ByteArrayInputStream(new byte[0]));
            }
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> first = service.checkForUpdate().join().release();
        assertTrue(first.isPresent());

        // Second check hits 403 rate limit -> uses cached release
        Optional<UpdateService.Release> second = service.checkForUpdate().join().release();
        assertTrue(second.isPresent(), "Cached release should be returned when rate-limited");
        assertEquals("1.10.0", second.get().version());

        // Third check: because reset time is in the future, transport is not even queried
        Optional<UpdateService.Release> third = service.checkForUpdate().join().release();
        assertTrue(third.isPresent());
        assertEquals(2, checkCount.get(), "HTTP transport should not be queried while rate limit window is active");
    }

    @Test
    @DisplayName("ETag 304 Not Modified returns cached release")
    void etag304NotModifiedReturnsCachedRelease() {
        AtomicInteger count = new AtomicInteger(0);
        HttpTransport transport = (uri, headers, timeout) -> {
            if (count.incrementAndGet() == 1) {
                String releaseJson = """
                        {
                          "tag_name": "v1.10.0",
                          "body": "SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                          "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"}]
                        }
                        """;
                return new HttpTransport.HttpResponse(200, Map.of("ETag", "\"tag456\""), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            } else {
                assertEquals("\"tag456\"", headers.get("If-None-Match"));
                return new HttpTransport.HttpResponse(304, Map.of(), new ByteArrayInputStream(new byte[0]));
            }
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> first = service.checkForUpdate().join().release();
        assertTrue(first.isPresent());

        Optional<UpdateService.Release> second = service.checkForUpdate().join().release();
        assertTrue(second.isPresent());
        assertEquals("1.10.0", second.get().version());
    }

    @Test
    @DisplayName("Content-Length header exceeding cap aborts download upfront without reading body (F13)")
    void contentLengthExceedingCapAbortsUpfront() throws IOException {
        long capBytes = 1024;
        String validSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String downloadUrl = "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar";

        AtomicBoolean bodyReadCalled = new AtomicBoolean(false);
        InputStream trackingStream = new InputStream() {
            @Override
            public int read() {
                bodyReadCalled.set(true);
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                bodyReadCalled.set(true);
                return -1;
            }
        };

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of("Content-Length", "2048"), trackingStream);

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                capBytes,
                Duration.ofSeconds(5)
        );

        UpdateService.Release release = new UpdateService.Release("1.10.0", downloadUrl, validSha, "Notes");
        UpdateService.DownloadResult result = service.download(release).join();

        assertEquals(UpdateService.DownloadResult.TOO_LARGE, result);
        // F13: Prove rejection happened upfront before any body bytes were read
        assertFalse(bodyReadCalled.get(), "No body bytes should ever be read when Content-Length exceeds cap upfront!");
        if (Files.exists(updateFolder)) {
            try (Stream<Path> stream = Files.list(updateFolder)) {
                assertTrue(stream.toList().isEmpty());
            }
        }
    }

    @Test
    @DisplayName("HTTP 404 or 500 during download returns NETWORK_ERROR")
    void httpErrorDuringDownloadReturnsNetworkError() throws IOException {
        String validSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String downloadUrl = "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar";

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(404, Map.of(), new ByteArrayInputStream(new byte[0]));

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.Release release = new UpdateService.Release("1.10.0", downloadUrl, validSha, "Notes");
        UpdateService.DownloadResult result = service.download(release).join();

        assertEquals(UpdateService.DownloadResult.NETWORK_ERROR, result);
        if (Files.exists(updateFolder)) {
            try (Stream<Path> stream = Files.list(updateFolder)) {
                assertTrue(stream.toList().isEmpty());
            }
        }
    }

    @Test
    @DisplayName("Repository URL constant in code cannot be changed via configuration (F1, F13)")
    void repositoryUrlIsConstantInCode() {
        assertEquals("https://api.github.com/repos/Dasannn/DiscordxTowny/releases/latest", DefaultUpdateService.GITHUB_RELEASES_API);

        // F1 & F13: Observe destinations actually contacted or refused
        AtomicInteger transportHitCount = new AtomicInteger(0);
        List<URI> requestedUris = new CopyOnWriteArrayList<>();
        HttpTransport transport = (uri, headers, timeout) -> {
            transportHitCount.incrementAndGet();
            requestedUris.add(uri);
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // 1. Verify checkForUpdate contacts GITHUB_RELEASES_API
        service.checkForUpdate().join();
        assertEquals(1, requestedUris.size());
        assertEquals(URI.create(DefaultUpdateService.GITHUB_RELEASES_API), requestedUris.getFirst(),
                "checkForUpdate must strictly contact GITHUB_RELEASES_API");

        String validSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        transportHitCount.set(0);

        // 2. Attempt download from untrusted external domain
        UpdateService.Release untrustedHostRelease = new UpdateService.Release(
                "1.10.0", "https://evil.com/malicious.jar", validSha, "Notes");
        UpdateService.DownloadResult untrustedResult = service.download(untrustedHostRelease).join();
        assertEquals(UpdateService.DownloadResult.IO_ERROR, untrustedResult);
        assertEquals(0, transportHitCount.get(), "Transport must NEVER be contacted for untrusted destination!");

        // 3. Attempt download via plain HTTP
        UpdateService.Release plainHttpRelease = new UpdateService.Release(
                "1.10.0", "http://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar", validSha, "Notes");
        UpdateService.DownloadResult httpResult = service.download(plainHttpRelease).join();
        assertEquals(UpdateService.DownloadResult.IO_ERROR, httpResult);
        assertEquals(0, transportHitCount.get(), "Transport must NEVER be contacted for plain HTTP!");

        // 4. Attempt download from a different repository
        UpdateService.Release wrongRepoRelease = new UpdateService.Release(
                "1.10.0", "https://github.com/Attacker/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar", validSha, "Notes");
        UpdateService.DownloadResult wrongRepoResult = service.download(wrongRepoRelease).join();
        assertEquals(UpdateService.DownloadResult.IO_ERROR, wrongRepoResult);
        assertEquals(0, transportHitCount.get(), "Transport must NEVER be contacted for untrusted repository!");

        // 5. Attempt download from arbitrary raw.githubusercontent.com path
        UpdateService.Release rawGithubRelease = new UpdateService.Release(
                "1.10.0", "https://raw.githubusercontent.com/Attacker/Repo/main/payload.jar", validSha, "Notes");
        UpdateService.DownloadResult rawResult = service.download(rawGithubRelease).join();
        assertEquals(UpdateService.DownloadResult.IO_ERROR, rawResult);
        assertEquals(0, transportHitCount.get(), "Transport must NEVER be contacted for raw.githubusercontent without official provenance!");

        // 6. Attempt download with path traversal escape
        UpdateService.Release traversalRelease = new UpdateService.Release(
                "1.10.0", "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/../../Attacker/Repo/releases/download/v1.10.0/payload.jar", validSha, "Notes");
        UpdateService.DownloadResult traversalResult = service.download(traversalRelease).join();
        assertEquals(UpdateService.DownloadResult.IO_ERROR, traversalResult);
        assertEquals(0, transportHitCount.get(), "Transport must NEVER be contacted for path traversal escape!");

        // 7. Verify untrusted checksum asset URL in release response is rejected before contacting
        String untrustedChecksumAssetJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "Release without body hash",
                  "assets": [
                    {"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"},
                    {"name": "checksums.txt", "browser_download_url": "https://raw.githubusercontent.com/Attacker/Repo/main/checksums.txt"}
                  ]
                }
                """;
        HttpTransport checksumTestTransport = (uri, headers, timeout) -> {
            transportHitCount.incrementAndGet();
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(untrustedChecksumAssetJson.getBytes(StandardCharsets.UTF_8)));
        };
        DefaultUpdateService serviceWithUntrustedAsset = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                checksumTestTransport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );
        transportHitCount.set(0);
        Optional<UpdateService.Release> untrustedCheckResult = serviceWithUntrustedAsset.checkForUpdate().join().release();
        assertTrue(untrustedCheckResult.isEmpty(), "Release with untrusted checksum asset URL must be refused");
        assertEquals(1, transportHitCount.get(), "Untrusted checksum asset must NEVER be contacted over network!");
    }

    @Test
    @DisplayName("Published SHA-256 with 65 hex digits, non-hex suffix, or wrong artifact is refused without fallback (F2)")
    void publishedSha256With65HexDigitsIsRefused() {
        String baseValidSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        // 1. 65-digit digest: valid 64 digits + 1 extra digit '9'
        assertReleaseRefusedWithBody("SHA-256: " + baseValidSha + "9", null);

        // 2. Non-hex suffix: valid 64 hex digits + non-hex 'Hg'
        assertReleaseRefusedWithBody("SHA-256: " + baseValidSha + "Hg", null);

        // 3. Digest bound to wrong artifact
        assertReleaseRefusedWithBody("SHA-256: " + baseValidSha + " for OtherPlugin.jar", null);
        assertReleaseRefusedWithBody("SHA-256: " + baseValidSha + " OtherPlugin.jar", null);

        // 4. Invalid or ambiguous body digest must NEVER fall back to valid checksum asset!
        String validAssetContent = baseValidSha + "  DiscordTowny-1.10.0.jar\n";
        assertReleaseRefusedWithBody("SHA-256: " + baseValidSha + "Hg", validAssetContent);
    }

    private void assertReleaseRefusedWithBody(String bodyText, String checksumAssetContent) {
        String assetEntries = """
                {"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"}
                """;
        if (checksumAssetContent != null) {
            assetEntries += """
                    , {"name": "checksums.txt", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/checksums.txt"}
                    """;
        }

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [%s]
                }
                """.formatted(bodyText.replace("\n", "\\n").replace("\"", "\\\""), assetEntries);

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith("checksums.txt") && checksumAssetContent != null) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(checksumAssetContent.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> releaseOpt = service.checkForUpdate().join().release();
        assertTrue(releaseOpt.isEmpty(), "Release with malformed, wrong-artifact, or ambiguous digest must be refused: " + bodyText);
    }

    @Test
    @DisplayName("checksums.txt with sources jar first correctly binds to runnable jar, and conflicts refuse without fallback (F2)")
    void checksumsTxtWithSourcesJarFirstExtractsRunnableJarSha() {
        String sourcesSha = "1111111111111111111111111111111111111111111111111111111111111111";
        String runnableSha = "2222222222222222222222222222222222222222222222222222222222222222";
        String conflictingSha = "3333333333333333333333333333333333333333333333333333333333333333";

        String checksumsContent = """
                %s  DiscordTowny-1.10.0-sources.jar
                %s  DiscordTowny-1.10.0.jar
                """.formatted(sourcesSha, runnableSha);

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "Release notes without hash",
                  "assets": [
                    {"name": "DiscordTowny-1.10.0-sources.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0-sources.jar"},
                    {"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"},
                    {"name": "checksums.txt", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/checksums.txt"}
                  ]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith("checksums.txt")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(checksumsContent.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> releaseOpt = service.checkForUpdate().join().release();
        assertTrue(releaseOpt.isPresent(), "Release with checksums.txt should be parsed successfully");
        assertEquals(runnableSha, releaseOpt.get().sha256(), "Must extract runnable jar SHA, NOT sources jar SHA!");

        // 1. Conflicting checksum assets must refuse without falling back to body
        String conflictingSha256Sums = conflictingSha + "  DiscordTowny-1.10.0.jar\n";
        String multiAssetJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: %s",
                  "assets": [
                    {"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"},
                    {"name": "checksums.txt", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/checksums.txt"},
                    {"name": "SHA256SUMS", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/SHA256SUMS"}
                  ]
                }
                """.formatted(runnableSha);

        HttpTransport conflictingAssetsTransport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith("checksums.txt")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(checksumsContent.getBytes(StandardCharsets.UTF_8)));
            }
            if (uri.toString().endsWith("SHA256SUMS")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(conflictingSha256Sums.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(multiAssetJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService serviceConflictingAssets = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                conflictingAssetsTransport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> conflictingResult = serviceConflictingAssets.checkForUpdate().join().release();
        assertTrue(conflictingResult.isEmpty(), "Conflicting checksum assets must refuse without falling back to body digest!");

        // 2. Conflicting body digests must refuse without falling back to asset
        String conflictingBodyJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: %s DiscordTowny-1.10.0.jar\\nSHA-256: %s DiscordTowny-1.10.0.jar",
                  "assets": [
                    {"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"},
                    {"name": "checksums.txt", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/checksums.txt"}
                  ]
                }
                """.formatted(runnableSha, conflictingSha);

        HttpTransport conflictingBodyTransport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith("checksums.txt")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(checksumsContent.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(conflictingBodyJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService serviceConflictingBody = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                conflictingBodyTransport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> conflictingBodyResult = serviceConflictingBody.checkForUpdate().join().release();
        assertTrue(conflictingBodyResult.isEmpty(), "Conflicting body digests must refuse without falling back to checksum asset!");
    }

    @Test
    @DisplayName("Active jar path alias with parent traversal is refused (F4)")
    void activeJarPathAliasWithParentTraversalIsRefused() throws IOException {
        Path pluginsDir = tempDir.resolve("plugins");
        Path activePluginJar = pluginsDir.resolve("DiscordTowny.jar");
        Files.writeString(activePluginJar, "ACTIVE_PLUGIN_JAR_BYTES", StandardCharsets.UTF_8);

        // Traversing update folder: plugins/update/.. resolves to plugins/
        Path traversingFolder = pluginsDir.resolve("update").resolve("..");

        byte[] payload = "NEW_PAYLOAD".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(payload);
        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(payload));

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                traversingFolder,
                activePluginJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.Release release = new UpdateService.Release(
                "1.10.0",
                "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar",
                sha,
                "Notes"
        );

        UpdateService.DownloadResult result = service.download(release).join();
        assertEquals(UpdateService.DownloadResult.IO_ERROR, result);
        assertEquals("ACTIVE_PLUGIN_JAR_BYTES", Files.readString(activePluginJar), "Active jar must remain completely untouched!");
    }

    @Test
    @DisplayName("targetJarName with path traversal is refused in constructor (F4)")
    void targetJarNameTraversingOrAbsoluteIsRefusedInConstructor() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "../DiscordTowny.jar",
                null,
                testLogger,
                null,
                null,
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        ));

        assertThrows(IllegalArgumentException.class, () -> new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "/etc/DiscordTowny.jar",
                null,
                testLogger,
                null,
                null,
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        ));
    }

    @Test
    @DisplayName("Stalled body streaming times out and cleans up temporary file (F5)")
    void stalledBodyStreamingTimesOutAndCleansTempFile() throws IOException {
        byte[] payload = "SOME_PAYLOAD".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(payload);

        InputStream stalledStream = new InputStream() {
            int count = 0;
            @Override
            public int read() throws IOException {
                if (count++ < 10) {
                    return 0x41;
                }
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    throw new IOException("Stream read interrupted", e);
                }
                return -1;
            }
        };

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), stalledStream);

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofMillis(150) // Short timeout for test
        );

        UpdateService.Release release = new UpdateService.Release(
                "1.10.0",
                "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar",
                sha,
                "Notes"
        );

        long start = System.currentTimeMillis();
        UpdateService.DownloadResult result = service.download(release).join();
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(UpdateService.DownloadResult.NETWORK_ERROR, result);
        assertTrue(elapsed < 4000, "Download should time out well before 10s stall: took " + elapsed + "ms");
        if (Files.exists(updateFolder)) {
            try (Stream<Path> stream = Files.list(updateFolder)) {
                assertTrue(stream.toList().isEmpty(), "Stalled download must clean up all temporary files on timeout");
            }
        }
    }

    @Test
    @DisplayName("Stalled body streaming unblocks when stream is closed and cleans up temporary file (F5)")
    void stalledBodyStreamingHonouringCloseTimesOutAndCleansTempFile() throws IOException {
        byte[] payload = "SOME_PAYLOAD".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(payload);

        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream socketLikeStream = new InputStream() {
            int count = 0;
            @Override
            public int read() throws IOException {
                if (count++ < 10) {
                    return 0x41;
                }
                while (!closed.get()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        throw new IOException("Stream read interrupted", e);
                    }
                }
                throw new IOException("Socket closed");
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), socketLikeStream);

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofMillis(150)
        );

        UpdateService.Release release = new UpdateService.Release(
                "1.10.0",
                "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar",
                sha,
                "Notes"
        );

        long start = System.currentTimeMillis();
        UpdateService.DownloadResult result = service.download(release).join();
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(UpdateService.DownloadResult.NETWORK_ERROR, result);
        assertTrue(elapsed < 4000, "Download should abort quickly upon stream close: took " + elapsed + "ms");
        if (Files.exists(updateFolder)) {
            try (Stream<Path> stream = Files.list(updateFolder)) {
                assertTrue(stream.toList().isEmpty(), "Stalled download must clean up all temporary files on close");
            }
        }
    }

    @Test
    @DisplayName("service.stop() cancels in-flight download and prevents publication (F5)")
    void serviceStopCancelsInFlightDownloadAndPreventsPublication() throws Exception {
        byte[] payload = "SOME_VALID_PAYLOAD_FOR_STOP_TEST".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(payload);

        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch stopCalled = new CountDownLatch(1);
        InputStream controlledStream = new InputStream() {
            private int index = 0;
            @Override
            public int read() throws IOException {
                readStarted.countDown();
                try {
                    stopCalled.await();
                } catch (InterruptedException e) {
                    throw new IOException("Interrupted by abort during stop", e);
                }
                if (index < payload.length) {
                    return payload[index++] & 0xFF;
                }
                return -1;
            }
        };

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of("Content-Length", String.valueOf(payload.length)), controlledStream);

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(10)
        );

        UpdateService.Release release = new UpdateService.Release(
                "1.10.0",
                "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar",
                sha,
                "Notes"
        );

        var downloadFuture = service.download(release);
        // Ensure worker has actively started reading before calling stop
        assertTrue(readStarted.await(5, TimeUnit.SECONDS), "Worker thread must start reading stream");

        long stopStart = System.currentTimeMillis();
        service.stop(); // Stop service during active download
        long stopDuration = System.currentTimeMillis() - stopStart;
        assertTrue(stopDuration < 2000, "service.stop() must execute promptly: took " + stopDuration + "ms");

        stopCalled.countDown(); // unblock stream if still waiting

        UpdateService.DownloadResult result = downloadFuture.join();
        assertNotEquals(UpdateService.DownloadResult.SUCCESS, result, "Stopped service must never report download SUCCESS");

        Path target = updateFolder.resolve("DiscordTowny.jar");
        assertFalse(Files.exists(target), "Stopped service must never publish executable jar even with valid payload");
        if (Files.exists(updateFolder)) {
            try (Stream<Path> stream = Files.list(updateFolder)) {
                assertTrue(stream.toList().isEmpty(), "No temp files should remain after stop");
            }
        }
    }

    @Test
    @DisplayName("JdkHttpTransport rejects untrusted redirect destination (F1)")
    void jdkHttpTransportRejectsUntrustedRedirect() {
        // 1. Direct constructor rejection of auto-redirecting HttpClient
        HttpClient autoRedirectClient = mock(HttpClient.class);
        when(autoRedirectClient.followRedirects()).thenReturn(HttpClient.Redirect.NORMAL);
        assertThrows(IllegalArgumentException.class, () -> new JdkHttpTransport(autoRedirectClient),
                "JdkHttpTransport must reject auto-redirecting HttpClient");

        // 2. Direct calls to untrusted destinations reject with specific policy violation messages
        JdkHttpTransport transport = new JdkHttpTransport();

        // Testing direct call to untrusted domain
        IOException evilEx = assertThrows(IOException.class, () ->
                transport.executeGet(URI.create("https://evil.com/malicious.jar"), Map.of(), Duration.ofSeconds(1)));
        assertTrue(evilEx.getMessage().contains("Untrusted destination rejected by official source policy"),
                "Must be refused by policy upfront: " + evilEx.getMessage());

        // Testing direct call to plain HTTP
        IOException httpEx = assertThrows(IOException.class, () ->
                transport.executeGet(URI.create("http://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"), Map.of(), Duration.ofSeconds(1)));
        assertTrue(httpEx.getMessage().contains("Untrusted destination rejected by official source policy"),
                "Must be refused due to policy: " + httpEx.getMessage());

        // Testing direct call to wrong GitHub repository
        IOException repoEx = assertThrows(IOException.class, () ->
                transport.executeGet(URI.create("https://github.com/Other/Repo/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"), Map.of(), Duration.ofSeconds(1)));
        assertTrue(repoEx.getMessage().contains("Untrusted destination rejected by official source policy"),
                "Must be refused due to policy: " + repoEx.getMessage());

        // 3. Follow redirect chain: 302 to untrusted destination must be intercepted and rejected
        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);

        URI officialUri = URI.create("https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar");

        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<InputStream> mockResponse = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(302);
        when(mockResponse.uri()).thenReturn(officialUri);
        when(mockResponse.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        HttpHeaders headersEvil = HttpHeaders.of(
                Map.of("Location", List.of("https://evil.com/malicious.jar")),
                (k, v) -> true
        );
        when(mockResponse.headers()).thenReturn(headersEvil);
        try {
            when(mockClient.<InputStream>send(any(), any())).thenReturn(mockResponse);
        } catch (IOException | InterruptedException ignored) {}

        JdkHttpTransport redirectTransport = new JdkHttpTransport(mockClient);

        IOException redirEx = assertThrows(IOException.class, () ->
                redirectTransport.executeGet(officialUri, Map.of(), Duration.ofSeconds(1)));
        assertTrue(redirEx.getMessage().contains("Untrusted destination rejected by official source policy: https://evil.com/malicious.jar"),
                "Redirect to untrusted destination must be refused by Location guard: " + redirEx.getMessage());

        // 4. Follow redirect chain: 302 to raw.githubusercontent.com without official provenance
        HttpHeaders headersRaw = HttpHeaders.of(
                Map.of("Location", List.of("https://raw.githubusercontent.com/Attacker/Repo/main/payload.jar")),
                (k, v) -> true
        );
        when(mockResponse.headers()).thenReturn(headersRaw);

        IOException redirRawEx = assertThrows(IOException.class, () ->
                redirectTransport.executeGet(officialUri, Map.of(), Duration.ofSeconds(1)));
        assertTrue(redirRawEx.getMessage().contains("Untrusted destination rejected by official source policy: https://raw.githubusercontent.com/"),
                "Redirect to raw.githubusercontent without provenance must be refused: " + redirRawEx.getMessage());

        // 5. Follow redirect chain: HTTPS downgrade attempt
        HttpHeaders headersHttp = HttpHeaders.of(
                Map.of("Location", List.of("http://release-assets.githubusercontent.com/downgrade.jar")),
                (k, v) -> true
        );
        when(mockResponse.headers()).thenReturn(headersHttp);

        IOException redirHttpEx = assertThrows(IOException.class, () ->
                redirectTransport.executeGet(officialUri, Map.of(), Duration.ofSeconds(1)));
        assertTrue(redirHttpEx.getMessage().contains("Untrusted destination rejected by official source policy: http://"),
                "HTTP downgrade redirect must be refused: " + redirHttpEx.getMessage());

        try {
            // Verify no request ever reached an untrusted destination
            verify(mockClient, times(3)).send(argThat(req -> officialUri.equals(req.uri())), any());
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("Release with different major version suppresses auto-download and awaits confirmation (F6)")
    void breakingReleaseWithDifferentMajorVersionSuppressesAutoDownload() {
        byte[] payload = "NEW_MAJOR_JAR".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(payload);
        String downloadUrl = "https://github.com/Dasannn/DiscordxTowny/releases/download/v2.0.0/DiscordTowny-2.0.0.jar";

        String releaseJson = """
                {
                  "tag_name": "v2.0.0",
                  "body": "Major release notes\\nSHA-256: %s",
                  "assets": [{"name": "DiscordTowny-2.0.0.jar", "browser_download_url": "%s"}]
                }
                """.formatted(sha, downloadUrl);

        AtomicInteger jarRequestCount = new AtomicInteger(0);
        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".jar")) {
                jarRequestCount.incrementAndGet();
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(payload));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.9.0", // Major 1 vs release Major 2
                new PluginConfig.Updates(true, Duration.ofHours(12), true, true), // autoDownload = true
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> releaseOpt = service.checkForUpdate().join().release();
        assertTrue(releaseOpt.isPresent());
        UpdateService.Release release = releaseOpt.get();

        assertTrue(service.isBreaking(release), "Major version bump (1.x -> 2.x) must be recognized as breaking");
        assertTrue(service.isAwaitingConfirmation(), "Breaking release must await admin confirmation");
        assertFalse(service.isUpdatePending(), "Auto-download must be suppressed for breaking release");
        assertEquals(0, jarRequestCount.get(), "Transport must NEVER be contacted for jar download when auto-download is suppressed");

        Path target = updateFolder.resolve("DiscordTowny.jar");
        assertFalse(Files.exists(target), "Target jar must not be staged during suppressed auto-download");

        // Verify admin notification mentions breaking change warning
        List<String> adminMessages = new ArrayList<>();
        service.notifyAdminOnJoin(adminMessages::add);
        assertEquals(1, adminMessages.size());
        assertTrue(adminMessages.getFirst().contains("breaking") || adminMessages.getFirst().contains("WARNING") || adminMessages.getFirst().contains("incompatibles"));
    }

    @Test
    @DisplayName("Release notes with [breaking] tag suppresses auto-download even within same major version (F6)")
    void breakingReleaseWithBreakingTagInNotesSuppressesAutoDownload() {
        byte[] payload = "NEW_MINOR_JAR".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(payload);
        String downloadUrl = "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar";

        // Supply inline [breaking] marker in release notes
        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "[breaking] Migrate your database configuration\\nSHA-256: %s",
                  "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "%s"}]
                }
                """.formatted(sha, downloadUrl);

        AtomicInteger jarRequestCount = new AtomicInteger(0);
        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".jar")) {
                jarRequestCount.incrementAndGet();
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(payload));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.9.0", // Same major version
                new PluginConfig.Updates(true, Duration.ofHours(12), true, true), // autoDownload = true
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> releaseOpt = service.checkForUpdate().join().release();
        assertTrue(releaseOpt.isPresent());
        UpdateService.Release release = releaseOpt.get();

        assertTrue(service.isBreaking(release), "Release notes containing inline [breaking] tag must be recognized as breaking");
        assertTrue(service.isAwaitingConfirmation());
        assertFalse(service.isUpdatePending(), "Auto-download must be suppressed for [breaking] tagged release");
        assertEquals(0, jarRequestCount.get(), "Transport must NEVER be contacted for jar download when auto-download is suppressed");

        Path target = updateFolder.resolve("DiscordTowny.jar");
        assertFalse(Files.exists(target), "Target jar must not be staged during suppressed auto-download");

        // Verify that isBreaking recognizes [breaking] anywhere in notes case-insensitively
        UpdateService.Release inlineRelease = new UpdateService.Release("1.10.0", downloadUrl, sha, "Notes contain [BREAKING] change in middle");
        assertTrue(service.isBreaking(inlineRelease), "isBreaking must return true when [breaking] is inline case-insensitively");
    }

    @Test
    @DisplayName("extractSummary strips [breaking] and SHA-256 and is included in admin notifications (F6)")
    void changesSummaryFromNotesIncludedInNotifications() {
        String notes = """
                New town bank limits and Discord roles sync.
                [breaking]
                SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
                """;

        String summary = DefaultUpdateService.extractSummary(notes);
        assertEquals("New town bank limits and Discord roles sync.", summary);
        assertFalse(summary.contains("[breaking]"));
        assertFalse(summary.contains("0123456789abcdef"));

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"}]
                }
                """.formatted(notes.replace("\n", "\\n"));

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService service = new DefaultUpdateService(
                "1.9.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        service.checkForUpdate().join();

        List<String> adminMessages = new ArrayList<>();
        service.notifyAdminOnJoin(adminMessages::add);
        assertEquals(1, adminMessages.size());
        assertTrue(adminMessages.getFirst().contains("New town bank limits and Discord roles sync."));
    }

    @Test
    @DisplayName("Join notification and audit detail use language catalog for Spanish and English (F7)")
    void joinNotificationAndAuditDetailUseLanguageCatalogInSpanishAndEnglish() {
        Map<String, String> esTexts = Map.of(
                "prefix", "[DT] ",
                "updates.available", "Hay una version nueva: {latest} (tienes {current}).",
                "updates.breaking", "La versión {latest} contiene cambios incompatibles.",
                "updates.summary", "Resumen de cambios: {summary}",
                "updates.downloaded", "La version {latest} esta descargada."
        );
        Messages esMessages = new YamlMessages(esTexts, s -> {});

        String releaseJson = """
                {
                  "tag_name": "v2.0.0",
                  "body": "Nuevas funciones\\nSHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "assets": [{"name": "DiscordTowny-2.0.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v2.0.0/DiscordTowny-2.0.0.jar"}]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                () -> esMessages,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        service.checkForUpdate().join();

        // 1. Check join notification in Spanish
        List<String> adminMessages = new ArrayList<>();
        service.notifyAdminOnJoin(adminMessages::add);
        assertEquals(1, adminMessages.size());
        String msg = adminMessages.getFirst();
        assertTrue(msg.contains("Hay una version nueva: 2.0.0"), "Message must use Spanish catalog text");
        assertTrue(msg.contains("cambios incompatibles"), "Message must use Spanish breaking text");

        // 2. The Discord log channel belongs to the log boundary, which spec 9.1 keeps
        // in English however the players are served. The same run therefore produces a
        // Spanish notice above and an English audit detail here.
        AuditEvent availableEvent = auditEvents.stream()
                .filter(e -> "update_available".equals(e.action()))
                .findFirst()
                .orElseThrow();
        assertTrue(availableEvent.detail().isPresent());
        String auditDetail = availableEvent.detail().get();
        assertFalse(auditDetail.startsWith("[DT]"), "Audit detail must NOT contain chat prefix");
        // The detail is one line: the notice, then the breaking warning, then the summary.
        assertTrue(
                auditDetail.startsWith(EnglishMessages.bundled().label("updates.available",
                        Map.of("latest", "2.0.0", "current", "1.0.0"))),
                "Audit detail must come from the bundled English catalog");
        assertTrue(
                auditDetail.contains(EnglishMessages.bundled().label("updates.breaking",
                        Map.of("latest", "2.0.0"))),
                "The breaking-change warning must be English too");
        assertFalse(auditDetail.contains("Hay una version nueva"),
                "The configured player catalog must never reach the Discord log channel");
    }

    @Test
    @DisplayName("Truncated body with new ETag does not commit ETag so subsequent check can recover (F8)")
    void truncatedBodyWithNewEtagDoesNotCommitEtagSoSubsequent304CanRecover() {
        AtomicInteger checkCount = new AtomicInteger(0);

        String fullReleaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"}]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) -> {
            int count = checkCount.incrementAndGet();
            if (count == 1) {
                // First check: sends new ETag "etag-v1", but body is truncated / malformed JSON
                return new HttpTransport.HttpResponse(200, Map.of("ETag", "\"etag-v1\""),
                        new ByteArrayInputStream("{\"tag_name\": \"v1.10.0\"".getBytes(StandardCharsets.UTF_8))); // unclosed JSON
            } else if (count == 2) {
                // Second check: If-None-Match MUST NOT be "etag-v1" because body parsing failed!
                assertNull(headers.get("If-None-Match"), "ETag must NOT have been committed after a failed parse");
                return new HttpTransport.HttpResponse(200, Map.of("ETag", "\"etag-v1\""),
                        new ByteArrayInputStream(fullReleaseJson.getBytes(StandardCharsets.UTF_8)));
            } else {
                // Third check: now that count 2 succeeded, ETag was committed, so 304 can be safely sent
                assertEquals("\"etag-v1\"", headers.get("If-None-Match"));
                return new HttpTransport.HttpResponse(304, Map.of(), new ByteArrayInputStream(new byte[0]));
            }
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // Check 1: truncated body fails parse
        Optional<UpdateService.Release> first = service.checkForUpdate().join().release();
        assertTrue(first.isEmpty(), "Truncated JSON should fail parsing");

        // Check 2: succeeds and commits ETag
        Optional<UpdateService.Release> second = service.checkForUpdate().join().release();
        assertTrue(second.isPresent());
        assertEquals("1.10.0", second.get().version());

        // Check 3: 304 returns cached release
        Optional<UpdateService.Release> third = service.checkForUpdate().join().release();
        assertTrue(third.isPresent());
        assertEquals("1.10.0", third.get().version());
    }

    @Test
    @DisplayName("ETag 304 retries unfinished auto-download when connectivity is restored (F8)")
    void etag304RetriesUnfinishedAutoDownloadWhenConnectivityRestored() throws Exception {
        byte[] payload = "NEW_JAR_CONTENT".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(payload);
        String downloadUrl = "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: %s",
                  "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "%s"}]
                }
                """.formatted(sha, downloadUrl);

        AtomicInteger jarDownloadAttempts = new AtomicInteger(0);
        AtomicInteger checkAttempts = new AtomicInteger(0);

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().contains("releases/latest")) {
                int c = checkAttempts.incrementAndGet();
                if (c == 1) {
                    return new HttpTransport.HttpResponse(200, Map.of("ETag", "\"etag-retry\""),
                            new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
                } else {
                    return new HttpTransport.HttpResponse(304, Map.of(), new ByteArrayInputStream(new byte[0]));
                }
            } else {
                int j = jarDownloadAttempts.incrementAndGet();
                if (j == 1) {
                    // First download attempt fails (asset server temporary 500)
                    return new HttpTransport.HttpResponse(500, Map.of(), new ByteArrayInputStream(new byte[0]));
                } else {
                    // Second download attempt succeeds
                    return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(payload));
                }
            }
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), true, true), // autoDownload = true
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // Check 1: 200 OK for release metadata, but jar download fails with HTTP 500
        service.checkForUpdate().join();
        Thread.sleep(150);
        assertFalse(service.isUpdatePending(), "Download failed so update must not be pending");

        // Check 2: 304 Not Modified; cached metadata should trigger auto-download retry
        service.checkForUpdate().join();

        // Wait for retry download to complete
        for (int i = 0; i < 150; i++) {
            if (service.isUpdatePending()) {
                break;
            }
            Thread.sleep(20);
        }

        assertTrue(service.isUpdatePending(), "304 must have retried unfinished auto-download and succeeded");
        assertEquals(2, jarDownloadAttempts.get(), "Asset server should have been retried on 304");
    }

    @Test
    @DisplayName("Repeated body read failures are logged only once until connectivity is restored (F9)")
    void repeatedBodyReadFailuresLoggedOnlyOnceUntilSuccess() {
        AtomicInteger checkCount = new AtomicInteger(0);
        String validJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"}]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) -> {
            int c = checkCount.incrementAndGet();
            if (c == 1 || c == 2) {
                // Fail during body read
                InputStream failingStream = new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("Connection reset while streaming release body");
                    }
                };
                return new HttpTransport.HttpResponse(200, Map.of(), failingStream);
            } else if (c == 3) {
                // Success: clears suppression flag
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(validJson.getBytes(StandardCharsets.UTF_8)));
            } else {
                // Fail again: should log a new warning
                InputStream failingStream = new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("Connection reset again");
                    }
                };
                return new HttpTransport.HttpResponse(200, Map.of(), failingStream);
            }
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // Check 1: fails body read -> logs warning
        service.checkForUpdate().join();
        long warningsAfter1 = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("could not reach GitHub")).count();
        assertEquals(1, warningsAfter1);

        // Check 2: fails body read -> suppressed, no new warning
        service.checkForUpdate().join();
        long warningsAfter2 = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("could not reach GitHub")).count();
        assertEquals(1, warningsAfter2, "Second consecutive body read failure must be suppressed");

        // Check 3: succeeds -> resets suppression
        service.checkForUpdate().join();

        // Check 4: fails body read again -> logs second warning
        service.checkForUpdate().join();
        long warningsAfter4 = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("could not reach GitHub")).count();
        assertEquals(2, warningsAfter4, "Subsequent body read failure after recovery must log a warning");
    }

    @Test
    @DisplayName("Auto-download non-success results log diagnostic warning (F9)")
    void autoDownloadNonSuccessResultsAreLoggedWithDiagnosticContext() throws Exception {
        byte[] payload = "WRONG_BYTES".getBytes(StandardCharsets.UTF_8);
        String expectedSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String downloadUrl = "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: %s",
                  "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "%s"}]
                }
                """.formatted(expectedSha, downloadUrl);

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().contains("releases/latest")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            } else {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(payload));
            }
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), true, true), // autoDownload = true
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        service.checkForUpdate().join();

        // Wait for auto-download attempt
        Thread.sleep(200);

        boolean diagnosticLogged = logRecords.stream().anyMatch(r ->
                r.getLevel() == Level.WARNING &&
                r.getMessage() != null &&
                r.getMessage().contains("Auto-download failed for release 1.10.0") &&
                r.getMessage().contains("CHECKSUM_MISMATCH"));
        assertTrue(diagnosticLogged, "Auto-download failure must be logged with release version and result status");
    }

    @Test
    @DisplayName("Audit callback exception after successful move does not fail committed download (F9)")
    void auditCallbackExceptionAfterSuccessfulMoveDoesNotFailCommittedDownload() throws Exception {
        byte[] payload = "VALID_JAR_BYTES".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(payload);
        String downloadUrl = "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar";

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(payload));

        Consumer<AuditEvent> throwingAuditLogger = event -> {
            throw new RuntimeException("Simulated Discord webhook HTTP 503 outage");
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                throwingAuditLogger,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.Release release = new UpdateService.Release("1.10.0", downloadUrl, sha, "Notes");
        UpdateService.DownloadResult result = service.download(release).join();

        assertEquals(UpdateService.DownloadResult.SUCCESS, result, "Download must succeed despite audit notification failure");
        assertTrue(service.isUpdatePending(), "Update must be marked pending");
        Path target = updateFolder.resolve("DiscordTowny.jar");
        assertTrue(Files.isRegularFile(target), "Committed jar file must exist in update folder");
    }

    @Test
    @DisplayName("Network failure fetching checksums.txt is treated as network error (F9)")
    void checksumFetchNetworkFailureTreatedAsNetworkErrorNotMissingChecksum() {
        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "No inline hash",
                  "assets": [
                    {"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"},
                    {"name": "checksums.txt", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/checksums.txt"}
                  ]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith("checksums.txt")) {
                throw new IOException("Connection refused by checksums asset host");
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> releaseOpt = service.checkForUpdate().join().release();
        assertTrue(releaseOpt.isEmpty());

        boolean networkWarningLogged = logRecords.stream().anyMatch(r ->
                r.getLevel() == Level.WARNING &&
                r.getMessage() != null &&
                r.getMessage().contains("could not reach GitHub"));
        assertTrue(networkWarningLogged, "Network error fetching checksums asset must trigger network failure handler");
    }

    @Test
    @DisplayName("Deeply nested JSON in API response does not crash service (F10)")
    void deeplyNestedJsonInApiResponseDoesNotThrowOutOfService() {
        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            nested.append("{\"key\":");
        }
        nested.append("1");
        for (int i = 0; i < 70; i++) {
            nested.append("}");
        }

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(nested.toString().getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        assertDoesNotThrow(() -> {
            Optional<UpdateService.Release> res = service.checkForUpdate().join().release();
            assertTrue(res.isEmpty());
        });
    }

    @Test
    @DisplayName("Secondary rate limit 403/429 with Retry-After and positive remaining quota is respected (F12)")
    void secondaryRateLimitWithRetryAfterAndPositiveRemainingQuotaIsRespected() {
        AtomicInteger transportCalls = new AtomicInteger(0);

        HttpTransport transport = (uri, headers, timeout) -> {
            transportCalls.incrementAndGet();
            return new HttpTransport.HttpResponse(403, Map.of(
                    "X-RateLimit-Remaining", "50", // Positive remaining quota!
                    "Retry-After", "120"           // But secondary rate limit 120 seconds
            ), new ByteArrayInputStream(new byte[0]));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> first = service.checkForUpdate().join().release();
        assertTrue(first.isEmpty());
        assertEquals(1, transportCalls.get());

        // Second check within the 120-second Retry-After window must NOT call transport
        Optional<UpdateService.Release> second = service.checkForUpdate().join().release();
        assertTrue(second.isEmpty());
        assertEquals(1, transportCalls.get(), "Transport must NOT be contacted while Retry-After delay is active");
    }

    @Test
    @DisplayName("Secondary rate limit with RFC-1123 Retry-After date is respected (F12)")
    void secondaryRateLimitWithRfc1123DateIsRespected() {
        AtomicInteger transportCalls = new AtomicInteger(0);
        Instant future = Instant.now().plusSeconds(300);
        String rfc1123Date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .withZone(java.time.ZoneOffset.UTC)
                .format(future);

        HttpTransport transport = (uri, headers, timeout) -> {
            transportCalls.incrementAndGet();
            return new HttpTransport.HttpResponse(429, Map.of(
                    "Retry-After", rfc1123Date
            ), new ByteArrayInputStream(new byte[0]));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        service.checkForUpdate().join();
        assertEquals(1, transportCalls.get());

        service.checkForUpdate().join();
        assertEquals(1, transportCalls.get(), "Transport must NOT be contacted while RFC-1123 Retry-After window is active");
    }

    private static Messages loadSpanishMessages() {
        Map<String, String> map = new HashMap<>();
        // The catalog is a resource on the test classpath; reading it from
        // src/main/resources instead would tie the test to the working directory.
        InputStream in = EnglishMessages.class.getResourceAsStream("/messages_es.yml");
        if (in != null) {
            try (in; InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(reader);
                for (String key : yaml.getKeys(true)) {
                    if (yaml.isString(key)) {
                        map.put(key, yaml.getString(key));
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return new YamlMessages(map, map, "messages_es.yml", s -> {});
    }

    @Test
    @DisplayName("UpdateSourcePolicy accepts known delivery hosts on redirect and rejects on direct initial call")
    void updateSourcePolicyEnforcesExactDeliveryHostsOnRedirectOnly() throws IOException {
        URI initialAsset = URI.create("https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny.jar");
        URI initialApi = URI.create("https://api.github.com/repos/Dasannn/DiscordxTowny/releases/latest");
        URI liveRedirectTarget = URI.create("https://release-assets.githubusercontent.com/github-production-release-asset/1376593898/cb094ea3-1234?sp=r&sv=2021-08-06&sig=abcdef");
        URI objectsRedirectTarget = URI.create("https://objects.githubusercontent.com/github-production-repository-file/1376593898/file.jar?token=123");

        // Initial requests to official repo are allowed
        assertTrue(UpdateSourcePolicy.isAllowedInitialUri(initialAsset));
        assertTrue(UpdateSourcePolicy.isAllowedInitialUri(initialApi));
        assertDoesNotThrow(() -> UpdateSourcePolicy.validateInitialUri(initialAsset));
        assertDoesNotThrow(() -> UpdateSourcePolicy.validateInitialUri(initialApi));

        // Redirects to exact delivery hosts are allowed (without path constraints)
        assertTrue(UpdateSourcePolicy.isAllowedDeliveryRedirectUri(liveRedirectTarget));
        assertTrue(UpdateSourcePolicy.isAllowedDeliveryRedirectUri(objectsRedirectTarget));
        assertTrue(UpdateSourcePolicy.isAllowedRedirectDestination(liveRedirectTarget));
        assertTrue(UpdateSourcePolicy.isAllowedRedirectDestination(objectsRedirectTarget));
        assertDoesNotThrow(() -> UpdateSourcePolicy.validateRedirectDestination(liveRedirectTarget));
        assertDoesNotThrow(() -> UpdateSourcePolicy.validateRedirectDestination(objectsRedirectTarget));

        // Case insensitivity: uppercase host is normalized and accepted on redirect
        URI uppercaseRedirect = URI.create("https://RELEASE-ASSETS.GITHUBUSERCONTENT.COM/file.jar");
        assertTrue(UpdateSourcePolicy.isAllowedDeliveryRedirectUri(uppercaseRedirect));
        assertTrue(UpdateSourcePolicy.isAllowedRedirectDestination(uppercaseRedirect));
        assertDoesNotThrow(() -> UpdateSourcePolicy.validateRedirectDestination(uppercaseRedirect));

        // Explicit standard HTTPS port 443 is accepted
        URI port443Redirect = URI.create("https://release-assets.githubusercontent.com:443/file.jar");
        assertTrue(UpdateSourcePolicy.isAllowedDeliveryRedirectUri(port443Redirect));
        assertTrue(UpdateSourcePolicy.isAllowedRedirectDestination(port443Redirect));
        assertDoesNotThrow(() -> UpdateSourcePolicy.validateRedirectDestination(port443Redirect));

        // Direct initial requests to delivery hosts without provenance are strictly rejected
        assertFalse(UpdateSourcePolicy.isAllowedInitialUri(liveRedirectTarget));
        assertFalse(UpdateSourcePolicy.isAllowedInitialUri(objectsRedirectTarget));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateInitialUri(liveRedirectTarget));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateInitialUri(objectsRedirectTarget));

        // Untrusted hosts, subdomains, suffix masquerading, non-standard ports, trailing dots are strictly rejected
        URI evilHost = URI.create("https://evil-githubusercontent.com/github-production-release-asset/1376593898/file.jar");
        URI suffixAttacker = URI.create("https://release-assets.githubusercontent.com.attacker.net/file.jar");
        URI rawHost = URI.create("https://githubusercontent.com/Dasannn/DiscordxTowny/file.jar");
        URI rawSubdomain = URI.create("https://raw.githubusercontent.com/file.jar");
        URI attackerSubdomain = URI.create("https://attacker.githubusercontent.com/file.jar");
        URI trailingDot = URI.create("https://release-assets.githubusercontent.com./file.jar");
        URI customPort = URI.create("https://release-assets.githubusercontent.com:8443/file.jar");
        URI httpHop = URI.create("http://release-assets.githubusercontent.com/github-production-release-asset/1376593898/file.jar");
        URI userHop = URI.create("https://user:pass@release-assets.githubusercontent.com/file.jar");

        assertFalse(UpdateSourcePolicy.isAllowedDeliveryRedirectUri(evilHost));
        assertFalse(UpdateSourcePolicy.isAllowedDeliveryRedirectUri(suffixAttacker));
        assertFalse(UpdateSourcePolicy.isAllowedDeliveryRedirectUri(rawHost));
        assertFalse(UpdateSourcePolicy.isAllowedDeliveryRedirectUri(rawSubdomain));
        assertFalse(UpdateSourcePolicy.isAllowedDeliveryRedirectUri(attackerSubdomain));
        assertFalse(UpdateSourcePolicy.isAllowedDeliveryRedirectUri(trailingDot));
        assertFalse(UpdateSourcePolicy.isAllowedDeliveryRedirectUri(customPort));
        assertFalse(UpdateSourcePolicy.isAllowedDeliveryRedirectUri(httpHop));
        assertFalse(UpdateSourcePolicy.isAllowedDeliveryRedirectUri(userHop));

        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(evilHost));
        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(suffixAttacker));
        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(rawHost));
        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(rawSubdomain));
        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(attackerSubdomain));
        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(trailingDot));
        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(customPort));
        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(httpHop));
        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(userHop));

        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(evilHost));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(suffixAttacker));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(rawHost));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(rawSubdomain));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(attackerSubdomain));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(trailingDot));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(customPort));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(httpHop));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(userHop));
    }

    @Test
    @DisplayName("JdkHttpTransport follows redirect to release-assets.githubusercontent.com and objects.githubusercontent.com")
    void jdkHttpTransportFollowsRedirectToDeliveryHosts() throws Exception {
        byte[] payload1 = "DELIVERED_JAR_BYTES_1".getBytes(StandardCharsets.UTF_8);
        byte[] payload2 = "DELIVERED_JAR_BYTES_2".getBytes(StandardCharsets.UTF_8);

        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);

        URI officialUri1 = URI.create("https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar");
        URI releaseAssetsTarget = URI.create("https://release-assets.githubusercontent.com/github-production-release-asset/1376593898/cb094ea3?sp=r&sig=abc");

        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<InputStream> redirectResponse1 = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
        when(redirectResponse1.statusCode()).thenReturn(302);
        when(redirectResponse1.uri()).thenReturn(officialUri1);
        when(redirectResponse1.headers()).thenReturn(HttpHeaders.of(
                Map.of("Location", List.of(releaseAssetsTarget.toString())), (k, v) -> true));
        when(redirectResponse1.body()).thenReturn(new ByteArrayInputStream(new byte[0]));

        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<InputStream> okResponse1 = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
        when(okResponse1.statusCode()).thenReturn(200);
        when(okResponse1.uri()).thenReturn(releaseAssetsTarget);
        when(okResponse1.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
        when(okResponse1.body()).thenReturn(new ByteArrayInputStream(payload1));

        URI officialUri2 = URI.create("https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar.sha256");
        URI objectsTarget = URI.create("https://objects.githubusercontent.com/github-production-repository-file/1376593898/checksum?token=123");

        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<InputStream> redirectResponse2 = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
        when(redirectResponse2.statusCode()).thenReturn(302);
        when(redirectResponse2.uri()).thenReturn(officialUri2);
        when(redirectResponse2.headers()).thenReturn(HttpHeaders.of(
                Map.of("Location", List.of(objectsTarget.toString())), (k, v) -> true));
        when(redirectResponse2.body()).thenReturn(new ByteArrayInputStream(new byte[0]));

        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<InputStream> okResponse2 = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
        when(okResponse2.statusCode()).thenReturn(200);
        when(okResponse2.uri()).thenReturn(objectsTarget);
        when(okResponse2.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
        when(okResponse2.body()).thenReturn(new ByteArrayInputStream(payload2));

        when(mockClient.<InputStream>send(any(), any()))
                .thenReturn(redirectResponse1)
                .thenReturn(okResponse1)
                .thenReturn(redirectResponse2)
                .thenReturn(okResponse2);

        JdkHttpTransport transport = new JdkHttpTransport(mockClient);

        // Case 1: redirect to release-assets.githubusercontent.com
        HttpTransport.HttpResponse res1 = transport.executeGet(officialUri1, Map.of(), Duration.ofSeconds(5));
        assertEquals(200, res1.statusCode());
        assertArrayEquals(payload1, res1.body().readAllBytes());

        // Case 2: redirect to objects.githubusercontent.com
        HttpTransport.HttpResponse res2 = transport.executeGet(officialUri2, Map.of(), Duration.ofSeconds(5));
        assertEquals(200, res2.statusCode());
        assertArrayEquals(payload2, res2.body().readAllBytes());

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient, times(4)).send(captor.capture(), any());
        List<HttpRequest> sent = captor.getAllValues();
        assertEquals(officialUri1, sent.get(0).uri(), "First request must be to official repository origin");
        assertEquals(releaseAssetsTarget, sent.get(1).uri(), "Second request must be to release-assets delivery host");
        assertEquals(officialUri2, sent.get(2).uri(), "Third request must be to official repository origin");
        assertEquals(objectsTarget, sent.get(3).uri(), "Fourth request must be to objects delivery host");
    }

    @Test
    @DisplayName("JdkHttpTransport limits redirect chain length to 5 hops")
    void jdkHttpTransportLimitsRedirectChain() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);

        URI officialUri = URI.create("https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar");
        List<URI> hops = List.of(
                URI.create("https://objects.githubusercontent.com/hop1"),
                URI.create("https://objects.githubusercontent.com/hop2"),
                URI.create("https://objects.githubusercontent.com/hop3"),
                URI.create("https://objects.githubusercontent.com/hop4"),
                URI.create("https://objects.githubusercontent.com/hop5"),
                URI.create("https://objects.githubusercontent.com/hop6")
        );

        when(mockClient.<InputStream>send(any(), any())).thenAnswer(inv -> {
            HttpRequest req = inv.getArgument(0);
            URI uri = req.uri();

            @SuppressWarnings("unchecked")
            java.net.http.HttpResponse<InputStream> resp = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
            when(resp.statusCode()).thenReturn(302);
            when(resp.uri()).thenReturn(uri);
            when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[0]));

            if (officialUri.equals(uri)) {
                when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of(hops.get(0).toString())), (k, v) -> true));
                return resp;
            }
            int idx = hops.indexOf(uri);
            if (idx >= 0 && idx < hops.size() - 1) {
                when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of(hops.get(idx + 1).toString())), (k, v) -> true));
                return resp;
            }
            when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of(hops.get(hops.size() - 1).toString())), (k, v) -> true));
            return resp;
        });

        JdkHttpTransport transport = new JdkHttpTransport(mockClient);
        IOException ex = assertThrows(IOException.class, () ->
                transport.executeGet(officialUri, Map.of(), Duration.ofSeconds(5)));
        assertTrue(ex.getMessage().contains("Too many redirects"), "Must reject redirect loop exceeding max hops: " + ex.getMessage());

        // Verify exact send count: initial request + 5 redirects = 6 sends
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient, times(6)).send(captor.capture(), any());
        List<URI> sent = captor.getAllValues().stream().map(HttpRequest::uri).toList();
        assertEquals(officialUri, sent.get(0));
        assertEquals(hops.get(0), sent.get(1));
        assertEquals(hops.get(1), sent.get(2));
        assertEquals(hops.get(2), sent.get(3));
        assertEquals(hops.get(3), sent.get(4));
        assertEquals(hops.get(4), sent.get(5));

        // Boundary success test: 5 hops that resolve to 200 OK on hop 5 succeeds
        HttpClient boundaryClient = mock(HttpClient.class);
        when(boundaryClient.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        byte[] payload = "SUCCESS_AT_5_HOPS".getBytes(StandardCharsets.UTF_8);

        when(boundaryClient.<InputStream>send(any(), any())).thenAnswer(inv -> {
            HttpRequest req = inv.getArgument(0);
            URI uri = req.uri();

            @SuppressWarnings("unchecked")
            java.net.http.HttpResponse<InputStream> resp = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
            when(resp.uri()).thenReturn(uri);

            if (officialUri.equals(uri)) {
                when(resp.statusCode()).thenReturn(302);
                when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of(hops.get(0).toString())), (k, v) -> true));
                when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
                return resp;
            }
            int idx = hops.indexOf(uri);
            if (idx >= 0 && idx < 4) {
                when(resp.statusCode()).thenReturn(302);
                when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of(hops.get(idx + 1).toString())), (k, v) -> true));
                when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
                return resp;
            }
            // Hop 4 -> hop 5 returns 200 OK
            when(resp.statusCode()).thenReturn(200);
            when(resp.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
            when(resp.body()).thenReturn(new ByteArrayInputStream(payload));
            return resp;
        });

        JdkHttpTransport boundaryTransport = new JdkHttpTransport(boundaryClient);
        HttpTransport.HttpResponse boundaryRes = boundaryTransport.executeGet(officialUri, Map.of(), Duration.ofSeconds(5));
        assertEquals(200, boundaryRes.statusCode());
        assertArrayEquals(payload, boundaryRes.body().readAllBytes());
    }

    @Test
    @DisplayName("Full download succeeds through redirect to release-assets.githubusercontent.com with valid checksum")
    void fullDownloadSucceedsThroughReleaseAssetsRedirectWithValidChecksum() throws IOException, InterruptedException {
        byte[] jarBytes = "DISCORD_TOWNY_1_0_0_RELEASE_JAR".getBytes(StandardCharsets.UTF_8);
        String expectedChecksum = sha256Hex(jarBytes);

        String releaseJson = """
                {
                  "tag_name": "v1.0.0",
                  "body": "Fix delivery host without sha in body",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.0.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar"
                    },
                    {
                      "name": "DiscordTowny-1.0.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar.sha256"
                    }
                  ]
                }
                """;

        String sha256AssetContent = expectedChecksum + "  DiscordTowny-1.0.0.jar\n";

        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);

        URI metadataUri = URI.create("https://api.github.com/repos/Dasannn/DiscordxTowny/releases/latest");
        URI jarOriginUri = URI.create("https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar");
        URI jarCdnUri = URI.create("https://release-assets.githubusercontent.com/github-production-release-asset/1376593898/cb094ea3?sp=r&sig=jar");
        URI checksumOriginUri = URI.create("https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar.sha256");
        URI checksumCdnUri = URI.create("https://release-assets.githubusercontent.com/github-production-release-asset/1376593898/cb094ea3?sp=r&sig=sha");

        when(mockClient.<InputStream>send(any(), any())).thenAnswer(inv -> {
            HttpRequest req = inv.getArgument(0);
            URI uri = req.uri();

            @SuppressWarnings("unchecked")
            java.net.http.HttpResponse<InputStream> resp = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
            when(resp.uri()).thenReturn(uri);

            if (metadataUri.equals(uri)) {
                when(resp.statusCode()).thenReturn(200);
                when(resp.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
                when(resp.body()).thenReturn(new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
                return resp;
            } else if (checksumOriginUri.equals(uri)) {
                when(resp.statusCode()).thenReturn(302);
                when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of(checksumCdnUri.toString())), (k, v) -> true));
                when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
                return resp;
            } else if (checksumCdnUri.equals(uri)) {
                when(resp.statusCode()).thenReturn(200);
                when(resp.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
                when(resp.body()).thenReturn(new ByteArrayInputStream(sha256AssetContent.getBytes(StandardCharsets.UTF_8)));
                return resp;
            } else if (jarOriginUri.equals(uri)) {
                when(resp.statusCode()).thenReturn(302);
                when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of(jarCdnUri.toString())), (k, v) -> true));
                when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
                return resp;
            } else if (jarCdnUri.equals(uri)) {
                when(resp.statusCode()).thenReturn(200);
                when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Length", List.of(String.valueOf(jarBytes.length))), (k, v) -> true));
                when(resp.body()).thenReturn(new ByteArrayInputStream(jarBytes));
                return resp;
            }
            throw new IOException("Unexpected request to " + uri);
        });

        JdkHttpTransport transport = new JdkHttpTransport(mockClient);

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0-SNAPSHOT",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> releaseOpt = service.checkForUpdate().join().release();
        assertTrue(releaseOpt.isPresent(), "Release 1.0.0 must be found");
        UpdateService.Release release = releaseOpt.get();
        assertEquals("1.0.0", release.version());
        assertEquals(expectedChecksum, release.sha256());
        assertFalse(service.isLastCheckFailed());
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, service.checkStatus());

        UpdateService.DownloadResult result = service.download(release).join();
        assertEquals(UpdateService.DownloadResult.SUCCESS, result);

        Path stagedJar = updateFolder.resolve("DiscordTowny.jar");
        assertTrue(Files.exists(stagedJar), "Staged jar must exist in update folder");
        assertArrayEquals(jarBytes, Files.readAllBytes(stagedJar));
        assertTrue(service.isUpdatePending());

        assertEquals("LIVE_JAR_CURRENT_VERSION_BYTES", Files.readString(activeJar));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient, atLeast(5)).send(captor.capture(), any());
        List<URI> sentUris = captor.getAllValues().stream().map(HttpRequest::uri).toList();
        assertTrue(sentUris.contains(metadataUri), "Must request release metadata from official API");
        assertTrue(sentUris.contains(checksumOriginUri), "Must request checksum from official repo origin");
        assertTrue(sentUris.contains(checksumCdnUri), "Must follow redirect to delivery host CDN for checksum");
        assertTrue(sentUris.contains(jarOriginUri), "Must request jar from official repo origin");
        assertTrue(sentUris.contains(jarCdnUri), "Must follow redirect to delivery host CDN for jar bytes");
    }

    @Test
    @DisplayName("Checksum mismatch after release-assets.githubusercontent.com delivery discards download and leaves no remnants")
    void checksumMismatchAfterDeliveryDiscardsDownloadWithoutRemnants() throws Exception {
        byte[] tamperedJar = "TAMPERED_BYTES_FROM_CDN".getBytes(StandardCharsets.UTF_8);
        String legitimateSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);

        URI jarOriginUri = URI.create("https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar");
        URI jarCdnUri = URI.create("https://release-assets.githubusercontent.com/github-production-release-asset/1376593898/cb094ea3?sp=r&sig=tampered");

        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<InputStream> redirResp = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
        when(redirResp.statusCode()).thenReturn(302);
        when(redirResp.uri()).thenReturn(jarOriginUri);
        when(redirResp.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of(jarCdnUri.toString())), (k, v) -> true));
        when(redirResp.body()).thenReturn(new ByteArrayInputStream(new byte[0]));

        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<InputStream> okResp = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
        when(okResp.statusCode()).thenReturn(200);
        when(okResp.uri()).thenReturn(jarCdnUri);
        when(okResp.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Length", List.of(String.valueOf(tamperedJar.length))), (k, v) -> true));
        when(okResp.body()).thenReturn(new ByteArrayInputStream(tamperedJar));

        when(mockClient.<InputStream>send(any(), any()))
                .thenReturn(redirResp)
                .thenReturn(okResp);

        JdkHttpTransport transport = new JdkHttpTransport(mockClient);

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0-SNAPSHOT",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.Release release = new UpdateService.Release(
                "1.0.0",
                jarOriginUri.toString(),
                legitimateSha,
                "Release notes"
        );

        UpdateService.DownloadResult result = service.download(release).join();
        assertEquals(UpdateService.DownloadResult.CHECKSUM_MISMATCH, result);

        // Verify transport calls occurred and followed redirect to delivery host CDN
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient, times(2)).send(captor.capture(), any());
        List<HttpRequest> sent = captor.getAllValues();
        assertEquals(jarOriginUri, sent.get(0).uri());
        assertEquals(jarCdnUri, sent.get(1).uri());

        Path stagedJar = updateFolder.resolve("DiscordTowny.jar");
        assertFalse(Files.exists(stagedJar));
        if (Files.exists(updateFolder)) {
            try (Stream<Path> stream = Files.list(updateFolder)) {
                assertTrue(stream.toList().isEmpty(), "No remnants may remain in update folder");
            }
        }
        assertEquals("LIVE_JAR_CURRENT_VERSION_BYTES", Files.readString(activeJar));
        assertFalse(service.isUpdatePending());
    }

    @Test
    @DisplayName("Failed check marks isLastCheckFailed, silences duplicate warnings, and renders check-failed rather than up-to-date")
    void failedCheckMarksStateAndRendersCheckFailedNeverUpToDate() {
        AtomicBoolean failing = new AtomicBoolean(true);
        String releaseJson = """
                {
                  "tag_name": "v1.0.0",
                  "body": "SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "assets": [{"name": "DiscordTowny-1.0.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar"}]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) -> {
            if (failing.get()) {
                throw new IOException("Connection refused");
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0-SNAPSHOT",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // 1. Initial state: NOT_CHECKED
        assertEquals(UpdateService.CheckStatus.NOT_CHECKED, service.checkStatus());
        assertFalse(service.isLastCheckFailed());
        assertFalse(service.hasCheckedAtLeastOnce());

        Messages enMessages = EnglishMessages.bundled();
        List<String> initialEnLines = service.renderStatusMessages(enMessages);
        assertTrue(initialEnLines.stream().anyMatch(l -> l.contains("No update check has been performed yet.")),
                "Initial status must render 'No update check has been performed yet.', got: " + initialEnLines);
        assertFalse(initialEnLines.stream().anyMatch(l -> l.contains("You are on the latest version")),
                "Initial status must NEVER render 'You are on the latest version'!");

        Messages esMessages = loadSpanishMessages();
        List<String> initialEsLines = service.renderStatusMessages(esMessages);
        assertTrue(initialEsLines.stream().anyMatch(l -> l.contains("Aún no se ha realizado ninguna comprobación de actualizaciones.")),
                "Initial Spanish status must render not-checked, got: " + initialEsLines);
        assertFalse(initialEsLines.stream().anyMatch(l -> l.contains("Estás en la última versión")),
                "Initial Spanish status must NEVER render 'Estás en la última versión'!");

        // 2. First check: fails
        Optional<UpdateService.Release> res1 = service.checkForUpdate().join().release();
        assertTrue(res1.isEmpty());
        assertTrue(service.isLastCheckFailed(), "isLastCheckFailed must be true after network failure");
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, service.checkStatus());
        assertTrue(service.getLastCheckError().isPresent());
        assertTrue(service.getLastCheckError().get().contains("could not reach GitHub"));

        long warningsCount1 = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING).count();
        assertEquals(1, warningsCount1, "First network failure must log a warning");

        // Second check: fails again -> warning must be silenced
        Optional<UpdateService.Release> res2 = service.checkForUpdate().join().release();
        assertTrue(res2.isEmpty());
        assertTrue(service.isLastCheckFailed());
        long warningsCount2 = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING).count();
        assertEquals(1, warningsCount2, "Consecutive network failure warning must be silenced");

        // Check rendered status messages in English
        List<String> enLines = service.renderStatusMessages(enMessages);
        assertFalse(enLines.isEmpty());
        boolean enHasFailedIntro = enLines.stream().anyMatch(l -> l.contains("Could not check for updates"));
        boolean enHasFailedReason = enLines.stream().anyMatch(l -> l.contains("could not reach GitHub"));
        boolean enHasUpToDate = enLines.stream().anyMatch(l -> l.contains("You are on the latest version"));
        assertTrue(enHasFailedIntro, "Status must render 'Could not check for updates', got: " + enLines);
        assertTrue(enHasFailedReason, "Status must render translated reason 'could not reach GitHub', got: " + enLines);
        assertFalse(enHasUpToDate, "Status must NEVER render 'You are on the latest version' when check failed!");

        // Check rendered status messages in Spanish (fully localized, zero English leakage)
        List<String> esLines = service.renderStatusMessages(esMessages);
        assertFalse(esLines.isEmpty());
        boolean esHasFailedIntro = esLines.stream().anyMatch(l -> l.contains("No se pudo comprobar si hay actualizaciones"));
        boolean esHasFailedReason = esLines.stream().anyMatch(l -> l.contains("no se pudo conectar con GitHub"));
        boolean esHasUpToDate = esLines.stream().anyMatch(l -> l.contains("Estás en la última versión"));
        boolean esHasEnglishLeak = esLines.stream().anyMatch(l -> l.contains("could not reach GitHub"));
        assertTrue(esHasFailedIntro, "Status must render 'No se pudo comprobar si hay actualizaciones', got: " + esLines);
        assertTrue(esHasFailedReason, "Status must render Spanish localized reason 'no se pudo conectar con GitHub', got: " + esLines);
        assertFalse(esHasUpToDate, "Status must NEVER render 'Estás en la última versión' when check failed!");
        assertFalse(esHasEnglishLeak, "Spanish status must not contain raw English error text!");

        // 3. Failure after cached discovery: reports BOTH cached update and failure
        // First, allow a check to succeed and cache release
        failing.set(false);
        service.checkForUpdate().join();
        assertFalse(service.isLastCheckFailed());
        assertTrue(service.getAvailableUpdate().isPresent());

        // Now subsequent check fails
        failing.set(true);
        service.checkForUpdate().join();
        assertTrue(service.isLastCheckFailed());
        assertTrue(service.getAvailableUpdate().isPresent());
        List<String> cachedThenFailedLines = service.renderStatusMessages(enMessages);
        assertTrue(cachedThenFailedLines.stream().anyMatch(l -> l.contains("There is a new version")),
                "Must preserve available update info in status, got: " + cachedThenFailedLines);
        assertTrue(cachedThenFailedLines.stream().anyMatch(l -> l.contains("Could not check for updates")),
                "Must report check failure even when cached update is present, got: " + cachedThenFailedLines);
        assertFalse(cachedThenFailedLines.stream().anyMatch(l -> l.contains("You are on the latest version")));
    }

    @Test
    @DisplayName("Recovery after failed check clears error state and renders up-to-date")
    void recoveryAfterFailedCheckClearsErrorState() {
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger status304 = new AtomicInteger(0);

        String releaseJson = """
                {
                  "tag_name": "v0.1.0-SNAPSHOT",
                  "body": "SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "assets": [{"name": "DiscordTowny-0.1.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v0.1.0/DiscordTowny-0.1.0.jar"}]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) -> {
            if (fail.get()) {
                throw new IOException("Connection refused");
            }
            if (status304.get() > 0) {
                return new HttpTransport.HttpResponse(304, Map.of(), new ByteArrayInputStream(new byte[0]));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0-SNAPSHOT",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // 1. Initial failure
        service.checkForUpdate().join();
        assertTrue(service.isLastCheckFailed());
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, service.checkStatus());
        long warningsCount1 = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING).count();
        assertEquals(1, warningsCount1);

        // 2. Recover via 200 OK
        fail.set(false);
        service.checkForUpdate().join();
        assertFalse(service.isLastCheckFailed(), "isLastCheckFailed must reset to false after successful check");
        assertTrue(service.getLastCheckError().isEmpty());
        assertEquals(UpdateService.CheckStatus.UP_TO_DATE, service.checkStatus());

        List<String> enLines = service.renderStatusMessages(EnglishMessages.bundled());
        assertTrue(enLines.stream().anyMatch(l -> l.contains("You are on the latest version")));
        assertFalse(enLines.stream().anyMatch(l -> l.contains("Could not check for updates")),
                "Recovered status must not contain failure line: " + enLines);

        // 3. New outage after recovery: warning suppression must be reset so new failure is logged
        fail.set(true);
        service.checkForUpdate().join();
        assertTrue(service.isLastCheckFailed());
        long warningsCount2 = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING).count();
        assertEquals(2, warningsCount2, "Warning suppression must reset after recovery so next failure is logged");

        // 4. Recover via 304 Not Modified
        fail.set(false);
        status304.set(1);
        service.checkForUpdate().join();
        assertFalse(service.isLastCheckFailed(), "isLastCheckFailed must reset to false after 304 Not Modified");
        assertTrue(service.getLastCheckError().isEmpty());
        assertEquals(UpdateService.CheckStatus.UP_TO_DATE, service.checkStatus());
    }

    @Test
    @DisplayName("Same line declaration with invalid checksum token refuses release even with valid checksum asset (F2)")
    void sameLineMultipleChecksumsWithInvalidRefusesReleaseEvenWithValidChecksumAsset() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String sameLineBody = "SHA-256: " + validHex + " SHA-256: invalid";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """.formatted(sameLineBody);

        String validShaAsset = validHex + "  DiscordTowny-1.10.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            String url = uri.toString();
            if (url.contains(".sha256")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(validShaAsset.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> res = service.checkForUpdate().join().release();
        assertTrue(res.isEmpty(), "Release declaring invalid checksum token on same line must be refused");
        assertTrue(service.isLastCheckFailed());
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, service.checkStatus());
        assertTrue(service.getLastCheckError().isPresent());
        assertTrue(service.getLastCheckError().get().toLowerCase(Locale.ROOT).contains("checksum"));
    }

    @Test
    @DisplayName("Same line conflicting checksum declarations refuse release even with valid checksum asset (F2)")
    void sameLineConflictingChecksumsRefusesReleaseEvenWithValidChecksumAsset() {
        String validHex1 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String validHex2 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
        String sameLineBody = "SHA-256: " + validHex1 + " SHA-256: " + validHex2;

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """.formatted(sameLineBody);

        String validShaAsset = validHex1 + "  DiscordTowny-1.10.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            String url = uri.toString();
            if (url.contains(".sha256")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(validShaAsset.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> res = service.checkForUpdate().join().release();
        assertTrue(res.isEmpty(), "Release declaring conflicting checksum tokens on same line must be refused");
        assertTrue(service.isLastCheckFailed());
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, service.checkStatus());
    }

    @Test
    @DisplayName("Rate limit response marks check failed even when cached release is returned (F3)")
    void rateLimitFailureMarksCheckFailedEvenWhenCachedReleaseIsUsed() {
        AtomicInteger callCount = new AtomicInteger(0);
        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"}]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) -> {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            }
            // Second call: 429 rate limited
            return new HttpTransport.HttpResponse(429, Map.of(
                    "x-ratelimit-remaining", "0",
                    "x-ratelimit-reset", String.valueOf(Instant.now().getEpochSecond() + 3600)
            ), new ByteArrayInputStream(new byte[0]));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // First check succeeds
        Optional<UpdateService.Release> res1 = service.checkForUpdate().join().release();
        assertTrue(res1.isPresent());
        assertFalse(service.isLastCheckFailed());
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, service.checkStatus());

        // Second check hits 429: cached release is returned, but last check MUST be marked failed
        Optional<UpdateService.Release> res2 = service.checkForUpdate().join().release();
        assertTrue(res2.isPresent(), "Cached release must still be returned for convenience");
        assertTrue(service.isLastCheckFailed(), "isLastCheckFailed must be true after rate limit");
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, service.checkStatus());
        assertTrue(service.getLastCheckError().isPresent());
        assertTrue(service.getLastCheckError().get().toLowerCase(Locale.ROOT).contains("rate limit"));

        // Third check hits rate limit window shortcut before expiration: cached release returned, check failed
        Optional<UpdateService.Release> res3 = service.checkForUpdate().join().release();
        assertTrue(res3.isPresent());
        assertTrue(service.isLastCheckFailed());
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, service.checkStatus());
    }

    @Test
    @DisplayName("Consecutive different check failures update lastCheckError to the latest reason (F6)")
    void consecutiveDifferentFailuresUpdatesLastCheckErrorToLatestReason() {
        AtomicInteger callCount = new AtomicInteger(0);

        HttpTransport transport = (uri, headers, timeout) -> {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                throw new IOException("Connection refused");
            }
            // Call 2: 200 OK with malformed JSON
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream("INVALID_JSON".getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // 1st failure: network error
        service.checkForUpdate().join();
        assertTrue(service.isLastCheckFailed());
        assertTrue(service.getLastCheckError().isPresent());
        assertTrue(service.getLastCheckError().get().contains("could not reach GitHub"));

        // 2nd failure: malformed JSON parse error
        service.checkForUpdate().join();
        assertTrue(service.isLastCheckFailed());
        assertTrue(service.getLastCheckError().isPresent());
        assertFalse(service.getLastCheckError().get().contains("could not reach GitHub"),
                "Stale network error must not persist; got: " + service.getLastCheckError().get());
        assertTrue(service.getLastCheckError().get().toLowerCase(Locale.ROOT).contains("parse"));
    }

    @Test
    @DisplayName("Release with multiple runnable jars without disambiguation is refused as ambiguous")
    void releaseWithMultipleRunnableJarsAmbiguityIsRefused() {
        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "DiscordTowny-Other-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-Other-1.10.0.jar"
                    }
                  ]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> res = service.checkForUpdate().join().release();
        assertTrue(res.isEmpty(), "Multiple runnable jars without unique canonical match must be refused");
        assertTrue(service.isLastCheckFailed());
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, service.checkStatus());
        assertTrue(service.getLastCheckError().isPresent());
        assertTrue(service.getLastCheckError().get().toLowerCase(Locale.ROOT).contains("ambiguous"));
    }

    @Test
    @DisplayName("Release discovery without published checksum in body or asset is refused")
    void releaseDiscoveryWithoutPublishedChecksumIsRefused() {
        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "Release notes with no checksum at all",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    }
                  ]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        Optional<UpdateService.Release> res = service.checkForUpdate().join().release();
        assertTrue(res.isEmpty(), "Release without published checksum must be refused at discovery");
        assertTrue(service.isLastCheckFailed());
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, service.checkStatus());
        assertTrue(service.getLastCheckError().isPresent());
        assertTrue(service.getLastCheckError().get().toLowerCase(Locale.ROOT).contains("no published checksum"));
    }

    @Test
    @DisplayName("A stopped service check returns NOT_CHECKED and never reports up-to-date (F1)")
    void stoppedServiceCheckReturnsNotCheckedAndNeverUpToDate() {
        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                (uri, headers, timeout) -> fail("Transport must not be called when stopped"),
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        service.stop();

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.NOT_CHECKED, result.status());
        assertTrue(result.release().isEmpty());
        assertTrue(result.error().isPresent());
        assertTrue(result.error().get().contains("stopped"));

        List<String> enLines = service.renderStatusMessages(EnglishMessages.bundled());
        assertTrue(enLines.stream().anyMatch(l -> l.contains("No update check has been performed yet")),
                "Must report not-checked, got: " + enLines);
        assertFalse(enLines.stream().anyMatch(l -> l.contains("latest version")),
                "Must NEVER report up to date when stopped!");
    }

    @Test
    @DisplayName("BSD format with two declarations on same line and invalid first refuses release even with valid checksum asset (F2)")
    void bsdTwoDeclarationsOnSameLineWithInvalidFirstRefusesReleaseEvenWithValidChecksumAsset() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        // Malformed declaration before valid declaration on the very same line
        String bodyText = "SHA256 (DiscordTowny-1.10.0.jar) = invalid SHA256 (./DiscordTowny-1.10.0.jar) = " + validHex;

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "checksums.txt",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/checksums.txt"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\""));

        String validChecksumAsset = validHex + "  DiscordTowny-1.10.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith("checksums.txt")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(validChecksumAsset.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertTrue(result.release().isEmpty(), "Release must be refused due to invalid declaration on body line");
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status());
        assertTrue(service.isLastCheckFailed());
        assertTrue(service.getLastCheckError().isPresent());
        assertTrue(service.getLastCheckError().get().toLowerCase(Locale.ROOT).contains("checksum"));
    }

    @Test
    @DisplayName("sha256sum format whose filename absorbs a malformed declaration refuses release without fallback (F2)")
    void sha256sumFilenameAbsorbingMalformedDeclarationRefusesReleaseEvenWithValidChecksumAsset() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        // Candidate where filename contains an absorbed invalid declaration
        String bodyText = validHex + "  SHA-256=invalid/DiscordTowny-1.10.0.jar";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "checksums.txt",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/checksums.txt"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\""));

        String validChecksumAsset = validHex + "  DiscordTowny-1.10.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith("checksums.txt")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(validChecksumAsset.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertTrue(result.release().isEmpty(), "Absorbed checksum declaration in filename must cause release to be refused");
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status());
        assertTrue(service.isLastCheckFailed());
    }

    @Test
    @DisplayName("Valid labeled checksum declaration with jar name is discovered successfully (F7)")
    void validLabeledChecksumWithJarNameIsDiscoveredSuccessfully() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        // Valid labeled format with jar name that was broken in round 3 by broad sumMatcher
        String bodyText = "SHA-256: " + validHex + " DiscordTowny-1.10.0.jar";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "checksums.txt",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/checksums.txt"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\""));

        String validChecksumAsset = validHex + "  DiscordTowny-1.10.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith("checksums.txt")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(validChecksumAsset.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertTrue(result.release().isPresent(), "Release with valid labeled checksum must be accepted");
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status());
        assertEquals("1.10.0", result.release().get().version());
        assertEquals(validHex, result.release().get().sha256());
        assertFalse(service.isLastCheckFailed());
    }

    @Test
    @DisplayName("HTTP 500 when fetching checksum asset reports network failure, not invalid checksum (F8)")
    void checksumAssetHttp500RendersNetworkFailureReasonNotInvalidChecksum() {
        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "Release without hash in body",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".sha256")) {
                return new HttpTransport.HttpResponse(500, Map.of(), new ByteArrayInputStream("Internal Server Error".getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertTrue(result.release().isEmpty());
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status());
        assertTrue(service.isLastCheckFailed());

        // Error message must classify as network error
        assertTrue(service.getLastCheckError().isPresent());
        String error = service.getLastCheckError().get();
        assertTrue(error.contains("could not reach GitHub") || error.contains("status 500"));

        // Render status messages in English
        List<String> enLines = service.renderStatusMessages(EnglishMessages.bundled());
        assertTrue(enLines.stream().anyMatch(l -> l.contains("could not reach GitHub")),
                "Status must mention network failure reason 'could not reach GitHub', got: " + enLines);
        assertFalse(enLines.stream().anyMatch(l -> l.contains("checksum is invalid or ambiguous")),
                "Status must NEVER claim checksum is invalid when asset could not be retrieved!");

        // Render status messages in Spanish
        Messages esMessages = loadSpanishMessages();
        List<String> esLines = service.renderStatusMessages(esMessages);
        assertTrue(esLines.stream().anyMatch(l -> l.contains("no se pudo conectar con GitHub")),
                "Spanish status must mention 'no se pudo conectar con GitHub', got: " + esLines);
        assertFalse(esLines.stream().anyMatch(l -> l.contains("suma de comprobación no es válida")),
                "Spanish status must NEVER claim checksum is invalid when asset HTTP 500 occurred!");
    }

    @Test
    @DisplayName("HTTP 403 rate limit when fetching checksum asset reports rate limit, not invalid checksum (F8)")
    void checksumAssetHttp403RateLimitRendersRateLimitReasonNotInvalidChecksum() {
        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "Release without hash in body",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".sha256")) {
                return new HttpTransport.HttpResponse(403, Map.of(
                        "x-ratelimit-remaining", "0",
                        "x-ratelimit-reset", String.valueOf(Instant.now().getEpochSecond() + 3600)
                ), new ByteArrayInputStream(new byte[0]));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertTrue(result.release().isEmpty());
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status());
        assertTrue(service.isLastCheckFailed());

        // Error message must classify as rate limit
        assertTrue(service.getLastCheckError().isPresent());
        String error = service.getLastCheckError().get().toLowerCase(Locale.ROOT);
        assertTrue(error.contains("rate limit"), "Error must contain 'rate limit', got: " + error);

        // Render status messages in English
        List<String> enLines = service.renderStatusMessages(EnglishMessages.bundled());
        assertTrue(enLines.stream().anyMatch(l -> l.contains("rate limit exceeded")),
                "Status must mention rate limit exceeded, got: " + enLines);
        assertFalse(enLines.stream().anyMatch(l -> l.contains("checksum is invalid")),
                "Status must NEVER claim checksum is invalid when asset was rate limited!");

        // Render status messages in Spanish
        Messages esMessages = loadSpanishMessages();
        List<String> esLines = service.renderStatusMessages(esMessages);
        assertTrue(esLines.stream().anyMatch(l -> l.contains("límite de peticiones")),
                "Spanish status must name the rate limit, got: " + esLines);
        assertFalse(esLines.stream().anyMatch(l -> l.contains("suma de comprobación no es válida")),
                "Spanish status must NEVER claim checksum is invalid when rate limited!");
    }

    @Test
    @DisplayName("Unlabeled sha256sum line with 65-hex characters refuses release even with valid checksum asset (F2)")
    void unlabeledSha256sumWith65HexDigitsRefusesReleaseEvenWithValidChecksumAsset() {
        String validAssetSha = "1111111111222222222233333333334444444444555555555566666666667777";
        String malformedBodyLine = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef9  DiscordTowny-1.10.0.jar";
        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """.formatted(malformedBodyLine);

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".sha256")) {
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(validAssetSha.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(),
                    new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status());
        assertTrue(result.release().isEmpty());
        assertTrue(service.isLastCheckFailed());
        assertTrue(service.getLastCheckError().isPresent());
        assertTrue(service.getLastCheckError().get().contains("Malformed SHA-256 token in body"),
                "Error must identify malformed body token, got: " + service.getLastCheckError().get());
    }

    @Test
    @DisplayName("Unlabeled sum line with non-hex tokens refuse release even with valid checksum asset (F2)")
    void unlabeledSumLineWithNonHexTokensRefuseReleaseEvenWithValidChecksumAsset() {
        String validAssetSha = "1111111111222222222233333333334444444444555555555566666666667777";
        List<String> malformedLines = List.of(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef9  DiscordTowny-1.10.0.jar",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdefHg  DiscordTowny-1.10.0.jar"
        );

        for (String malformedLine : malformedLines) {
            String releaseJson = """
                    {
                      "tag_name": "v1.10.0",
                      "body": "%s",
                      "assets": [
                        {
                          "name": "DiscordTowny-1.10.0.jar",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                        },
                        {
                          "name": "DiscordTowny-1.10.0.jar.sha256",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                        }
                      ]
                    }
                    """.formatted(malformedLine);

            HttpTransport transport = (uri, headers, timeout) -> {
                if (uri.toString().endsWith(".sha256")) {
                    return new HttpTransport.HttpResponse(200, Map.of(),
                            new ByteArrayInputStream(validAssetSha.getBytes(StandardCharsets.UTF_8)));
                }
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            };

            DefaultUpdateService service = new DefaultUpdateService(
                    "1.0.0",
                    new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                    updateFolder,
                    activeJar,
                    "DiscordTowny.jar",
                    transport,
                    testLogger,
                    auditEvents::add,
                    ForkJoinPool.commonPool(),
                    null,
                    false,
                    50 * 1024 * 1024L,
                    Duration.ofSeconds(5)
            );

            UpdateService.CheckResult result = service.checkForUpdate().join();
            assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status(),
                    "Check must fail for line: " + malformedLine);
            assertTrue(result.release().isEmpty());
            assertTrue(service.isLastCheckFailed());
            assertTrue(service.getLastCheckError().isPresent());
        }
    }

    @Test
    @DisplayName("Release body containing 'Release  notes  <64 hex>' prose beside valid labeled declaration is accepted")
    void releaseBodyWithReleaseNotesProseAndHexBesideValidLabeledDeclarationIsAccepted() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String proseHex = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        // Prose line: "Release  notes  <64 hex>" (two spaces)
        // Valid labeled declaration: "SHA-256: <validHex> DiscordTowny-1.10.0.jar"
        String bodyText = "Release  notes  " + proseHex + "\nSHA-256: " + validHex + " DiscordTowny-1.10.0.jar";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\"").replace("\n", "\\n"));

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status(),
                "Prose line 'Release  notes  <64 hex>' must not turn release into a refusal when valid labeled declaration is present");
        assertFalse(service.isLastCheckFailed());
        assertTrue(service.getAvailableUpdate().isPresent());
        assertEquals("1.10.0", service.getAvailableUpdate().get().version());
        assertEquals(validHex.toLowerCase(Locale.ROOT), service.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("Sum line for non-jar artifact with word where digest belongs is treated as prose and does not refuse release (F14 inverted back)")
    void sumLineForNonJarArtifactWithMalformedDigestIsTreatedAsProseAndDoesNotRefuseRelease() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String validAssetSha = "1111111111222222222233333333334444444444555555555566666666667777";

        // Case 1A (F14 inverted back): "invalid  DiscordTowny-1.10.0.zip" where zip is published.
        // A word where a digest belongs is not evidence of a declaration, and the release is still only accepted
        // because a valid checksum for the jar exists.
        String proseWordLine = "invalid  DiscordTowny-1.10.0.zip";

        // Beside valid dedicated checksum asset
        String releaseJson1A = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.zip",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.zip"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """.formatted(proseWordLine);

        HttpTransport transport1A = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".sha256")) {
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream((validAssetSha + "  DiscordTowny-1.10.0.jar\n").getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(),
                    new ByteArrayInputStream(releaseJson1A.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service1A = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport1A,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result1A = service1A.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result1A.status(),
                "A word where a digest belongs is not evidence of a declaration; release is accepted via dedicated checksum asset");
        assertFalse(service1A.isLastCheckFailed());
        assertTrue(service1A.getAvailableUpdate().isPresent());
        assertEquals(validAssetSha.toLowerCase(Locale.ROOT), service1A.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));

        // Beside valid labeled declaration in body
        String body1B = proseWordLine + "\nSHA-256: " + validHex + " DiscordTowny-1.10.0.jar";
        String releaseJson1B = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.zip",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.zip"
                    }
                  ]
                }
                """.formatted(body1B.replace("\n", "\\n"));

        HttpTransport transport1B = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(releaseJson1B.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService service1B = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport1B,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result1B = service1B.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result1B.status(),
                "A word where a digest belongs is not evidence of a declaration; release is accepted via valid body declaration");
        assertFalse(service1B.isLastCheckFailed());
        assertTrue(service1B.getAvailableUpdate().isPresent());
        assertEquals(validHex.toLowerCase(Locale.ROOT), service1B.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));

        // Case 1C: Rule 2 hash-shaped digest (66 chars) naming published non-jar artifact MUST refuse release
        String hashShapedNonJarLine = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdefHg  DiscordTowny-1.10.0.zip";

        // Hash-shaped beside dedicated checksum asset
        String releaseJson1C = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.zip",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.zip"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """.formatted(hashShapedNonJarLine);

        HttpTransport transport1C = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".sha256")) {
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream((validAssetSha + "  DiscordTowny-1.10.0.jar\n").getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(),
                    new ByteArrayInputStream(releaseJson1C.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service1C = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport1C,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result1C = service1C.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result1C.status(),
                "Hash-shaped malformed digest for published non-jar artifact beside valid asset must refuse release");
        assertTrue(service1C.isLastCheckFailed());
        assertTrue(result1C.error().isPresent());
        assertFalse(service1C.getAvailableUpdate().isPresent());

        // Hash-shaped beside valid labeled declaration in body
        String body1D = hashShapedNonJarLine + "\nSHA-256: " + validHex + " DiscordTowny-1.10.0.jar";
        String releaseJson1D = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.zip",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.zip"
                    }
                  ]
                }
                """.formatted(body1D.replace("\n", "\\n"));

        HttpTransport transport1D = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(releaseJson1D.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService service1D = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport1D,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result1D = service1D.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result1D.status(),
                "Hash-shaped malformed digest for published non-jar artifact beside valid body declaration must refuse release");
        assertTrue(service1D.isLastCheckFailed());
        assertTrue(result1D.error().isPresent());
        assertFalse(service1D.getAvailableUpdate().isPresent());

        // Case 2: where the named file is NOT an asset of the release and is not a jar — that one stays prose
        List<String> unreferencedNonJarLines = List.of(
                "invalid  DiscordTowny-1.10.0.zip",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdefHg  DiscordTowny-1.10.0.zip"
        );

        for (String nonAssetLine : unreferencedNonJarLines) {
            // Case 2A: beside valid dedicated checksum asset (release publishes NO zip)
            String releaseJsonA = """
                    {
                      "tag_name": "v1.10.0",
                      "body": "%s",
                      "assets": [
                        {
                          "name": "DiscordTowny-1.10.0.jar",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                        },
                        {
                          "name": "DiscordTowny-1.10.0.jar.sha256",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                        }
                      ]
                    }
                    """.formatted(nonAssetLine);

            HttpTransport transportA = (uri, headers, timeout) -> {
                if (uri.toString().endsWith(".sha256")) {
                    return new HttpTransport.HttpResponse(200, Map.of(),
                            new ByteArrayInputStream((validAssetSha + "  DiscordTowny-1.10.0.jar\n").getBytes(StandardCharsets.UTF_8)));
                }
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(releaseJsonA.getBytes(StandardCharsets.UTF_8)));
            };

            DefaultUpdateService serviceA = new DefaultUpdateService(
                    "1.0.0",
                    new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                    updateFolder,
                    activeJar,
                    "DiscordTowny.jar",
                    transportA,
                    testLogger,
                    auditEvents::add,
                    ForkJoinPool.commonPool(),
                    null,
                    false,
                    50 * 1024 * 1024L,
                    Duration.ofSeconds(5)
            );

            UpdateService.CheckResult resultA = serviceA.checkForUpdate().join();
            assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, resultA.status(),
                    "Malformed digest for non-asset non-jar line beside valid asset must stay prose and not refuse release: " + nonAssetLine);
            assertFalse(serviceA.isLastCheckFailed());
            assertTrue(serviceA.getAvailableUpdate().isPresent());
            assertEquals(validAssetSha.toLowerCase(Locale.ROOT), serviceA.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));

            // Case 2B: beside valid labeled declaration in body (release publishes NO zip)
            String bodyB = nonAssetLine + "\nSHA-256: " + validHex + " DiscordTowny-1.10.0.jar";
            String releaseJsonB = """
                    {
                      "tag_name": "v1.10.0",
                      "body": "%s",
                      "assets": [
                        {
                          "name": "DiscordTowny-1.10.0.jar",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                        }
                      ]
                    }
                    """.formatted(bodyB.replace("\n", "\\n"));

            HttpTransport transportB = (uri, headers, timeout) ->
                    new HttpTransport.HttpResponse(200, Map.of(),
                            new ByteArrayInputStream(releaseJsonB.getBytes(StandardCharsets.UTF_8)));

            DefaultUpdateService serviceB = new DefaultUpdateService(
                    "1.0.0",
                    new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                    updateFolder,
                    activeJar,
                    "DiscordTowny.jar",
                    transportB,
                    testLogger,
                    auditEvents::add,
                    ForkJoinPool.commonPool(),
                    null,
                    false,
                    50 * 1024 * 1024L,
                    Duration.ofSeconds(5)
            );

            UpdateService.CheckResult resultB = serviceB.checkForUpdate().join();
            assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, resultB.status(),
                    "Malformed digest for non-asset non-jar line beside valid body declaration must succeed: " + nonAssetLine);
            assertFalse(serviceB.isLastCheckFailed());
            assertTrue(serviceB.getAvailableUpdate().isPresent());
            assertEquals(validHex.toLowerCase(Locale.ROOT), serviceB.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    @DisplayName("Atomic check status transitions directly to failed and never exposes UP_TO_DATE (F1)")
    void atomicCheckStatusTransitionsDirectlyToFailedAndNeverExposesUpToDate() {
        HttpTransport transport = (uri, headers, timeout) -> {
            throw new IOException("Simulated network outage");
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // Before check: NOT_CHECKED
        assertEquals(UpdateService.CheckStatus.NOT_CHECKED, service.checkStatus());
        assertEquals(UpdateService.CheckStatus.NOT_CHECKED, service.getLastCheckResult().status());
        assertFalse(service.hasCheckedAtLeastOnce());
        assertFalse(service.isLastCheckFailed());

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status());
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, service.checkStatus());
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, service.getLastCheckResult().status());
        assertTrue(service.hasCheckedAtLeastOnce());
        assertTrue(service.isLastCheckFailed());
        assertTrue(result.error().isPresent());
        assertEquals(result.error(), service.getLastCheckError());
    }

    @Test
    @DisplayName("Legitimate checksum paths containing directory prefix are discovered successfully (F10)")
    void legitimateChecksumPathsWithDirectoryPrefixAreDiscoveredSuccessfully() {
        String expectedHash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

        // 1. sha256sum in asset with directory path: sha256/DiscordTowny-1.10.0.jar
        String assetContent1 = expectedHash + "  sha256/DiscordTowny-1.10.0.jar\n";
        // 2. BSD style in asset with directory path: SHA256 (sha256/DiscordTowny-1.10.0.jar) = <hex>
        String assetContent2 = "SHA256 (sha256/DiscordTowny-1.10.0.jar) = " + expectedHash + "\n";
        // 3. sha256sum in release body: <hex>  sha256/DiscordTowny-1.10.0.jar
        String bodyContent1 = expectedHash + "  sha256/DiscordTowny-1.10.0.jar";
        // 4. BSD style in release body: SHA256 (sha256/DiscordTowny-1.10.0.jar) = <hex>
        String bodyContent2 = "SHA256 (sha256/DiscordTowny-1.10.0.jar) = " + expectedHash;

        List<Supplier<HttpTransport>> transportSuppliers = List.of(
                () -> (uri, headers, timeout) -> {
                    if (uri.toString().endsWith(".txt")) {
                        return new HttpTransport.HttpResponse(200, Map.of(),
                                new ByteArrayInputStream(assetContent1.getBytes(StandardCharsets.UTF_8)));
                    }
                    String json = """
                            {
                              "tag_name": "v1.10.0",
                              "body": "Normal notes",
                              "assets": [
                                {"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"},
                                {"name": "DiscordTowny-1.10.0.jar.sha256.txt", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256.txt"}
                              ]
                            }
                            """;
                    return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
                },
                () -> (uri, headers, timeout) -> {
                    if (uri.toString().endsWith(".txt")) {
                        return new HttpTransport.HttpResponse(200, Map.of(),
                                new ByteArrayInputStream(assetContent2.getBytes(StandardCharsets.UTF_8)));
                    }
                    String json = """
                            {
                              "tag_name": "v1.10.0",
                              "body": "Normal notes",
                              "assets": [
                                {"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"},
                                {"name": "DiscordTowny-1.10.0.jar.sha256.txt", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256.txt"}
                              ]
                            }
                            """;
                    return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
                },
                () -> (uri, headers, timeout) -> {
                    String json = """
                            {
                              "tag_name": "v1.10.0",
                              "body": "%s",
                              "assets": [
                                {"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"}
                              ]
                            }
                            """.formatted(bodyContent1);
                    return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
                },
                () -> (uri, headers, timeout) -> {
                    String json = """
                            {
                              "tag_name": "v1.10.0",
                              "body": "%s",
                              "assets": [
                                {"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"}
                              ]
                            }
                            """.formatted(bodyContent2);
                    return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
                }
        );

        for (Supplier<HttpTransport> supplier : transportSuppliers) {
            DefaultUpdateService service = new DefaultUpdateService(
                    "1.0.0",
                    new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                    updateFolder,
                    activeJar,
                    "DiscordTowny.jar",
                    supplier.get(),
                    testLogger,
                    auditEvents::add,
                    ForkJoinPool.commonPool(),
                    null,
                    false,
                    50 * 1024 * 1024L,
                    Duration.ofSeconds(5)
            );

            UpdateService.CheckResult result = service.checkForUpdate().join();
            assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status());
            assertTrue(result.release().isPresent());
            assertEquals(expectedHash, result.release().get().sha256());
        }
    }

    @Test
    @DisplayName("Malformed checksum declaration with directory prefix refuses release even with valid checksum asset (F2, F10)")
    void malformedChecksumWithDirectoryPrefixRefusesReleaseEvenWithValidAsset() {
        String validAssetSha = "1111111111222222222233333333334444444444555555555566666666667777";
        List<String> malformedBodyLines = List.of(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef9  sha256/DiscordTowny-1.10.0.jar",
                "invalid  sha256/DiscordTowny-1.10.0.jar",
                "SHA256 (sha256/DiscordTowny-1.10.0.jar) = invalid"
        );

        for (String malformedLine : malformedBodyLines) {
            String releaseJson = """
                    {
                      "tag_name": "v1.10.0",
                      "body": "%s",
                      "assets": [
                        {
                          "name": "DiscordTowny-1.10.0.jar",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                        },
                        {
                          "name": "DiscordTowny-1.10.0.jar.sha256",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                        }
                      ]
                    }
                    """.formatted(malformedLine);

            HttpTransport transport = (uri, headers, timeout) -> {
                if (uri.toString().endsWith(".sha256")) {
                    return new HttpTransport.HttpResponse(200, Map.of(),
                            new ByteArrayInputStream(validAssetSha.getBytes(StandardCharsets.UTF_8)));
                }
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            };

            DefaultUpdateService service = new DefaultUpdateService(
                    "1.0.0",
                    new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                    updateFolder,
                    activeJar,
                    "DiscordTowny.jar",
                    transport,
                    testLogger,
                    auditEvents::add,
                    ForkJoinPool.commonPool(),
                    null,
                    false,
                    50 * 1024 * 1024L,
                    Duration.ofSeconds(5)
            );

            UpdateService.CheckResult result = service.checkForUpdate().join();
            assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status(),
                    "Malformed checksum with directory prefix must fail check: " + malformedLine);
            assertTrue(result.release().isEmpty());
            assertTrue(service.isLastCheckFailed());
            assertTrue(service.getLastCheckError().isPresent());
        }
    }

    @Test
    @DisplayName("Same-line BSD with directory prefix and invalid declaration refuses release (F2, F10)")
    void sameLineBsdWithDirectoryPrefixAndInvalidDeclarationRefusesRelease() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String validAssetSha = "1111111111222222222233333333334444444444555555555566666666667777";
        List<String> badLines = List.of(
                "SHA256 (sha256/DiscordTowny-1.10.0.jar) = " + validHex + " SHA-256: invalid",
                "SHA-256: invalid SHA256 (sha256/DiscordTowny-1.10.0.jar) = " + validHex
        );

        for (String badLine : badLines) {
            String releaseJson = """
                    {
                      "tag_name": "v1.10.0",
                      "body": "%s",
                      "assets": [
                        {
                          "name": "DiscordTowny-1.10.0.jar",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                        },
                        {
                          "name": "DiscordTowny-1.10.0.jar.sha256",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                        }
                      ]
                    }
                    """.formatted(badLine);

            HttpTransport transport = (uri, headers, timeout) -> {
                if (uri.toString().endsWith(".sha256")) {
                    return new HttpTransport.HttpResponse(200, Map.of(),
                            new ByteArrayInputStream(validAssetSha.getBytes(StandardCharsets.UTF_8)));
                }
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            };

            DefaultUpdateService service = new DefaultUpdateService(
                    "1.0.0",
                    new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                    updateFolder,
                    activeJar,
                    "DiscordTowny.jar",
                    transport,
                    testLogger,
                    auditEvents::add,
                    ForkJoinPool.commonPool(),
                    null,
                    false,
                    50 * 1024 * 1024L,
                    Duration.ofSeconds(5)
            );

            UpdateService.CheckResult result = service.checkForUpdate().join();
            assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status(),
                    "Same line with valid and invalid declaration must fail check: " + badLine);
            assertTrue(result.release().isEmpty());
            assertTrue(service.isLastCheckFailed());
            assertTrue(service.getLastCheckError().isPresent());
        }
    }

    @Test
    @DisplayName("Valid labeled checksum declaration with directory prefix is discovered successfully (F7, F10)")
    void validLabeledChecksumWithDirectoryPrefixIsDiscoveredSuccessfully() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String bodyText = "SHA-256: " + validHex + " sha256/DiscordTowny-1.10.0.jar";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\""));

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertTrue(result.release().isPresent(), "Release with valid labeled checksum containing directory prefix must be accepted");
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status());
        assertEquals("1.10.0", result.release().get().version());
        assertEquals(validHex, result.release().get().sha256());
        assertFalse(service.isLastCheckFailed());
    }

    @Test
    @DisplayName("Missing release metadata is categorized as parse error, not network error (F9)")
    void missingReleaseMetadataCategorizedAsParseErrorNotNetwork() {
        List<String> badPayloads = List.of(
                "{}",
                "{\"tag_name\": \"\"}",
                "{\"tag_name\": \"v1.10.0\"}",
                "{\"tag_name\": \"v1.10.0\", \"assets\": []}"
        );

        for (String payload : badPayloads) {
            HttpTransport transport = (uri, headers, timeout) ->
                    new HttpTransport.HttpResponse(200, Map.of(),
                            new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));

            DefaultUpdateService service = new DefaultUpdateService(
                    "1.0.0",
                    new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                    updateFolder,
                    activeJar,
                    "DiscordTowny.jar",
                    transport,
                    testLogger,
                    auditEvents::add,
                    ForkJoinPool.commonPool(),
                    null,
                    false,
                    50 * 1024 * 1024L,
                    Duration.ofSeconds(5)
            );

            UpdateService.CheckResult result = service.checkForUpdate().join();
            assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status(),
                    "Empty or missing metadata must fail check: " + payload);
            assertTrue(result.release().isEmpty());
            assertTrue(service.isLastCheckFailed());

            // English messages
            List<String> enLines = service.renderStatusMessages(EnglishMessages.bundled());
            assertTrue(enLines.stream().anyMatch(l -> l.contains("could not parse release metadata")),
                    "English status must report parse error for " + payload + ", got: " + enLines);
            assertFalse(enLines.stream().anyMatch(l -> l.contains("could not reach GitHub")),
                    "English status must NEVER claim network error when metadata was missing!");

            // Spanish messages
            Messages esMessages = loadSpanishMessages();
            List<String> esLines = service.renderStatusMessages(esMessages);
            assertTrue(esLines.stream().anyMatch(l -> l.contains("no se pudo interpretar la información")),
                    "Spanish status must report parse error for " + payload + ", got: " + esLines);
            assertFalse(esLines.stream().anyMatch(l -> l.contains("no se pudo conectar con GitHub")),
                    "Spanish status must NEVER claim network error when metadata was missing!");
        }
    }

    @Test
    @DisplayName("Conditional 304 response never publishes UP_TO_DATE without owning confirmed absence (F1-A)")
    void conditional304ResponseNeverPublishesUpToDateWithoutOwnedAbsenceRepresentation() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: %s DiscordTowny-1.10.0.jar",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    }
                  ]
                }
                """.formatted(validHex);

        AtomicInteger callCount = new AtomicInteger(0);
        List<String> capturedIfNoneMatch = new CopyOnWriteArrayList<>();

        HttpTransport transport = (uri, headers, timeout) -> {
            int call = callCount.incrementAndGet();
            String ifNoneMatch = headers.get("If-None-Match");
            if (ifNoneMatch != null) {
                capturedIfNoneMatch.add(ifNoneMatch);
            }
            if (call == 1) {
                // Initial unprompted 304 without cached release: must never publish UP_TO_DATE
                return new HttpTransport.HttpResponse(304, Map.of(), new ByteArrayInputStream(new byte[0]));
            }
            if (call == 2) {
                // 200 with new release and ETag
                return new HttpTransport.HttpResponse(200, Map.of("ETag", "\"etag-v110\""),
                        new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            }
            // Subsequent check receives 304 with ETag sent
            return new HttpTransport.HttpResponse(304, Map.of(), new ByteArrayInputStream(new byte[0]));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // 1. Initial 304 without cached representation: must fail, NEVER UP_TO_DATE
        UpdateService.CheckResult res1 = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, res1.status());
        assertNotEquals(UpdateService.CheckStatus.UP_TO_DATE, service.checkStatus());

        // 2. Discover newer release with ETag: commits ETag and release atomically
        UpdateService.CheckResult res2 = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, res2.status());
        assertEquals("1.10.0", res2.release().get().version());
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, service.checkStatus());

        // 3. Conditional 304: must publish UPDATE_AVAILABLE with the owned cached release, NEVER UP_TO_DATE
        UpdateService.CheckResult res3 = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, res3.status());
        assertEquals("1.10.0", res3.release().get().version());
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, service.checkStatus());
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, service.getLastCheckResult().status());
        assertTrue(service.getAvailableUpdate().isPresent());
        assertEquals("1.10.0", service.getAvailableUpdate().get().version());
        assertFalse(service.isLastCheckFailed());
        assertTrue(capturedIfNoneMatch.contains("\"etag-v110\""));
    }

    @Test
    @DisplayName("sha256sum whose filename absorbs whitespace-separated label declaration refuses release without fallback (F2)")
    void sha256sumFilenameAbsorbingWhitespaceSeparatedLabelRefusesReleaseEvenWithValidAsset() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        // Candidate where filename contains absorbed whitespace-separated label declaration
        String bodyText = validHex + "  SHA-256 invalid/DiscordTowny-1.10.0.jar";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "checksums.txt",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/checksums.txt"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\""));

        String validChecksumAsset = validHex + "  DiscordTowny-1.10.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith("checksums.txt")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(validChecksumAsset.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertTrue(result.release().isEmpty(), "Absorbed whitespace-separated declaration in filename must cause release to be refused");
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status());
        assertTrue(service.isLastCheckFailed());
    }

    @Test
    @DisplayName("Accepting a declaration must never skip validating another on that line, refusing even with valid follower (F2)")
    void sha256sumLineWithTrailingMalformedDeclarationRefusesReleaseEvenWithValidLineFollower() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        // Line 1: sum declaration with trailing malformed label; Line 2: valid declaration
        String bodyText = validHex + "  DiscordTowny-1.10.0.jar SHA-256 invalid\nSHA-256: " + validHex;

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "checksums.txt",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/checksums.txt"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\"").replace("\n", "\\n"));

        String validChecksumAsset = validHex + "  DiscordTowny-1.10.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith("checksums.txt")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(validChecksumAsset.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertTrue(result.release().isEmpty(), "Malformed declaration on line must refuse release even with valid second line and valid asset");
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status());
        assertTrue(service.isLastCheckFailed());
    }

    @Test
    @DisplayName("Compact labeled checksum declarations without spaces are discovered successfully (F11)")
    void compactLabeledChecksumWithoutSpacesIsDiscoveredSuccessfully() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        List<String> bodyVariants = List.of(
                "SHA-256:" + validHex + " DiscordTowny-1.10.0.jar",
                "SHA-256=" + validHex + " DiscordTowny-1.10.0.jar"
        );

        for (String bodyText : bodyVariants) {
            String releaseJson = """
                    {
                      "tag_name": "v1.10.0",
                      "body": "%s",
                      "assets": [
                        {
                          "name": "DiscordTowny-1.10.0.jar",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                        }
                      ]
                    }
                    """.formatted(bodyText.replace("\"", "\\\""));

            HttpTransport transport = (uri, headers, timeout) ->
                    new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

            DefaultUpdateService service = new DefaultUpdateService(
                    "1.0.0",
                    new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                    updateFolder,
                    activeJar,
                    "DiscordTowny.jar",
                    transport,
                    testLogger,
                    auditEvents::add,
                    ForkJoinPool.commonPool(),
                    null,
                    false,
                    50 * 1024 * 1024L,
                    Duration.ofSeconds(5)
            );

            UpdateService.CheckResult result = service.checkForUpdate().join();
            assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status(),
                    "Compact labeled format must be discovered: " + bodyText);
            assertTrue(result.release().isPresent());
            assertEquals(validHex, result.release().get().sha256());
            assertFalse(service.isLastCheckFailed());
        }
    }

    @Test
    @DisplayName("Delayed legitimate 304 from older check cannot erase newer release published by subsequent check (F13)")
    void delayedLegitimate304PublishesUpToDateAndClearsAvailableReleaseDeterministically() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String runningVersionJson = """
                {
                  "tag_name": "v1.0.0",
                  "body": "SHA-256: %s DiscordTowny-1.0.0.jar",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.0.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar"
                    }
                  ]
                }
                """.formatted(validHex);

        String newerReleaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: %s DiscordTowny-1.10.0.jar",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    }
                  ]
                }
                """.formatted(validHex);

        CompletableFuture<Void> checkAStarted = new CompletableFuture<>();
        CompletableFuture<HttpTransport.HttpResponse> checkAResponseReady = new CompletableFuture<>();
        AtomicInteger step = new AtomicInteger(0);

        HttpTransport transport = (uri, headers, timeout) -> {
            int currentStep = step.incrementAndGet();
            if (currentStep == 1) {
                // Step 1: Initial check of running version: 200 OK with E0, not newer
                return new HttpTransport.HttpResponse(200, Map.of("ETag", "\"E0\""),
                        new ByteArrayInputStream(runningVersionJson.getBytes(StandardCharsets.UTF_8)));
            } else if (currentStep == 2) {
                // Step 2: Check A arrives with If-None-Match: "E0". Intercept and suspend.
                checkAStarted.complete(null);
                try {
                    return checkAResponseReady.join();
                } catch (Exception e) {
                    throw new IOException("Check A response interrupted", e);
                }
            } else {
                // Step 3: Check B arrives and gets 200 OK with newer release R (ETag "E1")
                return new HttpTransport.HttpResponse(200, Map.of("ETag", "\"E1\""),
                        new ByteArrayInputStream(newerReleaseJson.getBytes(StandardCharsets.UTF_8)));
            }
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false), // auto-download disabled
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // 1. Initial check: caches (E0, null, true)
        UpdateService.CheckResult resInitial = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UP_TO_DATE, resInitial.status());
        assertEquals(UpdateService.CheckStatus.UP_TO_DATE, service.checkStatus());
        assertTrue(service.getAvailableUpdate().isEmpty());

        // 2. Launch Check A asynchronously: sends If-None-Match: E0 and pauses in transport
        CompletableFuture<UpdateService.CheckResult> futureA = service.checkForUpdate();
        checkAStarted.join();

        // 3. Launch and complete Check B while Check A is suspended
        UpdateService.CheckResult resB = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, resB.status());
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, service.checkStatus());
        assertTrue(service.getAvailableUpdate().isPresent());
        assertEquals("1.10.0", service.getAvailableUpdate().get().version());

        // 4. Release Check A's legitimate 304 response answering its request with validator E0
        checkAResponseReady.complete(new HttpTransport.HttpResponse(304, Map.of(), new ByteArrayInputStream(new byte[0])));
        UpdateService.CheckResult resA = futureA.join();
        assertEquals(UpdateService.CheckStatus.UP_TO_DATE, resA.status());

        // 5. Stale 304 from older check must not erase newer release published by subsequent check (F13)
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, service.checkStatus(),
                "checkStatus must retain UPDATE_AVAILABLE from newer check B");
        assertTrue(service.getAvailableUpdate().isPresent(),
                "getAvailableUpdate() must retain release 1.10.0 from newer check B; older 304 cannot erase it");
        assertEquals("1.10.0", service.getAvailableUpdate().get().version());
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, service.getLastCheckResult().status());
        assertTrue(service.getLastCheckResult().release().isPresent());
        assertEquals("1.10.0", service.getLastCheckResult().release().get().version());

        List<String> rendered = service.renderStatusMessages(EnglishMessages.bundled());
        assertTrue(rendered.stream().anyMatch(l -> l.contains("There is a new version")),
                "Status must render available update, got: " + rendered);
        assertFalse(rendered.stream().anyMatch(l -> l.contains("You are on the latest version")),
                "Status must never render up to date when update is available, got: " + rendered);
    }

    @Test
    @DisplayName("Overlapping 200 checks ensure later-started completed check remains authoritative (F13)")
    void overlapping200ChecksNeverLeavePublishedUpdateWithEmptyAvailabilityDeterministically() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String newerReleaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: %s DiscordTowny-1.10.0.jar",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    }
                  ]
                }
                """.formatted(validHex);

        String currentVersionJson = """
                {
                  "tag_name": "v1.0.0",
                  "body": "SHA-256: %s DiscordTowny-1.0.0.jar",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.0.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar"
                    }
                  ]
                }
                """.formatted(validHex);

        AtomicInteger callCount = new AtomicInteger(0);
        HttpTransport transport = (uri, headers, timeout) -> {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                // Check A gets newer release 1.10.0
                return new HttpTransport.HttpResponse(200, Map.of("ETag", "\"E-A\""),
                        new ByteArrayInputStream(newerReleaseJson.getBytes(StandardCharsets.UTF_8)));
            } else {
                // Check B gets non-newer running version 1.0.0
                return new HttpTransport.HttpResponse(200, Map.of("ETag", "\"E-B\""),
                        new ByteArrayInputStream(currentVersionJson.getBytes(StandardCharsets.UTF_8)));
            }
        };

        AtomicReference<DefaultUpdateService> serviceRef = new AtomicReference<>();
        AtomicBoolean checkBExecuted = new AtomicBoolean(false);

        // When Check A processes newer release, auditLogger is called before publishing result
        Consumer<AuditEvent> testAuditLogger = event -> {
            if ("update_available".equals(event.action()) && checkBExecuted.compareAndSet(false, true)) {
                // Interleave Check B synchronously on another check while Check A is pausing before publishing
                DefaultUpdateService svc = serviceRef.get();
                if (svc != null) {
                    UpdateService.CheckResult resB = svc.checkForUpdate().join();
                    assertEquals(UpdateService.CheckStatus.UP_TO_DATE, resB.status());
                }
            }
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false), // auto-download disabled
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                testAuditLogger,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );
        serviceRef.set(service);

        // Check A runs and its auditLogger hook triggers Check B before A publishes
        UpdateService.CheckResult resA = service.checkForUpdate().join();
        assertTrue(checkBExecuted.get(), "Check B must have executed during Check A's pause");
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, resA.status());

        // Aggregate settled state: later-started completed Check B remains authoritative (F13)
        assertEquals(UpdateService.CheckStatus.UP_TO_DATE, service.checkStatus(),
                "checkStatus must retain UP_TO_DATE from later-started check B; older check A cannot overwrite it");
        assertTrue(service.getAvailableUpdate().isEmpty(),
                "getAvailableUpdate() must be empty because later-started check B published UP_TO_DATE");
        assertEquals(UpdateService.CheckStatus.UP_TO_DATE, service.getLastCheckResult().status(),
                "lastCheckResult status must reflect later-started check B");
        assertTrue(service.getLastCheckResult().release().isEmpty(),
                "lastCheckResult release must be empty because later-started check B published UP_TO_DATE");

        List<String> rendered = service.renderStatusMessages(EnglishMessages.bundled());
        assertTrue(rendered.stream().anyMatch(l -> l.contains("You are on the latest version")),
                "Status must report latest version from later-started check, got: " + rendered);
        assertFalse(rendered.stream().anyMatch(l -> l.contains("There is a new version")),
                "Status must not claim update available when later check published up to date, got: " + rendered);
    }

    @Test
    @DisplayName("Unsolicited 304 without request-owned validator fails check and never borrows success (F1-A)")
    void unsolicited304WithoutRequestOwnedValidatorFailsCheckAndNeverBorrowsSuccess() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String newerReleaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: %s DiscordTowny-1.10.0.jar",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    }
                  ]
                }
                """.formatted(validHex);

        CompletableFuture<Void> checkAStarted = new CompletableFuture<>();
        CompletableFuture<HttpTransport.HttpResponse> checkAResponseReady = new CompletableFuture<>();
        AtomicInteger callCount = new AtomicInteger(0);

        HttpTransport transport = (uri, headers, timeout) -> {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                // Check A starts on fresh service (no If-None-Match header)
                assertNull(headers.get("If-None-Match"), "Check A must send no validator on fresh service");
                checkAStarted.complete(null);
                try {
                    return checkAResponseReady.join();
                } catch (Exception e) {
                    throw new IOException("Check A response interrupted", e);
                }
            } else {
                // Check B receives 200 with newer release
                return new HttpTransport.HttpResponse(200, Map.of("ETag", "\"E-B\""),
                        new ByteArrayInputStream(newerReleaseJson.getBytes(StandardCharsets.UTF_8)));
            }
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        // Check A starts without cache and pauses in transport
        CompletableFuture<UpdateService.CheckResult> futureA = service.checkForUpdate();
        checkAStarted.join();

        // Check B completes valid 200 check, publishing UPDATE_AVAILABLE
        UpdateService.CheckResult resB = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, resB.status());

        // Check A receives unsolicited 304 response
        checkAResponseReady.complete(new HttpTransport.HttpResponse(304, Map.of(), new ByteArrayInputStream(new byte[0])));
        UpdateService.CheckResult resA = futureA.join();

        // Check A must fail explicitly and NOT borrow Check B's classification
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, resA.status(),
                "Check A must fail on unsolicited 304 without validator, not borrow Check B's status");
        assertTrue(resA.error().isPresent());
        assertTrue(resA.error().get().contains("unexpected 304 without cached release representation"));
    }

    @Test
    @DisplayName("Malformed sum declaration for another artifact refuses release even with valid dedicated asset (F2)")
    void malformedChecksumForOtherArtifactRefusesReleaseEvenWithValidDedicatedAsset() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        // 65 hex digits for other artifact (-sources.jar)
        String malformed65Hex = validHex + "9";
        String bodyText = malformed65Hex + "  DiscordTowny-1.10.0-sources.jar";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\""));

        String dedicatedAssetContent = validHex + "  DiscordTowny-1.10.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".sha256")) {
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(dedicatedAssetContent.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(),
                    new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status(),
                "Malformed declaration for another artifact must refuse release; valid asset must not override it");
        assertTrue(service.isLastCheckFailed());
        assertTrue(result.error().isPresent());
        String err = result.error().get().toLowerCase(Locale.ROOT);
        assertTrue(err.contains("checksum") || err.contains("malformed") || err.contains("invalid"),
                "Error must indicate checksum failure, got: " + err);
    }

    @Test
    @DisplayName("Malformed sum declaration with plus in filename for another artifact refuses release even with valid dedicated asset (F2)")
    void malformedChecksumWithPlusInFilenameRefusesReleaseEvenWithValidDedicatedAsset() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        // 65 hex digits (<H>9) for other artifact (-sources+dev.jar)
        String malformed65Hex = validHex + "9";
        String bodyText = malformed65Hex + "  DiscordTowny-1.10.0-sources+dev.jar";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\""));

        String dedicatedAssetContent = validHex + "  DiscordTowny-1.10.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".sha256")) {
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(dedicatedAssetContent.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(),
                    new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status(),
                "Malformed declaration with '+' in filename for another artifact must refuse release; valid asset must not override it");
        assertTrue(service.isLastCheckFailed());
        assertTrue(result.error().isPresent());
        String err = result.error().get().toLowerCase(Locale.ROOT);
        assertTrue(err.contains("checksum") || err.contains("malformed") || err.contains("invalid"),
                "Error must indicate checksum failure, got: " + err);
    }

    @Test
    @DisplayName("Legitimate directory-prefixed filename sha256.txt is accepted and not reinterpreted as label (F12)")
    void sha256sumWithDirectoryPrefixedFilenameContainingSha256InDirectoryNameIsAccepted() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String bodyText = validHex + "  sha256.txt/DiscordTowny-1.10.0.jar";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\""));

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status(),
                "sha256.txt/ prefix must not be reinterpreted as a declaration keyword");
        assertFalse(service.isLastCheckFailed());
        assertTrue(service.getAvailableUpdate().isPresent());
        assertEquals("1.10.0", service.getAvailableUpdate().get().version());
        assertEquals(validHex.toLowerCase(Locale.ROOT), service.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("Labeled declaration with directory-prefixed filename sha256.txt is accepted (F12)")
    void labeledDeclarationWithDirectoryPrefixContainingSha256InDirectoryNameIsAccepted() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String bodyText = "SHA-256: " + validHex + " sha256.txt/DiscordTowny-1.10.0.jar";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\""));

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status());
        assertFalse(service.isLastCheckFailed());
        assertTrue(service.getAvailableUpdate().isPresent());
        assertEquals("1.10.0", service.getAvailableUpdate().get().version());
    }

    @Test
    @DisplayName("Checksum asset file declaring malformed sum for another artifact is refused (F2)")
    void checksumAssetWithMalformedDeclarationForOtherArtifactRefusesRelease() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String malformed65Hex = validHex + "9";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "Release v1.10.0 notes",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "checksums.txt",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/checksums.txt"
                    }
                  ]
                }
                """;

        String checksumAssetContent = malformed65Hex + "  DiscordTowny-1.10.0-sources.jar\n"
                + validHex + "  DiscordTowny-1.10.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith("checksums.txt")) {
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(checksumAssetContent.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(),
                    new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status(),
                "Malformed declaration for another artifact in checksum file must refuse release");
        assertTrue(service.isLastCheckFailed());
    }

    @Test
    @DisplayName("Checksum asset line with prefix and suffix surrounding BSD declaration is refused (F2-A)")
    void checksumAssetWithPrefixAndSuffixSurroundingBsdDeclarationIsRefused() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        // Exact fixture from F2-A: "notice SHA256 (DiscordTowny-1.10.0.jar) = <H> trailing-text"
        String checksumAssetContent = "notice SHA256 (DiscordTowny-1.10.0.jar) = " + validHex + " trailing-text\n";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "Release notes",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "checksums.txt",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/checksums.txt"
                    }
                  ]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith("checksums.txt")) {
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(checksumAssetContent.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(),
                    new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status(),
                "Embedded BSD declaration with prefix and suffix in checksum file must be refused; not accepted as valid declaration");
        assertTrue(service.isLastCheckFailed());
        assertTrue(result.error().isPresent());
        String err = result.error().get().toLowerCase(Locale.ROOT);
        assertTrue(err.contains("checksum") || err.contains("malformed") || err.contains("invalid") || err.contains("unrecognized"),
                "Error must indicate checksum failure, got: " + err);
    }

    @Test
    @DisplayName("Release body containing 'Release  notes' prose beside valid dedicated checksum is accepted (F2-B)")
    void releaseBodyWithReleaseNotesProseBesideValidDedicatedChecksumIsAccepted() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        // Exact fixture from F2-B: "Release  notes" (two spaces)
        String bodyText = "Release  notes";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\""));

        String dedicatedAssetContent = validHex + "  DiscordTowny-1.10.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".sha256")) {
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(dedicatedAssetContent.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(),
                    new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status(),
                "Ordinary prose 'Release  notes' must not be treated as malformed declaration and must not block valid dedicated checksum");
        assertFalse(service.isLastCheckFailed());
        assertTrue(service.getAvailableUpdate().isPresent());
        assertEquals("1.10.0", service.getAvailableUpdate().get().version());
        assertEquals(validHex.toLowerCase(Locale.ROOT), service.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("Quoted sum declaration with malformed digest refuses release for both quote characters (F15)")
    void quotedSumDeclarationWithMalformedDigestRefusesReleaseForBothQuoteCharacters() {
        String validAssetSha = "1111111111222222222233333333334444444444555555555566666666667777";
        List<String> quotedMalformedLines = List.of(
                "\"invalid  sha256/DiscordTowny-1.10.0.jar\"",
                "'invalid  sha256/DiscordTowny-1.10.0.jar'",
                "\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdefHg  DiscordTowny-1.10.0.jar\"",
                "'0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdefHg  DiscordTowny-1.10.0.jar'"
        );

        for (String quotedLine : quotedMalformedLines) {
            String releaseJson = """
                    {
                      "tag_name": "v1.10.0",
                      "body": "%s",
                      "assets": [
                        {
                          "name": "DiscordTowny-1.10.0.jar",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                        },
                        {
                          "name": "DiscordTowny-1.10.0.jar.sha256",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                        }
                      ]
                    }
                    """.formatted(quotedLine.replace("\"", "\\\""));

            HttpTransport transport = (uri, headers, timeout) -> {
                if (uri.toString().endsWith(".sha256")) {
                    return new HttpTransport.HttpResponse(200, Map.of(),
                            new ByteArrayInputStream((validAssetSha + "  DiscordTowny-1.10.0.jar\n").getBytes(StandardCharsets.UTF_8)));
                }
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            };

            DefaultUpdateService service = new DefaultUpdateService(
                    "1.0.0",
                    new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                    updateFolder,
                    activeJar,
                    "DiscordTowny.jar",
                    transport,
                    testLogger,
                    auditEvents::add,
                    ForkJoinPool.commonPool(),
                    null,
                    false,
                    50 * 1024 * 1024L,
                    Duration.ofSeconds(5)
            );

            UpdateService.CheckResult result = service.checkForUpdate().join();
            assertEquals(UpdateService.CheckStatus.CHECK_FAILED, result.status(),
                    "Quoted sum declaration with malformed digest must refuse release: " + quotedLine);
            assertTrue(service.isLastCheckFailed());
            assertTrue(result.error().isPresent());
            assertFalse(service.getAvailableUpdate().isPresent());
        }
    }

    @Test
    @DisplayName("Quoted sum declaration with valid digest is accepted for both quote characters (F15)")
    void quotedSumDeclarationWithValidDigestIsAcceptedForBothQuoteCharacters() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        List<String> quotedValidLines = List.of(
                "\"" + validHex + "  DiscordTowny-1.10.0.jar\"",
                "'" + validHex + "  DiscordTowny-1.10.0.jar'"
        );

        for (String quotedLine : quotedValidLines) {
            String releaseJson = """
                    {
                      "tag_name": "v1.10.0",
                      "body": "%s",
                      "assets": [
                        {
                          "name": "DiscordTowny-1.10.0.jar",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                        }
                      ]
                    }
                    """.formatted(quotedLine.replace("\"", "\\\""));

            HttpTransport transport = (uri, headers, timeout) ->
                    new HttpTransport.HttpResponse(200, Map.of(),
                            new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

            DefaultUpdateService service = new DefaultUpdateService(
                    "1.0.0",
                    new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                    updateFolder,
                    activeJar,
                    "DiscordTowny.jar",
                    transport,
                    testLogger,
                    auditEvents::add,
                    ForkJoinPool.commonPool(),
                    null,
                    false,
                    50 * 1024 * 1024L,
                    Duration.ofSeconds(5)
            );

            UpdateService.CheckResult result = service.checkForUpdate().join();
            assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status(),
                    "Quoted sum declaration with valid digest must be accepted: " + quotedLine);
            assertFalse(service.isLastCheckFailed());
            assertTrue(service.getAvailableUpdate().isPresent());
            assertEquals("1.10.0", service.getAvailableUpdate().get().version());
            assertEquals(validHex.toLowerCase(Locale.ROOT), service.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    @DisplayName("Checksum asset with leading UTF-8 BOM on BSD declaration line is accepted (F16)")
    void checksumAssetWithLeadingUtf8BomOnBsdDeclarationLineIsAccepted() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String bsdContentWithBom = "\uFEFFSHA256 (DiscordTowny-1.10.0.jar) = " + validHex + "\n";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "Release notes",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "checksums.txt",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/checksums.txt"
                    }
                  ]
                }
                """;

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith("checksums.txt")) {
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(bsdContentWithBom.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(),
                    new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status(),
                "Leading UTF-8 BOM on BSD declaration line in checksum asset must be accepted");
        assertFalse(service.isLastCheckFailed());
        assertTrue(service.getAvailableUpdate().isPresent());
        assertEquals("1.10.0", service.getAvailableUpdate().get().version());
        assertEquals(validHex.toLowerCase(Locale.ROOT), service.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("Published asset named 'notes' with body line 'Release  notes' beside valid dedicated checksum succeeds (F17)")
    void publishedAssetNamedNotesWithReleaseNotesProseBesideValidDedicatedChecksumSucceeds() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String bodyText = "Release  notes";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "notes",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/notes"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\""));

        String dedicatedAssetContent = validHex + "  DiscordTowny-1.10.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".sha256")) {
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(dedicatedAssetContent.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(),
                    new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status(),
                "Ordinary prose 'Release  notes' must not be treated as a declaration even when 'notes' is a published asset");
        assertFalse(service.isLastCheckFailed());
        assertTrue(service.getAvailableUpdate().isPresent());
        assertEquals("1.10.0", service.getAvailableUpdate().get().version());
        assertEquals(validHex.toLowerCase(Locale.ROOT), service.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("Release body with 'Release  docs/notes' beside valid dedicated checksum succeeds (F17)")
    void releaseBodyWithDocsNotesBesideValidDedicatedChecksumSucceeds() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String bodyText = "Release  docs/notes";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "notes",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/notes"
                    },
                    {
                      "name": "docs/notes",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/docs/notes"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """.formatted(bodyText.replace("\"", "\\\""));

        String dedicatedAssetContent = validHex + "  DiscordTowny-1.10.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".sha256")) {
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(dedicatedAssetContent.getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(),
                    new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status(),
                "Ordinary prose 'Release  docs/notes' must not be treated as a declaration even when notes/docs/notes exists");
        assertFalse(service.isLastCheckFailed());
        assertTrue(service.getAvailableUpdate().isPresent());
        assertEquals("1.10.0", service.getAvailableUpdate().get().version());
        assertEquals(validHex.toLowerCase(Locale.ROOT), service.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("Body whose first line is a BOM followed by a valid sum line succeeds and binds digest (F18)")
    void bodyWithLeadingUtf8BomFollowedByValidSumLineSucceedsAndBindsDigest() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String bodyContent = "\uFEFF" + validHex + "  DiscordTowny-1.10.0.jar\n";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    }
                  ]
                }
                """.formatted(bodyContent.replace("\n", "\\n"));

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status(),
                "Body whose first line is a BOM followed by a valid sum line must be accepted");
        assertFalse(service.isLastCheckFailed());
        assertTrue(service.getAvailableUpdate().isPresent());
        assertEquals("1.10.0", service.getAvailableUpdate().get().version());
        assertEquals(validHex.toLowerCase(Locale.ROOT), service.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("Extension-shaped prose naming published assets stays prose and does not block valid dedicated checksum (F19)")
    void extensionShapedProseLinesNamingPublishedAssetsStayProseAndDoNotBlockValidDedicatedChecksum() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        record Case(String bodyLine, List<String> publishedAssets) {}

        List<Case> proseCases = List.of(
                new Case("Release  notes.txt", List.of("DiscordTowny-1.10.0.jar", "notes.txt")),
                new Case("Version  1.2", List.of("DiscordTowny-1.10.0.jar", "1.2")),
                new Case("See  docs/notes.txt", List.of("DiscordTowny-1.10.0.jar", "notes.txt")),
                new Case("Download  https://example.test/notes.txt", List.of("DiscordTowny-1.10.0.jar", "notes.txt"))
        );

        for (Case c : proseCases) {
            StringBuilder assetsJson = new StringBuilder();
            for (int i = 0; i < c.publishedAssets().size(); i++) {
                String asset = c.publishedAssets().get(i);
                assetsJson.append("""
                        {
                          "name": "%s",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/%s"
                        },
                        """.formatted(asset, asset));
            }
            assetsJson.append("""
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                    """);

            String releaseJson = """
                    {
                      "tag_name": "v1.10.0",
                      "body": "%s",
                      "assets": [
                        %s
                      ]
                    }
                    """.formatted(c.bodyLine().replace("\"", "\\\""), assetsJson.toString().trim());

            String dedicatedAssetContent = validHex + "  DiscordTowny-1.10.0.jar\n";

            HttpTransport transport = (uri, headers, timeout) -> {
                if (uri.toString().endsWith(".sha256")) {
                    return new HttpTransport.HttpResponse(200, Map.of(),
                            new ByteArrayInputStream(dedicatedAssetContent.getBytes(StandardCharsets.UTF_8)));
                }
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            };

            DefaultUpdateService service = new DefaultUpdateService(
                    "1.0.0",
                    new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                    updateFolder,
                    activeJar,
                    "DiscordTowny.jar",
                    transport,
                    testLogger,
                    auditEvents::add,
                    ForkJoinPool.commonPool(),
                    null,
                    false,
                    50 * 1024 * 1024L,
                    Duration.ofSeconds(5)
            );

            UpdateService.CheckResult result = service.checkForUpdate().join();
            assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status(),
                    "Prose line '" + c.bodyLine() + "' must not be treated as a declaration even when published asset exists");
            assertFalse(service.isLastCheckFailed());
            assertTrue(service.getAvailableUpdate().isPresent());
            assertEquals("1.10.0", service.getAvailableUpdate().get().version());
            assertEquals(validHex.toLowerCase(Locale.ROOT), service.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    @DisplayName("Extensionless published artifact with broken word is prose; broken digest refuses release (F20)")
    void extensionlessPublishedArtifactWithBrokenWordIsProseWhileBrokenDigestRefuses() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String validAssetSha = "1111111111222222222233333333334444444444555555555566666666667777";

        // 1. "invalid  launcher": broken word is prose under the contract.
        // Beside valid dedicated checksum asset, release must be accepted.
        String releaseJsonProse = """
                {
                  "tag_name": "v1.10.0",
                  "body": "invalid  launcher",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "launcher",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/launcher"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """;

        HttpTransport transportProse = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".sha256")) {
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream((validAssetSha + "  DiscordTowny-1.10.0.jar\n").getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(),
                    new ByteArrayInputStream(releaseJsonProse.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService serviceProse = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transportProse,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult resultProse = serviceProse.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, resultProse.status(),
                "Broken word 'invalid  launcher' for extensionless published artifact is prose and must not refuse release");
        assertFalse(serviceProse.isLastCheckFailed());
        assertTrue(serviceProse.getAvailableUpdate().isPresent());
        assertEquals(validAssetSha.toLowerCase(Locale.ROOT), serviceProse.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));

        // 2. "0123...Hg  launcher": hash-shaped broken digest for extensionless published artifact
        // is recognized as a declaration under Rule 2 and strictly refuses release.
        String brokenDigestLine = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdefHg  launcher";
        String releaseJsonRefused = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    },
                    {
                      "name": "launcher",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/launcher"
                    },
                    {
                      "name": "DiscordTowny-1.10.0.jar.sha256",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar.sha256"
                    }
                  ]
                }
                """.formatted(brokenDigestLine);

        HttpTransport transportRefused = (uri, headers, timeout) -> {
            if (uri.toString().endsWith(".sha256")) {
                return new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream((validAssetSha + "  DiscordTowny-1.10.0.jar\n").getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpTransport.HttpResponse(200, Map.of(),
                    new ByteArrayInputStream(releaseJsonRefused.getBytes(StandardCharsets.UTF_8)));
        };

        DefaultUpdateService serviceRefused = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transportRefused,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult resultRefused = serviceRefused.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, resultRefused.status(),
                "Hash-shaped broken digest for extensionless published artifact must refuse release under Rule 2");
        assertTrue(serviceRefused.isLastCheckFailed());
        assertTrue(resultRefused.error().isPresent());
        assertFalse(serviceRefused.getAvailableUpdate().isPresent());
    }

    @Test
    @DisplayName("Body line with leading U+FEFF after whitespace or list prefix binds digest cleanly (F21)")
    void bodyLineWithLeadingUtf8BomAfterWhitespaceOrListPrefixBindsDigestCleanly() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        List<String> bomLines = List.of(
                "  \uFEFF" + validHex + "  DiscordTowny-1.10.0.jar",
                "- \uFEFF" + validHex + "  DiscordTowny-1.10.0.jar"
        );

        for (String bomLine : bomLines) {
            String releaseJson = """
                    {
                      "tag_name": "v1.10.0",
                      "body": "%s",
                      "assets": [
                        {
                          "name": "DiscordTowny-1.10.0.jar",
                          "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                        }
                      ]
                    }
                    """.formatted(bomLine.replace("\"", "\\\""));

            HttpTransport transport = (uri, headers, timeout) ->
                    new HttpTransport.HttpResponse(200, Map.of(),
                            new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

            DefaultUpdateService service = new DefaultUpdateService(
                    "1.0.0",
                    new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                    updateFolder,
                    activeJar,
                    "DiscordTowny.jar",
                    transport,
                    testLogger,
                    auditEvents::add,
                    ForkJoinPool.commonPool(),
                    null,
                    false,
                    50 * 1024 * 1024L,
                    Duration.ofSeconds(5)
            );

            UpdateService.CheckResult result = service.checkForUpdate().join();
            assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status(),
                    "Leading U+FEFF after whitespace or list prefix must bind digest and succeed: " + bomLine);
            assertFalse(service.isLastCheckFailed());
            assertTrue(service.getAvailableUpdate().isPresent());
            assertEquals("1.10.0", service.getAvailableUpdate().get().version());
            assertEquals(validHex.toLowerCase(Locale.ROOT), service.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    @DisplayName("Valid hash-shaped digest in body binds target jar under Rule 2")
    void validHashShapedDigestInBodyBindsTargetJarUnderRule2() {
        String validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String bodyText = validHex + "  DiscordTowny-1.10.0.jar";

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "%s",
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar"
                    }
                  ]
                }
                """.formatted(bodyText);

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(),
                        new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, false),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                transport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        UpdateService.CheckResult result = service.checkForUpdate().join();
        assertEquals(UpdateService.CheckStatus.UPDATE_AVAILABLE, result.status(),
                "Valid 64-hex digest under Rule 2 must bind target jar and succeed");
        assertFalse(service.isLastCheckFailed());
        assertTrue(service.getAvailableUpdate().isPresent());
        assertEquals("1.10.0", service.getAvailableUpdate().get().version());
        assertEquals(validHex.toLowerCase(Locale.ROOT), service.getAvailableUpdate().get().sha256().toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("F2: stagedUpdatePending is seeded from disk on startup and reflected in notifyAdminOnJoin")
    void stagedUpdatePending_seededFromDiskAtStartupAndReflectedInNotifyAdminOnJoin(@TempDir Path tempDir) throws Exception {
        Path updateFolder = tempDir.resolve("update");
        Path activeJar = tempDir.resolve("active.jar");
        Files.createDirectories(updateFolder);
        Files.writeString(activeJar, "CURRENT");

        // Service 1: empty update folder -> staged state is false
        DefaultUpdateService service1 = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                Logger.getLogger("test"),
                eventItem -> {}
        );
        List<String> messages1 = new ArrayList<>();
        service1.notifyAdminOnJoin(messages1::add);
        assertTrue(messages1.isEmpty(), "Initially no notice when no staged jar exists");
        assertFalse(service1.isUpdatePending());

        // Now create a staged jar on disk before Service 2 starts
        Path stagedJar = updateFolder.resolve("DiscordTowny.jar");
        Files.writeString(stagedJar, "STAGED_JAR_CONTENT");

        // Service 2: seeded at startup off server thread
        DefaultUpdateService service2 = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                Logger.getLogger("test"),
                eventItem -> {}
        );
        List<String> messages2 = new ArrayList<>();
        service2.notifyAdminOnJoin(messages2::add);
        assertEquals(1, messages2.size(), "Staged jar on disk seeds stagedUpdatePending to true during initialization");
        assertTrue(messages2.get(0).toLowerCase(Locale.ROOT).contains("downloaded")
                || messages2.get(0).toLowerCase(Locale.ROOT).contains("descargada"));

        // Calling isUpdatePending still checks disk and keeps cache consistent
        assertTrue(service2.isUpdatePending());
    }

    @Test
    @DisplayName("F2: notifyAdminOnJoin consumes in-memory staged state and does not call isUpdatePending")
    void notifyAdminOnJoin_consumesCachedStagedStateWithoutDiskAccess(@TempDir Path tempDir) throws Exception {
        Path updateFolder = tempDir.resolve("update");
        Path activeJar = tempDir.resolve("active.jar");
        Files.createDirectories(updateFolder);
        Files.writeString(activeJar, "CURRENT");

        // Write a real staged jar so constructor seeds stagedUpdatePending to true
        Path stagedJar = updateFolder.resolve("DiscordTowny.jar");
        Files.writeString(stagedJar, "STAGED_JAR_CONTENT");

        DefaultUpdateService service = new DefaultUpdateService(
                "1.0.0",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                Logger.getLogger("test"),
                eventItem -> {}
        );

        // Delete the staged jar from disk before calling notifyAdminOnJoin.
        // If notifyAdminOnJoin checked disk or called isUpdatePending(), it would find nothing.
        // Reading strictly from cached in-memory state means the notice is still delivered.
        Files.delete(stagedJar);
        assertFalse(Files.exists(stagedJar), "Staged jar must no longer exist on disk");

        List<String> messagesSent = new ArrayList<>();
        assertDoesNotThrow(() -> service.notifyAdminOnJoin(messagesSent::add));
        assertEquals(1, messagesSent.size(), "Downloaded update notice delivered to consumer from cached in-memory state");
        assertTrue(messagesSent.get(0).toLowerCase(Locale.ROOT).contains("downloaded")
                || messagesSent.get(0).toLowerCase(Locale.ROOT).contains("descargada"));

        // Now isUpdatePending is called explicitly; it probes disk, sees jar is gone, and syncs cache
        assertFalse(service.isUpdatePending(), "isUpdatePending probes disk and observes jar was deleted");

        // Subsequent notifyAdminOnJoin reflects the synced cache
        List<String> subsequentMessages = new ArrayList<>();
        service.notifyAdminOnJoin(subsequentMessages::add);
        assertTrue(subsequentMessages.isEmpty(), "After isUpdatePending syncs cache to false, no notice is sent");
    }
}
