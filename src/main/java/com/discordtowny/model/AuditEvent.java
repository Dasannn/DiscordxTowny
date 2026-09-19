package com.discordtowny.model;

import java.time.Instant;
import java.util.Optional;

/**
 * Something that the plugin did or attempted to do.
 *
 * <p>It is persisted and, in parallel, enqueued to the Discord log channel.
 * The detail must NEVER contain the token or credentials.
 */
public record AuditEvent(
        Instant at,
        Severity severity,
        String actor,
        String action,
        String target,
        boolean success,
        Optional<String> detail) {

    public enum Severity {
        /** Normal operation: creations, role changes, links. */
        INFO,
        /** Something unexpected that does not prevent operating: retry, corrected state. */
        WARNING,
        /** Failed operation. */
        ERROR
    }
}
