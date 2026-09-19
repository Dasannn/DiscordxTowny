package com.discordtowny.sync;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.discord.GuildOperation;
import com.discordtowny.model.AccountLink;
import com.discordtowny.model.ResidentSnapshot;
import com.discordtowny.model.SpaceRequest;
import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSnapshot;
import com.discordtowny.model.TownSpace;
import com.discordtowny.space.SpaceService;
import com.discordtowny.storage.LinkRepository;
import com.discordtowny.storage.SpaceRepository;
import com.discordtowny.towny.TownyFacade;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Default implementation of {@link SyncService}.
 *
 * <p>Dependency rule: domain logic only; does not import JDA or Bukkit.
 * Database operations and Discord interactions are performed off the server main thread.
 *
 * <p>Towny reads are performed only on the main server thread via the injected
 * {@code mainThreadExecutor}. If not configured, Towny reads execute through the
 * worker executor.
 *
 * <p>Principles:
 * <ul>
 *   <li><b>P1: Towny wins.</b> Discord is a projection; any role not justified by Towny
 *       is revoked, even if manually granted.</li>
 *   <li><b>P4: Main thread is sacred.</b> Only Towny reads happen on the main thread.
 *       Database and Discord operations execute off the main thread.</li>
 *   <li>Only roles managed by the plugin are touched; unmanaged roles are never modified.</li>
 *   <li>When in doubt, revoke rather than grant.</li>
 * </ul>
 */
public final class DefaultSyncService implements SyncService {

    private final SpaceRepository spaceRepository;
    private final LinkRepository linkRepository;
    private final DiscordGateway discordGateway;
    private final TownyFacade townyFacade;
    private final PluginConfig config;
    private final Clock clock;
    private final Executor executor;
    private final Consumer<Duration> pauseAction;
    private final Executor mainThreadExecutor;

    public DefaultSyncService(
            SpaceRepository spaceRepository,
            LinkRepository linkRepository,
            DiscordGateway discordGateway,
            TownyFacade townyFacade,
            PluginConfig config,
            Clock clock,
            Executor executor,
            Consumer<Duration> pauseAction,
            Executor mainThreadExecutor) {
        this.spaceRepository = Objects.requireNonNull(spaceRepository, "spaceRepository cannot be null");
        this.linkRepository = Objects.requireNonNull(linkRepository, "linkRepository cannot be null");
        this.discordGateway = Objects.requireNonNull(discordGateway, "discordGateway cannot be null");
        this.townyFacade = Objects.requireNonNull(townyFacade, "townyFacade cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
        this.pauseAction = pauseAction != null ? pauseAction : DefaultSyncService::defaultPause;
        this.mainThreadExecutor = mainThreadExecutor;
    }

    public DefaultSyncService(
            SpaceRepository spaceRepository,
            LinkRepository linkRepository,
            DiscordGateway discordGateway,
            TownyFacade townyFacade,
            PluginConfig config,
            Clock clock,
            Executor executor) {
        this(spaceRepository, linkRepository, discordGateway, townyFacade, config,
                clock, executor, null, null);
    }

    public DefaultSyncService(
            SpaceRepository spaceRepository,
            LinkRepository linkRepository,
            DiscordGateway discordGateway,
            TownyFacade townyFacade,
            PluginConfig config,
            Executor mainThreadExecutor) {
        this(spaceRepository, linkRepository, discordGateway, townyFacade, config,
                Clock.systemUTC(), ForkJoinPool.commonPool(), null, mainThreadExecutor);
    }

    public DefaultSyncService(
            SpaceRepository spaceRepository,
            LinkRepository linkRepository,
            DiscordGateway discordGateway,
            TownyFacade townyFacade,
            PluginConfig config) {
        this(spaceRepository, linkRepository, discordGateway, townyFacade, config,
                Clock.systemUTC(), ForkJoinPool.commonPool(), null, null);
    }

    public DefaultSyncService(
            SpaceService spaceService,
            SpaceRepository spaceRepository,
            LinkRepository linkRepository,
            DiscordGateway discordGateway,
            TownyFacade townyFacade,
            PluginConfig config) {
        this(spaceRepository, linkRepository, discordGateway, townyFacade, config,
                Clock.systemUTC(), ForkJoinPool.commonPool(), null, null);
    }

    @Override
    public CompletableFuture<Void> syncPlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            Optional<AccountLink> linkOpt = linkRepository.findByUuid(playerUuid);
            if (linkOpt.isEmpty()) {
                return CompletableFuture.<Void>completedFuture(null);
            }

            AccountLink link = linkOpt.get();
            String discordId = link.discordId();

            return callTowny(() -> {
                if (!townyFacade.isAvailable()) {
                    throw new IllegalStateException("Towny is unavailable");
                }
                Optional<ResidentSnapshot> resident = townyFacade.resident(playerUuid);
                Optional<TownSnapshot> town = townyFacade.townOf(playerUuid);
                if (town.isEmpty() && resident.isPresent() && resident.get().townUuid().isPresent()) {
                    town = townyFacade.town(resident.get().townUuid().get());
                }
                return new ResidentTownPair(resident, town);
            }).thenComposeAsync(pair -> {
                boolean isReportMode = config.sync().mode() == PluginConfig.Sync.Mode.REPORT;

                if (pair.resident().isPresent() && !isReportMode) {
                    linkRepository.updateLastKnownName(playerUuid, pair.resident().get().name());
                }

                Set<String> allManagedRoles = getManagedRoleIds();
                PlayerRoleDiff diff = calculatePlayerRoleDiff(
                        playerUuid, pair.resident(), pair.town(), allManagedRoles);

                if (isReportMode) {
                    return CompletableFuture.completedFuture(null);
                }

                if (diff.grantRoles().isEmpty() && diff.revokeRoles().isEmpty()) {
                    return CompletableFuture.completedFuture(null);
                }

                return discordGateway.submit(new GuildOperation.ApplyMemberRoles(
                        discordId, diff.grantRoles(), diff.revokeRoles()
                )).thenAccept(outcome -> {
                    if (outcome != null && !outcome.succeeded()) {
                        String reason = outcome.reason().orElse("Failed to adjust roles on Discord");
                        throw new IllegalStateException(reason);
                    }
                });
            }, executor);
        }, executor).thenCompose(f -> f);
    }

    @Override
    public CompletableFuture<SyncReport> syncTown(UUID townUuid) {
        return syncTown(townUuid, config.sync().mode());
    }

    public CompletableFuture<SyncReport> syncTown(UUID townUuid, PluginConfig.Sync.Mode mode) {
        if (townUuid == null) {
            return CompletableFuture.completedFuture(
                    new SyncReport(0, 0, 0, 0, 0, List.of("Town UUID must not be null")));
        }

        return CompletableFuture.supplyAsync(() -> {
            return callTowny(() -> {
                if (!townyFacade.isAvailable()) {
                    return new TownyLookupResult(false, Optional.empty());
                }
                return new TownyLookupResult(true, townyFacade.town(townUuid));
            }).thenComposeAsync(lookup -> {
                if (!lookup.available()) {
                    return CompletableFuture.completedFuture(
                            new SyncReport(0, 0, 0, 1, 0, List.of("Towny is unavailable")));
                }

                Optional<TownSnapshot> townOpt = lookup.town();
                Optional<TownSpace> spaceOpt = spaceRepository.findByTownUuid(townUuid);
                boolean isReportMode = mode == PluginConfig.Sync.Mode.REPORT;

                if (townOpt.isEmpty()) {
                    if (spaceOpt.isPresent()) {
                        TownSpace space = spaceOpt.get();
                        SyncReportAccumulator acc = new SyncReportAccumulator(1);
                        acc.inconsistenciesFound.incrementAndGet();
                        acc.problems.add("Town " + space.townName() + " (" + townUuid + ") no longer exists in Towny");

                        if (!isReportMode && space.state() == SpaceState.ACTIVE) {
                            return discordGateway.submit(new GuildOperation.ArchiveSpace(space.townUuid(), space.townName()))
                                    .thenApply(outcome -> {
                                        if (outcome != null && outcome.succeeded()) {
                                            spaceRepository.updateState(space.townUuid(), SpaceState.ARCHIVED);
                                            acc.inconsistenciesRepaired.incrementAndGet();
                                        } else {
                                            acc.problems.add("Failed to archive deleted town space " + space.townName()
                                                    + ": " + (outcome != null ? outcome.reason().orElse("unknown") : "unknown"));
                                        }
                                        return acc.toReport();
                                    });
                        }
                        return CompletableFuture.completedFuture(acc.toReport());
                    }
                    return CompletableFuture.completedFuture(
                            new SyncReport(0, 0, 0, 1, 0, List.of("Town " + townUuid + " not found")));
                }

                TownSnapshot town = townOpt.get();
                if (spaceOpt.isEmpty()) {
                    return CompletableFuture.completedFuture(
                            new SyncReport(0, 0, 0, 0, 0, List.of("Town " + town.name() + " has no registered space")));
                }

                TownSpace space = spaceOpt.get();
                return reconcileSingleSpace(space, town, mode).thenApply(SyncReportAccumulator::toReport);
            }, executor);
        }, executor).thenCompose(f -> f);
    }

    @Override
    public CompletableFuture<SyncReport> reconcileAll() {
        return reconcileAll(config.sync().mode());
    }

    public CompletableFuture<SyncReport> reconcileAll(PluginConfig.Sync.Mode mode) {
        return CompletableFuture.supplyAsync(() -> {
            return callTowny(() -> townyFacade.isAvailable()).thenComposeAsync(available -> {
                if (!available) {
                    return CompletableFuture.completedFuture(
                            new SyncReport(0, 0, 0, 1, 0, List.of("Towny is unavailable")));
                }

                List<TownSpace> allSpaces = spaceRepository.findAll();
                int totalSpaces = allSpaces.size();

                if (totalSpaces == 0) {
                    return CompletableFuture.completedFuture(
                            new SyncReport(0, 0, 0, 0, 0, List.of()));
                }

                int batchSize = Math.max(1, config.sync().batchSize());
                Duration batchPause = config.sync().batchPause();
                int totalBatches = (totalSpaces + batchSize - 1) / batchSize;

                CompletableFuture<SyncReportAccumulator> chain = CompletableFuture.completedFuture(
                        new SyncReportAccumulator(totalSpaces));

                for (int b = 0; b < totalBatches; b++) {
                    final int batchIndex = b;
                    int from = batchIndex * batchSize;
                    int to = Math.min(from + batchSize, totalSpaces);
                    List<TownSpace> batch = allSpaces.subList(from, to);

                    chain = chain.thenComposeAsync(totalAcc -> {
                        if (batchIndex > 0) {
                            pauseAction.accept(batchPause);
                        }
                        return processBatch(batch, mode).thenApply(totalAcc::merge);
                    }, executor);
                }

                return chain.thenApply(SyncReportAccumulator::toReport);
            }, executor);
        }, executor).thenCompose(f -> f);
    }

    private CompletableFuture<SyncReportAccumulator> processBatch(
            List<TownSpace> batch,
            PluginConfig.Sync.Mode mode) {
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(new SyncReportAccumulator(0));
        }

        List<CompletableFuture<SyncReportAccumulator>> futures = new ArrayList<>(batch.size());
        for (TownSpace space : batch) {
            futures.add(processSingleSpace(space, mode));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    SyncReportAccumulator batchAcc = new SyncReportAccumulator(0);
                    for (CompletableFuture<SyncReportAccumulator> f : futures) {
                        batchAcc.merge(f.join());
                    }
                    return batchAcc;
                });
    }

    private CompletableFuture<SyncReportAccumulator> processSingleSpace(
            TownSpace space,
            PluginConfig.Sync.Mode mode) {
        return callTowny(() -> {
            if (!townyFacade.isAvailable()) {
                return new TownyLookupResult(false, Optional.empty());
            }
            return new TownyLookupResult(true, townyFacade.town(space.townUuid()));
        }).thenComposeAsync(lookup -> {
            if (!lookup.available()) {
                SyncReportAccumulator acc = new SyncReportAccumulator(1);
                acc.inconsistenciesFound.incrementAndGet();
                acc.problems.add("Towny is unavailable while checking space " + space.townName());
                return CompletableFuture.completedFuture(acc);
            }

            Optional<TownSnapshot> townOpt = lookup.town();
            boolean isReportMode = mode == PluginConfig.Sync.Mode.REPORT;

            if (townOpt.isEmpty()) {
                SyncReportAccumulator acc = new SyncReportAccumulator(1);
                acc.inconsistenciesFound.incrementAndGet();
                acc.problems.add("Town " + space.townName() + " (" + space.townUuid() + ") no longer exists in Towny");

                if (!isReportMode && space.state() == SpaceState.ACTIVE) {
                    return discordGateway.submit(new GuildOperation.ArchiveSpace(space.townUuid(), space.townName()))
                            .thenApply(outcome -> {
                                if (outcome != null && outcome.succeeded()) {
                                    spaceRepository.updateState(space.townUuid(), SpaceState.ARCHIVED);
                                    acc.inconsistenciesRepaired.incrementAndGet();
                                } else {
                                    acc.problems.add("Failed to archive deleted town space " + space.townName()
                                            + ": " + (outcome != null ? outcome.reason().orElse("unknown") : "unknown"));
                                }
                                return acc;
                            });
                }
                return CompletableFuture.completedFuture(acc);
            }

            return reconcileSingleSpace(space, townOpt.get(), mode);
        }, executor);
    }

    private CompletableFuture<SyncReportAccumulator> reconcileSingleSpace(
            TownSpace space,
            TownSnapshot town,
            PluginConfig.Sync.Mode mode) {
        boolean isReportMode = mode == PluginConfig.Sync.Mode.REPORT;
        SyncReportAccumulator acc = new SyncReportAccumulator(1);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // 1. Ruined check
        if (town.ruined() && space.state() == SpaceState.ACTIVE) {
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add("Town " + town.name() + " is ruined but space is ACTIVE");
            if (!isReportMode) {
                futures.add(discordGateway.submit(new GuildOperation.ArchiveSpace(space.townUuid(), space.townName()))
                        .thenAccept(outcome -> {
                            if (outcome != null && outcome.succeeded()) {
                                spaceRepository.updateState(space.townUuid(), SpaceState.ARCHIVED);
                                acc.inconsistenciesRepaired.incrementAndGet();
                            } else {
                                acc.problems.add("Failed to archive ruined town space " + town.name()
                                        + ": " + (outcome != null ? outcome.reason().orElse("unknown") : "unknown"));
                            }
                        }));
            }
        }

        // 2. Rename check
        if (!town.name().equalsIgnoreCase(space.townName())) {
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add("Town renamed: " + space.townName() + " -> " + town.name());
            if (!isReportMode) {
                futures.add(discordGateway.submit(new GuildOperation.RenameSpace(space.townUuid(), space.townName(), town.name()))
                        .thenAccept(outcome -> {
                            if (outcome != null && outcome.succeeded()) {
                                acc.inconsistenciesRepaired.incrementAndGet();
                            } else {
                                acc.problems.add("Failed to rename space from " + space.townName() + " to " + town.name()
                                        + ": " + (outcome != null ? outcome.reason().orElse("unknown") : "unknown"));
                            }
                        }));
            }
        }

        // 3. Inconsistent state check
        if (space.state() == SpaceState.INCONSISTENT) {
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add("Space for town " + town.name() + " is in INCONSISTENT state");
            if (!isReportMode) {
                if (space.archivedAt().isPresent()) {
                    futures.add(discordGateway.submit(new GuildOperation.ArchiveSpace(space.townUuid(), space.townName()))
                            .thenAccept(outcome -> {
                                if (outcome != null && outcome.succeeded()) {
                                    spaceRepository.updateState(space.townUuid(), SpaceState.ARCHIVED);
                                    acc.inconsistenciesRepaired.incrementAndGet();
                                } else {
                                    acc.problems.add("Failed to repair archived space " + town.name()
                                            + ": " + (outcome != null ? outcome.reason().orElse("unknown") : "unknown"));
                                }
                            }));
                } else {
                    SpaceRequest request = buildSpaceRequest(town);
                    futures.add(discordGateway.submit(new GuildOperation.CreateSpace(request))
                            .thenAccept(outcome -> {
                                if (outcome != null && outcome.succeeded()) {
                                    acc.inconsistenciesRepaired.incrementAndGet();
                                } else {
                                    acc.problems.add("Failed to repair inconsistent space " + town.name()
                                            + ": " + (outcome != null ? outcome.reason().orElse("unknown") : "unknown"));
                                }
                            }));
                }
            }
        }

        // 4. Missing channels / role check
        boolean wantsText = config.structure().createTextChannel();
        boolean wantsVoice = config.structure().createVoiceChannel();
        boolean textMissing = wantsText && space.textChannelId().isEmpty();
        boolean voiceMissing = wantsVoice && space.voiceChannelId().isEmpty();
        boolean roleMissing = space.roleId().isEmpty();
        boolean noChannels = space.textChannelId().isEmpty() && space.voiceChannelId().isEmpty();

        if (space.state() == SpaceState.ACTIVE && (textMissing || voiceMissing || roleMissing || noChannels)) {
            acc.inconsistenciesFound.incrementAndGet();
            if (noChannels) {
                acc.problems.add("Space for town " + town.name() + " has no channels registered");
            } else if (textMissing || voiceMissing) {
                acc.problems.add("Space for town " + town.name() + " is missing required channels");
            } else {
                acc.problems.add("Space for town " + town.name() + " has deleted/missing role");
            }
            if (!isReportMode) {
                SpaceRequest request = buildSpaceRequest(town);
                futures.add(discordGateway.submit(new GuildOperation.CreateSpace(request))
                        .thenAccept(outcome -> {
                            if (outcome != null && outcome.succeeded()) {
                                acc.inconsistenciesRepaired.incrementAndGet();
                            } else {
                                acc.problems.add("Failed to repair missing channels/role for " + town.name()
                                        + ": " + (outcome != null ? outcome.reason().orElse("unknown") : "unknown"));
                            }
                        }));
            }
        }

        // 5. Residents role sync
        if (space.state() == SpaceState.ACTIVE && !town.ruined()) {
            Set<String> allManagedRoles = getManagedRoleIds();
            for (UUID residentUuid : town.residentUuids()) {
                Optional<AccountLink> linkOpt = linkRepository.findByUuid(residentUuid);
                if (linkOpt.isPresent()) {
                    AccountLink link = linkOpt.get();
                    PlayerRoleDiff diff = calculatePlayerRoleDiff(
                            residentUuid, Optional.empty(), Optional.of(town), allManagedRoles);

                    if (!isReportMode) {
                        if (!diff.grantRoles().isEmpty() || !diff.revokeRoles().isEmpty()) {
                            futures.add(discordGateway.submit(new GuildOperation.ApplyMemberRoles(
                                    link.discordId(), diff.grantRoles(), diff.revokeRoles()
                            )).thenAccept(outcome -> {
                                if (outcome != null && outcome.succeeded()) {
                                    acc.rolesGranted.addAndGet(diff.grantRoles().size());
                                    acc.rolesRevoked.addAndGet(diff.revokeRoles().size());
                                } else {
                                    acc.problems.add("Failed to adjust roles for member " + link.discordId()
                                            + ": " + (outcome != null ? outcome.reason().orElse("unknown") : "unknown"));
                                }
                            }));
                        }
                    }
                }
            }
        }

        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(acc);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> acc);
    }

    private <T> CompletableFuture<T> callTowny(Supplier<T> supplier) {
        if (mainThreadExecutor != null) {
            return CompletableFuture.supplyAsync(supplier, mainThreadExecutor);
        }
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    private Set<String> getManagedRoleIds() {
        Set<String> roles = new LinkedHashSet<>();
        for (TownSpace s : spaceRepository.findAll()) {
            s.roleId().ifPresent(roles::add);
        }
        try {
            Optional<String> mayorId = discordGateway.mayorRoleId();
            if (mayorId != null) {
                mayorId.ifPresent(roles::add);
            }
        } catch (Exception ignored) {
        }
        return roles;
    }

    private PlayerRoleDiff calculatePlayerRoleDiff(
            UUID playerUuid,
            Optional<ResidentSnapshot> residentOpt,
            Optional<TownSnapshot> townOpt,
            Set<String> allManagedRoles) {

        Set<String> shouldHave = new LinkedHashSet<>();

        if (townOpt.isPresent()) {
            TownSnapshot town = townOpt.get();
            if (!town.ruined()) {
                Optional<TownSpace> spaceOpt = spaceRepository.findByTownUuid(town.uuid());
                if (spaceOpt.isPresent()) {
                    TownSpace space = spaceOpt.get();
                    if (space.state() == SpaceState.ACTIVE || space.state() == SpaceState.INCONSISTENT) {
                        space.roleId().ifPresent(shouldHave::add);
                    }
                }

                boolean isMayor = town.isMayor(playerUuid)
                        || residentOpt.map(ResidentSnapshot::mayor).orElse(false);
                if (isMayor) {
                    try {
                        Optional<String> mayorId = discordGateway.mayorRoleId();
                        if (mayorId != null && mayorId.isPresent()) {
                            shouldHave.add(mayorId.get());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        Set<String> shouldRevoke = new LinkedHashSet<>(allManagedRoles);
        shouldRevoke.removeAll(shouldHave);

        return new PlayerRoleDiff(new ArrayList<>(shouldHave), new ArrayList<>(shouldRevoke));
    }

    private SpaceRequest buildSpaceRequest(TownSnapshot town) {
        List<String> linkedResidents = new ArrayList<>();
        String mayorDiscordId = "";

        for (UUID residentUuid : town.residentUuids()) {
            Optional<AccountLink> link = linkRepository.findByUuid(residentUuid);
            if (link.isPresent()) {
                linkedResidents.add(link.get().discordId());
                if (residentUuid.equals(town.mayorUuid())) {
                    mayorDiscordId = link.get().discordId();
                }
            }
        }

        if (mayorDiscordId.isBlank()) {
            Optional<AccountLink> mayorLink = linkRepository.findByUuid(town.mayorUuid());
            mayorDiscordId = mayorLink.map(AccountLink::discordId).orElse("");
        }

        return new SpaceRequest(
                town.uuid(),
                town.name(),
                town.mayorUuid(),
                linkedResidents,
                mayorDiscordId,
                town.residentCount()
        );
    }

    private static void defaultPause(Duration duration) {
        if (duration != null && !duration.isZero() && !duration.isNegative()) {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record ResidentTownPair(
            Optional<ResidentSnapshot> resident,
            Optional<TownSnapshot> town
    ) {}

    private record TownyLookupResult(
            boolean available,
            Optional<TownSnapshot> town
    ) {}

    private record PlayerRoleDiff(
            List<String> grantRoles,
            List<String> revokeRoles
    ) {}

    private static final class SyncReportAccumulator {
        final int spacesChecked;
        final AtomicInteger rolesGranted = new AtomicInteger(0);
        final AtomicInteger rolesRevoked = new AtomicInteger(0);
        final AtomicInteger inconsistenciesFound = new AtomicInteger(0);
        final AtomicInteger inconsistenciesRepaired = new AtomicInteger(0);
        final List<String> problems = Collections.synchronizedList(new ArrayList<>());

        SyncReportAccumulator(int spacesChecked) {
            this.spacesChecked = spacesChecked;
        }

        SyncReportAccumulator merge(SyncReportAccumulator other) {
            this.rolesGranted.addAndGet(other.rolesGranted.get());
            this.rolesRevoked.addAndGet(other.rolesRevoked.get());
            this.inconsistenciesFound.addAndGet(other.inconsistenciesFound.get());
            this.inconsistenciesRepaired.addAndGet(other.inconsistenciesRepaired.get());
            this.problems.addAll(other.problems);
            return this;
        }

        SyncReport toReport() {
            return new SyncReport(
                    spacesChecked,
                    rolesGranted.get(),
                    rolesRevoked.get(),
                    inconsistenciesFound.get(),
                    inconsistenciesRepaired.get(),
                    List.copyOf(problems)
            );
        }
    }
}
