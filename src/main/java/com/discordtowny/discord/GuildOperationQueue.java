package com.discordtowny.discord;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cola serializada de mutaciones del guild.
 *
 * <p>Un solo consumidor, nada en paralelo. Cada operacion se ejecuta con
 * {@link GuildOperationExecutor}, y los fallos transitorios se reintentan con
 * espera creciente. Un fallo permanente corta la tarea.
 *
 * <p>El futuro devuelto por {@link #submit} nunca completa de forma
 * excepcional: los fallos viajan dentro de {@link OperationOutcome}.
 *
 * <p>Separada de JDA para poder probarse sin red.
 */
final class GuildOperationQueue {

    // -- Reintentos con espera creciente --
    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(2);
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    private final GuildOperationExecutor executor;
    private final Logger logger;
    private final BlockingQueue<QueuedOperation> queue;
    private final Thread consumerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final int maxRetries;
    private final Duration baseDelay;
    private final double backoffMultiplier;

    /** Elemento en la cola: la operacion mas el futuro que espera su resultado. */
    record QueuedOperation(GuildOperation operation, CompletableFuture<OperationOutcome> future) {}

    GuildOperationQueue(GuildOperationExecutor executor, Logger logger) {
        this(executor, logger, DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY, DEFAULT_BACKOFF_MULTIPLIER);
    }

    /** Constructor con parametros de reintentos, para tests. */
    GuildOperationQueue(GuildOperationExecutor executor, Logger logger,
                        int maxRetries, Duration baseDelay, double backoffMultiplier) {
        this.executor = executor;
        this.logger = logger;
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
        this.backoffMultiplier = backoffMultiplier;
        this.queue = new LinkedBlockingQueue<>();
        this.consumerThread = Thread.ofVirtual().name("dt-guild-queue").unstarted(this::consume);
    }

    /** Arranca el consumidor. Llamar una sola vez. */
    void start() {
        if (running.compareAndSet(false, true)) {
            consumerThread.start();
        }
    }

    /** Apaga la cola limpiamente, completando la operacion en curso. */
    void shutdown() {
        running.set(false);
        consumerThread.interrupt();
        // Completar los pendientes como fallo transitorio para que no se queden colgados
        QueuedOperation pending;
        while ((pending = queue.poll()) != null) {
            pending.future().complete(
                    OperationOutcome.transientFailure("Cola detenida: el plugin se esta apagando"));
        }
    }

    /**
     * Encola una operacion. No bloquea.
     *
     * <p>El futuro nunca completa de forma excepcional: los fallos viajan
     * dentro de {@link OperationOutcome}.
     */
    CompletableFuture<OperationOutcome> submit(GuildOperation operation) {
        if (operation == null) {
            return CompletableFuture.completedFuture(
                    OperationOutcome.permanentFailure("Operacion nula"));
        }
        var future = new CompletableFuture<OperationOutcome>();
        try {
            queue.offer(new QueuedOperation(operation, future));
        } catch (Exception e) {
            future.complete(OperationOutcome.transientFailure(
                    "Error al encolar operacion: " + e.getMessage()));
        }
        return future;
    }

    /** Visible solo para tests: cuantas operaciones esperan en la cola. */
    int pendingCount() {
        return queue.size();
    }

    // -- Consumidor --

    private void consume() {
        while (running.get()) {
            QueuedOperation item = null;
            try {
                item = queue.take();
                OperationOutcome outcome = executeWithRetries(item.operation());
                item.future().complete(outcome);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // El shutdown interrumpe al consumidor; simplemente salimos del bucle
                break;
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "[Cola] Error inesperado en el consumidor", t);
                if (item != null && !item.future().isDone()) {
                    item.future().complete(OperationOutcome.transientFailure(
                            "Fallo critico en ejecucion: " + t.getMessage()));
                }
            }
        }
    }

    private OperationOutcome executeWithRetries(GuildOperation operation) {
        int attempt = 0;
        OperationOutcome lastOutcome = null;

        while (attempt <= maxRetries) {
            if (!running.get()) {
                return OperationOutcome.transientFailure("Cola detenida durante reintento");
            }

            try {
                lastOutcome = executor.execute(operation);
                if (lastOutcome == null) {
                    lastOutcome = OperationOutcome.transientFailure(
                            "El ejecutor devolvio un resultado nulo");
                }
            } catch (Throwable t) {
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return OperationOutcome.transientFailure("Interrumpido durante ejecucion");
                }
                logger.warning("[Cola] Excepcion no controlada en '"
                        + operation.describe() + "': " + t.getMessage());
                lastOutcome = OperationOutcome.transientFailure(
                        "Excepcion: " + t.getMessage());
            }

            switch (lastOutcome.status()) {
                case SUCCESS -> {
                    return lastOutcome;
                }
                case PERMANENT_FAILURE -> {
                    logger.warning("[Cola] Fallo permanente en '" + operation.describe()
                            + "': " + lastOutcome.reason().orElse("sin detalle"));
                    return lastOutcome;
                }
                case TRANSIENT_FAILURE -> {
                    attempt++;
                    if (attempt > maxRetries) {
                        logger.warning("[Cola] Agotados los reintentos para '"
                                + operation.describe() + "': "
                                + lastOutcome.reason().orElse("sin detalle"));
                        return lastOutcome;
                    }
                    long delayMs = (long) (baseDelay.toMillis()
                            * Math.pow(backoffMultiplier, attempt - 1));
                    logger.info("[Cola] Reintento " + attempt + "/" + maxRetries
                            + " de '" + operation.describe()
                            + "' en " + delayMs + "ms");
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return OperationOutcome.transientFailure(
                                "Interrumpido durante espera de reintento");
                    }
                }
            }
        }
        // Nunca deberia llegar aqui, pero por seguridad
        return lastOutcome != null ? lastOutcome
                : OperationOutcome.transientFailure("Error inesperado en la cola");
    }
}
