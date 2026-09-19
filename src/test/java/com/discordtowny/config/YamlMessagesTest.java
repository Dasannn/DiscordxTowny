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
}
