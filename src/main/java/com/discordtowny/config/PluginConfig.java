package com.discordtowny.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * config.yml already validated and typed.
 *
 * <p>Loaded once at startup and on every {@code /dt admin reload}.
 * Nobody reads from the live YamlConfiguration: if a value is needed, it is
 * added here.
 *
 * <p>An invalid configuration is not accepted: it is rejected indicating which
 * key is wrong and the plugin starts degraded, rather than operating with garbage.
 */
public record PluginConfig(
        String language,
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

    public PluginConfig {
        if (language == null || language.isBlank()) {
            language = "en";
        }
    }

    public PluginConfig(
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
        this("en", discord, database, structure, roles, limits, lifecycle, sync, linking, logging, updates, commands);
    }

    /**
     * The token is sensitive: do not include it in toString, logs, or error
     * messages. That is why this record overrides it.
     */
    public record Discord(
            String token,
            String guildId,
            Optional<String> logChannelId,
            Optional<String> linkChannelId) {

        public Discord(String token, String guildId, Optional<String> logChannelId) {
            this(token, guildId, logChannelId, Optional.empty());
        }

        @Override
        public String toString() {
            return "Discord[guildId=" + guildId + ", token=REDACTED]";
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
            return "Database[type=" + type + ", host=" + host + ", name=" + name + ", password=REDACTED]";
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
            /** Applies corrections. */
            REPAIR,
            /** Only logs them. */
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
