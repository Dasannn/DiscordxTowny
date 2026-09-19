package com.discordtowny.update;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.model.AuditEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

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
    @DisplayName("A response larger than the cap is abandoned while downloading")
    void responseLargerThanCapIsAbandonedWhileDownloading() throws IOException {
        long capBytes = 1024; // 1 KB cap for test
        byte[] oversizedData = new byte[2048]; // 2 KB payload
        Arrays.fill(oversizedData, (byte) 0x41);

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(oversizedData));

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

        UpdateService.Release release = new UpdateService.Release(
                "1.10.0",
                "https://example.com/huge.jar",
                sha256Hex(oversizedData),
                "Notes"
        );

        UpdateService.DownloadResult result = service.download(release).join();

        // Claim 1: Result is TOO_LARGE
        assertEquals(UpdateService.DownloadResult.TOO_LARGE, result);

        // Claim 2: Temp file deleted immediately, no leftover in update folder
        if (Files.exists(updateFolder)) {
            try (Stream<Path> stream = Files.list(updateFolder)) {
                assertTrue(stream.toList().isEmpty(), "No partial or temporary file must remain");
            }
        }

        // Claim 3: Active jar untouched
        assertEquals("LIVE_JAR_CURRENT_VERSION_BYTES", Files.readString(activeJar));
        assertFalse(service.isUpdatePending());
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
                      "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://example.com/dl.jar"}]
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

        long warningCountAfterFirst = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING).count();
        assertEquals(1, warningCountAfterFirst, "Must log network failure exactly once");

        // Second check with network failure still active
        Optional<UpdateService.Release> secondCheck = service.checkForUpdate().join();
        assertTrue(secondCheck.isEmpty(), "Second check returns Optional.empty()");

        long warningCountAfterSecond = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING).count();
        assertEquals(1, warningCountAfterSecond, "Second check must not log again while network failure persists");

        // Now restore network
        throwNetworkError.set(false);
        Optional<UpdateService.Release> thirdCheck = service.checkForUpdate().join();
        assertTrue(thirdCheck.isPresent(), "Check succeeds once network is restored");

        // Now simulate network failure dropping again
        throwNetworkError.set(true);
        Optional<UpdateService.Release> fourthCheck = service.checkForUpdate().join();
        assertTrue(fourthCheck.isEmpty());

        long warningCountAfterFourth = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING).count();
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
                  "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://example.com/1.10.0.jar"}]
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
                  "assets": [{"name": "DiscordTowny-1.9.0.jar", "browser_download_url": "https://example.com/1.9.0.jar"}]
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
    @DisplayName("Successful download verifies SHA-256 and atomically places jar into update folder")
    void successfulDownloadVerifiesSha256AndMovesToUpdateFolder() throws IOException {
        byte[] payload = "NEW_VERSION_1_10_0_JAR_CONTENT".getBytes(StandardCharsets.UTF_8);
        String correctSha256 = sha256Hex(payload);

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
                "https://example.com/dl.jar",
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
    }

    @Test
    @DisplayName("Notifications: console on startup, log channel once per version, and once on download")
    void notificationsConsoleOnStartupAndLogChannelOncePerVersion() {
        byte[] payload = "NEW_JAR_CONTENT".getBytes(StandardCharsets.UTF_8);
        String correctSha256 = sha256Hex(payload);

        String releaseJson = """
                {
                  "tag_name": "v1.10.0",
                  "body": "SHA-256: %s",
                  "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://example.com/dl.jar"}]
                }
                """.formatted(correctSha256);

        HttpTransport transport = (uri, headers, timeout) -> {
            if (uri.toString().contains("releases/latest")) {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            } else {
                return new HttpTransport.HttpResponse(200, Map.of(), new ByteArrayInputStream(payload));
            }
        };

        DefaultUpdateService service = new DefaultUpdateService(
                "0.1.0",
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

        // Wait up to 2 seconds for background auto-download completion
        for (int i = 0; i < 100 && !service.isUpdatePending(); i++) {
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
                  "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://example.com/dl.jar"}]
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
                          "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://example.com/dl.jar"}]
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
                          "assets": [{"name": "DiscordTowny-1.10.0.jar", "browser_download_url": "https://example.com/dl.jar"}]
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
    @DisplayName("Content-Length header exceeding cap aborts download upfront")
    void contentLengthExceedingCapAbortsUpfront() throws IOException {
        long capBytes = 1024;
        byte[] payload = new byte[512];

        HttpTransport transport = (uri, headers, timeout) ->
                new HttpTransport.HttpResponse(200, Map.of("Content-Length", "2048"), new ByteArrayInputStream(payload));

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

        UpdateService.Release release = new UpdateService.Release("1.10.0", "https://example.com/dl.jar", "anyhash", "Notes");
        UpdateService.DownloadResult result = service.download(release).join();

        assertEquals(UpdateService.DownloadResult.TOO_LARGE, result);
        if (Files.exists(updateFolder)) {
            try (Stream<Path> stream = Files.list(updateFolder)) {
                assertTrue(stream.toList().isEmpty());
            }
        }
    }

    @Test
    @DisplayName("HTTP 404 or 500 during download returns NETWORK_ERROR")
    void httpErrorDuringDownloadReturnsNetworkError() throws IOException {
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

        UpdateService.Release release = new UpdateService.Release("1.10.0", "https://example.com/dl.jar", "somehash", "Notes");
        UpdateService.DownloadResult result = service.download(release).join();

        assertEquals(UpdateService.DownloadResult.NETWORK_ERROR, result);
        if (Files.exists(updateFolder)) {
            try (Stream<Path> stream = Files.list(updateFolder)) {
                assertTrue(stream.toList().isEmpty());
            }
        }
    }

    @Test
    @DisplayName("Repository URL constant in code cannot be changed via configuration")
    void repositoryUrlIsConstantInCode() {
        assertEquals("https://api.github.com/repos/Dasannn/DiscordxTowny/releases/latest", DefaultUpdateService.GITHUB_RELEASES_API);
    }
}
