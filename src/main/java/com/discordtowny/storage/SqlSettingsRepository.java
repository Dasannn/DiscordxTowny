package com.discordtowny.storage;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Implementacion de {@link SettingsRepository} sobre JDBC. */
final class SqlSettingsRepository implements SettingsRepository {

    private final HikariDataSource ds;
    private final String table;
    private final boolean sqlite;

    SqlSettingsRepository(HikariDataSource ds, String prefix, boolean sqlite) {
        this.ds = ds;
        this.table = prefix + "settings";
        this.sqlite = sqlite;
    }

    @Override
    public Optional<String> get(String key) {
        String sql = "SELECT value FROM " + table + " WHERE setting_key = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("value"));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Error al leer el ajuste " + key, e);
        }
        return Optional.empty();
    }

    @Override
    public void put(String key, String value) {
        // La sintaxis de upsert no es la misma en ambos motores.
        String sql = sqlite
                ? "INSERT INTO " + table + " (setting_key, value) VALUES (?, ?)"
                    + " ON CONFLICT(setting_key) DO UPDATE SET value = excluded.value"
                : "INSERT INTO " + table + " (setting_key, value) VALUES (?, ?)"
                    + " ON DUPLICATE KEY UPDATE value = VALUES(value)";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Error al guardar el ajuste " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM " + table + " WHERE setting_key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Error al borrar el ajuste " + key, e);
        }
    }
}
