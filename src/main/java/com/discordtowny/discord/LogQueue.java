package com.discordtowny.discord;

import com.discordtowny.model.AuditEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Cola de eventos de auditoria hacia el canal de logs de Discord.
 *
 * <p>Agrupa mensajes y los envia cada intervalo configurable en un solo lote.
 * Tiene tamano maximo: si se llena, descarta los eventos menos relevantes
 * (INFO antes que WARNING antes que ERROR) y anota cuantos se perdieron.
 *
 * <p><b>Nunca bloquea a quien produce el evento.</b> La llamada a
 * {@link #enqueue} regresa inmediatamente, incluso si la cola esta llena.
 *
 * <p>Separada de JDA: recibe un {@code Consumer<LogBatch>} como destino,
 * lo que permite probarla sin red.
 */
final class LogQueue {

    private final ArrayBlockingQueue<AuditEvent> buffer;
    private final int maxSize;
    private final Consumer<LogBatch> sender;
    private final Logger logger;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger droppedCount = new AtomicInteger(0);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean senderFailed = new AtomicBoolean(false);

    /** Lote de eventos listo para enviar. */
    record LogBatch(List<AuditEvent> events, int droppedSinceLastFlush) {}

    /**
     * @param maxSize     tamano maximo de la cola; los excedentes se descartan
     * @param sender      destino: recibe un lote y lo envia a Discord
     * @param logger      logger del plugin
     */
    LogQueue(int maxSize, Consumer<LogBatch> sender, Logger logger) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize debe ser positivo: " + maxSize);
        }
        this.maxSize = maxSize;
        this.buffer = new ArrayBlockingQueue<>(maxSize);
        this.sender = sender;
        this.logger = logger;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().name("dt-log-queue").unstarted(r);
            return t;
        });
    }

    /** Arranca el flush periodico. */
    void start(Duration flushInterval) {
        if (started.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(
                    this::flush,
                    flushInterval.toMillis(),
                    flushInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    /** Detiene el scheduler y hace un ultimo flush. */
    void shutdown() {
        scheduler.shutdown();
        flush();
    }

    /**
     * Encola un evento. No bloquea nunca.
     *
     * <p>Si la cola esta llena, descarta el evento menos relevante (INFO antes
     * que WARNING antes que ERROR) de entre los existentes, o el nuevo evento
     * si es el menos relevante, y anota cuantos se perdieron.
     */
    void enqueue(AuditEvent event) {
        boolean offered = buffer.offer(event);
        if (!offered) {
            // Cola llena: decidir que descartar
            discardLeastRelevant(event);
        }
    }

    /** Cuantos eventos se descartaron desde el ultimo flush. */
    int droppedCount() {
        return droppedCount.get();
    }

    /** Tamano actual de la cola. */
    int size() {
        return buffer.size();
    }

    // -- Descarte --

    private void discardLeastRelevant(AuditEvent incoming) {
        // Buscamos el evento menos relevante en la cola actual
        // Si el incoming es menos relevante que todos, lo descartamos a el
        // Si hay uno menos relevante, lo sacamos y metemos el incoming

        // Convertimos la cola a lista para inspeccionar
        List<AuditEvent> snapshot = new ArrayList<>(buffer);
        if (snapshot.isEmpty()) {
            // Entre tanto se vacio; intentamos de nuevo
            if (!buffer.offer(incoming)) {
                droppedCount.incrementAndGet();
            }
            return;
        }

        // Encontrar el de menor severidad
        AuditEvent leastRelevant = Collections.min(snapshot, SEVERITY_COMPARATOR);

        if (SEVERITY_COMPARATOR.compare(incoming, leastRelevant) > 0) {
            // El incoming es mas relevante: sacamos el menos relevante
            boolean removed = buffer.remove(leastRelevant);
            if (removed) {
                if (!buffer.offer(incoming)) {
                    // Concurrencia rara: no se pudo meter. Perdemos el incoming
                    droppedCount.incrementAndGet();
                }
            }
            droppedCount.incrementAndGet();
        } else {
            // El incoming es igual o menos relevante: lo descartamos a el
            droppedCount.incrementAndGet();
        }
    }

    /**
     * Compara por severidad: ERROR > WARNING > INFO.
     * Los de mayor severidad son "mas relevantes" y se conservan.
     */
    private static final Comparator<AuditEvent> SEVERITY_COMPARATOR =
            Comparator.comparingInt(e -> e.severity().ordinal());

    // -- Flush --

    private void flush() {
        if (buffer.isEmpty() && droppedCount.get() == 0) {
            return;
        }

        List<AuditEvent> batch = new ArrayList<>();
        buffer.drainTo(batch);
        int dropped = droppedCount.getAndSet(0);

        if (batch.isEmpty() && dropped == 0) {
            return;
        }

        try {
            sender.accept(new LogBatch(Collections.unmodifiableList(batch), dropped));
            senderFailed.set(false);
        } catch (Exception e) {
            // Un fallo al enviar a Discord no debe afectar nada.
            // Anotamos en consola una sola vez para no saturar.
            if (senderFailed.compareAndSet(false, true)) {
                logger.warning("[Logs] Error al enviar logs a Discord: " + e.getMessage()
                        + ". Los proximos errores se omiten hasta que funcione.");
            }
        }
    }
}
