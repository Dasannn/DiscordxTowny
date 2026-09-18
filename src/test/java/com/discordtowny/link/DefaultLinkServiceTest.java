package com.discordtowny.link;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.discord.GuildOperation;
import com.discordtowny.model.AccountLink;
import com.discordtowny.model.AuditEvent;
import com.discordtowny.model.LinkCode;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pruebas obligatorias de {@link DefaultLinkService} contra SQLite real.
 */
class DefaultLinkServiceTest {

    private Path dbFile;
    private HikariStorage storage;
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

        storage = new HikariStorage(dbConfig, Logger.getLogger("LinkServiceTest"));
        storage.initialize();
        linkRepository = storage.links();
        spaceRepository = storage.spaces();

        clock = new MutableClock(Instant.parse("2026-09-18T12:00:00Z"));

        // Caducidad 10m, maximo 3 intentos fallidos, bloqueo de 15m
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
        when(discordGateway.submit(any())).thenReturn(CompletableFuture.completedFuture(null));

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

    // --- 1. Ciclo completo ---

    @Test
    void cicloCompletoGenerarCanjearYPersistir() {
        UUID uuid = UUID.randomUUID();
        String discordId = "discord_user_1";

        // Generar
        Optional<String> optCode = service.generateCode(uuid).join();
        assertTrue(optCode.isPresent(), "Debe generar un codigo");
        String code = optCode.get();
        assertEquals(6, code.length());
        assertFalse(CodeGenerator.containsAmbiguousCharacters(code));

        // Canjear
        LinkService.LinkResult result = service.redeem(code, discordId).join();
        assertEquals(LinkService.LinkResult.SUCCESS, result);

        // Vinculo persistido
        Optional<AccountLink> byUuid = service.findByUuid(uuid).join();
        assertTrue(byUuid.isPresent());
        assertEquals(uuid, byUuid.get().uuid());
        assertEquals(discordId, byUuid.get().discordId());

        Optional<AccountLink> byDiscord = service.findByDiscordId(discordId).join();
        assertTrue(byDiscord.isPresent());
        assertEquals(uuid, byDiscord.get().uuid());

        // Se disparo la sincronizacion de roles
        verify(syncService).syncPlayer(uuid);
        // Se registro evento de auditoria
        verify(discordGateway).log(argThat(event ->
                event.action().equals("link") && event.actor().equals(discordId) && event.success()));
    }

    // --- 2. Codigo caducado, inexistente y ya usado ---

    @Test
    void codigoCaducadoEsRechazado() {
        UUID uuid = UUID.randomUUID();
        String code = service.generateCode(uuid).join().orElseThrow();

        // Avanzar el tiempo 11 minutos (caduca a los 10 minutos)
        clock.advance(Duration.ofMinutes(11));

        LinkService.LinkResult result = service.redeem(code, "discord_user_1").join();
        assertEquals(LinkService.LinkResult.CODE_EXPIRED, result);

        // El codigo debe haberse eliminado al detectarse caducado
        assertTrue(linkRepository.findCode(code).isEmpty());
        // El vinculo no debe haberse creado
        assertTrue(service.findByUuid(uuid).join().isEmpty());
    }

    @Test
    void codigoInexistenteEsRechazado() {
        LinkService.LinkResult result = service.redeem("NOEXISTE", "discord_user_1").join();
        assertEquals(LinkService.LinkResult.CODE_INVALID, result);
    }

    @Test
    void codigoYaUsadoNoSePuedeCanjearDosVeces() {
        UUID uuid = UUID.randomUUID();
        String code = service.generateCode(uuid).join().orElseThrow();

        LinkService.LinkResult first = service.redeem(code, "discord_user_1").join();
        assertEquals(LinkService.LinkResult.SUCCESS, first);

        // Segundo intento con otra cuenta de Discord
        LinkService.LinkResult second = service.redeem(code, "discord_user_2").join();
        // El codigo ya no existe en la base de datos tras el primer uso
        assertEquals(LinkService.LinkResult.CODE_INVALID, second);
    }

    // --- 3. Jugador y cuenta de Discord ya vinculados ---

    @Test
    void jugadorYaVinculadoNoPuedeGenerarNuevoCodigo() {
        UUID uuid = UUID.randomUUID();
        String code = service.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, "discord_user_1").join());

        // Intentar generar codigo estando ya vinculado
        Optional<String> nuevo = service.generateCode(uuid).join();
        assertTrue(nuevo.isEmpty(), "Un jugador ya vinculado no debe poder generar codigos");
    }

    @Test
    void jugadorYaVinculadoRechazaCanje() {
        UUID uuid = UUID.randomUUID();
        // Guardamos directamente un codigo
        LinkCode code = new LinkCode("TEST01", uuid, clock.instant().plusSeconds(600), 0);
        linkRepository.saveCode(code);

        // Pero el jugador ya tiene un vinculo existente con otra cuenta de Discord
        AccountLink existingLink = new AccountLink(uuid, "discord_otra", clock.instant(), "Jugador");
        linkRepository.save(existingLink);

        LinkService.LinkResult result = service.redeem("TEST01", "discord_nueva").join();
        assertEquals(LinkService.LinkResult.PLAYER_ALREADY_LINKED, result);
    }

    @Test
    void cuentaDiscordYaVinculadaEsRechazada() {
        UUID uuid1 = UUID.randomUUID();
        String code1 = service.generateCode(uuid1).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code1, "discord_user_1").join());

        // Otro jugador genera un codigo
        UUID uuid2 = UUID.randomUUID();
        String code2 = service.generateCode(uuid2).join().orElseThrow();

        // La cuenta discord_user_1 intenta canjear el codigo del segundo jugador
        LinkService.LinkResult result = service.redeem(code2, "discord_user_1").join();
        assertEquals(LinkService.LinkResult.DISCORD_ALREADY_LINKED, result);
    }

    // --- 4. Limite de intentos y bloqueo ---

    @Test
    void limiteDeIntentosBloqueaDeVerdad() {
        String discordId = "discord_atacante";

        // maxAttempts = 3
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem("MAL001", discordId).join());
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem("MAL002", discordId).join());

        // Al tercer fallo consecutivo se activa el bloqueo
        assertEquals(LinkService.LinkResult.TOO_MANY_ATTEMPTS, service.redeem("MAL003", discordId).join());

        // Los intentos posteriores mientras este bloqueado se rechazan inmediatamente
        assertEquals(LinkService.LinkResult.TOO_MANY_ATTEMPTS, service.redeem("MAL004", discordId).join());

        // Incluso con un codigo valido que exista, se rechaza por estar bloqueado
        UUID uuid = UUID.randomUUID();
        String validCode = service.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.TOO_MANY_ATTEMPTS, service.redeem(validCode, discordId).join());

        // Otro usuario de Discord no esta bloqueado
        String discordId2 = "discord_inocente";
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(validCode, discordId2).join());

        // Despues de que pase el tiempo de bloqueo (15 minutos), el usuario atacante queda desbloqueado
        clock.advance(Duration.ofMinutes(16));
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem("MAL005", discordId).join());
    }

    @Test
    void exitoLimpiaContadorDeFallos() {
        String discordId = "discord_user";

        // Falla 2 veces (menos del limite de 3)
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem("MAL001", discordId).join());
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem("MAL002", discordId).join());

        // Canjea un codigo valido con exito
        UUID uuid = UUID.randomUUID();
        String code = service.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, discordId).join());

        // El contador queda reiniciado para este usuario
        assertTrue(service.findByDiscordId(discordId).join().isPresent());
    }

    // --- 5. Generar codigo nuevo invalida el anterior ---

    @Test
    void generarCodigoNuevoInvalidaElAnterior() {
        UUID uuid = UUID.randomUUID();

        String code1 = service.generateCode(uuid).join().orElseThrow();
        String code2 = service.generateCode(uuid).join().orElseThrow();

        assertNotEquals(code1, code2, "Los dos codigos deben ser distintos");

        // El codigo antiguo ya no es valido
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem(code1, "discord_1").join());

        // El codigo nuevo si es valido
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code2, "discord_1").join());
    }

    // --- 6. Caracteres ambiguos ---

    @Test
    void codigosGeneradosNoContienenCaracteresAmbiguos() {
        UUID uuid = UUID.randomUUID();
        for (int i = 0; i < 500; i++) {
            String code = service.generateCode(uuid).join().orElseThrow();
            assertFalse(CodeGenerator.containsAmbiguousCharacters(code));
            // Borrar codigo para poder generar el siguiente sin que el UUID este ocupado
            linkRepository.deleteCode(code);
        }
    }

    // --- 7. Desvinculacion y retirada de roles ---

    @Test
    void unlinkEliminaVinculoYRetiraRoles() {
        UUID uuid = UUID.randomUUID();
        String discordId = "discord_linked";

        String code = service.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, discordId).join());

        // Desvincular
        boolean unlinked = service.unlink(uuid).join();
        assertTrue(unlinked);

        // Ya no existe el vinculo
        assertTrue(service.findByUuid(uuid).join().isEmpty());
        assertTrue(service.findByDiscordId(discordId).join().isEmpty());

        // Se envio la orden a DiscordGateway para retirar roles
        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway, atLeastOnce()).submit(captor.capture());

        boolean roleRevocationFound = captor.getAllValues().stream()
                .filter(op -> op instanceof GuildOperation.ApplyMemberRoles)
                .map(op -> (GuildOperation.ApplyMemberRoles) op)
                .anyMatch(op -> op.discordId().equals(discordId));
        assertTrue(roleRevocationFound, "Debe solicitar revocar roles de Discord al desvincular");
    }

    @Test
    void unlinkJugadorNoVinculadoRetornaFalse() {
        assertFalse(service.unlink(UUID.randomUUID()).join());
    }

    // --- 8. Purga de codigos caducados ---

    @Test
    void purgaDeCodigosCaducados() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();

        String code1 = service.generateCode(u1).join().orElseThrow();
        clock.advance(Duration.ofMinutes(15));
        String code2 = service.generateCode(u2).join().orElseThrow();

        // code1 esta caducado, code2 esta vigente
        int purged = service.purgeExpiredCodes().join();
        assertEquals(1, purged);

        assertTrue(linkRepository.findCode(code1).isEmpty());
        assertTrue(linkRepository.findCode(code2).isPresent());
    }

    // --- 9. Captura de nombre y manejo de fallos de persistencia ---

    @Test
    void nombreCapturadoEnGeneracionSePersisteAlCanjear() {
        UUID uuid = UUID.randomUUID();
        String code = service.generateCode(uuid, "Steve").join().orElseThrow();

        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, "discord_steve").join());

        AccountLink link = service.findByUuid(uuid).join().orElseThrow();
        assertEquals("Steve", link.lastKnownName());
    }

    @Test
    void falloGenericoDeBaseDeDatosNoSeReportaComoVinculoDuplicado() {
        LinkRepository mockRepo = mock(LinkRepository.class);
        UUID uuid = UUID.randomUUID();
        String code = "ABC234";
        LinkCode linkCode = new LinkCode(code, uuid, clock.instant().plusSeconds(600), 0);

        when(mockRepo.findCode(code)).thenReturn(Optional.of(linkCode));
        when(mockRepo.findByDiscordId("discord_test")).thenReturn(Optional.empty());
        when(mockRepo.findByUuid(uuid)).thenReturn(Optional.empty());
        doThrow(new StorageException("Error al guardar vinculo", new SQLException("disk I/O error")))
                .when(mockRepo).save(any());

        DefaultLinkService failService = new DefaultLinkService(
                mockRepo, config, discordGateway, townyFacade, spaceRepository, syncService, clock, ForkJoinPool.commonPool()
        );

        CompletionException ex = assertThrows(
                CompletionException.class,
                () -> failService.redeem(code, "discord_test").join()
        );

        assertInstanceOf(StorageException.class, ex.getCause());
        assertFalse(ex.getCause().getMessage().contains("Ya existe"));
        verify(mockRepo, never()).deleteCode(code);
    }

    @Test
    void choqueDeUnicidadPorConcurrenciaSeDetectaCorrectamente() {
        LinkRepository mockRepo = mock(LinkRepository.class);
        UUID uuid = UUID.randomUUID();
        String code = "ABC234";
        String discordId = "discord_concurrent";
        LinkCode linkCode = new LinkCode(code, uuid, clock.instant().plusSeconds(600), 0);

        when(mockRepo.findCode(code)).thenReturn(Optional.of(linkCode));
        when(mockRepo.findByDiscordId(discordId)).thenReturn(
                Optional.empty(),
                Optional.of(new AccountLink(UUID.randomUUID(), discordId, Instant.now(), "Otro"))
        );
        when(mockRepo.findByUuid(uuid)).thenReturn(Optional.empty());
        doThrow(new StorageException("Ya existe un vinculo para este UUID o Discord ID: " + uuid))
                .when(mockRepo).save(any());

        DefaultLinkService concurrentService = new DefaultLinkService(
                mockRepo, config, discordGateway, townyFacade, spaceRepository, syncService, clock, ForkJoinPool.commonPool()
        );

        LinkService.LinkResult result = concurrentService.redeem(code, discordId).join();
        assertEquals(LinkService.LinkResult.DISCORD_ALREADY_LINKED, result);
        verify(mockRepo).deleteCode(code);
    }
}
