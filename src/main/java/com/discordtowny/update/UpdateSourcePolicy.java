package com.discordtowny.update;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Enforces the official source policy for all updater network requests and downloads.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Scheme must strictly be HTTPS on port 443 with no user-info.</li>
 *   <li>Metadata requests must target the official repository API endpoint on api.github.com.</li>
 *   <li>Release asset requests must target the official repository on github.com.</li>
 *   <li>Redirects are strictly constrained to the official repository's own assets on permitted delivery hosts.</li>
 *   <li>Paths are validated after decoding and segment normalization to prevent traversal or alias escapes.</li>
 * </ul>
 */
final class UpdateSourcePolicy {

    public static final String OFFICIAL_OWNER = DefaultUpdateService.REPOSITORY_OWNER;
    public static final String OFFICIAL_REPO = DefaultUpdateService.REPOSITORY_NAME;

    public static final Set<String> ALLOWED_DELIVERY_HOSTS = Set.of(
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com"
    );

    private UpdateSourcePolicy() {}

    /**
     * Checks if a destination URI is permitted for an initial request (metadata or initial asset download).
     * Must strictly target the official repository on api.github.com or github.com.
     */
    public static boolean isAllowedInitialUri(URI uri) {
        if (!isBasicHttpsValid(uri)) {
            return false;
        }
        return isAllowedApiUri(uri) || isAllowedAssetUri(uri);
    }

    /**
     * Checks if a destination URI is permitted for metadata, asset download, or redirect hops.
     */
    public static boolean isAllowedDestination(URI uri) {
        if (uri == null) {
            return false;
        }
        return isAllowedInitialUri(uri);
    }

    /**
     * Checks if an initial asset download URI is permitted (must strictly be official repository release asset).
     */
    public static boolean isAllowedDownloadDestination(URI uri) {
        if (uri == null) {
            return false;
        }
        return isAllowedAssetUri(uri);
    }

    /**
     * Validates that an initial destination URI is permitted, throwing {@link IOException} if outside policy.
     */
    public static void validateInitialUri(URI uri) throws IOException {
        if (!isAllowedInitialUri(uri)) {
            throw new IOException("Untrusted destination rejected by official source policy: " + uri);
        }
    }

    /**
     * Validates that a redirect hop destination URI is permitted, throwing {@link IOException} if outside policy.
     */
    public static void validateRedirectDestination(URI uri) throws IOException {
        if (!isAllowedRedirectDestination(uri)) {
            throw new IOException("Untrusted destination rejected by official source policy: " + uri);
        }
    }

    /**
     * Validates that the URI is permitted, throwing {@link IOException} if outside policy.
     */
    public static void validateDestination(URI uri) throws IOException {
        validateInitialUri(uri);
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
        List<String> segments = decodeAndNormalizeSegments(uri);
        if (segments == null || segments.size() < 4) {
            return false;
        }
        return "repos".equals(segments.get(0))
                && OFFICIAL_OWNER.equalsIgnoreCase(segments.get(1))
                && OFFICIAL_REPO.equalsIgnoreCase(segments.get(2))
                && "releases".equals(segments.get(3));
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
        List<String> segments = decodeAndNormalizeSegments(uri);
        if (segments == null || segments.size() < 4) {
            return false;
        }
        return OFFICIAL_OWNER.equalsIgnoreCase(segments.get(0))
                && OFFICIAL_REPO.equalsIgnoreCase(segments.get(1))
                && "releases".equals(segments.get(2))
                && "download".equals(segments.get(3));
    }

    /**
     * Checks if the URI is a permitted redirect destination hop from an authorized origin.
     * Allowed hops include official API URIs, official release asset URIs on github.com,
     * and exact known GitHub delivery hosts.
     */
    public static boolean isAllowedRedirectDestination(URI uri) {
        if (!isBasicHttpsValid(uri)) {
            return false;
        }
        return isAllowedApiUri(uri) || isAllowedAssetUri(uri) || isAllowedDeliveryRedirectUri(uri);
    }

    /**
     * Checks if the URI is a valid official GitHub asset delivery redirect host.
     * Host must exactly match one of the known delivery hosts.
     */
    public static boolean isAllowedDeliveryRedirectUri(URI uri) {
        if (!isBasicHttpsValid(uri)) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        return ALLOWED_DELIVERY_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private static List<String> decodeAndNormalizeSegments(URI uri) {
        if (uri == null) {
            return null;
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null) {
            String lowerRaw = rawPath.toLowerCase(Locale.ROOT);
            if (lowerRaw.contains("%2f") || lowerRaw.contains("%00")) {
                return null;
            }
        }
        String path = uri.getPath();
        if (path == null) {
            return null;
        }
        String[] rawSegments = path.split("/+");
        List<String> segments = new ArrayList<>();
        for (String rawSegment : rawSegments) {
            if (rawSegment.isEmpty() || ".".equals(rawSegment)) {
                continue;
            }
            if ("..".equals(rawSegment)) {
                if (!segments.isEmpty()) {
                    segments.remove(segments.size() - 1);
                } else {
                    return null;
                }
            } else {
                segments.add(rawSegment.toLowerCase(Locale.ROOT));
            }
        }
        return segments;
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
