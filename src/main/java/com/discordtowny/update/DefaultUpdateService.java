package com.discordtowny.update;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.model.AuditEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link UpdateService} checking against GitHub releases.
 *
 * <p>Principles enforced:
 * <ul>
 *   <li><b>Fixed download source:</b> The repository URL is a code constant, never configurable.</li>
 *   <li><b>P4: Main thread is sacred:</b> Checks and downloads run entirely off the main thread.</li>
 *   <li><b>No corruption:</b> The jar in use is never touched; updates wait in {@code update/}.</li>
 *   <li><b>Zero remnants:</b> A mismatched checksum leaves nothing behind in temp or update folders.</li>
 *   <li><b>Mandatory checksum:</b> Releases with no published SHA-256 are refused.</li>
 *   <li><b>Streaming size cap:</b> Enforced while streaming bytes to prevent disk exhaustion.</li>
 *   <li><b>Fail visibly and quietly:</b> Network outages log once and never spam the console.</li>
 * </ul>
 */
public final class DefaultUpdateService implements UpdateService, AutoCloseable {

    /** Official repository owner and repository name. */
    public static final String REPOSITORY_OWNER = "Dasannn";
    public static final String REPOSITORY_NAME = "DiscordxTowny";

    /** Fixed GitHub releases API endpoint. Not configurable. */
    public static final String GITHUB_RELEASES_API = "https://api.github.com/repos/" + REPOSITORY_OWNER + "/" + REPOSITORY_NAME + "/releases/latest";

    /** Maximum download size cap (50 MB). Enforced while streaming. */
    public static final long DEFAULT_MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024L;

    /** Default request timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private static final Pattern SHA256_LABEL_PATTERN = Pattern.compile("(?i)(?:sha-?256(?:sum)?[:=\\s]+)([a-f0-9]{64})");
    private static final Pattern STANDALONE_HEX_PATTERN = Pattern.compile("(?i)\\b([a-f0-9]{64})\\b");

    private final String currentVersion;
    private final PluginConfig.Updates config;
    private final Path updateFolder;
    private final Path activeJarPath;
    private final String targetJarName;
    private final HttpTransport httpTransport;
    private final Logger logger;
    private final Consumer<AuditEvent> auditLogger;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final long maxDownloadBytes;
    private final Duration requestTimeout;

    // Rate limiting & caching
    private final AtomicReference<Instant> rateLimitResetTime = new AtomicReference<>(null);
    private final AtomicReference<String> cachedEtag = new AtomicReference<>(null);
    private final AtomicReference<Release> cachedRelease = new AtomicReference<>(null);

    // Network error deduplication (log once until restored)
    private final AtomicBoolean networkErrorLogged = new AtomicBoolean(false);

    // Notification deduplication (once per version)
    private final Set<String> consoleNotifiedVersions = ConcurrentHashMap.newKeySet();
    private final Set<String> logChannelNotifiedVersions = ConcurrentHashMap.newKeySet();
    private final Set<String> downloadNotifiedVersions = ConcurrentHashMap.newKeySet();

    private volatile Release latestAvailableUpdate = null;
    private ScheduledFuture<?> periodicTask = null;

    public DefaultUpdateService(
            String currentVersion,
            PluginConfig.Updates config,
            Path updateFolder,
            Path activeJarPath,
            Logger logger,
            Consumer<AuditEvent> auditLogger) {
        this(
                currentVersion,
                config,
                updateFolder,
                activeJarPath,
                "DiscordTowny.jar",
                new JdkHttpTransport(),
                logger,
                auditLogger,
                ForkJoinPool.commonPool(),
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "dt-update-scheduler");
                    t.setDaemon(true);
                    return t;
                }),
                true,
                DEFAULT_MAX_DOWNLOAD_BYTES,
                DEFAULT_TIMEOUT
        );
    }

    public DefaultUpdateService(
            String currentVersion,
            PluginConfig.Updates config,
            Path updateFolder,
            Path activeJarPath,
            String targetJarName,
            HttpTransport httpTransport,
            Logger logger,
            Consumer<AuditEvent> auditLogger,
            Executor executor,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler,
            long maxDownloadBytes,
            Duration requestTimeout) {
        this.currentVersion = currentVersion != null ? currentVersion.trim() : "0.0.0";
        this.config = config != null ? config : new PluginConfig.Updates(true, Duration.ofHours(12), true, true);
        this.updateFolder = updateFolder != null ? updateFolder : Path.of("update");
        this.activeJarPath = activeJarPath;
        this.targetJarName = targetJarName != null ? targetJarName : "DiscordTowny.jar";
        this.httpTransport = httpTransport != null ? httpTransport : new JdkHttpTransport();
        this.logger = logger != null ? logger : Logger.getLogger("DiscordTowny");
        this.auditLogger = auditLogger;
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
        this.maxDownloadBytes = maxDownloadBytes > 0 ? maxDownloadBytes : DEFAULT_MAX_DOWNLOAD_BYTES;
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_TIMEOUT;
    }

    /**
     * Starts background periodic checks if {@code check-enabled} is true.
     */
    public synchronized void start() {
        if (!config.checkEnabled() || scheduler == null) {
            return;
        }

        long initialDelaySeconds = 5;
        long periodSeconds = Math.max(60, config.checkInterval().toSeconds());

        periodicTask = scheduler.scheduleAtFixedRate(
                () -> checkForUpdate().whenComplete((opt, err) -> {
                    if (err != null) {
                        logger.log(Level.FINE, "Update check failed", err);
                    }
                }),
                initialDelaySeconds,
                periodSeconds,
                TimeUnit.SECONDS
        );
    }

    /**
     * Stops background periodic checks and cleans up resources.
     */
    public synchronized void stop() {
        if (periodicTask != null) {
            periodicTask.cancel(true);
            periodicTask = null;
        }
        if (ownsScheduler && scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public String currentVersion() {
        return currentVersion;
    }

    @Override
    public boolean isUpdatePending() {
        try {
            Path target = updateFolder.resolve(targetJarName);
            return Files.isRegularFile(target) && Files.size(target) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    public Optional<Release> getAvailableUpdate() {
        return Optional.ofNullable(latestAvailableUpdate);
    }

    public boolean shouldNotifyAdminsOnJoin() {
        return config.notifyAdminsOnJoin();
    }

    /**
     * Notifies an admin player on join if updates are available and admin notification is enabled.
     *
     * @param messageSender consumer to send formatted message to the player
     */
    public void notifyAdminOnJoin(Consumer<String> messageSender) {
        if (!config.notifyAdminsOnJoin() || messageSender == null) {
            return;
        }
        if (isUpdatePending()) {
            Release available = latestAvailableUpdate;
            String ver = available != null ? available.version() : "new";
            messageSender.accept("Version " + ver + " is downloaded. Restart the server to apply it.");
            return;
        }
        Release available = latestAvailableUpdate;
        if (available != null) {
            messageSender.accept("There is a new version of DiscordTowny: " + available.version() + " (you have " + currentVersion + ").");
        }
    }

    @Override
    public CompletableFuture<Optional<Release>> checkForUpdate() {
        return CompletableFuture.supplyAsync(this::doCheckForUpdate, executor);
    }

    private Optional<Release> doCheckForUpdate() {
        // Respect rate limits: if rate limit was exceeded, do not query until reset
        Instant resetTime = rateLimitResetTime.get();
        if (resetTime != null && Instant.now().isBefore(resetTime)) {
            return Optional.ofNullable(cachedRelease.get());
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "DiscordTowny-Updater");
        headers.put("Accept", "application/vnd.github+json");

        String etag = cachedEtag.get();
        if (etag != null && !etag.isBlank()) {
            headers.put("If-None-Match", etag);
        }

        try (HttpTransport.HttpResponse response = httpTransport.executeGet(URI.create(GITHUB_RELEASES_API), headers, requestTimeout)) {
            // Restore network logging flag on successful response
            networkErrorLogged.set(false);

            int status = response.statusCode();

            // Handle GitHub Rate Limiting headers
            updateRateLimit(response);

            // 304 Not Modified: release has not changed, use cache
            if (status == 304) {
                return Optional.ofNullable(cachedRelease.get());
            }

            // 403 Forbidden or 429 Too Many Requests (Rate limited or access issue)
            if (status == 403 || status == 429) {
                return Optional.ofNullable(cachedRelease.get());
            }

            if (status != 200) {
                logger.log(Level.FINE, "GitHub releases API returned unexpected status {0}", status);
                return Optional.empty();
            }

            // Read ETag for future caching
            String newEtag = response.getHeader("ETag");
            if (newEtag != null) {
                cachedEtag.set(newEtag);
            }

            // Read JSON body (capped to 1 MB for metadata)
            String json = readStringCapped(response.body(), 1024 * 1024);
            Optional<Release> releaseOpt = parseRelease(json);

            if (releaseOpt.isEmpty()) {
                return Optional.empty();
            }

            Release release = releaseOpt.get();
            SemanticVersion latestSemVer = SemanticVersion.parse(release.version());
            SemanticVersion currentSemVer = SemanticVersion.parse(currentVersion);

            if (!latestSemVer.isNewerThan(currentSemVer)) {
                // Not newer: running current or newer version
                cachedRelease.set(null);
                latestAvailableUpdate = null;
                return Optional.empty();
            }

            cachedRelease.set(release);
            latestAvailableUpdate = release;

            // Notice in console on startup (once per version)
            if (consoleNotifiedVersions.add(release.version())) {
                logger.info("There is a new version of DiscordTowny: " + release.version() + " (you have " + currentVersion + ").");
            }

            // Notice in Discord log channel (once per version)
            if (logChannelNotifiedVersions.add(release.version()) && auditLogger != null) {
                auditLogger.accept(new AuditEvent(
                        Instant.now(),
                        AuditEvent.Severity.INFO,
                        "Updater",
                        "update_available",
                        release.version(),
                        true,
                        Optional.of("New version " + release.version() + " is available (current: " + currentVersion + ").")
                ));
            }

            // Auto-download if enabled and not already pending
            if (config.autoDownload() && !isUpdatePending()) {
                download(release).whenComplete((res, err) -> {
                    if (err != null) {
                        logger.log(Level.WARNING, "Auto-download failed: " + err.getMessage());
                    }
                });
            }

            return Optional.of(release);
        } catch (IOException e) {
            handleNetworkFailure(e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleNetworkFailure(new IOException("Update check interrupted", e));
            return Optional.empty();
        } catch (Exception e) {
            // Malformed JSON or parsing errors do not throw out of service
            logger.log(Level.FINE, "Failed to parse update release", e);
            return Optional.empty();
        }
    }

    private void updateRateLimit(HttpTransport.HttpResponse response) {
        String remaining = response.getHeader("X-RateLimit-Remaining");
        String reset = response.getHeader("X-RateLimit-Reset");
        if ("0".equals(remaining) && reset != null) {
            try {
                long epochSec = Long.parseLong(reset.trim());
                rateLimitResetTime.set(Instant.ofEpochSecond(epochSec));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void handleNetworkFailure(IOException e) {
        if (networkErrorLogged.compareAndSet(false, true)) {
            logger.log(Level.WARNING, "Unable to check for updates: could not reach GitHub ({0}). Further network errors will be silenced until connectivity is restored.", e.getMessage());
        }
    }

    private Optional<Release> parseRelease(String json) {
        try {
            SimpleJson.JsonObject obj = SimpleJson.parseObject(json);
            String tag = obj.getString("tag_name");
            if (tag == null || tag.isBlank()) {
                tag = obj.getString("name");
            }
            if (tag == null || tag.isBlank()) {
                return Optional.empty();
            }

            String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1).trim() : tag.trim();
            String notes = obj.getString("body");
            if (notes == null) {
                notes = "";
            }

            SimpleJson.JsonArray assets = obj.getArray("assets");
            if (assets == null || assets.isEmpty()) {
                return Optional.empty();
            }

            String jarDownloadUrl = null;
            String checksumAssetUrl = null;

            for (SimpleJson.JsonValue val : assets) {
                if (val instanceof SimpleJson.JsonObject asset) {
                    String name = asset.getString("name");
                    String downloadUrl = asset.getString("browser_download_url");
                    if (name == null || downloadUrl == null) {
                        continue;
                    }

                    String lowerName = name.toLowerCase(Locale.ROOT);
                    if (lowerName.endsWith(".jar") && !lowerName.endsWith("-sources.jar") && !lowerName.endsWith("-javadoc.jar")) {
                        jarDownloadUrl = downloadUrl;
                    } else if (lowerName.endsWith(".sha256") || lowerName.endsWith(".sha256.txt") || lowerName.equals("checksums.txt") || lowerName.equals("sha256sums")) {
                        checksumAssetUrl = downloadUrl;
                    }
                }
            }

            if (jarDownloadUrl == null) {
                return Optional.empty();
            }

            // Extract SHA-256: first from body, second from checksum asset
            String sha256 = extractSha256FromBody(notes);
            if (sha256 == null && checksumAssetUrl != null) {
                sha256 = fetchChecksumFromAsset(checksumAssetUrl);
            }

            return Optional.of(new Release(version, jarDownloadUrl, sha256, notes));
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to parse release JSON", e);
            return Optional.empty();
        }
    }

    private String extractSha256FromBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher labeled = SHA256_LABEL_PATTERN.matcher(body);
        if (labeled.find()) {
            return labeled.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private String fetchChecksumFromAsset(String assetUrl) {
        try (HttpTransport.HttpResponse response = httpTransport.executeGet(URI.create(assetUrl), Map.of("User-Agent", "DiscordTowny-Updater"), requestTimeout)) {
            if (response.statusCode() == 200) {
                String content = readStringCapped(response.body(), 8192);
                Matcher matcher = STANDALONE_HEX_PATTERN.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1).toLowerCase(Locale.ROOT);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public CompletableFuture<DownloadResult> download(Release release) {
        if (release == null || release.downloadUrl() == null || release.downloadUrl().isBlank()) {
            return CompletableFuture.completedFuture(DownloadResult.IO_ERROR);
        }

        // A release with no checksum is refused immediately
        if (release.sha256() == null || release.sha256().isBlank()) {
            logger.warning("Update download refused: release " + release.version() + " has no published SHA-256 checksum.");
            return CompletableFuture.completedFuture(DownloadResult.CHECKSUM_MISMATCH);
        }

        return CompletableFuture.supplyAsync(() -> performDownload(release), executor);
    }

    private DownloadResult performDownload(Release release) {
        Path tempFile = null;
        try {
            Files.createDirectories(updateFolder);
            tempFile = Files.createTempFile(updateFolder, "dt-update-", ".tmp");

            Map<String, String> headers = Map.of("User-Agent", "DiscordTowny-Updater");
            try (HttpTransport.HttpResponse response = httpTransport.executeGet(URI.create(release.downloadUrl()), headers, requestTimeout)) {
                if (response.statusCode() != 200) {
                    cleanupTemp(tempFile);
                    return DownloadResult.NETWORK_ERROR;
                }

                // Check Content-Length header upfront if present
                String clHeader = response.getHeader("Content-Length");
                if (clHeader != null) {
                    try {
                        long cl = Long.parseLong(clHeader.trim());
                        if (cl > maxDownloadBytes) {
                            cleanupTemp(tempFile);
                            return DownloadResult.TOO_LARGE;
                        }
                    } catch (NumberFormatException ignored) {}
                }

                // Stream body with streaming size cap & SHA-256 calculation
                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException e) {
                    cleanupTemp(tempFile);
                    return DownloadResult.IO_ERROR;
                }

                long totalBytes = 0;
                byte[] buffer = new byte[8192];
                try (InputStream in = response.body();
                     OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        totalBytes += read;
                        if (totalBytes > maxDownloadBytes) {
                            out.close();
                            cleanupTemp(tempFile);
                            return DownloadResult.TOO_LARGE;
                        }
                        digest.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                    }
                }

                // Verify SHA-256
                String actualSha256 = HexFormat.of().formatHex(digest.digest());
                String expectedSha256 = release.sha256().trim().toLowerCase(Locale.ROOT);

                if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                    cleanupTemp(tempFile);
                    logger.warning("Checksum mismatch for downloaded update! Expected: " + expectedSha256 + ", actual: " + actualSha256 + ". Download discarded.");
                    return DownloadResult.CHECKSUM_MISMATCH;
                }

                // Verified: move into server update folder. Active jar in use is never touched!
                Path destination = updateFolder.resolve(targetJarName);
                if (activeJarPath != null && activeJarPath.toAbsolutePath().equals(destination.toAbsolutePath())) {
                    cleanupTemp(tempFile);
                    logger.severe("Refusing to overwrite active jar directly!");
                    return DownloadResult.IO_ERROR;
                }

                try {
                    Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                tempFile = null; // Moved successfully

                logger.info("Version " + release.version() + " is downloaded. Restart the server to apply it.");

                // Log channel notification once on download completion
                if (downloadNotifiedVersions.add(release.version()) && auditLogger != null) {
                    auditLogger.accept(new AuditEvent(
                            Instant.now(),
                            AuditEvent.Severity.INFO,
                            "Updater",
                            "update_downloaded",
                            release.version(),
                            true,
                            Optional.of("Version " + release.version() + " is downloaded. Restart the server to apply it.")
                    ));
                }

                return DownloadResult.SUCCESS;
            }
        } catch (IOException e) {
            cleanupTemp(tempFile);
            return DownloadResult.IO_ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanupTemp(tempFile);
            return DownloadResult.NETWORK_ERROR;
        } catch (Exception e) {
            cleanupTemp(tempFile);
            return DownloadResult.NETWORK_ERROR;
        } finally {
            cleanupTemp(tempFile);
        }
    }

    private void cleanupTemp(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {}
        }
    }

    private static String readStringCapped(InputStream in, int maxBytes) throws IOException {
        if (in == null) {
            return "";
        }
        byte[] buffer = new byte[Math.min(8192, maxBytes)];
        int total = 0;
        StringBuilder sb = new StringBuilder();
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Response exceeded limit of " + maxBytes + " bytes");
            }
            sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
