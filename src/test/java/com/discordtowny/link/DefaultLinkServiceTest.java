package com.discordtowny.link;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.discord.GuildOperation;
import com.discordtowny.discord.OperationOutcome;
import com.discordtowny.model.AccountLink;
import com.discordtowny.model.AuditEvent;
import com.discordtowny.model.LinkCode;
import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSpace;
import com.discordtowny.storage.HikariStorage;
import com.discordtowny.storage.LinkRepository;
import com.discordtowny.storage.SpaceRepository;
import com.discordtowny.storage.StorageException;
import com.discordtowny.sync.SyncService;
import com.discordtowny.towny.TownyFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Mandatory tests for {@link DefaultLinkService} against real SQLite.
 */
class DefaultLinkServiceTest {

    private Path dbFile;
    private HikariStorage storage;
    private PluginConfig.Database dbConfig;
    private LinkRepository linkRepository;
    private SpaceRepository spaceRepository;
    private MutableClock clock;
    private PluginConfig config;
    private DiscordGateway discordGateway;
    private TownyFacade townyFacade;
    private SyncService syncService;
    private DefaultLinkService service;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("discordtowny-linktest-", ".db");

        dbConfig = new PluginConfig.Database(
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

        storage = new HikariStorage(dbConfig, Logger.getLogger("LinkServiceTest"));
        storage.initialize();
        linkRepository = storage.links();
        spaceRepository = storage.spaces();

        clock = new MutableClock(Instant.parse("2026-09-18T12:00:00Z"));

        // Expiry 10m, maximum 3 failed attempts, 15m lockout
        PluginConfig.Linking linkingConfig = new PluginConfig.Linking(
                Duration.ofMinutes(10),
                3,
                Duration.ofMinutes(15),
                true
        );

        config = new PluginConfig(
                new PluginConfig.Discord("token-dummy", "guild-dummy", Optional.empty()),
                dbConfig,
                new PluginConfig.Structure("Comunidades", "Archivo", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Alcalde", "{town}", Optional.empty(), false),
                new PluginConfig.Limits(200, 2, Duration.ofSeconds(60)),
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                new PluginConfig.Sync(Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPAIR, 20, Duration.ofSeconds(5)),
                linkingConfig,
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );

        discordGateway = mock(DiscordGateway.class);
        townyFacade = mock(TownyFacade.class);
        syncService = mock(SyncService.class);

        when(syncService.syncPlayer(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(discordGateway.submit(any())).thenReturn(CompletableFuture.completedFuture(OperationOutcome.success()));
        when(discordGateway.mayorRoleId()).thenReturn(Optional.empty());

        service = new DefaultLinkService(
                linkRepository,
                config,
                discordGateway,
                townyFacade,
                spaceRepository,
                syncService,
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

    // --- 1. Full cycle with reopen and audit verification without secrets ---

    @Test
    void fullCycleGenerateRedeemAndPersist() {
        UUID uuid = UUID.randomUUID();
        String discordId = "discord_user_1";

        // Generate
        Optional<String> optCode = service.generateCode(uuid).join();
        assertTrue(optCode.isPresent(), "Must generate a code");
        String code = optCode.get();
        assertEquals(6, code.length());
        assertFalse(CodeGenerator.containsAmbiguousCharacters(code));

        // Redeem
        LinkService.LinkResult result = service.redeem(code, discordId).join();
        assertEquals(LinkService.LinkResult.SUCCESS, result);

        // Persisted link
        Optional<AccountLink> byUuid = service.findByUuid(uuid).join();
        assertTrue(byUuid.isPresent());
        assertEquals(uuid, byUuid.get().uuid());
        assertEquals(discordId, byUuid.get().discordId());

        Optional<AccountLink> byDiscord = service.findByDiscordId(discordId).join();
        assertTrue(byDiscord.isPresent());
        assertEquals(uuid, byDiscord.get().uuid());

        // Role synchronization was triggered
        verify(syncService).syncPlayer(uuid);

        // Audit event was recorded without exposing the code in any field (finding 7)
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(discordGateway).log(captor.capture());
        AuditEvent event = captor.getValue();
        assertEquals("link", event.action());
        assertEquals(discordId, event.actor());
        assertTrue(event.success());
        assertFalse(event.target().contains(code), "Audit target must not contain the code");
        event.detail().ifPresent(d -> assertFalse(d.contains(code), "The detail must not contain the code"));

        // Verify persistence after restart/reopening of storage (finding 12)
        storage.close();
        HikariStorage reopened = new HikariStorage(dbConfig, Logger.getLogger("ReopenTest"));
        reopened.initialize();
        try {
            Optional<AccountLink> reopenedLink = reopened.links().findByUuid(uuid);
            assertTrue(reopenedLink.isPresent(), "The link must persist on disk after reopening");
            assertEquals(discordId, reopenedLink.get().discordId());
        } finally {
            reopened.close();
        }
    }

    // --- 2. Expired, nonexistent and already used code ---

    @Test
    void expiredCodeIsRejected() {
        UUID uuid = UUID.randomUUID();
        String code = service.generateCode(uuid).join().orElseThrow();

        // Advance time 11 minutes (expires after 10 minutes)
        clock.advance(Duration.ofMinutes(11));

        LinkService.LinkResult result = service.redeem(code, "discord_user_1").join();
        assertEquals(LinkService.LinkResult.CODE_EXPIRED, result);

        // The code must have been deleted upon detecting it expired
        assertTrue(linkRepository.findCode(code).isEmpty());
        // The link must not have been created
        assertTrue(service.findByUuid(uuid).join().isEmpty());

        // Audit does not expose the code
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(discordGateway).log(captor.capture());
        assertFalse(captor.getValue().target().contains(code));
    }

    @Test
    void nonexistentCodeIsRejected() {
        LinkService.LinkResult result = service.redeem("NOEXISTE", "discord_user_1").join();
        assertEquals(LinkService.LinkResult.CODE_INVALID, result);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(discordGateway).log(captor.capture());
        assertFalse(captor.getValue().target().contains("NOEXISTE"));
    }

    @Test
    void alreadyUsedCodeCannotBeRedeemedTwice() {
        UUID uuid = UUID.randomUUID();
        String code = service.generateCode(uuid).join().orElseThrow();

        LinkService.LinkResult first = service.redeem(code, "discord_user_1").join();
        assertEquals(LinkService.LinkResult.SUCCESS, first);

        // Second attempt with another Discord account
        LinkService.LinkResult second = service.redeem(code, "discord_user_2").join();
        // The code no longer exists in the database after first use
        assertEquals(LinkService.LinkResult.CODE_INVALID, second);
    }

    // --- 3. Player and Discord account already linked ---

    @Test
    void alreadyLinkedPlayerCannotGenerateNewCode() {
        UUID uuid = UUID.randomUUID();
        String code = service.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, "discord_user_1").join());

        // Attempt to generate code while already linked
        Optional<String> newCode = service.generateCode(uuid).join();
        assertTrue(newCode.isEmpty(), "An already linked player must not be able to generate codes");
    }

    @Test
    void alreadyLinkedPlayerRejectsRedemption() {
        UUID uuid = UUID.randomUUID();
        LinkCode code = new LinkCode("TEST01", uuid, clock.instant().plusSeconds(600), 0);
        linkRepository.saveCode(code);

        AccountLink existingLink = new AccountLink(uuid, "discord_otra", clock.instant(), "Jugador");
        linkRepository.save(existingLink);

        LinkService.LinkResult result = service.redeem("TEST01", "discord_nueva").join();
        assertEquals(LinkService.LinkResult.PLAYER_ALREADY_LINKED, result);
    }

    @Test
    void alreadyLinkedDiscordAccountIsRejected() {
        UUID uuid1 = UUID.randomUUID();
        String code1 = service.generateCode(uuid1).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code1, "discord_user_1").join());

        // Another player generates a code
        UUID uuid2 = UUID.randomUUID();
        String code2 = service.generateCode(uuid2).join().orElseThrow();

        // The discord_user_1 account attempts to redeem the second player's code
        LinkService.LinkResult result = service.redeem(code2, "discord_user_1").join();
        assertEquals(LinkService.LinkResult.DISCORD_ALREADY_LINKED, result);
    }

    // --- 4. Attempt limit and time budget ---

    @Test
    void attemptLimitActuallyBlocks() {
        String discordId = "discord_atacante";

        // maxAttempts = 3
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem("MAL001", discordId).join());
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem("MAL002", discordId).join());

        // On the third consecutive failure, lockout is triggered
        assertEquals(LinkService.LinkResult.TOO_MANY_ATTEMPTS, service.redeem("MAL003", discordId).join());

        // Subsequent attempts while locked out are immediately rejected
        assertEquals(LinkService.LinkResult.TOO_MANY_ATTEMPTS, service.redeem("MAL004", discordId).join());

        // Even with a valid existing code, it is rejected due to being locked out
        UUID uuid = UUID.randomUUID();
        String validCode = service.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.TOO_MANY_ATTEMPTS, service.redeem(validCode, discordId).join());

        // Another Discord user is not locked out
        String discordId2 = "discord_inocente";
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(validCode, discordId2).join());

        // After the lockout duration passes (15 minutes), the attacking user is unlocked
        clock.advance(Duration.ofMinutes(16));
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem("MAL005", discordId).join());
    }

    @Test
    void successAndUnlinkDoNotResetTheFailureBudgetWithinTheWindow() {
        // Fix for finding 4 and finding 12: observably verify
        // that a successful redemption and subsequent unlinking DO NOT clear the counter
        String discordId = "discord_atacante_astuto";

        // maxAttempts = 3: fails twice (1 attempt remains in the window)
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem("MAL001", discordId).join());
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem("MAL002", discordId).join());

        // Redeems their own valid code with success
        UUID ownUuid = UUID.randomUUID();
        String validCode = service.generateCode(ownUuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(validCode, discordId).join());

        // Unlinks the account
        assertTrue(service.unlink(ownUuid).join());

        // The attacker tries a new fake code within the 15-minute window:
        // It must be their 3rd accumulated failure and trigger lockout immediately
        assertEquals(LinkService.LinkResult.TOO_MANY_ATTEMPTS, service.redeem("MAL003", discordId).join());
    }

    @Test
    void concurrentBurstOfFailedAttemptsRespectsTheBudget() throws Exception {
        // Finding 4 and 12: count actual entries to consumeCodeAndLink and demand exact results
        String discordId = "discord_burst_attacker";
        int totalRequests = 10;
        CyclicBarrier barrier = new CyclicBarrier(totalRequests);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        List<LinkService.LinkResult> results = Collections.synchronizedList(new ArrayList<>());

        AtomicInteger consumeCalls = new AtomicInteger(0);
        LinkRepository spyRepo = spy(linkRepository);
        doAnswer(inv -> {
            consumeCalls.incrementAndGet();
            return inv.callRealMethod();
        }).when(spyRepo).consumeCodeAndLink(any(), eq(discordId), any(), any());

        DefaultLinkService burstService = new DefaultLinkService(
                spyRepo, config, discordGateway, townyFacade, spaceRepository, syncService, clock, ForkJoinPool.commonPool()
        );

        for (int i = 0; i < totalRequests; i++) {
            final String badCode = "BURST" + i;
            new Thread(() -> {
                try {
                    barrier.await();
                    LinkService.LinkResult res = burstService.redeem(badCode, discordId).join();
                    results.add(res);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        long invalidCount = results.stream().filter(r -> r == LinkService.LinkResult.CODE_INVALID).count();
        long lockedCount = results.stream().filter(r -> r == LinkService.LinkResult.TOO_MANY_ATTEMPTS).count();

        assertEquals(totalRequests, results.size(), "All requests must complete");
        // Since maxAttempts = 3: exactly 2 failures are invalid, the 3rd locks out and the remaining 7 bounce due to lockout
        assertEquals(2, invalidCount, "Exactly 2 CODE_INVALID results must be recorded");
        assertEquals(8, lockedCount, "Exactly 8 TOO_MANY_ATTEMPTS results must be recorded");
        // Actual entries to consumeCodeAndLink: only the first 3 attempts enter before lockout
        assertEquals(3, consumeCalls.get(), "Only exactly 3 real calls to consumeCodeAndLink must be performed");
    }

    // --- 5. Generating a new code invalidates the previous one ---

    @Test
    void generatingNewCodeInvalidatesThePreviousOne() {
        UUID uuid = UUID.randomUUID();

        String code1 = service.generateCode(uuid).join().orElseThrow();
        String code2 = service.generateCode(uuid).join().orElseThrow();

        assertNotEquals(code1, code2, "The two codes must be distinct");

        // The old code is no longer valid
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem(code1, "discord_1").join());

        // The new code is valid
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code2, "discord_1").join());
    }

    // --- 6. Ambiguous characters ---

    @Test
    void generatedCodesDoNotContainAmbiguousCharacters() {
        UUID uuid = UUID.randomUUID();
        for (int i = 0; i < 500; i++) {
            String code = service.generateCode(uuid).join().orElseThrow();
            assertFalse(CodeGenerator.containsAmbiguousCharacters(code));
            linkRepository.deleteCode(code);
        }
    }

    // --- 7. Unlinking and role removal (findings 2, 3, 12) ---

    @Test
    void unlinkRemovesLinkAndRevokesRolesIncludingMayorRole() {
        UUID uuid = UUID.randomUUID();
        String discordId = "discord_linked_mayor";

        // Register a town space with role
        TownSpace townSpace = new TownSpace(
                UUID.randomUUID(), "Madrid",
                Optional.of("cat-1"), Optional.of("text-1"), Optional.of("voice-1"),
                Optional.of("role-town-madrid"), SpaceState.ACTIVE, clock.instant(),
                Optional.empty(), Optional.empty()
        );
        spaceRepository.save(townSpace);

        // Gateway exposes the configured mayor role
        when(discordGateway.mayorRoleId()).thenReturn(Optional.of("role-mayor-global"));

        String code = service.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, discordId).join());

        // Unlink
        boolean unlinked = service.unlink(uuid).join();
        assertTrue(unlinked);

        // Link no longer exists
        assertTrue(service.findByUuid(uuid).join().isEmpty());
        assertTrue(service.findByDiscordId(discordId).join().isEmpty());

        // Command was sent to DiscordGateway to remove exact roles (town + mayor)
        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway, atLeastOnce()).submit(captor.capture());

        GuildOperation.ApplyMemberRoles applyOp = captor.getAllValues().stream()
                .filter(op -> op instanceof GuildOperation.ApplyMemberRoles)
                .map(op -> (GuildOperation.ApplyMemberRoles) op)
                .filter(op -> op.discordId().equals(discordId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ApplyMemberRoles order must be sent for " + discordId));

        // Finding 12 and 14: require exact equality in the list of removed roles
        List<String> expectedRoles = List.of("role-town-madrid", "role-mayor-global");
        assertEquals(expectedRoles, applyOp.revokeRoleIds(),
                "The list of roles to revoke must match exactly with no excess or missing roles");
    }

    @Test
    void unlinkDoesNotConfirmSuccessWhenDiscordFailsAndPreservesTheLink() {
        // Finding 2: with bot down or gateway failed, do not delete the link or confirm success
        UUID uuid = UUID.randomUUID();
        String discordId = "discord_gateway_down";

        String code = service.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, discordId).join());

        // Simulate Discord returning a transient failure
        when(discordGateway.submit(any())).thenReturn(
                CompletableFuture.completedFuture(OperationOutcome.transientFailure("Discord is unavailable")));

        CompletionException ex = assertThrows(CompletionException.class, () -> service.unlink(uuid).join());
        assertInstanceOf(IllegalStateException.class, ex.getCause());

        // The link must NOT have been deleted
        assertTrue(service.findByUuid(uuid).join().isPresent(),
                "The link must remain if Discord could not remove the roles");
        assertTrue(service.findByDiscordId(discordId).join().isPresent());
    }

    @Test
    void unlinkPropagatesSpaceRepositoryFailureWithoutDeletingLink() {
        // Finding 2: if spaceRepository.findAll() fails, do not swallow the exception
        SpaceRepository failingSpaceRepo = mock(SpaceRepository.class);
        when(failingSpaceRepo.findAll()).thenThrow(new StorageException("Error de BD en spaces"));

        DefaultLinkService failingService = new DefaultLinkService(
                linkRepository, config, discordGateway, townyFacade, failingSpaceRepo, syncService, clock, ForkJoinPool.commonPool()
        );

        UUID uuid = UUID.randomUUID();
        String discordId = "discord_space_err";

        String code = failingService.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, failingService.redeem(code, discordId).join());

        CompletionException ex = assertThrows(CompletionException.class, () -> failingService.unlink(uuid).join());
        assertInstanceOf(StorageException.class, ex.getCause());

        // The link must NOT have been deleted
        assertTrue(failingService.findByUuid(uuid).join().isPresent());
    }

    @Test
    void conditionalUnlinkDoesNotDeleteIfLinkWasRecreated() {
        // Finding 9: prevent an old request from deleting a recreated link
        UUID uuid = UUID.randomUUID();
        String oldDiscordId = "discord_antiguo";
        String newDiscordId = "discord_nuevo";

        String code = service.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, oldDiscordId).join());

        // Simulate that between authorization and deletion, the link was recreated for another account
        linkRepository.deleteByUuid(uuid);
        linkRepository.save(new AccountLink(uuid, newDiscordId, clock.instant(), "Jugador"));

        // Attempt to unlink conditional on the old account
        boolean unlinked = service.unlink(uuid, oldDiscordId).join();
        assertFalse(unlinked, "Must not delete if the Discord account does not match the expected one");

        // The link for newDiscordId remains intact
        Optional<AccountLink> actual = service.findByUuid(uuid).join();
        assertTrue(actual.isPresent());
        assertEquals(newDiscordId, actual.get().discordId());
    }

    @Test
    void conditionalUnlinkDoesNotDeleteIfLinkWasRecreatedWithSameDiscordIdButDifferentLinkedAt() {
        // Finding 9: strict conditional deletion by authorization timestamp
        UUID uuid = UUID.randomUUID();
        String discordId = "discord_recreated_same_user";
        Instant oldLinkedAt = clock.instant();

        String code = service.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, discordId).join());

        // Simulate that the link was broken and recreated at a later time
        clock.advance(Duration.ofHours(1));
        Instant newLinkedAt = clock.instant();
        linkRepository.deleteByUuid(uuid);
        linkRepository.save(new AccountLink(uuid, discordId, newLinkedAt, "Jugador"));

        // Unlink with the old version read in previous authorization
        boolean unlinked = service.unlink(uuid, discordId, oldLinkedAt).join();
        assertFalse(unlinked, "Must not delete if the link date does not match");

        // The new link remains intact
        Optional<AccountLink> actual = service.findByUuid(uuid).join();
        assertTrue(actual.isPresent());
        assertEquals(newLinkedAt, actual.get().linkedAt());
    }

    @Test
    void unlinkUnlinkedPlayerReturnsFalse() {
        assertFalse(service.unlink(UUID.randomUUID()).join());
    }

    // --- 8. Purge of expired codes ---

    @Test
    void purgeOfExpiredCodes() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();

        String code1 = service.generateCode(u1).join().orElseThrow();
        clock.advance(Duration.ofMinutes(15));
        String code2 = service.generateCode(u2).join().orElseThrow();

        int purged = service.purgeExpiredCodes().join();
        assertEquals(1, purged);

        assertTrue(linkRepository.findCode(code1).isEmpty());
        assertTrue(linkRepository.findCode(code2).isPresent());
    }

    // --- 9. Name capture, synchronization and real concurrency ---

    @Test
    void nameCapturedOnGenerationIsPersistedUponRedemption() {
        UUID uuid = UUID.randomUUID();
        String code = service.generateCode(uuid, "Steve").join().orElseThrow();

        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, "discord_steve").join());

        AccountLink link = service.findByUuid(uuid).join().orElseThrow();
        assertEquals("Steve", link.lastKnownName());
    }

    @Test
    void syncServiceFailureIsObservableInRedeemFuture() {
        // Finding 5: make any synchronization failure observable
        when(syncService.syncPlayer(any())).thenReturn(
                CompletableFuture.failedFuture(new RuntimeException("Fallo en sincronizacion")));

        UUID uuid = UUID.randomUUID();
        String code = service.generateCode(uuid).join().orElseThrow();

        CompletionException ex = assertThrows(CompletionException.class, () ->
                service.redeem(code, "discord_sync_err").join());
        assertInstanceOf(RuntimeException.class, ex.getCause());
        assertEquals("Fallo en sincronizacion", ex.getCause().getMessage());
    }

    @Test
    void genericDatabaseFailureIsNotReportedAsDuplicateLink() {
        LinkRepository mockRepo = mock(LinkRepository.class);
        String code = "ABC234";

        when(mockRepo.consumeCodeAndLink(eq(code), eq("discord_test"), anyString(), any()))
                .thenThrow(new StorageException("Error al canjear", new SQLException("disk I/O error")));

        DefaultLinkService failService = new DefaultLinkService(
                mockRepo, config, discordGateway, townyFacade, spaceRepository, syncService, clock, ForkJoinPool.commonPool()
        );

        CompletionException ex = assertThrows(
                CompletionException.class,
                () -> failService.redeem(code, "discord_test").join()
        );

        assertInstanceOf(StorageException.class, ex.getCause());
        assertFalse(ex.getCause().getMessage().contains("Ya existe"));
    }

    @Test
    void concurrentRedemptionOfSameCodeOnlyOneSucceeds() throws Exception {
        // Finding 1 and 12: real concurrency with barrier and multiple threads
        UUID uuid = UUID.randomUUID();
        String code = service.generateCode(uuid).join().orElseThrow();

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        List<LinkService.LinkResult> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final String discordUser = "discord_concurrent_" + i;
            new Thread(() -> {
                try {
                    barrier.await();
                    LinkService.LinkResult res = service.redeem(code, discordUser).join();
                    results.add(res);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        long successCount = results.stream().filter(r -> r == LinkService.LinkResult.SUCCESS).count();
        long failedCount = results.stream().filter(r -> r == LinkService.LinkResult.CODE_INVALID).count();

        assertEquals(1, successCount, "Exactly one of the redemptions must succeed");
        assertEquals(1, failedCount, "The other concurrent redemption must be rejected");
    }

    // Known coverage boundary (finding 12): exotic interleavings (partial failure followed
    // by retry, recreations during pending operations, redemption against regeneration and deletion
    // failure) require instrumenting the repository with internal barriers, which would add
    // more scaffolding than real value. This is accepted as a documented test limitation.
}
