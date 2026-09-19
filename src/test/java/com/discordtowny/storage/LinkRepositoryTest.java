package com.discordtowny.storage;

import com.discordtowny.model.AccountLink;
import com.discordtowny.model.LinkCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LinkRepository}: links and link codes.
 */
class LinkRepositoryTest extends StorageTestBase {

    // --- helpers ---

    private AccountLink newLink() {
        return new AccountLink(
                UUID.randomUUID(),
                "discord_" + System.nanoTime(),
                Instant.now(),
                "Jugador");
    }

    private LinkCode newCode(UUID uuid, Instant expiry) {
        return new LinkCode("COD" + System.nanoTime(), uuid, expiry, 0);
    }

    // --- findByUuid ---

    @Test
    void findByUuidReturnsEmptyWhenDoesNotExist() {
        assertTrue(links.findByUuid(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByUuidReturnsSavedLink() {
        AccountLink link = newLink();
        links.save(link);
        Optional<AccountLink> found = links.findByUuid(link.uuid());
        assertTrue(found.isPresent());
        assertEquals(link.uuid(), found.get().uuid());
        assertEquals(link.discordId(), found.get().discordId());
    }

    // --- findByDiscordId ---

    @Test
    void findByDiscordIdReturnsEmptyWhenDoesNotExist() {
        assertTrue(links.findByDiscordId("no_existe").isEmpty());
    }

    @Test
    void findByDiscordIdReturnsSavedLink() {
        AccountLink link = newLink();
        links.save(link);
        Optional<AccountLink> found = links.findByDiscordId(link.discordId());
        assertTrue(found.isPresent());
        assertEquals(link.uuid(), found.get().uuid());
    }

    // --- save y unicidad ---

    /**
     * Central requirement of T2: link uniqueness is guaranteed in the schema.
     * Two simultaneous requests cannot link the same UUID because the
     * second will collide with the UNIQUE constraint and throw StorageException.
     */
    @Test
    void saveRejectsDuplicateUuid() {
        UUID uuid = UUID.randomUUID();
        AccountLink link1 = new AccountLink(uuid, "discord_a", Instant.now(), "A");
        AccountLink link2 = new AccountLink(uuid, "discord_b", Instant.now(), "B");
        links.save(link1);
        assertThrows(StorageException.class, () -> links.save(link2));
    }

    @Test
    void saveRejectsDuplicateDiscordId() {
        String discordId = "discord_" + System.nanoTime();
        AccountLink link1 = new AccountLink(UUID.randomUUID(), discordId, Instant.now(), "A");
        AccountLink link2 = new AccountLink(UUID.randomUUID(), discordId, Instant.now(), "B");
        links.save(link1);
        assertThrows(StorageException.class, () -> links.save(link2));
    }

    @Test
    void saveLinkPreservesDateAndName() {
        Instant now = Instant.now();
        AccountLink link = new AccountLink(UUID.randomUUID(), "d1", now, "ElJugador");
        links.save(link);
        AccountLink found = links.findByUuid(link.uuid()).orElseThrow();
        assertEquals(now.toEpochMilli(), found.linkedAt().toEpochMilli());
        assertEquals("ElJugador", found.lastKnownName());
    }

    // --- deleteByUuid ---

    @Test
    void deleteByUuidReturnsTrueAndDeletes() {
        AccountLink link = newLink();
        links.save(link);
        assertTrue(links.deleteByUuid(link.uuid()));
        assertTrue(links.findByUuid(link.uuid()).isEmpty());
    }

    @Test
    void deleteByUuidReturnsFalseWhenDoesNotExist() {
        assertFalse(links.deleteByUuid(UUID.randomUUID()));
    }

    // --- count ---

    @Test
    void countReturns0Initially() {
        assertEquals(0, links.count());
    }

    @Test
    void countReflectsInsertions() {
        links.save(newLink());
        links.save(newLink());
        assertEquals(2, links.count());
    }

    // --- saveCode y findCode ---

    @Test
    void saveCodeSavesAndFindCodeRetrieves() {
        UUID uuid = UUID.randomUUID();
        LinkCode code = newCode(uuid, Instant.now().plusSeconds(600));
        links.saveCode(code);
        Optional<LinkCode> found = links.findCode(code.code());
        assertTrue(found.isPresent());
        assertEquals(code.code(), found.get().code());
        assertEquals(uuid, found.get().uuid());
        assertEquals(0, found.get().attempts());
    }

    @Test
    void saveCodeReplacesPreviousCodeOfSamePlayer() {
        UUID uuid = UUID.randomUUID();
        LinkCode code1 = new LinkCode("AAA111", uuid, Instant.now().plusSeconds(600), 0);
        LinkCode code2 = new LinkCode("BBB222", uuid, Instant.now().plusSeconds(600), 0);
        links.saveCode(code1);
        links.saveCode(code2);
        // Old code no longer exists.
        assertTrue(links.findCode("AAA111").isEmpty());
        // The new one does.
        assertTrue(links.findCode("BBB222").isPresent());
    }

    @Test
    void findCodeReturnsEmptyWhenDoesNotExist() {
        assertTrue(links.findCode("NOEXISTE").isEmpty());
    }

    // --- deleteCode ---

    @Test
    void deleteCodeDeletes() {
        UUID uuid = UUID.randomUUID();
        LinkCode code = newCode(uuid, Instant.now().plusSeconds(600));
        links.saveCode(code);
        links.deleteCode(code.code());
        assertTrue(links.findCode(code.code()).isEmpty());
    }

    // --- incrementAttempts ---

    @Test
    void incrementAttemptsIncrementsCounterAndReturnsCurrentTotal() {
        UUID uuid = UUID.randomUUID();
        LinkCode code = newCode(uuid, Instant.now().plusSeconds(600));
        links.saveCode(code);
        assertEquals(1, links.incrementAttempts(code.code()));
        assertEquals(2, links.incrementAttempts(code.code()));
        assertEquals(3, links.incrementAttempts(code.code()));
    }

    // --- purgeExpiredCodes ---

    @Test
    void purgeExpiredCodesDeletesExpiredOnes() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        // Expired 1 second ago.
        LinkCode expired = new LinkCode("EXP001", uuid1, Instant.now().minusSeconds(1), 0);
        // Valid for 10 minutes.
        LinkCode valid  = new LinkCode("VIG001", uuid2, Instant.now().plusSeconds(600), 0);
        links.saveCode(expired);
        links.saveCode(valid);

        int deleted = links.purgeExpiredCodes(Instant.now());

        assertEquals(1, deleted);
        assertTrue(links.findCode("EXP001").isEmpty(), "The expired code must have been deleted");
        assertTrue(links.findCode("VIG001").isPresent(), "The valid code must remain");
    }

    // --- isExpired en el modelo ---

    @Test
    void isExpiredWorksCorrectly() {
        LinkCode expired = new LinkCode("X", UUID.randomUUID(), Instant.now().minusSeconds(1), 0);
        LinkCode valid  = new LinkCode("Y", UUID.randomUUID(), Instant.now().plusSeconds(600), 0);
        assertTrue(expired.isExpired(Instant.now()));
        assertFalse(valid.isExpired(Instant.now()));
    }

    // --- clasificacion de errores de restriccion ---

    @Test
    void notNullViolationIsNotReportedAsDuplicateLink() {
        // SQLite throws code 19 (SQLITE_CONSTRAINT) for ANY
        // constraint, including NOT NULL. If all code 19 is treated as
        // a uniqueness collision, a null name is reported as "a link already exists",
        // which is false and sends diagnosis in the opposite direction.
        AccountLink withoutName = new AccountLink(UUID.randomUUID(), "discord_sin_nombre", Instant.now(), null);

        StorageException e = assertThrows(StorageException.class, () -> links.save(withoutName));

        assertFalse(e.getMessage().contains("Ya existe"),
                "A null name is not a duplicate link: " + e.getMessage());
    }

    // --- canje atomico ---

    @Test
    void atomicRedemptionConsumesCodeAndCreatesLink() {
        UUID uuid = UUID.randomUUID();
        links.saveCode(new LinkCode("ABC123", uuid, Instant.now().plusSeconds(600), 0));

        var output = links.consumeCodeAndLink("ABC123", "discord_1", "Steve", Instant.now());

        assertEquals(LinkRepository.ConsumeResult.OK, output.result());
        assertTrue(output.link().isPresent());
        assertEquals(uuid, output.link().get().uuid());
        assertTrue(links.findCode("ABC123").isEmpty(), "The code must be consumed");
        assertTrue(links.findByUuid(uuid).isPresent());
    }

    @Test
    void sameCodeCannotBeRedeemedTwice() {
        UUID uuid = UUID.randomUUID();
        links.saveCode(new LinkCode("ABC123", uuid, Instant.now().plusSeconds(600), 0));

        assertEquals(LinkRepository.ConsumeResult.OK,
                links.consumeCodeAndLink("ABC123", "discord_1", "Steve", Instant.now()).result());
        assertEquals(LinkRepository.ConsumeResult.CODE_NOT_FOUND,
                links.consumeCodeAndLink("ABC123", "discord_2", "Alex", Instant.now()).result());
    }

    @Test
    void uniquenessCollisionReturnsCodeToItsPlace() {
        // The player is already linked; their code must not be burned upon failure.
        UUID uuid = UUID.randomUUID();
        links.save(new AccountLink(uuid, "discord_previo", Instant.now(), "Steve"));
        links.saveCode(new LinkCode("ABC123", uuid, Instant.now().plusSeconds(600), 0));

        var output = links.consumeCodeAndLink("ABC123", "discord_nuevo", "Steve", Instant.now());

        assertEquals(LinkRepository.ConsumeResult.PLAYER_ALREADY_LINKED, output.result());
        assertTrue(links.findCode("ABC123").isPresent(),
                "A uniqueness collision cannot burn the player's code");
    }

    @Test
    void expiredCodeIsConsumedAndRejected() {
        UUID uuid = UUID.randomUUID();
        links.saveCode(new LinkCode("ABC123", uuid, Instant.now().minusSeconds(1), 0));

        var output = links.consumeCodeAndLink("ABC123", "discord_1", "Steve", Instant.now());

        assertEquals(LinkRepository.ConsumeResult.CODE_EXPIRED, output.result());
        assertTrue(links.findCode("ABC123").isEmpty());
        assertTrue(links.findByUuid(uuid).isEmpty());
    }

    // --- borrado condicional ---

    @Test
    void conditionalDeletionDeletesIfLinkMatches() {
        UUID uuid = UUID.randomUUID();
        Instant when = Instant.ofEpochMilli(1_700_000_000_000L);
        links.save(new AccountLink(uuid, "discord_1", when, "Steve"));

        assertTrue(links.deleteByUuidIfMatches(uuid, "discord_1", when));
        assertTrue(links.findByUuid(uuid).isEmpty());
    }

    @Test
    void conditionalDeletionDoesNotDeleteRecreatedLink() {
        UUID uuid = UUID.randomUUID();
        Instant oldTimestamp = Instant.ofEpochMilli(1_700_000_000_000L);
        Instant newTimestamp = Instant.ofEpochMilli(1_700_000_999_000L);

        // The link that exists now is another: it broke and was recreated.
        links.save(new AccountLink(uuid, "discord_2", newTimestamp, "Steve"));

        assertFalse(links.deleteByUuidIfMatches(uuid, "discord_1", oldTimestamp),
                "Must not delete a link different from the authorized one");
        assertTrue(links.findByUuid(uuid).isPresent(), "The new link remains intact");
    }

    @Test
    void refreshingTheNameChangesOnlyTheNameAndIgnoresPlayersWithoutALink() {
        UUID uuid = UUID.randomUUID();
        Instant when = Instant.ofEpochMilli(1_700_000_000_000L);
        links.save(new AccountLink(uuid, "discord_1", when, "OldName"));

        links.updateLastKnownName(uuid, "NewName");

        AccountLink stored = links.findByUuid(uuid).orElseThrow();
        assertEquals("NewName", stored.lastKnownName());
        assertEquals("discord_1", stored.discordId(), "The link itself must not change");
        assertEquals(when, stored.linkedAt());

        // A player with no link is not an error and creates nothing.
        UUID stranger = UUID.randomUUID();
        links.updateLastKnownName(stranger, "Ghost");
        assertTrue(links.findByUuid(stranger).isEmpty());
    }
}
