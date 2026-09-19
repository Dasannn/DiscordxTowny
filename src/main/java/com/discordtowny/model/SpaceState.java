package com.discordtowny.model;

/** State of a town's Discord space. */
public enum SpaceState {
    /** Channels and role exist, and are synchronized. */
    ACTIVE,
    /** Read-only channels, moved to archive, role deleted. */
    ARCHIVED,
    /**
     * An operation failed midway. Reconciliation must pick it back up.
     * Never implies open permissions: when in doubt, access is not granted.
     */
    INCONSISTENT
}
