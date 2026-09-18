package com.discordtowny.config;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Textos inmutables; los valores de marcadores nunca se interpretan como formato. */
public final class YamlMessages implements Messages {
    private static final Pattern MARCADOR = Pattern.compile("\\{([^{}]+)}");
    private final Map<String, String> textos;
    private final Consumer<String> aviso;
    private final Set<String> ausentes = ConcurrentHashMap.newKeySet();

    public YamlMessages(Map<String, String> textos, Consumer<String> aviso) {
        this.textos = Map.copyOf(textos);
        this.aviso = aviso;
    }

    private String texto(String clave) {
        String valor = textos.get(clave);
        if (valor != null) return valor;
        if (ausentes.add(clave)) aviso.accept("messages.yml: falta el mensaje " + clave);
        return "[mensaje ausente: " + clave + "]";
    }

    @Override
    public Component get(String key, Map<String, String> placeholders) {
        Component mensaje = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(texto("prefix") + texto(key));
        return mensaje.replaceText(regla -> regla.match(MARCADOR).replacement((coincidencia, original) -> {
            String valor = placeholders.get(coincidencia.group(1));
            return valor == null ? original.build() : Component.text(valor);
        }));
    }

    @Override
    public String plain(String key, Map<String, String> placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(get(key, placeholders));
    }
}
