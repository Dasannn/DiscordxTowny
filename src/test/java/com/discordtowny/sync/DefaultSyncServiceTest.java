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
import com.discordtowny.space.SpaceService;
import com.discordtowny.sync.SyncService.SyncReport;
import com.discordtowny.towny.TownyFacade;
import com.discordtowny.towny.TownyReadException;
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
        when(discordGateway.existingResourceIds(any())).thenAnswer(invocation -> {
            java.util.Collection<String> ids = invocation.getArgument(0);
            return ids != null ? new java.util.LinkedHashSet<>(ids) : java.util.Collections.emptySet();
        });

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
        when(discordGateway.roleHolders(townRole)).thenReturn(Set.of(mayorDiscordId, resDiscordId));

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

    // --- Tests for Role Holders Audit Gap (AC 8) ---

    @Test
    @DisplayName("A member holding a town role who is not a resident of that town loses that role and keeps every other role they had")
    void memberHoldingTownRoleWhoIsNotResidentLosesThatRoleAndKeepsOtherRoles() {
        UUID townAUuid = UUID.randomUUID();
        String roleTownA = "role-town-a";
        String roleTownB = "role-town-b";
        String unmanagedRole1 = "role-vip";
        String unmanagedRole2 = "role-staff";

        // Town A space
        spySpaceRepository.save(new TownSpace(
                townAUuid, "TownA",
                Optional.of("cat-1"), Optional.of("txt-a"), Optional.of("vc-a"),
                Optional.of(roleTownA), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));

        // Town B space (another managed space)
        UUID townBUuid = UUID.randomUUID();
        spySpaceRepository.save(new TownSpace(
                townBUuid, "TownB",
                Optional.of("cat-1"), Optional.of("txt-b"), Optional.of("vc-b"),
                Optional.of(roleTownB), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));

        // Alice is the resident of Town A
        UUID aliceUuid = UUID.randomUUID();
        String aliceDiscordId = "discord-alice";
        spyLinkRepository.save(new AccountLink(aliceUuid, aliceDiscordId, clock.instant(), "Alice"));

        TownSnapshot townA = new TownSnapshot(
                townAUuid, "TownA", aliceUuid, List.of(aliceUuid), false,
                Optional.empty(), 1, 100.0, 1000L);
        when(townyFacade.town(townAUuid)).thenReturn(Optional.of(townA));

        // Member Bob has discord-bob. Bob was manually given roleTownA in Discord.
        // Bob also has roleTownB, role-vip, and role-staff in Discord.
        // Bob is NOT a resident of Town A.
        String bobDiscordId = "discord-bob";

        // Discord gateway reports both Alice and Bob hold roleTownA
        when(discordGateway.roleHolders(roleTownA)).thenReturn(Set.of(aliceDiscordId, bobDiscordId));

        // Reconcile Town A
        SyncReport report = service.syncTown(townAUuid).join();

        // Capture operations submitted to Discord
        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway, atLeastOnce()).submit(captor.capture());

        // Find the operation submitted for Bob
        GuildOperation.ApplyMemberRoles bobOp = captor.getAllValues().stream()
                .filter(op -> op instanceof GuildOperation.ApplyMemberRoles a && a.discordId().equals(bobDiscordId))
                .map(op -> (GuildOperation.ApplyMemberRoles) op)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected ApplyMemberRoles operation for intruder Bob"));

        // Assert: ONLY roleTownA is revoked
        assertEquals(List.of(roleTownA), bobOp.revokeRoleIds(),
                "Bob must lose exactly roleTownA");
        assertTrue(bobOp.grantRoleIds().isEmpty(),
                "Bob must not be granted any roles");

        // Assert: Bob keeps every other role he had (managed or not)
        assertFalse(bobOp.revokeRoleIds().contains(roleTownB),
                "Bob's other managed role roleTownB must not be touched");
        assertFalse(bobOp.revokeRoleIds().contains(unmanagedRole1),
                "Bob's unmanaged role role-vip must not be touched");
        assertFalse(bobOp.revokeRoleIds().contains(unmanagedRole2),
                "Bob's unmanaged role role-staff must not be touched");

        // Assert: Report counters
        assertTrue(report.rolesRevoked() >= 1,
                "Must count the revoked role");
        assertTrue(report.inconsistenciesFound() >= 1,
                "Must report inconsistency found");
        assertTrue(report.inconsistenciesRepaired() >= 1,
                "Must report inconsistency repaired in repair mode");
    }

    @Test
    @DisplayName("A member who is a resident keeps it")
    void memberWhoIsResidentKeepsIt() {
        UUID townUuid = UUID.randomUUID();
        String townRole = "role-town-alpha";

        spySpaceRepository.save(new TownSpace(
                townUuid, "TownAlpha",
                Optional.of("cat-1"), Optional.of("txt-a"), Optional.of("vc-a"),
                Optional.of(townRole), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));

        // Alice is the resident of TownAlpha and has linked account
        UUID aliceUuid = UUID.randomUUID();
        String aliceDiscordId = "discord-alice";
        spyLinkRepository.save(new AccountLink(aliceUuid, aliceDiscordId, clock.instant(), "Alice"));

        TownSnapshot town = new TownSnapshot(
                townUuid, "TownAlpha", aliceUuid, List.of(aliceUuid), false,
                Optional.empty(), 1, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));

        // Alice legitimately holds townRole on Discord
        when(discordGateway.roleHolders(townRole)).thenReturn(Set.of(aliceDiscordId));

        // Reconcile
        SyncReport report = service.syncTown(townUuid).join();

        // Alice must keep her role: no operation should revoke townRole from Alice
        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway, atLeast(0)).submit(captor.capture());

        for (GuildOperation op : captor.getAllValues()) {
            if (op instanceof GuildOperation.ApplyMemberRoles a && a.discordId().equals(aliceDiscordId)) {
                assertFalse(a.revokeRoleIds().contains(townRole),
                        "Resident Alice must keep townRole and never have it in revoke list");
            }
        }

        // Alice was not revoked from unjustified holders check
        assertEquals(0, report.inconsistenciesFound(),
                "Legitimate resident holding the role is not an inconsistency");
        assertTrue(report.problems().isEmpty(),
                "No problems should be reported for legitimate resident");
    }

    @Test
    @DisplayName("Report mode finds the same case, reports it, and submits zero operations")
    void reportModeFindsTheSameCaseReportsItAndSubmitsZeroOperations() {
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

        UUID townUuid = UUID.randomUUID();
        String townRole = "role-town-report";

        spySpaceRepository.save(new TownSpace(
                townUuid, "TownReport",
                Optional.of("cat-1"), Optional.of("txt-r"), Optional.of("vc-r"),
                Optional.of(townRole), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));

        // Alice is resident
        UUID aliceUuid = UUID.randomUUID();
        String aliceDiscordId = "discord-alice";
        spyLinkRepository.save(new AccountLink(aliceUuid, aliceDiscordId, clock.instant(), "Alice"));

        TownSnapshot town = new TownSnapshot(
                townUuid, "TownReport", aliceUuid, List.of(aliceUuid), false,
                Optional.empty(), 1, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));

        // Intruder Eve holds townRole without being a resident
        String intruderDiscordId = "discord-eve";
        when(discordGateway.roleHolders(townRole)).thenReturn(Set.of(aliceDiscordId, intruderDiscordId));

        // Reconcile in REPORT mode
        SyncReport report = reportService.reconcileAll().join();

        // 1. Assert ZERO Discord operations submitted
        verify(discordGateway, never()).submit(any());

        // 2. Assert report counts what it would have revoked
        assertEquals(1, report.rolesRevoked(),
                "Report mode must count what it would have revoked");
        assertTrue(report.inconsistenciesFound() >= 1,
                "Report mode must record the inconsistency");
        assertEquals(0, report.inconsistenciesRepaired(),
                "Report mode must not repair anything");

        // 3. Assert problems list mentions the intruder and town
        assertFalse(report.problems().isEmpty(),
                "Report mode must list the problem");
        assertTrue(report.problems().stream().anyMatch(p -> p.contains(intruderDiscordId) && p.contains("TownReport")),
                "Problems list must name the intruder and town");
    }

    @Test
    @DisplayName("An empty or failed holder lookup revokes nothing and is reported as a problem")
    void emptyOrFailedHolderLookupRevokesNothingAndIsReportedAsAProblem() {
        UUID townUuid = UUID.randomUUID();
        String townRole = "role-town-check";

        spySpaceRepository.save(new TownSpace(
                townUuid, "TownCheck",
                Optional.of("cat-1"), Optional.of("txt-c"), Optional.of("vc-c"),
                Optional.of(townRole), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));

        UUID residentUuid = UUID.randomUUID();
        TownSnapshot town = new TownSnapshot(
                townUuid, "TownCheck", residentUuid, List.of(residentUuid), false,
                Optional.empty(), 1, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));

        // Sub-case A: Empty holder lookup (cache cold / intent off)
        when(discordGateway.roleHolders(townRole)).thenReturn(Set.of());

        SyncReport reportEmpty = service.syncTown(townUuid).join();

        // Must revoke nothing
        assertEquals(0, reportEmpty.rolesRevoked(),
                "Empty holder lookup must revoke nothing");
        // Must be reported as a problem
        assertTrue(reportEmpty.inconsistenciesFound() >= 1,
                "Empty holder lookup must be recorded as an inconsistency");
        assertTrue(reportEmpty.problems().stream().anyMatch(p ->
                p.toLowerCase().contains("empty") || p.toLowerCase().contains("cache")),
                "Problem description must report the empty lookup / cold cache");
        // Zero mutations submitted for revoking
        verify(discordGateway, never()).submit(any());

        // Sub-case B: Failed holder lookup (gateway threw exception)
        reset(discordGateway);
        // reset() wipes what setUp stubbed, so the baseline has to come back:
        // without it the stored resources look deleted and the service tries to
        // repair them, which is not what this test is about.
        when(discordGateway.isAvailable()).thenReturn(true);
        when(discordGateway.mayorRoleId()).thenReturn(Optional.of("role-mayor-id"));
        when(discordGateway.submit(any()))
                .thenReturn(CompletableFuture.completedFuture(OperationOutcome.success()));
        when(discordGateway.existingResourceIds(any())).thenAnswer(invocation -> {
            java.util.Collection<String> ids = invocation.getArgument(0);
            return ids != null ? new java.util.LinkedHashSet<>(ids) : java.util.Collections.emptySet();
        });
        when(discordGateway.roleHolders(townRole))
                .thenThrow(new IllegalStateException("Discord gateway is disconnected"));

        SyncReport reportFailed = service.syncTown(townUuid).join();

        // Must revoke nothing
        assertEquals(0, reportFailed.rolesRevoked(),
                "Failed holder lookup must revoke nothing");
        // Must be reported as a problem
        assertTrue(reportFailed.inconsistenciesFound() >= 1,
                "Failed holder lookup must be recorded as an inconsistency");
        assertTrue(reportFailed.problems().stream().anyMatch(p ->
                p.toLowerCase().contains("failed") || p.toLowerCase().contains("disconnected")),
                "Problem description must report the failed lookup");
        // Zero mutations submitted for revoking
        verify(discordGateway, never()).submit(any());
    }

    // --- Regression Tests for Finding 1 (AC 6): Deleted Discord resources with stored IDs ---

    @Test
    @DisplayName("Deleted Discord channel with persisted ID is detected and repaired via CreateSpace in repair mode")
    void deletedDiscordChannelWithPersistedIdIsDetectedAndRepairedInRepairMode() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "TownOne",
                Optional.of("cat-1"), Optional.of("txt-stored"), Optional.of("vc-stored"),
                Optional.of("role-stored"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        TownSnapshot town = new TownSnapshot(
                townUuid, "TownOne", UUID.randomUUID(), List.of(), false,
                Optional.empty(), 5, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));

        // text channel is missing in Discord, but persisted ID remains in database
        when(discordGateway.existingResourceIds(any())).thenReturn(Set.of("vc-stored", "role-stored"));

        SyncReport report = service.reconcileAll().join();

        assertEquals(1, report.spacesChecked());
        assertEquals(1, report.inconsistenciesFound());
        assertEquals(1, report.inconsistenciesRepaired());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("missing required channels")),
                "Problem must report missing required channels");

        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(captor.capture());
        assertInstanceOf(GuildOperation.CreateSpace.class, captor.getValue());
    }

    @Test
    @DisplayName("Deleted Discord channel with persisted ID in report mode records problem and submits zero operations")
    void deletedDiscordChannelWithPersistedIdInReportModeReportsDiscrepancyWithoutModifications() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "TownOne",
                Optional.of("cat-1"), Optional.of("txt-stored"), Optional.of("vc-stored"),
                Optional.of("role-stored"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        TownSnapshot town = new TownSnapshot(
                townUuid, "TownOne", UUID.randomUUID(), List.of(), false,
                Optional.empty(), 5, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));

        when(discordGateway.existingResourceIds(any())).thenReturn(Set.of("vc-stored", "role-stored"));

        PluginConfig reportConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(),
                new PluginConfig.Sync(Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPORT, 2, Duration.ofSeconds(5)),
                config.linking(), config.logging(), config.updates(), config.commands()
        );

        DefaultSyncService reportService = new DefaultSyncService(
                spySpaceRepository, spyLinkRepository, discordGateway, townyFacade, reportConfig,
                clock, Runnable::run, null, null
        );

        SyncReport report = reportService.reconcileAll().join();

        assertEquals(1, report.spacesChecked());
        assertEquals(1, report.inconsistenciesFound());
        assertEquals(0, report.inconsistenciesRepaired());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("missing required channels")));

        verify(discordGateway, never()).submit(any());
        verify(spySpaceRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deleted Discord role with persisted ID is detected and repaired via CreateSpace in repair mode")
    void deletedDiscordRoleWithPersistedIdIsDetectedAndRepairedInRepairMode() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "TownOne",
                Optional.of("cat-1"), Optional.of("txt-stored"), Optional.of("vc-stored"),
                Optional.of("role-stored"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        TownSnapshot town = new TownSnapshot(
                townUuid, "TownOne", UUID.randomUUID(), List.of(), false,
                Optional.empty(), 5, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));

        // role is missing in Discord, but persisted ID remains in database
        when(discordGateway.existingResourceIds(any())).thenReturn(Set.of("txt-stored", "vc-stored"));

        SyncReport report = service.reconcileAll().join();

        assertEquals(1, report.spacesChecked());
        assertEquals(1, report.inconsistenciesFound());
        assertEquals(1, report.inconsistenciesRepaired());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("deleted/missing role")),
                "Problem must report deleted/missing role");

        verify(discordGateway).submit(any(GuildOperation.CreateSpace.class));
    }

    @Test
    @DisplayName("Discord outage during resource existence check fails loudly and does not treat outage as deletion")
    void discordOutageDuringResourceExistenceCheckDoesNotTreatOutageAsDeletion() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "TownOne",
                Optional.of("cat-1"), Optional.of("txt-stored"), Optional.of("vc-stored"),
                Optional.of("role-stored"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        TownSnapshot town = new TownSnapshot(
                townUuid, "TownOne", UUID.randomUUID(), List.of(), false,
                Optional.empty(), 5, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));

        when(discordGateway.existingResourceIds(any()))
                .thenThrow(new IllegalStateException("Discord gateway unavailable"));

        SyncReport report = service.reconcileAll().join();

        assertEquals(1, report.spacesChecked());
        assertEquals(1, report.inconsistenciesFound());
        assertEquals(0, report.inconsistenciesRepaired());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("Failed to verify Discord resources")),
                "Problem must report failure to verify resources rather than claiming resource deletion");

        verify(discordGateway, never()).submit(any(GuildOperation.CreateSpace.class));
    }

    // --- Regression Tests for Finding 2: Unified single lifecycle decision ---

    @Test
    @DisplayName("Ruined town with ACTIVE space and missing role queues only ArchiveSpace and never CreateSpace")
    void ruinedTownWithActiveSpaceAndMissingRoleQueuesOnlyArchiveSpaceAndNeverCreateSpace() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "RuinedTown",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.empty(), SpaceState.ACTIVE, // missing role!
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        TownSnapshot ruinedTown = new TownSnapshot(
                townUuid, "RuinedTown", UUID.randomUUID(), List.of(), true, // ruined!
                Optional.empty(), 5, 0, 0);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(ruinedTown));

        SyncReport report = service.reconcileAll().join();

        assertEquals(1, report.spacesChecked());
        assertEquals(1, report.inconsistenciesFound());
        assertEquals(1, report.inconsistenciesRepaired());

        verify(discordGateway).submit(any(GuildOperation.ArchiveSpace.class));
        verify(discordGateway, never()).submit(any(GuildOperation.CreateSpace.class));
        verify(discordGateway, never()).submit(any(GuildOperation.ApplyMemberRoles.class));
        verify(spySpaceRepository).updateState(townUuid, SpaceState.ARCHIVED);
    }

    @Test
    @DisplayName("Ruined town with INCONSISTENT space queues ArchiveSpace and never CreateSpace")
    void ruinedTownWithInconsistentSpaceQueuesArchiveSpaceAndNeverCreateSpace() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "RuinedInconsistent",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.empty(),
                Optional.of("role-1"), SpaceState.INCONSISTENT,
                clock.instant(), Optional.empty(), Optional.empty()); // archivedAt is empty!
        spaceRepository.save(space);

        TownSnapshot ruinedTown = new TownSnapshot(
                townUuid, "RuinedInconsistent", UUID.randomUUID(), List.of(), true,
                Optional.empty(), 5, 0, 0);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(ruinedTown));

        SyncReport report = service.reconcileAll().join();

        assertEquals(1, report.spacesChecked());
        assertEquals(1, report.inconsistenciesFound());
        assertEquals(1, report.inconsistenciesRepaired());

        verify(discordGateway).submit(any(GuildOperation.ArchiveSpace.class));
        verify(discordGateway, never()).submit(any(GuildOperation.CreateSpace.class));
        verify(spySpaceRepository).updateState(townUuid, SpaceState.ARCHIVED);
    }

    @Test
    @DisplayName("Failed archive leaving space inconsistent is retried on later passes and archived")
    void failedArchiveLeavingSpaceInconsistentIsRetriedOnLaterPasses() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "FailedArchiveTown",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.of("role-1"), SpaceState.INCONSISTENT,
                clock.instant(), Optional.of(clock.instant()), Optional.empty());
        spaceRepository.save(space);

        when(townyFacade.town(townUuid)).thenReturn(Optional.empty()); // deleted town

        SyncReport report = service.reconcileAll().join();

        assertEquals(1, report.spacesChecked());
        assertEquals(1, report.inconsistenciesFound());
        assertEquals(1, report.inconsistenciesRepaired());

        verify(discordGateway).submit(any(GuildOperation.ArchiveSpace.class));
        verify(spySpaceRepository).updateState(townUuid, SpaceState.ARCHIVED);
    }

    @Test
    @DisplayName("Archive records intent before submitting Discord operation")
    void archiveRecordsIntentBeforeSubmittingDiscordOperation() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "IntentTown",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.of("role-1"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        when(townyFacade.town(townUuid)).thenReturn(Optional.empty());

        InOrder inOrder = inOrder(spySpaceRepository, discordGateway);

        service.reconcileAll().join();

        inOrder.verify(spySpaceRepository).save(argThat(s -> s.archivedAt().isPresent()));
        inOrder.verify(discordGateway).submit(any(GuildOperation.ArchiveSpace.class));
        inOrder.verify(spySpaceRepository).updateState(townUuid, SpaceState.ARCHIVED);
    }

    // --- Regression Tests for Finding 3: Act on absence, never on a failed read ---

    @Test
    @DisplayName("TownyReadException in syncTown reports problem and leaves access untouched without archiving")
    void townyReadExceptionInSyncTownReportsProblemAndLeavesAccessUntouched() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "TownLiving",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.of("role-1"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        when(townyFacade.town(townUuid))
                .thenThrow(new TownyReadException("Towny read timed out", new RuntimeException()));

        SyncReport report = service.syncTown(townUuid).join();

        assertEquals(1, report.inconsistenciesFound());
        assertEquals(0, report.inconsistenciesRepaired());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("Towny read timed out")),
                "Problem must report the Towny read failure");

        verify(discordGateway, never()).submit(any());
        verify(spySpaceRepository, never()).updateState(any(), any());

        TownSpace current = spaceRepository.findByTownUuid(townUuid).orElseThrow();
        assertEquals(SpaceState.ACTIVE, current.state(), "Space must remain ACTIVE after failed Towny read");
    }

    @Test
    @DisplayName("TownyReadException in reconcileAll reports problem and protects living town from archive")
    void townyReadExceptionInReconcileAllReportsProblemAndProtectsLivingTown() {
        UUID town1Uuid = UUID.randomUUID();
        TownSpace space1 = new TownSpace(
                town1Uuid, "TownFailingRead",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.of("role-1"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space1);

        UUID town2Uuid = UUID.randomUUID();
        TownSpace space2 = new TownSpace(
                town2Uuid, "TownHealthy",
                Optional.of("cat-2"), Optional.of("txt-2"), Optional.of("vc-2"),
                Optional.of("role-2"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space2);

        when(townyFacade.town(town1Uuid))
                .thenThrow(new TownyReadException("Read hiccup for town 1", null));
        when(townyFacade.town(town2Uuid))
                .thenReturn(Optional.of(new TownSnapshot(town2Uuid, "TownHealthy", UUID.randomUUID(), List.of(), false, Optional.empty(), 5, 0, 0)));

        SyncReport report = service.reconcileAll().join();

        assertEquals(2, report.spacesChecked());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("Towny read failed for space")),
                "Problem must report the failed read for space 1");

        verify(discordGateway, never()).submit(any(GuildOperation.ArchiveSpace.class));
        assertEquals(SpaceState.ACTIVE, spaceRepository.findByTownUuid(town1Uuid).orElseThrow().state(),
                "Space 1 must remain ACTIVE after Towny hiccup");
    }

    @Test
    @DisplayName("TownyReadException in syncPlayer completes exceptionally and submits zero Discord operations")
    void townyReadExceptionInSyncPlayerCompletesExceptionallyAndSubmitsZeroDiscordOperations() {
        UUID playerUuid = UUID.randomUUID();
        spyLinkRepository.save(new AccountLink(playerUuid, "discord-id", clock.instant(), "Player"));

        when(townyFacade.resident(playerUuid))
                .thenThrow(new TownyReadException("Failed to read resident snapshot", null));

        CompletableFuture<Void> future = service.syncPlayer(playerUuid);
        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(TownyReadException.class, ex.getCause());

        verify(discordGateway, never()).submit(any());
    }

    // --- Regression Tests for Finding 4: Revived town restoration ---

    @Test
    @DisplayName("Revived town restores ARCHIVED space to ACTIVE and restores residents access")
    void revivedTownRestoresArchivedSpaceToActiveAndRestoresResidentsAccess() {
        UUID townUuid = UUID.randomUUID();
        UUID mayorUuid = UUID.randomUUID();
        UUID residentUuid = UUID.randomUUID();

        TownSpace archivedSpace = new TownSpace(
                townUuid, "RevivedTown",
                Optional.of("cat-archive"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.of("role-old"), SpaceState.ARCHIVED,
                clock.instant(), Optional.of(clock.instant().minusSeconds(3600)), Optional.empty());
        spaceRepository.save(archivedSpace);

        spyLinkRepository.save(new AccountLink(mayorUuid, "mayor-discord-id", clock.instant(), "Mayor"));
        spyLinkRepository.save(new AccountLink(residentUuid, "resident-discord-id", clock.instant(), "Resident"));

        TownSnapshot aliveTown = new TownSnapshot(
                townUuid, "RevivedTown", mayorUuid, List.of(mayorUuid, residentUuid), false,
                Optional.empty(), 2, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(aliveTown));

        SyncReport report = service.reconcileAll().join();

        assertEquals(1, report.spacesChecked());
        assertEquals(1, report.inconsistenciesFound());
        assertEquals(1, report.inconsistenciesRepaired());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("is alive but space is ARCHIVED")),
                "Problem must report that alive town had an ARCHIVED space");

        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(captor.capture());
        assertInstanceOf(GuildOperation.RestoreSpace.class, captor.getValue());

        GuildOperation.RestoreSpace restoreOp = (GuildOperation.RestoreSpace) captor.getValue();
        assertEquals(townUuid, restoreOp.request().townUuid());
        assertEquals("mayor-discord-id", restoreOp.request().mayorDiscordId());
        assertTrue(restoreOp.request().linkedResidentDiscordIds().contains("resident-discord-id"),
                "Restoration request must include linked resident IDs to restore their access");

        TownSpace restored = spaceRepository.findByTownUuid(townUuid).orElseThrow();
        assertEquals(SpaceState.ACTIVE, restored.state(), "Space must transition to ACTIVE");
        assertTrue(restored.archivedAt().isEmpty(), "archivedAt must be cleared on restoration");
    }

    @Test
    @DisplayName("Revived town with INCONSISTENT space carrying archivedAt resumes restoration")
    void revivedTownWithInconsistentSpaceCarryingArchivedAtResumesRestoration() {
        UUID townUuid = UUID.randomUUID();
        TownSpace inconsistentSpace = new TownSpace(
                townUuid, "RevivedInconsistent",
                Optional.of("cat-archive"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.of("role-old"), SpaceState.INCONSISTENT,
                clock.instant(), Optional.of(clock.instant().minusSeconds(1000)), Optional.empty());
        spaceRepository.save(inconsistentSpace);

        TownSnapshot aliveTown = new TownSnapshot(
                townUuid, "RevivedInconsistent", UUID.randomUUID(), List.of(), false,
                Optional.empty(), 5, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(aliveTown));

        SyncReport report = service.reconcileAll().join();

        assertEquals(1, report.spacesChecked());
        assertEquals(1, report.inconsistenciesFound());
        assertEquals(1, report.inconsistenciesRepaired());

        verify(discordGateway).submit(any(GuildOperation.RestoreSpace.class));
        verify(discordGateway, never()).submit(any(GuildOperation.ArchiveSpace.class));
    }

    @Test
    @DisplayName("When SpaceService is provided, reconcile consumes SpaceService.archive for ruined town")
    void whenSpaceServiceProvidedReconcileConsumesSpaceServiceArchiveForRuinedTown() {
        SpaceService spaceService = mock(SpaceService.class);
        when(spaceService.archive(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        DefaultSyncService syncService = new DefaultSyncService(
                spaceService, spySpaceRepository, spyLinkRepository, discordGateway,
                townyFacade, config, clock, Runnable::run, null, null
        );

        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "RuinedWithService",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.of("role-1"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        TownSnapshot ruinedTown = new TownSnapshot(
                townUuid, "RuinedWithService", UUID.randomUUID(), List.of(), true,
                Optional.empty(), 5, 0, 0);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(ruinedTown));

        SyncReport report = syncService.reconcileAll().join();

        assertEquals(1, report.inconsistenciesFound());
        assertEquals(1, report.inconsistenciesRepaired());
        verify(spaceService).archive(eq(townUuid), contains("ruined"));
    }

    @Test
    @DisplayName("When SpaceService is provided, reconcile consumes SpaceService.restore for revived town")
    void whenSpaceServiceProvidedReconcileConsumesSpaceServiceRestoreForRevivedTown() {
        SpaceService spaceService = mock(SpaceService.class);
        when(spaceService.restore(any())).thenReturn(CompletableFuture.completedFuture(null));

        DefaultSyncService syncService = new DefaultSyncService(
                spaceService, spySpaceRepository, spyLinkRepository, discordGateway,
                townyFacade, config, clock, Runnable::run, null, null
        );

        UUID townUuid = UUID.randomUUID();
        TownSpace archivedSpace = new TownSpace(
                townUuid, "RevivedWithService",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.of("role-1"), SpaceState.ARCHIVED,
                clock.instant(), Optional.of(clock.instant().minusSeconds(3600)), Optional.empty());
        spaceRepository.save(archivedSpace);

        TownSnapshot aliveTown = new TownSnapshot(
                townUuid, "RevivedWithService", UUID.randomUUID(), List.of(), false,
                Optional.empty(), 5, 0, 0);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(aliveTown));

        SyncReport report = syncService.reconcileAll().join();

        assertEquals(1, report.inconsistenciesFound());
        assertEquals(1, report.inconsistenciesRepaired());
        verify(spaceService).restore(argThat(req -> req.townUuid().equals(townUuid)));
    }
}

