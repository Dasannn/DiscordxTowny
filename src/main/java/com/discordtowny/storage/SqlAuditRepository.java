package com.discordtowny.storage;

import com.discordtowny.model.AuditEvent;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implementacion de {@link AuditRepository} sobre JDBC.
 *
 * <p>Todas las sentencias son preparadas. El campo {@code detail} puede ser
 * nulo si el evento no tiene informacion adicional. Nunca contiene el token
 * ni credenciales: es responsabilidad de quien crea el evento.
 */
final class SqlAuditRepository implements AuditRepository {

    private final HikariDataSource ds;
    private final String tAudit;

    SqlAuditRepository(HikariDataSource ds, String prefix) {
        this.ds     = ds;
        this.tAudit = prefix + "audit_log";
    }

    // --- AuditRepository ---

    @Override
    public void record(AuditEvent event) {
        String sql = "INSERT INTO " + tAudit
                + " (at, severity, actor, action, target, success, detail)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, event.at().toEpochMilli());
            ps.setString(2, event.severity().name());
            ps.setString(3, event.actor());
            ps.setString(4, event.action());
            ps.setString(5, event.target());
            ps.setInt(6, event.success() ? 1 : 0);
            if (event.detail().isPresent()) {
                ps.setString(7, event.detail().get());
            } else {
                ps.setNull(7, Types.VARCHAR);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Error al registrar evento de auditoria", e);
        }
    }

    /**
     * Devuelve los eventos mas recientes de un objetivo, en orden descendente
     * de fecha. Alimenta {@code /dt admin info <town>}.
     */
    @Override
    public List<AuditEvent> recent(String target, int limit) {
        String sql = "SELECT at, severity, actor, action, target, success, detail"
                + " FROM " + tAudit
                + " WHERE target = ?"
                + " ORDER BY at DESC"
                + " LIMIT ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, target);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<AuditEvent> events = new ArrayList<>();
                while (rs.next()) events.add(mapEvent(rs));
                return events;
            }
        } catch (SQLException e) {
            throw new StorageException("Error al consultar auditoria reciente", e);
        }
    }

    /**
     * Borra eventos anteriores a la fecha dada para que la tabla no crezca
     * sin limite.
     *
     * @return numero de filas borradas.
     */
    @Override
    public int purgeBefore(Instant cutoff) {
        String sql = "DELETE FROM " + tAudit + " WHERE at < ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cutoff.toEpochMilli());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Error al purgar auditoria", e);
        }
    }

    // --- mapeo ---

    private AuditEvent mapEvent(ResultSet rs) throws SQLException {
        long at = rs.getLong("at");
        AuditEvent.Severity severity = AuditEvent.Severity.valueOf(rs.getString("severity"));
        String actor = rs.getString("actor");
        String action = rs.getString("action");
        String target = rs.getString("target");
        boolean success = rs.getInt("success") != 0;
        String detail = rs.getString("detail");
        return new AuditEvent(
                Instant.ofEpochMilli(at),
                severity,
                actor,
                action,
                target,
                success,
                detail == null ? Optional.empty() : Optional.of(detail));
    }
}
