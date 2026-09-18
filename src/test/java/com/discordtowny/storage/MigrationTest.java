package com.discordtowny.storage;

import com.discordtowny.config.PluginConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica que las migraciones se aplican correctamente desde cero y que
 * son idempotentes (se puede inicializar dos veces sobre el mismo esquema
 * sin error).
 */
class MigrationTest extends StorageTestBase {

    @Test
    void migrationesAplicadasDesde0() throws SQLException {
        assertTrue(storage.isHealthy(), "El storage debe estar sano tras las migraciones");

        try (Connection conn = storage.dataSource().getConnection()) {
            // Verificar que la tabla de versiones registro la ultima version.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT MAX(version) FROM dt_schema_version")) {
                assertTrue(rs.next(), "Debe existir registro en la tabla de versiones");
                assertEquals(5, rs.getInt(1), "La version final registrada debe ser 5");
            }

            // Verificar la existencia fisica de las tablas del esquema.
            Set<String> tables = new HashSet<>();
            try (ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }

            assertTrue(tables.contains("dt_schema_version"), "La tabla dt_schema_version debe existir fisicamente");
            assertTrue(tables.contains("dt_links"), "La tabla dt_links debe existir fisicamente");
            assertTrue(tables.contains("dt_link_codes"), "La tabla dt_link_codes debe existir fisicamente");
            assertTrue(tables.contains("dt_spaces"), "La tabla dt_spaces debe existir fisicamente");
            assertTrue(tables.contains("dt_audit_log"), "La tabla dt_audit_log debe existir fisicamente");
        }
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
