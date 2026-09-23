package com.discordtowny.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.discordtowny.storage.SettingsRepository;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

class YamlMessagesTest {
    @Test
    void prefixColorsAndLiteralPlaceholdersWithoutRecursiveSubstitutions() {
        var messages = new YamlMessages(Map.of("prefix", "&b[DT] &r", "saludo", "&aHola {town}, {mayor}!"), warning -> {});
        Map<String, String> values = Map.of("town", "&cRoma {mayor}", "mayor", "Ana");
        assertEquals("[DT] Hola &cRoma {mayor}, Ana!", messages.plain("saludo", values));
        String format = LegacyComponentSerializer.legacySection().serialize(messages.get("saludo", values));
        assertTrue(format.contains("\u00a7b"));
        assertTrue(format.contains("\u00a7a"));
        assertFalse(format.contains("\u00a7c"));
    }

    @Test
    void preservesPlaceholdersWithoutValueAndCopiesTexts() {
        Map<String, String> texts = new HashMap<>(Map.of("prefix", "", "mensaje", "Hola {player}"));
        var messages = new YamlMessages(texts, warning -> {});
        texts.put("mensaje", "cambiado");
        assertEquals("Hola {player}", messages.plain("mensaje", Map.of()));
    }

    @Test
    void missingMessageOrPrefixIsIdentifiedAndWarnsOnce() {
        List<String> warnings = new ArrayList<>();
        var messages = new YamlMessages(Map.of(), warnings::add);
        assertEquals("[missing message: prefix][missing message: general.working]",
                messages.plain("general.working", Map.of()));
        messages.get("general.working");
        assertEquals(2, warnings.size());
        assertTrue(warnings.getLast().contains("general.working"));
    }

    @Test
    void keyMissingFromSelectedFileFallsBackToEnglishAndWarnsOnce() {
        List<String> warnings = new ArrayList<>();
        Map<String, String> selected = Map.of("prefix", "[ES] ", "general.known", "Texto en espanol");
        Map<String, String> fallback = Map.of("prefix", "[EN] ", "general.known", "English text", "space.created", "Space for {town} is ready.");
        var messages = new YamlMessages(selected, fallback, "messages_es.yml", warnings::add);

        // Key in selected language returns selected text without warnings
        assertEquals("[ES] Texto en espanol", messages.plain("general.known", Map.of()));
        assertTrue(warnings.isEmpty());

        // Key missing from selected falls back to English and warns once naming key and file
        assertEquals("[ES] Space for Roma is ready.", messages.plain("space.created", Map.of("town", "Roma")));
        assertEquals(1, warnings.size());
        assertEquals("messages_es.yml: missing message space.created", warnings.getFirst());

        // Repeated call returns fallback text without warning again
        assertEquals("[ES] Space for Roma is ready.", messages.plain("space.created", Map.of("town", "Roma")));
        assertEquals(1, warnings.size());
    }

    @Test
    void keyMissingFromBothSelectedAndFallbackReturnsVisiblePlaceholder() {
        List<String> warnings = new ArrayList<>();
        Map<String, String> selected = Map.of("prefix", "[ES] ");
        Map<String, String> fallback = Map.of("prefix", "[EN] ");
        var messages = new YamlMessages(selected, fallback, "messages_es.yml", warnings::add);

        assertEquals("[ES] [missing message: unknown.key]", messages.plain("unknown.key", Map.of()));
        assertEquals(1, warnings.size());
        assertEquals("messages_es.yml: missing message unknown.key", warnings.getFirst());

        // Repeated call does not warn again
        assertEquals("[ES] [missing message: unknown.key]", messages.plain("unknown.key", Map.of()));
        assertEquals(1, warnings.size());
    }

    @Test
    void blankSelectedTextFallsBackToEnglishAndWarns() {
        List<String> warnings = new ArrayList<>();
        Map<String, String> selected = Map.of("prefix", "[ES] ", "empty.reply", "", "whitespace.reply", "   ");
        Map<String, String> fallback = Map.of("prefix", "[EN] ", "empty.reply", "Fallback 1", "whitespace.reply", "Fallback 2");
        var messages = new YamlMessages(selected, fallback, "messages_es.yml", warnings::add);

        assertEquals("[ES] Fallback 1", messages.plain("empty.reply", Map.of()));
        assertEquals(1, warnings.size());
        assertEquals("messages_es.yml: missing message empty.reply", warnings.getFirst());

        assertEquals("[ES] Fallback 2", messages.plain("whitespace.reply", Map.of()));
        assertEquals(2, warnings.size());
        assertEquals("messages_es.yml: missing message whitespace.reply", warnings.getLast());
    }

    @Test
    void blankFallbackTextEndsAtVisibleMissingKeyMarker() {
        List<String> warnings = new ArrayList<>();
        Map<String, String> selected = Map.of("prefix", "[ES] ", "empty.both", "", "whitespace.both", "   ");
        Map<String, String> fallback = Map.of("prefix", "[EN] ", "empty.both", "", "whitespace.both", "   ");
        var messages = new YamlMessages(selected, fallback, "messages_es.yml", warnings::add);

        assertEquals("[ES] [missing message: empty.both]", messages.plain("empty.both", Map.of()));
        assertEquals(1, warnings.size());
        assertEquals("messages_es.yml: missing message empty.both", warnings.getFirst());

        assertEquals("[ES] [missing message: whitespace.both]", messages.plain("whitespace.both", Map.of()));
        assertEquals(2, warnings.size());
        assertEquals("messages_es.yml: missing message whitespace.both", warnings.getLast());
    }

    @Test
    void emptyPrefixIsPermittedWithoutFallbackOrWarning() {
        List<String> warnings = new ArrayList<>();
        Map<String, String> selected = Map.of("prefix", "", "general.known", "Texto sin prefijo");
        Map<String, String> fallback = Map.of("prefix", "[EN] ", "general.known", "English text");
        var messages = new YamlMessages(selected, fallback, "messages_es.yml", warnings::add);

        assertEquals("Texto sin prefijo", messages.plain("general.known", Map.of()));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void emptyPrefixWithBlankReplyFallsBackWithoutPrefix() {
        List<String> warnings = new ArrayList<>();
        Map<String, String> selected = Map.of("prefix", "", "saludo", "");
        Map<String, String> fallback = Map.of("prefix", "[EN] ", "saludo", "Hello!");
        var messages = new YamlMessages(selected, fallback, "messages_es.yml", warnings::add);

        assertEquals("Hello!", messages.plain("saludo", Map.of()));
        assertEquals(1, warnings.size());
        assertEquals("messages_es.yml: missing message saludo", warnings.getFirst());
    }

    @Test
    void emptyPrefixWithBlankReplyAndBlankFallbackShowsMarkerWithoutPrefix() {
        List<String> warnings = new ArrayList<>();
        Map<String, String> selected = Map.of("prefix", "", "saludo", "");
        Map<String, String> fallback = Map.of("prefix", "[EN] ", "saludo", "   ");
        var messages = new YamlMessages(selected, fallback, "messages_es.yml", warnings::add);

        assertEquals("[missing message: saludo]", messages.plain("saludo", Map.of()));
        assertEquals(1, warnings.size());
        assertEquals("messages_es.yml: missing message saludo", warnings.getFirst());
    }

    @Test
    void customPrefixAppliesToGetWhilePlainRetainsCatalogPrefix() {
        var messages = new YamlMessages(
                Map.of("prefix", "&8[Catalog] &r", "hello", "Hello {name}!"),
                warning -> {}
        );
        messages.setCustomPrefix("&c[Custom] &r");

        // get() carries custom prefix
        String rendered = LegacyComponentSerializer.legacySection().serialize(messages.get("hello", Map.of("name", "Ana")));
        assertTrue(rendered.contains("\u00a7c[Custom]"));
        assertEquals("&c[Custom] &r", messages.rawPrefix());

        // plain() still carries catalog prefix
        String plain = messages.plain("hello", Map.of("name", "Ana"));
        assertTrue(plain.startsWith("[Catalog]"));
        assertFalse(plain.contains("[Custom]"));
        assertEquals("[Catalog] Hello Ana!", plain);
    }

    @Test
    void emptyPrefixResultsInNoPrefixForGetWhilePlainRetainsCatalogPrefix() {
        var messages = new YamlMessages(
                Map.of("prefix", "&8[Catalog] &r", "hello", "Hello {name}!"),
                warning -> {}
        );
        messages.setCustomPrefix("");

        // get() has no prefix
        String rendered = LegacyComponentSerializer.legacySection().serialize(messages.get("hello", Map.of("name", "Ana")));
        assertFalse(rendered.contains("[Catalog]"));
        assertEquals("", messages.rawPrefix());

        // plain() still carries catalog prefix
        String plain = messages.plain("hello", Map.of("name", "Ana"));
        assertTrue(plain.startsWith("[Catalog]"));

        // Reset restores catalog prefix
        messages.resetPrefix();
        String resetRendered = LegacyComponentSerializer.legacySection().serialize(messages.get("hello", Map.of("name", "Ana")));
        assertTrue(resetRendered.contains("[Catalog]"));
        assertEquals("&8[Catalog] &r", messages.rawPrefix());
    }

    @Test
    void storedPrefixSurvivesSimulatedRestart() {
        com.discordtowny.storage.SettingsRepository settings = new com.discordtowny.storage.SettingsRepository() {
            private String prefix = "&e[Stored] ";
            @Override
            public java.util.Optional<String> get(String key) {
                return com.discordtowny.storage.SettingsRepository.KEY_CHAT_PREFIX.equals(key)
                        ? java.util.Optional.ofNullable(prefix) : java.util.Optional.empty();
            }
            @Override
            public void put(String key, String value) {
                if (com.discordtowny.storage.SettingsRepository.KEY_CHAT_PREFIX.equals(key)) prefix = value;
            }
            @Override
            public void delete(String key) {
                if (com.discordtowny.storage.SettingsRepository.KEY_CHAT_PREFIX.equals(key)) prefix = null;
            }
        };

        var messages1 = new YamlMessages(Map.of("prefix", "[Catalog] ", "hello", "World"), warning -> {}, settings);
        assertEquals("&e[Stored] ", messages1.rawPrefix());

        // Update prefix through messages
        messages1.setCustomPrefix("&a[NewStored] ");
        settings.put(com.discordtowny.storage.SettingsRepository.KEY_CHAT_PREFIX, "&a[NewStored] ");

        // Simulated restart: construct a fresh YamlMessages pointing to the same store
        var messages2 = new YamlMessages(Map.of("prefix", "[Catalog] ", "hello", "World"), warning -> {}, settings);
        assertEquals("&a[NewStored] ", messages2.rawPrefix());
        String rendered = LegacyComponentSerializer.legacySection().serialize(messages2.get("hello"));
        assertTrue(rendered.contains("\u00a7a[NewStored]"));
    }

    @Test
    void failingSettingsStoreFallsBackToCatalogPrefixWithoutFailing() {
        com.discordtowny.storage.SettingsRepository failingSettings = new com.discordtowny.storage.SettingsRepository() {
            @Override
            public java.util.Optional<String> get(String key) {
                throw new com.discordtowny.storage.StorageException("Database unreachable");
            }
            @Override
            public void put(String key, String value) {
                throw new com.discordtowny.storage.StorageException("Database unreachable");
            }
            @Override
            public void delete(String key) {
                throw new com.discordtowny.storage.StorageException("Database unreachable");
            }
        };

        var messages = new YamlMessages(Map.of("prefix", "[Catalog] ", "hello", "World"), warning -> {}, failingSettings);
        assertEquals("[Catalog] ", messages.rawPrefix());

        // Messages still print with catalog prefix
        String plain = messages.plain("hello");
        assertEquals("[Catalog] World", plain);
        assertDoesNotThrow(() -> messages.get("hello"));
    }

    private static SettingsRepository inMemorySettings(String initialPrefix) {
        return new SettingsRepository() {
            private String prefix = initialPrefix;
            @Override
            public java.util.Optional<String> get(String key) {
                return SettingsRepository.KEY_CHAT_PREFIX.equals(key)
                        ? java.util.Optional.ofNullable(prefix) : java.util.Optional.empty();
            }
            @Override
            public void put(String key, String value) {
                if (SettingsRepository.KEY_CHAT_PREFIX.equals(key)) prefix = value;
            }
            @Override
            public void delete(String key) {
                if (SettingsRepository.KEY_CHAT_PREFIX.equals(key)) prefix = null;
            }
        };
    }

    @Test
    void messagesRenderWithStoredPrefixWhenSettingsRepositorySuppliedThroughConstruction() {
        SettingsRepository settings = inMemorySettings("&6[StoredPrefix] ");

        var messages = new YamlMessages(
                Map.of("prefix", "[CatalogPrefix] ", "greeting", "Hello {name}!"),
                warning -> {},
                settings
        );

        assertEquals("&6[StoredPrefix] ", messages.rawPrefix());
        String rendered = LegacyComponentSerializer.legacySection().serialize(
                messages.get("greeting", Map.of("name", "Alice"))
        );
        assertTrue(rendered.contains("\u00a76[StoredPrefix]"));
        assertTrue(rendered.contains("Hello Alice!"));
        assertFalse(rendered.contains("[CatalogPrefix]"));
    }

    @Test
    void twoYamlMessagesBuiltWithDifferentSettingsRepositoriesDoNotAffectEachOther() {
        SettingsRepository settingsA = inMemorySettings("&c[PrefixA] ");
        SettingsRepository settingsB = inMemorySettings("&9[PrefixB] ");

        var messagesA = new YamlMessages(
                Map.of("prefix", "[Catalog] ", "test", "Message A"),
                warning -> {},
                settingsA
        );
        var messagesB = new YamlMessages(
                Map.of("prefix", "[Catalog] ", "test", "Message B"),
                warning -> {},
                settingsB
        );

        // Verify independent initial prefixes
        assertEquals("&c[PrefixA] ", messagesA.rawPrefix());
        assertEquals("&9[PrefixB] ", messagesB.rawPrefix());

        String renderedA = LegacyComponentSerializer.legacySection().serialize(messagesA.get("test"));
        String renderedB = LegacyComponentSerializer.legacySection().serialize(messagesB.get("test"));
        assertTrue(renderedA.contains("\u00a7c[PrefixA]"));
        assertFalse(renderedA.contains("[PrefixB]"));
        assertTrue(renderedB.contains("\u00a79[PrefixB]"));
        assertFalse(renderedB.contains("[PrefixA]"));

        // Mutating messagesA does not bleed into messagesB
        messagesA.setCustomPrefix("&e[UpdatedA] ");
        assertEquals("&e[UpdatedA] ", messagesA.rawPrefix());
        assertEquals("&9[PrefixB] ", messagesB.rawPrefix());
        String updatedRenderedB = LegacyComponentSerializer.legacySection().serialize(messagesB.get("test"));
        assertTrue(updatedRenderedB.contains("\u00a79[PrefixB]"));
        assertFalse(updatedRenderedB.contains("[UpdatedA]"));

        // Resetting messagesA does not affect messagesB
        messagesA.resetPrefix();
        assertEquals("[Catalog] ", messagesA.rawPrefix());
        assertEquals("&9[PrefixB] ", messagesB.rawPrefix());
    }
}
