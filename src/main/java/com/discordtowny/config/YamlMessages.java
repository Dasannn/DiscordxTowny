package com.discordtowny.config;

import com.discordtowny.storage.SettingsRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
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

    private final Supplier<Optional<String>> customPrefixSupplier;
    private volatile Optional<String> cachedCustomPrefix;
    private volatile boolean prefixInitialized;

    public YamlMessages(Map<String, String> texts, Map<String, String> fallbackTexts, String fileName, Consumer<String> warning, Supplier<Optional<String>> customPrefixSupplier) {
        this.texts = Map.copyOf(texts);
        this.fallbackTexts = Map.copyOf(fallbackTexts);
        this.fileName = fileName != null && !fileName.isBlank() ? fileName : "messages.yml";
        this.warning = warning != null ? warning : s -> {};
        this.customPrefixSupplier = customPrefixSupplier;
        loadCustomPrefix();
    }

    public YamlMessages(Map<String, String> texts, Consumer<String> warning, Supplier<Optional<String>> customPrefixSupplier) {
        this(texts, Map.of(), "messages.yml", warning, customPrefixSupplier);
    }

    public YamlMessages(Map<String, String> texts, Map<String, String> fallbackTexts, String fileName, Consumer<String> warning, SettingsRepository settings) {
        this(texts, fallbackTexts, fileName, warning, settings != null ? () -> settings.get(SettingsRepository.KEY_CHAT_PREFIX) : null);
    }

    public YamlMessages(Map<String, String> texts, Consumer<String> warning, SettingsRepository settings) {
        this(texts, Map.of(), "messages.yml", warning, settings);
    }

    public YamlMessages(Map<String, String> texts, Map<String, String> fallbackTexts, String fileName, Consumer<String> warning) {
        this(texts, fallbackTexts, fileName, warning, (Supplier<Optional<String>>) null);
    }

    public YamlMessages(Map<String, String> texts, Consumer<String> warning) {
        this(texts, Map.of(), "messages.yml", warning);
    }

    private void loadCustomPrefix() {
        try {
            if (customPrefixSupplier != null) {
                this.cachedCustomPrefix = customPrefixSupplier.get();
                this.prefixInitialized = true;
            } else {
                this.cachedCustomPrefix = Optional.empty();
                this.prefixInitialized = true;
            }
        } catch (Throwable t) {
            // A database problem must not silence the plugin
            this.cachedCustomPrefix = Optional.empty();
            this.prefixInitialized = true;
        }
    }

    private Optional<String> getCustomPrefix() {
        if (!prefixInitialized) {
            loadCustomPrefix();
        }
        Optional<String> cached = this.cachedCustomPrefix;
        return cached != null ? cached : Optional.empty();
    }

    public String effectivePrefix() {
        Optional<String> custom = getCustomPrefix();
        if (custom != null && custom.isPresent()) {
            return custom.get();
        }
        return catalogPrefix();
    }

    @Override
    public String catalogPrefix() {
        return text("prefix");
    }

    @Override
    public String rawPrefix() {
        return effectivePrefix();
    }

    @Override
    public Component renderedPrefix() {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(effectivePrefix());
    }

    @Override
    public void setCustomPrefix(String prefix) {
        this.cachedCustomPrefix = Optional.ofNullable(prefix);
        this.prefixInitialized = true;
    }

    @Override
    public void resetPrefix() {
        this.cachedCustomPrefix = Optional.empty();
        this.prefixInitialized = true;
    }

    @Override
    public void invalidatePrefix() {
        this.prefixInitialized = false;
        this.cachedCustomPrefix = null;
    }

    private String text(String key) {
        String value = texts.get(key);
        if (isUsable(key, value)) return value;
        if (missing.add(key)) warning.accept(fileName + ": missing message " + key);
        String fallback = fallbackTexts.get(key);
        if (isUsable(key, fallback)) return fallback;
        return "[missing message: " + key + "]";
    }

    private static boolean isUsable(String key, String value) {
        if (value == null) {
            return false;
        }
        if ("prefix".equals(key)) {
            return true;
        }
        return !value.isBlank();
    }

    @Override
    public Component get(String key, Map<String, String> placeholders) {
        String prefix = effectivePrefix();
        Component body = resolve(
                LegacyComponentSerializer.legacyAmpersand().deserialize(text(key)),
                placeholders
        );
        if (prefix.isEmpty()) {
            return body;
        }
        Component prefixComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(prefix);
        return Component.text().append(prefixComponent).append(body).build();
    }

    /** Replaces placeholders without ever interpreting their values as formatting. */
    private Component resolve(Component message, Map<String, String> placeholders) {
        return message.replaceText(rule -> rule.match(PLACEHOLDER).replacement((match, original) -> {
            String value = placeholders.get(match.group(1));
            return value == null ? original.build() : Component.text(value);
        }));
    }

    @Override
    public String plain(String key, Map<String, String> placeholders) {
        String prefix = catalogPrefix();
        Component body = resolve(
                LegacyComponentSerializer.legacyAmpersand().deserialize(text(key)),
                placeholders
        );
        if (prefix.isEmpty()) {
            return PlainTextComponentSerializer.plainText().serialize(body);
        }
        Component prefixComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(prefix);
        Component combined = Component.text().append(prefixComponent).append(body).build();
        return PlainTextComponentSerializer.plainText().serialize(combined);
    }

    @Override
    public String label(String key, Map<String, String> placeholders) {
        Component label = LegacyComponentSerializer.legacyAmpersand().deserialize(text(key));
        return PlainTextComponentSerializer.plainText().serialize(resolve(label, placeholders));
    }
}
