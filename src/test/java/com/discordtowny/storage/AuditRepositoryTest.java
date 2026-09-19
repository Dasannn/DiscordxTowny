package com.discordtowny.storage;

import com.discordtowny.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AuditRepository}: recording and querying events.
 */
class AuditRepositoryTest extends StorageTestBase {

    // --- helpers ---

    private AuditEvent event(String target, AuditEvent.Severity severity, boolean success) {
        return new AuditEvent(
                Instant.now(),
                severity,
                "bot",
                "CREAR_ESPACIO",
                target,
                success,
                Optional.empty());
    }

    private AuditEvent eventWithDetail(String target, String detail) {
        return new AuditEvent(
                Instant.now(),
                AuditEvent.Severity.INFO,
                "bot",
                "VINCULAR",
                target,
                true,
                Optional.of(detail));
    }

    // --- record ---

    @Test
    void recordDoesNotThrowExceptionWithMinimalEvent() {
        assertDoesNotThrow(() -> audit.record(event("town_a", AuditEvent.Severity.INFO, true)));
    }

    @Test
    void recordWithNullDetail() {
        AuditEvent ev = new AuditEvent(
                Instant.now(), AuditEvent.Severity.ERROR, "bot", "ACCION",
                "pueblo", false, Optional.empty());
        assertDoesNotThrow(() -> audit.record(ev));
    }

    @Test
    void recordWithPresentDetail() {
        AuditEvent ev = eventWithDetail("town_b", "Detalle informativo");
        assertDoesNotThrow(() -> audit.record(ev));
    }

    // --- recent ---

    @Test
    void recentReturnsEmptyWhenThereAreNoEvents() {
        List<AuditEvent> result = audit.recent("town_inexistente", 10);
        assertTrue(result.isEmpty());
    }

    @Test
    void recentFiltersByTarget() {
        audit.record(event("town_a", AuditEvent.Severity.INFO, true));
        audit.record(event("town_a", AuditEvent.Severity.WARNING, false));
        audit.record(event("town_b", AuditEvent.Severity.INFO, true));

        List<AuditEvent> aTownA = audit.recent("town_a", 10);
        assertEquals(2, aTownA.size());

        List<AuditEvent> aTownB = audit.recent("town_b", 10);
        assertEquals(1, aTownB.size());
    }

    @Test
    void recentRespectsTheLimit() {
        for (int i = 0; i < 5; i++) {
            audit.record(event("town_x", AuditEvent.Severity.INFO, true));
        }
        List<AuditEvent> result = audit.recent("town_x", 3);
        assertEquals(3, result.size());
    }

    @Test
    void recentReturnsInDescendingOrder() {
        // We insert with different timestamps to ensure ordering.
        Instant t1 = Instant.ofEpochMilli(1000);
        Instant t2 = Instant.ofEpochMilli(2000);
        Instant t3 = Instant.ofEpochMilli(3000);

        audit.record(new AuditEvent(t1, AuditEvent.Severity.INFO, "bot", "A", "ord", true, Optional.empty()));
        audit.record(new AuditEvent(t3, AuditEvent.Severity.INFO, "bot", "C", "ord", true, Optional.empty()));
        audit.record(new AuditEvent(t2, AuditEvent.Severity.INFO, "bot", "B", "ord", true, Optional.empty()));

        List<AuditEvent> result = audit.recent("ord", 10);
        assertEquals(3, result.size());
        // Most recent first.
        assertEquals(t3, result.get(0).at());
        assertEquals(t2, result.get(1).at());
        assertEquals(t1, result.get(2).at());
    }

    @Test
    void recentRetrievesDetailCorrectly() {
        audit.record(eventWithDetail("town_d", "Este es el detalle"));
        List<AuditEvent> result = audit.recent("town_d", 1);
        assertEquals(1, result.size());
        assertTrue(result.get(0).detail().isPresent());
        assertEquals("Este es el detalle", result.get(0).detail().get());
    }

    @Test
    void recentWithNullDetailReturnsEmptyOptional() {
        audit.record(new AuditEvent(Instant.now(), AuditEvent.Severity.ERROR,
                "bot", "BORRAR", "town_e", false, Optional.empty()));
        List<AuditEvent> result = audit.recent("town_e", 1);
        assertTrue(result.get(0).detail().isEmpty());
    }

    @Test
    void recentRetrievesDetailWhenSuccessIsFalse() {
        AuditEvent ev = new AuditEvent(Instant.now(), AuditEvent.Severity.ERROR,
                "bot", "FALLO", "town_err", false, Optional.of("Causa del fallo"));
        audit.record(ev);
        List<AuditEvent> result = audit.recent("town_err", 1);
        assertEquals(1, result.size());
        assertFalse(result.get(0).success());
        assertTrue(result.get(0).detail().isPresent());
        assertEquals("Causa del fallo", result.get(0).detail().get());
    }

    @Test
    void recentPreservesAllSeverities() {
        audit.record(event("town_f", AuditEvent.Severity.INFO, true));
        audit.record(event("town_f", AuditEvent.Severity.WARNING, true));
        audit.record(event("town_f", AuditEvent.Severity.ERROR, false));

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
    void purgeBeforeDeletesEarlierEvents() {
        Instant cutoff = Instant.ofEpochMilli(5000);

        audit.record(new AuditEvent(Instant.ofEpochMilli(1000), AuditEvent.Severity.INFO,
                "bot", "A", "pur", true, Optional.empty()));
        audit.record(new AuditEvent(Instant.ofEpochMilli(4999), AuditEvent.Severity.INFO,
                "bot", "B", "pur", true, Optional.empty()));
        audit.record(new AuditEvent(Instant.ofEpochMilli(5000), AuditEvent.Severity.INFO,
                "bot", "C", "pur", true, Optional.empty()));
        audit.record(new AuditEvent(Instant.ofEpochMilli(6000), AuditEvent.Severity.INFO,
                "bot", "D", "pur", true, Optional.empty()));

        int deleted = audit.purgeBefore(cutoff);

        assertEquals(2, deleted, "Only the two prior to the cutoff date must be deleted");
        List<AuditEvent> remainder = audit.recent("pur", 10);
        assertEquals(2, remainder.size());
    }

    @Test
    void purgeBeforeDeletesNothingIfEverythingIsRecent() {
        audit.record(event("town_g", AuditEvent.Severity.INFO, true));
        int deleted = audit.purgeBefore(Instant.EPOCH);
        assertEquals(0, deleted);
        assertEquals(1, audit.recent("town_g", 10).size());
    }
}
