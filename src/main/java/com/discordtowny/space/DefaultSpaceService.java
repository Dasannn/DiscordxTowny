package com.discordtowny.space;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.discord.GuildOperation;
import com.discordtowny.discord.OperationOutcome;
import com.discordtowny.model.AuditEvent;
import com.discordtowny.model.SpaceRequest;
import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSpace;
import com.discordtowny.storage.SpaceRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default implementation of {@link SpaceService}.
 *
 * <p>Dependency rule: domain logic only; does not import JDA or Bukkit.
 * Database operations and Discord interactions are performed off the server main thread.
 *
 * <p>Towny data is never queried off the main thread: the caller reads Towny first
 * and provides a {@link SpaceRequest} containing the required information.
 *
 * <p>Creation is idempotent: any partially created space in {@link SpaceState#INCONSISTENT}
 * state can be resumed without creating duplicate Discord roles or channels.
 */
public final class DefaultSpaceService implements SpaceService {

    private static final Logger LOGGER = Logger.getLogger("DiscordTowny");

    private final SpaceRepository spaceRepository;
    private final PluginConfig config;
    private final DiscordGateway discordGateway;
    private final Consumer<AuditEvent> auditSink;
    private final Clock clock;
    private final Executor executor;

    private final Object admissionLock = new Object();
    private final Set<UUID> pendingTowns = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingMayors = ConcurrentHashMap.newKeySet();
    private int pendingCreations = 0;
    private final ConcurrentHashMap<UUID, Instant> creationCooldowns = new ConcurrentHashMap<>();

    public DefaultSpaceService(
            SpaceRepository spaceRepository,
            PluginConfig config,
            DiscordGateway discordGateway,
            Consumer<AuditEvent> auditSink,
            Clock clock,
            Executor executor) {
        this.spaceRepository = Objects.requireNonNull(spaceRepository, "spaceRepository cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.discordGateway = Objects.requireNonNull(discordGateway, "discordGateway cannot be null");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink cannot be null");
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
    }

    private void audit(AuditEvent event) {
        try {
            auditSink.accept(event);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Failed to deliver audit event: " + t.getMessage(), t);
        }
    }

    @Override
    public CompletableFuture<CreateResult> create(SpaceRequest request) {
        if (request == null || request.townUuid() == null || request.townName() == null || request.townName().isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid space request: town UUID and name are required"));
        }

        return CompletableFuture.supplyAsync(() -> {
            // 1. Cheap in-memory precondition: mayor must have a linked Discord account
            if (request.mayorDiscordId() == null || request.mayorDiscordId().isBlank()) {
                return CompletableFuture.completedFuture(CreateResult.MAYOR_NOT_LINKED);
            }

            // 2. Cheap in-memory precondition: minimum resident count.
            // Measured against the town's whole population, not against the
            // residents who happen to have linked: spec 4.1 rejects a town that
            // "does not reach the configured minimum residents", and linking is
            // a separate requirement that only the mayor has to meet here.
            if (request.townyResidentCount() < config.limits().minResidents()) {
                return CompletableFuture.completedFuture(CreateResult.TOO_FEW_RESIDENTS);
            }

            // 3. Atomic admission: cooldown, quota, existing space, and in-flight tracking
            Instant now = clock.instant();
            boolean admitted = false;
            try {
                synchronized (admissionLock) {
                    Optional<TownSpace> existingOpt = spaceRepository.findByTownUuid(request.townUuid());
                    // Only an incomplete creation (no archivedAt) can be resumed through create
                    boolean isResume = existingOpt.isPresent()
                            && existingOpt.get().state() == SpaceState.INCONSISTENT
                            && existingOpt.get().archivedAt().isEmpty();

                    if (pendingTowns.contains(request.townUuid())) {
                        return CompletableFuture.completedFuture(CreateResult.ALREADY_EXISTS);
                    }

                    // 4. Database checks: does space already exist in active, archived, or interrupted-archive state?
                    if (existingOpt.isPresent()) {
                        TownSpace existing = existingOpt.get();
                        if (existing.state() == SpaceState.ACTIVE
                                || existing.state() == SpaceState.ARCHIVED
                                || existing.archivedAt().isPresent()) {
                            return CompletableFuture.completedFuture(CreateResult.ALREADY_EXISTS);
                        }
                    }

                    Duration cooldown = config.limits().creationCooldown();
                    if (!isResume && isOnCooldownLocked(request, now, cooldown)) {
                        return CompletableFuture.completedFuture(CreateResult.ON_COOLDOWN);
                    }

                    // Capacity limit (active spaces + pending in-flight creations)
                    int activeCount = spaceRepository.countActive();
                    if (!isResume && (activeCount + pendingCreations) >= config.limits().maxTowns()) {
                        return CompletableFuture.completedFuture(CreateResult.LIMIT_REACHED);
                    }

                    // 5. Discord check: bot must be connected and have required permissions (Discord last)
                    Optional<String> discordUnready = checkDiscordUnready();
                    if (discordUnready.isPresent()) {
                        Instant eventTime = clock.instant();
                        String actor = request.mayorDiscordId() != null ? request.mayorDiscordId() : "mayor";
                        audit(new AuditEvent(
                                eventTime, AuditEvent.Severity.ERROR,
                                actor, "space_create",
                                request.townName(), false,
                                discordUnready));
                        return CompletableFuture.completedFuture(CreateResult.DISCORD_UNAVAILABLE);
                    }

                    // Admission succeeded: reserve slot and track in-flight creation
                    pendingTowns.add(request.townUuid());
                    if (request.mayorUuid() != null) {
                        pendingMayors.add(request.mayorUuid());
                    }
                    pendingCreations++;
                    recordCooldown(request, now);
                    admitted = true;
                }
            } catch (Throwable t) {
                if (admitted) {
                    releaseReservation(request);
                }
                throw t;
            }

            AtomicBoolean released = new AtomicBoolean(false);
            Runnable release = () -> {
                if (released.compareAndSet(false, true)) {
                    releaseReservation(request);
                }
            };

            CompletableFuture<OperationOutcome> submitFuture;
            try {
                submitFuture = discordGateway.submit(new GuildOperation.CreateSpace(request));
            } catch (Throwable t) {
                release.run();
                throw t;
            }

            return submitFuture
                    .whenComplete((outcome, ex) -> release.run())
                    .thenApply(outcome -> {
                        Instant eventTime = clock.instant();
                        String actor = request.mayorDiscordId() != null ? request.mayorDiscordId() : "mayor";
                        if (outcome != null && outcome.succeeded()) {
                            audit(new AuditEvent(
                                    eventTime, AuditEvent.Severity.INFO,
                                    actor, "space_create",
                                    request.townName(), true,
                                    Optional.of("Space created successfully")));
                            return CreateResult.SUCCESS;
                        } else {
                            String reason = (outcome != null && outcome.reason().isPresent())
                                    ? outcome.reason().get()
                                    : "Discord operation failed";
                            audit(new AuditEvent(
                                    eventTime, AuditEvent.Severity.ERROR,
                                    actor, "space_create",
                                    request.townName(), false,
                                    Optional.of(reason)));
                            return CreateResult.FAILED;
                        }
                    })
                    .exceptionally(ex -> {
                        String actor = request.mayorDiscordId() != null ? request.mayorDiscordId() : "mayor";
                        audit(new AuditEvent(
                                clock.instant(), AuditEvent.Severity.ERROR,
                                actor, "space_create",
                                request.townName(), false,
                                Optional.ofNullable(ex.getMessage())));
                        return CreateResult.FAILED;
                    });
        }, executor).thenCompose(f -> f);
    }

    @Override
    public CompletableFuture<Void> rename(UUID townUuid, String newName) {
        if (townUuid == null || newName == null || newName.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Town UUID and new name must not be null or blank"));
        }

        return CompletableFuture.supplyAsync(() -> {
            Optional<TownSpace> opt = spaceRepository.findByTownUuid(townUuid);
            if (opt.isEmpty()) {
                return CompletableFuture.<Void>failedFuture(
                        new IllegalArgumentException("No space registered for town " + townUuid));
            }
            TownSpace space = opt.get();
            String oldName = space.townName();

            return discordGateway.submit(new GuildOperation.RenameSpace(townUuid, oldName, newName))
                    .thenAccept(outcome -> {
                        Instant now = clock.instant();
                        if (outcome != null && outcome.succeeded()) {
                            audit(new AuditEvent(
                                    now, AuditEvent.Severity.INFO,
                                    "plugin", "space_rename", newName, true,
                                    Optional.of("Renamed from " + oldName + " to " + newName)));
                        } else {
                            String reason = (outcome != null && outcome.reason().isPresent())
                                    ? outcome.reason().get()
                                    : "Rename operation failed";
                            audit(new AuditEvent(
                                    now, AuditEvent.Severity.ERROR,
                                    "plugin", "space_rename", newName, false,
                                    Optional.of(reason)));
                            throw new IllegalStateException(reason);
                        }
                    });
        }, executor).thenCompose(f -> f);
    }

    @Override
    public CompletableFuture<Void> archive(UUID townUuid, String reason) {
        if (townUuid == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Town UUID must not be null"));
        }

        return CompletableFuture.supplyAsync(() -> {
            Optional<TownSpace> opt = spaceRepository.findByTownUuid(townUuid);
            if (opt.isEmpty()) {
                return CompletableFuture.<Void>failedFuture(
                        new IllegalArgumentException("No space registered for town " + townUuid));
            }
            TownSpace space = opt.get();
            if (space.state() == SpaceState.ARCHIVED) {
                return CompletableFuture.<Void>completedFuture(null);
            }

            Instant now = clock.instant();
            if (space.archivedAt().isEmpty()) {
                TownSpace beingArchived = new TownSpace(
                        space.townUuid(), space.townName(),
                        space.categoryId(), space.textChannelId(), space.voiceChannelId(), space.roleId(),
                        space.state(), space.createdAt(), Optional.of(now), space.lastActivityAt());
                spaceRepository.save(beingArchived);
            }

            return discordGateway.submit(new GuildOperation.ArchiveSpace(townUuid, space.townName()))
                    .thenAccept(outcome -> {
                        Instant eventTime = clock.instant();
                        if (outcome != null && outcome.succeeded()) {
                            spaceRepository.updateState(townUuid, SpaceState.ARCHIVED);
                            audit(new AuditEvent(
                                    eventTime, AuditEvent.Severity.INFO,
                                    "plugin", "space_archive", space.townName(), true,
                                    Optional.ofNullable(reason)));
                        } else {
                            String errorReason = (outcome != null && outcome.reason().isPresent())
                                    ? outcome.reason().get()
                                    : "Archive operation failed";
                            audit(new AuditEvent(
                                    eventTime, AuditEvent.Severity.ERROR,
                                    "plugin", "space_archive", space.townName(), false,
                                    Optional.of(errorReason)));
                            throw new IllegalStateException(errorReason);
                        }
                    });
        }, executor).thenCompose(f -> f);
    }

    @Override
    public CompletableFuture<Void> restore(SpaceRequest request) {
        if (request == null || request.townUuid() == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Request and town UUID must not be null"));
        }

        return CompletableFuture.supplyAsync(() -> {
            Optional<TownSpace> opt = spaceRepository.findByTownUuid(request.townUuid());
            if (opt.isEmpty()) {
                return CompletableFuture.<Void>failedFuture(
                        new IllegalArgumentException("No space found to restore for town " + request.townUuid()));
            }
            TownSpace space = opt.get();
            if (space.state() == SpaceState.ACTIVE) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            if (space.state() == SpaceState.INCONSISTENT && space.archivedAt().isEmpty()) {
                return CompletableFuture.<Void>failedFuture(
                        new IllegalStateException("Space is an incomplete creation, not an archived space: " + request.townUuid()));
            }

            return discordGateway.submit(new GuildOperation.RestoreSpace(request))
                    .thenAccept(outcome -> {
                        Instant now = clock.instant();
                        String actor = request.mayorDiscordId() != null ? request.mayorDiscordId() : "mayor";
                        if (outcome != null && outcome.succeeded()) {
                            Optional<TownSpace> currentOpt = spaceRepository.findByTownUuid(request.townUuid());
                            if (currentOpt.isPresent()) {
                                TownSpace current = currentOpt.get();
                                spaceRepository.save(new TownSpace(
                                        current.townUuid(), current.townName(),
                                        current.categoryId(), current.textChannelId(), current.voiceChannelId(), current.roleId(),
                                        SpaceState.ACTIVE,
                                        current.createdAt(), Optional.empty(), Optional.of(now)));
                            } else {
                                spaceRepository.updateState(request.townUuid(), SpaceState.ACTIVE);
                            }
                            audit(new AuditEvent(
                                    now, AuditEvent.Severity.INFO,
                                    actor, "space_restore",
                                    request.townName(), true,
                                    Optional.of("Space restored successfully")));
                        } else {
                            String reason = (outcome != null && outcome.reason().isPresent())
                                    ? outcome.reason().get()
                                    : "Restore operation failed";
                            audit(new AuditEvent(
                                    now, AuditEvent.Severity.ERROR,
                                    actor, "space_restore",
                                    request.townName(), false,
                                    Optional.of(reason)));
                            throw new IllegalStateException(reason);
                        }
                    });
        }, executor).thenCompose(f -> f);
    }

    @Override
    public CompletableFuture<Integer> purgeArchived() {
        return CompletableFuture.supplyAsync(() -> {
            List<TownSpace> archived = spaceRepository.findByState(SpaceState.ARCHIVED);
            if (archived.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }

            List<CompletableFuture<Boolean>> futures = new ArrayList<>(archived.size());
            for (TownSpace space : archived) {
                CompletableFuture<Boolean> fut = discordGateway.submit(
                        new GuildOperation.DeleteSpace(space.townUuid(), space.townName()))
                        .thenApply(outcome -> {
                            Instant now = clock.instant();
                            if (outcome != null && outcome.succeeded()) {
                                spaceRepository.delete(space.townUuid());
                                audit(new AuditEvent(
                                        now, AuditEvent.Severity.INFO,
                                        "admin", "space_purge", space.townName(), true,
                                        Optional.of("Archived space purged")));
                                return true;
                            } else {
                                String reason = (outcome != null && outcome.reason().isPresent())
                                        ? outcome.reason().get()
                                        : "Delete operation failed";
                                audit(new AuditEvent(
                                        now, AuditEvent.Severity.ERROR,
                                        "admin", "space_purge", space.townName(), false,
                                        Optional.of(reason)));
                                return false;
                            }
                        })
                        .exceptionally(ex -> false);
                futures.add(fut);
            }

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        int count = 0;
                        for (CompletableFuture<Boolean> f : futures) {
                            if (f.join()) {
                                count++;
                            }
                        }
                        return count;
                    });
        }, executor).thenCompose(f -> f);
    }

    @Override
    public CompletableFuture<Optional<TownSpace>> find(UUID townUuid) {
        if (townUuid == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> spaceRepository.findByTownUuid(townUuid), executor);
    }

    @Override
    public CompletableFuture<List<TownSpace>> findAll() {
        return CompletableFuture.supplyAsync(spaceRepository::findAll, executor);
    }

    private Optional<String> checkDiscordUnready() {
        if (!discordGateway.isAvailable()) {
            return Optional.of("Discord gateway is unavailable");
        }
        try {
            Optional<String> permWarning = discordGateway.verifyPermissions();
            if (permWarning != null && permWarning.isPresent()) {
                return Optional.of("Discord bot lacks required permissions: " + permWarning.get());
            }
        } catch (Exception e) {
            return Optional.of("Failed to verify Discord permissions: " + e.getMessage());
        }
        return Optional.empty();
    }

    private boolean isDiscordReady() {
        return checkDiscordUnready().isEmpty();
    }

    private void releaseReservation(SpaceRequest request) {
        synchronized (admissionLock) {
            pendingTowns.remove(request.townUuid());
            if (request.mayorUuid() != null) {
                pendingMayors.remove(request.mayorUuid());
            }
            pendingCreations = Math.max(0, pendingCreations - 1);
        }
    }

    private boolean isOnCooldownLocked(SpaceRequest request, Instant now, Duration cooldown) {
        if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            return false;
        }
        if (pendingTowns.contains(request.townUuid())) {
            return true;
        }
        Instant lastTown = creationCooldowns.get(request.townUuid());
        if (lastTown != null && Duration.between(lastTown, now).compareTo(cooldown) < 0) {
            return true;
        }
        if (request.mayorUuid() != null) {
            if (pendingMayors.contains(request.mayorUuid())) {
                return true;
            }
            Instant lastMayor = creationCooldowns.get(request.mayorUuid());
            if (lastMayor != null && Duration.between(lastMayor, now).compareTo(cooldown) < 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isOnCooldown(SpaceRequest request, Instant now, Duration cooldown) {
        return isOnCooldownLocked(request, now, cooldown);
    }

    private void recordCooldown(SpaceRequest request, Instant now) {
        creationCooldowns.put(request.townUuid(), now);
        if (request.mayorUuid() != null) {
            creationCooldowns.put(request.mayorUuid(), now);
        }
    }
}
