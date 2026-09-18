package com.discordtowny.discord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registro y persistencia de la identidad estable del rol global de alcalde.
 *
 * <p>Persiste el ID del rol para no depender del nombre en Discord,
 * evitando que renombrados en el servidor o roles duplicados hagan fallar
 * la gestion de roles o la revocacion en desvinculaciones.
 */
final class MayorRoleRegistry {

    private static final AtomicReference<String> CACHED_ID = new AtomicReference<>();

    private MayorRoleRegistry() {}

    private static Path storePath() {
        Path pluginsDir = Path.of("plugins", "DiscordTowny");
        return Files.isDirectory(pluginsDir)
                ? pluginsDir.resolve("mayor-role.id")
                : Path.of("mayor-role.id");
    }

    static Optional<String> getMayorRoleId() {
        String cached = CACHED_ID.get();
        if (cached != null && !cached.isBlank()) {
            return Optional.of(cached);
        }
        Path path = storePath();
        if (Files.exists(path)) {
            try {
                String read = Files.readString(path).trim();
                if (!read.isBlank()) {
                    CACHED_ID.set(read);
                    return Optional.of(read);
                }
            } catch (IOException ignored) {
                // Si no se puede leer el archivo se recurre a vacio
            }
        }
        return Optional.empty();
    }

    static void saveMayorRoleId(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return;
        }
        CACHED_ID.set(roleId);
        try {
            Path path = storePath();
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, roleId);
        } catch (IOException ignored) {
            // Entornos de prueba o sin permisos de escritura en disco
        }
    }

    static void clear() {
        CACHED_ID.set(null);
        try {
            Files.deleteIfExists(storePath());
        } catch (IOException ignored) {
        }
    }
}
