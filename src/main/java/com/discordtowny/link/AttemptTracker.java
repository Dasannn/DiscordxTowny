package com.discordtowny.link;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Control of failed redemption attempts per Discord user.
 *
 * <p>Prevents brute-force attacks on the code space. After reaching the
 * limit of failed attempts within the configured time window, the user
 * is locked out for the lockout duration.
 *
 * <p>Note on security and residual risk of distributed brute force (finding 13):
 * The code alphabet uses 31 non-ambiguous alphanumeric symbols (excluding
 * 0, O, o, 1, I, i, l, L). With codes of length 6, the total space is
 * 31^6 = 887,503,681 combinations (approximately 29.73 bits of entropy).
 * An individual limit of 5 attempts per user provides a success probability
 * of only 5 / 887,503,681 ≈ 5.63 x 10^-9 against a specific active code.
 * However, against an attacker capable of coordinating multiple Discord accounts
 * (for example, 1,000 Discord accounts), the aggregate budget rises
 * to 5,000 attempts and the probability rises to ≈ 5.63 x 10^-6 against a
 * particular code (and grows roughly with M concurrent active codes).
 * This implementation strictly protects the per-individual-account budget
 * during the time window. If mitigating massive distributed attacks across
 * multiple accounts is required, an aggregate global defense per window
 * must be submitted to the architect for approval.
 */
final class AttemptTracker {

    private static final class UserAttempts {
        final List<Instant> failureTimestamps = new ArrayList<>();
        Instant lockedUntil;
    }

    private final ConcurrentHashMap<String, UserAttempts> attempts = new ConcurrentHashMap<>();

    /**
     * Checks whether the Discord user is locked out at this instant.
     */
    boolean isLocked(String discordId, Instant now) {
        if (discordId == null) {
            return false;
        }
        UserAttempts user = attempts.get(discordId);
        if (user == null) {
            return false;
        }
        synchronized (user) {
            if (user.lockedUntil != null) {
                if (now.isBefore(user.lockedUntil)) {
                    return true;
                }
                // Lockout expired: remove the lockout and clear previous timestamps
                user.lockedUntil = null;
                user.failureTimestamps.clear();
            }
            return false;
        }
    }

    /**
     * Records a failed attempt and returns true if the user is locked out.
     * Keeps failure timestamps within the lockoutDuration time window no
     * matter what, without being reset by linkings or unlinkings.
     */
    boolean recordFailure(String discordId, Instant now, int maxAttempts, Duration lockoutDuration) {
        if (discordId == null) {
            return false;
        }
        UserAttempts user = attempts.computeIfAbsent(discordId, k -> new UserAttempts());
        synchronized (user) {
            if (user.lockedUntil != null && now.isBefore(user.lockedUntil)) {
                return true;
            }

            // Purge timestamps prior to the time window (now - lockoutDuration)
            Instant windowStart = now.minus(lockoutDuration);
            user.failureTimestamps.removeIf(t -> t.isBefore(windowStart));

            user.failureTimestamps.add(now);

            if (user.failureTimestamps.size() >= maxAttempts) {
                user.lockedUntil = now.plus(lockoutDuration);
                return true;
            }
            return false;
        }
    }

    /**
     * No longer clears the failure budget on successful redemptions.
     * The method is preserved as a no-op to ensure that the failure budget
     * is maintained during its time window no matter what.
     */
    void clear(String discordId) {
        // Intentionally empty: the failure budget must be maintained
        // during the time window regardless of successful linkings.
    }
}
