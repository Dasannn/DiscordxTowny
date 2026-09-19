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

    CompletableFuture<Optional<Release>> checkForUpdate();

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

    record Release(String version, String downloadUrl, String sha256, String notes) {}

    enum DownloadResult {
        SUCCESS,
        CHECKSUM_MISMATCH,
        NETWORK_ERROR,
        TOO_LARGE,
        IO_ERROR
    }
}
