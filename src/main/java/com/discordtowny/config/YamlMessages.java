package com.discordtowny.config;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Immutable texts; placeholder values are never interpreted as formatting. */
public final class YamlMessages implements Messages {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
    private final Map<String, String> texts;
    private final Map<String, String> fallbackTexts;
    private final String fileName;
    private final Consumer<String> warning;
    private final Set<String> missing = ConcurrentHashMap.newKeySet();

    public YamlMessages(Map<String, String> texts, Map<String, String> fallbackTexts, String fileName, Consumer<String> warning) {
        this.texts = Map.copyOf(texts);
        this.fallbackTexts = Map.copyOf(fallbackTexts);
        this.fileName = fileName != null && !fileName.isBlank() ? fileName : "messages.yml";
        this.warning = warning != null ? warning : s -> {};
    }

    public YamlMessages(Map<String, String> texts, Consumer<String> warning) {
        this(texts, Map.of(), "messages.yml", warning);
    }

    private String text(String key) {
        String value = texts.get(key);
        if (value != null) return value;
        if (missing.add(key)) warning.accept(fileName + ": missing message " + key);
        String fallback = fallbackTexts.get(key);
        if (fallback != null) return fallback;
        return "[missing message: " + key + "]";
    }

    @Override
    public Component get(String key, Map<String, String> placeholders) {
        Component message = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(text("prefix") + text(key));
        return message.replaceText(rule -> rule.match(PLACEHOLDER).replacement((match, original) -> {
            String value = placeholders.get(match.group(1));
            return value == null ? original.build() : Component.text(value);
        }));
    }

    @Override
    public String plain(String key, Map<String, String> placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(get(key, placeholders));
    }
}
