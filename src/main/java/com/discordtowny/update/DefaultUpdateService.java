package com.discordtowny.update;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.minecraft.EnglishMessages;
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
import java.util.function.Supplier;
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

    private static final Pattern SHA256_LABEL_PATTERN = Pattern.compile("(?i)(?:sha-?256(?:sum)?[:=\\s]+)([a-f0-9]{64})(?![a-f0-9])");
    private static final Pattern STANDALONE_HEX_PATTERN = Pattern.compile("(?i)\\b([a-f0-9]{64})\\b(?![a-f0-9])");
    private static final Pattern STRICT_HEX_64 = Pattern.compile("^[a-fA-F0-9]{64}$");

    private static final ScheduledExecutorService TIMEOUT_WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dt-update-watchdog");
        t.setDaemon(true);
        return t;
    });

    private static boolean isValidSha256(String hex) {
        return hex != null && STRICT_HEX_64.matcher(hex.trim()).matches();
    }

    private sealed interface ChecksumOutcome {
        record Valid(String sha256) implements ChecksumOutcome {}
        record None() implements ChecksumOutcome {}
        record InvalidOrAmbiguous(String reason) implements ChecksumOutcome {}
    }

    private static final class ActiveTransfer {
        final Thread workerThread;
        final AtomicReference<AutoCloseable> resource = new AtomicReference<>(null);
        final AtomicReference<Path> tempFile = new AtomicReference<>(null);

        ActiveTransfer(Thread workerThread) {
            this.workerThread = workerThread;
        }

        void abort() {
            if (workerThread != null) {
                workerThread.interrupt();
            }
            AutoCloseable res = resource.getAndSet(null);
            if (res != null) {
                try {
                    res.close();
                } catch (Exception ignored) {}
            }
            Path tmp = tempFile.getAndSet(null);
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {}
            }
        }
    }

    private final String currentVersion;
    private final PluginConfig.Updates config;
    private final Path updateFolder;
    private final Path activeJarPath;
    private final String targetJarName;
    private final HttpTransport httpTransport;
    private final Logger logger;
    private final Consumer<AuditEvent> auditLogger;
    private final Supplier<Messages> messagesSupplier;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final long maxDownloadBytes;
    private final Duration requestTimeout;

    // Lifecycle & active I/O tracking
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();
    private final Set<ActiveTransfer> activeTransfers = ConcurrentHashMap.newKeySet();

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
                logger,
                auditLogger,
                EnglishMessages::bundled
        );
    }

    public DefaultUpdateService(
            String currentVersion,
            PluginConfig.Updates config,
            Path updateFolder,
            Path activeJarPath,
            Logger logger,
            Consumer<AuditEvent> auditLogger,
            Supplier<Messages> messagesSupplier) {
        this(
                currentVersion,
                config,
                updateFolder,
                activeJarPath,
                "DiscordTowny.jar",
                new JdkHttpTransport(),
                logger,
                auditLogger,
                messagesSupplier,
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
        this(
                currentVersion,
                config,
                updateFolder,
                activeJarPath,
                targetJarName,
                httpTransport,
                logger,
                auditLogger,
                EnglishMessages::bundled,
                executor,
                scheduler,
                ownsScheduler,
                maxDownloadBytes,
                requestTimeout
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
            Supplier<Messages> messagesSupplier,
            Executor executor,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler,
            long maxDownloadBytes,
            Duration requestTimeout) {
        this.currentVersion = currentVersion != null ? currentVersion.trim() : "0.0.0";
        this.config = config != null ? config : new PluginConfig.Updates(true, Duration.ofHours(12), true, true);
        this.updateFolder = updateFolder != null ? updateFolder.toAbsolutePath().normalize() : Path.of("update").toAbsolutePath().normalize();
        this.activeJarPath = activeJarPath != null ? activeJarPath.toAbsolutePath().normalize() : null;

        String target = targetJarName != null && !targetJarName.isBlank() ? targetJarName : "DiscordTowny.jar";
        Path targetPath = Path.of(target);
        if (targetPath.isAbsolute() || targetPath.getNameCount() != 1 || target.contains("/") || target.contains("\\") || "..".equals(target) || ".".equals(target)) {
            throw new IllegalArgumentException("targetJarName must be a simple filename within the update folder, got: " + targetJarName);
        }
        this.targetJarName = target;

        this.httpTransport = httpTransport != null ? httpTransport : new JdkHttpTransport();
        this.logger = logger != null ? logger : Logger.getLogger("DiscordTowny");
        this.auditLogger = auditLogger;
        this.messagesSupplier = messagesSupplier != null ? messagesSupplier : EnglishMessages::bundled;
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
        this.maxDownloadBytes = maxDownloadBytes > 0 ? maxDownloadBytes : DEFAULT_MAX_DOWNLOAD_BYTES;
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_TIMEOUT;
    }

    private boolean isDestinationActiveJar(Path destination) {
        Path normalizedFolder = updateFolder.toAbsolutePath().normalize();
        Path normalizedDest = destination.toAbsolutePath().normalize();

        // 1. Destination must be directly inside updateFolder
        if (!normalizedFolder.equals(normalizedDest.getParent())) {
            return true;
        }

        if (activeJarPath == null) {
            return false;
        }

        Path normalizedActive = activeJarPath.toAbsolutePath().normalize();

        // 2. Normalized path comparison
        if (normalizedActive.equals(normalizedDest)) {
            return true;
        }

        // 3. Same file check if both exist
        try {
            if (Files.exists(activeJarPath) && Files.exists(destination) && Files.isSameFile(activeJarPath, destination)) {
                return true;
            }
        } catch (IOException ignored) {}

        // 4. Real path / symlink resolution against active jar
        try {
            if (Files.exists(activeJarPath) && Files.exists(normalizedFolder)) {
                Path realFolder = normalizedFolder.toRealPath();
                Path realDest = realFolder.resolve(targetJarName);
                Path realActive = activeJarPath.toRealPath();
                if (realActive.equals(realDest)) {
                    return true;
                }
            }
        } catch (IOException ignored) {}

        return false;
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
    public void stop() {
        synchronized (lifecycleLock) {
            stopped.set(true);
            if (periodicTask != null) {
                periodicTask.cancel(true);
                periodicTask = null;
            }
            for (ActiveTransfer transfer : activeTransfers) {
                transfer.abort();
            }
            activeTransfers.clear();
            if (ownsScheduler && scheduler != null) {
                scheduler.shutdownNow();
            }
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

    @Override
    public Optional<Release> getAvailableUpdate() {
        return Optional.ofNullable(latestAvailableUpdate);
    }

    @Override
    public boolean isBreaking(Release release) {
        if (release == null) {
            return false;
        }
        SemanticVersion latestSemVer = SemanticVersion.parse(release.version());
        SemanticVersion currentSemVer = SemanticVersion.parse(currentVersion);
        if (latestSemVer.major() != currentSemVer.major()) {
            return true;
        }
        if (release.notes() != null && release.notes().toLowerCase(Locale.ROOT).contains("[breaking]")) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isAwaitingConfirmation() {
        Release available = latestAvailableUpdate;
        return available != null && !isUpdatePending() && isBreaking(available);
    }

    public static String extractSummary(String notes) {
        if (notes == null || notes.isBlank()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : notes.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()
                    || SHA256_LABEL_PATTERN.matcher(line).matches()
                    || STRICT_HEX_64.matcher(line).matches()) {
                continue;
            }
            if (line.toLowerCase(Locale.ROOT).contains("[breaking]")) {
                line = line.replaceAll("(?i)\\[breaking\\]", "").trim();
                if (line.isEmpty()) {
                    continue;
                }
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            return "";
        }
        String joined = String.join(" ", lines);
        if (joined.length() > 200) {
            return joined.substring(0, 197) + "...";
        }
        return joined;
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
        Messages msgs = messagesSupplier.get();
        if (isUpdatePending()) {
            Release available = latestAvailableUpdate;
            String ver = available != null ? available.version() : "new";
            messageSender.accept(msgs.plain("updates.downloaded", Map.of("latest", ver)));
            return;
        }
        Release available = latestAvailableUpdate;
        if (available != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(msgs.plain("updates.available", Map.of("latest", available.version(), "current", currentVersion)));
            if (isBreaking(available)) {
                sb.append("\n").append(msgs.plain("updates.breaking", Map.of("latest", available.version())));
            }
            String summary = extractSummary(available.notes());
            if (!summary.isBlank()) {
                sb.append("\n").append(msgs.plain("updates.summary", Map.of("summary", summary)));
            }
            messageSender.accept(sb.toString());
        }
    }

    @Override
    public CompletableFuture<Optional<Release>> checkForUpdate() {
        return CompletableFuture.supplyAsync(this::doCheckForUpdate, executor);
    }

    private Optional<Release> doCheckForUpdate() {
        if (stopped.get()) {
            return Optional.empty();
        }

        Instant deadline = Instant.now().plus(requestTimeout);

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

        ActiveTransfer activeOp = new ActiveTransfer(Thread.currentThread());
        activeTransfers.add(activeOp);

        try {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero() || stopped.get()) {
                return Optional.empty();
            }

            try (HttpTransport.HttpResponse response = httpTransport.executeGet(URI.create(GITHUB_RELEASES_API), headers, remaining)) {
                activeOp.resource.set(response);

                if (stopped.get()) {
                    return Optional.empty();
                }

                int status = response.statusCode();

                // Handle GitHub Rate Limiting headers (including secondary limits 429/403 and Retry-After)
                updateRateLimit(response);

                // 304 Not Modified: release has not changed, use cache
                if (status == 304) {
                    networkErrorLogged.set(false);
                    Release release = cachedRelease.get();
                    if (release != null) {
                        latestAvailableUpdate = release;
                        // Unchanged metadata still drives unfinished auto-download to completion (F8)
                        if (config.autoDownload() && !isUpdatePending() && !isBreaking(release)) {
                            triggerAutoDownload(release);
                        }
                    }
                    return Optional.ofNullable(release);
                }

                // 403 Forbidden or 429 Too Many Requests (Rate limited or access issue)
                if (status == 403 || status == 429) {
                    return Optional.ofNullable(cachedRelease.get());
                }

                if (status != 200) {
                    logger.log(Level.FINE, "GitHub releases API returned unexpected status {0}", status);
                    return Optional.empty();
                }

                String newEtag = response.getHeader("ETag");

                remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isNegative() || remaining.isZero() || stopped.get()) {
                    return Optional.empty();
                }

                // Read JSON body (capped to 1 MB for metadata, with timeout watchdog)
                String json = readStringCapped(response.body(), 1024 * 1024, remaining);
                Optional<Release> releaseOpt = parseRelease(json, deadline);

                if (releaseOpt.isEmpty() || stopped.get()) {
                    return Optional.empty();
                }

                Release release = releaseOpt.get();
                SemanticVersion latestSemVer = SemanticVersion.parse(release.version());
                SemanticVersion currentSemVer = SemanticVersion.parse(currentVersion);

                if (!latestSemVer.isNewerThan(currentSemVer)) {
                    // Not newer: running current or newer version
                    cachedRelease.set(null);
                    latestAvailableUpdate = null;
                    if (newEtag != null) {
                        cachedEtag.set(newEtag);
                    }
                    networkErrorLogged.set(false);
                    return Optional.empty();
                }

                // Genuinely successful check: commit cache, etag, and clear outage suppression (F8, F9)
                networkErrorLogged.set(false);
                if (newEtag != null) {
                    cachedEtag.set(newEtag);
                }
                cachedRelease.set(release);
                latestAvailableUpdate = release;

                // Notice in console on startup (once per version, stays English)
                if (consoleNotifiedVersions.add(release.version())) {
                    logger.info("There is a new version of DiscordTowny: " + release.version() + " (you have " + currentVersion + ").");
                    if (isBreaking(release)) {
                        logger.warning("Release " + release.version() + " contains breaking changes. Automatic download is suppressed; confirmation is required.");
                    }
                    String summary = extractSummary(release.notes());
                    if (!summary.isBlank()) {
                        logger.info("Changes summary: " + summary);
                    }
                }

                // Notice in Discord log channel (once per version, localized via catalog)
                if (logChannelNotifiedVersions.add(release.version()) && auditLogger != null) {
                    Messages msgs = messagesSupplier.get();
                    StringBuilder detail = new StringBuilder(msgs.label("updates.available", Map.of("latest", release.version(), "current", currentVersion)));
                    if (isBreaking(release)) {
                        detail.append(" ").append(msgs.label("updates.breaking", Map.of("latest", release.version())));
                    }
                    String summary = extractSummary(release.notes());
                    if (!summary.isBlank()) {
                        detail.append(" ").append(msgs.label("updates.summary", Map.of("summary", summary)));
                    }
                    auditLogger.accept(new AuditEvent(
                            Instant.now(),
                            AuditEvent.Severity.INFO,
                            "Updater",
                            "update_available",
                            release.version(),
                            true,
                            Optional.of(detail.toString())
                    ));
                }

                // Auto-download if enabled, not already pending, and NOT breaking (F6)
                if (config.autoDownload() && !isUpdatePending() && !isBreaking(release)) {
                    triggerAutoDownload(release);
                }

                return Optional.of(release);
            }
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
        } finally {
            activeTransfers.remove(activeOp);
        }
    }

    private void triggerAutoDownload(Release release) {
        download(release).whenComplete((res, err) -> {
            if (err != null) {
                logger.log(Level.WARNING, "Auto-download failed: " + err.getMessage(), err);
            } else if (res != DownloadResult.SUCCESS) {
                logger.warning("Auto-download failed for release " + release.version() + ": " + res);
            }
        });
    }

    private void updateRateLimit(HttpTransport.HttpResponse response) {
        int status = response.statusCode();
        String retryAfter = response.getHeader("Retry-After");
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                recordRateLimitDelay(Instant.now().plusSeconds(seconds));
                return;
            } catch (NumberFormatException e) {
                try {
                    Instant parsed = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                            .parse(retryAfter.trim(), Instant::from);
                    recordRateLimitDelay(parsed);
                    return;
                } catch (Exception ignored) {}
            }
        }

        String remaining = response.getHeader("X-RateLimit-Remaining");
        String reset = response.getHeader("X-RateLimit-Reset");
        if (reset != null && !reset.isBlank()) {
            if ("0".equals(remaining) || status == 429 || status == 403) {
                try {
                    long epochSec = Long.parseLong(reset.trim());
                    recordRateLimitDelay(Instant.ofEpochSecond(epochSec));
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private void recordRateLimitDelay(Instant target) {
        if (target != null) {
            rateLimitResetTime.accumulateAndGet(target, (cur, next) ->
                    cur == null || next.isAfter(cur) ? next : cur);
        }
    }

    private void handleNetworkFailure(IOException e) {
        if (networkErrorLogged.compareAndSet(false, true)) {
            logger.log(Level.WARNING, "Unable to check for updates: could not reach GitHub ({0}). Further network errors will be silenced until connectivity is restored.", e.getMessage());
        }
    }

    private Optional<Release> parseRelease(String json, Instant deadline) throws IOException {
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

            // F1 & F2: Find all eligible runnable jar assets and checksum assets
            List<SimpleJson.JsonObject> eligibleJars = new ArrayList<>();
            List<SimpleJson.JsonObject> checksumAssets = new ArrayList<>();

            for (SimpleJson.JsonValue val : assets) {
                if (val instanceof SimpleJson.JsonObject asset) {
                    String name = asset.getString("name");
                    String downloadUrl = asset.getString("browser_download_url");
                    if (name == null || downloadUrl == null) {
                        continue;
                    }

                    // Enforce official source policy on asset URL
                    URI assetUri;
                    try {
                        assetUri = URI.create(downloadUrl);
                    } catch (Exception e) {
                        continue;
                    }
                    if (!UpdateSourcePolicy.isAllowedAssetUri(assetUri)) {
                        continue;
                    }

                    String lowerName = name.toLowerCase(Locale.ROOT);
                    if (lowerName.endsWith(".jar") && !lowerName.endsWith("-sources.jar") && !lowerName.endsWith("-javadoc.jar") && !lowerName.endsWith("-dev.jar")) {
                        eligibleJars.add(asset);
                    } else if (lowerName.endsWith(".sha256") || lowerName.endsWith(".sha256.txt") || lowerName.equals("checksums.txt") || lowerName.equals("sha256sums") || lowerName.equals("sha256sums.txt")) {
                        checksumAssets.add(asset);
                    }
                }
            }

            if (eligibleJars.isEmpty()) {
                return Optional.empty();
            }

            // Unambiguous jar selection: exactly one eligible runnable jar
            SimpleJson.JsonObject selectedJar = null;
            if (eligibleJars.size() == 1) {
                selectedJar = eligibleJars.getFirst();
            } else {
                // If multiple jars, match against DiscordTowny canonical pattern
                List<SimpleJson.JsonObject> matches = eligibleJars.stream()
                        .filter(a -> {
                            String n = a.getString("name");
                            return n != null && n.matches("(?i)DiscordTowny(-.*)?\\.jar");
                        })
                        .toList();
                if (matches.size() == 1) {
                    selectedJar = matches.getFirst();
                } else {
                    logger.warning("Ambiguous jar assets in release: multiple candidates found, refusing without staging.");
                    return Optional.empty();
                }
            }

            String selectedJarName = selectedJar.getString("name");
            String jarDownloadUrl = selectedJar.getString("browser_download_url");

            // Extract SHA-256 bound to selected jar with 3 distinct outcomes (F2)
            ChecksumOutcome assetOutcome = extractChecksumFromAssets(checksumAssets, selectedJarName, deadline);
            if (assetOutcome instanceof ChecksumOutcome.InvalidOrAmbiguous inv) {
                logger.warning("Invalid or ambiguous checksum evidence in assets for " + selectedJarName + ": " + inv.reason() + "; refusing without fallback.");
                return Optional.empty();
            }

            ChecksumOutcome bodyOutcome = extractSha256FromBody(notes, selectedJarName);
            if (bodyOutcome instanceof ChecksumOutcome.InvalidOrAmbiguous inv) {
                logger.warning("Invalid or ambiguous checksum evidence in body for " + selectedJarName + ": " + inv.reason() + "; refusing without fallback.");
                return Optional.empty();
            }

            String finalSha256;
            if (assetOutcome instanceof ChecksumOutcome.Valid vAsset && bodyOutcome instanceof ChecksumOutcome.Valid vBody) {
                if (!vAsset.sha256().equalsIgnoreCase(vBody.sha256())) {
                    logger.warning("Conflicting checksums between release body and checksum asset for " + selectedJarName + "; refusing.");
                    return Optional.empty();
                }
                finalSha256 = vAsset.sha256();
            } else if (assetOutcome instanceof ChecksumOutcome.Valid vAsset) {
                finalSha256 = vAsset.sha256();
            } else if (bodyOutcome instanceof ChecksumOutcome.Valid vBody) {
                finalSha256 = vBody.sha256();
            } else {
                logger.warning("No published checksum found for " + selectedJarName + "; refusing without staging.");
                return Optional.empty();
            }

            if (!isValidSha256(finalSha256)) {
                logger.warning("Malformed published SHA-256 checksum for " + selectedJarName + "; refusing.");
                return Optional.empty();
            }

            return Optional.of(new Release(version, jarDownloadUrl, finalSha256.toLowerCase(Locale.ROOT), notes));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to parse release JSON", e);
            return Optional.empty();
        }
    }

    private ChecksumOutcome extractChecksumFromAssets(List<SimpleJson.JsonObject> checksumAssets, String targetJarName, Instant deadline) throws IOException {
        if (checksumAssets == null || checksumAssets.isEmpty()) {
            return new ChecksumOutcome.None();
        }

        String dedicatedCandidate = null;
        String multiFileCandidate = null;

        for (SimpleJson.JsonObject asset : checksumAssets) {
            String name = asset.getString("name");
            String downloadUrl = asset.getString("browser_download_url");
            if (name == null || downloadUrl == null) {
                continue;
            }
            String lowerName = name.toLowerCase(Locale.ROOT);
            String lowerTarget = targetJarName.toLowerCase(Locale.ROOT);

            // Dedicated asset: e.g. DiscordTowny-1.10.0.jar.sha256
            if (lowerName.equals(lowerTarget + ".sha256") || lowerName.equals(lowerTarget + ".sha256.txt")) {
                ChecksumOutcome outcome = fetchChecksumFromAsset(downloadUrl, targetJarName, true, deadline);
                if (outcome instanceof ChecksumOutcome.InvalidOrAmbiguous) {
                    return outcome;
                }
                if (outcome instanceof ChecksumOutcome.Valid v) {
                    if (dedicatedCandidate != null && !dedicatedCandidate.equalsIgnoreCase(v.sha256())) {
                        return new ChecksumOutcome.InvalidOrAmbiguous("Conflicting dedicated checksum hashes for " + targetJarName);
                    }
                    dedicatedCandidate = v.sha256();
                }
            } else if (lowerName.equals("checksums.txt") || lowerName.equals("sha256sums") || lowerName.equals("sha256sums.txt")) {
                ChecksumOutcome outcome = fetchChecksumFromAsset(downloadUrl, targetJarName, false, deadline);
                if (outcome instanceof ChecksumOutcome.InvalidOrAmbiguous) {
                    return outcome;
                }
                if (outcome instanceof ChecksumOutcome.Valid v) {
                    if (multiFileCandidate != null && !multiFileCandidate.equalsIgnoreCase(v.sha256())) {
                        return new ChecksumOutcome.InvalidOrAmbiguous("Conflicting multi-file checksum hashes for " + targetJarName);
                    }
                    multiFileCandidate = v.sha256();
                }
            }
        }

        if (dedicatedCandidate != null && multiFileCandidate != null) {
            if (!dedicatedCandidate.equalsIgnoreCase(multiFileCandidate)) {
                return new ChecksumOutcome.InvalidOrAmbiguous("Conflicting checksums between dedicated asset (" + dedicatedCandidate + ") and multi-file asset (" + multiFileCandidate + ")");
            }
            return new ChecksumOutcome.Valid(dedicatedCandidate);
        }
        if (dedicatedCandidate != null) {
            return new ChecksumOutcome.Valid(dedicatedCandidate);
        }
        if (multiFileCandidate != null) {
            return new ChecksumOutcome.Valid(multiFileCandidate);
        }

        return new ChecksumOutcome.None();
    }

    private ChecksumOutcome fetchChecksumFromAsset(String assetUrl, String targetJarName, boolean isDedicated, Instant deadline) throws IOException {
        URI uri;
        try {
            uri = URI.create(assetUrl);
        } catch (Exception e) {
            return new ChecksumOutcome.InvalidOrAmbiguous("Invalid checksum asset URL: " + assetUrl);
        }
        if (!UpdateSourcePolicy.isAllowedAssetUri(uri)) {
            return new ChecksumOutcome.InvalidOrAmbiguous("Untrusted checksum asset destination rejected by policy: " + uri);
        }

        ActiveTransfer activeOp = new ActiveTransfer(Thread.currentThread());
        activeTransfers.add(activeOp);

        try {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero() || stopped.get()) {
                return new ChecksumOutcome.InvalidOrAmbiguous("Checksum fetch timed out before contacting " + uri);
            }

            try (HttpTransport.HttpResponse response = httpTransport.executeGet(uri, Map.of("User-Agent", "DiscordTowny-Updater"), remaining)) {
                activeOp.resource.set(response);

                if (stopped.get()) {
                    return new ChecksumOutcome.InvalidOrAmbiguous("Service stopped during checksum fetch");
                }

                if (response.statusCode() == 200) {
                    remaining = Duration.between(Instant.now(), deadline);
                    if (remaining.isNegative() || remaining.isZero() || stopped.get()) {
                        return new ChecksumOutcome.InvalidOrAmbiguous("Checksum body read timed out");
                    }
                    String content = readStringCapped(response.body(), 65536, remaining);
                    return parseChecksumFileContent(content, targetJarName, isDedicated);
                } else if (response.statusCode() == 404) {
                    return new ChecksumOutcome.None();
                } else {
                    throw new IOException("HTTP error fetching checksum asset from " + uri + ": status " + response.statusCode());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching the checksum asset from " + uri, e);
        } finally {
            activeTransfers.remove(activeOp);
        }
    }

    private ChecksumOutcome parseChecksumFileContent(String content, String targetJarName, boolean isDedicated) {
        if (content == null || content.isBlank()) {
            return new ChecksumOutcome.None();
        }

        String[] lines = content.split("\\r?\\n");
        String matchedSha = null;
        boolean hasConflict = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            // Pattern 1: standard sha256sum: "<hex> [* ]<filename>"
            Matcher m1 = Pattern.compile("^(\\S+)\\s+[*]?(.+)$").matcher(line);
            if (m1.matches()) {
                String candidateHex = m1.group(1);
                String file = m1.group(2).trim();
                Path p = Path.of(file);
                String filename = p.getFileName() != null ? p.getFileName().toString() : file;
                if (filename.equalsIgnoreCase(targetJarName)) {
                    if (!isValidSha256(candidateHex)) {
                        return new ChecksumOutcome.InvalidOrAmbiguous("Malformed SHA-256 for " + targetJarName + ": " + candidateHex);
                    }
                    if (matchedSha != null && !matchedSha.equalsIgnoreCase(candidateHex)) {
                        hasConflict = true;
                    }
                    matchedSha = candidateHex;
                }
                continue;
            }

            // Pattern 2: BSD style: "SHA256 (filename) = <hex>"
            Matcher m2 = Pattern.compile("(?i)^SHA256\\s*\\((.+)\\)\\s*=\\s*(\\S+)$").matcher(line);
            if (m2.matches()) {
                String file = m2.group(1).trim();
                String candidateHex = m2.group(2).trim();
                Path p = Path.of(file);
                String filename = p.getFileName() != null ? p.getFileName().toString() : file;
                if (filename.equalsIgnoreCase(targetJarName)) {
                    if (!isValidSha256(candidateHex)) {
                        return new ChecksumOutcome.InvalidOrAmbiguous("Malformed SHA-256 for " + targetJarName + ": " + candidateHex);
                    }
                    if (matchedSha != null && !matchedSha.equalsIgnoreCase(candidateHex)) {
                        hasConflict = true;
                    }
                    matchedSha = candidateHex;
                }
                continue;
            }

            // Pattern 3: dedicated single-checksum file containing standalone 64-hex string
            if (isDedicated) {
                if (isValidSha256(line)) {
                    if (matchedSha != null && !matchedSha.equalsIgnoreCase(line)) {
                        hasConflict = true;
                    }
                    matchedSha = line;
                } else {
                    return new ChecksumOutcome.InvalidOrAmbiguous("Malformed SHA-256 in dedicated checksum file: " + line);
                }
            }
        }

        if (hasConflict) {
            return new ChecksumOutcome.InvalidOrAmbiguous("Multiple conflicting hashes for " + targetJarName + " in checksum asset");
        }

        return matchedSha != null ? new ChecksumOutcome.Valid(matchedSha.toLowerCase(Locale.ROOT)) : new ChecksumOutcome.None();
    }

    private ChecksumOutcome extractSha256FromBody(String body, String targetJarName) {
        if (body == null || body.isBlank()) {
            return new ChecksumOutcome.None();
        }

        List<String> boundHashes = new ArrayList<>();
        List<String> otherArtifactHashes = new ArrayList<>();
        boolean foundChecksumKeyword = false;

        for (String rawLine : body.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            // Check if line refers to SHA-256
            Matcher keywordMatcher = Pattern.compile("(?i)\\bsha-?256(?:sum)?\\b").matcher(line);
            if (keywordMatcher.find()) {
                foundChecksumKeyword = true;

                // Check for label pattern: sha256[:=\s]+<token>
                Matcher labelMatcher = Pattern.compile("(?i)(?:sha-?256(?:sum)?[:=\\s]+)(\\S+)").matcher(line);
                if (labelMatcher.find()) {
                    String candidateToken = labelMatcher.group(1);

                    // Check if candidate is valid 64 hex characters
                    if (!candidateToken.matches("^[a-fA-F0-9]{64}$")) {
                        return new ChecksumOutcome.InvalidOrAmbiguous("Malformed SHA-256 token in body: " + candidateToken);
                    }

                    // Check if this line is bound to a specific file
                    Matcher jarMatcher = Pattern.compile("(?i)\\b([a-zA-Z0-9_.-]+\\.jar)\\b").matcher(line);
                    if (jarMatcher.find()) {
                        String namedJar = jarMatcher.group(1);
                        if (namedJar.equalsIgnoreCase(targetJarName)) {
                            boundHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                        } else {
                            otherArtifactHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                        }
                    } else {
                        // Unbound label line (e.g. "SHA-256: <hex>")
                        boundHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                    }
                    continue;
                } else {
                    return new ChecksumOutcome.InvalidOrAmbiguous("Malformed SHA-256 declaration in body: " + line);
                }
            }

            // Also check BSD format on line: SHA256 (filename) = <token>
            Matcher bsdMatcher = Pattern.compile("(?i)^SHA256\\s*\\((.+)\\)\\s*=\\s*(\\S+)$").matcher(line);
            if (bsdMatcher.matches()) {
                foundChecksumKeyword = true;
                String file = bsdMatcher.group(1).trim();
                String candidateToken = bsdMatcher.group(2).trim();
                if (!candidateToken.matches("^[a-fA-F0-9]{64}$")) {
                    return new ChecksumOutcome.InvalidOrAmbiguous("Malformed BSD SHA-256 token in body: " + candidateToken);
                }
                Path p = Path.of(file);
                String filename = p.getFileName() != null ? p.getFileName().toString() : file;
                if (filename.equalsIgnoreCase(targetJarName)) {
                    boundHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                } else {
                    otherArtifactHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                }
                continue;
            }

            // Also check sha256sum format on line: <token>  <file.jar>
            Matcher sumMatcher = Pattern.compile("^(\\S+)\\s+[*]?(.+\\.jar)$").matcher(line);
            if (sumMatcher.matches()) {
                String candidateToken = sumMatcher.group(1);
                String file = sumMatcher.group(2).trim();
                foundChecksumKeyword = true;
                if (!candidateToken.matches("^[a-fA-F0-9]{64}$")) {
                    return new ChecksumOutcome.InvalidOrAmbiguous("Malformed sha256sum token in body: " + candidateToken);
                }
                Path p = Path.of(file);
                String filename = p.getFileName() != null ? p.getFileName().toString() : file;
                if (filename.equalsIgnoreCase(targetJarName)) {
                    boundHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                } else {
                    otherArtifactHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                }
            }
        }

        // Deduplicate boundHashes
        Set<String> distinctBound = new HashSet<>(boundHashes);
        if (distinctBound.size() > 1) {
            return new ChecksumOutcome.InvalidOrAmbiguous("Multiple conflicting SHA-256 hashes bound to " + targetJarName + " in body: " + distinctBound);
        }

        if (distinctBound.size() == 1) {
            return new ChecksumOutcome.Valid(distinctBound.iterator().next());
        }

        if (!otherArtifactHashes.isEmpty()) {
            return new ChecksumOutcome.InvalidOrAmbiguous("Release body contains checksum evidence for other artifacts, but none bound to " + targetJarName);
        }

        if (foundChecksumKeyword) {
            return new ChecksumOutcome.InvalidOrAmbiguous("Unparseable or incomplete SHA-256 declaration in release body");
        }

        return new ChecksumOutcome.None();
    }

    @Override
    public CompletableFuture<DownloadResult> download(Release release) {
        if (release == null || release.downloadUrl() == null || release.downloadUrl().isBlank()) {
            return CompletableFuture.completedFuture(DownloadResult.IO_ERROR);
        }

        // Mandatory checksum: refuse immediately if null, blank, or invalid length/format
        if (release.sha256() == null || !isValidSha256(release.sha256())) {
            logger.warning("Update download refused: release " + release.version() + " has no valid published SHA-256 checksum.");
            return CompletableFuture.completedFuture(DownloadResult.CHECKSUM_MISMATCH);
        }

        // F1: Official repository source policy check before contacting
        URI downloadUri;
        try {
            downloadUri = URI.create(release.downloadUrl());
        } catch (Exception e) {
            logger.severe("Refusing download: invalid download URL syntax: " + release.downloadUrl());
            return CompletableFuture.completedFuture(DownloadResult.IO_ERROR);
        }
        if (!UpdateSourcePolicy.isAllowedDownloadDestination(downloadUri)) {
            logger.severe("Refusing download: destination outside official repository source policy: " + release.downloadUrl());
            return CompletableFuture.completedFuture(DownloadResult.IO_ERROR);
        }

        // F4: Ensure destination is inside updateFolder and not the active running jar
        Path destination = updateFolder.resolve(targetJarName).normalize();
        if (isDestinationActiveJar(destination)) {
            logger.severe("Refusing download: destination resolves to active jar or escapes update folder: " + destination);
            return CompletableFuture.completedFuture(DownloadResult.IO_ERROR);
        }

        return CompletableFuture.supplyAsync(() -> performDownload(release), executor);
    }

    private DownloadResult performDownload(Release release) {
        if (stopped.get()) {
            return DownloadResult.IO_ERROR;
        }

        Instant deadline = Instant.now().plus(requestTimeout);

        Path tempFile = null;
        ActiveTransfer activeTransfer = new ActiveTransfer(Thread.currentThread());
        activeTransfers.add(activeTransfer);

        try {
            Files.createDirectories(updateFolder);
            tempFile = Files.createTempFile(updateFolder, "dt-update-", ".tmp");
            activeTransfer.tempFile.set(tempFile);

            Map<String, String> headers = Map.of("User-Agent", "DiscordTowny-Updater");
            URI downloadUri = URI.create(release.downloadUrl());

            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero() || stopped.get()) {
                cleanupTemp(tempFile);
                return DownloadResult.NETWORK_ERROR;
            }

            try (HttpTransport.HttpResponse response = httpTransport.executeGet(downloadUri, headers, remaining)) {
                activeTransfer.resource.set(response);

                if (stopped.get()) {
                    cleanupTemp(tempFile);
                    return DownloadResult.IO_ERROR;
                }

                if (response.statusCode() != 200) {
                    cleanupTemp(tempFile);
                    return DownloadResult.NETWORK_ERROR;
                }

                // Check Content-Length header upfront if present (F13: zero body bytes read)
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

                remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isNegative() || remaining.isZero() || stopped.get()) {
                    cleanupTemp(tempFile);
                    return DownloadResult.NETWORK_ERROR;
                }

                // Setup watchdog on remaining duration of single operation deadline (F5)
                AtomicBoolean timedOut = new AtomicBoolean(false);
                ScheduledFuture<?> watchdog = TIMEOUT_WATCHDOG.schedule(() -> {
                    timedOut.set(true);
                    activeTransfer.abort();
                }, Math.max(1, remaining.toMillis()), TimeUnit.MILLISECONDS);

                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException e) {
                    watchdog.cancel(false);
                    cleanupTemp(tempFile);
                    return DownloadResult.IO_ERROR;
                }

                long totalBytes = 0;
                byte[] buffer = new byte[8192];

                try (InputStream in = response.body();
                     OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (stopped.get()) {
                            watchdog.cancel(false);
                            cleanupTemp(tempFile);
                            return DownloadResult.IO_ERROR;
                        }
                        if (timedOut.get() || Instant.now().isAfter(deadline)) {
                            watchdog.cancel(false);
                            cleanupTemp(tempFile);
                            logger.warning("Download body read timed out after " + requestTimeout);
                            return DownloadResult.NETWORK_ERROR;
                        }

                        totalBytes += read;
                        if (totalBytes > maxDownloadBytes) {
                            watchdog.cancel(false);
                            out.close();
                            cleanupTemp(tempFile);
                            return DownloadResult.TOO_LARGE;
                        }
                        digest.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                    }
                } catch (IOException e) {
                    watchdog.cancel(false);
                    if (timedOut.get() || Thread.interrupted()) {
                        cleanupTemp(tempFile);
                        logger.warning("Download body read timed out: " + e.getMessage());
                        return DownloadResult.NETWORK_ERROR;
                    }
                    throw e;
                } finally {
                    watchdog.cancel(false);
                    if (timedOut.get()) {
                        Thread.interrupted();
                    }
                }

                if (stopped.get()) {
                    cleanupTemp(tempFile);
                    return DownloadResult.IO_ERROR;
                }
                if (timedOut.get() || Instant.now().isAfter(deadline)) {
                    cleanupTemp(tempFile);
                    return DownloadResult.NETWORK_ERROR;
                }

                // Verify SHA-256
                String actualSha256 = HexFormat.of().formatHex(digest.digest());
                String expectedSha256 = release.sha256().trim().toLowerCase(Locale.ROOT);

                if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                    boolean deleted = cleanupTemp(tempFile);
                    tempFile = null;
                    if (deleted) {
                        logger.warning("Checksum mismatch for downloaded update! Expected: " + expectedSha256 + ", actual: " + actualSha256 + ". Download discarded.");
                    } else {
                        logger.warning("Checksum mismatch for downloaded update! Expected: " + expectedSha256 + ", actual: " + actualSha256 + ". Download discarded, but temporary file could not be removed: " + tempFile);
                    }
                    return DownloadResult.CHECKSUM_MISMATCH;
                }

                // F4: Establish destination is inside updateFolder and not the active running jar
                Path destination = updateFolder.resolve(targetJarName).normalize();
                if (isDestinationActiveJar(destination)) {
                    cleanupTemp(tempFile);
                    logger.severe("Refusing to overwrite active jar directly: " + destination);
                    return DownloadResult.IO_ERROR;
                }

                // F3 & F5: Publication synchronized with lifecycle to prevent publishing after stop()
                synchronized (lifecycleLock) {
                    if (stopped.get()) {
                        cleanupTemp(tempFile);
                        logger.warning("Download completed after service stopped; publication discarded.");
                        return DownloadResult.IO_ERROR;
                    }

                    publishExecutable(tempFile, destination);
                    tempFile = null; // Successfully published
                }

                logger.info("Version " + release.version() + " is downloaded. Restart the server to apply it.");

                // Log channel notification once on download completion (F7, F9: notification failure must not misreport committed download)
                try {
                    if (downloadNotifiedVersions.add(release.version()) && auditLogger != null) {
                        Messages msgs = messagesSupplier.get();
                        auditLogger.accept(new AuditEvent(
                                Instant.now(),
                                AuditEvent.Severity.INFO,
                                "Updater",
                                "update_downloaded",
                                release.version(),
                                true,
                                Optional.of(msgs.label("updates.downloaded", Map.of("latest", release.version())))
                        ));
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to emit audit notification after successful update download", e);
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
            activeTransfers.remove(activeTransfer);
            cleanupTemp(tempFile);
        }
    }

    void publishExecutable(Path source, Path destination) throws IOException {
        Path destinationDir = destination.getParent();
        if (destinationDir != null) {
            Files.createDirectories(destinationDir);
        }

        Path backup = null;
        boolean published = false;
        try {
            if (Files.exists(destination)) {
                backup = destination.resolveSibling(targetJarName + ".backup-" + UUID.randomUUID());
                try {
                    Files.move(destination, backup, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(destination, backup, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, destination);
            }
            published = true;
        } catch (Exception moveError) {
            // Rollback: if publication failed, destination must NEVER have a partial/corrupt file
            if (!published) {
                cleanupTemp(destination);
            }
            // Restore previous verified update if backup was created
            if (backup != null && Files.exists(backup)) {
                try {
                    try {
                        Files.move(backup, destination, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(backup, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception restoreError) {
                    logger.log(Level.SEVERE, "Failed to restore backup update jar after move failure: " + backup, restoreError);
                    moveError.addSuppressed(restoreError);
                }
            }
            throw (moveError instanceof IOException ioe ? ioe : new IOException("Failed to publish executable", moveError));
        } finally {
            if (published && backup != null) {
                cleanupTemp(backup);
            }
        }
    }

    private boolean cleanupTemp(Path file) {
        if (file != null) {
            boolean interrupted = Thread.interrupted();
            try {
                return Files.deleteIfExists(file);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to delete file: " + file, e);
                return false;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return true;
    }

    private static String readStringCapped(InputStream in, int maxBytes) throws IOException {
        return readStringCapped(in, maxBytes, null);
    }

    private static String readStringCapped(InputStream in, int maxBytes, Duration timeout) throws IOException {
        if (in == null) {
            return "";
        }
        Instant deadline = timeout != null ? Instant.now().plus(timeout) : null;
        Thread callerThread = Thread.currentThread();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> watchdog = timeout != null ? TIMEOUT_WATCHDOG.schedule(() -> {
            timedOut.set(true);
            callerThread.interrupt();
            try {
                in.close();
            } catch (Exception ignored) {}
        }, Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS) : null;

        byte[] buffer = new byte[Math.min(8192, maxBytes)];
        int total = 0;
        StringBuilder sb = new StringBuilder();
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (timedOut.get() || (deadline != null && Instant.now().isAfter(deadline))) {
                    throw new java.net.SocketTimeoutException("Read timed out after " + timeout);
                }
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Response exceeded limit of " + maxBytes + " bytes");
                }
                sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return sb.toString();
        } catch (IOException e) {
            if (timedOut.get()) {
                throw new java.net.SocketTimeoutException("Read timed out after " + timeout);
            }
            throw e;
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
            if (timedOut.get()) {
                Thread.interrupted();
            }
        }
    }
}
