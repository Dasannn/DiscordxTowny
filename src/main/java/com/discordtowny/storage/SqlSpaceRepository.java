package com.discordtowny.storage;

import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSpace;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementacion de {@link SpaceRepository} sobre JDBC.
 *
 * <p>Usa la sintaxis de upsert compatible con el motor configurado:
 * REPLACE INTO para MySQL/MariaDB, INSERT OR REPLACE para SQLite.
 * Ambas son equivalentes: borran la fila con la misma PK e insertan
 * la nueva, manteniendo un unico camino de codigo.
 *
 * <p>Todas las sentencias son preparadas.
 */
final class SqlSpaceRepository implements SpaceRepository {

    private final HikariDataSource ds;
    private final String tSpaces;
    /** Palabra clave de upsert segun el motor de base de datos. */
    private final String upsertPrefix;

    SqlSpaceRepository(HikariDataSource ds, String prefix, boolean isSqlite) {
        this.ds          = ds;
        this.tSpaces     = prefix + "spaces";
        this.upsertPrefix = isSqlite ? "INSERT OR REPLACE" : "REPLACE";
    }

    // --- SpaceRepository ---

    @Override
    public Optional<TownSpace> findByTownUuid(UUID townUuid) {
        String sql = "SELECT * FROM " + tSpaces + " WHERE town_uuid = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, townUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapSpace(rs));
            }
        } catch (SQLException e) {
            throw new StorageException("Error al buscar espacio por UUID de town", e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<TownSpace> findByChannelId(String channelId) {
        String sql = "SELECT * FROM " + tSpaces
                + " WHERE text_channel_id = ? OR voice_channel_id = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, channelId);
            ps.setString(2, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapSpace(rs));
            }
        } catch (SQLException e) {
            throw new StorageException("Error al buscar espacio por ID de canal", e);
        }
        return Optional.empty();
    }

    @Override
    public List<TownSpace> findByState(SpaceState state) {
        String sql = "SELECT * FROM " + tSpaces + " WHERE state = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.name());
            try (ResultSet rs = ps.executeQuery()) {
                return collectAll(rs);
            }
        } catch (SQLException e) {
            throw new StorageException("Error al buscar espacios por estado", e);
        }
    }

    @Override
    public List<TownSpace> findAll() {
        String sql = "SELECT * FROM " + tSpaces;
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return collectAll(rs);
        } catch (SQLException e) {
            throw new StorageException("Error al listar todos los espacios", e);
        }
    }

    /**
     * Inserta o actualiza el espacio completo.
     *
     * <p>REPLACE INTO / INSERT OR REPLACE borra la fila existente e inserta
     * la nueva si hay conflicto de clave primaria. Es el comportamiento
     * correcto para el ciclo de vida del espacio: se persiste cada campo
     * en cuanto se conoce, sin esperar a que el espacio este completo.
     */
    @Override
    public void save(TownSpace space) {
        // Usa la sintaxis adecuada al motor: INSERT OR REPLACE en SQLite, REPLACE en MySQL/MariaDB.
        String sql = upsertPrefix + " INTO " + tSpaces
                + " (town_uuid, town_name, category_id, text_channel_id, voice_channel_id,"
                + "  role_id, state, created_at, archived_at, last_activity_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, space.townUuid().toString());
            ps.setString(2, space.townName());
            setNullable(ps, 3, space.categoryId());
            setNullable(ps, 4, space.textChannelId());
            setNullable(ps, 5, space.voiceChannelId());
            setNullable(ps, 6, space.roleId());
            ps.setString(7, space.state().name());
            ps.setLong(8, space.createdAt().toEpochMilli());
            setNullableInstant(ps, 9, space.archivedAt());
            setNullableInstant(ps, 10, space.lastActivityAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Error al guardar espacio de town", e);
        }
    }

    @Override
    public void delete(UUID townUuid) {
        String sql = "DELETE FROM " + tSpaces + " WHERE town_uuid = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, townUuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Error al borrar espacio de town", e);
        }
    }

    @Override
    public void updateState(UUID townUuid, SpaceState state) {
        String sql = "UPDATE " + tSpaces + " SET state = ? WHERE town_uuid = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setString(2, townUuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Error al actualizar estado del espacio", e);
        }
    }

    @Override
    public void touchActivity(UUID townUuid, Instant at) {
        String sql = "UPDATE " + tSpaces + " SET last_activity_at = ? WHERE town_uuid = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, at.toEpochMilli());
            ps.setString(2, townUuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Error al actualizar actividad del espacio", e);
        }
    }

    @Override
    public int countActive() {
        String sql = "SELECT COUNT(*) FROM " + tSpaces + " WHERE state = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SpaceState.ACTIVE.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new StorageException("Error al contar espacios activos", e);
        }
    }

    // --- utilidades ---

    private List<TownSpace> collectAll(ResultSet rs) throws SQLException {
        List<TownSpace> result = new ArrayList<>();
        while (rs.next()) result.add(mapSpace(rs));
        return result;
    }

    private TownSpace mapSpace(ResultSet rs) throws SQLException {
        return new TownSpace(
                UUID.fromString(rs.getString("town_uuid")),
                rs.getString("town_name"),
                optStr(rs, "category_id"),
                optStr(rs, "text_channel_id"),
                optStr(rs, "voice_channel_id"),
                optStr(rs, "role_id"),
                SpaceState.valueOf(rs.getString("state")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                optInstant(rs, "archived_at"),
                optInstant(rs, "last_activity_at"));
    }

    private Optional<String> optStr(ResultSet rs, String col) throws SQLException {
        String val = rs.getString(col);
        return rs.wasNull() ? Optional.empty() : Optional.of(val);
    }

    private Optional<Instant> optInstant(ResultSet rs, String col) throws SQLException {
        long val = rs.getLong(col);
        return rs.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(val));
    }

    private void setNullable(PreparedStatement ps, int idx, Optional<String> val)
            throws SQLException {
        if (val.isPresent()) {
            ps.setString(idx, val.get());
        } else {
            ps.setNull(idx, Types.VARCHAR);
        }
    }

    private void setNullableInstant(PreparedStatement ps, int idx, Optional<Instant> val)
            throws SQLException {
        if (val.isPresent()) {
            ps.setLong(idx, val.get().toEpochMilli());
        } else {
            ps.setNull(idx, Types.BIGINT);
        }
    }
}
