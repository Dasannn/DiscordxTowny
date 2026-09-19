package com.discordtowny.update;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Enforces the official source policy for all updater network requests and downloads.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Scheme must strictly be HTTPS.</li>
 *   <li>Metadata requests must target the official repository API endpoint on api.github.com.</li>
 *   <li>Release asset requests must target the official repository on github.com.</li>
 *   <li>Redirects are strictly constrained to the official repository and permitted GitHub delivery hosts.</li>
 * </ul>
 */
final class UpdateSourcePolicy {

    public static final String OFFICIAL_OWNER = DefaultUpdateService.REPOSITORY_OWNER;
    public static final String OFFICIAL_REPO = DefaultUpdateService.REPOSITORY_NAME;

    private static final String API_PREFIX = "/repos/" + OFFICIAL_OWNER.toLowerCase(Locale.ROOT)
            + "/" + OFFICIAL_REPO.toLowerCase(Locale.ROOT) + "/releases";

    private static final String ASSET_PREFIX = "/" + OFFICIAL_OWNER.toLowerCase(Locale.ROOT)
            + "/" + OFFICIAL_REPO.toLowerCase(Locale.ROOT) + "/releases/download/";

    private static final Pattern S3_DELIVERY_HOST_PATTERN = Pattern.compile(
            "^github-production-release-asset-[a-z0-9]+\\.s3(\\.[a-z0-9-]+)?\\.amazonaws\\.com$"
    );

    private static final Pattern GITHUB_USER_CONTENT_PATTERN = Pattern.compile(
            "^[a-z0-9-]+\\.githubusercontent\\.com$"
    );

    private UpdateSourcePolicy() {}

    /**
     * Checks if a destination URI is permitted for metadata, asset download, or redirect hops.
     */
    public static boolean isAllowedDestination(URI uri) {
        if (uri == null) {
            return false;
        }
        return isAllowedApiUri(uri) || isAllowedAssetUri(uri) || isAllowedDeliveryRedirectUri(uri);
    }

    /**
     * Checks if an initial asset download URI is permitted (must be official repository asset download).
     */
    public static boolean isAllowedDownloadDestination(URI uri) {
        if (uri == null) {
            return false;
        }
        return isAllowedAssetUri(uri) || isAllowedDeliveryRedirectUri(uri);
    }

    /**
     * Validates that the URI is permitted, throwing {@link IOException} if outside policy.
     */
    public static void validateDestination(URI uri) throws IOException {
        if (!isAllowedDestination(uri)) {
            throw new IOException("Untrusted destination rejected by official source policy: " + uri);
        }
    }

    /**
     * Checks if the URI is a valid official GitHub Releases API URI.
     */
    public static boolean isAllowedApiUri(URI uri) {
        if (!isBasicHttpsValid(uri)) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!"api.github.com".equals(host)) {
            return false;
        }
        String path = uri.getPath();
        if (path == null) {
            return false;
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        return lowerPath.startsWith(API_PREFIX);
    }

    /**
     * Checks if the URI is a valid official GitHub release asset download URI.
     */
    public static boolean isAllowedAssetUri(URI uri) {
        if (!isBasicHttpsValid(uri)) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!"github.com".equals(host)) {
            return false;
        }
        String path = uri.getPath();
        if (path == null) {
            return false;
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        return lowerPath.startsWith(ASSET_PREFIX);
    }

    /**
     * Checks if the URI is a valid official GitHub asset delivery redirect host.
     */
    public static boolean isAllowedDeliveryRedirectUri(URI uri) {
        if (!isBasicHttpsValid(uri)) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (GITHUB_USER_CONTENT_PATTERN.matcher(host).matches()) {
            return true;
        }
        return S3_DELIVERY_HOST_PATTERN.matcher(host).matches();
    }

    private static boolean isBasicHttpsValid(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        if (uri.getUserInfo() != null) {
            return false;
        }
        return uri.getPort() == -1 || uri.getPort() == 443;
    }
}
