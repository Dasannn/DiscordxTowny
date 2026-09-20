package com.discordtowny.sync;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.discord.GuildOperation;
import com.discordtowny.discord.OperationOutcome;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
                Set<String> memberCurrentRoles = findMemberRoles(discordId, allManagedRoles);
                PlayerRoleDiff diff = calculatePlayerRoleDiff(
                        playerUuid, pair.resident(), pair.town(), allManagedRoles, memberCurrentRoles);

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
                    new SyncReport(0, 0, 0, 0, 0, List.of(new SyncReport.Problem("sync.problem-town-uuid-null")), mode, 0, 0));
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
                            new SyncReport(0, 0, 0, 1, 0, List.of(new SyncReport.Problem("sync.problem-towny-unavailable")), mode, 0, 0));
                }
                if (lookup.isFailedRead()) {
                    return CompletableFuture.completedFuture(
                            new SyncReport(0, 0, 0, 1, 0, List.of(new SyncReport.Problem(
                                    "sync.problem-towny-read-failed-town",
                                    Map.of("town", safeStr(townUuid), "error", safeError(lookup.errorMessage())))), mode, 0, 0));
                }

                Optional<TownSnapshot> townOpt = lookup.town();
                Optional<TownSpace> spaceOpt = spaceRepository.findByTownUuid(townUuid);
                boolean isReportMode = mode == PluginConfig.Sync.Mode.REPORT;

                if (townOpt.isEmpty()) {
                    if (spaceOpt.isPresent()) {
                        TownSpace space = spaceOpt.get();
                        SyncReportAccumulator acc = new SyncReportAccumulator(1, mode);
                        acc.inconsistenciesFound.incrementAndGet();
                        acc.problems.add(new SyncReport.Problem(
                                "sync.problem-town-no-longer-exists",
                                Map.of("town", space.townName(), "uuid", safeStr(townUuid))));

                        if (!isReportMode && space.state() != SpaceState.ARCHIVED) {
                            return executeArchive(space, "Town " + space.townName() + " deleted in Towny")
                                    .thenApply(success -> {
                                        if (success) {
                                            acc.inconsistenciesRepaired.incrementAndGet();
                                        } else {
                                            acc.problems.add(new SyncReport.Problem(
                                                    "sync.problem-archive-deleted-failed",
                                                    Map.of("town", space.townName())));
                                        }
                                        return acc.toReport();
                                    });
                        }
                        return CompletableFuture.completedFuture(acc.toReport());
                    }
                    return CompletableFuture.completedFuture(
                            new SyncReport(0, 0, 0, 1, 0, List.of(new SyncReport.Problem(
                                    "sync.problem-town-not-found",
                                    Map.of("town", safeStr(townUuid)))), mode, 0, 0));
                }

                TownSnapshot town = townOpt.get();
                if (spaceOpt.isEmpty()) {
                    return CompletableFuture.completedFuture(
                            new SyncReport(0, 0, 0, 0, 0, List.of(new SyncReport.Problem(
                                    "sync.problem-town-no-space",
                                    Map.of("town", town.name()))), mode, 0, 0));
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
            return callTowny(() -> {
                if (!townyFacade.isAvailable()) {
                    return false;
                }
                return true;
            }).handle((available, ex) -> {
                if (ex != null || !Boolean.TRUE.equals(available)) {
                    return false;
                }
                return true;
            }).thenComposeAsync(available -> {
                if (!available) {
                    return CompletableFuture.completedFuture(
                            new SyncReport(0, 0, 0, 1, 0, List.of(new SyncReport.Problem("sync.problem-towny-unavailable")), mode, 0, 0));
                }

                List<TownSpace> allSpaces = spaceRepository.findAll();
                int totalSpaces = allSpaces.size();

                if (totalSpaces == 0) {
                    return auditMayorRole(mode).thenApply(SyncReportAccumulator::toReport);
                }

                int batchSize = Math.max(1, config.sync().batchSize());
                Duration batchPause = config.sync().batchPause();
                int totalBatches = (totalSpaces + batchSize - 1) / batchSize;

                CompletableFuture<SyncReportAccumulator> chain = CompletableFuture.completedFuture(
                        new SyncReportAccumulator(totalSpaces, mode));

                for (int b = 0; b < totalBatches; b++) {
                    final int batchIndex = b;
                    int from = batchIndex * batchSize;
                    int to = Math.min(from + batchSize, totalSpaces);
                    List<TownSpace> batch = allSpaces.subList(from, to);

                    chain = chain.thenComposeAsync(totalAcc -> {
                        if (batchIndex > 0) {
                            try {
                                pauseAction.accept(batchPause);
                            } catch (Exception e) {
                                totalAcc.problems.add(new SyncReport.Problem(
                                        "sync.problem-batch-pause-failed",
                                        Map.of("error", safeError(e))));
                            }
                        }
                        return processBatch(batch, mode).handle((batchAcc, ex) -> {
                            if (ex != null) {
                                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                                totalAcc.inconsistenciesFound.incrementAndGet();
                                totalAcc.problems.add(new SyncReport.Problem(
                                        "sync.problem-batch-failed",
                                        Map.of("batch", String.valueOf(batchIndex), "error", safeError(cause))));
                            } else if (batchAcc != null) {
                                totalAcc.merge(batchAcc);
                            }
                            return totalAcc;
                        });
                    }, executor);
                }

                return chain.thenComposeAsync(totalAcc ->
                        auditMayorRole(mode).handle((mayorAcc, ex) -> {
                            if (ex != null) {
                                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                                totalAcc.inconsistenciesFound.incrementAndGet();
                                totalAcc.problems.add(new SyncReport.Problem(
                                        "sync.problem-mayor-audit-failed",
                                        Map.of("error", safeError(cause))));
                            } else if (mayorAcc != null) {
                                totalAcc.merge(mayorAcc);
                            }
                            return totalAcc;
                        })
                , executor).thenApply(SyncReportAccumulator::toReport);
            }, executor);
        }, executor).thenCompose(f -> f);
    }

    private CompletableFuture<SyncReportAccumulator> processBatch(
            List<TownSpace> batch,
            PluginConfig.Sync.Mode mode) {
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(new SyncReportAccumulator(0, mode));
        }

        List<CompletableFuture<SyncReportAccumulator>> futures = new ArrayList<>(batch.size());
        for (TownSpace space : batch) {
            futures.add(processSingleSpace(space, mode).handle((acc, ex) -> {
                if (ex != null) {
                    SyncReportAccumulator errAcc = new SyncReportAccumulator(1, mode);
                    errAcc.inconsistenciesFound.incrementAndGet();
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    errAcc.problems.add(new SyncReport.Problem(
                            "sync.problem-reconcile-space-failed",
                            Map.of("town", space.townName(), "uuid", safeStr(space.townUuid()), "error", safeError(cause))));
                    return errAcc;
                }
                return acc;
            }));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    SyncReportAccumulator batchAcc = new SyncReportAccumulator(0, mode);
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
        }).handle((lookup, ex) -> {
            if (ex != null) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                return TownyLookupResult.failedRead("Towny call threw exception: " + cause.getMessage());
            }
            return lookup;
        }).thenComposeAsync(lookup -> {
            if (lookup.isUnavailable()) {
                SyncReportAccumulator acc = new SyncReportAccumulator(1, mode);
                acc.inconsistenciesFound.incrementAndGet();
                acc.problems.add(new SyncReport.Problem(
                        "sync.problem-towny-unavailable-space",
                        Map.of("town", space.townName())));
                return CompletableFuture.completedFuture(acc);
            }
            if (lookup.isFailedRead()) {
                SyncReportAccumulator acc = new SyncReportAccumulator(1, mode);
                acc.inconsistenciesFound.incrementAndGet();
                acc.problems.add(new SyncReport.Problem(
                        "sync.problem-towny-read-failed-space",
                        Map.of("town", space.townName(), "uuid", safeStr(space.townUuid()), "error", safeError(lookup.errorMessage()))));
                return CompletableFuture.completedFuture(acc);
            }

            Optional<TownSnapshot> townOpt = lookup.town();
            boolean isReportMode = mode == PluginConfig.Sync.Mode.REPORT;

            if (townOpt.isEmpty()) {
                SyncReportAccumulator acc = new SyncReportAccumulator(1, mode);
                acc.inconsistenciesFound.incrementAndGet();
                acc.problems.add(new SyncReport.Problem(
                        "sync.problem-town-no-longer-exists",
                        Map.of("town", space.townName(), "uuid", safeStr(space.townUuid()))));

                if (!isReportMode && space.state() != SpaceState.ARCHIVED) {
                    return executeArchive(space, "Town " + space.townName() + " deleted in Towny")
                            .thenApply(success -> {
                                if (success) {
                                    acc.inconsistenciesRepaired.incrementAndGet();
                                } else {
                                    acc.problems.add(new SyncReport.Problem(
                                            "sync.problem-archive-deleted-failed",
                                            Map.of("town", space.townName())));
                                }
                                return acc;
                            });
                }
                return CompletableFuture.completedFuture(acc);
            }

            return reconcileSingleSpace(space, townOpt.get(), mode);
        }, executor).exceptionally(ex -> {
            SyncReportAccumulator acc = new SyncReportAccumulator(1, mode);
            acc.inconsistenciesFound.incrementAndGet();
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            acc.problems.add(new SyncReport.Problem(
                    "sync.problem-reconcile-space-failed",
                    Map.of("town", space.townName(), "uuid", safeStr(space.townUuid()), "error", safeError(cause))));
            return acc;
        });
    }

    private CompletableFuture<SyncReportAccumulator> reconcileSingleSpace(
            TownSpace space,
            TownSnapshot town,
            PluginConfig.Sync.Mode mode) {
        boolean isReportMode = mode == PluginConfig.Sync.Mode.REPORT;
        SyncReportAccumulator acc = new SyncReportAccumulator(1, mode);

        // 1. Ruined town check: ONE decision per space.
        // A ruined town's space must be ARCHIVED. No creation or resident role grants ever.
        if (town.ruined()) {
            if (space.state() == SpaceState.ARCHIVED) {
                return CompletableFuture.completedFuture(acc);
            }
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add(new SyncReport.Problem(
                    "sync.problem-town-ruined-space-state",
                    Map.of("town", town.name(), "state", safeState(space.state()))));
            if (isReportMode) {
                return CompletableFuture.completedFuture(acc);
            }
            return executeArchive(space, "Town " + town.name() + " is ruined")
                    .thenApply(success -> {
                        if (success) {
                            acc.inconsistenciesRepaired.incrementAndGet();
                        } else {
                            acc.problems.add(new SyncReport.Problem(
                                    "sync.problem-archive-ruined-failed",
                                    Map.of("town", town.name())));
                        }
                        return acc;
                    });
        }

        // Town is alive and not ruined:
        // 2. Revived town: ARCHIVED space whose town is alive again.
        // Must restore space with history intact (spec section 7).
        if (space.state() == SpaceState.ARCHIVED) {
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add(new SyncReport.Problem(
                    "sync.problem-town-alive-space-archived",
                    Map.of("town", town.name())));
            if (isReportMode) {
                return CompletableFuture.completedFuture(acc);
            }
            return executeRestore(space, town)
                    .thenApply(success -> {
                        if (success) {
                            acc.inconsistenciesRepaired.incrementAndGet();
                        } else {
                            acc.problems.add(new SyncReport.Problem(
                                    "sync.problem-restore-space-failed",
                                    Map.of("town", town.name())));
                        }
                        return acc;
                    });
        }

        // 3. Inconsistent state check
        if (space.state() == SpaceState.INCONSISTENT) {
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add(new SyncReport.Problem(
                    "sync.problem-space-inconsistent",
                    Map.of("town", town.name())));
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
                                acc.problems.add(new SyncReport.Problem(
                                        "sync.problem-resume-restoration-failed",
                                        Map.of("town", town.name())));
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
                                acc.problems.add(new SyncReport.Problem(
                                        "sync.problem-repair-inconsistent-failed",
                                        Map.of("town", town.name(), "reason", safeReason(outcome))));
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
            acc.problems.add(new SyncReport.Problem(
                    "sync.problem-town-renamed",
                    Map.of("old", space.townName(), "new", town.name())));
            if (!isReportMode) {
                futures.add(discordGateway.submit(new GuildOperation.RenameSpace(space.townUuid(), space.townName(), town.name()))
                        .thenAccept(outcome -> {
                            if (outcome != null && outcome.succeeded()) {
                                acc.inconsistenciesRepaired.incrementAndGet();
                            } else {
                                acc.problems.add(new SyncReport.Problem(
                                        "sync.problem-rename-failed",
                                        Map.of("old", space.townName(), "new", town.name(), "reason", safeReason(outcome))));
                            }
                        })
                        .exceptionally(ex -> {
                            acc.problems.add(new SyncReport.Problem(
                                    "sync.problem-rename-failed",
                                    Map.of("old", space.townName(), "new", town.name(), "reason", safeError(ex))));
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
            acc.problems.add(new SyncReport.Problem(
                    "sync.problem-verify-resources-failed",
                    Map.of("town", town.name(), "error", safeError(e))));
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
                acc.problems.add(new SyncReport.Problem(
                        "sync.problem-no-channels-registered",
                        Map.of("town", town.name())));
            } else if (textMissing || voiceMissing) {
                acc.problems.add(new SyncReport.Problem(
                        "sync.problem-missing-channels",
                        Map.of("town", town.name())));
            } else {
                acc.problems.add(new SyncReport.Problem(
                        "sync.problem-missing-role",
                        Map.of("town", town.name())));
            }
            if (!isReportMode) {
                SpaceRequest request = buildSpaceRequest(town);
                futures.add(discordGateway.submit(new GuildOperation.CreateSpace(request))
                        .thenAccept(outcome -> {
                            if (outcome != null && outcome.succeeded()) {
                                acc.inconsistenciesRepaired.incrementAndGet();
                            } else {
                                acc.problems.add(new SyncReport.Problem(
                                        "sync.problem-repair-channels-role-failed",
                                        Map.of("town", town.name(), "reason", safeReason(outcome))));
                            }
                        })
                        .exceptionally(ex -> {
                            acc.problems.add(new SyncReport.Problem(
                                    "sync.problem-repair-channels-role-failed",
                                    Map.of("town", town.name(), "reason", safeError(ex))));
                            return null;
                        }));
            }
        }

        // 4c. Residents role sync
        Set<String> allManagedRoles = getManagedRoleIds();
        Map<String, Set<String>> roleHoldersMap = fetchRoleHolders(allManagedRoles);

        for (UUID residentUuid : town.residentUuids()) {
            Optional<AccountLink> linkOpt = linkRepository.findByUuid(residentUuid);
            if (linkOpt.isPresent()) {
                AccountLink link = linkOpt.get();
                String discordId = link.discordId();

                Set<String> memberCurrentRoles = findMemberRoles(discordId, roleHoldersMap);
                PlayerRoleDiff diff = calculatePlayerRoleDiff(
                        residentUuid, Optional.empty(), Optional.of(town), allManagedRoles, memberCurrentRoles);

                if (isReportMode) {
                    if (!diff.grantRoles().isEmpty()) {
                        acc.inconsistenciesFound.addAndGet(diff.grantRoles().size());
                        acc.proposedGrants.addAndGet(diff.grantRoles().size());
                        acc.problems.add(new SyncReport.Problem(
                                "sync.problem-resident-missing-roles",
                                Map.of("discord", discordId, "town", town.name(), "roles", diff.grantRoles().toString())));
                    }
                    if (!diff.revokeRoles().isEmpty()) {
                        acc.inconsistenciesFound.addAndGet(diff.revokeRoles().size());
                        acc.proposedRevocations.addAndGet(diff.revokeRoles().size());
                        acc.problems.add(new SyncReport.Problem(
                                "sync.problem-resident-unjustified-roles",
                                Map.of("discord", discordId, "roles", diff.revokeRoles().toString())));
                    }
                } else {
                    if (!diff.grantRoles().isEmpty() || !diff.revokeRoles().isEmpty()) {
                        futures.add(discordGateway.submit(new GuildOperation.ApplyMemberRoles(
                                discordId, diff.grantRoles(), diff.revokeRoles()
                        )).thenAccept(outcome -> {
                            if (outcome != null && outcome.succeeded()) {
                                acc.rolesGranted.addAndGet(diff.grantRoles().size());
                                acc.rolesRevoked.addAndGet(diff.revokeRoles().size());
                                acc.inconsistenciesRepaired.addAndGet(diff.grantRoles().size() + diff.revokeRoles().size());
                            } else {
                                acc.problems.add(new SyncReport.Problem(
                                        "sync.problem-adjust-roles-failed",
                                        Map.of("discord", discordId, "reason", safeReason(outcome))));
                            }
                        }).exceptionally(ex -> {
                            acc.problems.add(new SyncReport.Problem(
                                    "sync.problem-adjust-roles-failed",
                                    Map.of("discord", discordId, "reason", safeError(ex))));
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
                Set<String> holders = roleHoldersMap.get(townRoleId);
                if (holders == null) {
                    holders = discordGateway.roleHolders(townRoleId);
                }
                if (holders == null || holders.isEmpty()) {
                    if (!town.residentUuids().isEmpty()) {
                        acc.inconsistenciesFound.incrementAndGet();
                        acc.problems.add(new SyncReport.Problem(
                                "sync.problem-role-holders-empty-town",
                                Map.of("town", town.name(), "role", townRoleId)));
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
                            acc.problems.add(new SyncReport.Problem(
                                    "sync.problem-member-unjustified-town-role",
                                    Map.of("discord", holderDiscordId, "town", town.name(), "role", townRoleId)));

                            if (isReportMode) {
                                acc.proposedRevocations.incrementAndGet();
                            } else {
                                futures.add(discordGateway.submit(new GuildOperation.ApplyMemberRoles(
                                        holderDiscordId, List.of(), List.of(townRoleId)
                                )).thenAccept(outcome -> {
                                    if (outcome != null && outcome.succeeded()) {
                                        acc.rolesRevoked.incrementAndGet();
                                        acc.inconsistenciesRepaired.incrementAndGet();
                                    } else {
                                        acc.problems.add(new SyncReport.Problem(
                                                "sync.problem-revoke-role-failed",
                                                Map.of("role", townRoleId, "discord", holderDiscordId, "reason", safeReason(outcome))));
                                    }
                                }).exceptionally(ex -> {
                                    acc.problems.add(new SyncReport.Problem(
                                            "sync.problem-revoke-role-failed",
                                            Map.of("role", townRoleId, "discord", holderDiscordId, "reason", safeError(ex))));
                                    return null;
                                }));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                acc.inconsistenciesFound.incrementAndGet();
                acc.problems.add(new SyncReport.Problem(
                        "sync.problem-lookup-role-holders-failed",
                        Map.of("town", town.name(), "role", townRoleId, "error", safeError(e))));
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
            Set<String> allManagedRoles,
            Set<String> memberCurrentRoles) {

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

        Set<String> toGrant = new LinkedHashSet<>(shouldHave);
        toGrant.removeAll(memberCurrentRoles);

        Set<String> toRevoke = new LinkedHashSet<>(memberCurrentRoles);
        toRevoke.removeAll(shouldHave);

        return new PlayerRoleDiff(new ArrayList<>(toGrant), new ArrayList<>(toRevoke));
    }

    private Map<String, Set<String>> fetchRoleHolders(Set<String> roleIds) {
        Map<String, Set<String>> map = new HashMap<>();
        for (String roleId : roleIds) {
            try {
                Set<String> holders = discordGateway.roleHolders(roleId);
                if (holders != null) {
                    map.put(roleId, holders);
                }
            } catch (Exception ignored) {
            }
        }
        return map;
    }

    private Set<String> findMemberRoles(String discordId, Map<String, Set<String>> roleHoldersMap) {
        Set<String> held = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : roleHoldersMap.entrySet()) {
            if (entry.getValue() != null && entry.getValue().contains(discordId)) {
                held.add(entry.getKey());
            }
        }
        return held;
    }

    private Set<String> findMemberRoles(String discordId, Set<String> managedRoleIds) {
        Set<String> held = new LinkedHashSet<>();
        for (String roleId : managedRoleIds) {
            try {
                Set<String> holders = discordGateway.roleHolders(roleId);
                if (holders != null && holders.contains(discordId)) {
                    held.add(roleId);
                }
            } catch (Exception ignored) {
            }
        }
        return held;
    }

    private CompletableFuture<SyncReportAccumulator> auditMayorRole(PluginConfig.Sync.Mode mode) {
        boolean isReportMode = mode == PluginConfig.Sync.Mode.REPORT;
        SyncReportAccumulator acc = new SyncReportAccumulator(0, mode);

        Optional<String> mayorRoleIdOpt;
        try {
            mayorRoleIdOpt = discordGateway.mayorRoleId();
        } catch (Exception e) {
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add(new SyncReport.Problem(
                    "sync.problem-mayor-role-id-failed",
                    Map.of("error", safeError(e))));
            return CompletableFuture.completedFuture(acc);
        }

        if (mayorRoleIdOpt == null || mayorRoleIdOpt.isEmpty()) {
            return CompletableFuture.completedFuture(acc);
        }

        String mayorRoleId = mayorRoleIdOpt.get();
        Set<String> holders;
        try {
            holders = discordGateway.roleHolders(mayorRoleId);
        } catch (Exception e) {
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add(new SyncReport.Problem(
                    "sync.problem-lookup-mayor-holders-failed",
                    Map.of("role", mayorRoleId, "error", safeError(e))));
            return CompletableFuture.completedFuture(acc);
        }

        if (holders == null) {
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add(new SyncReport.Problem(
                    "sync.problem-mayor-holders-null",
                    Map.of("role", mayorRoleId)));
            return CompletableFuture.completedFuture(acc);
        }

        return callTowny(() -> {
            if (!townyFacade.isAvailable()) {
                return TownyTownsResult.unavailable();
            }
            try {
                return TownyTownsResult.ok(townyFacade.allTowns());
            } catch (TownyReadException e) {
                return TownyTownsResult.failedRead(e.getMessage() != null ? e.getMessage() : "Read failed against Towny");
            }
        }).handle((lookup, ex) -> {
            if (ex != null) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                return TownyTownsResult.failedRead("Towny call threw exception: " + cause.getMessage());
            }
            return lookup;
        }).thenComposeAsync(lookup -> {
            if (lookup.isUnavailable() || lookup.isFailedRead()) {
                acc.inconsistenciesFound.incrementAndGet();
                acc.problems.add(new SyncReport.Problem(
                        "sync.problem-mayor-audit-towny-failed",
                        Map.of("error", safeError(lookup.errorMessage()))));
                return CompletableFuture.completedFuture(acc);
            }

            List<TownSnapshot> allTowns = lookup.towns();
            Set<String> justifiedMayorDiscordIds = new LinkedHashSet<>();
            for (TownSnapshot town : allTowns) {
                if (!town.ruined() && town.mayorUuid() != null) {
                    linkRepository.findByUuid(town.mayorUuid())
                            .ifPresent(link -> justifiedMayorDiscordIds.add(link.discordId()));
                }
            }

            if (holders.isEmpty()) {
                if (!justifiedMayorDiscordIds.isEmpty()) {
                    acc.inconsistenciesFound.incrementAndGet();
                    acc.problems.add(new SyncReport.Problem(
                            "sync.problem-mayor-holders-empty",
                            Map.of("role", mayorRoleId)));
                }
                return CompletableFuture.completedFuture(acc);
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (String holderDiscordId : holders) {
                if (!justifiedMayorDiscordIds.contains(holderDiscordId)) {
                    acc.inconsistenciesFound.incrementAndGet();
                    acc.problems.add(new SyncReport.Problem(
                            "sync.problem-member-unjustified-mayor-role",
                            Map.of("discord", holderDiscordId, "role", mayorRoleId)));

                    if (isReportMode) {
                        acc.proposedRevocations.incrementAndGet();
                    } else {
                        futures.add(discordGateway.submit(new GuildOperation.ApplyMemberRoles(
                                holderDiscordId, List.of(), List.of(mayorRoleId)
                        )).thenAccept(outcome -> {
                            if (outcome != null && outcome.succeeded()) {
                                acc.rolesRevoked.incrementAndGet();
                                acc.inconsistenciesRepaired.incrementAndGet();
                            } else {
                                acc.problems.add(new SyncReport.Problem(
                                        "sync.problem-revoke-mayor-role-failed",
                                        Map.of("role", mayorRoleId, "discord", holderDiscordId, "reason", safeReason(outcome))));
                            }
                        }).exceptionally(ex -> {
                            acc.problems.add(new SyncReport.Problem(
                                    "sync.problem-revoke-mayor-role-failed",
                                    Map.of("role", mayorRoleId, "discord", holderDiscordId, "reason", safeError(ex))));
                            return null;
                        }));
                    }
                }
            }

            for (TownSnapshot town : allTowns) {
                if (!town.ruined() && town.mayorUuid() != null) {
                    Optional<TownSpace> spaceOpt = spaceRepository.findByTownUuid(town.uuid());
                    if (spaceOpt.isEmpty()) {
                        Optional<AccountLink> linkOpt = linkRepository.findByUuid(town.mayorUuid());
                        if (linkOpt.isPresent()) {
                            String mayorDiscordId = linkOpt.get().discordId();
                            if (!holders.contains(mayorDiscordId)) {
                                acc.inconsistenciesFound.incrementAndGet();
                                acc.problems.add(new SyncReport.Problem(
                                        "sync.problem-mayor-missing-role-town-without-space",
                                        Map.of("discord", mayorDiscordId, "town", town.name(), "role", mayorRoleId)));

                                if (isReportMode) {
                                    acc.proposedGrants.incrementAndGet();
                                } else {
                                    futures.add(discordGateway.submit(new GuildOperation.ApplyMemberRoles(
                                            mayorDiscordId, List.of(mayorRoleId), List.of()
                                    )).thenAccept(outcome -> {
                                        if (outcome != null && outcome.succeeded()) {
                                            acc.rolesGranted.incrementAndGet();
                                            acc.inconsistenciesRepaired.incrementAndGet();
                                        } else {
                                            acc.problems.add(new SyncReport.Problem(
                                                    "sync.problem-grant-mayor-role-failed",
                                                    Map.of("role", mayorRoleId, "discord", mayorDiscordId, "reason", safeReason(outcome))));
                                        }
                                    }).exceptionally(ex -> {
                                        acc.problems.add(new SyncReport.Problem(
                                                "sync.problem-grant-mayor-role-failed",
                                                Map.of("role", mayorRoleId, "discord", mayorDiscordId, "reason", safeError(ex))));
                                        return null;
                                    }));
                                }
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
        }, executor).exceptionally(ex -> {
            acc.inconsistenciesFound.incrementAndGet();
            acc.problems.add(new SyncReport.Problem(
                    "sync.problem-audit-mayor-role-exception",
                    Map.of("error", safeError(ex))));
            return acc;
        });
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
            return new TownyLookupResult(TownyStatus.UNAVAILABLE, Optional.empty(), "sync.cause-towny-unavailable");
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

    private record TownyTownsResult(
            TownyStatus status,
            List<TownSnapshot> towns,
            String errorMessage
    ) {
        static TownyTownsResult ok(List<TownSnapshot> towns) {
            return new TownyTownsResult(TownyStatus.OK, towns != null ? towns : List.of(), null);
        }

        static TownyTownsResult failedRead(String message) {
            return new TownyTownsResult(TownyStatus.FAILED_READ, List.of(), message);
        }

        static TownyTownsResult unavailable() {
            return new TownyTownsResult(TownyStatus.UNAVAILABLE, List.of(), "sync.cause-towny-unavailable");
        }

        boolean isUnavailable() {
            return status == TownyStatus.UNAVAILABLE;
        }

        boolean isFailedRead() {
            return status == TownyStatus.FAILED_READ;
        }
    }

    private static String safeStr(Object val) {
        if (val == null) return "";
        String s = val.toString();
        return s != null ? s : "";
    }

    private static String safeState(SpaceState state) {
        if (state == null) {
            return "general.unknown";
        }
        return switch (state) {
            case ACTIVE -> "admin.state-active";
            case ARCHIVED -> "admin.state-archived";
            case INCONSISTENT -> "admin.state-inconsistent";
        };
    }

    private static String safeError(Throwable t) {
        if (t == null) return "general.unknown";
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) return "general.unknown";
        return mapCause(msg);
    }

    private static String safeError(String msg) {
        if (msg == null || msg.isBlank()) return "general.unknown";
        return mapCause(msg);
    }

    private static String safeReason(OperationOutcome outcome) {
        if (outcome != null && outcome.reason().isPresent()) {
            return mapCause(outcome.reason().get());
        }
        return "general.unknown";
    }

    private static String safeReason(OperationOutcome outcome, Throwable ex) {
        if (outcome != null && outcome.reason().isPresent()) {
            return mapCause(outcome.reason().get());
        }
        if (ex != null) {
            return safeError(ex);
        }
        return "general.unknown";
    }

    private static String mapCause(String msg) {
        if (msg == null || msg.isBlank() || msg.equalsIgnoreCase("unknown")) {
            return "general.unknown";
        }
        if (msg.startsWith("sync.cause-") || msg.startsWith("general.") || msg.startsWith("admin.")) {
            return msg;
        }
        if (msg.contains("Towny is unavailable") || msg.contains("Towny data unavailable")) {
            return "sync.cause-towny-unavailable";
        }
        if (msg.contains("Discord is unavailable") || msg.contains("Discord gateway is unavailable") || msg.contains("Discord gateway unavailable")) {
            return "sync.cause-discord-unavailable";
        }
        if (msg.contains("read could not be completed") || msg.contains("Read failed against Towny") || msg.contains("Towny call threw exception")) {
            return "sync.cause-towny-read-failed";
        }
        if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("Timeout")) {
            return "sync.cause-timeout";
        }
        if (msg.contains("disconnected") || msg.contains("Disconnected")) {
            return "sync.cause-disconnected";
        }
        if (msg.contains("Queue stopped")) {
            return "sync.cause-queue-stopped";
        }
        if (msg.contains("Interrupted")) {
            return "sync.cause-interrupted";
        }
        if (msg.contains("Unexpected error")) {
            return "sync.cause-unexpected";
        }
        return msg;
    }

    private static final class SyncReportAccumulator {
        final int spacesChecked;
        final PluginConfig.Sync.Mode mode;
        final AtomicInteger rolesGranted = new AtomicInteger(0);
        final AtomicInteger rolesRevoked = new AtomicInteger(0);
        final AtomicInteger inconsistenciesFound = new AtomicInteger(0);
        final AtomicInteger inconsistenciesRepaired = new AtomicInteger(0);
        final AtomicInteger proposedGrants = new AtomicInteger(0);
        final AtomicInteger proposedRevocations = new AtomicInteger(0);
        final List<SyncReport.Problem> problems = Collections.synchronizedList(new ArrayList<>());

        SyncReportAccumulator(int spacesChecked, PluginConfig.Sync.Mode mode) {
            this.spacesChecked = spacesChecked;
            this.mode = mode != null ? mode : PluginConfig.Sync.Mode.REPAIR;
        }

        SyncReportAccumulator(int spacesChecked) {
            this(spacesChecked, PluginConfig.Sync.Mode.REPAIR);
        }

        SyncReportAccumulator merge(SyncReportAccumulator other) {
            this.rolesGranted.addAndGet(other.rolesGranted.get());
            this.rolesRevoked.addAndGet(other.rolesRevoked.get());
            this.inconsistenciesFound.addAndGet(other.inconsistenciesFound.get());
            this.inconsistenciesRepaired.addAndGet(other.inconsistenciesRepaired.get());
            this.proposedGrants.addAndGet(other.proposedGrants.get());
            this.proposedRevocations.addAndGet(other.proposedRevocations.get());
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
                    List.copyOf(problems),
                    mode,
                    proposedGrants.get(),
                    proposedRevocations.get()
            );
        }
    }
}
