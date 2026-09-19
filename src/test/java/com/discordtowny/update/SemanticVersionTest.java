package com.discordtowny.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class SemanticVersionTest {

    @Test
    @DisplayName("Two-digit minor comparison: 1.10.0 is newer than 1.9.0")
    void twoDigitMinorIsNewerThanSingleDigitMinor() {
        SemanticVersion v110 = SemanticVersion.parse("1.10.0");
        SemanticVersion v19 = SemanticVersion.parse("1.9.0");

        assertTrue(v110.isNewerThan(v19), "1.10.0 must be newer than 1.9.0");
        assertFalse(v19.isNewerThan(v110), "1.9.0 must not be newer than 1.10.0");
        assertTrue(v110.compareTo(v19) > 0);
        assertTrue(v19.compareTo(v110) < 0);
    }

    @Test
    @DisplayName("Equal versions: 1.0.0 is not newer than 1.0.0")
    void equalVersionsAreNotNewer() {
        SemanticVersion v1 = SemanticVersion.parse("1.0.0");
        SemanticVersion v2 = SemanticVersion.parse("1.0.0");

        assertFalse(v1.isNewerThan(v2), "Equal version must not be newer");
        assertFalse(v2.isNewerThan(v1), "Equal version must not be newer");
        assertEquals(0, v1.compareTo(v2));
        assertEquals(v1, v2);
    }

    @Test
    @DisplayName("Equal versions with v prefix: v1.0.0 equals 1.0.0")
    void vPrefixIsNormalized() {
        SemanticVersion withV = SemanticVersion.parse("v1.0.0");
        SemanticVersion withoutV = SemanticVersion.parse("1.0.0");

        assertEquals(0, withV.compareTo(withoutV));
        assertFalse(withV.isNewerThan(withoutV));
        assertFalse(withoutV.isNewerThan(withV));
    }

    @Test
    @DisplayName("Older versions: 1.8.0 is not newer than 1.9.0")
    void olderVersionIsNotNewer() {
        SemanticVersion v18 = SemanticVersion.parse("1.8.0");
        SemanticVersion v19 = SemanticVersion.parse("1.9.0");

        assertFalse(v18.isNewerThan(v19), "1.8.0 must not be newer than 1.9.0");
        assertTrue(v19.isNewerThan(v18), "1.9.0 must be newer than 1.8.0");
    }

    @Test
    @DisplayName("Release is newer than pre-release of same version: 1.0.0 is newer than 1.0.0-SNAPSHOT")
    void releaseIsNewerThanSnapshot() {
        SemanticVersion release = SemanticVersion.parse("1.0.0");
        SemanticVersion snapshot = SemanticVersion.parse("1.0.0-SNAPSHOT");

        assertTrue(release.isNewerThan(snapshot), "1.0.0 must be newer than 1.0.0-SNAPSHOT");
        assertFalse(snapshot.isNewerThan(release), "1.0.0-SNAPSHOT must not be newer than 1.0.0");
    }

    @ParameterizedTest(name = "{0} newer than {1} should be {2}")
    @CsvSource({
            "1.10.0, 1.9.0, true",
            "1.9.0, 1.10.0, false",
            "1.0.0, 1.0.0, false",
            "1.0.1, 1.0.0, true",
            "1.0.0, 1.0.1, false",
            "2.0.0, 1.99.99, true",
            "1.20.5, 1.2.99, true",
            "0.1.0, 0.1.0-SNAPSHOT, true",
            "0.2.0-SNAPSHOT, 0.1.0, true",
            "1.0.0.1, 1.0.0.0, true",
            "1.0.0.0, 1.0.0.1, false"
    })
    void versionComparisonMatrix(String v1, String v2, boolean expected) {
        SemanticVersion parsed1 = SemanticVersion.parse(v1);
        SemanticVersion parsed2 = SemanticVersion.parse(v2);
        assertEquals(expected, parsed1.isNewerThan(parsed2),
                () -> v1 + " isNewerThan " + v2 + " should be " + expected);
    }

    @Test
    @DisplayName("Null and blank input gracefully fallback to 0.0.0")
    void nullAndBlankFallbackGracefully() {
        SemanticVersion nullVer = SemanticVersion.parse(null);
        SemanticVersion blankVer = SemanticVersion.parse("   ");

        assertEquals(0, nullVer.major());
        assertEquals(0, nullVer.minor());
        assertEquals(0, nullVer.patch());

        assertEquals(0, blankVer.major());
        assertEquals(0, blankVer.minor());
        assertEquals(0, blankVer.patch());

        assertFalse(nullVer.isNewerThan(blankVer));
    }
}
