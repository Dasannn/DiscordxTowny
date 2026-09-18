package com.discordtowny.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    @TempDir Path carpeta;
    private YamlConfiguration yaml;
    private YamlConfigLoader loader;
    private final List<String> avisos = new ArrayList<>();

    @BeforeEach
    void preparar() throws Exception {
        try (var config = getClass().getResourceAsStream("/config.yml");
             var mensajes = getClass().getResourceAsStream("/messages.yml")) {
            Files.copy(config, carpeta.resolve("config.yml"));
            Files.copy(mensajes, carpeta.resolve("messages.yml"));
        }
        yaml = new YamlConfiguration();
        yaml.load(carpeta.resolve("config.yml").toFile());
        yaml.set("discord.token", TOKEN);
        yaml.set("discord.guild-id", "123456789012345678");
        yaml.set("database.password", PASSWORD);
        guardar();
        loader = new YamlConfigLoader(carpeta, avisos::add);
    }

    private void guardar() throws Exception {
        yaml.save(carpeta.resolve("config.yml").toFile());
    }

    @Test
    void cargaTodosLosBloquesYNoExponeSecretos() {
        assertTrue(loader.validate().isEmpty());
        PluginConfig config = loader.load();
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
        for (String volcado : List.of(config.toString(), config.discord().toString(), config.database().toString())) {
            assertFalse(volcado.contains(TOKEN));
            assertFalse(volcado.contains(PASSWORD));
        }
    }

    static Stream<Arguments> invalidos() {
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
                Arguments.of("logging.detail", "otro"),
                Arguments.of("structure.create-text-channel", "true"),
                Arguments.of("commands.town.enabled", "si"),
                Arguments.of("commands.help.ephemeral", null),
                Arguments.of("updates.check-enabled", null),
                Arguments.of("config-version", 2));
    }

    @ParameterizedTest
    @MethodSource("invalidos")
    void rechazaIndicandoClaveExacta(String clave, Object valor) throws Exception {
        yaml.set(clave, valor);
        guardar();
        assertTrue(loader.validate().stream().anyMatch(p -> p.startsWith(clave + ":")));
        ConfigException fallo = assertThrows(ConfigException.class, loader::load);
        assertTrue(fallo.getMessage().contains(clave + ":"));
        assertFalse(fallo.getMessage().contains(TOKEN));
        assertFalse(fallo.getMessage().contains(PASSWORD));
        assertNull(fallo.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"database.pool.maximum-size", "database.pool.connection-timeout-seconds",
            "limits.min-residents", "limits.creation-cooldown-seconds", "sync.interval-minutes", "sync.batch-size",
            "sync.batch-pause-seconds", "linking.code-expiry-minutes", "linking.max-attempts",
            "linking.attempt-lockout-minutes", "logging.flush-interval-seconds", "logging.queue-size",
            "updates.check-interval-hours", "commands.cooldown-seconds"})
    void intervalosYTamanosPositivos(String clave) throws Exception {
        for (int invalido : new int[] {0, -1}) {
            yaml.set(clave, invalido);
            guardar();
            assertTrue(loader.validate().stream().anyMatch(p -> p.startsWith(clave + ":")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mysql", "mariadb", "sqlite"})
    void motoresAdmitidos(String tipo) throws Exception {
        yaml.set("database.type", tipo);
        yaml.set("database.user", "usuario");
        guardar();
        assertEquals(tipo, loader.load().database().type().name().toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void limitesOpcionalesYMarcadoresValidos() throws Exception {
        yaml.set("limits.max-towns", 240);
        yaml.set("discord.log-channel-id", "18446744073709551615");
        yaml.set("database.pool.minimum-idle", 0);
        yaml.set("lifecycle.archive-reminder-days", 0);
        yaml.set("roles.town-role-color", "#abcdef");
        yaml.set("roles.town-role-name", "{town} de {mayor}");
        yaml.set("structure.text-channel-name", "ciudad-{town}-{mayor}");
        yaml.set("structure.voice-channel-name", "Voz de {town}");
        guardar();
        assertTrue(loader.validate().isEmpty());
        assertEquals("18446744073709551615", loader.load().discord().logChannelId().orElseThrow());
    }

    @Test
    void recargaExplicitaYValidacionNoPublica() throws Exception {
        PluginConfig anterior = loader.load();
        Messages mensajes = loader.messages();
        yaml.set("limits.max-towns", 100);
        guardar();
        Files.writeString(carpeta.resolve("messages.yml"), "prefix: '[nuevo] '\ngeneral:\n  working: nuevo\n");
        assertTrue(loader.validate().isEmpty());
        assertSame(mensajes, loader.messages());
        assertEquals(200, anterior.limits().maxTowns());
        assertEquals(100, loader.load().limits().maxTowns());
        assertEquals("[nuevo] nuevo", loader.messages().plain("general.working", Map.of()));
        Messages validos = loader.messages();
        yaml.set("discord.token", "");
        guardar();
        Files.writeString(carpeta.resolve("messages.yml"), "prefix: cambiado\n");
        assertThrows(ConfigException.class, loader::load);
        assertSame(validos, loader.messages());
    }

    @Test
    void yamlMalformadoNuncaFiltraSecretosNiCausas() throws Exception {
        Files.writeString(carpeta.resolve("config.yml"), "discord:\n  token: [" + TOKEN + "\npassword: " + PASSWORD);
        ConfigException fallo = assertThrows(ConfigException.class, loader::load);
        StringWriter pila = new StringWriter();
        fallo.printStackTrace(new PrintWriter(pila));
        assertFalse(pila.toString().contains(TOKEN));
        assertFalse(pila.toString().contains(PASSWORD));
        assertTrue(loader.validate().stream().anyMatch(p -> p.startsWith("config.yml:")));
        assertTrue(avisos.isEmpty());
    }

    @Test
    void archivosAusentesOTextosMalformadosSeRechazan() throws Exception {
        Files.delete(carpeta.resolve("config.yml"));
        Files.writeString(carpeta.resolve("messages.yml"), "prefix: [sin cerrar");
        List<String> problemas = loader.validate();
        assertTrue(problemas.stream().anyMatch(p -> p.startsWith("config.yml:")));
        assertTrue(problemas.stream().anyMatch(p -> p.startsWith("messages.yml:")));
        assertThrows(ConfigException.class, loader::load);
    }
}
