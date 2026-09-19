package com.discordtowny;

import com.discordtowny.config.ConfigException;
import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.config.YamlConfigLoader;
import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.discord.JdaDiscordGateway;
import com.discordtowny.link.DefaultLinkService;
import com.discordtowny.link.LinkService;
import com.discordtowny.minecraft.EnglishMessages;
import com.discordtowny.space.DefaultSpaceService;
import com.discordtowny.space.SpaceService;
import com.discordtowny.storage.HikariStorage;
import com.discordtowny.storage.Storage;
import com.discordtowny.sync.DefaultSyncService;
import com.discordtowny.sync.PeriodicSyncJob;
import com.discordtowny.sync.SyncScheduler;
import com.discordtowny.sync.SyncService;
import com.discordtowny.towny.LiveTownyFacade;
import com.discordtowny.towny.TownyFacade;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Component wiring and lifecycle orchestration decoupled from Bukkit.
 *
 * <p>Its sole responsibility is lifecycle orchestration: creating components in dependency
 * order, making them available, and shutting them down in reverse. Functional logic lives in
 * domain packages, never here.
 *
 * <p>Principles enforced:
 * <ul>
 *   <li><b>P4: Main thread is sacred.</b> {@link DefaultSyncService} is supplied with a main-thread
 *       executor returning to the server thread, ensuring {@link LiveTownyFacade} reads safely.</li>
 *   <li><b>P9: Fail visibly and safely.</b> Without a database the plugin does not operate on
 *       Discord; it degrades visibly and avoids mutating the guild when results cannot be persisted.</li>
 *   <li><b>Fault-tolerant shutdown:</b> {@link #stop()} tolerates an incomplete startup and never throws.</li>
 * </ul>
 */
public final class DiscordTownyWiring {

    private final Path dataFolder;
    private final Logger logger;
    private final Executor mainThreadExecutor;
    private final SyncScheduler syncScheduler;
    private final Runnable cancelTasksAction;
    private final Consumer<DiscordTownyWiring> postStartAction;
    private final String version;

    private YamlConfigLoader configLoader;
    private PluginConfig config;
    private Messages messages;
    private Messages consoleMessages;

    private Storage storage;
    private JdaDiscordGateway discordGateway;
    private TownyFacade townyFacade;
    private SpaceService spaceService;
    private SyncService syncService;
    private LinkService linkService;
    private PeriodicSyncJob periodicSyncJob;

    private boolean degraded = false;
    private volatile boolean stopped = false;
    private CompletableFuture<Void> startupFuture;
    private CompletableFuture<Void> discordConnectFuture;

    public DiscordTownyWiring(
            Path dataFolder,
            Logger logger,
            Executor mainThreadExecutor,
            SyncScheduler syncScheduler,
            Runnable cancelTasksAction,
            Consumer<DiscordTownyWiring> postStartAction,
            String version) {
        this.dataFolder = dataFolder;
        this.logger = logger != null ? logger : Logger.getLogger("DiscordTowny");
        this.mainThreadExecutor = mainThreadExecutor;
        this.syncScheduler = syncScheduler;
        this.cancelTasksAction = cancelTasksAction;
        this.postStartAction = postStartAction;
        this.version = version != null ? version : "unknown";
    }

    public DiscordTownyWiring() {
        this(null, Logger.getLogger("DiscordTowny"), null, null, null, null, "1.0.0");
    }

    public synchronized void start() {
        this.stopped = false;

        // 1. Configuration loading
        if (dataFolder != null) {
            configLoader = new YamlConfigLoader(dataFolder, msg -> safeLog(Level.WARNING, msg));
            try {
                config = configLoader.load();
            } catch (ConfigException e) {
                safeLog(Level.SEVERE, "Invalid configuration: " + e.getMessage());
                safeLog(Level.SEVERE, "Plugin starting in degraded mode.");
                degraded = true;
            }

            messages = configLoader.messages();
        }
        consoleMessages = EnglishMessages.bundled();

        // 2. Towny Facade
        if (townyFacade == null) {
            townyFacade = new LiveTownyFacade(msg -> safeLog(Level.WARNING, msg));
        }

        // If degraded or no config, complete immediately without blocking
        if (degraded || config == null) {
            dispatchPostStart();
            startupFuture = CompletableFuture.completedFuture(null);
            safeLog(Level.INFO, "DiscordTowny " + version + " started in degraded mode.");
            return;
        }

        // 3. Storage and domain services initialized asynchronously off the main thread (P4)
        startupFuture = CompletableFuture.runAsync(this::initializeServicesAsync,
                r -> Thread.ofVirtual().name("dt-startup").start(r));
    }

    private void initializeServicesAsync() {
        synchronized (this) {
            if (stopped) {
                return;
            }
        }

        // Initialize Storage off the main thread
        Storage newStorage = null;
        try {
            if (this.storage == null) {
                newStorage = new HikariStorage(config.database(), logger, dataFolder);
                newStorage.initialize();
            } else {
                newStorage = this.storage;
            }
        } catch (RuntimeException e) {
            safeLog(Level.SEVERE, "Database initialization failed: " + e.getMessage());
            safeLog(Level.SEVERE, "Plugin starting in degraded mode: database and Discord operations disabled.");
            if (newStorage != null) {
                try {
                    newStorage.close();
                } catch (Throwable ignored) {}
            }
            synchronized (this) {
                this.storage = null;
                this.degraded = true;
            }
            dispatchPostStart();
            return;
        }

        synchronized (this) {
            if (stopped) {
                if (newStorage != null && newStorage != this.storage) {
                    try {
                        newStorage.close();
                    } catch (Throwable ignored) {}
                }
                return;
            }
            this.storage = newStorage;
        }

        // 4. Discord Gateway: only when storage succeeded
        try {
            synchronized (this) {
                if (!stopped && discordGateway == null) {
                    discordGateway = new JdaDiscordGateway(config, storage.spaces(), storage.settings(), logger);
                    discordConnectFuture = discordGateway.connect();
                    discordConnectFuture.whenComplete((v, t) -> {
                        synchronized (this) {
                            if (stopped && discordGateway != null) {
                                try {
                                    discordGateway.shutdown();
                                } catch (Throwable ignored) {}
                                discordGateway = null;
                            }
                        }
                    });
                }
            }
        } catch (Throwable t) {
            safeLog(Level.SEVERE, "Failed to start Discord gateway: " + t.getMessage());
        }

        // 5. Domain Services
        synchronized (this) {
            if (stopped) {
                return;
            }
            DiscordGateway effectiveGateway = discordGateway != null ? discordGateway : DegradedDiscordGateway.INSTANCE;

            spaceService = new DefaultSpaceService(storage.spaces(), config, effectiveGateway);
            syncService = new DefaultSyncService(
                    spaceService,
                    storage.spaces(),
                    storage.links(),
                    effectiveGateway,
                    townyFacade,
                    config,
                    Clock.systemUTC(),
                    ForkJoinPool.commonPool(),
                    null,
                    mainThreadExecutor
            );
            linkService = new DefaultLinkService(
                    storage.links(),
                    config,
                    effectiveGateway,
                    townyFacade,
                    storage.spaces(),
                    syncService,
                    Clock.systemUTC(),
                    ForkJoinPool.commonPool()
            );

            // 6. Periodic Sync Job
            if (syncScheduler != null) {
                try {
                    periodicSyncJob = new PeriodicSyncJob(syncService, () -> config, syncScheduler, logger);
                    periodicSyncJob.start();
                } catch (Throwable t) {
                    safeLog(Level.WARNING, "Failed to start periodic sync job: " + t.getMessage());
                }
            }
        }

        // 7. Safely return to the server thread for registration
        dispatchPostStart();
    }

    private void dispatchPostStart() {
        Runnable returnAction = () -> {
            synchronized (this) {
                if (stopped) {
                    return;
                }
                if (postStartAction != null) {
                    try {
                        postStartAction.accept(this);
                    } catch (Throwable t) {
                        safeLog(Level.SEVERE, "Failed to register components: " + t.getMessage());
                    }
                }
                safeLog(Level.INFO, "DiscordTowny " + version + " started.");
            }
        };

        if (mainThreadExecutor != null) {
            try {
                mainThreadExecutor.execute(returnAction);
            } catch (Throwable t) {
                safeLog(Level.WARNING, "Could not return to main thread for post-start action: " + t.getMessage());
            }
        } else {
            returnAction.run();
        }
    }

    public synchronized void stop() {
        this.stopped = true;

        if (startupFuture != null && !startupFuture.isDone()) {
            startupFuture.cancel(true);
        }

        if (discordConnectFuture != null && !discordConnectFuture.isDone()) {
            discordConnectFuture.cancel(true);
        }

        // Tolerates incomplete startup: shutdown in reverse order, null-safe

        // 1. Periodic sync job
        if (periodicSyncJob != null) {
            try {
                periodicSyncJob.stop();
            } catch (Throwable t) {
                safeLog(Level.WARNING, "Error stopping periodic sync job: " + t.getMessage());
            }
            periodicSyncJob = null;
        }

        // 2. Pending scheduler tasks
        if (cancelTasksAction != null) {
            try {
                cancelTasksAction.run();
            } catch (Throwable ignored) {}
        }

        // 3. Discord gateway
        if (discordGateway != null) {
            try {
                discordGateway.shutdown();
            } catch (Throwable t) {
                safeLog(Level.WARNING, "Error shutting down Discord gateway: " + t.getMessage());
            }
            discordGateway = null;
        }

        // 4. Database storage
        if (storage != null) {
            try {
                storage.close();
            } catch (Throwable t) {
                safeLog(Level.WARNING, "Error closing database storage: " + t.getMessage());
            }
            storage = null;
        }

        spaceService = null;
        syncService = null;
        linkService = null;
        townyFacade = null;
        config = null;
        messages = null;
        consoleMessages = null;
        configLoader = null;
        degraded = false;

        safeLog(Level.INFO, "DiscordTowny stopped.");
    }

    public void onEnable() {
        start();
    }

    public void onDisable() {
        stop();
    }

    public synchronized void reload() {
        if (configLoader != null) {
            PluginConfig oldConfig = this.config;
            PluginConfig newConfig;
            try {
                newConfig = configLoader.load();
            } catch (ConfigException e) {
                safeLog(Level.SEVERE, "Invalid configuration during reload: " + e.getMessage());
                throw e;
            }

            this.config = newConfig;
            this.messages = configLoader.messages();
            this.consoleMessages = EnglishMessages.bundled();

            if (oldConfig != null) {
                if (!oldConfig.discord().token().equals(newConfig.discord().token())
                        || !oldConfig.discord().guildId().equals(newConfig.discord().guildId())) {
                    safeLog(Level.WARNING, "Changes to Discord bot token or guild ID require a server restart to take effect.");
                }
                if (!oldConfig.database().equals(newConfig.database())) {
                    safeLog(Level.WARNING, "Changes to database configuration require a server restart to take effect.");
                }
            }

            // Attempt recovery from degraded mode if storage was uninitialized
            if (degraded && storage == null) {
                try {
                    storage = new HikariStorage(newConfig.database(), logger, dataFolder);
                    storage.initialize();
                    degraded = false;
                    safeLog(Level.INFO, "Recovered from degraded mode: database connection established.");
                } catch (RuntimeException e) {
                    safeLog(Level.SEVERE, "Database initialization failed during reload: " + e.getMessage());
                    if (storage != null) {
                        try {
                            storage.close();
                        } catch (Throwable ignored) {}
                        storage = null;
                    }
                    degraded = true;
                }
            }

            // When storage is ready, genuinely update the domain services and reschedule the periodic job
            if (!degraded && storage != null) {
                if (discordGateway == null) {
                    try {
                        discordGateway = new JdaDiscordGateway(newConfig, storage.spaces(), storage.settings(), logger);
                        discordConnectFuture = discordGateway.connect();
                        discordConnectFuture.whenComplete((v, t) -> {
                            synchronized (this) {
                                if (stopped && discordGateway != null) {
                                    try {
                                        discordGateway.shutdown();
                                    } catch (Throwable ignored) {}
                                    discordGateway = null;
                                }
                            }
                        });
                    } catch (Throwable t) {
                        safeLog(Level.SEVERE, "Failed to start Discord gateway during reload: " + t.getMessage());
                    }
                }

                DiscordGateway effectiveGateway = discordGateway != null ? discordGateway : DegradedDiscordGateway.INSTANCE;

                this.spaceService = new DefaultSpaceService(storage.spaces(), newConfig, effectiveGateway);
                this.syncService = new DefaultSyncService(
                        spaceService,
                        storage.spaces(),
                        storage.links(),
                        effectiveGateway,
                        townyFacade,
                        newConfig,
                        Clock.systemUTC(),
                        ForkJoinPool.commonPool(),
                        null,
                        mainThreadExecutor
                );
                this.linkService = new DefaultLinkService(
                        storage.links(),
                        newConfig,
                        effectiveGateway,
                        townyFacade,
                        storage.spaces(),
                        syncService,
                        Clock.systemUTC(),
                        ForkJoinPool.commonPool()
                );

                if (periodicSyncJob != null) {
                    try {
                        periodicSyncJob.stop();
                    } catch (Throwable t) {
                        safeLog(Level.WARNING, "Error stopping periodic sync job during reload: " + t.getMessage());
                    }
                    periodicSyncJob = null;
                }
                if (syncScheduler != null) {
                    try {
                        periodicSyncJob = new PeriodicSyncJob(syncService, () -> this.config, syncScheduler, logger);
                        periodicSyncJob.start();
                    } catch (Throwable t) {
                        safeLog(Level.WARNING, "Failed to reschedule periodic sync job during reload: " + t.getMessage());
                    }
                }
            }

            safeLog(Level.INFO, "Configuration and messages reloaded.");
        }
    }

    private void safeLog(Level level, String message) {
        try {
            if (logger != null) {
                logger.log(level, message);
            }
        } catch (Throwable ignored) {}
    }

    // Accessors for diagnostics and testing

    public Storage getStorage() {
        return storage;
    }

    public JdaDiscordGateway getDiscordGateway() {
        return discordGateway;
    }

    public SpaceService getSpaceService() {
        return spaceService;
    }

    public SyncService getSyncService() {
        return syncService;
    }

    public LinkService getLinkService() {
        return linkService;
    }

    public TownyFacade getTownyFacade() {
        return townyFacade;
    }

    public PeriodicSyncJob getPeriodicSyncJob() {
        return periodicSyncJob;
    }

    public PluginConfig getConfig() {
        return config;
    }

    public Messages getMessages() {
        return messages;
    }

    public Messages getConsoleMessages() {
        return consoleMessages;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public CompletableFuture<Void> getStartupFuture() {
        return startupFuture;
    }

    public CompletableFuture<Void> getDiscordConnectFuture() {
        return discordConnectFuture;
    }

    // Package-private test setters for testing shutdown after failed startup
    void setPeriodicSyncJobForTest(PeriodicSyncJob job) {
        this.periodicSyncJob = job;
    }

    void setDiscordGatewayForTest(JdaDiscordGateway gateway) {
        this.discordGateway = gateway;
    }

    void setStorageForTest(Storage storage) {
        this.storage = storage;
    }

    void setTownyFacadeForTest(TownyFacade facade) {
        this.townyFacade = facade;
    }

    void setConfigLoaderForTest(YamlConfigLoader loader) {
        this.configLoader = loader;
    }

    void setConfigForTest(PluginConfig config) {
        this.config = config;
    }

    /**
     * Minimal fallback gateway used when Discord cannot connect or is disabled.
     */
    private static final class DegradedDiscordGateway implements DiscordGateway {
        static final DegradedDiscordGateway INSTANCE = new DegradedDiscordGateway();

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public java.util.concurrent.CompletableFuture<com.discordtowny.discord.OperationOutcome> submit(
                com.discordtowny.discord.GuildOperation operation) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    com.discordtowny.discord.OperationOutcome.transientFailure("Discord is unavailable"));
        }

        @Override
        public void log(com.discordtowny.model.AuditEvent event) {}

        @Override
        public java.util.Optional<String> verifyPermissions() {
            return java.util.Optional.of("Discord is not connected");
        }

        @Override
        public java.util.Optional<String> mayorRoleId() {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Set<String> roleHolders(String roleId) {
            return java.util.Set.of();
        }

        @Override
        public java.util.Set<String> existingResourceIds(java.util.Collection<String> ids) {
            return java.util.Set.of();
        }
    }
}
