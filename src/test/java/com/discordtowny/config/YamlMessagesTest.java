package com.discordtowny.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
}
