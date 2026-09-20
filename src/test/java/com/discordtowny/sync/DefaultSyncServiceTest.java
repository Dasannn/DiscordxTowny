package com.discordtowny.sync;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.config.YamlMessages;
import org.bukkit.configuration.file.YamlConfiguration;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
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
    private Messages enMessages;
    private Messages esMessages;

    @BeforeEach
    void setUp() throws IOException {
        enMessages = loadMessages("/messages_en.yml");
        esMessages = loadMessages("/messages_es.yml");
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

    private static Messages loadMessages(String resourceName) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        try (InputStream in = DefaultSyncServiceTest.class.getResourceAsStream(resourceName)) {
            if (in != null) {
                try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    YamlConfiguration yaml = new YamlConfiguration();
                    yaml.load(reader);
                    for (String key : yaml.getKeys(true)) {
                        if (yaml.isString(key)) {
                            map.put(key, yaml.getString(key));
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load test messages from " + resourceName, e);
        }
        return new YamlMessages(map, s -> {});
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

        // Player currently holds on Discord: roleTownB (unjustified) and unmanaged roles ("role-vip", "role-mod").
        // Player does NOT hold roleTownA or the mayor role.
        String unmanagedRole1 = "role-vip";
        String unmanagedRole2 = "role-mod";
        when(discordGateway.roleHolders(roleTownB)).thenReturn(Set.of(discordId));
        when(discordGateway.roleHolders(roleTownA)).thenReturn(Set.of());
        when(discordGateway.roleHolders("role-mayor-id")).thenReturn(Set.of());
        when(discordGateway.roleHolders(unmanagedRole1)).thenReturn(Set.of(discordId));
        when(discordGateway.roleHolders(unmanagedRole2)).thenReturn(Set.of(discordId));

        // Execute sync
        service.syncPlayer(playerUuid).join();

        // Capture GuildOperation submitted to Discord
        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(captor.capture());

        assertInstanceOf(GuildOperation.ApplyMemberRoles.class, captor.getValue());
        GuildOperation.ApplyMemberRoles op = (GuildOperation.ApplyMemberRoles) captor.getValue();

        assertEquals(discordId, op.discordId());

        // The unjustified town role (roleTownB) MUST be in the revoke list
        assertEquals(List.of(roleTownB), op.revokeRoleIds(),
                "Unjustified town role B must be revoked, and no unheld roles should be phantom-revoked");

        // The justified town role (roleTownA) MUST be in the grant list
        assertEquals(List.of(roleTownA), op.grantRoleIds(),
                "Justified town role A must be granted");

        // CRUCIAL: Unmanaged roles MUST NEVER be in the revoke list or grant list
        assertFalse(op.revokeRoleIds().contains(unmanagedRole1),
                "Unmanaged role 'role-vip' must not be touched");
        assertFalse(op.revokeRoleIds().contains(unmanagedRole2),
                "Unmanaged role 'role-mod' must not be touched");
        assertFalse(op.grantRoleIds().contains(unmanagedRole1));
        assertFalse(op.grantRoleIds().contains(unmanagedRole2));
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

        // Newly linked resident Bob holds NO managed roles on Discord yet, but holds an unmanaged role
        String unmanagedRole = "role-guest";
        when(discordGateway.roleHolders(roleTown1)).thenReturn(Set.of());
        when(discordGateway.roleHolders(roleTown2)).thenReturn(Set.of());
        when(discordGateway.roleHolders(roleTown3)).thenReturn(Set.of());
        when(discordGateway.roleHolders("role-mayor-id")).thenReturn(Set.of());
        when(discordGateway.roleHolders(unmanagedRole)).thenReturn(Set.of(discordId));

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

        // Since Bob held no other managed roles, there is nothing to revoke
        assertTrue(op.revokeRoleIds().isEmpty(),
                "Newly linked resident holding no other managed roles has nothing to revoke");

        // Unmanaged role must not be touched
        assertFalse(op.grantRoleIds().contains(unmanagedRole));
        assertFalse(op.revokeRoleIds().contains(unmanagedRole));
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
        assertFalse(report.problemDetails().isEmpty(),
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

        // Town1 has a missing role in Discord so it submits a CreateSpace repair operation
        when(discordGateway.existingResourceIds(any())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            Set<String> result = new LinkedHashSet<>(ids != null ? ids : List.of());
            result.remove("role-1"); // role-1 missing -> triggers repair
            return result;
        });

        // Hold the repair operation for BatchTown1 pending to prove Batch 2 waits
        CompletableFuture<OperationOutcome> pendingDiscordOp = new CompletableFuture<>();
        when(discordGateway.submit(any(GuildOperation.CreateSpace.class))).thenReturn(pendingDiscordOp);

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

        CompletableFuture<SyncReport> reconcileFuture = batchService.reconcileAll();

        // While pendingDiscordOp in Batch 1 is not completed:
        // Batch 1 has not finished, so pause must NOT have occurred, and Batch 2 must NOT have started
        assertFalse(reconcileFuture.isDone(), "Reconcile pass must wait for Batch 1's pending operation");
        assertFalse(eventLog.contains("pause"), "Pause must not happen while Batch 1 operation is pending");
        assertFalse(eventLog.contains("process-BatchTown3"), "Batch 2 must not start while Batch 1 is pending");
        assertFalse(eventLog.contains("process-BatchTown4"), "Batch 2 must not start while Batch 1 is pending");

        // Complete the pending Discord operation for BatchTown1
        pendingDiscordOp.complete(OperationOutcome.success());

        SyncReport report = reconcileFuture.join();

        assertEquals(5, report.spacesChecked());

        // For 3 batches, exactly 2 pauses must occur
        verify(mockPause, times(2)).accept(pauseDuration);

        // Prove exact sequence of batch boundaries:
        // Batch 1 spaces -> Pause 1 -> Batch 2 spaces -> Pause 2 -> Batch 3 space
        assertEquals(7, eventLog.size(), "Exact event sequence: 2 spaces + pause + 2 spaces + pause + 1 space");

        int firstPauseIndex = eventLog.indexOf("pause");
        int lastPauseIndex = eventLog.lastIndexOf("pause");

        assertEquals(2, firstPauseIndex, "First pause must happen immediately after the 2 spaces of Batch 1");
        assertEquals(5, lastPauseIndex, "Second pause must happen immediately after the 2 spaces of Batch 2");

        // Batch 1 spaces are before first pause
        assertTrue(eventLog.subList(0, 2).containsAll(List.of("process-BatchTown1", "process-BatchTown2")),
                "Batch 1 spaces must finish before the first pause");

        // Batch 2 spaces are between first and second pause
        assertTrue(eventLog.subList(3, 5).containsAll(List.of("process-BatchTown3", "process-BatchTown4")),
                "Batch 2 spaces must finish between the first and second pause");

        // Batch 3 space is after second pause
        assertEquals("process-BatchTown5", eventLog.get(6),
                "Batch 3 space must process after the second pause");
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
        // Mayor and Res already hold townRole
        when(discordGateway.roleHolders(townRole)).thenReturn(Set.of(mayorDiscordId, resDiscordId));
        // Neither holds mayorRole in Discord yet -> Mayor is missing mayorRole (1 actual grant)
        when(discordGateway.roleHolders(mayorRole)).thenReturn(Set.of());
        // Resident holds otherTownRole in Discord -> Resident holds unjustified role (1 actual revoke)
        when(discordGateway.roleHolders(otherTownRole)).thenReturn(Set.of(resDiscordId));

        SyncReport report = service.syncTown(townUuid).join();

        assertEquals(1, report.spacesChecked());
        // Mayor gets: 1 grant (mayorRole), 0 revoke
        // Res gets: 0 grant, 1 revoke (otherTownRole)
        // Unlinked gets: 0 grant, 0 revoke
        assertEquals(1, report.rolesGranted(),
                "Real count of actual roles granted on Discord");
        assertEquals(1, report.rolesRevoked(),
                "Real count of actual roles revoked on Discord");
        assertEquals(0, report.inconsistenciesFound(),
                "In repair mode, resident role adjustments increment inconsistenciesRepaired rather than inconsistenciesFound");
        assertEquals(2, report.inconsistenciesRepaired(),
                "Real count of repairs performed");
        assertTrue(report.problemDetails().isEmpty());

        // Assert actual operations submitted to Discord
        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway, times(2)).submit(captor.capture());
        List<GuildOperation> ops = captor.getAllValues();

        GuildOperation.ApplyMemberRoles mayorOp = ops.stream()
                .filter(o -> o instanceof GuildOperation.ApplyMemberRoles)
                .map(o -> (GuildOperation.ApplyMemberRoles) o)
                .filter(o -> o.discordId().equals(mayorDiscordId))
                .findFirst().orElseThrow();
        assertEquals(List.of(mayorRole), mayorOp.grantRoleIds(),
                "Mayor must actually receive missing mayor role");
        assertTrue(mayorOp.revokeRoleIds().isEmpty(),
                "Mayor holds no unjustified roles to revoke");

        GuildOperation.ApplyMemberRoles resOp = ops.stream()
                .filter(o -> o instanceof GuildOperation.ApplyMemberRoles)
                .map(o -> (GuildOperation.ApplyMemberRoles) o)
                .filter(o -> o.discordId().equals(resDiscordId))
                .findFirst().orElseThrow();
        assertTrue(resOp.grantRoleIds().isEmpty(),
                "Resident is not missing any roles");
        assertEquals(List.of(otherTownRole), resOp.revokeRoleIds(),
                "Resident must actually lose unjustified otherTownRole");
    }

    @Test
    @DisplayName("An already-correct town produces zero grants and zero revokes on subsequent pass")
    void alreadyCorrectTownProducesZeroGrantsAndZeroRevokes() {
        UUID townUuid = UUID.randomUUID();
        UUID mayorUuid = UUID.randomUUID();
        UUID resUuid = UUID.randomUUID();

        String mayorDiscordId = "discord-mayor";
        String resDiscordId = "discord-res";

        spyLinkRepository.save(new AccountLink(mayorUuid, mayorDiscordId, clock.instant(), "MayorPlayer"));
        spyLinkRepository.save(new AccountLink(resUuid, resDiscordId, clock.instant(), "ResidentPlayer"));

        String townRole = "role-town-x";
        String mayorRole = "role-mayor-id";

        spySpaceRepository.save(new TownSpace(
                townUuid, "TownX",
                Optional.of("cat-1"), Optional.of("txt-x"), Optional.of("vc-x"),
                Optional.of(townRole), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));

        TownSnapshot town = new TownSnapshot(
                townUuid, "TownX", mayorUuid, List.of(mayorUuid, resUuid), false,
                Optional.empty(), 10, 1000.0, 5000L);

        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));
        // Both hold townRole
        when(discordGateway.roleHolders(townRole)).thenReturn(Set.of(mayorDiscordId, resDiscordId));
        // Mayor holds mayorRole
        when(discordGateway.roleHolders(mayorRole)).thenReturn(Set.of(mayorDiscordId));

        reset(discordGateway);
        when(discordGateway.isAvailable()).thenReturn(true);
        when(discordGateway.mayorRoleId()).thenReturn(Optional.of(mayorRole));
        when(discordGateway.roleHolders(townRole)).thenReturn(Set.of(mayorDiscordId, resDiscordId));
        when(discordGateway.roleHolders(mayorRole)).thenReturn(Set.of(mayorDiscordId));
        when(discordGateway.existingResourceIds(any())).thenAnswer(inv -> new LinkedHashSet<>(inv.getArgument(0)));

        SyncReport report = service.syncTown(townUuid).join();

        assertEquals(1, report.spacesChecked());
        assertEquals(0, report.rolesGranted(), "No-op pass must grant zero roles");
        assertEquals(0, report.rolesRevoked(), "No-op pass must revoke zero roles");
        assertEquals(0, report.inconsistenciesFound(), "No discrepancies in fully synchronized town");
        assertEquals(0, report.inconsistenciesRepaired());

        // Zero operations submitted to Discord
        verify(discordGateway, never()).submit(any());
    }

    @Test
    @DisplayName("Report mode detects missing role assignment and reports it without submitting mutations")
    void reportModeDetectsMissingRoleAssignmentAndReportsItWithoutMutations() {
        UUID townUuid = UUID.randomUUID();
        UUID mayorUuid = UUID.randomUUID();
        UUID resUuid = UUID.randomUUID();

        String mayorDiscordId = "discord-mayor";
        String resDiscordId = "discord-res";

        spyLinkRepository.save(new AccountLink(mayorUuid, mayorDiscordId, clock.instant(), "MayorPlayer"));
        spyLinkRepository.save(new AccountLink(resUuid, resDiscordId, clock.instant(), "ResidentPlayer"));

        String townRole = "role-town-x";
        String mayorRole = "role-mayor-id";

        spySpaceRepository.save(new TownSpace(
                townUuid, "TownX",
                Optional.of("cat-1"), Optional.of("txt-x"), Optional.of("vc-x"),
                Optional.of(townRole), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));

        TownSnapshot town = new TownSnapshot(
                townUuid, "TownX", mayorUuid, List.of(mayorUuid, resUuid), false,
                Optional.empty(), 10, 1000.0, 5000L);

        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));
        // Alice (Mayor) holds townRole and mayorRole. Bob (Resident) should hold townRole, but DOES NOT.
        when(discordGateway.roleHolders(townRole)).thenReturn(Set.of(mayorDiscordId)); // resDiscordId is missing!
        when(discordGateway.roleHolders(mayorRole)).thenReturn(Set.of(mayorDiscordId));

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

        SyncReport report = reportService.syncTown(townUuid).join();

        assertTrue(report.isReportMode());
        assertEquals(0, report.rolesGranted(), "Report mode must never report confirmed grants");
        assertEquals(0, report.rolesRevoked(), "Report mode must never report confirmed revocations");
        assertEquals(1, report.proposedGrants(), "Report mode must detect Bob's missing town role assignment");
        assertEquals(0, report.proposedRevocations());
        assertEquals(1, report.inconsistenciesFound());
        assertEquals(0, report.inconsistenciesRepaired());

        assertTrue(report.problemDetails().stream().anyMatch(p ->
                "sync.problem-resident-missing-roles".equals(p.key())
                && resDiscordId.equals(p.placeholders().get("discord"))),
                "Problems list must explicitly mention missing role for Bob as a structured problem");
        assertTrue(report.problems(enMessages).stream().anyMatch(p -> p.contains(resDiscordId) && p.contains("missing roles")),
                "English rendered problem must explicitly mention missing role for Bob");
        assertTrue(report.problems(esMessages).stream().anyMatch(p -> p.contains(resDiscordId) && p.contains("roles faltantes")),
                "Spanish rendered problem must explicitly mention missing role for Bob");

        verify(discordGateway, never()).submit(any());
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

        // Prior to mayorship transfer in Discord:
        // Old mayor holds townRole AND role-mayor-id.
        // New mayor holds townRole (as existing resident), but NOT role-mayor-id.
        String mayorRole = "role-mayor-id";
        when(discordGateway.roleHolders(townRole)).thenReturn(Set.of(oldMayorDiscordId, newMayorDiscordId));
        when(discordGateway.roleHolders(mayorRole)).thenReturn(Set.of(oldMayorDiscordId));

        // Sync old mayor
        service.syncPlayer(oldMayorUuid).join();
        ArgumentCaptor<GuildOperation> captor1 = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(captor1.capture());
        GuildOperation.ApplyMemberRoles op1 = (GuildOperation.ApplyMemberRoles) captor1.getValue();

        assertEquals(List.of(mayorRole), op1.revokeRoleIds(),
                "Old mayor must lose mayor role");
        assertTrue(op1.grantRoleIds().isEmpty(),
                "Old mayor already holds town role, so no grant operation should be submitted");

        // Sync new mayor
        service.syncPlayer(newMayorUuid).join();
        ArgumentCaptor<GuildOperation> captor2 = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway, times(2)).submit(captor2.capture());
        GuildOperation.ApplyMemberRoles op2 = (GuildOperation.ApplyMemberRoles) captor2.getValue();

        assertEquals(List.of(mayorRole), op2.grantRoleIds(),
                "New mayor must gain mayor role");
        assertTrue(op2.revokeRoleIds().isEmpty(),
                "New mayor holds no unjustified roles to revoke");
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

        // Kicked resident currently holds the managed town role and mayor role on Discord, plus an unmanaged role
        String mayorRole = "role-mayor-id";
        String unmanagedRole = "role-supporter";
        when(discordGateway.roleHolders(townRole)).thenReturn(Set.of(discordId));
        when(discordGateway.roleHolders(mayorRole)).thenReturn(Set.of(discordId));
        when(discordGateway.roleHolders(unmanagedRole)).thenReturn(Set.of(discordId));

        service.syncPlayer(playerUuid).join();

        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway).submit(captor.capture());
        GuildOperation.ApplyMemberRoles op = (GuildOperation.ApplyMemberRoles) captor.getValue();

        assertTrue(op.grantRoleIds().isEmpty(),
                "Player with no town must be granted zero roles");
        assertEquals(Set.of(townRole, mayorRole), Set.copyOf(op.revokeRoleIds()),
                "All held managed roles must be revoked");
        assertFalse(op.revokeRoleIds().contains(unmanagedRole),
                "Unmanaged role must not be touched");
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
        assertTrue(report.problemDetails().isEmpty(),
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

        // 2. Assert report counts what it would have revoked in proposedRevocations, not confirmed rolesRevoked
        assertEquals(0, report.rolesRevoked(),
                "Report mode must not count confirmed revocations");
        assertEquals(1, report.proposedRevocations(),
                "Report mode must record proposed revocations");
        assertTrue(report.inconsistenciesFound() >= 1,
                "Report mode must record the inconsistency");
        assertEquals(0, report.inconsistenciesRepaired(),
                "Report mode must not repair anything");

        // 3. Assert problems list mentions the intruder and town
        assertFalse(report.problemDetails().isEmpty(),
                "Report mode must list the problem");
        assertTrue(report.problemDetails().stream().anyMatch(p ->
                "sync.problem-member-unjustified-town-role".equals(p.key())
                && intruderDiscordId.equals(p.placeholders().get("discord"))
                && "TownReport".equals(p.placeholders().get("town"))),
                "Problem details must carry structured key and placeholders for unjustified town role");
        assertTrue(report.problems(enMessages).stream().anyMatch(p -> p.contains(intruderDiscordId) && p.contains("TownReport")),
                "English rendered problem must name the intruder and town");
        assertTrue(report.problems(esMessages).stream().anyMatch(p -> p.contains(intruderDiscordId) && p.contains("TownReport")),
                "Spanish rendered problem must name the intruder and town");
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
        assertTrue(reportEmpty.problemDetails().stream().anyMatch(p ->
                "sync.problem-role-holders-empty-town".equals(p.key())
                && "TownCheck".equals(p.placeholders().get("town"))
                && townRole.equals(p.placeholders().get("role"))),
                "Problem description must carry structured key for empty town role holders");
        assertTrue(reportEmpty.problems(enMessages).stream().anyMatch(p ->
                p.toLowerCase().contains("empty") || p.toLowerCase().contains("cache")),
                "English rendered problem must report the empty lookup / cold cache");
        assertTrue(reportEmpty.problems(esMessages).stream().anyMatch(p ->
                p.toLowerCase().contains("caché") || p.toLowerCase().contains("omiten")),
                "Spanish rendered problem must report the cold cache / skipped revocations");
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
        assertTrue(reportFailed.problemDetails().stream().anyMatch(p ->
                "sync.problem-lookup-role-holders-failed".equals(p.key())
                && "TownCheck".equals(p.placeholders().get("town"))
                && townRole.equals(p.placeholders().get("role"))
                && p.placeholders().get("error").contains("disconnected")),
                "Problem description must carry structured key and error detail for failed role lookup");
        assertTrue(reportFailed.problems(enMessages).stream().anyMatch(p ->
                p.toLowerCase().contains("failed") || p.toLowerCase().contains("disconnected")),
                "English rendered problem must report the failed lookup");
        assertTrue(reportFailed.problems(esMessages).stream().anyMatch(p ->
                p.toLowerCase().contains("error") && p.toLowerCase().contains("desconectado")),
                "Spanish rendered problem must report the failed lookup in Spanish");
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
        assertTrue(report.problemDetails().stream().anyMatch(p ->
                "sync.problem-missing-channels".equals(p.key())
                && "TownOne".equals(p.placeholders().get("town"))),
                "Problem must carry structured key for missing channels");
        assertTrue(report.problems(enMessages).stream().anyMatch(p -> p.contains("missing required channels")),
                "English problem must report missing required channels");
        assertTrue(report.problems(esMessages).stream().anyMatch(p -> p.contains("canales requeridos")),
                "Spanish problem must report missing required channels in Spanish");

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
        assertTrue(report.problemDetails().stream().anyMatch(p ->
                "sync.problem-missing-channels".equals(p.key())
                && "TownOne".equals(p.placeholders().get("town"))),
                "Problem must carry structured key for missing channels");
        assertTrue(report.problems(enMessages).stream().anyMatch(p -> p.contains("missing required channels")));
        assertTrue(report.problems(esMessages).stream().anyMatch(p -> p.contains("canales requeridos")));

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
        assertTrue(report.problemDetails().stream().anyMatch(p ->
                "sync.problem-missing-role".equals(p.key())
                && "TownOne".equals(p.placeholders().get("town"))),
                "Problem must carry structured key for deleted/missing role");
        assertTrue(report.problems(enMessages).stream().anyMatch(p -> p.contains("deleted/missing role")),
                "English problem must report deleted/missing role");
        assertTrue(report.problems(esMessages).stream().anyMatch(p -> p.contains("eliminado/faltante")),
                "Spanish problem must report deleted/missing role in Spanish");

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
        assertTrue(report.problemDetails().stream().anyMatch(p ->
                "sync.problem-verify-resources-failed".equals(p.key())
                && "TownOne".equals(p.placeholders().get("town"))
                && p.placeholders().get("error").contains("discord-unavailable")),
                "Problem must carry structured key and error detail for failed resource verification");
        assertTrue(report.problems(enMessages).stream().anyMatch(p ->
                p.contains("Failed to verify Discord resources") && p.contains("Discord is unavailable")),
                "English problem must report failure to verify resources with localized cause");
        assertTrue(report.problems(esMessages).stream().anyMatch(p ->
                p.contains("Error al verificar recursos de Discord") && p.contains("Discord no está disponible")),
                "Spanish problem must report failure to verify resources in Spanish without English leak");
        assertFalse(report.problems(esMessages).stream().anyMatch(p ->
                p.contains("Discord is unavailable") || p.contains("Discord gateway unavailable")),
                "Spanish problem must not leak English exception text");

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
        assertTrue(report.problemDetails().stream().anyMatch(p ->
                "sync.problem-towny-read-failed-town".equals(p.key())
                && townUuid.toString().equals(p.placeholders().get("town"))
                && p.placeholders().get("error").contains("timeout")),
                "Problem must carry structured key and error detail for Towny read failure");
        assertTrue(report.problems(enMessages).stream().anyMatch(p -> p.contains("timed out")),
                "English problem must report the Towny read failure");
        assertTrue(report.problems(esMessages).stream().anyMatch(p -> p.contains("Error al leer la town") && p.contains("tiempo de espera agotado")),
                "Spanish problem must report the Towny read failure in Spanish");

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
        assertTrue(report.problemDetails().stream().anyMatch(p ->
                "sync.problem-towny-read-failed-space".equals(p.key())
                && "TownFailingRead".equals(p.placeholders().get("town"))
                && town1Uuid.toString().equals(p.placeholders().get("uuid"))),
                "Problem must carry structured key for failed Towny read for space");
        assertTrue(report.problems(enMessages).stream().anyMatch(p -> p.contains("Towny read failed for space")),
                "English problem must report the failed read for space 1");
        assertTrue(report.problems(esMessages).stream().anyMatch(p -> p.contains("Error de lectura de Towny para el espacio")),
                "Spanish problem must report the failed read for space 1 in Spanish");

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
        assertTrue(report.problemDetails().stream().anyMatch(p ->
                "sync.problem-town-alive-space-archived".equals(p.key())
                && "RevivedTown".equals(p.placeholders().get("town"))),
                "Problem must carry structured key for alive town with ARCHIVED space");
        assertTrue(report.problems(enMessages).stream().anyMatch(p -> p.contains("is alive but space is ARCHIVED")),
                "English problem must report that alive town had an ARCHIVED space");
        assertTrue(report.problems(esMessages).stream().anyMatch(p -> p.contains("está viva pero su espacio está archivado")),
                "Spanish problem must report restoration in Spanish");

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

    // --- Tests for Finding 7: Isolated Batch Failures ---

    @Test
    @DisplayName("One failing space in an earlier batch does not abort later batches and retains problems in the report")
    void spaceFailureInEarlierBatchDoesNotAbortLaterBatchesAndRetainsProblemsInReport() {
        // 4 spaces, batch size is 2 => Batch 1: Town 1, Town 2; Batch 2: Town 3, Town 4
        UUID town1Uuid = UUID.randomUUID();
        UUID town2Uuid = UUID.randomUUID();
        UUID town3Uuid = UUID.randomUUID();
        UUID town4Uuid = UUID.randomUUID();

        spySpaceRepository.save(new TownSpace(
                town1Uuid, "FailingTown1",
                Optional.of("cat-1"), Optional.of("txt-1"), Optional.of("vc-1"),
                Optional.of("role-1"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));
        spySpaceRepository.save(new TownSpace(
                town2Uuid, "HealthyTown2",
                Optional.of("cat-1"), Optional.of("txt-2"), Optional.of("vc-2"),
                Optional.of("role-2"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));
        spySpaceRepository.save(new TownSpace(
                town3Uuid, "HealthyTown3",
                Optional.of("cat-1"), Optional.of("txt-3"), Optional.of("vc-3"),
                Optional.of("role-3"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));
        spySpaceRepository.save(new TownSpace(
                town4Uuid, "HealthyTown4",
                Optional.of("cat-1"), Optional.of("txt-4"), Optional.of("vc-4"),
                Optional.of("role-4"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty()));

        // Town 1 throws an unexpected runtime exception during lookup
        when(townyFacade.town(town1Uuid)).thenThrow(new RuntimeException("Simulated database failure for Town 1"));

        // Towns 2, 3, 4 return healthy snapshots
        TownSnapshot snap2 = new TownSnapshot(town2Uuid, "HealthyTown2", UUID.randomUUID(), List.of(), false, Optional.empty(), 1, 100.0, 1000L);
        TownSnapshot snap3 = new TownSnapshot(town3Uuid, "HealthyTown3", UUID.randomUUID(), List.of(), false, Optional.empty(), 1, 100.0, 1000L);
        TownSnapshot snap4 = new TownSnapshot(town4Uuid, "HealthyTown4", UUID.randomUUID(), List.of(), false, Optional.empty(), 1, 100.0, 1000L);
        when(townyFacade.town(town2Uuid)).thenReturn(Optional.of(snap2));
        when(townyFacade.town(town3Uuid)).thenReturn(Optional.of(snap3));
        when(townyFacade.town(town4Uuid)).thenReturn(Optional.of(snap4));

        // Reconcile all
        SyncReport report = service.reconcileAll().join();

        // Assert: The report was not discarded, and all 4 spaces were checked across both batches
        assertEquals(4, report.spacesChecked(), "All 4 spaces must be checked despite failure in Batch 1");

        // Assert: Batch 2 spaces were indeed processed
        verify(townyFacade).town(town3Uuid);
        verify(townyFacade).town(town4Uuid);

        // Assert: Problems list retains the failure for Town 1
        assertTrue(report.inconsistenciesFound() >= 1, "Must record inconsistency for the failed space");
        assertTrue(report.problemDetails().stream().anyMatch(p ->
                "sync.problem-towny-read-failed-space".equals(p.key())
                && "FailingTown1".equals(p.placeholders().get("town"))
                && town1Uuid.toString().equals(p.placeholders().get("uuid"))),
                "Problems list must retain the failure details for Town 1 as structured problem");
        assertTrue(report.problems(enMessages).stream().anyMatch(p -> p.contains("FailingTown1")),
                "English problems list must retain the failure details for Town 1");
        assertTrue(report.problems(esMessages).stream().anyMatch(p -> p.contains("FailingTown1")),
                "Spanish problems list must retain the failure details for Town 1");
    }

    // --- Tests for Finding 11: Global Mayor Role Auditing ---

    @Test
    @DisplayName("Audit mayor role: unjustified holder loses mayor role in repair mode while legitimate mayors are unaffected")
    void auditMayorRoleRevokesUnjustifiedHolderInRepairMode() {
        UUID mayorAliceUuid = UUID.randomUUID();
        String aliceDiscordId = "discord-alice";
        spyLinkRepository.save(new AccountLink(mayorAliceUuid, aliceDiscordId, clock.instant(), "Alice"));

        UUID townAUuid = UUID.randomUUID();
        TownSpace spaceA = new TownSpace(
                townAUuid, "TownA",
                Optional.of("cat-1"), Optional.of("txt-a"), Optional.of("vc-a"),
                Optional.of("role-town-a"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spySpaceRepository.save(spaceA);

        TownSnapshot townA = new TownSnapshot(
                townAUuid, "TownA", mayorAliceUuid, List.of(mayorAliceUuid), false,
                Optional.empty(), 1, 100.0, 1000L);
        when(townyFacade.town(townAUuid)).thenReturn(Optional.of(townA));
        when(townyFacade.allTowns()).thenReturn(List.of(townA));

        // In Discord, both legitimate mayor Alice and intruder Eve hold the global mayor role
        String mayorRoleId = "role-mayor-id";
        String eveDiscordId = "discord-eve";
        when(discordGateway.roleHolders(mayorRoleId)).thenReturn(Set.of(aliceDiscordId, eveDiscordId));
        when(discordGateway.roleHolders("role-town-a")).thenReturn(Set.of(aliceDiscordId));

        SyncReport report = service.reconcileAll().join();

        // Capture Discord operations
        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway, atLeastOnce()).submit(captor.capture());

        // Eve must have mayor role revoked
        GuildOperation.ApplyMemberRoles eveOp = captor.getAllValues().stream()
                .filter(op -> op instanceof GuildOperation.ApplyMemberRoles a && a.discordId().equals(eveDiscordId))
                .map(op -> (GuildOperation.ApplyMemberRoles) op)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected ApplyMemberRoles operation for intruder Eve"));

        assertEquals(List.of(mayorRoleId), eveOp.revokeRoleIds(), "Intruder Eve must have mayor role revoked");
        assertTrue(eveOp.grantRoleIds().isEmpty());

        // Alice must NOT have mayor role revoked
        boolean aliceMayorRevoked = captor.getAllValues().stream()
                .filter(op -> op instanceof GuildOperation.ApplyMemberRoles a && a.discordId().equals(aliceDiscordId))
                .map(op -> (GuildOperation.ApplyMemberRoles) op)
                .anyMatch(a -> a.revokeRoleIds().contains(mayorRoleId));
        assertFalse(aliceMayorRevoked, "Legitimate mayor Alice must not have mayor role revoked");

        assertTrue(report.rolesRevoked() >= 1, "Must count revoked mayor role");
    }

    @Test
    @DisplayName("Audit mayor role: report mode reports proposed revocation without submitting mutations")
    void auditMayorRoleInReportModeDoesNotMutateAndReportsProposedRevocation() {
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

        UUID mayorAliceUuid = UUID.randomUUID();
        String aliceDiscordId = "discord-alice";
        spyLinkRepository.save(new AccountLink(mayorAliceUuid, aliceDiscordId, clock.instant(), "Alice"));

        UUID townAUuid = UUID.randomUUID();
        TownSpace spaceA = new TownSpace(
                townAUuid, "TownA",
                Optional.of("cat-1"), Optional.of("txt-a"), Optional.of("vc-a"),
                Optional.of("role-town-a"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spySpaceRepository.save(spaceA);

        TownSnapshot townA = new TownSnapshot(
                townAUuid, "TownA", mayorAliceUuid, List.of(mayorAliceUuid), false,
                Optional.empty(), 1, 100.0, 1000L);
        when(townyFacade.town(townAUuid)).thenReturn(Optional.of(townA));
        when(townyFacade.allTowns()).thenReturn(List.of(townA));

        String mayorRoleId = "role-mayor-id";
        String eveDiscordId = "discord-eve";
        when(discordGateway.roleHolders(mayorRoleId)).thenReturn(Set.of(aliceDiscordId, eveDiscordId));
        when(discordGateway.roleHolders("role-town-a")).thenReturn(Set.of(aliceDiscordId));

        SyncReport report = reportService.reconcileAll().join();

        // Zero mutations submitted
        verify(discordGateway, never()).submit(any());

        // Confirmed revocations must be 0, proposed revocations must be >= 1
        assertEquals(0, report.rolesRevoked(), "Report mode must not perform confirmed revocations");
        assertTrue(report.proposedRevocations() >= 1, "Report mode must count proposed revocations for mayor role");
        assertTrue(report.problemDetails().stream().anyMatch(p ->
                "sync.problem-member-unjustified-mayor-role".equals(p.key())
                && eveDiscordId.equals(p.placeholders().get("discord"))
                && mayorRoleId.equals(p.placeholders().get("role"))),
                "Problems list must identify Eve holding the mayor role as structured problem");
        assertTrue(report.problems(enMessages).stream().anyMatch(p -> p.contains(eveDiscordId) && p.contains(mayorRoleId)),
                "English problems list must identify Eve holding the mayor role");
        assertTrue(report.problems(esMessages).stream().anyMatch(p -> p.contains(eveDiscordId) && p.contains(mayorRoleId)),
                "Spanish problems list must identify Eve holding the mayor role");
    }

    @Test
    @DisplayName("Audit mayor role: legitimate mayor whose town has no registered space keeps their role")
    void auditMayorRolePreservesLegitimateMayorWhoseTownHasNoSpace() {
        UUID charlieUuid = UUID.randomUUID();
        String charlieDiscordId = "discord-charlie";
        spyLinkRepository.save(new AccountLink(charlieUuid, charlieDiscordId, clock.instant(), "Charlie"));

        UUID townNoSpaceUuid = UUID.randomUUID();
        TownSnapshot townNoSpace = new TownSnapshot(
                townNoSpaceUuid, "NoSpaceTown", charlieUuid, List.of(charlieUuid), false,
                Optional.empty(), 1, 100.0, 1000L);

        // Town exists in Towny but NOT in spaceRepository
        when(townyFacade.allTowns()).thenReturn(List.of(townNoSpace));

        String mayorRoleId = "role-mayor-id";
        when(discordGateway.roleHolders(mayorRoleId)).thenReturn(Set.of(charlieDiscordId));

        SyncReport report = service.reconcileAll().join();

        // Charlie legitimately holds the mayor role: no revocation should be submitted
        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway, atLeast(0)).submit(captor.capture());

        boolean charlieRevoked = captor.getAllValues().stream()
                .filter(op -> op instanceof GuildOperation.ApplyMemberRoles a && a.discordId().equals(charlieDiscordId))
                .map(op -> (GuildOperation.ApplyMemberRoles) op)
                .anyMatch(a -> a.revokeRoleIds().contains(mayorRoleId));
        assertFalse(charlieRevoked, "Charlie is legitimate mayor of NoSpaceTown and must not lose mayor role");
        assertEquals(0, report.rolesRevoked());
    }

    @Test
    @DisplayName("Audit mayor role: legitimate mayor whose town has no registered space is granted missing mayor role")
    void auditMayorRoleGrantsMissingRoleToLegitimateMayorWhoseTownHasNoSpace() {
        UUID charlieUuid = UUID.randomUUID();
        String charlieDiscordId = "discord-charlie";
        spyLinkRepository.save(new AccountLink(charlieUuid, charlieDiscordId, clock.instant(), "Charlie"));

        UUID townNoSpaceUuid = UUID.randomUUID();
        TownSnapshot townNoSpace = new TownSnapshot(
                townNoSpaceUuid, "NoSpaceTown", charlieUuid, List.of(charlieUuid), false,
                Optional.empty(), 1, 100.0, 1000L);

        // Alice has mayor role, Charlie does not
        String mayorRoleId = "role-mayor-id";
        when(discordGateway.roleHolders(mayorRoleId)).thenReturn(Set.of("discord-alice"));

        // Alice is also a verified mayor
        UUID aliceUuid = UUID.randomUUID();
        spyLinkRepository.save(new AccountLink(aliceUuid, "discord-alice", clock.instant(), "Alice"));
        UUID townAliceUuid = UUID.randomUUID();
        TownSnapshot townAlice = new TownSnapshot(
                townAliceUuid, "AliceTown", aliceUuid, List.of(aliceUuid), false,
                Optional.empty(), 1, 100.0, 1000L);
        when(townyFacade.allTowns()).thenReturn(List.of(townNoSpace, townAlice));

        SyncReport report = service.reconcileAll().join();

        // Charlie must be granted the mayor role
        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway, atLeastOnce()).submit(captor.capture());

        GuildOperation.ApplyMemberRoles charlieOp = captor.getAllValues().stream()
                .filter(op -> op instanceof GuildOperation.ApplyMemberRoles a && a.discordId().equals(charlieDiscordId))
                .map(op -> (GuildOperation.ApplyMemberRoles) op)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected ApplyMemberRoles operation for mayor Charlie"));

        assertTrue(charlieOp.grantRoleIds().contains(mayorRoleId), "Charlie must be granted the mayor role");
        assertTrue(report.rolesGranted() >= 1, "Must record role granted");
    }

    @Test
    @DisplayName("Audit mayor role: empty or failed holder lookup or failed Towny read revokes nothing")
    void auditMayorRoleEmptyOrFailedLookupRevokesNothing() {
        String mayorRoleId = "role-mayor-id";
        UUID aliceUuid = UUID.randomUUID();
        String aliceDiscordId = "discord-alice";
        spyLinkRepository.save(new AccountLink(aliceUuid, aliceDiscordId, clock.instant(), "Alice"));

        TownSnapshot townA = new TownSnapshot(
                UUID.randomUUID(), "TownA", aliceUuid, List.of(aliceUuid), false,
                Optional.empty(), 1, 100.0, 1000L);
        when(townyFacade.allTowns()).thenReturn(List.of(townA));

        // Subcase A: Gateway roleHolders throws exception
        when(discordGateway.roleHolders(mayorRoleId)).thenThrow(new IllegalStateException("Gateway timeout"));

        SyncReport reportThrown = service.reconcileAll().join();
        assertEquals(0, reportThrown.rolesRevoked(), "Exception in roleHolders must revoke nothing");
        assertTrue(reportThrown.inconsistenciesFound() >= 1);
        assertTrue(reportThrown.problemDetails().stream().anyMatch(p ->
                "sync.problem-lookup-mayor-holders-failed".equals(p.key())
                && mayorRoleId.equals(p.placeholders().get("role"))
                && p.placeholders().get("error").contains("timeout")),
                "Problem details must carry structured key and error detail for failed mayor role lookup");
        assertTrue(reportThrown.problems(enMessages).stream().anyMatch(p -> p.contains("mayor") && p.contains("timed out")));
        assertTrue(reportThrown.problems(esMessages).stream().anyMatch(p -> p.contains("alcalde") && p.contains("tiempo de espera agotado")));
        assertFalse(reportThrown.problems(esMessages).stream().anyMatch(p -> p.contains("Gateway timeout")));

        // Subcase B: roleHolders returns empty set when verified mayors exist (cache cold)
        reset(discordGateway);
        when(discordGateway.isAvailable()).thenReturn(true);
        when(discordGateway.mayorRoleId()).thenReturn(Optional.of(mayorRoleId));
        when(discordGateway.roleHolders(mayorRoleId)).thenReturn(Set.of());

        SyncReport reportEmpty = service.reconcileAll().join();
        assertEquals(0, reportEmpty.rolesRevoked(), "Empty roleHolders lookup must revoke nothing");
        assertTrue(reportEmpty.inconsistenciesFound() >= 1);
        assertTrue(reportEmpty.problemDetails().stream().anyMatch(p ->
                "sync.problem-mayor-holders-empty".equals(p.key())
                && mayorRoleId.equals(p.placeholders().get("role"))),
                "Problem details must carry structured key for empty mayor holders");
        assertTrue(reportEmpty.problems(enMessages).stream().anyMatch(p ->
                p.toLowerCase().contains("cold") || p.toLowerCase().contains("cache") || p.toLowerCase().contains("empty")));
        assertTrue(reportEmpty.problems(esMessages).stream().anyMatch(p ->
                p.toLowerCase().contains("fría") || p.toLowerCase().contains("caché")));
        verify(discordGateway, never()).submit(any());

        // Subcase C: TownyFacade.allTowns() throws TownyReadException
        when(discordGateway.roleHolders(mayorRoleId)).thenReturn(Set.of("discord-eve"));
        when(townyFacade.allTowns()).thenThrow(new TownyReadException("Towny data unavailable"));

        SyncReport reportTownyFailed = service.reconcileAll().join();
        assertEquals(0, reportTownyFailed.rolesRevoked(), "Towny read failure must revoke nothing");
        assertTrue(reportTownyFailed.inconsistenciesFound() >= 1);
        assertTrue(reportTownyFailed.problemDetails().stream().anyMatch(p ->
                "sync.problem-mayor-audit-towny-failed".equals(p.key())
                && p.placeholders().get("error").contains("unavailable")),
                "Problem details must carry structured key for failed mayor audit Towny read");
        assertTrue(reportTownyFailed.problems(enMessages).stream().anyMatch(p ->
                p.contains("Towny read failed") && p.contains("Towny is unavailable")));
        assertTrue(reportTownyFailed.problems(esMessages).stream().anyMatch(p ->
                p.contains("Error de lectura de Towny") && p.contains("Towny no está disponible")));
        assertFalse(reportTownyFailed.problems(esMessages).stream().anyMatch(p ->
                p.contains("Towny data unavailable") || p.contains("Towny is unavailable")));
        verify(discordGateway, never()).submit(any());
    }

    // --- T19 Dedicated Tests ---

    @Test
    @DisplayName("T19: No problem emitted by DefaultSyncService contains a finished English sentence")
    void noProblemEmittedBySyncServiceContainsFinishedEnglishSentence() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "CheckTown",
                Optional.of("cat-1"), Optional.of("txt-c"), Optional.of("vc-c"),
                Optional.of("role-town"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        TownSnapshot town = new TownSnapshot(
                townUuid, "CheckTown", UUID.randomUUID(), List.of(), false,
                Optional.empty(), 1, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));
        when(discordGateway.roleHolders("role-town")).thenReturn(Set.of("unjustified-holder"));

        SyncReport report = service.syncTown(townUuid).join();
        assertFalse(report.problemDetails().isEmpty(), "Report must contain at least one problem");

        for (SyncReport.Problem problem : report.problemDetails()) {
            assertNotNull(problem.key(),
                    "Problem key must not be null: " + problem);
            assertTrue(problem.key().startsWith("sync.problem-"),
                    "Problem key must start with 'sync.problem-': " + problem.key());
            assertFalse(problem.key().contains(" "),
                    "Problem key must not contain spaces (finished sentences): " + problem.key());
        }

        // report.problemKeys() returns catalog keys, not full English sentences
        for (String problemKey : report.problemKeys()) {
            assertTrue(problemKey.startsWith("sync.problem-"),
                    "problemKeys() must return key, got: " + problemKey);
            assertFalse(problemKey.contains(" "),
                    "problemKeys() must not return English sentence with spaces: " + problemKey);
        }
    }

    @Test
    @DisplayName("T19: Structured problems render cleanly in both English and Spanish without unresolved placeholders or missing keys")
    void syncReportRendersInBothEnglishAndSpanishWithoutMissingKeysOrUnresolvedPlaceholders() {
        UUID townUuid = UUID.randomUUID();
        String townRole = "role-town-x";
        TownSpace space = new TownSpace(
                townUuid, "AlphaTown",
                Optional.of("cat-1"), Optional.of("txt-missing"), Optional.of("vc-missing"),
                Optional.of(townRole), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        TownSnapshot town = new TownSnapshot(
                townUuid, "AlphaTown", UUID.randomUUID(), List.of(), false,
                Optional.empty(), 1, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));
        when(discordGateway.roleHolders(townRole)).thenReturn(Set.of("unjustified-alpha"));

        SyncReport report = service.syncTown(townUuid).join();
        assertFalse(report.problemDetails().isEmpty(), "Report must contain problems");

        List<String> enRendered = report.problems(enMessages);
        List<String> esRendered = report.problems(esMessages);

        assertEquals(report.problemDetails().size(), enRendered.size());
        assertEquals(report.problemDetails().size(), esRendered.size());

        for (String en : enRendered) {
            assertFalse(en.startsWith("sync.problem-"), "English message must not be unrendered key: " + en);
            assertFalse(en.contains("[missing message:"), "English message must not be missing: " + en);
            assertFalse(en.matches(".*\\{[a-zA-Z0-9_]+}.*"), "English message must not have unreplaced placeholders: " + en);
        }

        for (String es : esRendered) {
            assertFalse(es.startsWith("sync.problem-"), "Spanish message must not be unrendered key: " + es);
            assertFalse(es.contains("[missing message:"), "Spanish message must not be missing: " + es);
            assertFalse(es.matches(".*\\{[a-zA-Z0-9_]+}.*"), "Spanish message must not have unreplaced placeholders: " + es);
        }
    }

    @Test
    @DisplayName("T19: SyncReport enforces structured Problem contract, exposes problemKeys(), and renders via problems(Messages)")
    void syncReportEnforcesStructuredProblemContract() {
        SyncReport.Problem p1 = new SyncReport.Problem(
                "sync.problem-missing-role", Map.of("town", "Roma", "role", "role-1"));
        SyncReport.Problem p2 = new SyncReport.Problem(
                "sync.problem-towny-unavailable");

        SyncReport report = new SyncReport(
                3, 1, 2, 2, 1,
                List.of(p1, p2),
                PluginConfig.Sync.Mode.REPORT,
                1, 0
        );

        assertTrue(report.isReportMode());
        assertEquals(1, report.proposedGrants());
        assertEquals(0, report.proposedRevocations());
        assertEquals(2, report.problemDetails().size());

        // problemKeys() returns catalog keys without display formatting
        List<String> keys = report.problemKeys();
        assertEquals(List.of("sync.problem-missing-role", "sync.problem-towny-unavailable"), keys);

        // Rendering requires Messages and returns localized text
        List<String> en = report.problems(enMessages);
        assertEquals(2, en.size());
        assertEquals("Space for town Roma has deleted/missing role", en.get(0));
        assertEquals("Towny is unavailable", en.get(1));

        List<String> es = report.problems(esMessages);
        assertEquals(2, es.size());
        assertEquals("El espacio para town Roma tiene un rol eliminado/faltante", es.get(0));
        assertEquals("Towny no está disponible", es.get(1));

        // Passing null Messages throws NullPointerException
        assertThrows(NullPointerException.class, () -> report.problems(null));

        // Creating Problem with null key throws NullPointerException
        assertThrows(NullPointerException.class, () -> new SyncReport.Problem(null));

        // Null problems list defaults to empty
        SyncReport nullProblemsReport = new SyncReport(1, 0, 0, 0, 0, null);
        assertTrue(nullProblemsReport.problemDetails().isEmpty());
        assertTrue(nullProblemsReport.problemKeys().isEmpty());
    }

    @Test
    @DisplayName("T19: All 44 sync problem keys in catalog have matching placeholders and valid Spanish invariant domain terms")
    void allSyncProblemKeysInCatalogHaveMatchingPlaceholdersAndInvariantDomainTerms() throws Exception {
        YamlConfiguration en = new YamlConfiguration();
        YamlConfiguration es = new YamlConfiguration();
        try (var inEn = getClass().getResourceAsStream("/messages_en.yml");
             var inEs = getClass().getResourceAsStream("/messages_es.yml")) {
            assertNotNull(inEn, "messages_en.yml must exist");
            assertNotNull(inEs, "messages_es.yml must exist");
            en.load(new InputStreamReader(inEn, StandardCharsets.UTF_8));
            es.load(new InputStreamReader(inEs, StandardCharsets.UTF_8));
        }

        List<String> enSyncProblemKeys = new ArrayList<>();
        for (String key : en.getKeys(true)) {
            if (key.startsWith("sync.problem-") && !key.equals("sync.problem-entry") && en.isString(key)) {
                enSyncProblemKeys.add(key);
            }
        }

        List<String> esSyncProblemKeys = new ArrayList<>();
        for (String key : es.getKeys(true)) {
            if (key.startsWith("sync.problem-") && !key.equals("sync.problem-entry") && es.isString(key)) {
                esSyncProblemKeys.add(key);
            }
        }

        assertEquals(44, enSyncProblemKeys.size(), "There must be exactly 44 sync.problem-* keys in messages_en.yml");
        assertEquals(enSyncProblemKeys, esSyncProblemKeys, "Keys must exist in identical order in both catalogs");

        java.util.regex.Pattern placeholderPattern = java.util.regex.Pattern.compile("\\{([^{}]+)}");
        for (String key : enSyncProblemKeys) {
            String enVal = en.getString(key);
            String esVal = es.getString(key);
            assertNotNull(enVal, "EN value must not be null for " + key);
            assertNotNull(esVal, "ES value must not be null for " + key);

            Set<String> enPlaceholders = new java.util.HashSet<>();
            var mEn = placeholderPattern.matcher(enVal);
            while (mEn.find()) {
                enPlaceholders.add(mEn.group(1));
            }

            Set<String> esPlaceholders = new java.util.HashSet<>();
            var mEs = placeholderPattern.matcher(esVal);
            while (mEs.find()) {
                esPlaceholders.add(mEs.group(1));
            }

            assertEquals(enPlaceholders, esPlaceholders, "Placeholders must match for key: " + key);

            // Invariant domain terms check (spec section 9.1):
            // "Domain terms are the exception, and they are invariant. town, nation and resident stay as they are in both languages."
            // Verify ES translation doesn't substitute 'ciudad', 'residente', 'nación'
            assertFalse(esVal.toLowerCase().contains("ciudad"),
                    "Key " + key + " in Spanish must preserve domain term 'town' instead of 'ciudad': " + esVal);
            assertFalse(esVal.toLowerCase().contains("residente"),
                    "Key " + key + " in Spanish must preserve domain term 'resident' instead of 'residente': " + esVal);
            assertFalse(esVal.toLowerCase().contains("nación") || esVal.toLowerCase().contains("nacion"),
                    "Key " + key + " in Spanish must preserve domain term 'nation' instead of 'nación': " + esVal);

            // 'space' is NOT an invariant domain term and must be translated to 'espacio' in Spanish
            assertFalse(esVal.matches(".*\\b[Ss]pace\\b.*"),
                    "Key " + key + " in Spanish must translate 'space' to 'espacio': " + esVal);
            assertFalse(esVal.contains("ARCHIVED"),
                    "Key " + key + " in Spanish must not contain raw state 'ARCHIVED': " + esVal);
            assertFalse(esVal.contains("INCONSISTENT"),
                    "Key " + key + " in Spanish must not contain raw state 'INCONSISTENT': " + esVal);
        }
    }

    @Test
    @DisplayName("T19: Ruined town with active space produces localized state in both English and Spanish")
    void ruinedTownWithActiveSpaceProducesLocalizedStateInBothEnglishAndSpanish() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "RuinedRome",
                Optional.of("cat-1"), Optional.of("txt-c"), Optional.of("vc-c"),
                Optional.of("role-town"), SpaceState.ACTIVE,
                clock.instant(), Optional.empty(), Optional.empty());
        spaceRepository.save(space);

        TownSnapshot ruinedTown = new TownSnapshot(
                townUuid, "RuinedRome", UUID.randomUUID(), List.of(), true,
                Optional.empty(), 1, 100.0, 1000L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(ruinedTown));

        SyncReport report = service.syncTown(townUuid).join();
        assertFalse(report.problemDetails().isEmpty(), "Must report problem for ruined town with active space");

        SyncReport.Problem problem = report.problemDetails().stream()
                .filter(p -> "sync.problem-town-ruined-space-state".equals(p.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected problem sync.problem-town-ruined-space-state"));

        assertEquals("RuinedRome", problem.placeholders().get("town"));
        assertEquals("admin.state-active", problem.placeholders().get("state"));

        List<String> en = report.problems(enMessages);
        assertTrue(en.stream().anyMatch(p -> p.contains("Town RuinedRome is ruined but space is active")),
                "English must render state as 'active', got: " + en);
        assertFalse(en.stream().anyMatch(p -> p.contains("ACTIVE")),
                "English must not leak uppercase enum 'ACTIVE'");

        List<String> es = report.problems(esMessages);
        assertTrue(es.stream().anyMatch(p -> p.contains("Town RuinedRome está en ruinas pero el espacio está activo")),
                "Spanish must translate 'space' to 'espacio' and 'state' to 'activo', got: " + es);
        assertFalse(es.stream().anyMatch(p -> p.contains("space") || p.contains("ACTIVE")),
                "Spanish must not leak 'space' or 'ACTIVE'");
    }

    @Test
    @DisplayName("T19: Mayor audit Towny unavailable produces localized cause in both catalogs")
    void mayorAuditTownyUnavailableProducesLocalizedCauseInBothCatalogs() {
        String mayorRoleId = "role-mayor-id";
        when(discordGateway.mayorRoleId()).thenReturn(Optional.of(mayorRoleId));
        when(discordGateway.roleHolders(mayorRoleId)).thenReturn(Set.of("discord-mayor"));
        // Towny available for initial reconcileAll check, then unavailable by the time mayor role audit runs
        when(townyFacade.isAvailable()).thenReturn(true, false);

        SyncReport report = service.reconcileAll().join();
        assertFalse(report.problemDetails().isEmpty(), "Report must contain problem when Towny is unavailable");

        SyncReport.Problem problem = report.problemDetails().stream()
                .filter(p -> "sync.problem-mayor-audit-towny-failed".equals(p.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected problem sync.problem-mayor-audit-towny-failed"));

        // Verify the structured placeholder key
        String errorPlaceholder = problem.placeholders().get("error");
        assertNotNull(errorPlaceholder, "Problem must carry an 'error' placeholder");
        assertEquals("sync.cause-towny-unavailable", errorPlaceholder,
                "Placeholder 'error' must be the catalog key 'sync.cause-towny-unavailable', not raw text");
        assertFalse(errorPlaceholder.contains("Towny is unavailable"),
                "Placeholder value must not contain raw English exception text");
        assertFalse(errorPlaceholder.contains(" "),
                "Placeholder value must be a catalog key without spaces");

        // Verify that resolving the placeholder directly in Spanish contains no English fragment
        String renderedCauseEs = esMessages.label(errorPlaceholder);
        assertEquals("Towny no está disponible", renderedCauseEs,
                "Spanish catalog must render cause key as 'Towny no está disponible'");
        assertFalse(renderedCauseEs.contains("Towny is unavailable"),
                "Rendered Spanish cause must not contain English sentence 'Towny is unavailable'");
        assertFalse(renderedCauseEs.toLowerCase().contains("unavailable"),
                "Rendered Spanish cause must contain no English fragment 'unavailable', got: " + renderedCauseEs);

        // Verify that resolving the placeholder in English produces the expected English text
        String renderedCauseEn = enMessages.label(errorPlaceholder);
        assertEquals("Towny is unavailable", renderedCauseEn,
                "English catalog must render cause key as 'Towny is unavailable'");

        // Verify English problem message rendering
        List<String> en = report.problems(enMessages);
        assertTrue(en.stream().anyMatch(p -> p.contains("Towny read failed during mayor role audit: Towny is unavailable")),
                "English must render cause as 'Towny is unavailable', got: " + en);

        // Verify Spanish problem message rendering and isolate the rendered placeholder value
        List<String> es = report.problems(esMessages);
        String esProblem = es.stream()
                .filter(p -> p.contains("Error de lectura de Towny durante la auditoría del rol de alcalde"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected Spanish mayor audit towny failed message in: " + es));

        String prefix = "Error de lectura de Towny durante la auditoría del rol de alcalde: ";
        assertTrue(esProblem.startsWith(prefix),
                "Spanish problem must start with prefix '" + prefix + "', got: " + esProblem);

        String placeholderInSentence = esProblem.substring(prefix.length());
        assertEquals("Towny no está disponible", placeholderInSentence,
                "Rendered placeholder inside Spanish sentence must be 'Towny no está disponible'");
        assertFalse(placeholderInSentence.contains("Towny is unavailable"),
                "Rendered placeholder inside Spanish sentence must not contain 'Towny is unavailable'");
        assertFalse(placeholderInSentence.toLowerCase().contains("unavailable"),
                "Rendered placeholder inside Spanish sentence must not contain English fragment 'unavailable'");

        assertFalse(esProblem.contains("Towny is unavailable"),
                "Spanish problem must not leak English cause text 'Towny is unavailable'");
        assertFalse(esProblem.toLowerCase().contains("unavailable"),
                "Spanish problem must not contain English fragment 'unavailable'");
        assertFalse(esProblem.contains("unknown"),
                "Spanish problem must not leak 'unknown'");
    }

    @Test
    @DisplayName("T19: Internal fallback unknown error produces localized unknown in both catalogs")
    void internalFallbackUnknownErrorProducesLocalizedUnknownInBothCatalogs() {
        SyncReport.Problem nullErrorProblem = new SyncReport.Problem(
                "sync.problem-batch-failed",
                Map.of("batch", "1", "error", "general.unknown")
        );
        SyncReport nullReport = new SyncReport(1, 0, 0, 1, 0, List.of(nullErrorProblem));

        assertEquals("Batch 1 failed: unknown", nullReport.problems(enMessages).get(0));
        assertEquals("Lote 1 fallido: desconocido", nullReport.problems(esMessages).get(0));

        // Even if raw 'unknown' string is supplied in placeholder, Problem.render maps it to localized unknown
        SyncReport.Problem rawUnknownProblem = new SyncReport.Problem(
                "sync.problem-batch-failed",
                Map.of("batch", "2", "error", "unknown")
        );
        SyncReport rawReport = new SyncReport(1, 0, 0, 1, 0, List.of(rawUnknownProblem));

        assertEquals("Batch 2 failed: unknown", rawReport.problems(enMessages).get(0));
        assertEquals("Lote 2 fallido: desconocido", rawReport.problems(esMessages).get(0));
    }
}

