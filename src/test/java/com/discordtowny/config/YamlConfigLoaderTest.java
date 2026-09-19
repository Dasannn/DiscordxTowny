package com.discordtowny.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class YamlConfigLoaderTest {
    private static final String TOKEN = "token-secreto-de-prueba";
    private static final String PASSWORD = "password-secreto-de-prueba";
    @TempDir Path folder;
    private YamlConfiguration yaml;
    private YamlConfigLoader loader;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void prepare() throws Exception {
        try (var config = getClass().getResourceAsStream("/config.yml");
             var messagesEn = getClass().getResourceAsStream("/messages_en.yml");
             var messagesEs = getClass().getResourceAsStream("/messages_es.yml")) {
            Files.copy(config, folder.resolve("config.yml"));
            Files.copy(messagesEn, folder.resolve("messages_en.yml"));
            Files.copy(messagesEs, folder.resolve("messages_es.yml"));
        }
        yaml = new YamlConfiguration();
        yaml.load(folder.resolve("config.yml").toFile());
        yaml.set("discord.token", TOKEN);
        yaml.set("discord.guild-id", "123456789012345678");
        yaml.set("database.password", PASSWORD);
        save();
        loader = new YamlConfigLoader(folder, warnings::add);
    }

    private void save() throws Exception {
        yaml.save(folder.resolve("config.yml").toFile());
    }

    @Test
    void loadsAllBlocksAndDoesNotExposeSecrets() {
        assertTrue(loader.validate().isEmpty());
        PluginConfig config = loader.load();
        assertEquals("en", config.language());
        assertEquals(TOKEN, config.discord().token());
        assertTrue(config.discord().logChannelId().isEmpty());
        assertEquals(PluginConfig.Database.Type.SQLITE, config.database().type());
        assertEquals(PASSWORD, config.database().password());
        assertEquals(3306, config.database().port());
        assertEquals("dt_", config.database().tablePrefix());
        assertEquals(10, config.database().poolMaximumSize());
        assertEquals(2, config.database().poolMinimumIdle());
        assertEquals(Duration.ofSeconds(10), config.database().connectionTimeout());
        assertEquals("Comunidades", config.structure().categoryName());
        assertTrue(config.structure().createTextChannel());
        assertTrue(config.structure().createVoiceChannel());
        assertEquals("{town}", config.structure().textChannelName());
        assertEquals("Alcalde", config.roles().mayorRoleName());
        assertEquals(200, config.limits().maxTowns());
        assertEquals(Duration.ofSeconds(60), config.limits().creationCooldown());
        assertEquals(PluginConfig.Lifecycle.Action.ARCHIVE, config.lifecycle().onTownRuined());
        assertEquals(Duration.ofMinutes(30), config.sync().interval());
        assertEquals(PluginConfig.Sync.Mode.REPAIR, config.sync().mode());
        assertEquals(Duration.ofMinutes(10), config.linking().codeExpiry());
        assertEquals(500, config.logging().queueSize());
        assertEquals(Duration.ofHours(12), config.updates().checkInterval());
        assertEquals(6, config.commands().commands().size());
        assertTrue(config.commands().byName("mytown").orElseThrow().ephemeral());
        assertEquals(Duration.ofSeconds(5), config.commands().cooldown());
        assertThrows(UnsupportedOperationException.class, () -> config.commands().commands().clear());
        for (String dump : List.of(config.toString(), config.discord().toString(), config.database().toString())) {
            assertFalse(dump.contains(TOKEN));
            assertFalse(dump.contains(PASSWORD));
        }
    }

    static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of("discord.token", ""),
                Arguments.of("discord.token", " PON_AQUI_TU_TOKEN "),
                Arguments.of("discord.token", 123),
                Arguments.of("discord.guild-id", ""),
                Arguments.of("discord.guild-id", "abc"),
                Arguments.of("discord.guild-id", "0"),
                Arguments.of("discord.guild-id", "-123"),
                Arguments.of("discord.guild-id", "18446744073709551616"),
                Arguments.of("discord.guild-id", "012345678901234567"),
                Arguments.of("discord.guild-id", 123456789012345678L),
                Arguments.of("discord.log-channel-id", "canal"),
                Arguments.of("discord.log-channel-id", " "),
                Arguments.of("database.type", "postgres"),
                Arguments.of("database.port", 65536),
                Arguments.of("database.table-prefix", "dt_; DROP TABLE links"),
                Arguments.of("database.pool.minimum-idle", -1),
                Arguments.of("database.pool.minimum-idle", 11),
                Arguments.of("limits.max-towns", 241),
                Arguments.of("limits.max-towns", 0),
                Arguments.of("limits.max-towns", 1.5),
                Arguments.of("limits.max-towns", "200"),
                Arguments.of("limits.max-towns", Long.MAX_VALUE),
                Arguments.of("structure.text-channel-name", "{desconocido}"),
                Arguments.of("structure.text-channel-name", "{town"),
                Arguments.of("structure.text-channel-name", "town}"),
                Arguments.of("structure.text-channel-name", ""),
                Arguments.of("structure.text-channel-name", "Town {town}"),
                Arguments.of("structure.text-channel-name", "town!"),
                Arguments.of("structure.text-channel-name", "x".repeat(100) + "{town}"),
                Arguments.of("structure.voice-channel-name", "{otro}"),
                Arguments.of("structure.voice-channel-name", "voz\n{town}"),
                Arguments.of("structure.category-name", "x".repeat(101)),
                Arguments.of("structure.archive-category-name", " "),
                Arguments.of("roles.town-role-name", "{otro}"),
                Arguments.of("roles.mayor-role-name", ""),
                Arguments.of("roles.town-role-color", "rojo"),
                Arguments.of("lifecycle.on-town-deleted", "delete"),
                Arguments.of("lifecycle.on-town-ruined", "purge"),
                Arguments.of("lifecycle.archive-reminder-days", -1),
                Arguments.of("sync.mode", "otro"),
                Arguments.of("sync.interval-minutes", -1),
                Arguments.of("roles.town-role-name", "{mayor}"),
                Arguments.of("logging.detail", "otro"),
                Arguments.of("structure.create-text-channel", "true"),
                Arguments.of("commands.town.enabled", "si"),
                Arguments.of("commands.help.ephemeral", null),
                Arguments.of("updates.check-enabled", null),
                Arguments.of("config-version", 2));
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void rejectsIndicatingExactKey(String key, Object value) throws Exception {
        yaml.set(key, value);
        save();
        assertTrue(loader.validate().stream().anyMatch(p -> p.startsWith(key + ":")));
        ConfigException failure = assertThrows(ConfigException.class, loader::load);
        assertTrue(failure.getMessage().contains(key + ":"));
        assertFalse(failure.getMessage().contains(TOKEN));
        assertFalse(failure.getMessage().contains(PASSWORD));
        assertNull(failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"database.pool.maximum-size", "database.pool.connection-timeout-seconds",
            "limits.min-residents", "limits.creation-cooldown-seconds", "sync.batch-size",
            "sync.batch-pause-seconds", "linking.code-expiry-minutes", "linking.max-attempts",
            "linking.attempt-lockout-minutes", "logging.flush-interval-seconds", "logging.queue-size",
            "updates.check-interval-hours", "commands.cooldown-seconds"})
    void positiveIntervalsAndSizes(String key) throws Exception {
        for (int invalidValue : new int[] {0, -1}) {
            yaml.set(key, invalidValue);
            save();
            assertTrue(loader.validate().stream().anyMatch(p -> p.startsWith(key + ":")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mysql", "mariadb", "sqlite"})
    void supportedEngines(String type) throws Exception {
        yaml.set("database.type", type);
        yaml.set("database.user", "usuario");
        save();
        assertEquals(type, loader.load().database().type().name().toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void optionalLimitsAndValidPlaceholders() throws Exception {
        yaml.set("limits.max-towns", 240);
        yaml.set("discord.log-channel-id", "18446744073709551615");
        yaml.set("database.pool.minimum-idle", 0);
        yaml.set("lifecycle.archive-reminder-days", 0);
        yaml.set("roles.town-role-color", "#abcdef");
        yaml.set("roles.town-role-name", "Ciudad {town}");
        yaml.set("structure.text-channel-name", "ciudad-{town}-{mayor}");
        yaml.set("structure.voice-channel-name", "Voz de {town} de {mayor}");
        save();
        assertTrue(loader.validate().isEmpty());
        assertEquals("18446744073709551615", loader.load().discord().logChannelId().orElseThrow());
    }

    @Test
    void zeroIntervalDisablesReconciliation() throws Exception {
        yaml.set("sync.interval-minutes", 0);
        save();
        assertTrue(loader.validate().isEmpty());
        assertEquals(Duration.ZERO, loader.load().sync().interval());
    }

    @Test
    void explicitReloadAndValidationDoesNotPublish() throws Exception {
        PluginConfig previous = loader.load();
        Messages messages = loader.messages();
        yaml.set("limits.max-towns", 100);
        save();
        Files.writeString(folder.resolve("messages_en.yml"), "prefix: '[nuevo] '\ngeneral:\n  working: nuevo\n");
        assertTrue(loader.validate().isEmpty());
        assertSame(messages, loader.messages());
        assertEquals(200, previous.limits().maxTowns());
        assertEquals(100, loader.load().limits().maxTowns());
        assertEquals("[nuevo] nuevo", loader.messages().plain("general.working", Map.of()));
        Messages valid = loader.messages();
        yaml.set("discord.token", "");
        save();
        Files.writeString(folder.resolve("messages_en.yml"), "prefix: cambiado\n");
        assertThrows(ConfigException.class, loader::load);
        assertSame(valid, loader.messages());
    }

    @Test
    void malformedYamlNeverLeaksSecretsNorCauses() throws Exception {
        Files.writeString(folder.resolve("config.yml"), "discord:\n  token: [" + TOKEN + "\npassword: " + PASSWORD);
        ConfigException failure = assertThrows(ConfigException.class, loader::load);
        StringWriter stackTrace = new StringWriter();
        failure.printStackTrace(new PrintWriter(stackTrace));
        assertFalse(stackTrace.toString().contains(TOKEN));
        assertFalse(stackTrace.toString().contains(PASSWORD));
        assertTrue(loader.validate().stream().anyMatch(p -> p.startsWith("config.yml:")));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void missingConfigFileIsRejected() throws Exception {
        Files.delete(folder.resolve("config.yml"));
        List<String> problems = loader.validate();
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("config.yml:")));
        assertThrows(ConfigException.class, loader::load);
    }

    @Test
    void malformedMessageFileWarnsAndFallsBackToBundledEnglishWithoutStoppingServer() throws Exception {
        Files.writeString(folder.resolve("messages_en.yml"), "prefix: [sin cerrar\n");
        List<String> problems = loader.validate();
        assertTrue(problems.isEmpty(), "Malformed message file must not add fatal configuration problems");

        PluginConfig config = assertDoesNotThrow(loader::load);
        assertNotNull(config);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("messages_en.yml")));
        String text = loader.messages().plain("general.working", Map.of());
        assertTrue(text.contains("Working on it"));
    }

    @Test
    void malformedSpanishMessageFileFallsBackToBundledEnglishAndWarns() throws Exception {
        yaml.set("language", "es");
        save();
        Files.writeString(folder.resolve("messages_es.yml"), "prefix: [corrupto\n");

        assertTrue(loader.validate().isEmpty());
        PluginConfig config = assertDoesNotThrow(loader::load);
        assertEquals("es", config.language());

        assertTrue(warnings.stream().anyMatch(w -> w.contains("messages_es.yml")));
        String text = loader.messages().plain("general.working", Map.of());
        assertTrue(text.contains("Working on it"));
    }

    @Test
    void missingKeyInSpanishFileFallsBackToEnglishAndWarnsOnce() throws Exception {
        yaml.set("language", "es");
        save();
        // Remove a key from messages_es.yml on disk
        YamlConfiguration esConfig = new YamlConfiguration();
        esConfig.load(folder.resolve("messages_es.yml").toFile());
        esConfig.set("space.created", null);
        esConfig.save(folder.resolve("messages_es.yml").toFile());

        loader.load();
        Messages messages = loader.messages();

        // First call: falls back to bundled English text and warns once naming key and file
        String text1 = messages.plain("space.created", Map.of("town", "Roma"));
        assertTrue(text1.contains("Roma"));
        assertTrue(text1.contains("ready on Discord"));
        assertEquals(1, warnings.stream().filter(w -> w.contains("messages_es.yml") && w.contains("space.created")).count());

        // Second call: does not warn again
        String text2 = messages.plain("space.created", Map.of("town", "Roma"));
        assertEquals(text1, text2);
        assertEquals(1, warnings.stream().filter(w -> w.contains("messages_es.yml") && w.contains("space.created")).count());
    }

    @Test
    void invalidLanguageStartsInEnglishAndNamesKey() throws Exception {
        yaml.set("language", "fr");
        save();

        PluginConfig config = loader.load();
        assertEquals("en", config.language());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("language") && w.contains("fr")));

        // Messages are loaded in English
        String text = loader.messages().plain("general.no-permission", Map.of());
        assertTrue(text.contains("You do not have permission"));
    }

    @Test
    void enAndEsReturnDifferentTextForSameKey() throws Exception {
        yaml.set("language", "en");
        save();
        loader.load();
        String enText = loader.messages().plain("general.no-permission", Map.of());

        yaml.set("language", "es");
        save();
        loader.load();
        String esText = loader.messages().plain("general.no-permission", Map.of());

        assertNotEquals(enText, esText);
        assertTrue(enText.contains("You do not have permission"));
        assertTrue(esText.contains("No tienes permiso"));
    }

    @Test
    void bundledMessagesHaveIdenticalKeySets() throws Exception {
        YamlConfiguration en = new YamlConfiguration();
        YamlConfiguration es = new YamlConfiguration();
        try (var inEn = getClass().getResourceAsStream("/messages_en.yml");
             var inEs = getClass().getResourceAsStream("/messages_es.yml")) {
            assertNotNull(inEn, "messages_en.yml bundled resource must exist");
            assertNotNull(inEs, "messages_es.yml bundled resource must exist");
            en.load(new InputStreamReader(inEn, StandardCharsets.UTF_8));
            es.load(new InputStreamReader(inEs, StandardCharsets.UTF_8));
        }
        Set<String> enKeys = new TreeSet<>();
        for (String key : en.getKeys(true)) {
            if (en.isString(key)) enKeys.add(key);
        }
        Set<String> esKeys = new TreeSet<>();
        for (String key : es.getKeys(true)) {
            if (es.isString(key)) esKeys.add(key);
        }
        assertEquals(enKeys, esKeys, "Every key in messages_en.yml must be in messages_es.yml and vice versa");
    }

    @Test
    void bundledMessagesHaveMatchingPlaceholders() throws Exception {
        YamlConfiguration en = new YamlConfiguration();
        YamlConfiguration es = new YamlConfiguration();
        try (var inEn = getClass().getResourceAsStream("/messages_en.yml");
             var inEs = getClass().getResourceAsStream("/messages_es.yml")) {
            en.load(new InputStreamReader(inEn, StandardCharsets.UTF_8));
            es.load(new InputStreamReader(inEs, StandardCharsets.UTF_8));
        }
        Pattern placeholder = Pattern.compile("\\{([^{}]+)}");
        for (String key : en.getKeys(true)) {
            if (en.isString(key)) {
                var matcherEn = placeholder.matcher(en.getString(key));
                Set<String> enPlaceholders = new HashSet<>();
                while (matcherEn.find()) enPlaceholders.add(matcherEn.group(1));

                var matcherEs = placeholder.matcher(es.getString(key));
                Set<String> esPlaceholders = new HashSet<>();
                while (matcherEs.find()) esPlaceholders.add(matcherEs.group(1));

                assertEquals(esPlaceholders, enPlaceholders, "Placeholders for key " + key + " must match between en and es");
            }
        }
    }

    @Test
    void firstRunWritesBothFilesAndNeverOverwritesAfterwards(@TempDir Path freshFolder) throws Exception {
        Files.copy(folder.resolve("config.yml"), freshFolder.resolve("config.yml"));
        assertFalse(Files.exists(freshFolder.resolve("messages_en.yml")));
        assertFalse(Files.exists(freshFolder.resolve("messages_es.yml")));

        YamlConfigLoader freshLoader = new YamlConfigLoader(freshFolder, w -> {});
        freshLoader.load();

        assertTrue(Files.exists(freshFolder.resolve("messages_en.yml")));
        assertTrue(Files.exists(freshFolder.resolve("messages_es.yml")));

        // Server owner modifies file
        Files.writeString(freshFolder.resolve("messages_en.yml"), "prefix: '[custom] '\n");
        freshLoader.load();

        // Ensure owner edit was not overwritten
        // The file still holds exactly what the owner wrote, byte for byte.
        assertEquals("prefix: '[custom] '\n",
                Files.readString(freshFolder.resolve("messages_en.yml")));
    }

    @Test
    void reloadingAfterEditingLanguageChangesTextsWithoutRestart() throws Exception {
        yaml.set("language", "en");
        save();
        loader.load();
        Messages retained = loader.messages();
        assertTrue(retained.plain("general.working", Map.of()).contains("Working on it"));

        // Change to es and reload - retained reference must see Spanish without asking loader for a fresh one
        yaml.set("language", "es");
        save();
        loader.load();
        assertTrue(retained.plain("general.working", Map.of()).contains("Trabajando en ello"));
        assertSame(retained, loader.messages());

        // Change back to en and reload - retained reference must see English again
        yaml.set("language", "en");
        save();
        loader.load();
        assertTrue(retained.plain("general.working", Map.of()).contains("Working on it"));
        assertSame(retained, loader.messages());
    }

    @Test
    void consumerHoldingMessagesSeesLanguageSwapOnReload() throws Exception {
        yaml.set("language", "en");
        save();
        loader.load();

        class CommandHandler {
            private final Messages messages;

            CommandHandler(Messages messages) {
                this.messages = messages;
            }

            String reply() {
                return messages.plain("general.working", Map.of());
            }
        }

        CommandHandler handler = new CommandHandler(loader.messages());
        assertTrue(handler.reply().contains("Working on it"));

        yaml.set("language", "es");
        save();
        loader.load();

        assertTrue(handler.reply().contains("Trabajando en ello"));
    }
}
