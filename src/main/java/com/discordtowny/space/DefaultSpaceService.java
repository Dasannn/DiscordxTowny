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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

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

    private final SpaceRepository spaceRepository;
    private final PluginConfig config;
    private final DiscordGateway discordGateway;
    private final Clock clock;
    private final Executor executor;

    private final ConcurrentHashMap<UUID, Instant> creationCooldowns = new ConcurrentHashMap<>();

    public DefaultSpaceService(
            SpaceRepository spaceRepository,
            PluginConfig config,
            DiscordGateway discordGateway,
            Clock clock,
            Executor executor) {
        this.spaceRepository = Objects.requireNonNull(spaceRepository, "spaceRepository cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.discordGateway = Objects.requireNonNull(discordGateway, "discordGateway cannot be null");
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
    }

    public DefaultSpaceService(
            SpaceRepository spaceRepository,
            PluginConfig config,
            DiscordGateway discordGateway) {
        this(spaceRepository, config, discordGateway, Clock.systemUTC(), ForkJoinPool.commonPool());
    }

    @Override
    public CompletableFuture<CreateResult> create(SpaceRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            if (request == null || request.townUuid() == null || request.townName() == null || request.townName().isBlank()) {
                return CompletableFuture.completedFuture(CreateResult.FAILED);
            }

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

            // 3. In-memory check: creation cooldown
            Duration cooldown = config.limits().creationCooldown();
            Instant now = clock.instant();

            Optional<TownSpace> existingOpt = spaceRepository.findByTownUuid(request.townUuid());
            boolean isResume = existingOpt.isPresent() && existingOpt.get().state() == SpaceState.INCONSISTENT;

            if (!isResume && isOnCooldown(request, now, cooldown)) {
                return CompletableFuture.completedFuture(CreateResult.ON_COOLDOWN);
            }

            // 4. Database checks: does space already exist in active or archived state?
            if (existingOpt.isPresent()) {
                SpaceState state = existingOpt.get().state();
                if (state == SpaceState.ACTIVE || state == SpaceState.ARCHIVED) {
                    return CompletableFuture.completedFuture(CreateResult.ALREADY_EXISTS);
                }
            }

            // Capacity limit (active spaces)
            if (!isResume && spaceRepository.countActive() >= config.limits().maxTowns()) {
                return CompletableFuture.completedFuture(CreateResult.LIMIT_REACHED);
            }

            // 5. Discord check: bot must be connected and have required permissions (Discord last)
            if (!isDiscordReady()) {
                return CompletableFuture.completedFuture(CreateResult.DISCORD_UNAVAILABLE);
            }

            // Record cooldown upon admitting a new creation
            recordCooldown(request, now);

            return discordGateway.submit(new GuildOperation.CreateSpace(request))
                    .thenApply(outcome -> {
                        Instant eventTime = clock.instant();
                        String actor = request.mayorDiscordId() != null ? request.mayorDiscordId() : "mayor";
                        if (outcome != null && outcome.succeeded()) {
                            discordGateway.log(new AuditEvent(
                                    eventTime, AuditEvent.Severity.INFO,
                                    actor, "space_create",
                                    request.townName(), true,
                                    Optional.of("Space created successfully")));
                            return CreateResult.SUCCESS;
                        } else {
                            String reason = (outcome != null && outcome.reason().isPresent())
                                    ? outcome.reason().get()
                                    : "Discord operation failed";
                            discordGateway.log(new AuditEvent(
                                    eventTime, AuditEvent.Severity.ERROR,
                                    actor, "space_create",
                                    request.townName(), false,
                                    Optional.of(reason)));
                            return CreateResult.FAILED;
                        }
                    })
                    .exceptionally(ex -> {
                        String actor = request.mayorDiscordId() != null ? request.mayorDiscordId() : "mayor";
                        discordGateway.log(new AuditEvent(
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
                            discordGateway.log(new AuditEvent(
                                    now, AuditEvent.Severity.INFO,
                                    "plugin", "space_rename", newName, true,
                                    Optional.of("Renamed from " + oldName + " to " + newName)));
                        } else {
                            String reason = (outcome != null && outcome.reason().isPresent())
                                    ? outcome.reason().get()
                                    : "Rename operation failed";
                            discordGateway.log(new AuditEvent(
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

            return discordGateway.submit(new GuildOperation.ArchiveSpace(townUuid, space.townName()))
                    .thenAccept(outcome -> {
                        Instant now = clock.instant();
                        if (outcome != null && outcome.succeeded()) {
                            spaceRepository.updateState(townUuid, SpaceState.ARCHIVED);
                            discordGateway.log(new AuditEvent(
                                    now, AuditEvent.Severity.INFO,
                                    "plugin", "space_archive", space.townName(), true,
                                    Optional.ofNullable(reason)));
                        } else {
                            String errorReason = (outcome != null && outcome.reason().isPresent())
                                    ? outcome.reason().get()
                                    : "Archive operation failed";
                            discordGateway.log(new AuditEvent(
                                    now, AuditEvent.Severity.ERROR,
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

            return discordGateway.submit(new GuildOperation.RestoreSpace(request))
                    .thenAccept(outcome -> {
                        Instant now = clock.instant();
                        String actor = request.mayorDiscordId() != null ? request.mayorDiscordId() : "mayor";
                        if (outcome != null && outcome.succeeded()) {
                            spaceRepository.updateState(request.townUuid(), SpaceState.ACTIVE);
                            discordGateway.log(new AuditEvent(
                                    now, AuditEvent.Severity.INFO,
                                    actor, "space_restore",
                                    request.townName(), true,
                                    Optional.of("Space restored successfully")));
                        } else {
                            String reason = (outcome != null && outcome.reason().isPresent())
                                    ? outcome.reason().get()
                                    : "Restore operation failed";
                            discordGateway.log(new AuditEvent(
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
                                discordGateway.log(new AuditEvent(
                                        now, AuditEvent.Severity.INFO,
                                        "admin", "space_purge", space.townName(), true,
                                        Optional.of("Archived space purged")));
                                return true;
                            } else {
                                String reason = (outcome != null && outcome.reason().isPresent())
                                        ? outcome.reason().get()
                                        : "Delete operation failed";
                                discordGateway.log(new AuditEvent(
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

    private boolean isDiscordReady() {
        if (!discordGateway.isAvailable()) {
            return false;
        }
        try {
            Optional<String> permWarning = discordGateway.verifyPermissions();
            return permWarning == null || permWarning.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isOnCooldown(SpaceRequest request, Instant now, Duration cooldown) {
        if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            return false;
        }
        Instant lastTown = creationCooldowns.get(request.townUuid());
        if (lastTown != null && Duration.between(lastTown, now).compareTo(cooldown) < 0) {
            return true;
        }
        if (request.mayorUuid() != null) {
            Instant lastMayor = creationCooldowns.get(request.mayorUuid());
            if (lastMayor != null && Duration.between(lastMayor, now).compareTo(cooldown) < 0) {
                return true;
            }
        }
        return false;
    }

    private void recordCooldown(SpaceRequest request, Instant now) {
        creationCooldowns.put(request.townUuid(), now);
        if (request.mayorUuid() != null) {
            creationCooldowns.put(request.mayorUuid(), now);
        }
    }
}
