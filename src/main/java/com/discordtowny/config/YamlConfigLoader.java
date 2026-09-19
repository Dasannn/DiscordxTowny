package com.discordtowny.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Explicit loading: each load reloads both files and publishes only if everything is valid.
 *
 * <p>The wiring provides the data folder and logger::warning. On startup or
 * reload, it must catch ConfigException and keep the plugin degraded if it
 * lacks a valid configuration. A rejected reload retains previous texts;
 * the consumer also retains its previous PluginConfig.
 *
 * <p>config-version is file metadata, not a PluginConfig field.
 */
public final class YamlConfigLoader implements ConfigLoader {
    private final Path dataFolder;
    private final Consumer<String> warning;
    private final Map<String, String> bundledEnglish;
    private volatile Messages messages;

    public YamlConfigLoader(Path dataFolder, Consumer<String> warning) {
        this.dataFolder = dataFolder;
        this.warning = warning;
        this.bundledEnglish = loadBundledEnglish();
        saveDefaultMessages();
        this.messages = new YamlMessages(Map.of(), bundledEnglish, "messages_en.yml", warning);
    }

    public void saveDefaultMessages() {
        saveDefaultFile("messages_en.yml");
        saveDefaultFile("messages_es.yml");
    }

    private void saveDefaultFile(String name) {
        Path target = dataFolder.resolve(name);
        if (Files.notExists(target)) {
            try {
                Files.createDirectories(dataFolder);
                try (var in = getClass().getResourceAsStream("/" + name)) {
                    if (in != null) {
                        Files.copy(in, target);
                    }
                }
            } catch (IOException ignored) {
            }
        }
    }

    private Map<String, String> loadBundledEnglish() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/messages_en.yml")) {
            if (in != null) {
                try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    yaml.load(reader);
                }
            }
        } catch (IOException | InvalidConfigurationException e) {
            // Bundled resource is valid and packaged in jar
        }
        Map<String, String> map = new HashMap<>();
        for (String key : yaml.getKeys(true)) {
            if (yaml.isString(key)) map.put(key, yaml.getString(key));
        }
        return Map.copyOf(map);
    }

    @Override
    public synchronized PluginConfig load() {
        saveDefaultMessages();
        ReadResult readResult = read();
        if (!readResult.problems.isEmpty()) {
            throw new ConfigException(String.join("; ", readResult.problems));
        }
        messages = new YamlMessages(readResult.texts, bundledEnglish, readResult.fileName, warning);
        return readResult.config;
    }

    @Override
    public Messages messages() {
        return messages;
    }

    @Override
    public List<String> validate() {
        return List.copyOf(read().problems);
    }

    private ReadResult read() {
        List<String> problems = new ArrayList<>();
        YamlConfiguration yaml = file("config.yml", problems);

        String language = "en";
        Object rawLang = yaml.get("language");
        if (rawLang != null) {
            if (rawLang instanceof String str && (str.equalsIgnoreCase("en") || str.equalsIgnoreCase("es"))) {
                language = str.toLowerCase(Locale.ROOT);
            } else {
                warning.accept("language: unrecognized value '" + rawLang + "', accepted languages: [en, es]; falling back to en");
                language = "en";
            }
        }

        String messageFileName = "messages_" + language + ".yml";
        YamlConfiguration texts = file(messageFileName, problems);
        Map<String, String> map = new HashMap<>();
        for (String key : texts.getKeys(true)) {
            if (texts.isString(key)) map.put(key, texts.getString(key));
        }
        PluginConfig config = new Values(yaml, problems, language).config();
        return new ReadResult(config, map, messageFileName, problems);
    }

    private YamlConfiguration file(String name, List<String> problems) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var reader = Files.newBufferedReader(dataFolder.resolve(name), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            // The parser may include lines with secrets: never attach its cause or its message.
            problems.add(name + ": expected a readable file with valid YAML syntax");
        }
        return yaml;
    }

    private record ReadResult(PluginConfig config, Map<String, String> texts, String fileName, List<String> problems) {}

    private static final class Values {
        private final YamlConfiguration yaml;
        private final List<String> problems;
        private final String language;

        private Values(YamlConfiguration yaml, List<String> problems, String language) {
            this.yaml = yaml;
            this.problems = problems;
            this.language = language;
        }

        private void require(boolean condition, String key, String expected) {
            if (!condition) problems.add(key + ": expected " + expected);
        }

        private String text(String key) {
            Object value = yaml.get(key);
            require(value instanceof String, key, "quoted text if it is a number");
            return value instanceof String str ? str : "";
        }

        private String nonEmpty(String key) {
            String value = text(key);
            require(!value.isBlank(), key, "non-empty text");
            return value;
        }

        private boolean bool(String key) {
            Object value = yaml.get(key);
            require(value instanceof Boolean, key, "true or false");
            return Boolean.TRUE.equals(value);
        }

        private int integer(String key, int min, int max) {
            Object value = yaml.get(key);
            boolean valid = (value instanceof Integer || value instanceof Long)
                    && ((Number) value).longValue() >= min && ((Number) value).longValue() <= max;
            require(valid, key, "an integer between " + min + " and " + max);
            return valid ? ((Number) value).intValue() : min;
        }

        private int positive(String key) {
            return integer(key, 1, Integer.MAX_VALUE);
        }

        private Duration seconds(String key) {
            return Duration.ofSeconds(positive(key));
        }

        private Duration minutes(String key) {
            return Duration.ofMinutes(positive(key));
        }

        private <E extends Enum<E>> E option(String key, Class<E> type) {
            String value = text(key).toUpperCase(Locale.ROOT);
            for (E opt : type.getEnumConstants()) {
                if (opt.name().equals(value)) return opt;
            }
            require(false, key, "one of " + Arrays.toString(type.getEnumConstants()));
            return type.getEnumConstants()[0];
        }

        private Optional<String> optional(String key) {
            String value = text(key);
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        }

        private String snowflake(String key, boolean optional) {
            String value = text(key);
            if (optional && value.isEmpty()) return value;
            boolean valid = value.matches("[1-9][0-9]{0,19}");
            if (valid) {
                try {
                    Long.parseUnsignedLong(value);
                } catch (NumberFormatException failure) {
                    valid = false;
                }
            }
            require(valid, key, "a positive decimal snowflake of up to 64 unsigned bits"
                    + (optional ? " or empty text" : ""));
            return value;
        }

        private String name(String key, boolean textChannel, String... placeholders) {
            String value = nonEmpty(key);
            String sample = value;
            for (String placeholder : placeholders) sample = sample.replace(placeholder, "x");
            require(!sample.contains("{") && !sample.contains("}"), key,
                    placeholders.length > 0 ? "only placeholders " + String.join(", ", placeholders) : "a name without placeholders");
            require(sample.length() >= 1 && sample.length() <= 100, key,
                    "a name of 1 to 100 characters, counting at least one per placeholder");
            require(sample.codePoints().noneMatch(Character::isISOControl), key, "a name without control characters");
            if (textChannel) {
                require(sample.matches("[\\p{Ll}\\p{Lo}\\p{M}\\p{N}_-]+"), key,
                        "lowercase letters, numbers, hyphens, or underscores outside placeholders");
            }
            // Real names must be validated again after substituting town and mayor when creating the channel.
            return value;
        }

        private PluginConfig config() {
            String token = nonEmpty("discord.token");
            require(!token.strip().equalsIgnoreCase("PON_AQUI_TU_TOKEN"), "discord.token", "your own token, not the example");
            String guild = snowflake("discord.guild-id", false);
            String log = snowflake("discord.log-channel-id", true);
            var discord = new PluginConfig.Discord(token, guild, log.isEmpty() ? Optional.empty() : Optional.of(log));

            var type = option("database.type", PluginConfig.Database.Type.class);
            String host = text("database.host");
            int port = integer("database.port", 1, 65535);
            String dbName = text("database.name");
            String user = text("database.user");
            String password = text("database.password");
            String prefix = text("database.table-prefix");
            require(prefix.matches("[A-Za-z_][A-Za-z0-9_]*") || prefix.isEmpty(), "database.table-prefix",
                    "an SQL prefix of letters, numbers, and underscores, not starting with a number, or empty");
            if (type != PluginConfig.Database.Type.SQLITE) {
                require(!host.isBlank(), "database.host", "a non-empty host");
                require(!dbName.isBlank(), "database.name", "a non-empty name");
                require(!user.isBlank(), "database.user", "a non-empty user");
            }
            int max = positive("database.pool.maximum-size");
            int min = integer("database.pool.minimum-idle", 0, max);
            var database = new PluginConfig.Database(type, host, port, dbName, user, password,
                    prefix, max, min, seconds("database.pool.connection-timeout-seconds"));

            var structure = new PluginConfig.Structure(name("structure.category-name", false),
                    name("structure.archive-category-name", false), bool("structure.create-text-channel"),
                    bool("structure.create-voice-channel"), name("structure.text-channel-name", true, "{town}", "{mayor}"),
                    name("structure.voice-channel-name", false, "{town}", "{mayor}"));
            Optional<String> color = optional("roles.town-role-color");
            require(color.isEmpty() || color.get().matches("#?[0-9a-fA-F]{6}"), "roles.town-role-color",
                    "a six-digit hexadecimal color or empty");
            var roles = new PluginConfig.Roles(name("roles.mayor-role-name", false),
                    name("roles.town-role-name", false, "{town}"), color, bool("roles.town-role-hoisted"));
            var limits = new PluginConfig.Limits(integer("limits.max-towns", 1, 240), positive("limits.min-residents"),
                    seconds("limits.creation-cooldown-seconds"));
            var lifecycle = new PluginConfig.Lifecycle(option("lifecycle.on-town-deleted", PluginConfig.Lifecycle.Action.class),
                    option("lifecycle.on-town-ruined", PluginConfig.Lifecycle.Action.class),
                    integer("lifecycle.archive-reminder-days", 0, Integer.MAX_VALUE));
            var sync = new PluginConfig.Sync(Duration.ofMinutes(integer("sync.interval-minutes", 0, Integer.MAX_VALUE)), option("sync.mode", PluginConfig.Sync.Mode.class),
                    positive("sync.batch-size"), seconds("sync.batch-pause-seconds"));
            var linking = new PluginConfig.Linking(minutes("linking.code-expiry-minutes"), positive("linking.max-attempts"),
                    minutes("linking.attempt-lockout-minutes"), bool("linking.unlink-on-guild-leave"));
            var logging = new PluginConfig.Logging(seconds("logging.flush-interval-seconds"), positive("logging.queue-size"),
                    option("logging.detail", PluginConfig.Logging.Detail.class));
            var updates = new PluginConfig.Updates(bool("updates.check-enabled"),
                    Duration.ofHours(positive("updates.check-interval-hours")), bool("updates.auto-download"),
                    bool("updates.notify-admins-on-join"));
            var commandsList = new ArrayList<PluginConfig.DiscordCommand>();
            for (String cmd : List.of("town", "residents", "res", "townlist", "mytown", "help")) {
                commandsList.add(new PluginConfig.DiscordCommand(cmd, bool("commands." + cmd + ".enabled"),
                        bool("commands." + cmd + ".ephemeral")));
            }
            var commands = new PluginConfig.Commands(seconds("commands.cooldown-seconds"), List.copyOf(commandsList));
            integer("config-version", 1, 1);
            return new PluginConfig(language, discord, database, structure, roles, limits, lifecycle, sync, linking, logging, updates, commands);
        }
    }
}
