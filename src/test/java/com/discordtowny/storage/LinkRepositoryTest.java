package com.discordtowny.storage;

import com.discordtowny.model.AccountLink;
import com.discordtowny.model.LinkCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de {@link LinkRepository}: vinculos y codigos de vinculacion.
 */
class LinkRepositoryTest extends StorageTestBase {

    // --- helpers ---

    private AccountLink nuevoLink() {
        return new AccountLink(
                UUID.randomUUID(),
                "discord_" + System.nanoTime(),
                Instant.now(),
                "Jugador");
    }

    private LinkCode nuevoCodigo(UUID uuid, Instant expiry) {
        return new LinkCode("COD" + System.nanoTime(), uuid, expiry, 0);
    }

    // --- findByUuid ---

    @Test
    void findByUuidRetornaVacioSiNoExiste() {
        assertTrue(links.findByUuid(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByUuidRetornaLinkGuardado() {
        AccountLink link = nuevoLink();
        links.save(link);
        Optional<AccountLink> found = links.findByUuid(link.uuid());
        assertTrue(found.isPresent());
        assertEquals(link.uuid(), found.get().uuid());
        assertEquals(link.discordId(), found.get().discordId());
    }

    // --- findByDiscordId ---

    @Test
    void findByDiscordIdRetornaVacioSiNoExiste() {
        assertTrue(links.findByDiscordId("no_existe").isEmpty());
    }

    @Test
    void findByDiscordIdRetornaLinkGuardado() {
        AccountLink link = nuevoLink();
        links.save(link);
        Optional<AccountLink> found = links.findByDiscordId(link.discordId());
        assertTrue(found.isPresent());
        assertEquals(link.uuid(), found.get().uuid());
    }

    // --- save y unicidad ---

    /**
     * Requisito central de T2: la unicidad de links se garantiza en el esquema.
     * Dos peticiones simultaneas no pueden vincular el mismo UUID porque la
     * segunda chocara con la restriccion UNIQUE y lanzara StorageException.
     */
    @Test
    void saveRechazaUUIDDuplicado() {
        UUID uuid = UUID.randomUUID();
        AccountLink link1 = new AccountLink(uuid, "discord_a", Instant.now(), "A");
        AccountLink link2 = new AccountLink(uuid, "discord_b", Instant.now(), "B");
        links.save(link1);
        assertThrows(StorageException.class, () -> links.save(link2));
    }

    @Test
    void saveRechazaDiscordIdDuplicado() {
        String discordId = "discord_" + System.nanoTime();
        AccountLink link1 = new AccountLink(UUID.randomUUID(), discordId, Instant.now(), "A");
        AccountLink link2 = new AccountLink(UUID.randomUUID(), discordId, Instant.now(), "B");
        links.save(link1);
        assertThrows(StorageException.class, () -> links.save(link2));
    }

    @Test
    void saveLinkConservaFechaYNombre() {
        Instant ahora = Instant.now();
        AccountLink link = new AccountLink(UUID.randomUUID(), "d1", ahora, "ElJugador");
        links.save(link);
        AccountLink found = links.findByUuid(link.uuid()).orElseThrow();
        assertEquals(ahora.toEpochMilli(), found.linkedAt().toEpochMilli());
        assertEquals("ElJugador", found.lastKnownName());
    }

    // --- deleteByUuid ---

    @Test
    void deleteByUuidRetornaTrueYBorra() {
        AccountLink link = nuevoLink();
        links.save(link);
        assertTrue(links.deleteByUuid(link.uuid()));
        assertTrue(links.findByUuid(link.uuid()).isEmpty());
    }

    @Test
    void deleteByUuidRetornaFalseSiNoExiste() {
        assertFalse(links.deleteByUuid(UUID.randomUUID()));
    }

    // --- count ---

    @Test
    void countDevuelve0Inicialmente() {
        assertEquals(0, links.count());
    }

    @Test
    void countReflejaInserciones() {
        links.save(nuevoLink());
        links.save(nuevoLink());
        assertEquals(2, links.count());
    }

    // --- saveCode y findCode ---

    @Test
    void saveCodeGuardaYFindCodeRecupera() {
        UUID uuid = UUID.randomUUID();
        LinkCode code = nuevoCodigo(uuid, Instant.now().plusSeconds(600));
        links.saveCode(code);
        Optional<LinkCode> found = links.findCode(code.code());
        assertTrue(found.isPresent());
        assertEquals(code.code(), found.get().code());
        assertEquals(uuid, found.get().uuid());
        assertEquals(0, found.get().attempts());
    }

    @Test
    void saveCodeSustituiyeCodigoAnteriorDelMismoJugador() {
        UUID uuid = UUID.randomUUID();
        LinkCode code1 = new LinkCode("AAA111", uuid, Instant.now().plusSeconds(600), 0);
        LinkCode code2 = new LinkCode("BBB222", uuid, Instant.now().plusSeconds(600), 0);
        links.saveCode(code1);
        links.saveCode(code2);
        // El codigo antiguo ya no existe.
        assertTrue(links.findCode("AAA111").isEmpty());
        // El nuevo si.
        assertTrue(links.findCode("BBB222").isPresent());
    }

    @Test
    void findCodeRetornaVacioSiNoExiste() {
        assertTrue(links.findCode("NOEXISTE").isEmpty());
    }

    // --- deleteCode ---

    @Test
    void deleteCodeElimina() {
        UUID uuid = UUID.randomUUID();
        LinkCode code = nuevoCodigo(uuid, Instant.now().plusSeconds(600));
        links.saveCode(code);
        links.deleteCode(code.code());
        assertTrue(links.findCode(code.code()).isEmpty());
    }

    // --- incrementAttempts ---

    @Test
    void incrementAttemptsAumentaContadorYDevuelveTotalActual() {
        UUID uuid = UUID.randomUUID();
        LinkCode code = nuevoCodigo(uuid, Instant.now().plusSeconds(600));
        links.saveCode(code);
        assertEquals(1, links.incrementAttempts(code.code()));
        assertEquals(2, links.incrementAttempts(code.code()));
        assertEquals(3, links.incrementAttempts(code.code()));
    }

    // --- purgeExpiredCodes ---

    @Test
    void purgeExpiredCodesBorraLosCaducados() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        // Caducado hace 1 segundo.
        LinkCode expirado = new LinkCode("EXP001", uuid1, Instant.now().minusSeconds(1), 0);
        // Vigente por 10 minutos.
        LinkCode vigente  = new LinkCode("VIG001", uuid2, Instant.now().plusSeconds(600), 0);
        links.saveCode(expirado);
        links.saveCode(vigente);

        int borrados = links.purgeExpiredCodes();

        assertEquals(1, borrados);
        assertTrue(links.findCode("EXP001").isEmpty(), "El codigo caducado debe haberse borrado");
        assertTrue(links.findCode("VIG001").isPresent(), "El codigo vigente debe seguir");
    }

    // --- isExpired en el modelo ---

    @Test
    void isExpiredFuncionaCorrectamente() {
        LinkCode expirado = new LinkCode("X", UUID.randomUUID(), Instant.now().minusSeconds(1), 0);
        LinkCode vigente  = new LinkCode("Y", UUID.randomUUID(), Instant.now().plusSeconds(600), 0);
        assertTrue(expirado.isExpired(Instant.now()));
        assertFalse(vigente.isExpired(Instant.now()));
    }

    // --- clasificacion de errores de restriccion ---

    @Test
    void violacionDeNotNullNoSeReportaComoVinculoDuplicado() {
        // SQLite lanza el codigo 19 (SQLITE_CONSTRAINT) para CUALQUIER
        // restriccion, tambien NOT NULL. Si se trata todo el codigo 19 como
        // choque de unicidad, un nombre nulo se reporta como "ya existe un
        // vinculo", que es falso y manda el diagnostico en direccion contraria.
        AccountLink sinNombre = new AccountLink(UUID.randomUUID(), "discord_sin_nombre", Instant.now(), null);

        StorageException e = assertThrows(StorageException.class, () -> links.save(sinNombre));

        assertFalse(e.getMessage().contains("Ya existe"),
                "Un nombre nulo no es un vinculo duplicado: " + e.getMessage());
    }
}
