package com.discordtowny.discord;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.model.AuditEvent;
import com.discordtowny.storage.SpaceRepository;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import java.awt.Color;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementacion de {@link DiscordGateway} sobre JDA.
 *
 * <p>Conexion con los intents MINIMOS necesarios. Arranque y apagado limpios.
 * Si no hay conexion, {@link #isAvailable()} devuelve falso y el resto del
 * plugin puede seguir funcionando.
 *
 * <p>Las mutaciones del guild pasan por una cola serializada de un solo
 * consumidor ({@link GuildOperationQueue}). Los eventos de auditoria van a
 * una cola de logs separada ({@link LogQueue}).
 */
public final class JdaDiscordGateway implements DiscordGateway {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final PluginConfig config;
    private final SpaceRepository spaces;
    private final Logger logger;

    private final AtomicReference<JDA> jdaRef = new AtomicReference<>();
    private volatile Guild guild;
    private volatile GuildOperationQueue operationQueue;
    private volatile LogQueue logQueue;
    private volatile boolean available = false;

    public JdaDiscordGateway(PluginConfig config, SpaceRepository spaces, Logger logger) {
        this.config = config;
        this.spaces = spaces;
        this.logger = logger;
    }

    // -- Arranque y apagado --

    /**
     * Conecta con Discord. Bloquea hasta que JDA este listo.
     *
     * <p>Si falla, no lanza excepcion: deja el gateway como no disponible.
     * El servidor de Minecraft sigue funcionando.
     */
    public void connect() {
        try {
            // Intents MINIMOS: solo los que necesitamos
            // GUILD_MEMBERS para saber quien tiene que roles
            // GUILD_MESSAGES no es privilegiado; lo necesitamos para el canal de logs
            JDA jda = JDABuilder.createDefault(config.discord().token())
                    .enableIntents(EnumSet.of(
                            GatewayIntent.GUILD_MEMBERS,
                            GatewayIntent.GUILD_MESSAGES
                    ))
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
                logger.severe("[Discord] El bot no tiene acceso al guild configurado: "
                        + config.discord().guildId());
                shutdown();
                return;
            }
            this.guild = g;

            // Cola de operaciones
            var executor = new JdaGuildOperationExecutor(g, config, spaces, logger);
            this.operationQueue = new GuildOperationQueue(executor, logger);
            operationQueue.start();

            // Cola de logs
            int queueSize = config.logging().queueSize();
            this.logQueue = new LogQueue(
                    queueSize > 0 ? queueSize : 100,
                    this::sendLogBatch,
                    logger);
            logQueue.start(config.logging().flushInterval());

            available = true;
            logger.info("[Discord] Conectado al guild '" + g.getName() + "'");

        } catch (Exception e) {
            // No filtramos el token: el mensaje de JDA podria contenerlo
            String safeMessage = sanitizeMessage(e.getMessage());
            logger.severe("[Discord] No se pudo conectar: " + safeMessage);
            available = false;
        }
    }

    /** Apaga la conexion limpiamente. */
    public void shutdown() {
        available = false;

        if (logQueue != null) {
            logQueue.shutdown();
        }

        if (operationQueue != null) {
            operationQueue.shutdown();
        }

        JDA jda = jdaRef.getAndSet(null);
        if (jda != null) {
            jda.shutdown();
            logger.info("[Discord] Conexion cerrada");
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
                    OperationOutcome.transientFailure("Discord no esta disponible"));
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
            return Optional.of("Discord no esta conectado");
        }
        // Recopilar los IDs de roles gestionados (roles de towns + rol de alcalde)
        List<String> managedRoleIds = new java.util.ArrayList<>(spaces.findAll().stream()
                .flatMap(s -> s.roleId().stream())
                .toList());

        guild().ifPresent(g -> {
            for (net.dv8tion.jda.api.entities.Role r : g.getRolesByName(config.roles().mayorRoleName(), true)) {
                managedRoleIds.add(r.getId());
            }
        });

        return PermissionVerifier.verify(guild, managedRoleIds);
    }

    /** Devuelve el guild conectado, si lo hay. Para uso interno del paquete. */
    Optional<Guild> guild() {
        return Optional.ofNullable(guild);
    }

    // -- Envio de logs a Discord --

    private void sendLogBatch(LogQueue.LogBatch batch) {
        Optional<String> channelId = config.discord().logChannelId();
        if (channelId.isEmpty() || !isAvailable()) {
            return;
        }

        TextChannel channel = guild.getTextChannelById(channelId.get());
        if (channel == null) {
            // Canal no existe o no es accesible. Se avisa una vez (el LogQueue
            // ya maneja la logica de "registrar una sola vez").
            throw new IllegalStateException("Canal de logs no encontrado: " + channelId.get());
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

            // Discord embeds tienen limite de 4096 caracteres
            if (sb.length() > 3800) {
                sb.append("... (truncado)\n");
                break;
            }
        }

        if (batch.droppedSinceLastFlush() > 0) {
            sb.append("\n\u26a0\ufe0f **").append(batch.droppedSinceLastFlush())
                    .append(" eventos descartados** por cola llena.");
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
     * Elimina el token del mensaje de error, si aparece.
     * El token NUNCA debe aparecer en logs (P7 de la constitucion).
     */
    String sanitizeMessage(String message) {
        if (message == null) return "error desconocido";
        String token = config.discord().token();
        if (token != null && !token.isEmpty() && message.contains(token)) {
            return message.replace(token, "[TOKEN_OCULTO]");
        }
        return message;
    }
}
