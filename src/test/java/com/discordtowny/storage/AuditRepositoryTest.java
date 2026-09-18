package com.discordtowny.storage;

import com.discordtowny.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de {@link AuditRepository}: registro y consulta de eventos.
 */
class AuditRepositoryTest extends StorageTestBase {

    // --- helpers ---

    private AuditEvent evento(String target, AuditEvent.Severity severity, boolean success) {
        return new AuditEvent(
                Instant.now(),
                severity,
                "bot",
                "CREAR_ESPACIO",
                target,
                success,
                Optional.empty());
    }

    private AuditEvent eventoConDetalle(String target, String detalle) {
        return new AuditEvent(
                Instant.now(),
                AuditEvent.Severity.INFO,
                "bot",
                "VINCULAR",
                target,
                true,
                Optional.of(detalle));
    }

    // --- record ---

    @Test
    void recordNoLanzaExcepcionConEventoMinimo() {
        assertDoesNotThrow(() -> audit.record(evento("town_a", AuditEvent.Severity.INFO, true)));
    }

    @Test
    void recordConDetalleNulo() {
        AuditEvent ev = new AuditEvent(
                Instant.now(), AuditEvent.Severity.ERROR, "bot", "ACCION",
                "pueblo", false, Optional.empty());
        assertDoesNotThrow(() -> audit.record(ev));
    }

    @Test
    void recordConDetallePresente() {
        AuditEvent ev = eventoConDetalle("town_b", "Detalle informativo");
        assertDoesNotThrow(() -> audit.record(ev));
    }

    // --- recent ---

    @Test
    void recentDevuelveVacioSiNoHayEventos() {
        List<AuditEvent> result = audit.recent("town_inexistente", 10);
        assertTrue(result.isEmpty());
    }

    @Test
    void recentFiltraPorTarget() {
        audit.record(evento("town_a", AuditEvent.Severity.INFO, true));
        audit.record(evento("town_a", AuditEvent.Severity.WARNING, false));
        audit.record(evento("town_b", AuditEvent.Severity.INFO, true));

        List<AuditEvent> aTownA = audit.recent("town_a", 10);
        assertEquals(2, aTownA.size());

        List<AuditEvent> aTownB = audit.recent("town_b", 10);
        assertEquals(1, aTownB.size());
    }

    @Test
    void recentRespetaElLimite() {
        for (int i = 0; i < 5; i++) {
            audit.record(evento("town_x", AuditEvent.Severity.INFO, true));
        }
        List<AuditEvent> result = audit.recent("town_x", 3);
        assertEquals(3, result.size());
    }

    @Test
    void recentDevuelveEnOrdenDescendente() {
        // Insertamos con tiempos distintos para garantizar orden.
        Instant t1 = Instant.ofEpochMilli(1000);
        Instant t2 = Instant.ofEpochMilli(2000);
        Instant t3 = Instant.ofEpochMilli(3000);

        audit.record(new AuditEvent(t1, AuditEvent.Severity.INFO, "bot", "A", "ord", true, Optional.empty()));
        audit.record(new AuditEvent(t3, AuditEvent.Severity.INFO, "bot", "C", "ord", true, Optional.empty()));
        audit.record(new AuditEvent(t2, AuditEvent.Severity.INFO, "bot", "B", "ord", true, Optional.empty()));

        List<AuditEvent> result = audit.recent("ord", 10);
        assertEquals(3, result.size());
        // El mas reciente primero.
        assertEquals(t3, result.get(0).at());
        assertEquals(t2, result.get(1).at());
        assertEquals(t1, result.get(2).at());
    }

    @Test
    void recentRecuperaDetalleCorrectamente() {
        audit.record(eventoConDetalle("town_d", "Este es el detalle"));
        List<AuditEvent> result = audit.recent("town_d", 1);
        assertEquals(1, result.size());
        assertTrue(result.get(0).detail().isPresent());
        assertEquals("Este es el detalle", result.get(0).detail().get());
    }

    @Test
    void recentConDetalleNuloDevuelveOptionalVacio() {
        audit.record(new AuditEvent(Instant.now(), AuditEvent.Severity.ERROR,
                "bot", "BORRAR", "town_e", false, Optional.empty()));
        List<AuditEvent> result = audit.recent("town_e", 1);
        assertTrue(result.get(0).detail().isEmpty());
    }

    @Test
    void recentConservaTodasLasSeveridades() {
        audit.record(evento("town_f", AuditEvent.Severity.INFO, true));
        audit.record(evento("town_f", AuditEvent.Severity.WARNING, true));
        audit.record(evento("town_f", AuditEvent.Severity.ERROR, false));

        List<AuditEvent> result = audit.recent("town_f", 10);
        long info    = result.stream().filter(e -> e.severity() == AuditEvent.Severity.INFO).count();
        long warning = result.stream().filter(e -> e.severity() == AuditEvent.Severity.WARNING).count();
        long error   = result.stream().filter(e -> e.severity() == AuditEvent.Severity.ERROR).count();
        assertEquals(1, info);
        assertEquals(1, warning);
        assertEquals(1, error);
    }

    // --- purgeBefore ---

    @Test
    void purgeBeforeBorraEventosAnteriores() {
        Instant corte = Instant.ofEpochMilli(5000);

        audit.record(new AuditEvent(Instant.ofEpochMilli(1000), AuditEvent.Severity.INFO,
                "bot", "A", "pur", true, Optional.empty()));
        audit.record(new AuditEvent(Instant.ofEpochMilli(4999), AuditEvent.Severity.INFO,
                "bot", "B", "pur", true, Optional.empty()));
        audit.record(new AuditEvent(Instant.ofEpochMilli(5000), AuditEvent.Severity.INFO,
                "bot", "C", "pur", true, Optional.empty()));
        audit.record(new AuditEvent(Instant.ofEpochMilli(6000), AuditEvent.Severity.INFO,
                "bot", "D", "pur", true, Optional.empty()));

        int borrados = audit.purgeBefore(corte);

        assertEquals(2, borrados, "Solo los dos anteriores a la fecha de corte deben borrarse");
        List<AuditEvent> resto = audit.recent("pur", 10);
        assertEquals(2, resto.size());
    }

    @Test
    void purgeBeforeNoBorraNadaSiTodoEsReciente() {
        audit.record(evento("town_g", AuditEvent.Severity.INFO, true));
        int borrados = audit.purgeBefore(Instant.EPOCH);
        assertEquals(0, borrados);
        assertEquals(1, audit.recent("town_g", 10).size());
    }
}
