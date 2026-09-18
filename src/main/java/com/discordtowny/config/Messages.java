package com.discordtowny.config;

import net.kyori.adventure.text.Component;
import java.util.Map;

/** Textos de messages.yml, con sus marcadores resueltos. */
public interface Messages {

    /**
     * @param key ruta con puntos, por ejemplo {@code linking.code-generated}
     * @param placeholders marcadores sin llaves: {@code Map.of("town", "Roma")}
     * @return el texto ya formateado, con el prefijo incluido
     */
    Component get(String key, Map<String, String> placeholders);

    default Component get(String key) {
        return get(key, Map.of());
    }

    /** Version en texto plano, para Discord y para la consola. */
    String plain(String key, Map<String, String> placeholders);
}
