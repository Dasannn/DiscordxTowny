package com.discordtowny.config;

import java.io.IOException;
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
 * Carga explicita: cada load recarga ambos archivos y publica solo si todo es valido.
 *
 * <p>El cableado entrega la carpeta de datos y logger::warning. Al arrancar o
 * recargar, debe capturar ConfigException y mantener el plugin degradado si no
 * dispone de una configuracion valida. Una recarga rechazada conserva los textos
 * anteriores; el consumidor conserva tambien su PluginConfig anterior.
 *
 * <p>config-version es metadato del archivo, no un campo de PluginConfig.
 */
public final class YamlConfigLoader implements ConfigLoader {
    private final Path carpeta;
    private final Consumer<String> aviso;
    private volatile Messages mensajes;

    public YamlConfigLoader(Path carpeta, Consumer<String> aviso) {
        this.carpeta = carpeta;
        this.aviso = aviso;
        this.mensajes = new YamlMessages(Map.of(), aviso);
    }

    @Override
    public synchronized PluginConfig load() {
        Lectura lectura = leer();
        if (!lectura.problemas.isEmpty()) {
            throw new ConfigException(String.join("; ", lectura.problemas));
        }
        mensajes = new YamlMessages(lectura.textos, aviso);
        return lectura.config;
    }

    @Override
    public Messages messages() {
        return mensajes;
    }

    @Override
    public List<String> validate() {
        return List.copyOf(leer().problemas);
    }

    private Lectura leer() {
        List<String> problemas = new ArrayList<>();
        YamlConfiguration yaml = archivo("config.yml", problemas);
        YamlConfiguration textos = archivo("messages.yml", problemas);
        Map<String, String> mapa = new HashMap<>();
        for (String clave : textos.getKeys(true)) {
            if (textos.isString(clave)) mapa.put(clave, textos.getString(clave));
        }
        PluginConfig config = new Valores(yaml, problemas).config();
        return new Lectura(config, mapa, problemas);
    }

    private YamlConfiguration archivo(String nombre, List<String> problemas) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var lector = Files.newBufferedReader(carpeta.resolve(nombre), StandardCharsets.UTF_8)) {
            yaml.load(lector);
        } catch (IOException | InvalidConfigurationException | RuntimeException fallo) {
            // El parser puede incluir lineas con secretos: nunca adjuntar su causa ni su mensaje.
            problemas.add(nombre + ": se esperaba un archivo legible con sintaxis YAML valida");
        }
        return yaml;
    }

    private record Lectura(PluginConfig config, Map<String, String> textos, List<String> problemas) {}

    private static final class Valores {
        private final YamlConfiguration yaml;
        private final List<String> problemas;

        private Valores(YamlConfiguration yaml, List<String> problemas) {
            this.yaml = yaml;
            this.problemas = problemas;
        }

        private void exigir(boolean condicion, String clave, String esperado) {
            if (!condicion) problemas.add(clave + ": se esperaba " + esperado);
        }

        private String texto(String clave) {
            Object valor = yaml.get(clave);
            exigir(valor instanceof String, clave, "texto entre comillas si es un numero");
            return valor instanceof String cadena ? cadena : "";
        }

        private String noVacio(String clave) {
            String valor = texto(clave);
            exigir(!valor.isBlank(), clave, "texto no vacio");
            return valor;
        }

        private boolean booleano(String clave) {
            Object valor = yaml.get(clave);
            exigir(valor instanceof Boolean, clave, "true o false");
            return Boolean.TRUE.equals(valor);
        }

        private int entero(String clave, int minimo, int maximo) {
            Object valor = yaml.get(clave);
            boolean valido = (valor instanceof Integer || valor instanceof Long)
                    && ((Number) valor).longValue() >= minimo && ((Number) valor).longValue() <= maximo;
            exigir(valido, clave, "un entero entre " + minimo + " y " + maximo);
            return valido ? ((Number) valor).intValue() : minimo;
        }

        private int positivo(String clave) {
            return entero(clave, 1, Integer.MAX_VALUE);
        }

        private Duration segundos(String clave) {
            return Duration.ofSeconds(positivo(clave));
        }

        private Duration minutos(String clave) {
            return Duration.ofMinutes(positivo(clave));
        }

        private <E extends Enum<E>> E opcion(String clave, Class<E> tipo) {
            String valor = texto(clave).toUpperCase(Locale.ROOT);
            for (E opcion : tipo.getEnumConstants()) {
                if (opcion.name().equals(valor)) return opcion;
            }
            exigir(false, clave, "uno de " + Arrays.toString(tipo.getEnumConstants()));
            return tipo.getEnumConstants()[0];
        }

        private Optional<String> opcional(String clave) {
            String valor = texto(clave);
            return valor.isBlank() ? Optional.empty() : Optional.of(valor);
        }

        private String snowflake(String clave, boolean opcional) {
            String valor = texto(clave);
            if (opcional && valor.isEmpty()) return valor;
            boolean valido = valor.matches("[1-9][0-9]{0,19}");
            if (valido) {
                try {
                    Long.parseUnsignedLong(valor);
                } catch (NumberFormatException fallo) {
                    valido = false;
                }
            }
            exigir(valido, clave, "un snowflake decimal positivo de hasta 64 bits sin signo"
                    + (opcional ? " o texto vacio" : ""));
            return valor;
        }

        private String nombre(String clave, boolean plantilla, boolean canalTexto) {
            String valor = noVacio(clave);
            String muestra = plantilla ? valor.replace("{town}", "x").replace("{mayor}", "x") : valor;
            exigir(!muestra.contains("{") && !muestra.contains("}"), clave,
                    plantilla ? "solo marcadores {town} y {mayor}" : "un nombre sin marcadores");
            exigir(muestra.length() >= 1 && muestra.length() <= 100, clave,
                    "un nombre de 1 a 100 caracteres, contando al menos uno por marcador");
            exigir(muestra.codePoints().noneMatch(Character::isISOControl), clave, "un nombre sin caracteres de control");
            if (canalTexto) {
                exigir(muestra.matches("[\\p{Ll}\\p{Lo}\\p{M}\\p{N}_-]+"), clave,
                        "letras minusculas, numeros, guiones o guiones bajos fuera de los marcadores");
            }
            // Los nombres reales se deben validar de nuevo tras sustituir town y mayor al crear el canal.
            return valor;
        }

        private PluginConfig config() {
            String token = noVacio("discord.token");
            exigir(!token.strip().equalsIgnoreCase("PON_AQUI_TU_TOKEN"), "discord.token", "un token propio, no el ejemplo");
            String guild = snowflake("discord.guild-id", false);
            String log = snowflake("discord.log-channel-id", true);
            var discord = new PluginConfig.Discord(token, guild, log.isEmpty() ? Optional.empty() : Optional.of(log));

            var tipo = opcion("database.type", PluginConfig.Database.Type.class);
            String host = texto("database.host");
            int puerto = entero("database.port", 1, 65535);
            String nombre = texto("database.name");
            String usuario = texto("database.user");
            String password = texto("database.password");
            String prefijo = texto("database.table-prefix");
            exigir(prefijo.matches("[A-Za-z_][A-Za-z0-9_]*") || prefijo.isEmpty(), "database.table-prefix",
                    "un prefijo SQL de letras, numeros y guiones bajos, sin empezar por numero, o vacio");
            if (tipo != PluginConfig.Database.Type.SQLITE) {
                exigir(!host.isBlank(), "database.host", "un host no vacio");
                exigir(!nombre.isBlank(), "database.name", "un nombre no vacio");
                exigir(!usuario.isBlank(), "database.user", "un usuario no vacio");
            }
            int maximo = positivo("database.pool.maximum-size");
            int minimo = entero("database.pool.minimum-idle", 0, maximo);
            var database = new PluginConfig.Database(tipo, host, puerto, nombre, usuario, password,
                    prefijo, maximo, minimo, segundos("database.pool.connection-timeout-seconds"));

            var structure = new PluginConfig.Structure(nombre("structure.category-name", false, false),
                    nombre("structure.archive-category-name", false, false), booleano("structure.create-text-channel"),
                    booleano("structure.create-voice-channel"), nombre("structure.text-channel-name", true, true),
                    nombre("structure.voice-channel-name", true, false));
            Optional<String> color = opcional("roles.town-role-color");
            exigir(color.isEmpty() || color.get().matches("#?[0-9a-fA-F]{6}"), "roles.town-role-color",
                    "un color hexadecimal de seis digitos o vacio");
            var roles = new PluginConfig.Roles(nombre("roles.mayor-role-name", false, false),
                    nombre("roles.town-role-name", true, false), color, booleano("roles.town-role-hoisted"));
            var limits = new PluginConfig.Limits(entero("limits.max-towns", 1, 240), positivo("limits.min-residents"),
                    segundos("limits.creation-cooldown-seconds"));
            var lifecycle = new PluginConfig.Lifecycle(opcion("lifecycle.on-town-deleted", PluginConfig.Lifecycle.Action.class),
                    opcion("lifecycle.on-town-ruined", PluginConfig.Lifecycle.Action.class),
                    entero("lifecycle.archive-reminder-days", 0, Integer.MAX_VALUE));
            var sync = new PluginConfig.Sync(minutos("sync.interval-minutes"), opcion("sync.mode", PluginConfig.Sync.Mode.class),
                    positivo("sync.batch-size"), segundos("sync.batch-pause-seconds"));
            var linking = new PluginConfig.Linking(minutos("linking.code-expiry-minutes"), positivo("linking.max-attempts"),
                    minutos("linking.attempt-lockout-minutes"), booleano("linking.unlink-on-guild-leave"));
            var logging = new PluginConfig.Logging(segundos("logging.flush-interval-seconds"), positivo("logging.queue-size"),
                    opcion("logging.detail", PluginConfig.Logging.Detail.class));
            var updates = new PluginConfig.Updates(booleano("updates.check-enabled"),
                    Duration.ofHours(positivo("updates.check-interval-hours")), booleano("updates.auto-download"),
                    booleano("updates.notify-admins-on-join"));
            var comandos = new ArrayList<PluginConfig.DiscordCommand>();
            for (String comando : List.of("town", "residents", "res", "townlist", "mytown", "help")) {
                comandos.add(new PluginConfig.DiscordCommand(comando, booleano("commands." + comando + ".enabled"),
                        booleano("commands." + comando + ".ephemeral")));
            }
            var commands = new PluginConfig.Commands(segundos("commands.cooldown-seconds"), List.copyOf(comandos));
            entero("config-version", 1, 1);
            return new PluginConfig(discord, database, structure, roles, limits, lifecycle, sync, linking, logging, updates, commands);
        }
    }
}
