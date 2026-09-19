package com.discordtowny.space;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.discord.GuildOperation;
import com.discordtowny.discord.OperationOutcome;
import com.discordtowny.model.SpaceRequest;
import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSpace;
import com.discordtowny.space.SpaceService.CreateResult;
import com.discordtowny.storage.HikariStorage;
import com.discordtowny.storage.SpaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DefaultSpaceService}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Each {@link CreateResult} value for the specific precondition or outcome it represents.</li>
 *   <li>Resumption of an interrupted space creation without duplicating roles or channels.</li>
 *   <li>Archiving that preserves channel IDs and sets read-only state without deleting channels.</li>
 *   <li>Restoration that reuses existing channel IDs instead of creating new ones.</li>
 *   <li>Permanent deletion of archived spaces during purgeArchived while preserving active spaces.</li>
 *   <li>Renaming, finding, and error handling.</li>
 * </ul>
 */
class DefaultSpaceServiceTest {

    private Path dbFile;
    private HikariStorage storage;
    private SpaceRepository spaceRepository;
    private MutableClock clock;
    private PluginConfig config;
    private DiscordGateway discordGateway;
    private DefaultSpaceService service;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("discordtowny-spacetest-", ".db");

        PluginConfig.Database dbConfig = new PluginConfig.Database(
                PluginConfig.Database.Type.SQLITE,
                "localhost",
                3306,
                dbFile.toAbsolutePath().toString(),
                "",
                "",
                "dt_",
                1,
                1,
                Duration.ofSeconds(5));

        storage = new HikariStorage(dbConfig, Logger.getLogger("DefaultSpaceServiceTest"));
        storage.initialize();
        spaceRepository = storage.spaces();

        clock = new MutableClock(Instant.parse("2026-09-18T12:00:00Z"));

        config = new PluginConfig(
                new PluginConfig.Discord("token-test", "guild-12345", Optional.empty()),
                dbConfig,
                new PluginConfig.Structure("Comunidades", "Archivo", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Alcalde", "{town}", Optional.empty(), false),
                new PluginConfig.Limits(2, 2, Duration.ofSeconds(60)), // max 2 towns, min 2 residents, 60s cooldown
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                new PluginConfig.Sync(Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPAIR, 20, Duration.ofSeconds(5)),
                new PluginConfig.Linking(Duration.ofMinutes(10), 3, Duration.ofMinutes(15), true),
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );

        discordGateway = mock(DiscordGateway.class);
        when(discordGateway.isAvailable()).thenReturn(true);
        when(discordGateway.verifyPermissions()).thenReturn(Optional.empty());
        when(discordGateway.submit(any())).thenReturn(CompletableFuture.completedFuture(OperationOutcome.success()));

        service = new DefaultSpaceService(
                spaceRepository,
                config,
                discordGateway,
                clock,
                ForkJoinPool.commonPool()
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        storage.close();
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-shm"));
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-wal"));
        }
    }

    // --- CreateResult verifications ---

    @Test
    @DisplayName("Create fails with MAYOR_NOT_LINKED when mayor discord ID is null")
    void createFailsWithMayorNotLinkedWhenMayorDiscordIdIsNull() {
        SpaceRequest req = new SpaceRequest(
                UUID.randomUUID(), "Riverwood", UUID.randomUUID(), List.of("res1", "res2"), null, 2);

        CreateResult result = service.create(req).join();

        assertEquals(CreateResult.MAYOR_NOT_LINKED, result);
        verify(discordGateway, never()).submit(any());
    }

    @Test
    @DisplayName("Create fails with MAYOR_NOT_LINKED when mayor discord ID is blank")
    void createFailsWithMayorNotLinkedWhenMayorDiscordIdIsBlank() {
        SpaceRequest req = new SpaceRequest(
                UUID.randomUUID(), "Riverwood", UUID.randomUUID(), List.of("res1", "res2"), "   ", 2);

        CreateResult result = service.create(req).join();

        assertEquals(CreateResult.MAYOR_NOT_LINKED, result);
        verify(discordGateway, never()).submit(any());
    }

    @Test
    @DisplayName("Create fails with TOO_FEW_RESIDENTS when the town population is below the configured minimum")
    void createFailsWithTooFewResidentsWhenBelowMinimum() {
        // minResidents is 2; the town has only one resident in Towny
        SpaceRequest req = new SpaceRequest(
                UUID.randomUUID(), "Riverwood", UUID.randomUUID(), List.of("mayor-discord"), "mayor-discord", 1);

        CreateResult result = service.create(req).join();

        assertEquals(CreateResult.TOO_FEW_RESIDENTS, result);
        verify(discordGateway, never()).submit(any());
    }

    @Test
    @DisplayName("Create is not rejected when the town meets the minimum but few residents have linked")
    void createIsNotRejectedWhenTownMeetsMinimumButFewResidentsLinked() {
        // The minimum is measured against the town population, not against how
        // many residents got around to linking. Only the mayor must be linked.
        SpaceRequest req = new SpaceRequest(
                UUID.randomUUID(), "Riverwood", UUID.randomUUID(), List.of("mayor-discord"), "mayor-discord", 8);

        CreateResult result = service.create(req).join();

        assertNotEquals(CreateResult.TOO_FEW_RESIDENTS, result);
    }

    @Test
    @DisplayName("Create fails with ON_COOLDOWN when second creation is attempted within cooldown duration")
    void createFailsWithOnCooldownWhenAttemptedWithinCooldownWindow() {
        UUID townUuid = UUID.randomUUID();
        UUID mayorUuid = UUID.randomUUID();
        SpaceRequest req = new SpaceRequest(
                townUuid, "Riverwood", mayorUuid, List.of("res1", "res2"), "mayor-discord", 2);

        // First attempt succeeds
        CreateResult firstResult = service.create(req).join();
        assertEquals(CreateResult.SUCCESS, firstResult);

        // Advance clock by only 10 seconds (cooldown is 60 seconds)
        clock.advance(Duration.ofSeconds(10));

        // Second attempt for the same town
        CreateResult secondResult = service.create(req).join();
        assertEquals(CreateResult.ON_COOLDOWN, secondResult);

        // Only the first attempt was submitted to Discord queue
        verify(discordGateway, times(1)).submit(any(GuildOperation.CreateSpace.class));
    }

    @Test
    @DisplayName("Create fails with ALREADY_EXISTS when town space is already active and not on cooldown")
    void createFailsWithAlreadyExistsWhenSpaceIsAlreadyActive() {
        UUID townUuid = UUID.randomUUID();
        TownSpace activeSpace = new TownSpace(
                townUuid, "Riverwood",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("voice-1"), Optional.of("role-1"),
                SpaceState.ACTIVE,
                clock.instant().minus(Duration.ofMinutes(10)),
                Optional.empty(),
                Optional.of(clock.instant().minus(Duration.ofMinutes(10))));
        spaceRepository.save(activeSpace);

        // Past cooldown window
        clock.advance(Duration.ofMinutes(5));

        SpaceRequest req = new SpaceRequest(
                townUuid, "Riverwood", UUID.randomUUID(), List.of("res1", "res2"), "mayor-discord", 2);

        CreateResult result = service.create(req).join();

        assertEquals(CreateResult.ALREADY_EXISTS, result);
        verify(discordGateway, never()).submit(any());
    }

    @Test
    @DisplayName("Create fails with ALREADY_EXISTS when town space is archived and must be restored")
    void createFailsWithAlreadyExistsWhenSpaceIsArchived() {
        UUID townUuid = UUID.randomUUID();
        TownSpace archivedSpace = new TownSpace(
                townUuid, "Riverwood",
                Optional.of("cat-archived"), Optional.of("txt-1"), Optional.of("voice-1"), Optional.empty(),
                SpaceState.ARCHIVED,
                clock.instant().minus(Duration.ofDays(2)),
                Optional.of(clock.instant().minus(Duration.ofDays(1))),
                Optional.empty());
        spaceRepository.save(archivedSpace);

        clock.advance(Duration.ofHours(1));

        SpaceRequest req = new SpaceRequest(
                townUuid, "Riverwood", UUID.randomUUID(), List.of("res1", "res2"), "mayor-discord", 2);

        CreateResult result = service.create(req).join();

        assertEquals(CreateResult.ALREADY_EXISTS, result);
        verify(discordGateway, never()).submit(any());
    }

    @Test
    @DisplayName("Create fails with LIMIT_REACHED when count of active spaces reaches max-towns")
    void createFailsWithLimitReachedWhenAtActiveCapacity() {
        // Limit is 2 active towns. Populate 2 active spaces.
        TownSpace s1 = new TownSpace(UUID.randomUUID(), "TownOne",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("voz-1"), Optional.of("rol-1"),
                SpaceState.ACTIVE, clock.instant(), Optional.empty(), Optional.empty());
        TownSpace s2 = new TownSpace(UUID.randomUUID(), "TownTwo",
                Optional.of("cat-2"), Optional.of("txt-2"), Optional.of("voz-2"), Optional.of("rol-2"),
                SpaceState.ACTIVE, clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(s1);
        spaceRepository.save(s2);

        assertEquals(2, spaceRepository.countActive());

        SpaceRequest req = new SpaceRequest(
                UUID.randomUUID(), "TownThree", UUID.randomUUID(), List.of("res1", "res2"), "mayor-discord", 2);

        CreateResult result = service.create(req).join();

        assertEquals(CreateResult.LIMIT_REACHED, result);
        verify(discordGateway, never()).submit(any());
    }

    @Test
    @DisplayName("Create fails with DISCORD_UNAVAILABLE when gateway is not connected")
    void createFailsWithDiscordUnavailableWhenGatewayOffline() {
        when(discordGateway.isAvailable()).thenReturn(false);

        SpaceRequest req = new SpaceRequest(
                UUID.randomUUID(), "Riverwood", UUID.randomUUID(), List.of("res1", "res2"), "mayor-discord", 2);

        CreateResult result = service.create(req).join();

        assertEquals(CreateResult.DISCORD_UNAVAILABLE, result);
        verify(discordGateway, never()).submit(any());
    }

    @Test
    @DisplayName("Create fails with DISCORD_UNAVAILABLE when bot lacks permissions")
    void createFailsWithDiscordUnavailableWhenMissingPermissions() {
        when(discordGateway.isAvailable()).thenReturn(true);
        when(discordGateway.verifyPermissions()).thenReturn(Optional.of("Missing MANAGE_CHANNELS permission"));

        SpaceRequest req = new SpaceRequest(
                UUID.randomUUID(), "Riverwood", UUID.randomUUID(), List.of("res1", "res2"), "mayor-discord", 2);

        CreateResult result = service.create(req).join();

        assertEquals(CreateResult.DISCORD_UNAVAILABLE, result);
        verify(discordGateway, never()).submit(any());
    }

    @Test
    @DisplayName("Create fails with FAILED when Discord operation queue reports failure")
    void createFailsWithFailedWhenDiscordOperationFails() {
        when(discordGateway.submit(any()))
                .thenReturn(CompletableFuture.completedFuture(OperationOutcome.permanentFailure("Discord 50001")));

        SpaceRequest req = new SpaceRequest(
                UUID.randomUUID(), "Riverwood", UUID.randomUUID(), List.of("res1", "res2"), "mayor-discord", 2);

        CreateResult result = service.create(req).join();

        assertEquals(CreateResult.FAILED, result);
        verify(discordGateway).submit(any(GuildOperation.CreateSpace.class));
    }

    @Test
    @DisplayName("Create fails with FAILED when request parameters are invalid")
    void createFailsWithFailedWhenRequestIsInvalid() {
        assertEquals(CreateResult.FAILED, service.create(null).join());

        SpaceRequest reqNullTown = new SpaceRequest(
                null, "Riverwood", UUID.randomUUID(), List.of("res1", "res2"), "mayor-discord", 2);
        assertEquals(CreateResult.FAILED, service.create(reqNullTown).join());

        SpaceRequest reqBlankName = new SpaceRequest(
                UUID.randomUUID(), "   ", UUID.randomUUID(), List.of("res1", "res2"), "mayor-discord", 2);
        assertEquals(CreateResult.FAILED, service.create(reqBlankName).join());
    }

    @Test
    @DisplayName("Create succeeds when all preconditions are met")
    void createSucceedsWhenPreconditionsMet() {
        SpaceRequest req = new SpaceRequest(
                UUID.randomUUID(), "Riverwood", UUID.randomUUID(), List.of("res1", "res2"), "mayor-discord", 2);

        CreateResult result = service.create(req).join();

        assertEquals(CreateResult.SUCCESS, result);
        verify(discordGateway).submit(any(GuildOperation.CreateSpace.class));
        verify(discordGateway).log(argThat(event ->
                event.action().equals("space_create") && event.success()));
    }

    // --- Idempotency & Resumption ---

    @Test
    @DisplayName("Creation interrupted after role is resumed and completed without being rejected as ALREADY_EXISTS")
    void creationInterruptedAfterRoleResumesAndCompletes() {
        UUID townUuid = UUID.randomUUID();
        String townName = "Falkreath";
        String existingRoleId = "role-interrupted-456";
        String existingCatId = "cat-interrupted-123";

        // Space was interrupted during creation: category and role were persisted, channels missing, state is INCONSISTENT
        TownSpace interrupted = new TownSpace(
                townUuid, townName,
                Optional.of(existingCatId), Optional.empty(), Optional.empty(), Optional.of(existingRoleId),
                SpaceState.INCONSISTENT,
                clock.instant(), Optional.empty(), Optional.of(clock.instant()));
        spaceRepository.save(interrupted);

        // Simulated Discord executor finishing the space when CreateSpace is submitted
        when(discordGateway.submit(any(GuildOperation.CreateSpace.class))).thenAnswer(invocation -> {
            // Update repository as JdaGuildOperationExecutor would upon completion
            TownSpace completed = new TownSpace(
                    townUuid, townName,
                    Optional.of(existingCatId), Optional.of("txt-new-789"), Optional.of("voice-new-999"), Optional.of(existingRoleId),
                    SpaceState.ACTIVE,
                    interrupted.createdAt(), Optional.empty(), Optional.of(clock.instant()));
            spaceRepository.save(completed);
            return CompletableFuture.completedFuture(OperationOutcome.success());
        });

        SpaceRequest req = new SpaceRequest(
                townUuid, townName, UUID.randomUUID(), List.of("res1", "res2"), "mayor-discord", 2);

        // Calling create must NOT reject as ALREADY_EXISTS; it must submit CreateSpace and succeed
        CreateResult result = service.create(req).join();

        assertEquals(CreateResult.SUCCESS, result);

        ArgumentCaptor<GuildOperation> opCaptor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(opCaptor.capture());
        assertInstanceOf(GuildOperation.CreateSpace.class, opCaptor.getValue());

        TownSpace finalSpace = spaceRepository.findByTownUuid(townUuid).orElseThrow();
        assertEquals(SpaceState.ACTIVE, finalSpace.state());
        // Preserved the existing role ID rather than creating a duplicate
        assertEquals(Optional.of(existingRoleId), finalSpace.roleId());
        assertEquals(Optional.of(existingCatId), finalSpace.categoryId());
        assertTrue(finalSpace.textChannelId().isPresent());
        assertTrue(finalSpace.voiceChannelId().isPresent());
    }

    // --- Archiving ---

    @Test
    @DisplayName("Archiving moves channels and deletes role without deleting channels from storage")
    void archivingPreservesChannelsAndUpdatesState() {
        UUID townUuid = UUID.randomUUID();
        String textChannelId = "txt-persist-111";
        String voiceChannelId = "voice-persist-222";

        TownSpace activeSpace = new TownSpace(
                townUuid, "Dawnstar",
                Optional.of("cat-active"), Optional.of(textChannelId), Optional.of(voiceChannelId), Optional.of("role-old"),
                SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(activeSpace);

        service.archive(townUuid, "Town fell into ruins").join();

        ArgumentCaptor<GuildOperation> opCaptor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(opCaptor.capture());
        assertInstanceOf(GuildOperation.ArchiveSpace.class, opCaptor.getValue());
        GuildOperation.ArchiveSpace archiveOp = (GuildOperation.ArchiveSpace) opCaptor.getValue();
        assertEquals(townUuid, archiveOp.townUuid());

        TownSpace archivedSpace = spaceRepository.findByTownUuid(townUuid).orElseThrow();
        assertEquals(SpaceState.ARCHIVED, archivedSpace.state());
        // Channels remain intact in repository: nothing deletes channels on archiving
        assertEquals(Optional.of(textChannelId), archivedSpace.textChannelId());
        assertEquals(Optional.of(voiceChannelId), archivedSpace.voiceChannelId());

        // Verify that DeleteSpace was never submitted
        verify(discordGateway, never()).submit(any(GuildOperation.DeleteSpace.class));
    }

    @Test
    @DisplayName("Archiving an already archived space is idempotent and does not resubmit")
    void archivingAlreadyArchivedSpaceIsIdempotent() {
        UUID townUuid = UUID.randomUUID();
        TownSpace archivedSpace = new TownSpace(
                townUuid, "Solitude",
                Optional.of("cat-archive"), Optional.of("txt-1"), Optional.of("voice-1"), Optional.empty(),
                SpaceState.ARCHIVED,
                clock.instant(), Optional.of(clock.instant()), Optional.empty());
        spaceRepository.save(archivedSpace);

        service.archive(townUuid, "Already archived").join();

        verify(discordGateway, never()).submit(any());
    }

    // --- Restoration ---

    @Test
    @DisplayName("Restoration reuses existing channels instead of creating new ones")
    void restorationReusesExistingChannels() {
        UUID townUuid = UUID.randomUUID();
        String textChannelId = "txt-existing-333";
        String voiceChannelId = "voice-existing-444";

        TownSpace archivedSpace = new TownSpace(
                townUuid, "Whiterun",
                Optional.of("cat-archive"), Optional.of(textChannelId), Optional.of(voiceChannelId), Optional.empty(),
                SpaceState.ARCHIVED,
                clock.instant().minus(Duration.ofDays(5)),
                Optional.of(clock.instant().minus(Duration.ofDays(1))),
                Optional.empty());
        spaceRepository.save(archivedSpace);

        when(discordGateway.submit(any(GuildOperation.RestoreSpace.class))).thenAnswer(invocation -> {
            // Restore space reuses the channels and assigns a new role
            TownSpace restored = new TownSpace(
                    townUuid, "Whiterun",
                    Optional.of("cat-active"), Optional.of(textChannelId), Optional.of(voiceChannelId), Optional.of("role-new-888"),
                    SpaceState.ACTIVE,
                    archivedSpace.createdAt(), Optional.empty(), Optional.of(clock.instant()));
            spaceRepository.save(restored);
            return CompletableFuture.completedFuture(OperationOutcome.success());
        });

        SpaceRequest req = new SpaceRequest(
                townUuid, "Whiterun", UUID.randomUUID(), List.of("res1", "res2"), "mayor-discord", 2);

        service.restore(req).join();

        ArgumentCaptor<GuildOperation> opCaptor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(opCaptor.capture());
        assertInstanceOf(GuildOperation.RestoreSpace.class, opCaptor.getValue());

        TownSpace activeSpace = spaceRepository.findByTownUuid(townUuid).orElseThrow();
        assertEquals(SpaceState.ACTIVE, activeSpace.state());
        // Channel IDs are unchanged: existing channels were reused
        assertEquals(Optional.of(textChannelId), activeSpace.textChannelId());
        assertEquals(Optional.of(voiceChannelId), activeSpace.voiceChannelId());

        // CreateSpace was never submitted
        verify(discordGateway, never()).submit(any(GuildOperation.CreateSpace.class));
    }

    @Test
    @DisplayName("Restoration of an already active space is idempotent")
    void restorationOfActiveSpaceIsIdempotent() {
        UUID townUuid = UUID.randomUUID();
        TownSpace activeSpace = new TownSpace(
                townUuid, "Whiterun",
                Optional.of("cat-active"), Optional.of("txt-1"), Optional.of("voz-1"), Optional.of("rol-1"),
                SpaceState.ACTIVE, clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(activeSpace);

        SpaceRequest req = new SpaceRequest(
                townUuid, "Whiterun", UUID.randomUUID(), List.of("res1", "res2"), "mayor-discord", 2);

        service.restore(req).join();

        verify(discordGateway, never()).submit(any());
    }

    // --- Purge Archived ---

    @Test
    @DisplayName("Purge archived permanently deletes archived spaces and preserves active spaces")
    void purgeArchivedDeletesOnlyArchivedSpaces() {
        UUID activeTown = UUID.randomUUID();
        UUID archivedTown1 = UUID.randomUUID();
        UUID archivedTown2 = UUID.randomUUID();

        TownSpace active = new TownSpace(activeTown, "ActiveTown",
                Optional.of("cat-1"), Optional.of("t1"), Optional.of("v1"), Optional.of("r1"),
                SpaceState.ACTIVE, clock.instant(), Optional.empty(), Optional.empty());
        TownSpace archived1 = new TownSpace(archivedTown1, "ArchivedTown1",
                Optional.of("cat-a"), Optional.of("t2"), Optional.of("v2"), Optional.empty(),
                SpaceState.ARCHIVED, clock.instant(), Optional.of(clock.instant()), Optional.empty());
        TownSpace archived2 = new TownSpace(archivedTown2, "ArchivedTown2",
                Optional.of("cat-a"), Optional.of("t3"), Optional.of("v3"), Optional.empty(),
                SpaceState.ARCHIVED, clock.instant(), Optional.of(clock.instant()), Optional.empty());

        spaceRepository.save(active);
        spaceRepository.save(archived1);
        spaceRepository.save(archived2);

        int purgedCount = service.purgeArchived().join();

        assertEquals(2, purgedCount);
        assertTrue(spaceRepository.findByTownUuid(archivedTown1).isEmpty());
        assertTrue(spaceRepository.findByTownUuid(archivedTown2).isEmpty());
        // Active space was not touched
        assertTrue(spaceRepository.findByTownUuid(activeTown).isPresent());
        assertEquals(SpaceState.ACTIVE, spaceRepository.findByTownUuid(activeTown).get().state());

        verify(discordGateway, times(2)).submit(any(GuildOperation.DeleteSpace.class));
    }

    @Test
    @DisplayName("Purge archived returns zero when there are no archived spaces")
    void purgeArchivedReturnsZeroWhenEmpty() {
        int purged = service.purgeArchived().join();
        assertEquals(0, purged);
        verify(discordGateway, never()).submit(any());
    }

    // --- Rename ---

    @Test
    @DisplayName("Rename submits rename operation with old and new names")
    void renameSubmitsRenameOperation() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(townUuid, "OldTownName",
                Optional.of("cat-1"), Optional.of("t1"), Optional.of("v1"), Optional.of("r1"),
                SpaceState.ACTIVE, clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        service.rename(townUuid, "NewTownName").join();

        ArgumentCaptor<GuildOperation> opCaptor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(opCaptor.capture());
        assertInstanceOf(GuildOperation.RenameSpace.class, opCaptor.getValue());
        GuildOperation.RenameSpace renameOp = (GuildOperation.RenameSpace) opCaptor.getValue();
        assertEquals("OldTownName", renameOp.oldName());
        assertEquals("NewTownName", renameOp.newName());
    }

    @Test
    @DisplayName("Rename fails exceptionally when space does not exist")
    void renameFailsWhenSpaceNotFound() {
        UUID randomUuid = UUID.randomUUID();
        assertThrows(CompletionException.class, () ->
                service.rename(randomUuid, "NonExistentTown").join());
    }

    // --- Find & FindAll ---

    @Test
    @DisplayName("Find and findAll correctly query the repository")
    void findAndFindAllReturnSpaces() {
        UUID uuid = UUID.randomUUID();
        TownSpace space = new TownSpace(uuid, "Markarth",
                Optional.of("cat-1"), Optional.of("t1"), Optional.of("v1"), Optional.of("r1"),
                SpaceState.ACTIVE, clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        Optional<TownSpace> found = service.find(uuid).join();
        assertTrue(found.isPresent());
        assertEquals("Markarth", found.get().townName());

        List<TownSpace> all = service.findAll().join();
        assertEquals(1, all.size());
        assertEquals("Markarth", all.getFirst().townName());

        assertTrue(service.find(null).join().isEmpty());
    }
}
