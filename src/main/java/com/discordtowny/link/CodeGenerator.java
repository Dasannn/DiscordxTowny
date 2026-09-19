package com.discordtowny.link;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Cryptographically secure generator of link codes.
 *
 * <p>Generates 6-character alphanumeric codes without ambiguous characters
 * (0, O, 1, I, l, L are excluded). Uses {@link SecureRandom}, never {@link java.util.Random},
 * to prevent codes from being predictable.
 *
 * <p>Entropy analysis and residual risk of distributed brute force (finding 13):
 * The alphabet consists of 31 symbols. A 6-symbol code produces 31^6 = 887,503,681
 * possible combinations (approximately 29.73 bits of entropy). With the per-user
 * limit of 5 attempts per window (AttemptTracker), the probability of guessing a
 * specific code is only 5 / 887,503,681 ≈ 5.63 x 10^-9. However, in a
 * distributed attack using a thousand simultaneous Discord accounts, the collective budget
 * reaches 5,000 attempts (probability ≈ 5.63 x 10^-6 against a particular code,
 * multiplied linearly if there are M concurrent active codes). This residual risk
 * is documented so that the architect can evaluate whether an aggregate global
 * defense per window is required in the future.
 */
public final class CodeGenerator {

    /**
     * Alphabet of 31 non-ambiguous alphanumeric characters:
     * Digits: 2-9 (without 0 or 1)
     * Letters: A-Z (without I, L, O)
     */
    public static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    public static final int CODE_LENGTH = 6;

    private final SecureRandom random;

    public CodeGenerator() {
        this(new SecureRandom());
    }

    public CodeGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Generates a new link code.
     */
    public String nextCode() {
        char[] chars = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            chars[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(chars);
    }

    /**
     * Checks whether a character is considered ambiguous (0, O, 1, I, l, L).
     */
    public static boolean isAmbiguous(char c) {
        return c == '0' || c == 'O' || c == 'o'
                || c == '1' || c == 'I' || c == 'i'
                || c == 'l' || c == 'L';
    }

    /**
     * Checks whether a string contains any ambiguous character.
     */
    public static boolean containsAmbiguousCharacters(String code) {
        if (code == null) return false;
        for (int i = 0; i < code.length(); i++) {
            if (isAmbiguous(code.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
