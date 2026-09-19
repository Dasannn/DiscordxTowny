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
 * Tests for {@link SpaceRepository}: lifecycle of Discord spaces.
 */
class SpaceRepositoryTest extends StorageTestBase {

    // --- helpers ---

    private TownSpace newSpace(SpaceState state) {
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

    private TownSpace completeSpace(UUID uuid, String name) {
        return new TownSpace(
                uuid,
                name,
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
    void findByTownUuidReturnsEmptyWhenDoesNotExist() {
        assertTrue(spaces.findByTownUuid(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByTownUuidReturnsSavedSpace() {
        TownSpace space = newSpace(SpaceState.ACTIVE);
        spaces.save(space);
        Optional<TownSpace> found = spaces.findByTownUuid(space.townUuid());
        assertTrue(found.isPresent());
        assertEquals(space.townUuid(), found.get().townUuid());
        assertEquals(space.townName(), found.get().townName());
    }

    // --- findByChannelId ---

    @Test
    void findByChannelIdReturnsEmptyWhenThereIsNoChannel() {
        assertTrue(spaces.findByChannelId("no_existe").isEmpty());
    }

    @Test
    void findByChannelIdFindsByTextChannel() {
        UUID uuid = UUID.randomUUID();
        TownSpace space = completeSpace(uuid, "Pueblo");
        spaces.save(space);
        String textId = space.textChannelId().orElseThrow();
        Optional<TownSpace> found = spaces.findByChannelId(textId);
        assertTrue(found.isPresent());
        assertEquals(uuid, found.get().townUuid());
    }

    @Test
    void findByChannelIdFindsByVoiceChannel() {
        UUID uuid = UUID.randomUUID();
        TownSpace space = completeSpace(uuid, "Pueblo");
        spaces.save(space);
        String voiceId = space.voiceChannelId().orElseThrow();
        Optional<TownSpace> found = spaces.findByChannelId(voiceId);
        assertTrue(found.isPresent());
        assertEquals(uuid, found.get().townUuid());
    }

    // --- findByState ---

    @Test
    void findByStateReturnsFilteredList() {
        spaces.save(newSpace(SpaceState.ACTIVE));
        spaces.save(newSpace(SpaceState.ACTIVE));
        spaces.save(newSpace(SpaceState.ARCHIVED));

        List<TownSpace> active   = spaces.findByState(SpaceState.ACTIVE);
        List<TownSpace> archived = spaces.findByState(SpaceState.ARCHIVED);
        assertEquals(2, active.size());
        assertEquals(1, archived.size());
    }

    // --- findAll ---

    @Test
    void findAllReturnsAll() {
        spaces.save(newSpace(SpaceState.ACTIVE));
        spaces.save(newSpace(SpaceState.ARCHIVED));
        spaces.save(newSpace(SpaceState.INCONSISTENT));
        assertEquals(3, spaces.findAll().size());
    }

    // --- save (upsert) ---

    @Test
    void saveUpdatesExistingSpace() {
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
    void savePreservesCreationDate() {
        Instant now = Instant.now();
        TownSpace space = new TownSpace(UUID.randomUUID(), "Pueblo",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                SpaceState.ACTIVE, now, Optional.empty(), Optional.empty());
        spaces.save(space);
        TownSpace found = spaces.findByTownUuid(space.townUuid()).orElseThrow();
        assertEquals(now.toEpochMilli(), found.createdAt().toEpochMilli());
    }

    @Test
    void saveWithArchivedAt() {
        Instant archivedAt = Instant.now().plusSeconds(100);
        TownSpace space = new TownSpace(UUID.randomUUID(), "Pueblo",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                SpaceState.ARCHIVED, Instant.now(), Optional.of(archivedAt), Optional.empty());
        spaces.save(space);
        TownSpace found = spaces.findByTownUuid(space.townUuid()).orElseThrow();
        assertTrue(found.archivedAt().isPresent());
        assertEquals(archivedAt.toEpochMilli(), found.archivedAt().get().toEpochMilli());
    }

    // --- delete ---

    @Test
    void deleteDeletesTheSpace() {
        TownSpace space = newSpace(SpaceState.ACTIVE);
        spaces.save(space);
        spaces.delete(space.townUuid());
        assertTrue(spaces.findByTownUuid(space.townUuid()).isEmpty());
    }

    @Test
    void deleteIsNotAnErrorWhenDoesNotExist() {
        assertDoesNotThrow(() -> spaces.delete(UUID.randomUUID()));
    }

    // --- updateState ---

    @Test
    void updateStateUpdatesState() {
        TownSpace space = newSpace(SpaceState.ACTIVE);
        spaces.save(space);
        spaces.updateState(space.townUuid(), SpaceState.ARCHIVED);
        TownSpace found = spaces.findByTownUuid(space.townUuid()).orElseThrow();
        assertEquals(SpaceState.ARCHIVED, found.state());
    }

    @Test
    void updateStateToInconsistent() {
        TownSpace space = newSpace(SpaceState.ACTIVE);
        spaces.save(space);
        spaces.updateState(space.townUuid(), SpaceState.INCONSISTENT);
        assertEquals(SpaceState.INCONSISTENT,
                spaces.findByTownUuid(space.townUuid()).orElseThrow().state());
    }

    // --- touchActivity ---

    @Test
    void touchActivityUpdatesLastActivityDate() {
        TownSpace space = newSpace(SpaceState.ACTIVE);
        spaces.save(space);
        Instant activity = Instant.now().plusSeconds(500);
        spaces.touchActivity(space.townUuid(), activity);
        TownSpace found = spaces.findByTownUuid(space.townUuid()).orElseThrow();
        assertTrue(found.lastActivityAt().isPresent());
        assertEquals(activity.toEpochMilli(), found.lastActivityAt().get().toEpochMilli());
    }

    // --- countActive ---

    @Test
    void countActiveCountsOnlyActive() {
        spaces.save(newSpace(SpaceState.ACTIVE));
        spaces.save(newSpace(SpaceState.ACTIVE));
        spaces.save(newSpace(SpaceState.ARCHIVED));
        spaces.save(newSpace(SpaceState.INCONSISTENT));
        assertEquals(2, spaces.countActive());
    }

    @Test
    void countActiveIsZeroWhenThereIsNothing() {
        assertEquals(0, spaces.countActive());
    }

    // --- isComplete ---

    @Test
    void isCompleteWithTextAndVoice() {
        TownSpace space = completeSpace(UUID.randomUUID(), "Pueblo");
        assertTrue(space.isComplete(true, true));
        assertTrue(space.isComplete(true, false));
        assertTrue(space.isComplete(false, true));
        assertTrue(space.isComplete(false, false));
    }

    @Test
    void isCompleteIncomplete() {
        TownSpace space = new TownSpace(UUID.randomUUID(), "Pueblo",
                Optional.empty(), Optional.of("txt123"), Optional.empty(),
                Optional.of("rol123"), SpaceState.ACTIVE, Instant.now(),
                Optional.empty(), Optional.empty());
        // Has text and role, lacks voice.
        assertFalse(space.isComplete(false, true), "Missing voice");
        assertTrue(space.isComplete(true, false), "Only asks for text and there is text");
        assertTrue(space.isComplete(false, false), "Asks for nothing: always complete");
    }
}
