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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pruebas obligatorias de {@link DefaultLinkService} contra SQLite real.
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

    // --- 1. Ciclo completo con reapertura y verificacion de auditoria sin secretos ---

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

        // Se registro evento de auditoria sin exponer el codigo en ningun campo (hallazgo 7)
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(discordGateway).log(captor.capture());
        AuditEvent event = captor.getValue();
        assertEquals("link", event.action());
        assertEquals(discordId, event.actor());
        assertTrue(event.success());
        assertFalse(event.target().contains(code), "El target de auditoria no debe contener el codigo");
        event.detail().ifPresent(d -> assertFalse(d.contains(code), "El detalle no debe contener el codigo"));

        // Verificar persistencia tras reinicio/reapertura de almacenamiento (hallazgo 12)
        storage.close();
        HikariStorage reopened = new HikariStorage(dbConfig, Logger.getLogger("ReopenTest"));
        reopened.initialize();
        try {
            Optional<AccountLink> reopenedLink = reopened.links().findByUuid(uuid);
            assertTrue(reopenedLink.isPresent(), "El vinculo debe persistir en disco tras reapertura");
            assertEquals(discordId, reopenedLink.get().discordId());
        } finally {
            reopened.close();
        }
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

        // Auditoria no expone el codigo
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(discordGateway).log(captor.capture());
        assertFalse(captor.getValue().target().contains(code));
    }

    @Test
    void codigoInexistenteEsRechazado() {
        LinkService.LinkResult result = service.redeem("NOEXISTE", "discord_user_1").join();
        assertEquals(LinkService.LinkResult.CODE_INVALID, result);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(discordGateway).log(captor.capture());
        assertFalse(captor.getValue().target().contains("NOEXISTE"));
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
        LinkCode code = new LinkCode("TEST01", uuid, clock.instant().plusSeconds(600), 0);
        linkRepository.saveCode(code);

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

    // --- 4. Limite de intentos y presupuesto temporal ---

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
    void exitoYDesvinculacionNoReinicianPresupuestoDeFallosEnVentana() {
        // Correccion de hallazgo 4 y hallazgo 12: comprobar observablemente
        // que un canje con exito y desvinculacion posterior NO borran el contador
        String discordId = "discord_atacante_astuto";

        // maxAttempts = 3: falla 2 veces (queda 1 intento en la ventana)
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem("MAL001", discordId).join());
        assertEquals(LinkService.LinkResult.CODE_INVALID, service.redeem("MAL002", discordId).join());

        // Canjea un codigo valido propio con exito
        UUID uuidPropio = UUID.randomUUID();
        String codeValido = service.generateCode(uuidPropio).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(codeValido, discordId).join());

        // Desvincula la cuenta
        assertTrue(service.unlink(uuidPropio).join());

        // El atacante prueba un nuevo codigo falso dentro de la ventana de 15 minutos:
        // Debe ser su 3er fallo acumulado y activar el bloqueo de inmediato
        assertEquals(LinkService.LinkResult.TOO_MANY_ATTEMPTS, service.redeem("MAL003", discordId).join());
    }

    @Test
    void rafagaConcurrenteDeIntentosFallidosRespetaElPresupuesto() throws Exception {
        // Hallazgo 4: serializacion de admision y resolucion ante rafagas concurrentes
        String discordId = "discord_burst_attacker";
        int totalPeticiones = 10;
        CyclicBarrier barrier = new CyclicBarrier(totalPeticiones);
        CountDownLatch latch = new CountDownLatch(totalPeticiones);
        List<LinkService.LinkResult> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalPeticiones; i++) {
            final String badCode = "BURST" + i;
            new Thread(() -> {
                try {
                    barrier.await();
                    LinkService.LinkResult res = service.redeem(badCode, discordId).join();
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

        assertEquals(totalPeticiones, invalidCount + lockedCount);
        // Como maxAttempts = 3, exactamente 2 fallos son invalidos y a partir del 3ro se bloquea
        assertTrue(invalidCount <= 3, "No pueden pasar mas intentos fallidos que el limite: " + invalidCount);
        assertTrue(lockedCount >= (totalPeticiones - 3), "Al menos 7 peticiones deben recibir bloqueo: " + lockedCount);
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
            linkRepository.deleteCode(code);
        }
    }

    // --- 7. Desvinculacion y retirada de roles (hallazgos 2, 3, 12) ---

    @Test
    void unlinkEliminaVinculoYRetiraRolesIncluyendoRolDeAlcalde() {
        UUID uuid = UUID.randomUUID();
        String discordId = "discord_linked_mayor";

        // Registrar un espacio de town con rol
        TownSpace townSpace = new TownSpace(
                UUID.randomUUID(), "Madrid",
                Optional.of("cat-1"), Optional.of("text-1"), Optional.of("voice-1"),
                Optional.of("role-town-madrid"), SpaceState.ACTIVE, clock.instant(),
                Optional.empty(), Optional.empty()
        );
        spaceRepository.save(townSpace);

        // Gateway expone el rol de alcalde configurado
        when(discordGateway.mayorRoleId()).thenReturn(Optional.of("role-mayor-global"));

        String code = service.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, discordId).join());

        // Desvincular
        boolean unlinked = service.unlink(uuid).join();
        assertTrue(unlinked);

        // Ya no existe el vinculo
        assertTrue(service.findByUuid(uuid).join().isEmpty());
        assertTrue(service.findByDiscordId(discordId).join().isEmpty());

        // Se envio la orden a DiscordGateway para retirar roles exactos (town + alcalde)
        ArgumentCaptor<GuildOperation> captor = ArgumentCaptor.forClass(GuildOperation.class);
        verify(discordGateway, atLeastOnce()).submit(captor.capture());

        GuildOperation.ApplyMemberRoles applyOp = captor.getAllValues().stream()
                .filter(op -> op instanceof GuildOperation.ApplyMemberRoles)
                .map(op -> (GuildOperation.ApplyMemberRoles) op)
                .filter(op -> op.discordId().equals(discordId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Debe enviarse orden ApplyMemberRoles para " + discordId));

        assertTrue(applyOp.revokeRoleIds().contains("role-town-madrid"),
                "Debe solicitar revocar el rol de la town");
        assertTrue(applyOp.revokeRoleIds().contains("role-mayor-global"),
                "Debe solicitar revocar el rol global de alcalde");
    }

    @Test
    void unlinkNoConfirmaExitoSiFallaDiscordYPreservaElVinculo() {
        // Hallazgo 2: con bot caido o gateway fallido, no borrar el vinculo ni confirmar exito
        UUID uuid = UUID.randomUUID();
        String discordId = "discord_gateway_down";

        String code = service.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, discordId).join());

        // Simular que Discord devuelve un fallo transitorio
        when(discordGateway.submit(any())).thenReturn(
                CompletableFuture.completedFuture(OperationOutcome.transientFailure("Discord no esta disponible")));

        CompletionException ex = assertThrows(CompletionException.class, () -> service.unlink(uuid).join());
        assertInstanceOf(IllegalStateException.class, ex.getCause());

        // El vinculo NO debe haberse eliminado
        assertTrue(service.findByUuid(uuid).join().isPresent(),
                "El vinculo debe permanecer si Discord no pudo retirar los roles");
        assertTrue(service.findByDiscordId(discordId).join().isPresent());
    }

    @Test
    void unlinkPropagaFalloDeSpaceRepositorySinBorrarVinculo() {
        // Hallazgo 2: si falla spaceRepository.findAll(), no tragar la excepcion
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

        // El vinculo NO debe haberse eliminado
        assertTrue(failingService.findByUuid(uuid).join().isPresent());
    }

    @Test
    void unlinkCondicionalNoBorraSiElVinculoFueRecreado() {
        // Hallazgo 9: evitar que una peticion antigua borre un vinculo recreado
        UUID uuid = UUID.randomUUID();
        String discordIdAntiguo = "discord_antiguo";
        String discordIdNuevo = "discord_nuevo";

        String code = service.generateCode(uuid).join().orElseThrow();
        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, discordIdAntiguo).join());

        // Simular que entre la autorizacion y el borrado, el vinculo se recreo para otra cuenta
        linkRepository.deleteByUuid(uuid);
        linkRepository.save(new AccountLink(uuid, discordIdNuevo, clock.instant(), "Jugador"));

        // Intentar desvincular condicionando a la cuenta antigua
        boolean unlinked = service.unlink(uuid, discordIdAntiguo).join();
        assertFalse(unlinked, "No debe borrar si la cuenta Discord no coincide con la esperada");

        // El vinculo de discordIdNuevo permanece intacto
        Optional<AccountLink> actual = service.findByUuid(uuid).join();
        assertTrue(actual.isPresent());
        assertEquals(discordIdNuevo, actual.get().discordId());
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

        int purged = service.purgeExpiredCodes().join();
        assertEquals(1, purged);

        assertTrue(linkRepository.findCode(code1).isEmpty());
        assertTrue(linkRepository.findCode(code2).isPresent());
    }

    // --- 9. Captura de nombre, sincronizacion y concurrencia real ---

    @Test
    void nombreCapturadoEnGeneracionSePersisteAlCanjear() {
        UUID uuid = UUID.randomUUID();
        String code = service.generateCode(uuid, "Steve").join().orElseThrow();

        assertEquals(LinkService.LinkResult.SUCCESS, service.redeem(code, "discord_steve").join());

        AccountLink link = service.findByUuid(uuid).join().orElseThrow();
        assertEquals("Steve", link.lastKnownName());
    }

    @Test
    void falloDeSyncServiceEsObservableEnElFuturoDeRedeem() {
        // Hallazgo 5: hacer observable cualquier fallo de sincronizacion
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
    void falloGenericoDeBaseDeDatosNoSeReportaComoVinculoDuplicado() {
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
    void canjeConcurrenteDelMismoCodigoSoloUnoTieneExito() throws Exception {
        // Hallazgo 1 y 12: concurrencia real con barrera y multiples hilos
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

        assertEquals(1, successCount, "Exactamente uno de los canjes debe tener exito");
        assertEquals(1, failedCount, "El otro canje concurrente debe ser rechazado");
    }
}
