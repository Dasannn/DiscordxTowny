package com.discordtowny.update;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Checking and downloading new versions published on GitHub.
 *
 * <p>Independent of the rest of the plugin: neither reads nor writes its
 * state and can fail completely without affecting anything.
 *
 * <p>The download source is a <b>constant in the code</b>, never
 * configurable: an editable source would turn config.yml into arbitrary
 * code execution.
 *
 * <p>The new version is placed in the server's {@code update} folder and
 * takes effect upon restart. The jar in use is never touched: reloading a
 * plugin with live connections to Discord and the database corrupts state.
 */
public interface UpdateService {

    CompletableFuture<CheckResult> checkForUpdate();

    default CompletableFuture<Optional<Release>> checkForUpdateOptional() {
        return checkForUpdate().thenApply(CheckResult::release);
    }

    /**
     * Downloads the release, verifies its SHA-256 against the published checksum,
     * and only then places it in the {@code update} folder.
     *
     * <p>A checksum that does not match discards the download without leaving remnants.
     */
    CompletableFuture<DownloadResult> download(Release release);

    /** True if there is already a downloaded version waiting for restart. */
    boolean isUpdatePending();

    String currentVersion();

    /**
     * Returns the latest available release detected, if any.
     */
    Optional<Release> getAvailableUpdate();

    /**
     * Returns true if the release contains breaking configuration changes or requires migration.
     *
     * <p>A release counts as breaking when either its major version differs from the
     * running version, or its release notes contain {@code [breaking]}.
     */
    boolean isBreaking(Release release);

    /**
     * Returns true if there is an available update that is breaking and requires administrator
     * confirmation before it can be staged.
     */
    boolean isAwaitingConfirmation();

    /**
     * Returns true if the most recent update check failed to complete.
     */
    default boolean isLastCheckFailed() {
        return false;
    }

    /**
     * Returns the failure reason of the most recent update check, if it failed.
     */
    default Optional<String> getLastCheckError() {
        return Optional.empty();
    }

    /**
     * Distinct status outcomes for an update check.
     */
    enum CheckStatus {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        CHECK_FAILED,
        NOT_CHECKED
    }

    /**
     * Returns true if at least one update check has completed (successfully or with failure).
     */
    default boolean hasCheckedAtLeastOnce() {
        return false;
    }

    /**
     * Returns the outcome of the most recent update check as a coherent immutable record.
     */
    default CheckResult getLastCheckResult() {
        if (isLastCheckFailed()) {
            return CheckResult.checkFailed(getLastCheckError().orElse(null), getAvailableUpdate().orElse(null));
        }
        if (getAvailableUpdate().isPresent()) {
            return CheckResult.updateAvailable(getAvailableUpdate().get());
        }
        if (!hasCheckedAtLeastOnce()) {
            return CheckResult.notChecked(null);
        }
        return CheckResult.upToDate();
    }

    /**
     * Returns the current status of update checking.
     */
    default CheckStatus checkStatus() {
        return getLastCheckResult().status();
    }

    /**
     * Stops the periodic check and abandons any transfer in flight.
     *
     * <p>Part of the contract because the service owns a scheduler and a worker:
     * a caller holding only this interface must still be able to end it, or
     * disabling the plugin leaves both running.
     */
    void stop();

    record Release(String version, String downloadUrl, String sha256, String notes) {}

    enum DownloadResult {
        SUCCESS,
        CHECKSUM_MISMATCH,
        NETWORK_ERROR,
        TOO_LARGE,
        IO_ERROR
    }

    record CheckResult(CheckStatus status, Optional<Release> release, Optional<String> error) {
        public static CheckResult upToDate() {
            return new CheckResult(CheckStatus.UP_TO_DATE, Optional.empty(), Optional.empty());
        }

        public static CheckResult updateAvailable(Release release) {
            return new CheckResult(CheckStatus.UPDATE_AVAILABLE, Optional.of(release), Optional.empty());
        }

        public static CheckResult checkFailed(String error) {
            return new CheckResult(CheckStatus.CHECK_FAILED, Optional.empty(), Optional.ofNullable(error));
        }

        public static CheckResult checkFailed(String error, Release cachedRelease) {
            return new CheckResult(CheckStatus.CHECK_FAILED, Optional.ofNullable(cachedRelease), Optional.ofNullable(error));
        }

        public static CheckResult notChecked(String reason) {
            return new CheckResult(CheckStatus.NOT_CHECKED, Optional.empty(), Optional.ofNullable(reason));
        }

        public boolean isSuccess() {
            return status == CheckStatus.UP_TO_DATE || status == CheckStatus.UPDATE_AVAILABLE;
        }

        public boolean isFailed() {
            return status == CheckStatus.CHECK_FAILED;
        }

        public boolean isNotChecked() {
            return status == CheckStatus.NOT_CHECKED;
        }

        public Optional<Release> getRelease() {
            return release;
        }

        public Optional<String> getError() {
            return error;
        }

        public CheckStatus getStatus() {
            return status;
        }
    }
}
