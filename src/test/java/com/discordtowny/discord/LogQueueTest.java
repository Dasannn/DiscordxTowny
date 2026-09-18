package com.discordtowny.discord;

import com.discordtowny.model.AuditEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de la cola de logs.
 *
 * <p>Demuestra sin red:
 * <ul>
 *   <li>que descarta con recuento al llenarse, en vez de crecer sin limite,</li>
 *   <li>que nunca bloquea a quien produce el evento,</li>
 *   <li>que agrupa y envia por lotes,</li>
 *   <li>que conserva los eventos mas relevantes al descartar.</li>
 * </ul>
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LogQueueTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    private AuditEvent event(AuditEvent.Severity severity, String action) {
        return new AuditEvent(
                Instant.now(), severity, "test", action, "target", true, Optional.empty());
    }

    @Test
    @DisplayName("La cola descarta con recuento al llenarse, en vez de crecer sin limite")
    void discardsWhenFull() {
        int maxSize = 3;
        List<LogQueue.LogBatch> batches = new CopyOnWriteArrayList<>();

        var queue = new LogQueue(maxSize, batches::add, LOGGER);
        // No arrancamos el scheduler; flusheamos manual

        // Llenar la cola
        queue.enqueue(event(AuditEvent.Severity.INFO, "a1"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "a2"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "a3"));
        assertEquals(3, queue.size(), "La cola debe estar llena");

        // Uno mas: debe descartar
        queue.enqueue(event(AuditEvent.Severity.INFO, "a4"));
        assertEquals(3, queue.size(), "La cola no debe crecer mas alla del maximo");
        assertTrue(queue.droppedCount() > 0, "Debe haber descartado al menos uno");
    }

    @Test
    @DisplayName("Conserva los eventos mas relevantes al descartar")
    void keepsMoreRelevantEvents() {
        int maxSize = 2;
        List<LogQueue.LogBatch> batches = new CopyOnWriteArrayList<>();

        var queue = new LogQueue(maxSize, batches::add, LOGGER);

        // Llenar con un ERROR y un WARNING
        queue.enqueue(event(AuditEvent.Severity.ERROR, "error1"));
        queue.enqueue(event(AuditEvent.Severity.WARNING, "warn1"));
        assertEquals(2, queue.size());

        // Intentar meter un INFO: debe descartar el INFO (no el ERROR ni el WARNING)
        queue.enqueue(event(AuditEvent.Severity.INFO, "info1"));
        assertEquals(2, queue.size());
        assertEquals(1, queue.droppedCount());

        // Intentar meter otro ERROR: debe descartar el WARNING (menos relevante que ERROR)
        queue.enqueue(event(AuditEvent.Severity.ERROR, "error2"));
        assertEquals(2, queue.size());
        assertEquals(2, queue.droppedCount());
    }

    @Test
    @DisplayName("enqueue nunca bloquea")
    void enqueueNeverBlocks() {
        int maxSize = 2;
        var queue = new LogQueue(maxSize, batch -> {
            // Consumidor lento para forzar que la cola se llene
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
        }, LOGGER);

        // No arrancamos el scheduler para que la cola no se vacia

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            queue.enqueue(event(AuditEvent.Severity.INFO, "evento-" + i));
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // 100 enqueues deben tomar menos de 1 segundo (sin bloqueo)
        assertTrue(elapsed < 1000,
                "enqueue no debe bloquear; tardo " + elapsed + "ms para 100 inserciones");
        assertEquals(2, queue.size(), "La cola debe estar en su maximo");
        assertTrue(queue.droppedCount() > 0, "Debe haber descartado");
    }

    @Test
    @DisplayName("El flush agrupa los eventos y reporta descartados")
    void flushGroupsAndReportsDropped() throws Exception {
        int maxSize = 5;
        List<LogQueue.LogBatch> batches = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        var queue = new LogQueue(maxSize, batch -> {
            batches.add(batch);
            latch.countDown();
        }, LOGGER);

        // Meter 3 eventos y 2 que se descarten
        queue.enqueue(event(AuditEvent.Severity.INFO, "e1"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "e2"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "e3"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "e4"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "e5"));
        // Cola llena, meter uno mas
        queue.enqueue(event(AuditEvent.Severity.WARNING, "w1"));

        // Flush con intervalo corto
        queue.start(Duration.ofMillis(100));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Debe haber hecho flush");
        queue.shutdown();

        assertFalse(batches.isEmpty(), "Debe haber al menos un batch");
        LogQueue.LogBatch first = batches.getFirst();
        assertTrue(first.events().size() <= maxSize, "No debe exceder el maximo");
        // Reporta los descartados
        assertTrue(first.droppedSinceLastFlush() >= 1,
                "Debe reportar al menos 1 evento descartado");
    }

    @Test
    @DisplayName("Si el sender falla, no bloquea ni crece sin limite")
    void senderFailureDoesNotBlock() {
        int maxSize = 5;
        var queue = new LogQueue(maxSize, batch -> {
            throw new RuntimeException("Discord esta caido");
        }, LOGGER);

        queue.enqueue(event(AuditEvent.Severity.INFO, "e1"));
        queue.enqueue(event(AuditEvent.Severity.INFO, "e2"));

        // Flush con intervalo muy corto, para que intente enviar
        queue.start(Duration.ofMillis(50));

        // Esperar un poco para que falle
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        // Meter mas eventos: no debe bloquear
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            queue.enqueue(event(AuditEvent.Severity.INFO, "post-error-" + i));
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        queue.shutdown();

        assertTrue(elapsed < 500,
                "enqueue no debe bloquear aunque el sender falle; tardo " + elapsed + "ms");
    }

    @Test
    @DisplayName("Tamano maximo cero o negativo lanza IllegalArgumentException")
    void invalidMaxSizeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new LogQueue(0, batch -> {}, LOGGER));
        assertThrows(IllegalArgumentException.class,
                () -> new LogQueue(-1, batch -> {}, LOGGER));
    }

    @Test
    @DisplayName("Cola vacia no genera batches")
    void emptyQueueNoFlush() throws Exception {
        List<LogQueue.LogBatch> batches = new CopyOnWriteArrayList<>();
        var queue = new LogQueue(10, batches::add, LOGGER);

        queue.start(Duration.ofMillis(50));
        Thread.sleep(200);
        queue.shutdown();

        assertTrue(batches.isEmpty(), "No debe haber batches si la cola esta vacia");
    }
}
