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
import com.discordtowny.towny.TownyReadException;

import java.time.Clock;
import java.time.Instant;
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
    private final SpaceService spaceService;

    public DefaultSyncService(
            SpaceService spaceService,
            SpaceRepository spaceRepository,
            LinkRepository linkRepository,
            DiscordGateway discordGateway,
            TownyFacade townyFacade,
            PluginConfig config,
            Clock clock,
            Executor executor,
            Consumer<Duration> pauseAction,
            Executor mainThreadExecutor) {
        this.spaceService = spaceService;
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
            Executor executor,
            Consumer<Duration> pauseAction,
            Executor mainThreadExecutor) {
        this(null, spaceRepository, linkRepository, discordGateway, townyFacade, config,
                clock, executor, pauseAction, mainThreadExecutor);
    }

    public DefaultSyncService(
            SpaceRepository spaceRepository,
            LinkRepository linkRepository,
            DiscordGateway discordGateway,
            TownyFacade townyFacade,
            PluginConfig config,
            Clock clock,
            Executor executor) {
        this(null, spaceRepository, linkRepository, discordGateway, townyFacade, config,
                clock, executor, null, null);
    }

    public DefaultSyncService(
            SpaceRepository spaceRepository,
            LinkRepository linkRepository,
            DiscordGateway discordGateway,
            TownyFacade townyFacade,
            PluginConfig config,
            Executor mainThreadExecutor) {
        this(null, spaceRepository, linkRepository, discordGateway, townyFacade, config,
                Clock.systemUTC(), ForkJoinPool.commonPool(), null, mainThreadExecutor);
    }

    public DefaultSyncService(
            SpaceRepository spaceRepository,
            LinkRepository linkRepository,
            DiscordGateway discordGateway,
            TownyFacade townyFacade,
            PluginConfig config) {
        this(null, spaceRepository, linkRepository, discordGateway, townyFacade, config,
                Clock.systemUTC(), ForkJoinPool.commonPool(), null, null);
    }

    public DefaultSyncService(
            SpaceService spaceService,
            SpaceRepository spaceRepository,
            LinkRepository linkRepository,
            DiscordGateway discordGateway,
            TownyFacade townyFacade,
            PluginConfig config) {
        this(spaceService, spaceRepository, linkRepository, discordGateway, townyFacade, config,
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
                    return TownyLookupResult.unavailable();
                }
                try {
                    return TownyLookupResult.ok(townyFacade.town(townUuid));
                } catch (TownyReadException e) {
                    return TownyLookupResult.failedRead(e.getMessage() != null ? e.getMessage() : "Read failed against Towny");
                }
            }).thenComposeAsync(lookup -> {
                if (lookup.isUnavailable()) {
                    return CompletableFuture.completedFuture(
                            new SyncReport(0, 0, 0, 1, 0, List.of("Towny is unavailable")));
                }
                if (lookup.isFailedRead()) {
                    return CompletableFuture.completedFuture(
                            new SyncReport(0, 0, 0, 1, 0, List.of("Towny read failed for town " + townUuid + ": " + lookup.errorMessage())));
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

                        if (!isReportMode && space.state() != SpaceState.ARCHIVED) {
                            return executeArchive(space, "Town " + space.townName() + " deleted in Towny")
                                    .thenApply(success -> {
                                        if (success) {
                                            acc.inconsistenciesRepaired.incrementAndGet();
                                        } else {
                                            acc.problems.add("Failed to archive deleted town space " + space.townName());
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
                return TownyLookupResult.unavailable();
            }
            try {
                return TownyLookupResult.ok(townyFacade.town(space.townUuid()));
            } catch (TownyReadException e) {
                return TownyLookupResult.failedRead(e.getMessage() != null ? e.getMessage() : "Read failed against Towny");
            }
        }).thenComposeAsync(lookup -> {
            if (lookup.isUnavailable()) {
                SyncReportAccumulator acc = new SyncReportAccumulator(1);
                acc.inconsistenciesFound.incrementAndGet();
                acc.problems.add("Towny is unavailable while checking space " + space.townName());
                return CompletableFuture.completedFuture(acc);
            }
            if (lookup.isFailedRead()) {
                SyncReportAccumulator acc = new SyncReportAccumulator(1);
                acc.inconsistenciesFound.incrementAndGet();
                acc.problems.add("Towny read failed for space " + space.townName() + " (" + space.townUuid() + "): " + lookup.errorMessage());
                return CompletableFuture.completedFuture(acc);
            }

            Optional<TownSnapshot> townOpt = lookup.town();
            boolean isReportMode = mode == PluginConfig.Sync.Mode.REPORT;

            if (townOpt.isEmpty()) {
                SyncReportAccumulator acc = new SyncReportAccumulator(1);
                acc.inconsistenciesFound.incrementAndGet();
                acc.problems.add("Town " + space.townName() + " (" + space.townUuid() + ") no longer exists in Towny");

                if (!isReportMode && space.state() != SpaceState.ARCHIVED) {
                    return executeArchive(space, "Town " + space.townName() + " deleted in Towny")
                            .thenApply(success -> {
                                if (success) {
                                    acc.inconsistenciesRepaired.incrementAndGet();
                                } else {
                                    acc.problems.add("Failed to archive deleted town space " + space.townName());
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

        // 1. Ruined town check: ONE decision per space.
        // A ruined town's space must be ARCHIVED. No creation or resident role grants ever.
        if (town.ruined()) {
            if (space.state() == SpaceState.ARCHIVED) {
                return CompletableFuture.completedFuture(acc);
            }
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add("Town " + town.name() + " is ruined but space is " + space.state());
            if (isReportMode) {
                return CompletableFuture.completedFuture(acc);
            }
            return executeArchive(space, "Town " + town.name() + " is ruined")
                    .thenApply(success -> {
                        if (success) {
                            acc.inconsistenciesRepaired.incrementAndGet();
                        } else {
                            acc.problems.add("Failed to archive ruined town space " + town.name());
                        }
                        return acc;
                    });
        }

        // Town is alive and not ruined:
        // 2. Revived town: ARCHIVED space whose town is alive again.
        // Must restore space with history intact (spec section 7).
        if (space.state() == SpaceState.ARCHIVED) {
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add("Town " + town.name() + " is alive but space is ARCHIVED");
            if (isReportMode) {
                return CompletableFuture.completedFuture(acc);
            }
            return executeRestore(space, town)
                    .thenApply(success -> {
                        if (success) {
                            acc.inconsistenciesRepaired.incrementAndGet();
                        } else {
                            acc.problems.add("Failed to restore space " + town.name());
                        }
                        return acc;
                    });
        }

        // 3. Inconsistent state check
        if (space.state() == SpaceState.INCONSISTENT) {
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add("Space for town " + town.name() + " is in INCONSISTENT state");
            if (isReportMode) {
                return CompletableFuture.completedFuture(acc);
            }
            if (space.archivedAt().isPresent()) {
                // Incomplete restoration: resume restoration with history
                return executeRestore(space, town)
                        .thenApply(success -> {
                            if (success) {
                                acc.inconsistenciesRepaired.incrementAndGet();
                            } else {
                                acc.problems.add("Failed to resume restoration of space " + town.name());
                            }
                            return acc;
                        });
            } else {
                // Incomplete creation: resume creation
                SpaceRequest request = buildSpaceRequest(town);
                return discordGateway.submit(new GuildOperation.CreateSpace(request))
                        .thenApply(outcome -> {
                            if (outcome != null && outcome.succeeded()) {
                                acc.inconsistenciesRepaired.incrementAndGet();
                            } else {
                                acc.problems.add("Failed to repair inconsistent space " + town.name()
                                        + ": " + (outcome != null ? outcome.reason().orElse("unknown") : "unknown"));
                            }
                            return acc;
                        });
            }
        }

        // 4. ACTIVE space reconciliation
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // 4a. Rename check
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
                        })
                        .exceptionally(ex -> {
                            acc.problems.add("Failed to rename space from " + space.townName() + " to " + town.name() + ": " + ex.getMessage());
                            return null;
                        }));
            }
        }

        // 4b. Missing channels / role check against Discord (Finding 1)
        boolean wantsText = config.structure().createTextChannel();
        boolean wantsVoice = config.structure().createVoiceChannel();

        List<String> storedIds = new ArrayList<>();
        space.textChannelId().ifPresent(storedIds::add);
        space.voiceChannelId().ifPresent(storedIds::add);
        space.roleId().ifPresent(storedIds::add);

        Set<String> existingDiscordIds = null;
        boolean discordCheckFailed = false;
        try {
            existingDiscordIds = discordGateway.existingResourceIds(storedIds);
        } catch (Exception e) {
            discordCheckFailed = true;
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add("Failed to verify Discord resources for town " + town.name() + ": " + e.getMessage());
        }

        boolean textMissing;
        boolean voiceMissing;
        boolean roleMissing;
        boolean noChannels;

        if (discordCheckFailed || existingDiscordIds == null) {
            // When Discord is unavailable or check failed loudly:
            // The caller must NEVER read a deletion out of an outage!
            textMissing = false;
            voiceMissing = false;
            roleMissing = false;
            noChannels = false;
        } else {
            textMissing = wantsText && (space.textChannelId().isEmpty() || !existingDiscordIds.contains(space.textChannelId().get()));
            voiceMissing = wantsVoice && (space.voiceChannelId().isEmpty() || !existingDiscordIds.contains(space.voiceChannelId().get()));
            roleMissing = space.roleId().isEmpty() || !existingDiscordIds.contains(space.roleId().get());
            boolean hasText = space.textChannelId().isPresent() && existingDiscordIds.contains(space.textChannelId().get());
            boolean hasVoice = space.voiceChannelId().isPresent() && existingDiscordIds.contains(space.voiceChannelId().get());
            noChannels = !hasText && !hasVoice;
        }

        if (textMissing || voiceMissing || roleMissing || noChannels) {
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
                        })
                        .exceptionally(ex -> {
                            acc.problems.add("Failed to repair missing channels/role for " + town.name() + ": " + ex.getMessage());
                            return null;
                        }));
            }
        }

        // 4c. Residents role sync
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
                        }).exceptionally(ex -> {
                            acc.problems.add("Failed to adjust roles for member " + link.discordId() + ": " + ex.getMessage());
                            return null;
                        }));
                    }
                }
            }
        }

        // 4d. Role holders audit (ensure every holder of this managed town role is a justified resident)
        boolean roleExistsInDiscord = space.roleId().isPresent()
                && existingDiscordIds != null
                && existingDiscordIds.contains(space.roleId().get());

        if (roleExistsInDiscord) {
            String townRoleId = space.roleId().get();
            try {
                Set<String> holders = discordGateway.roleHolders(townRoleId);
                if (holders == null || holders.isEmpty()) {
                    if (!town.residentUuids().isEmpty()) {
                        acc.inconsistenciesFound.incrementAndGet();
                        acc.problems.add("Role holders lookup returned empty for town " + town.name()
                                + " (role " + townRoleId + "): member cache may be cold or GUILD_MEMBERS intent disabled");
                    }
                } else {
                    Set<String> justifiedDiscordIds = new LinkedHashSet<>();
                    for (UUID residentUuid : town.residentUuids()) {
                        linkRepository.findByUuid(residentUuid)
                                .ifPresent(link -> justifiedDiscordIds.add(link.discordId()));
                    }

                    for (String holderDiscordId : holders) {
                        if (!justifiedDiscordIds.contains(holderDiscordId)) {
                            acc.inconsistenciesFound.incrementAndGet();
                            acc.problems.add("Member " + holderDiscordId + " holds role for town "
                                    + town.name() + " (" + townRoleId + ") without being a resident");

                            if (isReportMode) {
                                acc.rolesRevoked.incrementAndGet();
                            } else {
                                futures.add(discordGateway.submit(new GuildOperation.ApplyMemberRoles(
                                        holderDiscordId, List.of(), List.of(townRoleId)
                                )).thenAccept(outcome -> {
                                    if (outcome != null && outcome.succeeded()) {
                                        acc.rolesRevoked.incrementAndGet();
                                        acc.inconsistenciesRepaired.incrementAndGet();
                                    } else {
                                        acc.problems.add("Failed to revoke unjustified role " + townRoleId
                                                + " from member " + holderDiscordId + ": "
                                                + (outcome != null ? outcome.reason().orElse("unknown") : "unknown"));
                                    }
                                }).exceptionally(ex -> {
                                    acc.problems.add("Failed to revoke unjustified role " + townRoleId
                                            + " from member " + holderDiscordId + ": " + ex.getMessage());
                                    return null;
                                }));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                acc.inconsistenciesFound.incrementAndGet();
                acc.problems.add("Failed to lookup role holders for town " + town.name()
                        + " (role " + townRoleId + "): " + e.getMessage());
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

    private CompletableFuture<Boolean> executeArchive(TownSpace space, String reason) {
        if (spaceService != null) {
            return spaceService.archive(space.townUuid(), reason)
                    .thenApply(v -> true)
                    .exceptionally(ex -> false);
        }

        Instant now = clock.instant();
        if (space.archivedAt().isEmpty()) {
            TownSpace beingArchived = new TownSpace(
                    space.townUuid(), space.townName(),
                    space.categoryId(), space.textChannelId(), space.voiceChannelId(), space.roleId(),
                    space.state(), space.createdAt(), Optional.of(now), space.lastActivityAt());
            spaceRepository.save(beingArchived);
        }

        return discordGateway.submit(new GuildOperation.ArchiveSpace(space.townUuid(), space.townName()))
                .thenApply(outcome -> {
                    if (outcome != null && outcome.succeeded()) {
                        spaceRepository.updateState(space.townUuid(), SpaceState.ARCHIVED);
                        return true;
                    }
                    return false;
                })
                .exceptionally(ex -> false);
    }

    private CompletableFuture<Boolean> executeRestore(TownSpace space, TownSnapshot town) {
        SpaceRequest request = buildSpaceRequest(town);
        if (spaceService != null) {
            return spaceService.restore(request)
                    .thenApply(v -> true)
                    .exceptionally(ex -> false);
        }

        return discordGateway.submit(new GuildOperation.RestoreSpace(request))
                .thenApply(outcome -> {
                    if (outcome != null && outcome.succeeded()) {
                        Instant now = clock.instant();
                        Optional<TownSpace> currentOpt = spaceRepository.findByTownUuid(space.townUuid());
                        if (currentOpt.isPresent()) {
                            TownSpace current = currentOpt.get();
                            spaceRepository.save(new TownSpace(
                                    current.townUuid(), current.townName(),
                                    current.categoryId(), current.textChannelId(), current.voiceChannelId(), current.roleId(),
                                    SpaceState.ACTIVE,
                                    current.createdAt(), Optional.empty(), Optional.of(now)));
                        } else {
                            spaceRepository.updateState(space.townUuid(), SpaceState.ACTIVE);
                        }
                        return true;
                    }
                    return false;
                })
                .exceptionally(ex -> false);
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

    private enum TownyStatus {
        OK,
        FAILED_READ,
        UNAVAILABLE
    }

    private record TownyLookupResult(
            TownyStatus status,
            Optional<TownSnapshot> town,
            String errorMessage
    ) {
        static TownyLookupResult ok(Optional<TownSnapshot> town) {
            return new TownyLookupResult(TownyStatus.OK, town, null);
        }

        static TownyLookupResult failedRead(String message) {
            return new TownyLookupResult(TownyStatus.FAILED_READ, Optional.empty(), message);
        }

        static TownyLookupResult unavailable() {
            return new TownyLookupResult(TownyStatus.UNAVAILABLE, Optional.empty(), "Towny is unavailable");
        }

        boolean isOk() {
            return status == TownyStatus.OK;
        }

        boolean isUnavailable() {
            return status == TownyStatus.UNAVAILABLE;
        }

        boolean isFailedRead() {
            return status == TownyStatus.FAILED_READ;
        }
    }

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
