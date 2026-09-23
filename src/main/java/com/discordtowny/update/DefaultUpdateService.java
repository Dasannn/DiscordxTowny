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
import java.util.concurrent.atomic.AtomicLong;
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

    private static final Pattern BSD_DECL_PATTERN =
            Pattern.compile("(?i)\\bSHA-?256\\s*\\(([^)\r\n]+)\\)\\s*=\\s*(\\S+)");
    private static final Pattern SUM_DECL_PATTERN =
            Pattern.compile("^(\\S+)(?:[ ]{2,}|[ ]\\*|\\t|\\s+[*]?)(\\S.*)$");
    private static final Pattern KEYWORD_AUDIT_PATTERN =
            Pattern.compile("(?<![/\\\\])(?i)\\bsha-?256(?:sum)?\\b(?![^\\s/\\\\]*[/\\\\])");
    private static final Pattern LABELED_DECL_PATTERN =
            Pattern.compile("(?<![/\\\\])(?i)\\bsha-?256(?:sum)?(?![\\s]*\\()(?![^\\s/\\\\]*[/\\\\])[:=\\s]+(\\S+)");
    private static final Pattern ABSORBED_DECL_PATTERN =
            Pattern.compile("(?<![/\\\\])(?i)\\bsha-?256(?:sum)?(?:\\s*[:=\\(]|\\s+\\S+)");
    private static final Pattern JAR_NAME_PATTERN =
            Pattern.compile("(?i)\\b(\\S+\\.jar)\\b");
    private static final Pattern FILENAME_WITH_EXTENSION =
            Pattern.compile("^.+\\.[a-zA-Z0-9]{1,8}$");

    private enum DeclarationFormat {
        BSD,
        SUM,
        LABELED
    }

    private record ChecksumDeclaration(
            DeclarationFormat format,
            String candidateToken,
            String rawFilename
    ) {}

    private static String normalizeFilename(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        String normFile = file.replace('\\', '/');
        try {
            Path p = Path.of(normFile);
            return p.getFileName() != null ? p.getFileName().toString() : normFile;
        } catch (Exception e) {
            int lastSlash = normFile.lastIndexOf('/');
            return lastSlash >= 0 ? normFile.substring(lastSlash + 1) : normFile;
        }
    }

    private static boolean absorbsChecksumDeclaration(String file) {
        if (file == null || file.isBlank()) {
            return false;
        }
        return ABSORBED_DECL_PATTERN.matcher(file).find();
    }

    static boolean hasFileExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        return FILENAME_WITH_EXTENSION.matcher(filename.trim()).matches();
    }

    private static boolean isSupportedSumFilename(String rawFile, String targetJarName, Set<String> releaseAssetNames) {
        if (rawFile == null || rawFile.isBlank()) {
            return false;
        }
        String norm = normalizeFilename(rawFile);
        if (norm == null || norm.isBlank()) {
            return false;
        }
        if (!hasFileExtension(norm)) {
            return false;
        }
        if (norm.equalsIgnoreCase(targetJarName)) {
            return true;
        }
        if (norm.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return true;
        }
        if (releaseAssetNames != null && !releaseAssetNames.isEmpty()) {
            for (String assetName : releaseAssetNames) {
                if (assetName != null) {
                    String normAsset = normalizeFilename(assetName);
                    if (normAsset != null && norm.equalsIgnoreCase(normAsset)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static ChecksumOutcome validateDeclaration(ChecksumDeclaration decl, String sourceDesc) {
        if (decl.rawFilename() != null && absorbsChecksumDeclaration(decl.rawFilename())) {
            if ("checksum file".equals(sourceDesc)) {
                String desc = decl.format() == DeclarationFormat.BSD
                        ? "Malformed BSD declaration in checksum file absorbing checksum declaration: "
                        : "Malformed sha256sum entry in checksum file absorbing checksum declaration: ";
                return new ChecksumOutcome.InvalidOrAmbiguous(desc + decl.rawFilename());
            } else {
                String desc = decl.format() == DeclarationFormat.BSD
                        ? "Malformed BSD declaration absorbing checksum declaration in filename: "
                        : (decl.format() == DeclarationFormat.SUM
                        ? "Malformed sha256sum entry absorbing checksum declaration in filename: "
                        : "Malformed declaration absorbing checksum declaration in filename: ");
                return new ChecksumOutcome.InvalidOrAmbiguous(desc + decl.rawFilename());
            }
        }
        if (!isValidSha256(decl.candidateToken())) {
            if ("checksum file".equals(sourceDesc)) {
                return new ChecksumOutcome.InvalidOrAmbiguous("Malformed SHA-256 token in checksum file: " + decl.candidateToken());
            } else {
                String prefix = decl.format() == DeclarationFormat.BSD ? "BSD " : "";
                return new ChecksumOutcome.InvalidOrAmbiguous("Malformed " + prefix + "SHA-256 token in body: " + decl.candidateToken());
            }
        }
        return null;
    }

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

    private record CachedRelease(String etag, Release release, boolean upToDate) {}

    // Rate limiting & caching
    private final AtomicReference<Instant> rateLimitResetTime = new AtomicReference<>(null);
    private final AtomicReference<CachedRelease> cachedRelease = new AtomicReference<>(null);

    // Network error deduplication (log once until restored)
    private final AtomicBoolean networkErrorLogged = new AtomicBoolean(false);

    // Check failure state tracking
    private final AtomicBoolean lastCheckFailed = new AtomicBoolean(false);
    private final AtomicReference<String> lastCheckError = new AtomicReference<>(null);
    private final AtomicBoolean hasCheckedAtLeastOnce = new AtomicBoolean(false);
    private final AtomicReference<CheckResult> lastCheckResult = new AtomicReference<>(CheckResult.notChecked("Not checked yet"));

    // Monotonic check operation sequence tracking (F13)
    private final AtomicLong checkSequenceGenerator = new AtomicLong(0);
    private final AtomicLong publishedSequence = new AtomicLong(0);

    // Notification deduplication (once per version)
    private final Set<String> consoleNotifiedVersions = ConcurrentHashMap.newKeySet();
    private final Set<String> logChannelNotifiedVersions = ConcurrentHashMap.newKeySet();
    private final Set<String> downloadNotifiedVersions = ConcurrentHashMap.newKeySet();

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
        CheckResult result = lastCheckResult.get();
        if (result.status() == CheckStatus.UPDATE_AVAILABLE) {
            return result.release();
        }
        if (result.status() == CheckStatus.CHECK_FAILED) {
            if (result.release().isPresent()) {
                return result.release();
            }
            CachedRelease cached = cachedRelease.get();
            return (cached != null && cached.release() != null) ? Optional.of(cached.release()) : Optional.empty();
        }
        return Optional.empty();
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
        Release available = getAvailableUpdate().orElse(null);
        return available != null && !isUpdatePending() && isBreaking(available);
    }

    @Override
    public boolean isLastCheckFailed() {
        return lastCheckResult.get().status() == CheckStatus.CHECK_FAILED;
    }

    @Override
    public Optional<String> getLastCheckError() {
        return lastCheckResult.get().error();
    }

    @Override
    public boolean hasCheckedAtLeastOnce() {
        return lastCheckResult.get().status() != CheckStatus.NOT_CHECKED;
    }

    @Override
    public CheckStatus checkStatus() {
        return lastCheckResult.get().status();
    }

    @Override
    public CheckResult getLastCheckResult() {
        CheckResult result = lastCheckResult.get();
        if (result.release().isPresent()) {
            return result;
        }
        if (result.status() == CheckStatus.CHECK_FAILED) {
            CachedRelease cached = cachedRelease.get();
            Release r = (cached != null) ? cached.release() : null;
            if (r != null) {
                return CheckResult.checkFailed(result.error().orElse(null), r);
            }
        }
        return result;
    }

    private synchronized CheckResult publishCheckResult(long checkSeq, CheckResult result, CachedRelease newCache) {
        if (checkSeq > publishedSequence.get()) {
            publishedSequence.set(checkSeq);
            lastCheckResult.set(result);
            if (newCache != null) {
                cachedRelease.set(newCache);
            }
            if (result.status() == CheckStatus.CHECK_FAILED) {
                lastCheckFailed.set(true);
                lastCheckError.set(result.error().orElse(null));
                hasCheckedAtLeastOnce.set(true);
            } else if (result.status() == CheckStatus.UPDATE_AVAILABLE) {
                lastCheckFailed.set(false);
                lastCheckError.set(null);
                hasCheckedAtLeastOnce.set(true);
                networkErrorLogged.set(false);
            } else if (result.status() == CheckStatus.UP_TO_DATE) {
                lastCheckFailed.set(false);
                lastCheckError.set(null);
                hasCheckedAtLeastOnce.set(true);
                networkErrorLogged.set(false);
            }
        }
        return result;
    }

    private CheckResult publishCheckResult(long checkSeq, CheckResult result) {
        return publishCheckResult(checkSeq, result, null);
    }

    public static String formatCheckFailureReason(String rawError, Messages msg) {
        if (rawError == null || rawError.isBlank()) {
            return msg.label("general.unknown");
        }
        String lower = rawError.toLowerCase(Locale.ROOT);
        if (lower.contains("rate limit") || lower.contains("status 429") || lower.contains("status 403")) {
            return msg.label("updates.check-reason-rate-limited");
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return msg.label("updates.check-reason-timeout");
        }
        if (lower.contains("interrupted")) {
            return msg.label("updates.check-reason-interrupted");
        }
        if (lower.contains("no runnable jar")) {
            return msg.label("updates.check-reason-no-jar");
        }
        if (lower.contains("ambiguous jar")) {
            return msg.label("updates.check-reason-ambiguous-jar");
        }
        if (lower.contains("missing release") || lower.contains("no assets") || lower.contains("parse")) {
            return msg.label("updates.check-reason-parse-error");
        }
        if (lower.contains("no published checksum")) {
            return msg.label("updates.check-reason-no-checksum");
        }
        if (lower.contains("invalid or ambiguous checksum") || lower.contains("conflicting checksum")
                || lower.contains("malformed sha-256") || lower.contains("malformed bsd sha-256")
                || lower.contains("malformed published sha-256") || lower.contains("unparseable or incomplete sha-256")
                || lower.contains("checksum evidence for other artifacts") || lower.contains("invalid-checksum")
                || lower.contains("invalid checksum") || lower.contains("absorbing checksum")) {
            return msg.label("updates.check-reason-invalid-checksum");
        }
        if (lower.contains("could not reach github") || lower.contains("http error") || lower.contains("connection") || lower.contains("untrusted") || lower.contains("unexpected status")) {
            return msg.label("updates.check-reason-network");
        }
        return msg.label("updates.check-reason-parse-error");
    }

    public List<String> renderStatusMessages(Messages msg) {
        List<String> result = new ArrayList<>();
        result.add(msg.plain("updates.status-current", Map.of("current", currentVersion)));

        CheckResult lastResult = getLastCheckResult();
        Optional<Release> availableOpt = lastResult.release();

        boolean hasUpdateInfo = false;
        if (isUpdatePending()) {
            hasUpdateInfo = true;
            String ver = availableOpt
                    .map(Release::version)
                    .orElse(currentVersion);
            result.add(msg.plain("updates.downloaded", Map.of("latest", ver)));
        } else if (availableOpt.isPresent()) {
            hasUpdateInfo = true;
            Release release = availableOpt.get();
            result.add(msg.plain("updates.available", Map.of("latest", release.version(), "current", currentVersion)));
            if (isBreaking(release)) {
                result.add(msg.plain("updates.breaking", Map.of("latest", release.version())));
            }
            String summary = extractSummary(release.notes());
            if (!summary.isBlank()) {
                result.add(msg.plain("updates.summary", Map.of("summary", summary)));
            }
        }

        CheckStatus status = lastResult.status();
        if (status == CheckStatus.CHECK_FAILED) {
            String reason = formatCheckFailureReason(lastResult.error().orElse(null), msg);
            result.add(msg.plain("updates.check-failed", Map.of("reason", reason)));
        } else if (!hasUpdateInfo) {
            if (status == CheckStatus.NOT_CHECKED) {
                result.add(msg.plain("updates.not-checked", Map.of()));
            } else if (status == CheckStatus.UP_TO_DATE) {
                result.add(msg.plain("updates.up-to-date", Map.of()));
            }
        }
        return result;
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
        CheckResult lastResult = getLastCheckResult();
        boolean checkFailed = lastResult.status() == CheckStatus.CHECK_FAILED;
        Release available = lastResult.release().orElse(null);
        if (isUpdatePending()) {
            String ver = available != null ? available.version() : "new";
            messageSender.accept(msgs.plain("updates.downloaded", Map.of("latest", ver)));
            if (checkFailed) {
                String reason = formatCheckFailureReason(lastResult.error().orElse(null), msgs);
                messageSender.accept(msgs.plain("updates.check-failed", Map.of("reason", reason)));
            }
            return;
        }
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
            if (checkFailed) {
                String reason = formatCheckFailureReason(lastResult.error().orElse(null), msgs);
                messageSender.accept(msgs.plain("updates.check-failed", Map.of("reason", reason)));
            }
        } else if (checkFailed) {
            String reason = formatCheckFailureReason(lastResult.error().orElse(null), msgs);
            messageSender.accept(msgs.plain("updates.check-failed", Map.of("reason", reason)));
        }
    }

    @Override
    public CompletableFuture<CheckResult> checkForUpdate() {
        return CompletableFuture.supplyAsync(this::doCheckForUpdate, executor);
    }

    private CheckResult doCheckForUpdate() {
        if (stopped.get()) {
            return CheckResult.notChecked("Service stopped");
        }
        long checkSeq = checkSequenceGenerator.incrementAndGet();

        CachedRelease sentCache = cachedRelease.get();
        Release capturedRelease = (sentCache != null) ? sentCache.release() : null;

        Instant deadline = Instant.now().plus(requestTimeout);

        // Respect rate limits: if rate limit was exceeded, do not query until reset
        Instant resetTime = rateLimitResetTime.get();
        if (resetTime != null && Instant.now().isBefore(resetTime)) {
            String err = "could not reach GitHub: rate limit exceeded; resets at " + resetTime;
            return publishCheckResult(checkSeq, CheckResult.checkFailed(err, capturedRelease));
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "DiscordTowny-Updater");
        headers.put("Accept", "application/vnd.github+json");

        String etag = (sentCache != null) ? sentCache.etag() : null;
        if (etag != null && !etag.isBlank()) {
            headers.put("If-None-Match", etag);
        }

        ActiveTransfer activeOp = new ActiveTransfer(Thread.currentThread());
        activeTransfers.add(activeOp);

        try {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero() || stopped.get()) {
                if (!stopped.get()) {
                    String err = "could not reach GitHub (request timed out)";
                    return publishCheckResult(checkSeq, CheckResult.checkFailed(err));
                }
                return CheckResult.notChecked("Service stopped");
            }

            try (HttpTransport.HttpResponse response = httpTransport.executeGet(URI.create(GITHUB_RELEASES_API), headers, remaining)) {
                activeOp.resource.set(response);

                if (stopped.get()) {
                    return CheckResult.notChecked("Service stopped");
                }

                int status = response.statusCode();

                // Handle GitHub Rate Limiting headers (including secondary limits 429/403 and Retry-After)
                updateRateLimit(response);

                // 304 Not Modified: release has not changed, use cache
                if (status == 304) {
                    networkErrorLogged.set(false);
                    // Conditional-response classification must use the validated representation
                    // associated with its request validator; absence of that representation cannot
                    // authorize an up-to-date result (F1-A).
                    if (sentCache != null && sentCache.release() != null) {
                        Release release = sentCache.release();
                        // Unchanged metadata still drives unfinished auto-download to completion (F8)
                        if (config.autoDownload() && !isUpdatePending() && !isBreaking(release)) {
                            triggerAutoDownload(release);
                        }
                        return publishCheckResult(checkSeq, CheckResult.updateAvailable(release), sentCache);
                    }
                    if (sentCache != null && sentCache.upToDate()) {
                        return publishCheckResult(checkSeq, CheckResult.upToDate(), sentCache);
                    }
                    // An unsolicited 304 answering a request without validator/representation is an
                    // abnormal upstream response and must never borrow success from another check (F1-A).
                    String err = "could not reach GitHub: unexpected 304 without cached release representation";
                    return publishCheckResult(checkSeq, CheckResult.checkFailed(err));
                }

                // 403 Forbidden or 429 Too Many Requests (Rate limited or access issue)
                if (status == 403 || status == 429) {
                    String err = "could not reach GitHub: rate limit exceeded (HTTP " + status + ")";
                    return publishCheckResult(checkSeq, CheckResult.checkFailed(err, capturedRelease));
                }

                if (status != 200) {
                    logger.log(Level.FINE, "GitHub releases API returned unexpected status {0}", status);
                    String err = "could not reach GitHub: unexpected status " + status;
                    return publishCheckResult(checkSeq, CheckResult.checkFailed(err));
                }

                String newEtag = response.getHeader("ETag");

                remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isNegative() || remaining.isZero() || stopped.get()) {
                    if (!stopped.get()) {
                        String err = "could not reach GitHub (reading body timed out)";
                        return publishCheckResult(checkSeq, CheckResult.checkFailed(err));
                    }
                    return CheckResult.notChecked("Service stopped");
                }

                // Read JSON body (capped to 1 MB for metadata, with timeout watchdog)
                String json = readStringCapped(response.body(), 1024 * 1024, remaining);
                ParseResult parseResult = parseRelease(json, deadline);

                if (!parseResult.isSuccess() || stopped.get()) {
                    if (!stopped.get()) {
                        String err = parseResult.error() != null
                                ? parseResult.error()
                                : "could not reach GitHub: could not resolve release jar or published checksum";
                        return publishCheckResult(checkSeq, CheckResult.checkFailed(err));
                    }
                    return CheckResult.notChecked("Service stopped");
                }

                Release release = parseResult.release();
                SemanticVersion latestSemVer = SemanticVersion.parse(release.version());
                SemanticVersion currentSemVer = SemanticVersion.parse(currentVersion);

                if (!latestSemVer.isNewerThan(currentSemVer)) {
                    // Not newer: running current or newer version
                    CachedRelease newCache = new CachedRelease(newEtag, null, true);
                    return publishCheckResult(checkSeq, CheckResult.upToDate(), newCache);
                }

                // Genuinely successful check: clear outage suppression (F8, F9, F1-A)
                networkErrorLogged.set(false);

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
                try {
                    if (logChannelNotifiedVersions.add(release.version()) && auditLogger != null) {
                        // The Discord log channel is part of the log boundary, which spec 9.1
                        // keeps in English whatever the players read.
                        Messages msgs = EnglishMessages.bundled();
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
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to emit audit notification for available update", e);
                }

                // Auto-download if enabled, not already pending, and NOT breaking (F6)
                if (config.autoDownload() && !isUpdatePending() && !isBreaking(release)) {
                    triggerAutoDownload(release);
                }

                CachedRelease newCache = new CachedRelease(newEtag, release, false);
                return publishCheckResult(checkSeq, CheckResult.updateAvailable(release), newCache);
            }
        } catch (IOException e) {
            String errorMsg = e.getMessage() != null && !e.getMessage().isBlank()
                    ? "could not reach GitHub (" + e.getMessage() + ")"
                    : "could not reach GitHub";
            handleNetworkFailure(e);
            return publishCheckResult(checkSeq, CheckResult.checkFailed(errorMsg));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String errorMsg = "could not reach GitHub (Update check interrupted)";
            handleNetworkFailure(new IOException("Update check interrupted", e));
            return publishCheckResult(checkSeq, CheckResult.checkFailed(errorMsg));
        } catch (Exception e) {
            // Malformed JSON or parsing errors do not throw out of service
            String errorMsg = e.getMessage() != null && !e.getMessage().isBlank()
                    ? "could not reach GitHub (" + e.getMessage() + ")"
                    : "could not reach GitHub (Failed to parse update release)";
            logger.log(Level.FINE, "Failed to parse update release", e);
            return publishCheckResult(checkSeq, CheckResult.checkFailed(errorMsg));
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

    private record ParseResult(Release release, String error) {
        static ParseResult success(Release release) {
            return new ParseResult(release, null);
        }
        static ParseResult failure(String error) {
            return new ParseResult(null, error);
        }
        boolean isSuccess() {
            return release != null;
        }
    }

    private ParseResult parseRelease(String json, Instant deadline) throws IOException {
        try {
            SimpleJson.JsonObject obj = SimpleJson.parseObject(json);
            String tag = obj.getString("tag_name");
            if (tag == null || tag.isBlank()) {
                tag = obj.getString("name");
            }
            if (tag == null || tag.isBlank()) {
                return ParseResult.failure("Missing release tag or name in release metadata");
            }

            String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1).trim() : tag.trim();
            String notes = obj.getString("body");
            if (notes == null) {
                notes = "";
            }

            SimpleJson.JsonArray assets = obj.getArray("assets");
            if (assets == null || assets.isEmpty()) {
                return ParseResult.failure("Release metadata has no assets");
            }

            // F1 & F2: Find all eligible runnable jar assets and checksum assets
            List<SimpleJson.JsonObject> eligibleJars = new ArrayList<>();
            List<SimpleJson.JsonObject> checksumAssets = new ArrayList<>();
            Set<String> releaseAssetNames = new HashSet<>();

            for (SimpleJson.JsonValue val : assets) {
                if (val instanceof SimpleJson.JsonObject asset) {
                    String name = asset.getString("name");
                    if (name != null && !name.isBlank()) {
                        releaseAssetNames.add(name.trim());
                    }
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
                return ParseResult.failure("No runnable jar asset found in release");
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
                    return ParseResult.failure("Ambiguous jar assets in release");
                }
            }

            String selectedJarName = selectedJar.getString("name");
            String jarDownloadUrl = selectedJar.getString("browser_download_url");

            // Extract SHA-256 bound to selected jar with 3 distinct outcomes (F2)
            ChecksumOutcome assetOutcome = extractChecksumFromAssets(checksumAssets, selectedJarName, deadline);
            if (assetOutcome instanceof ChecksumOutcome.InvalidOrAmbiguous inv) {
                logger.warning("Invalid or ambiguous checksum evidence in assets for " + selectedJarName + ": " + inv.reason() + "; refusing without fallback.");
                return ParseResult.failure("Invalid or ambiguous checksum in assets: " + inv.reason());
            }

            ChecksumOutcome bodyOutcome = extractSha256FromBody(notes, selectedJarName, releaseAssetNames);
            if (bodyOutcome instanceof ChecksumOutcome.InvalidOrAmbiguous inv) {
                logger.warning("Invalid or ambiguous checksum evidence in body for " + selectedJarName + ": " + inv.reason() + "; refusing without fallback.");
                return ParseResult.failure("Invalid or ambiguous checksum in release body: " + inv.reason());
            }

            String finalSha256;
            if (assetOutcome instanceof ChecksumOutcome.Valid vAsset && bodyOutcome instanceof ChecksumOutcome.Valid vBody) {
                if (!vAsset.sha256().equalsIgnoreCase(vBody.sha256())) {
                    logger.warning("Conflicting checksums between release body and checksum asset for " + selectedJarName + "; refusing.");
                    return ParseResult.failure("Conflicting checksums between release body and checksum asset");
                }
                finalSha256 = vAsset.sha256();
            } else if (assetOutcome instanceof ChecksumOutcome.Valid vAsset) {
                finalSha256 = vAsset.sha256();
            } else if (bodyOutcome instanceof ChecksumOutcome.Valid vBody) {
                finalSha256 = vBody.sha256();
            } else {
                logger.warning("No published checksum found for " + selectedJarName + "; refusing without staging.");
                return ParseResult.failure("No published checksum found for " + selectedJarName);
            }

            if (!isValidSha256(finalSha256)) {
                logger.warning("Malformed published SHA-256 checksum for " + selectedJarName + "; refusing.");
                return ParseResult.failure("Malformed published SHA-256 checksum for " + selectedJarName);
            }

            return ParseResult.success(new Release(version, jarDownloadUrl, finalSha256.toLowerCase(Locale.ROOT), notes));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to parse release JSON", e);
            return ParseResult.failure("Failed to parse release JSON");
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

                updateRateLimit(response);

                if (response.statusCode() == 200) {
                    remaining = Duration.between(Instant.now(), deadline);
                    if (remaining.isNegative() || remaining.isZero() || stopped.get()) {
                        return new ChecksumOutcome.InvalidOrAmbiguous("Checksum body read timed out");
                    }
                    String content = readStringCapped(response.body(), 65536, remaining);
                    return parseChecksumFileContent(content, targetJarName, isDedicated);
                } else if (response.statusCode() == 404) {
                    return new ChecksumOutcome.None();
                } else if (response.statusCode() == 403 || response.statusCode() == 429) {
                    throw new IOException("HTTP error fetching checksum asset from " + uri + ": rate limit exceeded (status " + response.statusCode() + ")");
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
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }

        String[] lines = content.split("\\r?\\n");
        String matchedSha = null;
        boolean hasConflict = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            // Pattern 1: BSD style whole-line: "SHA256 (filename) = <hex>"
            Matcher mBsd = BSD_DECL_PATTERN.matcher(line);
            if (mBsd.matches()) {
                String file = mBsd.group(1).trim();
                String candidateHex = mBsd.group(2).trim();
                ChecksumDeclaration decl = new ChecksumDeclaration(DeclarationFormat.BSD, candidateHex, file);
                ChecksumOutcome validation = validateDeclaration(decl, "checksum file");
                if (validation != null) {
                    return validation;
                }
                String filename = normalizeFilename(file);
                if (filename != null && filename.equalsIgnoreCase(targetJarName)) {
                    if (matchedSha != null && !matchedSha.equalsIgnoreCase(candidateHex)) {
                        hasConflict = true;
                    }
                    matchedSha = candidateHex;
                }
                continue;
            }

            // Pattern 2: standard sha256sum: "<hex> [* ]<filename>"
            Matcher mSum = SUM_DECL_PATTERN.matcher(line);
            if (mSum.matches()) {
                String candidateHex = mSum.group(1).trim();
                String file = mSum.group(2).trim();
                ChecksumDeclaration decl = new ChecksumDeclaration(DeclarationFormat.SUM, candidateHex, file);
                ChecksumOutcome validation = validateDeclaration(decl, "checksum file");
                if (validation != null) {
                    return validation;
                }
                String filename = normalizeFilename(file);
                if (filename != null && filename.equalsIgnoreCase(targetJarName)) {
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
                    continue;
                } else {
                    return new ChecksumOutcome.InvalidOrAmbiguous("Malformed SHA-256 in dedicated checksum file: " + line);
                }
            }

            // Any unconsumed non-comment text in a checksum file is an invalid declaration (F2-A)
            return new ChecksumOutcome.InvalidOrAmbiguous("Malformed or unrecognized declaration in checksum file: " + line);
        }

        if (hasConflict) {
            return new ChecksumOutcome.InvalidOrAmbiguous("Multiple conflicting hashes for " + targetJarName + " in checksum asset");
        }

        return matchedSha != null ? new ChecksumOutcome.Valid(matchedSha.toLowerCase(Locale.ROOT)) : new ChecksumOutcome.None();
    }

    private ChecksumOutcome extractSha256FromBody(String body, String targetJarName, Set<String> releaseAssetNames) {
        if (body == null || body.isBlank()) {
            return new ChecksumOutcome.None();
        }
        if (body.startsWith("\uFEFF")) {
            body = body.substring(1);
        }

        List<String> boundHashes = new ArrayList<>();
        List<String> otherArtifactHashes = new ArrayList<>();
        // A line that names a checksum but yields no usable hash makes the whole release
        // unusable: the publisher meant to declare one and it cannot be read.
        boolean foundChecksumKeyword = false;

        for (String rawLine : body.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                line = line.substring(2).trim();
            }
            boolean stripped;
            do {
                stripped = false;
                if (line.startsWith("`") && line.endsWith("`") && line.length() >= 2) {
                    line = line.substring(1, line.length() - 1).trim();
                    stripped = true;
                } else if (line.startsWith("\"") && line.endsWith("\"") && line.length() >= 2) {
                    line = line.substring(1, line.length() - 1).trim();
                    stripped = true;
                } else if (line.startsWith("'") && line.endsWith("'") && line.length() >= 2) {
                    line = line.substring(1, line.length() - 1).trim();
                    stripped = true;
                }
            } while (stripped && !line.isEmpty());
            if (line.isEmpty()) {
                continue;
            }

            // 1. BSD format declarations on the line: SHA256 (filename) = <token>
            Matcher bsdMatcher = BSD_DECL_PATTERN.matcher(line);
            int bsdCount = 0;
            while (bsdMatcher.find()) {
                bsdCount++;
                String file = bsdMatcher.group(1).trim();
                String candidateToken = bsdMatcher.group(2).trim();

                ChecksumDeclaration decl = new ChecksumDeclaration(DeclarationFormat.BSD, candidateToken, file);
                ChecksumOutcome validation = validateDeclaration(decl, "body");
                if (validation != null) {
                    return validation;
                }

                String filename = normalizeFilename(file);
                if (filename != null && filename.equalsIgnoreCase(targetJarName)) {
                    boundHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                } else {
                    otherArtifactHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                }
            }

            // 2. sha256sum format declaration on the line: <token> [*]<file>
            Matcher sumMatcher = SUM_DECL_PATTERN.matcher(line);
            boolean sumMatchedOnLine = false;
            String sumConsumedFile = null;
            if (sumMatcher.matches()) {
                String candidateToken = sumMatcher.group(1).trim();
                String file = sumMatcher.group(2).trim();

                // If candidateToken is a checksum label keyword (e.g. SHA-256:, SHA-256=<H>), this is a labeled line, not a sha256sum line
                if (!candidateToken.matches("(?i)^sha-?256(?:sum)?(?:[:=].*)?$")) {
                    if (isSupportedSumFilename(file, targetJarName, releaseAssetNames)) {
                        ChecksumDeclaration decl = new ChecksumDeclaration(DeclarationFormat.SUM, candidateToken, file);
                        ChecksumOutcome validation = validateDeclaration(decl, "body");
                        if (validation != null) {
                            return validation;
                        }

                        String filename = normalizeFilename(file);
                        if (filename != null && filename.equalsIgnoreCase(targetJarName)) {
                            boundHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                        } else {
                            otherArtifactHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                        }
                        sumMatchedOnLine = true;
                        sumConsumedFile = file;
                    }
                }
            }

            // 3. Labeled declarations and remaining keyword audit
            // If BSD declarations were found on this line, mask them out before checking for labeled declarations
            String lineToCheck = bsdCount > 0
                    ? BSD_DECL_PATTERN.matcher(line).replaceAll(" ")
                    : line;
            // Audit other declarations without reinterpreting consumed sum filenames as declarations (F12)
            if (sumMatchedOnLine && sumConsumedFile != null) {
                lineToCheck = lineToCheck.replace(sumConsumedFile, " ");
            }

            // Count total keyword occurrences on lineToCheck, excluding directory paths like sha256/ or sha256.txt/
            Matcher allKeywords = KEYWORD_AUDIT_PATTERN.matcher(lineToCheck);
            int totalKeywords = 0;
            while (allKeywords.find()) {
                totalKeywords++;
            }
            if (totalKeywords > 0) {
                foundChecksumKeyword = true;
            }

            if (totalKeywords > 0) {
                // Find all labeled declarations on the line (excluding BSD which is followed by '(' and paths with slashes)
                Matcher labelMatcher = LABELED_DECL_PATTERN.matcher(lineToCheck);
                record Decl(int start, int end, String token) {}
                List<Decl> decls = new ArrayList<>();
                while (labelMatcher.find()) {
                    decls.add(new Decl(labelMatcher.start(), labelMatcher.end(), labelMatcher.group(1)));
                }

                if (decls.size() < totalKeywords) {
                    return new ChecksumOutcome.InvalidOrAmbiguous("Malformed SHA-256 declaration in body: " + line);
                }

                for (int i = 0; i < decls.size(); i++) {
                    Decl decl = decls.get(i);
                    String candidateToken = decl.token();

                    int segStart = (i == 0) ? 0 : decl.start();
                    int segEnd = (i == decls.size() - 1) ? lineToCheck.length() : decls.get(i + 1).start();
                    String segment = lineToCheck.substring(segStart, segEnd);

                    Matcher jarMatcher = JAR_NAME_PATTERN.matcher(segment);
                    String namedJar = jarMatcher.find() ? jarMatcher.group(1) : null;

                    ChecksumDeclaration checksumDecl = new ChecksumDeclaration(DeclarationFormat.LABELED, candidateToken, namedJar);
                    ChecksumOutcome validation = validateDeclaration(checksumDecl, "body");
                    if (validation != null) {
                        return validation;
                    }

                    if (namedJar != null) {
                        String filename = normalizeFilename(namedJar);
                        if (filename != null && filename.equalsIgnoreCase(targetJarName)) {
                            boundHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                        } else {
                            otherArtifactHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                        }
                    } else {
                        boundHashes.add(candidateToken.toLowerCase(Locale.ROOT));
                    }
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
                        // The Discord log channel is part of the log boundary, which spec 9.1
                        // keeps in English whatever the players read.
                        Messages msgs = EnglishMessages.bundled();
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
