package com.discordtowny.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Aplica las migraciones de esquema numeradas al arrancar.
 *
 * <p>Mantiene una tabla {@code schema_version} con el numero de la ultima
 * migracion aplicada. Cada migracion es idempotente: si ya se aplico, se
 * salta. Las migraciones se ejecutan en orden ascendente, dentro de una
 * transaccion por migracion para que un fallo parcial sea recuperable.
 */
final class MigrationRunner {

    private final String prefix;
    private final Logger logger;

    MigrationRunner(String prefix, Logger logger) {
        this.prefix = prefix;
        this.logger = logger;
    }

    /**
     * Crea la tabla de version si hace falta y aplica todas las migraciones
     * pendientes.
     *
     * @throws StorageException si alguna migracion falla.
     */
    void run(Connection conn) throws StorageException {
        try {
            ensureVersionTable(conn);
            int current = currentVersion(conn);
            applyPending(conn, current);
        } catch (SQLException e) {
            throw new StorageException("Error al ejecutar migraciones de esquema", e);
        }
    }

    // --- tabla de version ---

    private void ensureVersionTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                    "CREATE TABLE IF NOT EXISTS " + prefix + "schema_version ("
                    + "version INTEGER NOT NULL,"
                    + "applied_at INTEGER NOT NULL"
                    + ")");
        }
    }

    private int currentVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT MAX(version) FROM " + prefix + "schema_version")) {
            if (rs.next()) {
                int v = rs.getInt(1);
                return rs.wasNull() ? 0 : v;
            }
            return 0;
        }
    }

    // --- migraciones ---

    private void applyPending(Connection conn, int current) throws SQLException {
        Migration[] migrations = migrations();
        for (Migration m : migrations) {
            if (m.version() > current) {
                logger.info("Aplicando migracion v" + m.version() + "...");
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
            throw new StorageException("Fallo la migracion v" + m.version(), e);
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    // --- definicion de migraciones ---

    private Migration[] migrations() {
        return new Migration[]{
            migration1Links(),
            migration2LinkCodes(),
            migration3Spaces(),
            migration4AuditLog()
        };
    }

    /**
     * v1: tabla links.
     * - uuid como clave primaria (texto: representacion estandar de UUID).
     * - discord_id unico: un Discord ID a un UUID y viceversa.
     * - La unicidad se garantiza en el esquema, no solo comprobando antes.
     */
    private Migration migration1Links() {
        return new Migration(1, new String[]{
            "CREATE TABLE IF NOT EXISTS " + prefix + "links ("
            + "uuid TEXT NOT NULL PRIMARY KEY,"
            + "discord_id TEXT NOT NULL UNIQUE,"
            + "linked_at INTEGER NOT NULL,"
            + "last_known_name TEXT NOT NULL"
            + ")"
        });
    }

    /**
     * v2: tabla link_codes.
     * - code como clave primaria.
     * - uuid unico: un codigo vivo por jugador. Si el jugador genera uno nuevo,
     *   el anterior se borra antes de insertar (ver SqlLinkRepository).
     */
    private Migration migration2LinkCodes() {
        return new Migration(2, new String[]{
            "CREATE TABLE IF NOT EXISTS " + prefix + "link_codes ("
            + "code TEXT NOT NULL PRIMARY KEY,"
            + "uuid TEXT NOT NULL UNIQUE,"
            + "expires_at INTEGER NOT NULL,"
            + "attempts INTEGER NOT NULL DEFAULT 0"
            + ")"
        });
    }

    /**
     * v3: tabla spaces.
     * - town_uuid como clave primaria.
     * - Los IDs de Discord son opcionales (NULL): una creacion puede estar a
     *   medias y cada ID se persiste en cuanto existe.
     */
    private Migration migration3Spaces() {
        return new Migration(3, new String[]{
            "CREATE TABLE IF NOT EXISTS " + prefix + "spaces ("
            + "town_uuid TEXT NOT NULL PRIMARY KEY,"
            + "town_name TEXT NOT NULL,"
            + "category_id TEXT,"
            + "text_channel_id TEXT,"
            + "voice_channel_id TEXT,"
            + "role_id TEXT,"
            + "state TEXT NOT NULL,"
            + "created_at INTEGER NOT NULL,"
            + "archived_at INTEGER,"
            + "last_activity_at INTEGER"
            + ")"
        });
    }

    /**
     * v4: tabla audit_log.
     * - id autoincremental.
     * - target indexado para acelerar la consulta recent(target, limit).
     */
    private Migration migration4AuditLog() {
        return new Migration(4, new String[]{
            "CREATE TABLE IF NOT EXISTS " + prefix + "audit_log ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "at INTEGER NOT NULL,"
            + "severity TEXT NOT NULL,"
            + "actor TEXT NOT NULL,"
            + "action TEXT NOT NULL,"
            + "target TEXT NOT NULL,"
            + "success INTEGER NOT NULL,"
            + "detail TEXT"
            + ")",
            "CREATE INDEX IF NOT EXISTS idx_" + prefix + "audit_target"
            + " ON " + prefix + "audit_log (target, at DESC)"
        });
    }

    // --- tipo auxiliar ---

    /** Una migracion numerada: version + sentencias SQL a ejecutar en orden. */
    private record Migration(int version, String[] statements) {}
}
