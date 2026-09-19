package com.discordtowny.storage;

import com.discordtowny.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for lifecycle and configuration of {@link HikariStorage}.
 */
class HikariStorageTest {

    private static final Logger LOGGER = Logger.getLogger("HikariStorageTest");

    @Test
    void sqlitePreservesSingleConnectionPoolEvenIfConfigHasHigherValues() throws IOException, SQLException {
        // In config.yml the default value of poolMaximumSize is 10 and poolMinimumIdle is 2.
        // For SQLite these values must never overwrite the 1 connection limit.
        Path tmpDb = Files.createTempFile("discordtowny-pool-test-", ".db");
        try {
            PluginConfig.Database dbConfig = new PluginConfig.Database(
                    PluginConfig.Database.Type.SQLITE,
                    "localhost",
                    3306,
                    tmpDb.toAbsolutePath().toString(),
                    "",
                    "",
                    "dt_",
                    10,
                    2,
                    Duration.ofMillis(250));

            HikariStorage storage = new HikariStorage(dbConfig, LOGGER);

            // 1. Verification at HikariConfig level
            HikariConfig config = storage.buildConfig();
            assertEquals(1, config.getMaximumPoolSize(),
                    "SQLite must force maximumPoolSize to 1 regardless of config.yml");
            assertEquals(1, config.getMinimumIdle(),
                    "SQLite must force minimumIdle to 1 regardless of config.yml");

            // 2. Verification on active DataSource after initializing
            storage.initialize();
            assertNotNull(storage.dataSource());
            assertEquals(1, storage.dataSource().getMaximumPoolSize(),
                    "Active SQLite pool must have maximum size 1");
            assertEquals(1, storage.dataSource().getMinimumIdle(),
                    "Active SQLite pool must have minimum idle 1");

            // 3. Concurrency operational verification: upon taking the only connection,
            // requesting a second one must fail due to timeout as there are no more connections.
            try (Connection conn1 = storage.dataSource().getConnection()) {
                assertNotNull(conn1);
                assertThrows(SQLException.class, () -> storage.dataSource().getConnection(),
                        "Getting more than 1 concurrent connection must not be allowed in SQLite");
            }

            storage.close();
        } finally {
            Files.deleteIfExists(tmpDb);
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-shm"));
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-wal"));
        }
    }

    @Test
    void mysqlAndMariaDbRespectConfiguredPool() throws StorageException {
        // For MySQL and MariaDB, configured values must be assigned to the pool without being forced to 1.
        PluginConfig.Database mysqlConfig = new PluginConfig.Database(
                PluginConfig.Database.Type.MYSQL,
                "localhost",
                3306,
                "discordtowny",
                "user",
                "pass",
                "dt_",
                10,
                2,
                Duration.ofSeconds(5));

        HikariStorage mysqlStorage = new HikariStorage(mysqlConfig, LOGGER);
        HikariConfig builtMysql = mysqlStorage.buildConfig();
        assertEquals(10, builtMysql.getMaximumPoolSize(),
                "MySQL must respect configured poolMaximumSize");
        assertEquals(2, builtMysql.getMinimumIdle(),
                "MySQL must respect configured poolMinimumIdle");

        PluginConfig.Database mariaConfig = new PluginConfig.Database(
                PluginConfig.Database.Type.MARIADB,
                "localhost",
                3306,
                "discordtowny",
                "user",
                "pass",
                "dt_",
                15,
                4,
                Duration.ofSeconds(5));

        HikariStorage mariaStorage = new HikariStorage(mariaConfig, LOGGER);
        HikariConfig builtMaria = mariaStorage.buildConfig();
        assertEquals(15, builtMaria.getMaximumPoolSize(),
                "MariaDB must respect configured poolMaximumSize");
        assertEquals(4, builtMaria.getMinimumIdle(),
                "MariaDB must respect configured poolMinimumIdle");
    }

    @Test
    void initializeThrowsIllegalStateExceptionIfAlreadyInitialized() throws IOException {
        Path tmpDb = Files.createTempFile("discordtowny-double-init-", ".db");
        try {
            PluginConfig.Database dbConfig = new PluginConfig.Database(
                    PluginConfig.Database.Type.SQLITE,
                    "localhost",
                    3306,
                    tmpDb.toAbsolutePath().toString(),
                    "",
                    "",
                    "dt_",
                    1,
                    1,
                    Duration.ofSeconds(5));

            HikariStorage storage = new HikariStorage(dbConfig, LOGGER);
            storage.initialize();

            // Calling initialize a second time without closing must throw IllegalStateException
            // to avoid leaking the previous DataSource.
            assertThrows(IllegalStateException.class, storage::initialize,
                    "Reinitializing an already opened storage must throw IllegalStateException");

            storage.close();
        } finally {
            Files.deleteIfExists(tmpDb);
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-shm"));
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-wal"));
        }
    }

    @Test
    void initializeAllowedAfterClosing() throws IOException {
        Path tmpDb = Files.createTempFile("discordtowny-reinit-", ".db");
        try {
            PluginConfig.Database dbConfig = new PluginConfig.Database(
                    PluginConfig.Database.Type.SQLITE,
                    "localhost",
                    3306,
                    tmpDb.toAbsolutePath().toString(),
                    "",
                    "",
                    "dt_",
                    1,
                    1,
                    Duration.ofSeconds(5));

            HikariStorage storage = new HikariStorage(dbConfig, LOGGER);
            storage.initialize();
            assertTrue(storage.isHealthy());

            // When closing the storage, initializing again must be allowed without throwing an exception.
            storage.close();
            assertFalse(storage.isHealthy());

            assertDoesNotThrow(storage::initialize,
                    "It must be possible to initialize the storage after having been closed");
            assertTrue(storage.isHealthy());

            storage.close();
        } finally {
            Files.deleteIfExists(tmpDb);
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-shm"));
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-wal"));
        }
    }
}
