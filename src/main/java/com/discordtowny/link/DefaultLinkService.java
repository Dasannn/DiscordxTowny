package com.discordtowny.link;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.discord.GuildOperation;
import com.discordtowny.model.AccountLink;
import com.discordtowny.model.AuditEvent;
import com.discordtowny.model.LinkCode;
import com.discordtowny.model.ResidentSnapshot;
import com.discordtowny.storage.LinkRepository;
import com.discordtowny.storage.SpaceRepository;
import com.discordtowny.storage.StorageException;
import com.discordtowny.sync.SyncService;
import com.discordtowny.towny.TownyFacade;

import java.sql.SQLException;
import java.time.Clock;
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
            if (lastKnownName != null && !lastKnownName.isBlank()) {
                pendingPlayerNames.put(uuid, lastKnownName);
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
                return LinkResult.CODE_INVALID;
            }

            String code = rawCode.trim().toUpperCase(Locale.ROOT);
            Instant now = clock.instant();

            // 1. Comprobar bloqueo previo por demasiados intentos fallidos
            if (attemptTracker.isLocked(discordId, now)) {
                return LinkResult.TOO_MANY_ATTEMPTS;
            }

            // 2. Comprobar si la cuenta de Discord ya esta vinculada a otro jugador
            if (linkRepository.findByDiscordId(discordId).isPresent()) {
                return LinkResult.DISCORD_ALREADY_LINKED;
            }

            // 3. Buscar el codigo en el repositorio
            Optional<LinkCode> optCode = linkRepository.findCode(code);
            if (optCode.isEmpty()) {
                boolean locked = attemptTracker.recordFailure(
                        discordId, now, config.linking().maxAttempts(), config.linking().attemptLockout());
                if (discordGateway != null) {
                    discordGateway.log(new AuditEvent(
                            now, AuditEvent.Severity.WARNING, discordId, "link_attempt",
                            "code:" + code, false, Optional.of("Codigo no valido")));
                }
                return locked ? LinkResult.TOO_MANY_ATTEMPTS : LinkResult.CODE_INVALID;
            }

            LinkCode linkCode = optCode.get();

            // 4. Comprobar si el codigo ha caducado
            if (linkCode.isExpired(now)) {
                linkRepository.deleteCode(code);
                pendingPlayerNames.remove(linkCode.uuid());
                boolean locked = attemptTracker.recordFailure(
                        discordId, now, config.linking().maxAttempts(), config.linking().attemptLockout());
                if (discordGateway != null) {
                    discordGateway.log(new AuditEvent(
                            now, AuditEvent.Severity.WARNING, discordId, "link_attempt",
                            "code:" + code, false, Optional.of("Codigo caducado")));
                }
                return locked ? LinkResult.TOO_MANY_ATTEMPTS : LinkResult.CODE_EXPIRED;
            }

            // 5. Comprobar si el jugador ya tiene otra cuenta de Discord vinculada
            UUID playerUuid = linkCode.uuid();
            if (linkRepository.findByUuid(playerUuid).isPresent()) {
                linkRepository.deleteCode(code);
                pendingPlayerNames.remove(playerUuid);
                if (discordGateway != null) {
                    discordGateway.log(new AuditEvent(
                            now, AuditEvent.Severity.WARNING, discordId, "link_attempt",
                            playerUuid.toString(), false, Optional.of("Jugador ya vinculado")));
                }
                return LinkResult.PLAYER_ALREADY_LINKED;
            }

            // 6. Obtener nombre del residente o jugador para mostrar (nunca null)
            String lastKnownName = pendingPlayerNames.remove(playerUuid);
            if (lastKnownName == null || lastKnownName.isBlank()) {
                if (townyFacade != null) {
                    try {
                        lastKnownName = townyFacade.resident(playerUuid)
                                .map(ResidentSnapshot::name)
                                .orElse(null);
                    } catch (Exception ignored) {
                        // TownyFacade solo se consulta en hilo principal; si falla se ignora
                    }
                }
            }
            if (lastKnownName == null || lastKnownName.isBlank()) {
                lastKnownName = "";
            }

            // 7. Persistir el vinculo distinguiendo choques de unicidad de otros fallos
            AccountLink link = new AccountLink(playerUuid, discordId, now, lastKnownName);
            try {
                linkRepository.save(link);
            } catch (StorageException e) {
                if (isUniqueViolation(e)) {
                    // Choque genuino en las restricciones UNIQUE por peticiones concurrentes
                    linkRepository.deleteCode(code);
                    if (linkRepository.findByDiscordId(discordId).isPresent()) {
                        return LinkResult.DISCORD_ALREADY_LINKED;
                    }
                    if (linkRepository.findByUuid(playerUuid).isPresent()) {
                        return LinkResult.PLAYER_ALREADY_LINKED;
                    }
                }
                // Si fue otro fallo de base de datos o no se pudo resolver el choque,
                // propagamos la excepcion para indicar que la operacion fallo
                throw e;
            }

            // 8. Invalida el codigo utilizado
            linkRepository.deleteCode(code);

            // 9. Limpiar los intentos fallidos acumulados para este usuario de Discord
            attemptTracker.clear(discordId);

            // 10. Registrar evento de auditoria
            if (discordGateway != null) {
                discordGateway.log(new AuditEvent(
                        now, AuditEvent.Severity.INFO, discordId, "link",
                        playerUuid.toString(), true, Optional.of("Cuenta vinculada exitosamente")));
            }

            // 11. Disparar sincronizacion de roles
            if (syncService != null) {
                syncService.syncPlayer(playerUuid);
            } else if (discordGateway != null && spaceRepository != null) {
                triggerRoleSyncFallback(playerUuid, discordId);
            }

            return LinkResult.SUCCESS;
        }, executor);
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
        return CompletableFuture.supplyAsync(() -> {
            pendingPlayerNames.remove(uuid);
            Optional<AccountLink> optLink = linkRepository.findByUuid(uuid);
            if (optLink.isEmpty()) {
                return false;
            }

            AccountLink link = optLink.get();
            String discordId = link.discordId();

            boolean deleted = linkRepository.deleteByUuid(uuid);
            if (!deleted) {
                return false;
            }

            Instant now = clock.instant();

            // Registrar auditoria
            if (discordGateway != null) {
                discordGateway.log(new AuditEvent(
                        now, AuditEvent.Severity.INFO, "server", "unlink",
                        uuid.toString(), true, Optional.of("Vinculo eliminado")));
            }

            // Retirar todos los roles que dio el plugin
            revokeAllPluginRoles(discordId);

            return true;
        }, executor);
    }

    /**
     * Purga los codigos caducados de la base de datos.
     */
    public CompletableFuture<Integer> purgeExpiredCodes() {
        return CompletableFuture.supplyAsync(() -> linkRepository.purgeExpiredCodes(clock.instant()), executor);
    }

    private void revokeAllPluginRoles(String discordId) {
        if (discordGateway == null) {
            return;
        }

        List<String> rolesToRevoke = new ArrayList<>();
        if (spaceRepository != null) {
            try {
                spaceRepository.findAll().forEach(space -> {
                    space.roleId().ifPresent(rolesToRevoke::add);
                });
            } catch (Exception ignored) {
                // Fallo de base de datos se ignora
            }
        }

        discordGateway.submit(new GuildOperation.ApplyMemberRoles(
                discordId,
                List.of(),
                rolesToRevoke
        ));
    }

    private void triggerRoleSyncFallback(UUID playerUuid, String discordId) {
        if (discordGateway == null || spaceRepository == null || townyFacade == null) {
            return;
        }
        try {
            townyFacade.townOf(playerUuid).ifPresent(town -> {
                spaceRepository.findByTownUuid(town.uuid()).ifPresent(space -> {
                    space.roleId().ifPresent(roleId -> {
                        discordGateway.submit(new GuildOperation.ApplyMemberRoles(
                                discordId,
                                List.of(roleId),
                                List.of()
                        ));
                    });
                });
            });
        } catch (Exception ignored) {
            // Ignorado si no se pudo acceder a Towny
        }
    }

    /**
     * Distingue si la excepcion de almacenamiento corresponde a una violacion
     * de unicidad (choque por concurrencia) o a un fallo generico de base de datos.
     */
    private static boolean isUniqueViolation(StorageException e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("Ya existe")
                || msg.contains("UNIQUE constraint failed")
                || msg.contains("Duplicate entry")
                || msg.contains("PRIMARY KEY must be unique"))) {
            return true;
        }
        Throwable cause = e.getCause();
        if (cause != null) {
            String causeMsg = cause.getMessage();
            if (causeMsg != null && (causeMsg.contains("UNIQUE constraint failed")
                    || causeMsg.contains("PRIMARY KEY must be unique")
                    || causeMsg.contains("Duplicate entry"))) {
                return true;
            }
            if (cause instanceof SQLException sqlEx && sqlEx.getErrorCode() == 1062) {
                return true;
            }
        }
        return false;
    }
}
