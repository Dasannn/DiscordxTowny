package com.discordtowny.update;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic version representation supporting standard semver and common plugin version formats.
 *
 * <p>Supports:
 * <ul>
 *   <li>Standard {@code major.minor.patch} (e.g. {@code 1.10.0})</li>
 *   <li>Two-digit or multi-digit components (e.g. {@code 1.10.0} &gt; {@code 1.9.0})</li>
 *   <li>Optional {@code v} prefix (e.g. {@code v1.2.3})</li>
 *   <li>Pre-release identifiers (e.g. {@code 1.0.0-SNAPSHOT}, {@code 1.0.0-beta.1})</li>
 *   <li>Four-part build numbers (e.g. {@code 1.2.3.4})</li>
 * </ul>
 */
public final class SemanticVersion implements Comparable<SemanticVersion> {

    private static final Pattern SEMVER_PATTERN = Pattern.compile(
            "^[vV]?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([a-zA-Z0-9.-]+))?(?:\\+([a-zA-Z0-9.-]+))?$"
    );

    private static final Pattern FALLBACK_DIGITS = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    private final int major;
    private final int minor;
    private final int patch;
    private final int build;
    private final String preRelease;
    private final String raw;

    public SemanticVersion(int major, int minor, int patch, int build, String preRelease, String raw) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.build = build;
        this.preRelease = preRelease != null && !preRelease.isBlank() ? preRelease.trim() : null;
        this.raw = raw != null ? raw.trim() : "";
    }

    /**
     * Parses a version string into a {@link SemanticVersion}.
     *
     * <p>If the version string is null, empty, or unparseable, returns a fallback
     * version {@code 0.0.0} rather than throwing an exception.
     */
    public static SemanticVersion parse(String versionString) {
        if (versionString == null || versionString.isBlank()) {
            return new SemanticVersion(0, 0, 0, 0, null, "");
        }
        String clean = versionString.trim();
        Matcher matcher = SEMVER_PATTERN.matcher(clean);
        if (matcher.matches()) {
            int major = Integer.parseInt(matcher.group(1));
            int minor = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
            int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
            int build = matcher.group(4) != null ? Integer.parseInt(matcher.group(4)) : 0;
            String preRelease = matcher.group(5);
            return new SemanticVersion(major, minor, patch, build, preRelease, clean);
        }

        // Fallback for non-standard version strings (e.g. arbitrary text containing digits)
        Matcher fallback = FALLBACK_DIGITS.matcher(clean);
        if (fallback.find()) {
            int major = Integer.parseInt(fallback.group(1));
            int minor = fallback.group(2) != null ? Integer.parseInt(fallback.group(2)) : 0;
            int patch = fallback.group(3) != null ? Integer.parseInt(fallback.group(3)) : 0;
            return new SemanticVersion(major, minor, patch, 0, null, clean);
        }

        return new SemanticVersion(0, 0, 0, 0, clean, clean);
    }

    /**
     * Returns true if this version is strictly newer than {@code other}.
     */
    public boolean isNewerThan(SemanticVersion other) {
        if (other == null) {
            return true;
        }
        return this.compareTo(other) > 0;
    }

    @Override
    public int compareTo(SemanticVersion other) {
        if (other == null) {
            return 1;
        }
        if (this.major != other.major) {
            return Integer.compare(this.major, other.major);
        }
        if (this.minor != other.minor) {
            return Integer.compare(this.minor, other.minor);
        }
        if (this.patch != other.patch) {
            return Integer.compare(this.patch, other.patch);
        }
        if (this.build != other.build) {
            return Integer.compare(this.build, other.build);
        }

        // Pre-release versions: a release without pre-release tag has higher precedence than with one
        boolean thisHasPre = this.preRelease != null && !this.preRelease.isEmpty();
        boolean otherHasPre = other.preRelease != null && !other.preRelease.isEmpty();

        if (!thisHasPre && otherHasPre) {
            return 1; // 1.0.0 > 1.0.0-SNAPSHOT
        }
        if (thisHasPre && !otherHasPre) {
            return -1; // 1.0.0-SNAPSHOT < 1.0.0
        }
        if (thisHasPre && otherHasPre) {
            return comparePreRelease(this.preRelease, other.preRelease);
        }

        return 0;
    }

    private static int comparePreRelease(String preA, String preB) {
        if (Objects.equals(preA, preB)) {
            return 0;
        }
        String[] partsA = preA.split("\\.");
        String[] partsB = preB.split("\\.");
        int minLen = Math.min(partsA.length, partsB.length);

        for (int i = 0; i < minLen; i++) {
            String a = partsA[i];
            String b = partsB[i];
            if (a.equals(b)) {
                continue;
            }

            boolean aIsNumeric = isNumeric(a);
            boolean bIsNumeric = isNumeric(b);

            if (aIsNumeric && bIsNumeric) {
                try {
                    int numA = Integer.parseInt(a);
                    int numB = Integer.parseInt(b);
                    int cmp = Integer.compare(numA, numB);
                    if (cmp != 0) {
                        return cmp;
                    }
                } catch (NumberFormatException e) {
                    java.math.BigInteger bigA = new java.math.BigInteger(a);
                    java.math.BigInteger bigB = new java.math.BigInteger(b);
                    int cmp = bigA.compareTo(bigB);
                    if (cmp != 0) {
                        return cmp;
                    }
                }
            } else if (aIsNumeric) {
                // Numeric identifiers always have lower precedence than non-numeric identifiers
                return -1;
            } else if (bIsNumeric) {
                return 1;
            } else {
                // Non-numeric compared lexically in ASCII sort order
                int cmp = a.compareTo(b);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }

        // A larger set of pre-release fields has a higher precedence than a smaller set
        return Integer.compare(partsA.length, partsB.length);
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    public int build() {
        return build;
    }

    public String preRelease() {
        return preRelease;
    }

    public String raw() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SemanticVersion that)) return false;
        return major == that.major
                && minor == that.minor
                && patch == that.patch
                && build == that.build
                && Objects.equals(preRelease, that.preRelease);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, build, preRelease);
    }

    @Override
    public String toString() {
        return raw.isEmpty() ? major + "." + minor + "." + patch : raw;
    }
}
