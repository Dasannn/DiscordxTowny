package com.discordtowny.storage;

import com.discordtowny.model.AccountLink;
import com.discordtowny.model.LinkCode;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link LinkRepository}.
 *
 * <p>All statements are prepared statements. No user value is concatenated
 * into SQL strings. Link uniqueness (one UUID to one Discord ID and vice versa)
 * is guaranteed by UNIQUE constraints in the schema: a collision throws
 * {@link StorageException} instead of passing silently.
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
            throw new StorageException("Failed to find link by UUID", e);
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
            throw new StorageException("Failed to find link by Discord ID", e);
        }
        return Optional.empty();
    }

    /**
     * Inserts the link.
     *
     * <p>The UNIQUE constraint in the schema guarantees that two concurrent
     * requests cannot link the same UUID or the same Discord ID.
     * A collision translates into a {@link StorageException} with a clear message
     * that does not reveal credentials.
     *
     * @throws StorageException if the UUID or Discord ID is already linked.
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
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new StorageException(
                    "A link already exists for this UUID or Discord ID: " + link.uuid(), e);
            }
            throw new StorageException("Failed to save link", e);
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
            throw new StorageException("Failed to delete link", e);
        }
    }

    @Override
    public boolean deleteByUuidIfMatches(UUID uuid, String discordId, Instant linkedAt) {
        // The condition goes inside the DELETE: checking before and deleting after
        // leaves a gap through which a link recreated in between would be deleted.
        String sql = "DELETE FROM " + tLinks
                + " WHERE uuid = ? AND discord_id = ? AND linked_at = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, discordId);
            ps.setLong(3, linkedAt.toEpochMilli());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StorageException("Failed to conditionally delete link", e);
        }
    }

    // --- link_codes ---

    /**
     * Replaces any active code for the player with this one.
     *
     * <p>The uuid column has UNIQUE, so the previous code for the same player
     * is deleted first and then the new one is inserted, all in one transaction.
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
            throw new StorageException("Failed to save link code", e);
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
            throw new StorageException("Failed to find link code", e);
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
            throw new StorageException("Failed to delete link code", e);
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
            throw new StorageException("Failed to increment code attempts", e);
        }
        return 0;
    }

    @Override
    public int purgeExpiredCodes(Instant now) {
        String sql = "DELETE FROM " + tCodes + " WHERE expires_at < ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now.toEpochMilli());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Failed to purge expired codes", e);
        }
    }

    /**
     * Consumes the code and creates the link in a single transaction.
     *
     * <p>The code is claimed by deleting it: the deletion is atomic, so of two
     * concurrent redemptions only one affects a row and the other leaves with
     * CODE_NOT_FOUND. If insertion fails, the rollback puts the code back
     * in place: a uniqueness collision must not burn the player's code.
     */
    @Override
    public ConsumeOutcome consumeCodeAndLink(String code, String discordId, String lastKnownName,
                                             Instant now) {
        String sel = "SELECT uuid, expires_at FROM " + tCodes + " WHERE code = ?";
        String del = "DELETE FROM " + tCodes + " WHERE code = ?";
        String ins = "INSERT INTO " + tLinks
                + " (uuid, discord_id, linked_at, last_known_name) VALUES (?, ?, ?, ?)";

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                UUID uuid;
                long expiresAt;
                try (PreparedStatement ps = conn.prepareStatement(sel)) {
                    ps.setString(1, code);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return ConsumeOutcome.of(ConsumeResult.CODE_NOT_FOUND);
                        }
                        uuid = UUID.fromString(rs.getString("uuid"));
                        expiresAt = rs.getLong("expires_at");
                    }
                }

                // Claim the code by deleting it. Zero rows means another
                // concurrent redemption arrived first.
                try (PreparedStatement ps = conn.prepareStatement(del)) {
                    ps.setString(1, code);
                    if (ps.executeUpdate() == 0) {
                        conn.rollback();
                        return ConsumeOutcome.of(ConsumeResult.CODE_NOT_FOUND);
                    }
                }

                if (now.toEpochMilli() > expiresAt) {
                    // Expired: deletion is committed, it is no longer of any use.
                    conn.commit();
                    return ConsumeOutcome.of(ConsumeResult.CODE_EXPIRED);
                }

                AccountLink link = new AccountLink(uuid, discordId, now, lastKnownName);
                try (PreparedStatement ps = conn.prepareStatement(ins)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, discordId);
                    ps.setLong(3, now.toEpochMilli());
                    ps.setString(4, lastKnownName);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (!isUniqueViolation(e)) {
                        throw e;
                    }
                    // The code becomes available again upon rollback.
                    conn.rollback();
                    return ConsumeOutcome.of(existingLink(conn, uuid)
                            ? ConsumeResult.PLAYER_ALREADY_LINKED
                            : ConsumeResult.DISCORD_ALREADY_LINKED);
                }

                conn.commit();
                return new ConsumeOutcome(ConsumeResult.OK, Optional.of(link));
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to redeem link code", e);
        }
    }

    /** Over the same connection, so as not to leave the ongoing transaction. */
    private boolean existingLink(Connection conn, UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM " + tLinks + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
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
            throw new StorageException("Failed to count links", e);
        }
    }

    // --- mapping ---

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

    // --- utilities ---

    /**
     * Detects a uniqueness violation in SQLite and MySQL/MariaDB.
     *
     * <p>Checked by message and not by SQLite error code 19:
     * that code is SQLITE_CONSTRAINT and covers ALL constraints, including
     * NOT NULL and foreign keys. Treating it as a uniqueness collision turns
     * any constraint failure into a false "a link already exists", and
     * sends whoever is diagnosing in the opposite direction.
     */
    private static boolean isUniqueViolation(SQLException e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("UNIQUE constraint failed")
                || msg.contains("PRIMARY KEY must be unique")
                || msg.contains("Duplicate entry"))) {
            return true;
        }
        // MySQL/MariaDB: 1062 is exclusively duplicate entry. Neither bare
        // SQLIntegrityConstraintViolationException nor SQLState
        // 23000 is suitable: both also cover NOT NULL and foreign keys, and
        // treating them as duplicate returns a false diagnosis.
        return e.getErrorCode() == 1062;
    }
}
