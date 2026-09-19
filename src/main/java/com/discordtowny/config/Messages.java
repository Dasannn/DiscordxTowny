package com.discordtowny.config;

import net.kyori.adventure.text.Component;
import java.util.Map;

/** Texts from message files, with their placeholders resolved. */
public interface Messages {

    /**
     * @param key dotted path, for example {@code linking.code-generated}
     * @param placeholders placeholders without braces: {@code Map.of("town", "Roma")}
     * @return the formatted text, including the prefix
     */
    Component get(String key, Map<String, String> placeholders);

    default Component get(String key) {
        return get(key, Map.of());
    }

    /** Plain-text version, for Discord and for the console. */
    String plain(String key, Map<String, String> placeholders);
}
