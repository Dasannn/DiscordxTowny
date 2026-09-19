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

    /** Plain-text version, for Discord and for the console. Carries the prefix. */
    String plain(String key, Map<String, String> placeholders);

    /**
     * The text on its own: no prefix, no formatting.
     *
     * <p>For a label rather than a line. The name of a field in a Discord embed,
     * or the caption of a button, is not a chat message: prefixing it would
     * produce a field literally called "[DiscordTowny] Mayor".
     *
     * <p>The distinction is explicit on purpose. Deciding it from the shape of
     * the key would work until someone adds a second family of labels.
     */
    String label(String key, Map<String, String> placeholders);

    default String label(String key) {
        return label(key, Map.of());
    }
}
