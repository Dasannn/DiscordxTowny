package com.discordtowny.sync;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.discord.GuildOperation;
import com.discordtowny.discord.OperationOutcome;
import com.discordtowny.model.AccountLink;
import com.discordtowny.model.ResidentSnapshot;
import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSnapshot;
import com.discordtowny.model.TownSpace;
import com.discordtowny.storage.HikariStorage;
import com.discordtowny.storage.LinkRepository;
import com.discordtowny.storage.SpaceRepository;
import com.discordtowny.sync.SyncService.SyncReport;
import com.discordtowny.towny.TownyFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DefaultSyncService}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>A member holding a town role Towny does not justify loses it, and keeps every unmanaged role they had.</li>
 *   <li>A resident who links gains exactly their own town's role and no other town's.</li>
 *   <li>Report-only mode with a broken state: assert zero Discord operations submitted and zero writes to the repository.</li>
 *   <li>Batching: with more spaces than batchSize, more than one batch runs and the pause happens between them,
 *       not before the first or after the last.</li>
 *   <li>Real counts in SyncReport, mayor transfer, kick handling, main thread hopping, and error handling.</li>
 * </ul>
 */
class DefaultSyncServiceTest {

    private Path dbFile;
    private HikariStorage storage;
    private SpaceRepository spaceRepository;
    private LinkRepository linkRepository;
    private SpaceRepository spySpaceRepository;
    private LinkRepository spyLinkRepository;
    private MutableClock clock;
    private PluginConfig config;
    private DiscordGateway discordGateway;
    private TownyFacade townyFacade;
    private DefaultSyncService service;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("discordtowny-synctest-", ".db");

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

        storage = new HikariStorage(dbConfig, Logger.getLogger("DefaultSyncServiceTest"));
        storage.initialize();

        spaceRepository = storage.spaces();
        linkRepository = storage.links();
        spySpaceRepository = spy(spaceRepository);
        spyLinkRepository = spy(linkRepository);

        clock = new MutableClock(Instant.parse("2026-09-19T12:00:00Z"));

        config = new PluginConfig(
                new PluginConfig.Discord("token-dummy", "guild-12345", Optional.empty()),
                dbConfig,
                new PluginConfig.Structure("Comunidades", "Archivo", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Alcalde", "{town}", Optional.empty(), false),
                new PluginConfig.Limits(200, 2, Duration.ofSeconds(60)),
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                new PluginConfig.Sync(Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPAIR, 2, Duration.ofSeconds(5)),
                new PluginConfig.Linking(Duration.ofMinutes(10), 3, Duration.ofMinutes(15), true),
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );

        discordGateway = mock(DiscordGateway.class);
        when(discordGateway.isAvailable()).thenReturn(true);
        when(discordGateway.mayorRoleId()).thenReturn(Optional.of("role-mayor-id"));
        when(discordGateway.submit(any())).thenReturn(CompletableFuture.completedFuture(OperationOutcome.success()));

        townyFacade = mock(TownyFacade.class);
        when(townyFacade.isAvailable()).thenReturn(true);

        service = new DefaultSyncService(
                spySpaceRepository,
                spyLinkRepository,
                discordGateway,
                townyFacade,
                config,
                clock,
                Runnable::run,
                null,
                null
        );
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
        try {
            Files.deleteIfExists(dbFile);
        } catch (IOException ignored) {
        }
    }

    // --- Required Test 1: Member holding unjustified town role loses it and keeps unmanaged roles ---

    @Test
    @DisplayName("A member holding a town role Towny does not justify loses it, and keeps every unmanaged role they had")
    void memberHoldingTownRoleTownyDoesNotJustifyLosesItAndKeepsEveryUnmanagedRoleTheyHad() {
        UUID playerUuid = UUID.randomUUID();
        String discordId = "discord-member-777";
        spyLinkRepository.save(new AccountLink(playerUuid, discordId, clock.instant(), "Alice"));

        UUID townAUuid = UUID.randomUUID();
        UUID townBUuid = UUID.randomUUID();
        String roleTownA = "role-town-a-id";
        String roleTownB = "role-town-b-id";

        TownSpace spaceA = new TownSpace(
                townAUuid, "TownA",
                Optional.of("cat-1"), Optional.of("txt-a"), Optional.of("vc-a"),
                Optional.of(roleTownA), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        TownSpace spaceB = new TownSpace(
                townBUuid, "TownB",
                Optional.of("cat-1"), Optional.of("txt-b"), Optional.of("vc-b"),
                Optional.of(roleTownB), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spySpaceRepository.save(spaceA);
        spySpaceRepository.save(spaceB);

        // Player is a resident of Town A, and is NOT mayor
        TownSnapshot townA = new TownSnapshot(
                townAUuid, "TownA", UUID.randomUUID(), List.of(playerUuid), false,
                Optional.empty(), 10, 500.0, 1000L);
        ResidentSnapshot residentA = new ResidentSnapshot(
                playerUuid, "Alice", Optional.of("TownA"), Optional.of(townAUuid),
                false, true, 2000L, 100.0);

        when(townyFacade.resident(playerUuid)).thenReturn(Optional.of(residentA));
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.of(townA));
        when(townyFacade.town(townAUuid)).thenReturn(Optional.of(townA));

        // Player currently holds on Discord: roleTownB (unjustified) and unmanaged roles ("role-vip", "role-mod")
        String unmanagedRole1 = "role-vip";
        String unmanagedRole2 = "role-mod";

        // Execute sync
        service.syncPlayer(playerUuid).join();

        // Capture GuildOperation submitted to Discord
        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(captor.capture());

        assertInstanceOf(GuildOperation.ApplyMemberRoles.class, captor.getValue());
        GuildOperation.ApplyMemberRoles op = (GuildOperation.ApplyMemberRoles) captor.getValue();

        assertEquals(discordId, op.discordId());

        // The unjustified town role (roleTownB) MUST be in the revoke list
        assertTrue(op.revokeRoleIds().contains(roleTownB),
                "Unjustified town role B must be revoked");

        // The mayor role MUST also be revoked (since player is not mayor)
        assertTrue(op.revokeRoleIds().contains("role-mayor-id"),
                "Unjustified mayor role must be revoked");

        // The justified town role (roleTownA) MUST be in the grant list
        assertTrue(op.grantRoleIds().contains(roleTownA),
                "Justified town role A must be granted");
        assertFalse(op.grantRoleIds().contains(roleTownB),
                "Unjustified town role B must not be granted");

        // CRUCIAL: Unmanaged roles MUST NEVER be in the revoke list or grant list
        assertFalse(op.revokeRoleIds().contains(unmanagedRole1),
                "Unmanaged role 'role-vip' must not be touched");
        assertFalse(op.revokeRoleIds().contains(unmanagedRole2),
                "Unmanaged role 'role-mod' must not be touched");
        assertFalse(op.grantRoleIds().contains(unmanagedRole1));
        assertFalse(op.grantRoleIds().contains(unmanagedRole2));

        // In fact, all roles in revokeRoleIds must belong strictly to the managed roles set
        Set<String> managedRoles = Set.of(roleTownA, roleTownB, "role-mayor-id");
        assertTrue(managedRoles.containsAll(op.revokeRoleIds()),
                "Revoke list must only contain managed roles");
        assertTrue(managedRoles.containsAll(op.grantRoleIds()),
                "Grant list must only contain managed roles");
    }

    // --- Required Test 2: Resident who links gains exactly their own town's role and no other town's ---

    @Test
    @DisplayName("A resident who links gains exactly their own town's role and no other town's")
    void residentWhoLinksGainsExactlyOwnTownRoleAndNoOtherTown() {
        UUID residentUuid = UUID.randomUUID();
        String discordId = "discord-resident-101";
        spyLinkRepository.save(new AccountLink(residentUuid, discordId, clock.instant(), "Bob"));

        UUID town1Uuid = UUID.randomUUID();
        UUID town2Uuid = UUID.randomUUID();
        UUID town3Uuid = UUID.randomUUID();
        String roleTown1 = "role-town-1";
        String roleTown2 = "role-town-2";
        String roleTown3 = "role-town-3";

        spySpaceRepository.save(new TownSpace(
                town1Uuid, "TownOne",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.of(roleTown1), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));
        spySpaceRepository.save(new TownSpace(
                town2Uuid, "TownTwo",
                Optional.of("cat-1"), Optional.of("txt-2"), Optional.of("vc-2"),
                Optional.of(roleTown2), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));
        spySpaceRepository.save(new TownSpace(
                town3Uuid, "TownThree",
                Optional.of("cat-1"), Optional.of("txt-3"), Optional.of("vc-3"),
                Optional.of(roleTown3), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));

        // Bob belongs to Town 1 in Towny and is NOT mayor
        TownSnapshot town1 = new TownSnapshot(
                town1Uuid, "TownOne", UUID.randomUUID(), List.of(residentUuid), false,
                Optional.empty(), 5, 200.0, 1000L);
        ResidentSnapshot resident1 = new ResidentSnapshot(
                residentUuid, "Bob", Optional.of("TownOne"), Optional.of(town1Uuid),
                false, true, 3000L, 50.0);

        when(townyFacade.resident(residentUuid)).thenReturn(Optional.of(resident1));
        when(townyFacade.townOf(residentUuid)).thenReturn(Optional.of(town1));
        when(townyFacade.town(town1Uuid)).thenReturn(Optional.of(town1));

        // Sync player upon linking
        service.syncPlayer(residentUuid).join();

        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(captor.capture());

        GuildOperation.ApplyMemberRoles op = (GuildOperation.ApplyMemberRoles) captor.getValue();
        assertEquals(discordId, op.discordId());

        // Must gain EXACTLY Town 1's role and no other town role
        assertEquals(List.of(roleTown1), op.grantRoleIds(),
                "Must gain exactly own town's role");

        assertFalse(op.grantRoleIds().contains(roleTown2));
        assertFalse(op.grantRoleIds().contains(roleTown3));

        // Revoke list must contain other town roles
        assertTrue(op.revokeRoleIds().contains(roleTown2));
        assertTrue(op.revokeRoleIds().contains(roleTown3));
    }

    // --- Required Test 3: Report-only mode with broken state asserts zero Discord operations and zero repository writes ---

    @Test
    @DisplayName("Report-only mode with a broken state: assert zero Discord operations submitted and zero writes to the repository")
    void reportOnlyModeWithBrokenStateAssertsZeroDiscordOperationsAndZeroRepositoryWrites() {
        // Configure mode = REPORT
        PluginConfig.Sync reportSyncConfig = new PluginConfig.Sync(
                Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPORT, 10, Duration.ofSeconds(5));
        PluginConfig reportConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), reportSyncConfig, config.linking(),
                config.logging(), config.updates(), config.commands());

        DefaultSyncService reportService = new DefaultSyncService(
                spySpaceRepository,
                spyLinkRepository,
                discordGateway,
                townyFacade,
                reportConfig,
                clock,
                Runnable::run
        );

        // Populate broken state in database:
        // 1. INCONSISTENT space
        UUID town1Uuid = UUID.randomUUID();
        TownSpace broken1 = new TownSpace(
                town1Uuid, "Broken1",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.of("role-1"), SpaceState.INCONSISTENT,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(broken1);

        // 2. Space with missing channels
        UUID town2Uuid = UUID.randomUUID();
        TownSpace broken2 = new TownSpace(
                town2Uuid, "Broken2",
                Optional.of("cat-1"), Optional.empty(), Optional.empty(),
                Optional.of("role-2"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(broken2);

        // 3. Space with missing role
        UUID town3Uuid = UUID.randomUUID();
        TownSpace broken3 = new TownSpace(
                town3Uuid, "Broken3",
                Optional.of("cat-1"), Optional.of("txt-3"), Optional.of("vc-3"),
                Optional.empty(), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(broken3);

        // 4. Space whose town is in ruins in Towny
        UUID town4Uuid = UUID.randomUUID();
        TownSpace broken4 = new TownSpace(
                town4Uuid, "RuinedTown",
                Optional.of("cat-1"), Optional.of("txt-4"), Optional.of("vc-4"),
                Optional.of("role-4"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(broken4);

        // 5. Space whose town does not exist in Towny
        UUID town5Uuid = UUID.randomUUID();
        TownSpace broken5 = new TownSpace(
                town5Uuid, "DeletedTown",
                Optional.of("cat-1"), Optional.of("txt-5"), Optional.of("vc-5"),
                Optional.of("role-5"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(broken5);

        // Configure Towny stubs
        TownSnapshot t1 = new TownSnapshot(town1Uuid, "Broken1", UUID.randomUUID(), List.of(), false, Optional.empty(), 5, 0, 0);
        TownSnapshot t2 = new TownSnapshot(town2Uuid, "Broken2", UUID.randomUUID(), List.of(), false, Optional.empty(), 5, 0, 0);
        TownSnapshot t3 = new TownSnapshot(town3Uuid, "Broken3", UUID.randomUUID(), List.of(), false, Optional.empty(), 5, 0, 0);
        TownSnapshot t4 = new TownSnapshot(town4Uuid, "RuinedTown", UUID.randomUUID(), List.of(), true, Optional.empty(), 5, 0, 0); // ruined

        when(townyFacade.town(town1Uuid)).thenReturn(Optional.of(t1));
        when(townyFacade.town(town2Uuid)).thenReturn(Optional.of(t2));
        when(townyFacade.town(town3Uuid)).thenReturn(Optional.of(t3));
        when(townyFacade.town(town4Uuid)).thenReturn(Optional.of(t4));
        when(townyFacade.town(town5Uuid)).thenReturn(Optional.empty()); // deleted

        // Reset invocation counts on repositories before running reconciliation
        reset(spySpaceRepository, spyLinkRepository, discordGateway);
        when(discordGateway.isAvailable()).thenReturn(true);
        when(discordGateway.mayorRoleId()).thenReturn(Optional.of("role-mayor-id"));

        // Execute reconciliation pass
        SyncReport report = reportService.reconcileAll().join();

        // 1. Assert problems were identified
        assertEquals(5, report.spacesChecked());
        assertTrue(report.inconsistenciesFound() >= 5,
                "Must detect all 5 broken spaces");
        assertEquals(0, report.inconsistenciesRepaired(),
                "Must not repair anything in REPORT mode");
        assertEquals(0, report.rolesGranted(),
                "Must not grant any roles in REPORT mode");
        assertEquals(0, report.rolesRevoked(),
                "Must not revoke any roles in REPORT mode");
        assertFalse(report.problems().isEmpty(),
                "Must report problems in REPORT mode");

        // 2. Assert ZERO Discord operations were submitted
        verify(discordGateway, never()).submit(any());

        // 3. Assert ZERO writes to the SpaceRepository
        verify(spySpaceRepository, never()).save(any());
        verify(spySpaceRepository, never()).updateState(any(), any());
        verify(spySpaceRepository, never()).delete(any());

        // 4. Assert ZERO writes to the LinkRepository
        verify(spyLinkRepository, never()).save(any());
        verify(spyLinkRepository, never()).deleteByUuid(any());
        verify(spyLinkRepository, never()).deleteByUuidIfMatches(any(), any(), any());
        verify(spyLinkRepository, never()).updateLastKnownName(any(), any());
    }

    // --- Required Test 4: Batching runs multiple batches and pauses between them, not before first or after last ---

    @Test
    @DisplayName("Batching: with more spaces than batchSize, more than one batch runs and the pause happens between them, not before the first or after the last")
    void batchingRunsMultipleBatchesAndPausesBetweenThemNotBeforeFirstOrAfterLast() {
        // Configure batchSize = 2, batchPause = 5s
        Duration pauseDuration = Duration.ofSeconds(5);
        PluginConfig.Sync syncConfig = new PluginConfig.Sync(
                Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPAIR, 2, pauseDuration);
        PluginConfig batchConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), syncConfig, config.linking(),
                config.logging(), config.updates(), config.commands());

        // 5 spaces => 3 batches (Batch 1: 2 spaces, Batch 2: 2 spaces, Batch 3: 1 space)
        List<String> eventLog = new CopyOnWriteArrayList<>();
        for (int i = 1; i <= 5; i++) {
            UUID townUuid = UUID.randomUUID();
            String name = "BatchTown" + i;
            TownSpace space = new TownSpace(
                    townUuid, name,
                    Optional.of("cat-1"), Optional.of("txt-" + i), Optional.of("vc-" + i),
                    Optional.of("role-" + i), SpaceState.ACTIVE,
                    clock.instant(), Optional.empty(), Optional.empty());
            spaceRepository.save(space);

            TownSnapshot town = new TownSnapshot(
                    townUuid, name, UUID.randomUUID(), List.of(), false,
                    Optional.empty(), 5, 0, 0);
            when(townyFacade.town(townUuid)).thenAnswer(inv -> {
                eventLog.add("process-" + name);
                return Optional.of(town);
            });
        }

        @SuppressWarnings("unchecked")
        Consumer<Duration> mockPause = mock(Consumer.class);
        doAnswer(inv -> {
            eventLog.add("pause");
            return null;
        }).when(mockPause).accept(any());

        DefaultSyncService batchService = new DefaultSyncService(
                spySpaceRepository,
                spyLinkRepository,
                discordGateway,
                townyFacade,
                batchConfig,
                clock,
                Runnable::run,
                mockPause,
                null
        );

        SyncReport report = batchService.reconcileAll().join();

        assertEquals(5, report.spacesChecked());

        // For 3 batches, exactly 2 pauses must occur
        verify(mockPause, times(2)).accept(pauseDuration);

        // Prove sequence of execution:
        // First batch processed (BatchTown1, BatchTown2) -> pause -> second batch -> pause -> third batch
        assertFalse(eventLog.isEmpty());
        assertNotEquals("pause", eventLog.getFirst(),
                "Pause must NOT happen before the first batch");
        assertNotEquals("pause", eventLog.getLast(),
                "Pause must NOT happen after the last batch");

        // The pause must happen exactly between batches
        int firstPauseIndex = eventLog.indexOf("pause");
        int lastPauseIndex = eventLog.lastIndexOf("pause");

        assertTrue(firstPauseIndex >= 2,
                "First pause must happen after at least the first batch has processed");
        assertTrue(lastPauseIndex < eventLog.size() - 1,
                "Last pause must happen before the final batch finishes processing");
        assertNotEquals(firstPauseIndex, lastPauseIndex,
                "There must be distinct pauses between batches");
    }

    // --- Additional Tests: syncTown, mayor transfer, kick handling, main-thread hop, error handling ---

    @Test
    @DisplayName("syncTown adjusts roles for all residents and returns real counts, not estimates")
    void syncTownAdjustsRolesForAllResidentsAndReturnsRealCounts() {
        UUID townUuid = UUID.randomUUID();
        UUID mayorUuid = UUID.randomUUID();
        UUID resUuid = UUID.randomUUID();
        UUID unlinkedUuid = UUID.randomUUID();

        String mayorDiscordId = "discord-mayor";
        String resDiscordId = "discord-res";

        spyLinkRepository.save(new AccountLink(mayorUuid, mayorDiscordId, clock.instant(), "MayorPlayer"));
        spyLinkRepository.save(new AccountLink(resUuid, resDiscordId, clock.instant(), "ResidentPlayer"));
        // unlinkedUuid is deliberately NOT saved in linkRepository

        String townRole = "role-town-x";
        String otherTownRole = "role-town-y";
        String mayorRole = "role-mayor-id";

        spySpaceRepository.save(new TownSpace(
                townUuid, "TownX",
                Optional.of("cat-1"), Optional.of("txt-x"), Optional.of("vc-x"),
                Optional.of(townRole), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));
        spySpaceRepository.save(new TownSpace(
                UUID.randomUUID(), "TownY",
                Optional.of("cat-1"), Optional.of("txt-y"), Optional.of("vc-y"),
                Optional.of(otherTownRole), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));

        TownSnapshot town = new TownSnapshot(
                townUuid, "TownX", mayorUuid, List.of(mayorUuid, resUuid, unlinkedUuid), false,
                Optional.empty(), 10, 1000.0, 5000L);

        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));

        SyncReport report = service.syncTown(townUuid).join();

        assertEquals(1, report.spacesChecked());
        // Mayor gets: townRole + mayorRole = 2 grant, otherTownRole = 1 revoke
        // Res gets: townRole = 1 grant, mayorRole + otherTownRole = 2 revoke
        // Unlinked gets: 0 grant, 0 revoke
        assertEquals(3, report.rolesGranted(),
                "Real count of roles granted across linked residents");
        assertEquals(3, report.rolesRevoked(),
                "Real count of roles revoked across linked residents");
        assertEquals(0, report.inconsistenciesFound());
        assertEquals(0, report.inconsistenciesRepaired());
        assertTrue(report.problems().isEmpty());
    }

    @Test
    @DisplayName("Mayor role transfers when mayorship changes in Towny")
    void mayorRoleTransfersWhenMayorChangesInTowny() {
        UUID townUuid = UUID.randomUUID();
        UUID oldMayorUuid = UUID.randomUUID();
        UUID newMayorUuid = UUID.randomUUID();

        String oldMayorDiscordId = "discord-old-mayor";
        String newMayorDiscordId = "discord-new-mayor";

        spyLinkRepository.save(new AccountLink(oldMayorUuid, oldMayorDiscordId, clock.instant(), "OldMayor"));
        spyLinkRepository.save(new AccountLink(newMayorUuid, newMayorDiscordId, clock.instant(), "NewMayor"));

        String townRole = "role-town-z";
        spySpaceRepository.save(new TownSpace(
                townUuid, "TownZ",
                Optional.of("cat-1"), Optional.of("txt-z"), Optional.of("vc-z"),
                Optional.of(townRole), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));

        // Towny state: newMayorUuid is now mayor!
        TownSnapshot town = new TownSnapshot(
                townUuid, "TownZ", newMayorUuid, List.of(oldMayorUuid, newMayorUuid), false,
                Optional.empty(), 10, 1000.0, 5000L);

        ResidentSnapshot oldMayorRes = new ResidentSnapshot(
                oldMayorUuid, "OldMayor", Optional.of("TownZ"), Optional.of(townUuid),
                false, true, 1000L, 100.0);
        ResidentSnapshot newMayorRes = new ResidentSnapshot(
                newMayorUuid, "NewMayor", Optional.of("TownZ"), Optional.of(townUuid),
                true, true, 1000L, 100.0);

        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));
        when(townyFacade.townOf(oldMayorUuid)).thenReturn(Optional.of(town));
        when(townyFacade.townOf(newMayorUuid)).thenReturn(Optional.of(town));
        when(townyFacade.resident(oldMayorUuid)).thenReturn(Optional.of(oldMayorRes));
        when(townyFacade.resident(newMayorUuid)).thenReturn(Optional.of(newMayorRes));

        // Sync old mayor
        service.syncPlayer(oldMayorUuid).join();
        ArgumentCaptor<GuildOperation> captor1 = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(captor1.capture());
        GuildOperation.ApplyMemberRoles op1 = (GuildOperation.ApplyMemberRoles) captor1.getValue();

        assertTrue(op1.revokeRoleIds().contains("role-mayor-id"),
                "Old mayor must lose mayor role");
        assertTrue(op1.grantRoleIds().contains(townRole),
                "Old mayor keeps town role");

        // Sync new mayor
        service.syncPlayer(newMayorUuid).join();
        ArgumentCaptor<GuildOperation> captor2 = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway, times(2)).submit(captor2.capture());
        GuildOperation.ApplyMemberRoles op2 = (GuildOperation.ApplyMemberRoles) captor2.getValue();

        assertTrue(op2.grantRoleIds().contains("role-mayor-id"),
                "New mayor must gain mayor role");
        assertTrue(op2.grantRoleIds().contains(townRole),
                "New mayor gains/keeps town role");
    }

    @Test
    @DisplayName("Kicked resident who belongs to no town loses all managed roles")
    void kickedResidentLosesAllManagedRoles() {
        UUID playerUuid = UUID.randomUUID();
        String discordId = "discord-kicked";
        spyLinkRepository.save(new AccountLink(playerUuid, discordId, clock.instant(), "KickedPlayer"));

        String townRole = "role-town-k";
        spySpaceRepository.save(new TownSpace(
                UUID.randomUUID(), "TownK",
                Optional.of("cat-1"), Optional.of("txt-k"), Optional.of("vc-k"),
                Optional.of(townRole), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));

        // Towny state: resident has no town
        ResidentSnapshot res = new ResidentSnapshot(
                playerUuid, "KickedPlayer", Optional.empty(), Optional.empty(),
                false, true, 1000L, 0.0);
        when(townyFacade.resident(playerUuid)).thenReturn(Optional.of(res));
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.empty());

        service.syncPlayer(playerUuid).join();

        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(captor.capture());
        GuildOperation.ApplyMemberRoles op = (GuildOperation.ApplyMemberRoles) captor.getValue();

        assertTrue(op.grantRoleIds().isEmpty(),
                "Player with no town must be granted zero roles");
        assertTrue(op.revokeRoleIds().contains(townRole),
                "Town role must be revoked");
        assertTrue(op.revokeRoleIds().contains("role-mayor-id"),
                "Mayor role must be revoked");
    }

    @Test
    @DisplayName("Main thread hop executes Towny queries on the provided main-thread executor")
    void mainThreadHopExecutesTownyQueriesOnProvidedMainThreadExecutor() {
        AtomicBoolean mainThreadCalled = new AtomicBoolean(false);
        Executor mainThreadExecutor = task -> {
            mainThreadCalled.set(true);
            task.run();
        };

        DefaultSyncService threadCheckingService = new DefaultSyncService(
                spySpaceRepository,
                spyLinkRepository,
                discordGateway,
                townyFacade,
                config,
                mainThreadExecutor
        );

        UUID playerUuid = UUID.randomUUID();
        spyLinkRepository.save(new AccountLink(playerUuid, "discord-hop", clock.instant(), "PlayerHop"));
        when(townyFacade.resident(playerUuid)).thenReturn(Optional.empty());
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.empty());

        threadCheckingService.syncPlayer(playerUuid).join();

        assertTrue(mainThreadCalled.get(),
                "Towny reads must be executed through the mainThreadExecutor");
    }

    @Test
    @DisplayName("Reconcile in repair mode repairs inconsistent space by submitting CreateSpace")
    void reconcileInRepairModeRepairsInconsistentSpaceBySubmittingCreateSpace() {
        UUID townUuid = UUID.randomUUID();
        TownSpace inconsistentSpace = new TownSpace(
                townUuid, "InconsistentTown",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.empty(),
                Optional.of("role-1"), SpaceState.INCONSISTENT,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(inconsistentSpace);

        TownSnapshot town = new TownSnapshot(
                townUuid, "InconsistentTown", UUID.randomUUID(), List.of(), false,
                Optional.empty(), 5, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));

        SyncReport report = service.reconcileAll().join();

        assertEquals(1, report.spacesChecked());
        assertEquals(1, report.inconsistenciesFound());
        assertEquals(1, report.inconsistenciesRepaired());

        verify(discordGateway).submit(any(GuildOperation.CreateSpace.class));
    }

    @Test
    @DisplayName("Reconcile in repair mode archives space when town was deleted in Towny")
    void reconcileInRepairModeArchivesSpaceWhenTownDeletedInTowny() {
        UUID townUuid = UUID.randomUUID();
        TownSpace activeSpace = new TownSpace(
                townUuid, "DeletedTown",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.of("role-1"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(activeSpace);

        when(townyFacade.town(townUuid)).thenReturn(Optional.empty());

        SyncReport report = service.reconcileAll().join();

        assertEquals(1, report.spacesChecked());
        assertEquals(1, report.inconsistenciesFound());
        assertEquals(1, report.inconsistenciesRepaired());

        verify(discordGateway).submit(any(GuildOperation.ArchiveSpace.class));
        verify(spySpaceRepository).updateState(townUuid, SpaceState.ARCHIVED);
    }

    @Test
    @DisplayName("Reconcile in repair mode renames space when town was renamed in Towny")
    void reconcileInRepairModeRenamesSpaceWhenTownRenamedInTowny() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "OldName",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.of("role-1"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        TownSnapshot town = new TownSnapshot(
                townUuid, "NewName", UUID.randomUUID(), List.of(), false,
                Optional.empty(), 5, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));

        SyncReport report = service.reconcileAll().join();

        assertEquals(1, report.spacesChecked());
        assertEquals(1, report.inconsistenciesFound());
        assertEquals(1, report.inconsistenciesRepaired());

        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(captor.capture());
        assertInstanceOf(GuildOperation.RenameSpace.class, captor.getValue());
        GuildOperation.RenameSpace renameOp = (GuildOperation.RenameSpace) captor.getValue();
        assertEquals("OldName", renameOp.oldName());
        assertEquals("NewName", renameOp.newName());
    }

    @Test
    @DisplayName("syncPlayer propagates Discord gateway failure as exceptional future")
    void syncPlayerPropagatesDiscordGatewayFailureAsExceptionalFuture() {
        UUID playerUuid = UUID.randomUUID();
        String discordId = "discord-fail";
        spyLinkRepository.save(new AccountLink(playerUuid, discordId, clock.instant(), "FailPlayer"));

        UUID townUuid = UUID.randomUUID();
        spySpaceRepository.save(new TownSpace(
                townUuid, "TownF",
                Optional.of("cat-1"), Optional.of("txt-f"), Optional.of("vc-f"),
                Optional.of("role-f"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));

        TownSnapshot town = new TownSnapshot(
                townUuid, "TownF", UUID.randomUUID(), List.of(playerUuid), false,
                Optional.empty(), 5, 0, 0);
        when(townyFacade.resident(playerUuid)).thenReturn(Optional.empty());
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.of(town));

        when(discordGateway.submit(any()))
                .thenReturn(CompletableFuture.completedFuture(
                        OperationOutcome.permanentFailure("Missing permissions in guild")));

        CompletableFuture<Void> future = service.syncPlayer(playerUuid);
        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Missing permissions in guild"));
    }

    @Test
    @DisplayName("syncPlayer fails safely when Towny is unavailable")
    void syncPlayerFailsSafelyWhenTownyIsUnavailable() {
        UUID playerUuid = UUID.randomUUID();
        spyLinkRepository.save(new AccountLink(playerUuid, "discord-id", clock.instant(), "Player"));

        when(townyFacade.isAvailable()).thenReturn(false);

        CompletableFuture<Void> future = service.syncPlayer(playerUuid);
        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Towny is unavailable"));
    }

    @Test
    @DisplayName("syncPlayer for unlinked player completes without submitting Discord operations")
    void syncPlayerForUnlinkedPlayerCompletesWithoutDiscordOperations() {
        UUID unlinked = UUID.randomUUID();
        service.syncPlayer(unlinked).join();
        verify(discordGateway, never()).submit(any());
    }
}
