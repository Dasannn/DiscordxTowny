package com.discordtowny.discord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de la cola serializada de operaciones del guild.
 *
 * <p>Demuestra sin red ni JDA:
 * <ul>
 *   <li>que la cola serializa (no ejecuta dos operaciones a la vez),</li>
 *   <li>que un reintento tras fallo parcial no duplica recursos,</li>
 *   <li>que un fallo permanente no se reintenta.</li>
 * </ul>
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class GuildOperationQueueTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    @Test
    @DisplayName("La cola serializa: no ejecuta dos operaciones a la vez")
    void serializesOperations() throws Exception {
        // Registro de concurrencia: si alguna vez dos ejecuciones se solapan, lo detectamos
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        List<String> executionOrder = new CopyOnWriteArrayList<>();

        GuildOperationExecutor executor = op -> {
            int running = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(running, Math::max);
            try {
                // Pequena pausa para verificar concurrencia
                Thread.sleep(10);
                executionOrder.add(op.describe());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrent.decrementAndGet();
            }
            return OperationOutcome.success();
        };

        var queue = new GuildOperationQueue(executor, LOGGER,
                3, java.time.Duration.ofMillis(5), 1.0);
        queue.start();

        // Enviar varias operaciones
        var futures = new ArrayList<CompletableFuture<OperationOutcome>>();
        for (int i = 0; i < 5; i++) {
            var op = new GuildOperation.DeleteSpace(
                    java.util.UUID.randomUUID(), "town-" + i);
            futures.add(queue.submit(op));
        }

        // Esperar a que todas terminen
        for (var f : futures) {
            OperationOutcome result = f.get(5, TimeUnit.SECONDS);
            assertTrue(result.succeeded());
        }

        queue.shutdown();

        // La concurrencia maxima debe ser 1
        assertEquals(1, maxConcurrent.get(),
                "Nunca debe haber mas de una operacion ejecutandose a la vez");

        // Todas se ejecutaron
        assertEquals(5, executionOrder.size());
    }

    @Test
    @DisplayName("Reintento tras fallo parcial no duplica recursos")
    void retryDoesNotDuplicate() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        List<String> createdRoles = new ArrayList<>();
        List<String> createdChannels = new ArrayList<>();

        // Ejecutor que simula la creacion idempotente:
        // Intento 1: crea el rol con exito, pero falla antes de crear los canales (fallo parcial).
        // Intento 2: al reintentar, comprueba si el rol ya existe (no lo duplica) y crea los canales.
        GuildOperationExecutor executor = op -> {
            int attempt = attempts.incrementAndGet();

            // Paso 1: Rol de la town (idempotente: no duplicar si ya existe)
            if (!createdRoles.contains("rol-test-town")) {
                createdRoles.add("rol-test-town");
            }

            // Fallo parcial en el primer intento tras crear el rol
            if (attempt == 1) {
                return OperationOutcome.transientFailure("Error transitorio de red al crear canales");
            }

            // Paso 2: Canales de la town (idempotente)
            if (!createdChannels.contains("canal-test-town")) {
                createdChannels.add("canal-test-town");
            }

            return OperationOutcome.success();
        };

        // Parametros de reintento rapidos para no demorar los tests
        var queue = new GuildOperationQueue(executor, LOGGER,
                3, java.time.Duration.ofMillis(10), 1.0);
        queue.start();

        var op = new GuildOperation.CreateSpace(new com.discordtowny.model.SpaceRequest(
                java.util.UUID.randomUUID(), "test-town", java.util.UUID.randomUUID(),
                List.of(), "discord-mayor"));
        OperationOutcome result = queue.submit(op).get(5, TimeUnit.SECONDS);

        queue.shutdown();

        assertTrue(result.succeeded(), "Debe haber tenido exito tras reintento");
        assertEquals(2, attempts.get(), "Exactamente 2 intentos: el original + 1 reintento");
        assertEquals(1, createdRoles.size(), "No debe duplicar el rol");
        assertEquals(1, createdChannels.size(), "No debe duplicar el canal");
        assertEquals("rol-test-town", createdRoles.getFirst());
        assertEquals("canal-test-town", createdChannels.getFirst());
    }

    @Test
    @DisplayName("Fallo permanente no se reintenta")
    void permanentFailureNoRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        GuildOperationExecutor executor = op -> {
            attempts.incrementAndGet();
            return OperationOutcome.permanentFailure("Permisos insuficientes");
        };

        var queue = new GuildOperationQueue(executor, LOGGER,
                3, java.time.Duration.ofMillis(10), 1.0);
        queue.start();

        var op = new GuildOperation.DeleteSpace(
                java.util.UUID.randomUUID(), "test-town");
        OperationOutcome result = queue.submit(op).get(5, TimeUnit.SECONDS);

        queue.shutdown();

        assertFalse(result.succeeded());
        assertEquals(OperationOutcome.Status.PERMANENT_FAILURE, result.status());
        assertEquals(1, attempts.get(),
                "Un fallo permanente solo se intenta una vez");
    }

    @Test
    @DisplayName("Los fallos transitorios se reintentan hasta el maximo")
    void transientFailureRetriesUpToMax() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        GuildOperationExecutor executor = op -> {
            attempts.incrementAndGet();
            return OperationOutcome.transientFailure("Siempre falla");
        };

        // Reintentos rapidos para el test (10ms base, sin multiplicador)
        var queue = new GuildOperationQueue(executor, LOGGER,
                5, java.time.Duration.ofMillis(10), 1.0);
        queue.start();

        var op = new GuildOperation.DeleteSpace(
                java.util.UUID.randomUUID(), "test-town");
        OperationOutcome result = queue.submit(op).get(5, TimeUnit.SECONDS);

        queue.shutdown();

        assertFalse(result.succeeded());
        assertEquals(OperationOutcome.Status.TRANSIENT_FAILURE, result.status());
        // 1 intento original + 5 reintentos = 6
        assertEquals(6, attempts.get(),
                "Se deben agotar todos los reintentos (1 + MAX_RETRIES)");
    }

    @Test
    @DisplayName("submit() nunca completa el futuro de forma excepcional")
    void submitNeverCompletesExceptionally() throws Exception {
        // Caso 1: Lanza excepcion en intento 1 y luego tiene exito
        AtomicInteger calls1 = new AtomicInteger(0);
        GuildOperationExecutor executor1 = op -> {
            if (calls1.incrementAndGet() == 1) {
                throw new RuntimeException("Explosion inesperada");
            }
            return OperationOutcome.success();
        };

        var queue1 = new GuildOperationQueue(executor1, LOGGER,
                3, java.time.Duration.ofMillis(10), 1.0);
        queue1.start();

        var op1 = new GuildOperation.DeleteSpace(
                java.util.UUID.randomUUID(), "test-town");

        CompletableFuture<OperationOutcome> f1 = queue1.submit(op1);
        OperationOutcome result1 = f1.get(5, TimeUnit.SECONDS);

        queue1.shutdown();

        assertFalse(f1.isCompletedExceptionally());
        assertNotNull(result1);
        assertTrue(result1.succeeded());
        assertEquals(2, calls1.get());

        // Caso 2: Lanza excepcion en todos los intentos (agota reintentos sin completar excepcionalmente)
        GuildOperationExecutor executor2 = op -> {
            throw new IllegalStateException("Error constante");
        };

        var queue2 = new GuildOperationQueue(executor2, LOGGER,
                2, java.time.Duration.ofMillis(5), 1.0);
        queue2.start();

        CompletableFuture<OperationOutcome> f2 = queue2.submit(op1);
        OperationOutcome result2 = f2.get(5, TimeUnit.SECONDS);

        queue2.shutdown();

        assertFalse(f2.isCompletedExceptionally());
        assertNotNull(result2);
        assertFalse(result2.succeeded());
        assertEquals(OperationOutcome.Status.TRANSIENT_FAILURE, result2.status());

        // Caso 3: El ejecutor devuelve null
        GuildOperationExecutor executor3 = op -> null;
        var queue3 = new GuildOperationQueue(executor3, LOGGER,
                1, java.time.Duration.ofMillis(5), 1.0);
        queue3.start();

        CompletableFuture<OperationOutcome> f3 = queue3.submit(op1);
        OperationOutcome result3 = f3.get(5, TimeUnit.SECONDS);

        queue3.shutdown();

        assertFalse(f3.isCompletedExceptionally());
        assertNotNull(result3);
        assertFalse(result3.succeeded());

        // Caso 4: Se envia una operacion nula
        var queue4 = new GuildOperationQueue(op -> OperationOutcome.success(), LOGGER);
        CompletableFuture<OperationOutcome> f4 = queue4.submit(null);
        OperationOutcome result4 = f4.get(5, TimeUnit.SECONDS);

        assertFalse(f4.isCompletedExceptionally());
        assertNotNull(result4);
        assertFalse(result4.succeeded());
        assertEquals(OperationOutcome.Status.PERMANENT_FAILURE, result4.status());
    }

    @Test
    @DisplayName("El orden de ejecucion respeta el orden de envio")
    void executionRespectsSubmissionOrder() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        GuildOperationExecutor executor = op -> {
            order.add(op.describe());
            return OperationOutcome.success();
        };

        var queue = new GuildOperationQueue(executor, LOGGER,
                3, java.time.Duration.ofMillis(5), 1.0);
        queue.start();

        var futures = new ArrayList<CompletableFuture<OperationOutcome>>();
        for (int i = 0; i < 10; i++) {
            var op = new GuildOperation.DeleteSpace(
                    java.util.UUID.randomUUID(), "town-" + i);
            futures.add(queue.submit(op));
        }

        for (var f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        queue.shutdown();

        // Verificar que el orden de ejecucion es secuencial
        assertEquals(10, order.size());
        for (int i = 0; i < 10; i++) {
            assertEquals("borrar definitivamente el espacio de town-" + i, order.get(i));
        }
    }

    @Test
    @DisplayName("Shutdown completa los futuros pendientes sin colgarse")
    void shutdownCompletesPendingFutures() throws Exception {
        // Ejecutor que tarda un poco para que haya pendientes al hacer shutdown
        CountDownLatch started = new CountDownLatch(1);
        GuildOperationExecutor executor = op -> {
            started.countDown();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OperationOutcome.success();
        };

        var queue = new GuildOperationQueue(executor, LOGGER,
                3, java.time.Duration.ofMillis(5), 1.0);
        queue.start();

        // La primera se ejecutara (y tardara)
        var f1 = queue.submit(new GuildOperation.DeleteSpace(
                java.util.UUID.randomUUID(), "lenta"));
        // Las demas quedaran en cola
        var f2 = queue.submit(new GuildOperation.DeleteSpace(
                java.util.UUID.randomUUID(), "pendiente-1"));
        var f3 = queue.submit(new GuildOperation.DeleteSpace(
                java.util.UUID.randomUUID(), "pendiente-2"));

        // Esperar a que la primera empiece
        assertTrue(started.await(5, TimeUnit.SECONDS));

        // Apagar la cola
        queue.shutdown();

        // Los pendientes deben completarse (con fallo transitorio, no colgarse)
        OperationOutcome r2 = f2.get(5, TimeUnit.SECONDS);
        OperationOutcome r3 = f3.get(5, TimeUnit.SECONDS);

        assertNotNull(r2);
        assertNotNull(r3);
        assertEquals(OperationOutcome.Status.TRANSIENT_FAILURE, r2.status());
        assertEquals(OperationOutcome.Status.TRANSIENT_FAILURE, r3.status());
    }
}
