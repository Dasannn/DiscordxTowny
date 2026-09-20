package com.discordtowny;

import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.model.AuditEvent;
import com.discordtowny.storage.AuditRepository;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Composed audit sink delivering events to both persistent storage and Discord.
 *
 * <p>Enforces the architectural invariants of T16:
 * <ul>
 *   <li><b>Sink composition in wiring:</b> {@code discord/} does not depend on {@code storage/}.
 *       The two sinks are composed here in the wiring layer.</li>
 *   <li><b>Fault isolation:</b> One sink failing (e.g. database down, Discord unreachable, or no log
 *       channel configured) does not prevent the other sink from processing the event.</li>
 *   <li><b>Off-main-thread execution:</b> Neither sink runs on the server main thread. Both sinks
 *       are dispatched to an asynchronous executor so that callers never block, and database writes
 *       never wait on Discord.</li>
 *   <li><b>Observable completion:</b> Callers and shutdown orchestrators can observe completion of the
 *       database write via {@link #record(AuditEvent)} and drain pending writes via {@link #drain(Duration)}.</li>
 * </ul>
 */
public final class CompositeAuditSink implements Consumer<AuditEvent>, AutoCloseable {

    private final Supplier<AuditRepository> auditRepositorySupplier;
    private final Supplier<DiscordGateway> discordGatewaySupplier;
    private final Executor executor;
    private final Logger logger;
    private final AtomicInteger pendingWrites = new AtomicInteger(0);

    public CompositeAuditSink(
            Supplier<AuditRepository> auditRepositorySupplier,
            Supplier<DiscordGateway> discordGatewaySupplier,
            Executor executor,
            Logger logger) {
        this.auditRepositorySupplier = Objects.requireNonNull(auditRepositorySupplier, "auditRepositorySupplier cannot be null");
        this.discordGatewaySupplier = Objects.requireNonNull(discordGatewaySupplier, "discordGatewaySupplier cannot be null");
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    public CompositeAuditSink(
            AuditRepository auditRepository,
            DiscordGateway discordGateway,
            Executor executor) {
        this(() -> auditRepository, () -> discordGateway, executor, Logger.getLogger("DiscordTowny"));
    }

    /**
     * Dispatches an audit event to persistent storage and Discord asynchronously,
     * returning an observable completion handle for the database write.
     *
     * <p>Invariants:
     * <ul>
     *   <li>Never blocks the calling thread (including the Paper server thread).</li>
     *   <li>Never makes database persistence wait on Discord.</li>
     *   <li>The returned future completes when database persistence completes, or completes
     *       exceptionally if database persistence failed.</li>
     * </ul>
     *
     * @param event the audit event to record
     * @return a completion future for the database write
     */
    public CompletableFuture<Void> record(AuditEvent event) {
        if (event == null) {
            return CompletableFuture.completedFuture(null);
        }

        pendingWrites.incrementAndGet();
        CompletableFuture<Void> dbFuture = new CompletableFuture<>();

        // Sink 1: Database persistence off the main thread
        try {
            executor.execute(() -> {
                try {
                    AuditRepository repo = auditRepositorySupplier.get();
                    if (repo != null) {
                        repo.record(event);
                    }
                    dbFuture.complete(null);
                } catch (Throwable t) {
                    safeLog(Level.WARNING, "Failed to persist audit event to database: " + t.getMessage(), t);
                    dbFuture.completeExceptionally(t);
                } finally {
                    pendingWrites.decrementAndGet();
                }
            });
        } catch (Throwable t) {
            pendingWrites.decrementAndGet();
            safeLog(Level.WARNING, "Failed to dispatch audit event to database executor: " + t.getMessage(), t);
            dbFuture.completeExceptionally(t);
        }

        // Sink 2: Discord log channel off the main thread (independent of database future)
        try {
            executor.execute(() -> {
                try {
                    DiscordGateway gateway = discordGatewaySupplier.get();
                    if (gateway != null) {
                        gateway.log(event);
                    }
                } catch (Throwable t) {
                    safeLog(Level.WARNING, "Failed to forward audit event to Discord: " + t.getMessage(), t);
                }
            });
        } catch (Throwable t) {
            safeLog(Level.WARNING, "Failed to dispatch audit event to Discord executor: " + t.getMessage(), t);
        }

        return dbFuture;
    }

    /**
     * Alias for {@link #record(AuditEvent)}.
     *
     * @param event the audit event to record
     * @return a completion future for the database write
     */
    public CompletableFuture<Void> submit(AuditEvent event) {
        return record(event);
    }

    @Override
    public void accept(AuditEvent event) {
        record(event);
    }

    /**
     * Bounded drain waiting for all pending audit writes to complete.
     *
     * <p>If the executor is an {@link ExecutorService}, it initiates shutdown and awaits
     * termination within the given timeout. If the wait expires, remaining tasks are cancelled
     * via {@link ExecutorService#shutdownNow()} and a warning is logged reporting the exact
     * count of dropped audit events.
     *
     * @param timeout maximum duration to wait for pending writes to drain
     * @return true if drained completely within timeout, false if wait expired
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean drain(Duration timeout) throws InterruptedException {
        long timeoutMs = timeout != null ? timeout.toMillis() : 3000L;
        if (executor instanceof ExecutorService es) {
            es.shutdown();
            boolean terminated = es.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
            if (!terminated) {
                // Read the count BEFORE shutting down: shutdownNow interrupts the running
                // write, whose finally block decrements the counter. Reading afterwards can
                // report zero dropped rows while one was in fact lost.
                int droppedEvents = pendingWrites.get();
                List<Runnable> dropped = es.shutdownNow();
                safeLog(Level.WARNING, "Timed out waiting for audit log to drain: "
                        + droppedEvents + " queued audit event(s) were dropped.");
                return false;
            }
            return true;
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (pendingWrites.get() > 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                int dropped = pendingWrites.get();
                safeLog(Level.WARNING, "Timed out waiting for audit log to drain: "
                        + dropped + " queued audit event(s) were dropped.");
                return false;
            }
            Thread.sleep(Math.min(50L, TimeUnit.NANOSECONDS.toMillis(remaining) + 1));
        }
        return true;
    }

    public int pendingCount() {
        return pendingWrites.get();
    }

    @Override
    public void close() {
        try {
            drain(Duration.ofSeconds(3));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            safeLog(Level.WARNING, "Interrupted while closing audit sink: " + e.getMessage());
        }
    }

    private void safeLog(Level level, String message, Throwable t) {
        try {
            if (logger != null) {
                logger.log(level, message, t);
            }
        } catch (Throwable ignored) {}
    }

    private void safeLog(Level level, String message) {
        try {
            if (logger != null) {
                logger.log(level, message);
            }
        } catch (Throwable ignored) {}
    }
}
