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
    void prefijoColoresYMarcadoresLiteralesSinSustitucionesRecursivas() {
        var mensajes = new YamlMessages(Map.of("prefix", "&b[DT] &r", "saludo", "&aHola {town}, {mayor}!"), aviso -> {});
        Map<String, String> valores = Map.of("town", "&cRoma {mayor}", "mayor", "Ana");
        assertEquals("[DT] Hola &cRoma {mayor}, Ana!", mensajes.plain("saludo", valores));
        String formato = LegacyComponentSerializer.legacySection().serialize(mensajes.get("saludo", valores));
        assertTrue(formato.contains("\u00a7b"));
        assertTrue(formato.contains("\u00a7a"));
        assertFalse(formato.contains("\u00a7c"));
    }

    @Test
    void conservaMarcadoresSinValorYCopiaLosTextos() {
        Map<String, String> textos = new HashMap<>(Map.of("prefix", "", "mensaje", "Hola {player}"));
        var mensajes = new YamlMessages(textos, aviso -> {});
        textos.put("mensaje", "cambiado");
        assertEquals("Hola {player}", mensajes.plain("mensaje", Map.of()));
    }

    @Test
    void mensajeOPrefijoAusenteSeIdentificaYAvisaUnaVez() {
        List<String> avisos = new ArrayList<>();
        var mensajes = new YamlMessages(Map.of(), avisos::add);
        assertEquals("[mensaje ausente: prefix][mensaje ausente: general.working]",
                mensajes.plain("general.working", Map.of()));
        mensajes.get("general.working");
        assertEquals(2, avisos.size());
        assertTrue(avisos.getLast().contains("general.working"));
    }
}
