package com.discordtowny.storage;

/**
 * Database access.
 *
 * <p><b>All repository methods block.</b> They are called from the plugin's
 * pool, never from the server's main thread nor from a JDA thread. This is
 * preferred over returning futures everywhere: the code that consumes them
 * already runs off the main thread, and chaining futures would only add noise.
 *
 * <p>A single implementation serves both MySQL/MariaDB and SQLite: the code is
 * the same, only the connection string changes.
 */
public interface Storage extends AutoCloseable {

    /**
     * Opens the pool and applies pending migrations.
     *
     * @throws StorageException if connecting fails or a migration fails.
     *     Without a database the plugin does not operate on Discord: the guild
     *     is not touched without being able to persist the result.
     */
    void initialize() throws StorageException;

    LinkRepository links();

    /**
     * Internal plugin settings, as key-value pairs.
     *
     * <p>For the few things the plugin needs to remember and does not fit in any
     * table: for example the ID of the global mayor role, which must survive a
     * rename in Discord and a server restart.
     *
     * <p>Not for administrator configuration, which lives in config.yml.
     */
    SettingsRepository settings();

    SpaceRepository spaces();

    AuditRepository audit();

    /** True if the pool responds. Consulted by commands before operating. */
    boolean isHealthy();

    @Override
    void close();
}
