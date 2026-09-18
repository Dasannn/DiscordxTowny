package com.discordtowny.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * config.yml ya validado y tipado.
 *
 * <p>Se carga una sola vez al arrancar y en cada {@code /dt admin reload}.
 * Nadie lee del YamlConfiguration en caliente: si un valor hace falta, se
 * anade aqui.
 *
 * <p>Una configuracion invalida no se acepta: se rechaza diciendo que clave
 * esta mal y el plugin arranca degradado, en lugar de operar con basura.
 */
public record PluginConfig(
        Discord discord,
        Database database,
        Structure structure,
        Roles roles,
        Limits limits,
        Lifecycle lifecycle,
        Sync sync,
        Linking linking,
        Logging logging,
        Updates updates,
        Commands commands) {

    /**
     * El token es sensible: no lo incluyas en toString, en logs ni en mensajes
     * de error. Por eso este record lo sobreescribe.
     */
    public record Discord(String token, String guildId, Optional<String> logChannelId) {
        @Override
        public String toString() {
            return "Discord[guildId=" + guildId + ", token=OCULTO]";
        }
    }

    public record Database(
            Type type,
            String host,
            int port,
            String name,
            String user,
            String password,
            String tablePrefix,
            int poolMaximumSize,
            int poolMinimumIdle,
            Duration connectionTimeout) {

        public enum Type {
            MYSQL,
            MARIADB,
            SQLITE
        }

        @Override
        public String toString() {
            return "Database[type=" + type + ", host=" + host + ", name=" + name + ", password=OCULTA]";
        }
    }

    public record Structure(
            String categoryName,
            String archiveCategoryName,
            boolean createTextChannel,
            boolean createVoiceChannel,
            String textChannelName,
            String voiceChannelName) {}

    public record Roles(
            String mayorRoleName,
            String townRoleName,
            Optional<String> townRoleColor,
            boolean townRoleHoisted) {}

    public record Limits(int maxTowns, int minResidents, Duration creationCooldown) {}

    public record Lifecycle(Action onTownDeleted, Action onTownRuined, int archiveReminderDays) {
        public enum Action {
            ARCHIVE
        }
    }

    public record Sync(Duration interval, Mode mode, int batchSize, Duration batchPause) {
        public enum Mode {
            /** Aplica las correcciones. */
            REPAIR,
            /** Solo las registra. */
            REPORT
        }
    }

    public record Linking(
            Duration codeExpiry, int maxAttempts, Duration attemptLockout, boolean unlinkOnGuildLeave) {}

    public record Logging(Duration flushInterval, int queueSize, Detail detail) {
        public enum Detail {
            FULL,
            CHANGES,
            ERRORS
        }
    }

    public record Updates(
            boolean checkEnabled, Duration checkInterval, boolean autoDownload, boolean notifyAdminsOnJoin) {}

    public record Commands(Duration cooldown, List<DiscordCommand> commands) {

        public Optional<DiscordCommand> byName(String name) {
            return commands.stream().filter(c -> c.name().equals(name)).findFirst();
        }
    }

    public record DiscordCommand(String name, boolean enabled, boolean ephemeral) {}
}
