package com.discordtowny.update;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.config.YamlMessages;
import com.discordtowny.minecraft.EnglishMessages;
import com.discordtowny.model.AuditEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        Optional<UpdateService.Release> firstCheck = service.checkForUpdate().join();
        assertTrue(firstCheck.isEmpty(), "Normal operation under network failure: returns Optional.empty()");

        long warningCountAfterFirst = countOutageWarnings();
        assertEquals(1, warningCountAfterFirst, "Must log network failure exactly once");

        // Second check with network failure still active
        Optional<UpdateService.Release> secondCheck = service.checkForUpdate().join();
        assertTrue(secondCheck.isEmpty(), "Second check returns Optional.empty()");

        long warningCountAfterSecond = countOutageWarnings();
        assertEquals(1, warningCountAfterSecond, "Second check must not log again while network failure persists");

        // Now restore network
        throwNetworkError.set(false);
        Optional<UpdateService.Release> thirdCheck = service.checkForUpdate().join();
        assertTrue(thirdCheck.isPresent(), "Check succeeds once network is restored");

        // Now simulate network failure dropping again
        throwNetworkError.set(true);
        Optional<UpdateService.Release> fourthCheck = service.checkForUpdate().join();
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

        Optional<UpdateService.Release> updateOpt = serviceAgainst190.checkForUpdate().join();
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

        Optional<UpdateService.Release> equalOpt = serviceEqual.checkForUpdate().join();
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

        Optional<UpdateService.Release> olderOpt = serviceOlder.checkForUpdate().join();
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
                Optional<UpdateService.Release> result = service.checkForUpdate().join();
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
        Optional<UpdateService.Release> release = service.checkForUpdate().join();
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

        Optional<UpdateService.Release> first = service.checkForUpdate().join();
        assertTrue(first.isPresent());

        // Second check hits 403 rate limit -> uses cached release
        Optional<UpdateService.Release> second = service.checkForUpdate().join();
        assertTrue(second.isPresent(), "Cached release should be returned when rate-limited");
        assertEquals("1.10.0", second.get().version());

        // Third check: because reset time is in the future, transport is not even queried
        Optional<UpdateService.Release> third = service.checkForUpdate().join();
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

        Optional<UpdateService.Release> first = service.checkForUpdate().join();
        assertTrue(first.isPresent());

        Optional<UpdateService.Release> second = service.checkForUpdate().join();
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
        Optional<UpdateService.Release> untrustedCheckResult = serviceWithUntrustedAsset.checkForUpdate().join();
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

        Optional<UpdateService.Release> releaseOpt = service.checkForUpdate().join();
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

        Optional<UpdateService.Release> releaseOpt = service.checkForUpdate().join();
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

        Optional<UpdateService.Release> conflictingResult = serviceConflictingAssets.checkForUpdate().join();
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

        Optional<UpdateService.Release> conflictingBodyResult = serviceConflictingBody.checkForUpdate().join();
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

        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<InputStream> mockResponse = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(302);
        HttpHeaders headersEvil = HttpHeaders.of(
                Map.of("Location", List.of("https://evil.com/malicious.jar")),
                (k, v) -> true
        );
        when(mockResponse.headers()).thenReturn(headersEvil);
        try {
            when(mockClient.<InputStream>send(any(), any())).thenReturn(mockResponse);
        } catch (IOException | InterruptedException ignored) {}

        JdkHttpTransport redirectTransport = new JdkHttpTransport(mockClient);
        URI officialUri = URI.create("https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar");

        IOException redirEx = assertThrows(IOException.class, () ->
                redirectTransport.executeGet(officialUri, Map.of(), Duration.ofSeconds(1)));
        assertTrue(redirEx.getMessage().contains("Untrusted destination rejected by official source policy"),
                "Redirect to untrusted destination must be refused: " + redirEx.getMessage());

        // 4. Follow redirect chain: 302 to raw.githubusercontent.com without official provenance
        HttpHeaders headersRaw = HttpHeaders.of(
                Map.of("Location", List.of("https://raw.githubusercontent.com/Attacker/Repo/main/payload.jar")),
                (k, v) -> true
        );
        when(mockResponse.headers()).thenReturn(headersRaw);

        IOException redirRawEx = assertThrows(IOException.class, () ->
                redirectTransport.executeGet(officialUri, Map.of(), Duration.ofSeconds(1)));
        assertTrue(redirRawEx.getMessage().contains("Untrusted destination rejected by official source policy"),
                "Redirect to raw.githubusercontent without provenance must be refused: " + redirRawEx.getMessage());
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

        Optional<UpdateService.Release> releaseOpt = service.checkForUpdate().join();
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

        Optional<UpdateService.Release> releaseOpt = service.checkForUpdate().join();
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
        Optional<UpdateService.Release> first = service.checkForUpdate().join();
        assertTrue(first.isEmpty(), "Truncated JSON should fail parsing");

        // Check 2: succeeds and commits ETag
        Optional<UpdateService.Release> second = service.checkForUpdate().join();
        assertTrue(second.isPresent());
        assertEquals("1.10.0", second.get().version());

        // Check 3: 304 returns cached release
        Optional<UpdateService.Release> third = service.checkForUpdate().join();
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

        Optional<UpdateService.Release> releaseOpt = service.checkForUpdate().join();
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
            Optional<UpdateService.Release> res = service.checkForUpdate().join();
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

        Optional<UpdateService.Release> first = service.checkForUpdate().join();
        assertTrue(first.isEmpty());
        assertEquals(1, transportCalls.get());

        // Second check within the 120-second Retry-After window must NOT call transport
        Optional<UpdateService.Release> second = service.checkForUpdate().join();
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

        // Direct initial requests to delivery hosts without provenance are strictly rejected
        assertFalse(UpdateSourcePolicy.isAllowedInitialUri(liveRedirectTarget));
        assertFalse(UpdateSourcePolicy.isAllowedInitialUri(objectsRedirectTarget));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateInitialUri(liveRedirectTarget));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateInitialUri(objectsRedirectTarget));

        // Untrusted hosts and suffix masquerading are strictly rejected
        URI evilHost = URI.create("https://evil-githubusercontent.com/github-production-release-asset/1376593898/file.jar");
        URI suffixAttacker = URI.create("https://release-assets.githubusercontent.com.attacker.net/file.jar");
        URI rawHost = URI.create("https://githubusercontent.com/Dasannn/DiscordxTowny/file.jar");
        URI httpHop = URI.create("http://release-assets.githubusercontent.com/github-production-release-asset/1376593898/file.jar");
        URI userHop = URI.create("https://user:pass@release-assets.githubusercontent.com/file.jar");

        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(evilHost));
        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(suffixAttacker));
        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(rawHost));
        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(httpHop));
        assertFalse(UpdateSourcePolicy.isAllowedRedirectDestination(userHop));

        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(evilHost));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(suffixAttacker));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(rawHost));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(httpHop));
        assertThrows(IOException.class, () -> UpdateSourcePolicy.validateRedirectDestination(userHop));
    }

    @Test
    @DisplayName("JdkHttpTransport follows redirect to release-assets.githubusercontent.com and objects.githubusercontent.com")
    void jdkHttpTransportFollowsRedirectToDeliveryHosts() throws Exception {
        byte[] payload = "DELIVERED_JAR_BYTES".getBytes(StandardCharsets.UTF_8);

        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);

        URI officialUri = URI.create("https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar");
        URI redirectTarget = URI.create("https://release-assets.githubusercontent.com/github-production-release-asset/1376593898/cb094ea3?sp=r&sig=abc");

        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<InputStream> redirectResponse = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
        when(redirectResponse.statusCode()).thenReturn(302);
        when(redirectResponse.uri()).thenReturn(officialUri);
        HttpHeaders redirectHeaders = HttpHeaders.of(
                Map.of("Location", List.of(redirectTarget.toString())),
                (k, v) -> true
        );
        when(redirectResponse.headers()).thenReturn(redirectHeaders);
        when(redirectResponse.body()).thenReturn(new ByteArrayInputStream(new byte[0]));

        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<InputStream> okResponse = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
        when(okResponse.statusCode()).thenReturn(200);
        when(okResponse.uri()).thenReturn(redirectTarget);
        when(okResponse.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
        when(okResponse.body()).thenReturn(new ByteArrayInputStream(payload));

        when(mockClient.<InputStream>send(any(), any()))
                .thenReturn(redirectResponse)
                .thenReturn(okResponse);

        JdkHttpTransport transport = new JdkHttpTransport(mockClient);
        HttpTransport.HttpResponse res = transport.executeGet(officialUri, Map.of(), Duration.ofSeconds(5));

        assertEquals(200, res.statusCode());
        assertArrayEquals(payload, res.body().readAllBytes());
    }

    @Test
    @DisplayName("JdkHttpTransport limits redirect chain length to 5 hops")
    void jdkHttpTransportLimitsRedirectChain() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);

        URI officialUri = URI.create("https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar");
        URI hopTarget = URI.create("https://objects.githubusercontent.com/hop");

        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<InputStream> hopResponse = (java.net.http.HttpResponse<InputStream>) mock(java.net.http.HttpResponse.class);
        when(hopResponse.statusCode()).thenReturn(302);
        when(hopResponse.uri()).thenReturn(hopTarget);
        when(hopResponse.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of(hopTarget.toString())), (k, v) -> true));
        when(hopResponse.body()).thenReturn(new ByteArrayInputStream(new byte[0]));

        when(mockClient.<InputStream>send(any(), any())).thenReturn(hopResponse);

        JdkHttpTransport transport = new JdkHttpTransport(mockClient);
        IOException ex = assertThrows(IOException.class, () ->
                transport.executeGet(officialUri, Map.of(), Duration.ofSeconds(5)));
        assertTrue(ex.getMessage().contains("Too many redirects"), "Must reject redirect loop exceeding max hops: " + ex.getMessage());
    }

    @Test
    @DisplayName("Full download succeeds through redirect to release-assets.githubusercontent.com with valid checksum")
    void fullDownloadSucceedsThroughReleaseAssetsRedirectWithValidChecksum() throws IOException {
        byte[] jarBytes = "DISCORD_TOWNY_1_0_0_RELEASE_JAR".getBytes(StandardCharsets.UTF_8);
        String expectedChecksum = sha256Hex(jarBytes);

        String releaseJson = """
                {
                  "tag_name": "v1.0.0",
                  "body": "SHA-256: %s\\nFix delivery host",
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
                """.formatted(expectedChecksum);

        String sha256AssetContent = expectedChecksum + "  DiscordTowny-1.0.0.jar\n";

        HttpTransport transport = (uri, headers, timeout) -> {
            String url = uri.toString();
            if (url.contains("releases/latest")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            } else if (url.contains(".sha256")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(sha256AssetContent.getBytes(StandardCharsets.UTF_8)));
            } else if (url.contains("DiscordTowny-1.0.0.jar")) {
                return new HttpTransport.HttpResponse(200, Map.of("Content-Length", String.valueOf(jarBytes.length)), new ByteArrayInputStream(jarBytes));
            }
            throw new IOException("Unexpected URI: " + uri);
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

        Optional<UpdateService.Release> releaseOpt = service.checkForUpdate().join();
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
    }

    @Test
    @DisplayName("Checksum mismatch after release-assets.githubusercontent.com delivery discards download and leaves no remnants")
    void checksumMismatchAfterDeliveryDiscardsDownloadWithoutRemnants() throws IOException {
        byte[] tamperedJar = "TAMPERED_BYTES_FROM_CDN".getBytes(StandardCharsets.UTF_8);
        String legitimateSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of("Content-Length", String.valueOf(tamperedJar.length)), new ByteArrayInputStream(tamperedJar));

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
                "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar",
                legitimateSha,
                "Release notes"
        );

        UpdateService.DownloadResult result = service.download(release).join();
        assertEquals(UpdateService.DownloadResult.CHECKSUM_MISMATCH, result);

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
        HttpTransport failingTransport = (uri, headers, timeout) -> {
            throw new IOException("Untrusted destination rejected by official source policy: https://release-assets.githubusercontent.com/...");
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0-SNAPSHOT",
                new PluginConfig.Updates(true, Duration.ofHours(12), false, true),
                updateFolder,
                activeJar,
                "DiscordTowny.jar",
                failingTransport,
                testLogger,
                auditEvents::add,
                ForkJoinPool.commonPool(),
                null,
                false,
                50 * 1024 * 1024L,
                Duration.ofSeconds(5)
        );

        assertEquals(UpdateService.CheckStatus.NOT_CHECKED, service.checkStatus());
        assertFalse(service.isLastCheckFailed());

        // First check: fails
        Optional<UpdateService.Release> res1 = service.checkForUpdate().join();
        assertTrue(res1.isEmpty());
        assertTrue(service.isLastCheckFailed(), "isLastCheckFailed must be true after network failure");
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, service.checkStatus());
        assertTrue(service.getLastCheckError().isPresent());
        assertTrue(service.getLastCheckError().get().contains("could not reach GitHub"));

        long warningsCount1 = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING).count();
        assertEquals(1, warningsCount1, "First network failure must log a warning");

        // Second check: fails again -> warning must be silenced
        Optional<UpdateService.Release> res2 = service.checkForUpdate().join();
        assertTrue(res2.isEmpty());
        assertTrue(service.isLastCheckFailed());
        long warningsCount2 = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING).count();
        assertEquals(1, warningsCount2, "Consecutive network failure warning must be silenced");

        // Check rendered status messages in English
        Messages enMessages = EnglishMessages.bundled();
        List<String> enLines = service.renderStatusMessages(enMessages);
        assertFalse(enLines.isEmpty());
        boolean enHasFailed = enLines.stream().anyMatch(l -> l.contains("Could not check for updates"));
        boolean enHasUpToDate = enLines.stream().anyMatch(l -> l.contains("You are on the latest version"));
        assertTrue(enHasFailed, "Status must render 'Could not check for updates', got: " + enLines);
        assertFalse(enHasUpToDate, "Status must NEVER render 'You are on the latest version' when check failed!");

        // Check rendered status messages in Spanish
        Messages esMessages = loadSpanishMessages();
        List<String> esLines = service.renderStatusMessages(esMessages);
        assertFalse(esLines.isEmpty());
        boolean esHasFailed = esLines.stream().anyMatch(l -> l.contains("No se pudo comprobar si hay actualizaciones"));
        boolean esHasUpToDate = esLines.stream().anyMatch(l -> l.contains("Estás en la última versión"));
        assertTrue(esHasFailed, "Status must render 'No se pudo comprobar si hay actualizaciones', got: " + esLines);
        assertFalse(esHasUpToDate, "Status must NEVER render 'Estás en la última versión' when check failed!");
    }

    @Test
    @DisplayName("Recovery after failed check clears error state and renders up-to-date")
    void recoveryAfterFailedCheckClearsErrorState() {
        AtomicBoolean fail = new AtomicBoolean(true);
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

        // Fail first
        service.checkForUpdate().join();
        assertTrue(service.isLastCheckFailed());
        assertEquals(UpdateService.CheckStatus.CHECK_FAILED, service.checkStatus());

        // Recover
        fail.set(false);
        service.checkForUpdate().join();
        assertFalse(service.isLastCheckFailed(), "isLastCheckFailed must reset to false after successful check");
        assertTrue(service.getLastCheckError().isEmpty());
        assertEquals(UpdateService.CheckStatus.UP_TO_DATE, service.checkStatus());

        List<String> enLines = service.renderStatusMessages(EnglishMessages.bundled());
        assertTrue(enLines.stream().anyMatch(l -> l.contains("You are on the latest version")));
    }
}
