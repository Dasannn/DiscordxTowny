package com.discordtowny.discord;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.link.LinkService;
import com.discordtowny.model.AuditEvent;
import com.discordtowny.storage.SettingsRepository;
import com.discordtowny.storage.SpaceRepository;
import com.discordtowny.towny.TownyFacade;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import java.awt.Color;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JDA implementation of {@link DiscordGateway}.
 *
 * <p>Connection with MINIMAL required intents. Clean startup and shutdown.
 * If there is no connection, {@link #isAvailable()} returns false and the rest
 * of the plugin can continue functioning.
 *
 * <p>Guild mutations pass through a serialized single-consumer queue
 * ({@link GuildOperationQueue}). Audit events go to a separate log queue
 * ({@link LogQueue}).
 */
public final class JdaDiscordGateway implements DiscordGateway {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private volatile PluginConfig config;
    private final SpaceRepository spaces;
    private final SettingsRepository settings;
    private final Logger logger;

    private final AtomicReference<JDA> jdaRef = new AtomicReference<>();
    private volatile Guild guild;
    private volatile JdaGuildOperationExecutor operationExecutor;
    private volatile GuildOperationQueue operationQueue;
    private volatile LogQueue logQueue;
    private volatile boolean available = false;

    private record PendingSlashCommandRegistration(
            TownyFacade townyFacade,
            LinkService linkService,
            Messages messages,
            Executor mainThreadExecutor
    ) {}

    private final AtomicReference<PendingSlashCommandRegistration> pendingCommands = new AtomicReference<>();
    private volatile LinkSlashCommands linkCommands;
    private volatile TownySlashCommands townyCommands;

    public JdaDiscordGateway(PluginConfig config, SpaceRepository spaces,
                             SettingsRepository settings, Logger logger) {
        this.config = config;
        this.spaces = spaces;
        this.settings = java.util.Objects.requireNonNull(settings, "settings cannot be null");
        this.logger = logger;
    }

    /** Test constructor with simulated connection. */
    JdaDiscordGateway(PluginConfig config, SpaceRepository spaces,
                      SettingsRepository settings, Logger logger,
                      JDA jda, Guild guild) {
        this.config = config;
        this.spaces = spaces;
        this.settings = java.util.Objects.requireNonNull(settings, "settings cannot be null");
        this.logger = logger;
        this.jdaRef.set(jda);
        this.guild = guild;
        this.available = true;
        if (guild != null) {
            this.operationExecutor = new JdaGuildOperationExecutor(guild, config, spaces, settings, logger);
        }
    }

    void initJdaForTest(JDA jda, Guild guild) {
        this.jdaRef.set(jda);
        this.guild = guild;
        this.available = true;
        if (guild != null && this.operationExecutor == null) {
            this.operationExecutor = new JdaGuildOperationExecutor(guild, config, spaces, settings, logger);
        }
        PendingSlashCommandRegistration pending = pendingCommands.getAndSet(null);
        if (pending != null) {
            performSlashCommandRegistration(pending);
        }
    }

    // -- Startup and shutdown --

    /**
     * Connects to Discord asynchronously off the main thread.
     *
     * <p>If it fails, does not throw an exception: leaves the gateway as unavailable.
     * The Minecraft server continues functioning without blocking startup.
     */
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(this::doConnect,
                r -> Thread.ofVirtual().name("dt-discord-connect").start(r));
    }

    private void doConnect() {
        try {
            // MINIMAL intents: only those we need
            // GUILD_MEMBERS to know who has which roles
            // GUILD_MESSAGES is not privileged; we need it for the log channel
            JDA jda = JDABuilder.createDefault(config.discord().token())
                    .enableIntents(EnumSet.of(
                            GatewayIntent.GUILD_MEMBERS,
                            GatewayIntent.GUILD_MESSAGES
                    ))
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .disableCache(EnumSet.of(
                            CacheFlag.VOICE_STATE,
                            CacheFlag.EMOJI,
                            CacheFlag.STICKER,
                            CacheFlag.SCHEDULED_EVENTS
                    ))
                    .build()
                    .awaitReady();

            jdaRef.set(jda);

            Guild g = jda.getGuildById(config.discord().guildId());
            if (g == null) {
                logger.severe("[Discord] Bot does not have access to configured guild: "
                        + config.discord().guildId());
                shutdown();
                return;
            }
            this.guild = g;

            // Operation queue with sanitizer
            var executor = new JdaGuildOperationExecutor(g, config, spaces, settings, logger);
            this.operationExecutor = executor;
            this.operationQueue = new GuildOperationQueue(executor, logger, this::sanitizeMessage);
            operationQueue.start();

            // Log queue with sanitizer
            int queueSize = config.logging().queueSize();
            this.logQueue = new LogQueue(
                    queueSize > 0 ? queueSize : 100,
                    this::sendLogBatch,
                    logger,
                    this::sanitizeMessage);
            logQueue.start(config.logging().flushInterval());

            available = true;
            logger.info("[Discord] Connected to guild '" + g.getName() + "'");

            // Check permissions off the main thread once connected
            verifyPermissions().ifPresent(msg ->
                    logger.warning("[Discord] Permissions warning: " + msg));

            PendingSlashCommandRegistration pending = pendingCommands.getAndSet(null);
            if (pending != null) {
                performSlashCommandRegistration(pending);
            }

        } catch (Exception e) {
            String safeMessage = sanitizeMessage(e.getMessage());
            logger.severe("[Discord] Failed to connect: " + safeMessage);
            available = false;
        }
    }

    /** Shuts down the connection cleanly. */
    public void shutdown() {
        available = false;
        pendingCommands.set(null);

        if (logQueue != null) {
            logQueue.shutdown();
        }

        if (operationQueue != null) {
            operationQueue.shutdown();
        }

        JDA jda = jdaRef.getAndSet(null);
        if (jda != null) {
            if (linkCommands != null) {
                try {
                    jda.removeEventListener(linkCommands);
                } catch (Throwable ignored) {}
                linkCommands = null;
            }
            if (townyCommands != null) {
                try {
                    jda.removeEventListener(townyCommands);
                } catch (Throwable ignored) {}
                townyCommands = null;
            }
            jda.shutdown();
            logger.info("[Discord] Connection closed");
        }
    }

    // -- DiscordGateway --

    @Override
    public boolean isAvailable() {
        return available && jdaRef.get() != null;
    }

    @Override
    public CompletableFuture<OperationOutcome> submit(GuildOperation operation) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(
                    OperationOutcome.transientFailure("Discord is unavailable"));
        }
        return operationQueue.submit(operation);
    }

    @Override
    public void log(AuditEvent event) {
        if (logQueue != null) {
            logQueue.enqueue(event);
        }
    }

    @Override
    public Optional<String> verifyPermissions() {
        if (!isAvailable()) {
            return Optional.of("Discord is not connected");
        }
        // Collect managed role IDs (town roles + mayor role)
        List<String> managedRoleIds = new java.util.ArrayList<>(spaces.findAll().stream()
                .flatMap(s -> s.roleId().stream())
                .toList());

        guild().ifPresent(g -> {
            Optional<String> persisted = settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID);
            if (persisted.isPresent()) {
                net.dv8tion.jda.api.entities.Role r = g.getRoleById(persisted.get());
                if (r != null) {
                    managedRoleIds.add(r.getId());
                }
                return;
            }
            for (net.dv8tion.jda.api.entities.Role r : g.getRolesByName(config.roles().mayorRoleName(), true)) {
                managedRoleIds.add(r.getId());
            }
        });

        return PermissionVerifier.verify(guild, managedRoleIds);
    }

    @Override
    public CompletableFuture<Optional<String>> verifyPermissionsAsync() {
        return CompletableFuture.supplyAsync(this::verifyPermissions);
    }

    @Override
    public Optional<String> mayorRoleId() {
        if (!isAvailable() || guild == null) {
            throw new IllegalStateException("Discord is unavailable");
        }

        // 1. Stable persisted identity
        Optional<String> persistedId = settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID);
        if (persistedId.isPresent()) {
            net.dv8tion.jda.api.entities.Role role = guild.getRoleById(persistedId.get());
            if (role != null) {
                return Optional.of(role.getId());
            }
            // Verified that role with persisted ID no longer exists in guild
            return Optional.empty();
        }

        // 2. If no persisted ID yet, query by name without adopting or persisting
        List<net.dv8tion.jda.api.entities.Role> roles = guild.getRolesByName(config.roles().mayorRoleName(), true);
        if (!roles.isEmpty()) {
            return Optional.of(roles.getFirst().getId());
        }

        // 3. Verified that it does not exist in guild
        return Optional.empty();
    }

    @Override
    public Set<String> roleHolders(String roleId) {
        if (!isAvailable() || guild == null) {
            throw new IllegalStateException("Discord is unavailable");
        }
        if (roleId == null || roleId.isBlank()) {
            return Collections.emptySet();
        }
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            return Collections.emptySet();
        }
        List<Member> members = guild.getMembersWithRoles(role);
        if (members == null || members.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> holders = new java.util.LinkedHashSet<>(members.size());
        for (Member member : members) {
            holders.add(member.getId());
        }
        return Collections.unmodifiableSet(holders);
    }

    @Override
    public Set<String> existingResourceIds(Collection<String> ids) {
        if (!isAvailable() || guild == null) {
            throw new IllegalStateException("Discord is unavailable");
        }
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> existing = new java.util.LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            boolean found = false;
            try {
                if (guild.getRoleById(id) != null) {
                    found = true;
                }
            } catch (Exception ignored) {
            }
            if (!found) {
                try {
                    if (guild.getGuildChannelById(id) != null) {
                        found = true;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!found) {
                try {
                    if (guild.getTextChannelById(id) != null || guild.getVoiceChannelById(id) != null) {
                        found = true;
                    }
                } catch (Exception ignored) {
                }
            }
            if (found) {
                existing.add(id);
            }
        }
        return Collections.unmodifiableSet(existing);
    }

    /** Returns the connected guild, if any. For package-internal use. */
    Optional<Guild> guild() {
        return Optional.ofNullable(guild);
    }

    // -- Sending logs to Discord --

    private void sendLogBatch(LogQueue.LogBatch batch) {
        Optional<String> channelId = config.discord().logChannelId();
        if (channelId.isEmpty() || !isAvailable()) {
            return;
        }

        TextChannel channel = guild.getTextChannelById(channelId.get());
        if (channel == null) {
            // Channel does not exist or is not accessible. Warn once (LogQueue
            // already handles the "log only once" logic).
            throw new IllegalStateException("Log channel not found: " + channelId.get());
        }

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("DiscordTowny — Log");
        embed.setColor(colorForBatch(batch));

        StringBuilder sb = new StringBuilder();
        for (AuditEvent event : batch.events()) {
            String icon = switch (event.severity()) {
                case INFO -> "\u2139\ufe0f";      // i emoji
                case WARNING -> "\u26a0\ufe0f"; // warning emoji
                case ERROR -> "\u274c";          // cross emoji
            };
            sb.append(icon).append(" **").append(event.action()).append("** ")
                    .append(event.target());
            event.detail().ifPresent(d -> sb.append(" — ").append(d));
            sb.append(" `").append(TIME_FORMAT.format(event.at())).append("`\n");

            // Discord embeds have a 4096-character limit
            if (sb.length() > 3800) {
                sb.append("... (truncated)\n");
                break;
            }
        }

        if (batch.droppedSinceLastFlush() > 0) {
            sb.append("\n\u26a0\ufe0f **").append(batch.droppedSinceLastFlush())
                    .append(" events dropped** due to full queue.");
        }

        embed.setDescription(sb.toString());
        channel.sendMessageEmbeds(embed.build()).queue();
    }

    private Color colorForBatch(LogQueue.LogBatch batch) {
        boolean hasError = batch.events().stream()
                .anyMatch(e -> e.severity() == AuditEvent.Severity.ERROR);
        boolean hasWarning = batch.events().stream()
                .anyMatch(e -> e.severity() == AuditEvent.Severity.WARNING);
        if (hasError) return Color.RED;
        if (hasWarning) return Color.ORANGE;
        return Color.GREEN;
    }

    /**
     * Removes the token from the error message, if it appears.
     * The token must NEVER appear in logs (P7 of the constitution).
     */
    String sanitizeMessage(String message) {
        return DiscordSanitizer.sanitize(message, config.discord().token());
    }

    @Override
    public void registerSlashCommands(
            TownyFacade townyFacade,
            LinkService linkService,
            Messages messages,
            Executor mainThreadExecutor) {
        PendingSlashCommandRegistration reg = new PendingSlashCommandRegistration(
                townyFacade, linkService, messages, mainThreadExecutor);
        if (isAvailable() && guild != null && jdaRef.get() != null) {
            performSlashCommandRegistration(reg);
        } else {
            pendingCommands.set(reg);
        }
    }

    private synchronized void performSlashCommandRegistration(PendingSlashCommandRegistration reg) {
        JDA jda = jdaRef.get();
        Guild g = this.guild;
        if (jda == null || g == null) {
            return;
        }

        if (this.linkCommands != null) {
            try {
                jda.removeEventListener(this.linkCommands);
            } catch (Throwable ignored) {}
        }
        if (this.townyCommands != null) {
            try {
                jda.removeEventListener(this.townyCommands);
            } catch (Throwable ignored) {}
        }

        Messages effectiveMessages = reg.messages() != null ? reg.messages() : com.discordtowny.minecraft.EnglishMessages.bundled();
        // The warning about an unusable link channel has to reach the operator, so it
        // goes to the plugin logger rather than to a logger nobody is watching.
        this.linkCommands = new LinkSlashCommands(
                reg.linkService(), config, effectiveMessages, logger::warning);
        this.townyCommands = new TownySlashCommands(reg.townyFacade(), reg.linkService(), config, effectiveMessages, reg.mainThreadExecutor());

        jda.addEventListener(this.linkCommands, this.townyCommands);

        List<SlashCommandData> commandData = new ArrayList<>();
        commandData.addAll(LinkSlashCommands.getCommandData(config));
        commandData.addAll(TownySlashCommands.getCommandData(config));

        try {
            var updateAction = g.updateCommands();
            if (updateAction != null) {
                updateAction.addCommands(commandData).queue(
                        cmds -> logger.info("[Discord] Published " + commandData.size() + " slash commands to guild '" + g.getName() + "'"),
                        t -> logger.warning("[Discord] Failed to publish slash commands: " + sanitizeMessage(t.getMessage()))
                );
            }
        } catch (Throwable t) {
            logger.warning("[Discord] Error updating guild slash commands: " + sanitizeMessage(t.getMessage()));
        }
    }

    @Override
    public Optional<LinkSlashCommands> linkSlashCommands() {
        return Optional.ofNullable(linkCommands);
    }

    @Override
    public Optional<TownySlashCommands> townySlashCommands() {
        return Optional.ofNullable(townyCommands);
    }

    @Override
    public synchronized void updateConfig(PluginConfig newConfig) {
        java.util.Objects.requireNonNull(newConfig, "newConfig cannot be null");
        PluginConfig oldConfig = this.config;
        this.config = newConfig;
        if (this.operationExecutor != null) {
            this.operationExecutor.updateConfig(newConfig);
        }
        if (this.linkCommands != null) {
            this.linkCommands.updateConfig(newConfig);
        }
        if (this.townyCommands != null) {
            this.townyCommands.updateConfig(newConfig);
        }
        PluginConfig.Logging oldLogging = oldConfig != null ? oldConfig.logging() : null;
        PluginConfig.Logging newLogging = newConfig.logging();
        if (this.logQueue != null && newLogging != null && !java.util.Objects.equals(oldLogging, newLogging)) {
            this.logQueue.shutdown();
            int queueSize = newLogging.queueSize();
            this.logQueue = new LogQueue(
                    queueSize > 0 ? queueSize : 100,
                    this::sendLogBatch,
                    logger,
                    this::sanitizeMessage);
            this.logQueue.start(newLogging.flushInterval());
        }
    }

    public PluginConfig getConfig() {
        return config;
    }

    public LinkSlashCommands getLinkSlashCommands() {
        return linkCommands;
    }

    public TownySlashCommands getTownySlashCommands() {
        return townyCommands;
    }

    JdaGuildOperationExecutor operationExecutor() {
        return operationExecutor;
    }

    LogQueue logQueue() {
        return logQueue;
    }

    void setLogQueueForTest(LogQueue logQueue) {
        this.logQueue = logQueue;
    }
}
