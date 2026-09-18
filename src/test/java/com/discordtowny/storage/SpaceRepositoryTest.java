package com.discordtowny.storage;

import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSpace;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de {@link SpaceRepository}: ciclo de vida de los espacios de Discord.
 */
class SpaceRepositoryTest extends StorageTestBase {

    // --- helpers ---

    private TownSpace nuevoEspacio(SpaceState state) {
        return new TownSpace(
                UUID.randomUUID(),
                "ElPueblo_" + System.nanoTime(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                state,
                Instant.now(),
                Optional.empty(),
                Optional.empty());
    }

    private TownSpace espacioCompleto(UUID uuid, String nombre) {
        return new TownSpace(
                uuid,
                nombre,
                Optional.of("cat_" + System.nanoTime()),
                Optional.of("txt_" + System.nanoTime()),
                Optional.of("voz_" + System.nanoTime()),
                Optional.of("rol_" + System.nanoTime()),
                SpaceState.ACTIVE,
                Instant.now(),
                Optional.empty(),
                Optional.empty());
    }

    // --- findByTownUuid ---

    @Test
    void findByTownUuidRetornaVacioSiNoExiste() {
        assertTrue(spaces.findByTownUuid(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByTownUuidRetornaEspacioGuardado() {
        TownSpace esp = nuevoEspacio(SpaceState.ACTIVE);
        spaces.save(esp);
        Optional<TownSpace> found = spaces.findByTownUuid(esp.townUuid());
        assertTrue(found.isPresent());
        assertEquals(esp.townUuid(), found.get().townUuid());
        assertEquals(esp.townName(), found.get().townName());
    }

    // --- findByChannelId ---

    @Test
    void findByChannelIdRetornaVacioSiNoHayCanal() {
        assertTrue(spaces.findByChannelId("no_existe").isEmpty());
    }

    @Test
    void findByChannelIdEncuentraPorTextChannel() {
        UUID uuid = UUID.randomUUID();
        TownSpace esp = espacioCompleto(uuid, "Pueblo");
        spaces.save(esp);
        String textId = esp.textChannelId().orElseThrow();
        Optional<TownSpace> found = spaces.findByChannelId(textId);
        assertTrue(found.isPresent());
        assertEquals(uuid, found.get().townUuid());
    }

    @Test
    void findByChannelIdEncuentraPorVoiceChannel() {
        UUID uuid = UUID.randomUUID();
        TownSpace esp = espacioCompleto(uuid, "Pueblo");
        spaces.save(esp);
        String voiceId = esp.voiceChannelId().orElseThrow();
        Optional<TownSpace> found = spaces.findByChannelId(voiceId);
        assertTrue(found.isPresent());
        assertEquals(uuid, found.get().townUuid());
    }

    // --- findByState ---

    @Test
    void findByStateRetornaListaFiltrada() {
        spaces.save(nuevoEspacio(SpaceState.ACTIVE));
        spaces.save(nuevoEspacio(SpaceState.ACTIVE));
        spaces.save(nuevoEspacio(SpaceState.ARCHIVED));

        List<TownSpace> activos   = spaces.findByState(SpaceState.ACTIVE);
        List<TownSpace> archivados = spaces.findByState(SpaceState.ARCHIVED);
        assertEquals(2, activos.size());
        assertEquals(1, archivados.size());
    }

    // --- findAll ---

    @Test
    void findAllRetornaTodos() {
        spaces.save(nuevoEspacio(SpaceState.ACTIVE));
        spaces.save(nuevoEspacio(SpaceState.ARCHIVED));
        spaces.save(nuevoEspacio(SpaceState.INCONSISTENT));
        assertEquals(3, spaces.findAll().size());
    }

    // --- save (upsert) ---

    @Test
    void saveActualizaEspacioExistente() {
        UUID uuid = UUID.randomUUID();
        TownSpace v1 = new TownSpace(uuid, "NombreViejo", Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                SpaceState.ACTIVE, Instant.now(), Optional.empty(), Optional.empty());
        spaces.save(v1);

        TownSpace v2 = new TownSpace(uuid, "NombreNuevo", Optional.of("cat123"),
                Optional.of("txt123"), Optional.empty(), Optional.of("rol123"),
                SpaceState.ACTIVE, v1.createdAt(), Optional.empty(), Optional.empty());
        spaces.save(v2);

        TownSpace found = spaces.findByTownUuid(uuid).orElseThrow();
        assertEquals("NombreNuevo", found.townName());
        assertTrue(found.categoryId().isPresent());
        assertEquals("cat123", found.categoryId().get());
    }

    @Test
    void saveConservaFechaDeCreacion() {
        Instant ahora = Instant.now();
        TownSpace esp = new TownSpace(UUID.randomUUID(), "Pueblo",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                SpaceState.ACTIVE, ahora, Optional.empty(), Optional.empty());
        spaces.save(esp);
        TownSpace found = spaces.findByTownUuid(esp.townUuid()).orElseThrow();
        assertEquals(ahora.toEpochMilli(), found.createdAt().toEpochMilli());
    }

    @Test
    void saveConArchivedAt() {
        Instant archivedAt = Instant.now().plusSeconds(100);
        TownSpace esp = new TownSpace(UUID.randomUUID(), "Pueblo",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                SpaceState.ARCHIVED, Instant.now(), Optional.of(archivedAt), Optional.empty());
        spaces.save(esp);
        TownSpace found = spaces.findByTownUuid(esp.townUuid()).orElseThrow();
        assertTrue(found.archivedAt().isPresent());
        assertEquals(archivedAt.toEpochMilli(), found.archivedAt().get().toEpochMilli());
    }

    // --- delete ---

    @Test
    void deleteBorraElEspacio() {
        TownSpace esp = nuevoEspacio(SpaceState.ACTIVE);
        spaces.save(esp);
        spaces.delete(esp.townUuid());
        assertTrue(spaces.findByTownUuid(esp.townUuid()).isEmpty());
    }

    @Test
    void deleteNoEsErrorSiNoExiste() {
        assertDoesNotThrow(() -> spaces.delete(UUID.randomUUID()));
    }

    // --- updateState ---

    @Test
    void updateStateActualizaElEstado() {
        TownSpace esp = nuevoEspacio(SpaceState.ACTIVE);
        spaces.save(esp);
        spaces.updateState(esp.townUuid(), SpaceState.ARCHIVED);
        TownSpace found = spaces.findByTownUuid(esp.townUuid()).orElseThrow();
        assertEquals(SpaceState.ARCHIVED, found.state());
    }

    @Test
    void updateStateAInconsistent() {
        TownSpace esp = nuevoEspacio(SpaceState.ACTIVE);
        spaces.save(esp);
        spaces.updateState(esp.townUuid(), SpaceState.INCONSISTENT);
        assertEquals(SpaceState.INCONSISTENT,
                spaces.findByTownUuid(esp.townUuid()).orElseThrow().state());
    }

    // --- touchActivity ---

    @Test
    void touchActivityActualizaFechaDeUltimaActividad() {
        TownSpace esp = nuevoEspacio(SpaceState.ACTIVE);
        spaces.save(esp);
        Instant actividad = Instant.now().plusSeconds(500);
        spaces.touchActivity(esp.townUuid(), actividad);
        TownSpace found = spaces.findByTownUuid(esp.townUuid()).orElseThrow();
        assertTrue(found.lastActivityAt().isPresent());
        assertEquals(actividad.toEpochMilli(), found.lastActivityAt().get().toEpochMilli());
    }

    // --- countActive ---

    @Test
    void countActiveContaSoloActivos() {
        spaces.save(nuevoEspacio(SpaceState.ACTIVE));
        spaces.save(nuevoEspacio(SpaceState.ACTIVE));
        spaces.save(nuevoEspacio(SpaceState.ARCHIVED));
        spaces.save(nuevoEspacio(SpaceState.INCONSISTENT));
        assertEquals(2, spaces.countActive());
    }

    @Test
    void countActiveEsCeroSiNoHayNada() {
        assertEquals(0, spaces.countActive());
    }

    // --- isComplete ---

    @Test
    void isCompleteConTextoYVoz() {
        TownSpace esp = espacioCompleto(UUID.randomUUID(), "Pueblo");
        assertTrue(esp.isComplete(true, true));
        assertTrue(esp.isComplete(true, false));
        assertTrue(esp.isComplete(false, true));
        assertTrue(esp.isComplete(false, false));
    }

    @Test
    void isCompleteIncompleto() {
        TownSpace esp = new TownSpace(UUID.randomUUID(), "Pueblo",
                Optional.empty(), Optional.of("txt123"), Optional.empty(),
                Optional.of("rol123"), SpaceState.ACTIVE, Instant.now(),
                Optional.empty(), Optional.empty());
        // Tiene texto y rol, le falta voz.
        assertFalse(esp.isComplete(false, true), "Falta voz");
        assertTrue(esp.isComplete(true, false), "Solo pide texto y hay texto");
        assertTrue(esp.isComplete(false, false), "No pide nada: siempre completo");
    }
}
