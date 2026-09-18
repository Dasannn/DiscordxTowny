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
 * Implementacion por defecto de {@link LinkService}.
 *
 * <p>Regla de dependencias: dominio puro, no importa JDA ni Bukkit.
 * Todas las operaciones contra la base de datos se ejecutan fuera del
 * hilo principal a traves del executor configurado.
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
    private final ConcurrentHashMap<UUID, Object> uuidLocks = new ConcurrentHashMap<>();

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
        this.discordGateway = discordGateway;
        this.townyFacade = townyFacade;
        this.spaceRepository = spaceRepository;
        this.syncService = syncService;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
        this.codeGenerator = new CodeGenerator();
        this.attemptTracker = new AttemptTracker();
    }

    public DefaultLinkService(
            LinkRepository linkRepository,
            PluginConfig config,
            DiscordGateway discordGateway,
            TownyFacade townyFacade) {
        this(linkRepository, config, discordGateway, townyFacade, null, null, Clock.systemUTC(), ForkJoinPool.commonPool());
    }

    public DefaultLinkService(
            LinkRepository linkRepository,
            PluginConfig config) {
        this(linkRepository, config, null, null, null, null, Clock.systemUTC(), ForkJoinPool.commonPool());
    }

    public DefaultLinkService(
            LinkRepository linkRepository,
            PluginConfig config,
            Clock clock) {
        this(linkRepository, config, null, null, null, null, clock, ForkJoinPool.commonPool());
    }

    @Override
    public CompletableFuture<Optional<String>> generateCode(UUID uuid) {
        return generateCode(uuid, "");
    }

    @Override
    public CompletableFuture<Optional<String>> generateCode(UUID uuid, String lastKnownName) {
        return CompletableFuture.supplyAsync(() -> {
            // Devuelve vacio si el jugador ya esta vinculado
            if (linkRepository.findByUuid(uuid).isPresent()) {
                return Optional.empty();
            }

            // Generar codigo no ambiguo asegurando que no colisione con otro vivo
            String code;
            int maxTries = 10;
            do {
                code = codeGenerator.nextCode();
                maxTries--;
            } while (linkRepository.findCode(code).isPresent() && maxTries > 0);

            Instant expiresAt = clock.instant().plus(config.linking().codeExpiry());
            LinkCode linkCode = new LinkCode(code, uuid, expiresAt, 0);

            // Guarda el codigo sustituyendo cualquier codigo previo del mismo jugador
            linkRepository.saveCode(linkCode);

            // Guardar el nombre capturado para cuando se canjee el codigo
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

            // Serializar admision y resolucion de intentos por Discord ID
            synchronized (getDiscordLock(discordId)) {
                // 1. Comprobar bloqueo previo por intentos fallidos acumulados en la ventana
                if (attemptTracker.isLocked(discordId, now)) {
                    return CompletableFuture.completedFuture(LinkResult.TOO_MANY_ATTEMPTS);
                }

                // 2. Obtener ultimo nombre conocido capturado en el hilo principal
                String lastKnownName = pendingCodeNames.getOrDefault(code, "");
                if (lastKnownName.isBlank()) {
                    lastKnownName = "";
                }

                // 3. Canje atomico en una sola transaccion
                LinkRepository.ConsumeOutcome outcome = linkRepository.consumeCodeAndLink(
                        code, discordId, lastKnownName, now);

                int maxAttempts = config.linking().maxAttempts();
                Duration lockoutDuration = config.linking().attemptLockout();

                switch (outcome.result()) {
                    case CODE_NOT_FOUND -> {
                        boolean locked = attemptTracker.recordFailure(discordId, now, maxAttempts, lockoutDuration);
                        if (discordGateway != null) {
                            // Sin exponer el codigo en la auditoria
                            discordGateway.log(new AuditEvent(
                                    now, AuditEvent.Severity.WARNING, discordId, "link_attempt",
                                    "", false, Optional.of("Codigo no valido")));
                        }
                        return CompletableFuture.completedFuture(
                                locked ? LinkResult.TOO_MANY_ATTEMPTS : LinkResult.CODE_INVALID);
                    }
                    case CODE_EXPIRED -> {
                        pendingCodeNames.remove(code);
                        boolean locked = attemptTracker.recordFailure(discordId, now, maxAttempts, lockoutDuration);
                        if (discordGateway != null) {
                            // Sin exponer el codigo en la auditoria
                            discordGateway.log(new AuditEvent(
                                    now, AuditEvent.Severity.WARNING, discordId, "link_attempt",
                                    "", false, Optional.of("Codigo caducado")));
                        }
                        return CompletableFuture.completedFuture(
                                locked ? LinkResult.TOO_MANY_ATTEMPTS : LinkResult.CODE_EXPIRED);
                    }
                    case PLAYER_ALREADY_LINKED -> {
                        if (discordGateway != null) {
                            discordGateway.log(new AuditEvent(
                                    now, AuditEvent.Severity.WARNING, discordId, "link_attempt",
                                    "", false, Optional.of("Jugador ya vinculado")));
                        }
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

                        // No se reinicia el contador de fallos: se preserva durante su ventana temporal

                        if (discordGateway != null) {
                            discordGateway.log(new AuditEvent(
                                    now, AuditEvent.Severity.INFO, discordId, "link",
                                    playerUuid.toString(), true, Optional.of("Cuenta vinculada exitosamente")));
                        }

                        // Delegar sincronizacion en el contrato previsto y encadenar resultado
                        if (syncService != null) {
                            return syncService.syncPlayer(playerUuid)
                                    .thenApply(v -> LinkResult.SUCCESS);
                        }
                        return CompletableFuture.completedFuture(LinkResult.SUCCESS);
                    }
                    default -> throw new IllegalStateException("Resultado inesperado de canje: " + outcome.result());
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
        return unlink(uuid, null);
    }

    @Override
    public CompletableFuture<Boolean> unlink(UUID uuid, String expectedDiscordId) {
        return CompletableFuture.supplyAsync(() -> {
            pendingPlayerNames.remove(uuid);

            Optional<AccountLink> optLink = linkRepository.findByUuid(uuid);
            if (optLink.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }

            AccountLink link = optLink.get();
            if (expectedDiscordId != null && !link.discordId().equals(expectedDiscordId)) {
                return CompletableFuture.completedFuture(false);
            }

            String discordId = link.discordId();
            Instant linkedAt = link.linkedAt();

            // 1. Recopilar roles de towns registrados en storage (propagando fallos si ocurren)
            List<String> rolesToRevoke = new ArrayList<>();
            if (spaceRepository != null) {
                spaceRepository.findAll().forEach(space -> {
                    space.roleId().ifPresent(rolesToRevoke::add);
                });
            }

            if (discordGateway != null) {
                // Incluir tambien el rol global de alcalde gestionado por Discord
                discordGateway.mayorRoleId().ifPresent(rolesToRevoke::add);

                return discordGateway.submit(new GuildOperation.ApplyMemberRoles(
                        discordId,
                        List.of(),
                        rolesToRevoke
                )).thenApply(outcome -> {
                    if (!outcome.succeeded()) {
                        throw new IllegalStateException("Fallo al retirar roles en Discord: "
                                + outcome.reason().orElse("desconocido"));
                    }

                    // 2. Solo tras confirmacion exitosa de Discord, verificar que no cambio y borrar
                    synchronized (getUuidLock(uuid)) {
                        Optional<AccountLink> current = linkRepository.findByUuid(uuid);
                        if (current.isEmpty() || !current.get().discordId().equals(discordId)
                                || !current.get().linkedAt().equals(linkedAt)) {
                            return false;
                        }

                        boolean deleted = linkRepository.deleteByUuid(uuid);
                        if (!deleted) {
                            return false;
                        }

                        Instant now = clock.instant();
                        discordGateway.log(new AuditEvent(
                                now, AuditEvent.Severity.INFO, "server", "unlink",
                                uuid.toString(), true, Optional.of("Vinculo eliminado")));
                        return true;
                    }
                });
            }

            // Si no hay discordGateway configurado
            synchronized (getUuidLock(uuid)) {
                Optional<AccountLink> current = linkRepository.findByUuid(uuid);
                if (current.isEmpty() || !current.get().discordId().equals(discordId)
                        || !current.get().linkedAt().equals(linkedAt)) {
                    return CompletableFuture.completedFuture(false);
                }
                boolean deleted = linkRepository.deleteByUuid(uuid);
                return CompletableFuture.completedFuture(deleted);
            }
        }, executor).thenCompose(future -> future);
    }

    /**
     * Purga los codigos caducados de la base de datos.
     */
    public CompletableFuture<Integer> purgeExpiredCodes() {
        return CompletableFuture.supplyAsync(() -> linkRepository.purgeExpiredCodes(clock.instant()), executor);
    }

    private Object getDiscordLock(String discordId) {
        return discordLocks.computeIfAbsent(discordId, k -> new Object());
    }

    private Object getUuidLock(UUID uuid) {
        return uuidLocks.computeIfAbsent(uuid, k -> new Object());
    }
}
