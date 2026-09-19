package com.discordtowny.link;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.discord.GuildOperation;
import com.discordtowny.discord.OperationOutcome;
import com.discordtowny.model.AccountLink;
import com.discordtowny.model.AuditEvent;
import com.discordtowny.model.LinkCode;
import com.discordtowny.storage.LinkRepository;
import com.discordtowny.storage.SpaceRepository;
import com.discordtowny.sync.SyncService;
import com.discordtowny.towny.TownyFacade;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Default implementation of {@link LinkService}.
 *
 * <p>Dependency rule: pure domain, does not import JDA or Bukkit.
 * All database operations run off the main thread through the
 * configured executor.
 */
public final class DefaultLinkService implements LinkService {

    private final LinkRepository linkRepository;
    private final PluginConfig config;
    private final DiscordGateway discordGateway;
    private final TownyFacade townyFacade;
    private final SpaceRepository spaceRepository;
    private final SyncService syncService;
    private final Clock clock;
    private final Executor executor;
    private final CodeGenerator codeGenerator;
    private final AttemptTracker attemptTracker;
    private final ConcurrentHashMap<UUID, String> pendingPlayerNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pendingCodeNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> discordLocks = new ConcurrentHashMap<>();

    public DefaultLinkService(
            LinkRepository linkRepository,
            PluginConfig config,
            DiscordGateway discordGateway,
            TownyFacade townyFacade,
            SpaceRepository spaceRepository,
            SyncService syncService,
            Clock clock,
            Executor executor) {
        this.linkRepository = Objects.requireNonNull(linkRepository, "linkRepository");
        this.config = Objects.requireNonNull(config, "config");
        this.discordGateway = Objects.requireNonNull(discordGateway, "discordGateway");
        this.townyFacade = townyFacade;
        this.spaceRepository = Objects.requireNonNull(spaceRepository, "spaceRepository");
        this.syncService = Objects.requireNonNull(syncService, "syncService");
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
        this.codeGenerator = new CodeGenerator();
        this.attemptTracker = new AttemptTracker();
    }

    public DefaultLinkService(
            LinkRepository linkRepository,
            PluginConfig config,
            DiscordGateway discordGateway,
            TownyFacade townyFacade,
            SpaceRepository spaceRepository,
            SyncService syncService) {
        this(linkRepository, config, discordGateway, townyFacade, spaceRepository, syncService,
                Clock.systemUTC(), ForkJoinPool.commonPool());
    }

    @Override
    public CompletableFuture<Optional<String>> generateCode(UUID uuid) {
        return generateCode(uuid, "");
    }

    @Override
    public CompletableFuture<Optional<String>> generateCode(UUID uuid, String lastKnownName) {
        return CompletableFuture.supplyAsync(() -> {
            // Return empty if the player is already linked
            if (linkRepository.findByUuid(uuid).isPresent()) {
                return Optional.empty();
            }

            // Generate non-ambiguous code ensuring it does not collide with another active one
            String code;
            int maxTries = 10;
            do {
                code = codeGenerator.nextCode();
                maxTries--;
            } while (linkRepository.findCode(code).isPresent() && maxTries > 0);

            Instant expiresAt = clock.instant().plus(config.linking().codeExpiry());
            LinkCode linkCode = new LinkCode(code, uuid, expiresAt, 0);

            // Save the code replacing any previous code from the same player
            linkRepository.saveCode(linkCode);

            // Save the captured name for when the code is redeemed
            String name = (lastKnownName != null) ? lastKnownName : "";
            pendingCodeNames.put(code, name);
            if (!name.isBlank()) {
                pendingPlayerNames.put(uuid, name);
            } else {
                pendingPlayerNames.remove(uuid);
            }

            return Optional.of(code);
        }, executor);
    }

    @Override
    public CompletableFuture<LinkResult> redeem(String rawCode, String discordId) {
        return CompletableFuture.supplyAsync(() -> {
            if (rawCode == null || rawCode.isBlank() || discordId == null || discordId.isBlank()) {
                return CompletableFuture.completedFuture(LinkResult.CODE_INVALID);
            }

            String code = rawCode.trim().toUpperCase(Locale.ROOT);
            Instant now = clock.instant();

            // Serialize attempt admission and resolution by Discord ID
            synchronized (getDiscordLock(discordId)) {
                // 1. Check previous lockout from accumulated failed attempts in the window
                if (attemptTracker.isLocked(discordId, now)) {
                    return CompletableFuture.completedFuture(LinkResult.TOO_MANY_ATTEMPTS);
                }

                // 2. Get last known name captured on the main thread
                // ponytail: if the server restarts with a pending code, pendingCodeNames will be empty
                // and an empty name ("") will be saved. This is acceptable because lastKnownName is for
                // display only per contract and does not justify a schema migration; T7 will refresh it when syncing on join.
                String lastKnownName = pendingCodeNames.getOrDefault(code, "");
                if (lastKnownName.isBlank()) {
                    lastKnownName = "";
                }

                // 3. Atomic redemption in a single transaction
                LinkRepository.ConsumeOutcome outcome = linkRepository.consumeCodeAndLink(
                        code, discordId, lastKnownName, now);

                int maxAttempts = config.linking().maxAttempts();
                Duration lockoutDuration = config.linking().attemptLockout();

                switch (outcome.result()) {
                    case CODE_NOT_FOUND -> {
                        boolean locked = attemptTracker.recordFailure(discordId, now, maxAttempts, lockoutDuration);
                        // Without exposing the code in the audit
                        discordGateway.log(new AuditEvent(
                                now, AuditEvent.Severity.WARNING, discordId, "link_attempt",
                                "", false, Optional.of("Invalid code")));
                        return CompletableFuture.completedFuture(
                                locked ? LinkResult.TOO_MANY_ATTEMPTS : LinkResult.CODE_INVALID);
                    }
                    case CODE_EXPIRED -> {
                        pendingCodeNames.remove(code);
                        boolean locked = attemptTracker.recordFailure(discordId, now, maxAttempts, lockoutDuration);
                        // Without exposing the code in the audit
                        discordGateway.log(new AuditEvent(
                                now, AuditEvent.Severity.WARNING, discordId, "link_attempt",
                                "", false, Optional.of("Expired code")));
                        return CompletableFuture.completedFuture(
                                locked ? LinkResult.TOO_MANY_ATTEMPTS : LinkResult.CODE_EXPIRED);
                    }
                    case PLAYER_ALREADY_LINKED -> {
                        discordGateway.log(new AuditEvent(
                                now, AuditEvent.Severity.WARNING, discordId, "link_attempt",
                                "", false, Optional.of("Player already linked")));
                        return CompletableFuture.completedFuture(LinkResult.PLAYER_ALREADY_LINKED);
                    }
                    case DISCORD_ALREADY_LINKED -> {
                        return CompletableFuture.completedFuture(LinkResult.DISCORD_ALREADY_LINKED);
                    }
                    case OK -> {
                        AccountLink link = outcome.link().orElseThrow();
                        UUID playerUuid = link.uuid();
                        pendingCodeNames.remove(code);
                        pendingPlayerNames.remove(playerUuid);

                        // Failure counter is not reset: preserved during its time window

                        discordGateway.log(new AuditEvent(
                                now, AuditEvent.Severity.INFO, discordId, "link",
                                playerUuid.toString(), true, Optional.of("Account linked successfully")));

                        // Delegate synchronization to the specified contract and chain result
                        return syncService.syncPlayer(playerUuid)
                                .thenApply(v -> LinkResult.SUCCESS);
                    }
                    default -> throw new IllegalStateException("Unexpected redemption outcome: " + outcome.result());
                }
            }
        }, executor).thenCompose(future -> future);
    }

    @Override
    public CompletableFuture<Optional<AccountLink>> findByUuid(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> linkRepository.findByUuid(uuid), executor);
    }

    @Override
    public CompletableFuture<Optional<AccountLink>> findByDiscordId(String discordId) {
        return CompletableFuture.supplyAsync(() -> linkRepository.findByDiscordId(discordId), executor);
    }

    @Override
    public CompletableFuture<Boolean> unlink(UUID uuid) {
        return unlink(uuid, null, null);
    }

    @Override
    public CompletableFuture<Boolean> unlink(UUID uuid, String expectedDiscordId) {
        return unlink(uuid, expectedDiscordId, null);
    }

    @Override
    public CompletableFuture<Boolean> unlink(UUID uuid, String expectedDiscordId, Instant expectedLinkedAt) {
        return CompletableFuture.supplyAsync(() -> {
            pendingPlayerNames.remove(uuid);

            // 1. Check first: verify existence and read version in authorization before touching Discord
            Optional<AccountLink> optLink = linkRepository.findByUuid(uuid);
            if (optLink.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }

            AccountLink link = optLink.get();
            if (expectedDiscordId != null && !link.discordId().equals(expectedDiscordId)) {
                return CompletableFuture.completedFuture(false);
            }
            if (expectedLinkedAt != null && !link.linkedAt().equals(expectedLinkedAt)) {
                return CompletableFuture.completedFuture(false);
            }

            String discordId = link.discordId();
            Instant linkedAt = link.linkedAt();

            // 2. Collect town roles registered in storage (propagating failures if they occur)
            List<String> rolesToRevoke = new ArrayList<>();
            spaceRepository.findAll().forEach(space -> {
                space.roleId().ifPresent(rolesToRevoke::add);
            });

            // Include also the global mayor role managed by Discord
            // If the gateway cannot resolve it, it will throw an exception, preventing silent success
            discordGateway.mayorRoleId().ifPresent(rolesToRevoke::add);

            // 3. Request role removal on Discord
            return discordGateway.submit(new GuildOperation.ApplyMemberRoles(
                    discordId,
                    List.of(),
                    rolesToRevoke
            )).thenApply(outcome -> {
                if (!outcome.succeeded()) {
                    throw new IllegalStateException("Failed to remove roles on Discord: "
                            + outcome.reason().orElse("unknown"));
                }

                // 4. Atomic conditional deletion: the condition travels within the DELETE itself
                boolean deleted = linkRepository.deleteByUuidIfMatches(uuid, discordId, linkedAt);
                if (!deleted) {
                    return false;
                }

                Instant now = clock.instant();
                discordGateway.log(new AuditEvent(
                        now, AuditEvent.Severity.INFO, "server", "unlink",
                        uuid.toString(), true, Optional.of("Link deleted")));
                return true;
            });
        }, executor).thenCompose(future -> future);
    }

    /**
     * Purges expired codes from the database.
     */
    public CompletableFuture<Integer> purgeExpiredCodes() {
        return CompletableFuture.supplyAsync(() -> linkRepository.purgeExpiredCodes(clock.instant()), executor);
    }

    private Object getDiscordLock(String discordId) {
        return discordLocks.computeIfAbsent(discordId, k -> new Object());
    }
}
