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

    default String plain(String key) {
        return plain(key, Map.of());
    }

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

    /**
     * Raw prefix currently in effect, including formatting codes (e.g. &amp;8[&amp;bDiscordTowny&amp;8] &amp;r).
     */
    default String rawPrefix() {
        return "";
    }

    /**
     * Default prefix from the catalog (file/resource), ignoring custom store.
     */
    default String catalogPrefix() {
        return "";
    }

    /**
     * Rendered prefix as an Adventure Component, with formatting codes parsed.
     */
    default Component renderedPrefix() {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(rawPrefix());
    }

    /**
     * Updates the in-memory custom prefix.
     */
    default void setCustomPrefix(String prefix) {}

    /**
     * Resets the custom prefix, reverting to the catalog default.
     */
    default void resetPrefix() {}

    /**
     * Invalidates the cached prefix so it re-reads from storage on next access.
     */
    default void invalidatePrefix() {}
}
