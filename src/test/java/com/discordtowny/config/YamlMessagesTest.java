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
}
