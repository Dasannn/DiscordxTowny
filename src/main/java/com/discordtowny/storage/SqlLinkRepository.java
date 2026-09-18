package com.discordtowny.storage;

import com.discordtowny.model.AccountLink;
import com.discordtowny.model.LinkCode;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementacion de {@link LinkRepository} sobre JDBC.
 *
 * <p>Todas las sentencias son preparadas. Ningun valor de usuario se concatena
 * a cadenas SQL. La unicidad de links (un UUID a un Discord ID y viceversa)
 * esta garantizada por restricciones UNIQUE del esquema: un choque lanza
 * {@link StorageException} en lugar de pasar silencioso.
 */
final class SqlLinkRepository implements LinkRepository {

    private final HikariDataSource ds;
    private final String tLinks;
    private final String tCodes;

    SqlLinkRepository(HikariDataSource ds, String prefix) {
        this.ds     = ds;
        this.tLinks = prefix + "links";
        this.tCodes = prefix + "link_codes";
    }

    // --- links ---

    @Override
    public Optional<AccountLink> findByUuid(UUID uuid) {
        String sql = "SELECT uuid, discord_id, linked_at, last_known_name FROM " + tLinks
                + " WHERE uuid = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapLink(rs));
            }
        } catch (SQLException e) {
            throw new StorageException("Error al buscar vinculo por UUID", e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<AccountLink> findByDiscordId(String discordId) {
        String sql = "SELECT uuid, discord_id, linked_at, last_known_name FROM " + tLinks
                + " WHERE discord_id = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapLink(rs));
            }
        } catch (SQLException e) {
            throw new StorageException("Error al buscar vinculo por Discord ID", e);
        }
        return Optional.empty();
    }

    /**
     * Inserta el vinculo.
     *
     * <p>La restriccion UNIQUE del esquema garantiza que dos peticiones
     * simultaneas no puedan vincular el mismo UUID o el mismo Discord ID.
     * Un choque se traduce en {@link StorageException} con un mensaje claro
     * que no revela credenciales.
     *
     * @throws StorageException si el UUID o el Discord ID ya estan vinculados.
     */
    @Override
    public void save(AccountLink link) {
        String sql = "INSERT INTO " + tLinks
                + " (uuid, discord_id, linked_at, last_known_name) VALUES (?, ?, ?, ?)";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, link.uuid().toString());
            ps.setString(2, link.discordId());
            ps.setLong(3, link.linkedAt().toEpochMilli());
            ps.setString(4, link.lastKnownName());
            ps.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new StorageException(
                "Ya existe un vinculo para este UUID o Discord ID: " + link.uuid(), e);
        } catch (SQLException e) {
            // SQLite lanza SQLException con SQLiteErrorCode; no SQLIntegrityConstraintViolationException.
            if (isUniqueViolation(e)) {
                throw new StorageException(
                    "Ya existe un vinculo para este UUID o Discord ID: " + link.uuid(), e);
            }
            throw new StorageException("Error al guardar vinculo", e);
        }
    }

    @Override
    public boolean deleteByUuid(UUID uuid) {
        String sql = "DELETE FROM " + tLinks + " WHERE uuid = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StorageException("Error al borrar vinculo", e);
        }
    }

    // --- link_codes ---

    /**
     * Sustituye cualquier codigo vivo del jugador por este.
     *
     * <p>La columna uuid tiene UNIQUE, por lo que primero se borra el codigo
     * anterior del mismo jugador y luego se inserta el nuevo, todo en una
     * transaccion.
     */
    @Override
    public void saveCode(LinkCode code) {
        String del = "DELETE FROM " + tCodes + " WHERE uuid = ?";
        String ins = "INSERT INTO " + tCodes
                + " (code, uuid, expires_at, attempts) VALUES (?, ?, ?, ?)";
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement psDel = conn.prepareStatement(del);
                 PreparedStatement psIns = conn.prepareStatement(ins)) {
                psDel.setString(1, code.uuid().toString());
                psDel.executeUpdate();

                psIns.setString(1, code.code());
                psIns.setString(2, code.uuid().toString());
                psIns.setLong(3, code.expiresAt().toEpochMilli());
                psIns.setInt(4, code.attempts());
                psIns.executeUpdate();

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StorageException("Error al guardar codigo de vinculacion", e);
        }
    }

    @Override
    public Optional<LinkCode> findCode(String code) {
        String sql = "SELECT code, uuid, expires_at, attempts FROM " + tCodes + " WHERE code = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapCode(rs));
            }
        } catch (SQLException e) {
            throw new StorageException("Error al buscar codigo de vinculacion", e);
        }
        return Optional.empty();
    }

    @Override
    public void deleteCode(String code) {
        String sql = "DELETE FROM " + tCodes + " WHERE code = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Error al borrar codigo de vinculacion", e);
        }
    }

    @Override
    public int incrementAttempts(String code) {
        String upd = "UPDATE " + tCodes + " SET attempts = attempts + 1 WHERE code = ?";
        String sel = "SELECT attempts FROM " + tCodes + " WHERE code = ?";
        try (Connection conn = ds.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(upd)) {
                ps.setString(1, code);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(sel)) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Error al incrementar intentos del codigo", e);
        }
        return 0;
    }

    @Override
    public int purgeExpiredCodes() {
        String sql = "DELETE FROM " + tCodes + " WHERE expires_at < ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, Instant.now().toEpochMilli());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Error al purgar codigos caducados", e);
        }
    }

    @Override
    public int count() {
        String sql = "SELECT COUNT(*) FROM " + tLinks;
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new StorageException("Error al contar vinculos", e);
        }
    }

    // --- mapeo ---

    private AccountLink mapLink(ResultSet rs) throws SQLException {
        return new AccountLink(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("discord_id"),
                Instant.ofEpochMilli(rs.getLong("linked_at")),
                rs.getString("last_known_name"));
    }

    private LinkCode mapCode(ResultSet rs) throws SQLException {
        return new LinkCode(
                rs.getString("code"),
                UUID.fromString(rs.getString("uuid")),
                Instant.ofEpochMilli(rs.getLong("expires_at")),
                rs.getInt("attempts"));
    }

    // --- utilidades ---

    /**
     * Detecta violacion de unicidad de SQLite y MySQL/MariaDB, donde SQLite
     * lanza SQLException con codigo 19 o mensaje "UNIQUE constraint failed"
     * en lugar de SQLIntegrityConstraintViolationException.
     */
    private static boolean isUniqueViolation(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String sqlState = e.getSQLState();
        if (sqlState != null && sqlState.startsWith("23")) {
            return true;
        }
        String msg = e.getMessage();
        if (msg != null && (msg.contains("UNIQUE constraint failed")
                || msg.contains("PRIMARY KEY must be unique")
                || msg.contains("Duplicate entry"))) {
            return true;
        }
        return e.getErrorCode() == 19 || e.getErrorCode() == 1062;
    }
}
