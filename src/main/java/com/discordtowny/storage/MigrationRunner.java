package com.discordtowny.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Applies numbered schema migrations at startup.
 *
 * <p>Maintains a {@code schema_version} table with the number of the last
 * applied migration. Each migration is idempotent: if it was already applied,
 * it is skipped. Migrations execute in ascending order, within one transaction
 * per migration so that a partial failure is recoverable.
 */
final class MigrationRunner {

    private final String prefix;
    private final Logger logger;
    private final boolean isSqlite;

    MigrationRunner(String prefix, Logger logger) {
        this(prefix, logger, true);
    }

    MigrationRunner(String prefix, Logger logger, boolean isSqlite) {
        this.prefix = prefix;
        this.logger = logger;
        this.isSqlite = isSqlite;
    }

    /**
     * Creates the version table if needed and applies all pending
     * migrations.
     *
     * @throws StorageException if any migration fails.
     */
    void run(Connection conn) throws StorageException {
        try {
            boolean sqlite = this.isSqlite;
            try {
                String prod = conn.getMetaData().getDatabaseProductName();
                if (prod != null) {
                    String lower = prod.toLowerCase();
                    if (lower.contains("sqlite")) {
                        sqlite = true;
                    } else if (lower.contains("mysql") || lower.contains("mariadb")) {
                        sqlite = false;
                    }
                }
            } catch (SQLException ignored) {
            }

            ensureVersionTable(conn);
            int current = currentVersion(conn);
            applyPending(conn, current, sqlite);
        } catch (SQLException e) {
            throw new StorageException("Failed to execute schema migrations", e);
        }
    }

    // --- version table ---

    private void ensureVersionTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                    "CREATE TABLE IF NOT EXISTS " + prefix + "schema_version ("
                    + "version INTEGER NOT NULL,"
                    + "applied_at BIGINT NOT NULL"
                    + ")");
        }
    }

    private int currentVersion(Connection conn) throws SQLException {
        String sql = "SELECT MAX(version) FROM " + prefix + "schema_version";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                int v = rs.getInt(1);
                return rs.wasNull() ? 0 : v;
            }
            return 0;
        }
    }

    // --- migrations ---

    private void applyPending(Connection conn, int current, boolean sqlite) throws SQLException {
        Migration[] migrations = migrations(sqlite);
        for (Migration m : migrations) {
            if (m.version() > current) {
                logger.info("Applying migration v" + m.version() + "...");
                applyOne(conn, m);
            }
        }
    }

    private void applyOne(Connection conn, Migration m) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            for (String sql : m.statements()) {
                st.execute(sql);
            }
            st.execute(
                    "INSERT INTO " + prefix + "schema_version (version, applied_at) VALUES ("
                    + m.version() + ", " + System.currentTimeMillis() + ")");
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw new StorageException("Migration v" + m.version() + " failed", e);
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    // --- migration definitions ---

    Migration[] migrations(boolean sqlite) {
        return new Migration[]{
            migration1Links(),
            migration2LinkCodes(),
            migration3Spaces(),
            migration4AuditLog(sqlite),
            migration5Settings()
        };
    }

    /**
     * v1: links table.
     * - uuid as primary key.
     * - unique discord_id: one Discord ID to one UUID and vice versa.
     * - Uniqueness is guaranteed in the schema, not merely by checking beforehand.
     */
    private Migration migration1Links() {
        return new Migration(1, new String[]{
            "CREATE TABLE IF NOT EXISTS " + prefix + "links ("
            + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
            + "discord_id VARCHAR(32) NOT NULL UNIQUE,"
            + "linked_at BIGINT NOT NULL,"
            + "last_known_name VARCHAR(255) NOT NULL"
            + ")"
        });
    }

    /**
     * v2: link_codes table.
     * - code as primary key.
     * - unique uuid: one active code per player. If the player generates a new one,
     *   the previous one is deleted before inserting (see SqlLinkRepository).
     */
    private Migration migration2LinkCodes() {
        return new Migration(2, new String[]{
            "CREATE TABLE IF NOT EXISTS " + prefix + "link_codes ("
            + "code VARCHAR(64) NOT NULL PRIMARY KEY,"
            + "uuid VARCHAR(36) NOT NULL UNIQUE,"
            + "expires_at BIGINT NOT NULL,"
            + "attempts INTEGER NOT NULL DEFAULT 0"
            + ")"
        });
    }

    /**
     * v5: settings table.
     *
     * <p>State that the plugin writes to itself between restarts. This is not
     * administrator configuration: that lives in config.yml.
     */
    private Migration migration5Settings() {
        return new Migration(5, new String[]{
            "CREATE TABLE IF NOT EXISTS " + prefix + "settings ("
            + "setting_key VARCHAR(64) NOT NULL PRIMARY KEY,"
            + "value VARCHAR(255) NOT NULL"
            + ")"
        });
    }

    /**
     * v3: spaces table.
     * - town_uuid as primary key.
     * - Discord IDs are optional (NULL): a creation may be half-finished
     *   and each ID is persisted as soon as it exists.
     */
    private Migration migration3Spaces() {
        return new Migration(3, new String[]{
            "CREATE TABLE IF NOT EXISTS " + prefix + "spaces ("
            + "town_uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
            + "town_name VARCHAR(255) NOT NULL,"
            + "category_id VARCHAR(32),"
            + "text_channel_id VARCHAR(32),"
            + "voice_channel_id VARCHAR(32),"
            + "role_id VARCHAR(32),"
            + "state VARCHAR(32) NOT NULL,"
            + "created_at BIGINT NOT NULL,"
            + "archived_at BIGINT,"
            + "last_activity_at BIGINT"
            + ")"
        });
    }

    /**
     * v4: audit_log table.
     * - auto-incrementing id according to the engine (AUTOINCREMENT in SQLite, AUTO_INCREMENT in MySQL/MariaDB).
     * - target indexed to speed up the recent(target, limit) query.
     */
    private Migration migration4AuditLog(boolean sqlite) {
        if (sqlite) {
            return new Migration(4, new String[]{
                "CREATE TABLE IF NOT EXISTS " + prefix + "audit_log ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "at BIGINT NOT NULL,"
                + "severity VARCHAR(16) NOT NULL,"
                + "actor VARCHAR(64) NOT NULL,"
                + "action VARCHAR(64) NOT NULL,"
                + "target VARCHAR(255) NOT NULL,"
                + "success INTEGER NOT NULL,"
                + "detail TEXT"
                + ")",
                "CREATE INDEX IF NOT EXISTS idx_" + prefix + "audit_target"
                + " ON " + prefix + "audit_log (target, at DESC)"
            });
        } else {
            return new Migration(4, new String[]{
                "CREATE TABLE IF NOT EXISTS " + prefix + "audit_log ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "at BIGINT NOT NULL,"
                + "severity VARCHAR(16) NOT NULL,"
                + "actor VARCHAR(64) NOT NULL,"
                + "action VARCHAR(64) NOT NULL,"
                + "target VARCHAR(255) NOT NULL,"
                + "success INT NOT NULL,"
                + "detail TEXT,"
                + "INDEX idx_" + prefix + "audit_target (target, at DESC)"
                + ")"
            });
        }
    }

    // --- helper type ---

    /** A numbered migration: version + SQL statements to execute in order. */
    record Migration(int version, String[] statements) {}
}
