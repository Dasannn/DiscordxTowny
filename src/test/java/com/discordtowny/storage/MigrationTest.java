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
 * Verifies that migrations are applied correctly from scratch and that
 * they are idempotent (can be initialized twice on the same schema
 * without error).
 */
class MigrationTest extends StorageTestBase {

    @Test
    void migrationsAppliedFromScratch() throws SQLException {
        assertTrue(storage.isHealthy(), "Storage must be healthy after migrations");

        try (Connection conn = storage.dataSource().getConnection()) {
            // Verify that the version table recorded the latest version.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT MAX(version) FROM dt_schema_version")) {
                assertTrue(rs.next(), "Record must exist in the version table");
                assertEquals(5, rs.getInt(1), "The final recorded version must be 5");
            }

            // Verify the physical existence of the schema tables.
            Set<String> tables = new HashSet<>();
            try (ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }

            assertTrue(tables.contains("dt_schema_version"), "Table dt_schema_version must exist physically");
            assertTrue(tables.contains("dt_links"), "Table dt_links must exist physically");
            assertTrue(tables.contains("dt_link_codes"), "Table dt_link_codes must exist physically");
            assertTrue(tables.contains("dt_spaces"), "Table dt_spaces must exist physically");
            assertTrue(tables.contains("dt_audit_log"), "Table dt_audit_log must exist physically");
        }
    }

    @Test
    void idempotentMigrations() throws IOException {
        // We create a second storage on a new file to verify
        // that migrations do not fail if tables already exist
        // (CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS).
        Path tmpDb = Files.createTempFile("discordtowny-idempotente-", ".db");
        try {
            PluginConfig.Database dbConfig = new PluginConfig.Database(
                    PluginConfig.Database.Type.SQLITE,
                    "localhost", 3306, tmpDb.toAbsolutePath().toString(), "", "",
                    "dt_", 1, 1, Duration.ofSeconds(5));

            HikariStorage storage2 = new HikariStorage(dbConfig, Logger.getLogger("MigrationTest2"));
            // First initialization: creates everything.
            assertDoesNotThrow(storage2::initialize);
            storage2.close();

            // Second initialization on the same file: must not throw.
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
    void storageIsHealthyBeforeAndAfterOperating() {
        assertTrue(storage.isHealthy());
        // Basic operation to verify that health is not lost when using the pool.
        assertDoesNotThrow(() -> links.count());
        assertTrue(storage.isHealthy());
    }
}
