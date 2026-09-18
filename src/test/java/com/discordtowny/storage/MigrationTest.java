package com.discordtowny.storage;

import com.discordtowny.config.PluginConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica que las migraciones se aplican correctamente desde cero y que
 * son idempotentes (se puede inicializar dos veces sobre el mismo esquema
 * sin error).
 */
class MigrationTest extends StorageTestBase {

    @Test
    void migrationesAplicadasDesde0() {
        // Si el setUp no lanzo excepcion, las cuatro migraciones pasaron.
        assertTrue(storage.isHealthy(), "El storage debe estar sano tras las migraciones");
    }

    @Test
    void migracionesIdempotentes() throws IOException {
        // Creamos un segundo storage sobre un archivo nuevo para verificar
        // que las migraciones no fallan si ya existen las tablas
        // (CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS).
        Path tmpDb = Files.createTempFile("discordtowny-idempotente-", ".db");
        try {
            PluginConfig.Database dbConfig = new PluginConfig.Database(
                    PluginConfig.Database.Type.SQLITE,
                    "localhost", 3306, tmpDb.toAbsolutePath().toString(), "", "",
                    "dt_", 1, 1, Duration.ofSeconds(5));

            HikariStorage storage2 = new HikariStorage(dbConfig, Logger.getLogger("MigrationTest2"));
            // Primera inicializacion: crea todo.
            assertDoesNotThrow(storage2::initialize);
            storage2.close();

            // Segunda inicializacion sobre el mismo archivo: no debe lanzar.
            HikariStorage storage3 = new HikariStorage(dbConfig, Logger.getLogger("MigrationTest3"));
            assertDoesNotThrow(storage3::initialize);
            storage3.close();
        } finally {
            Files.deleteIfExists(tmpDb);
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-shm"));
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-wal"));
        }
    }

    @Test
    void storageIsHealthyAntesYDespuesDeOperar() {
        assertTrue(storage.isHealthy());
        // Operacion basica para verificar que no se pierde la salud al usar el pool.
        assertDoesNotThrow(() -> links.count());
        assertTrue(storage.isHealthy());
    }
}
