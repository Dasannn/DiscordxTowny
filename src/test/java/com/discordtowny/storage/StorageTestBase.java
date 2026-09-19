package com.discordtowny.storage;

import com.discordtowny.config.PluginConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Base for storage tests: starts a HikariStorage against
 * SQLite in a temporary file and closes it upon finishing each test.
 *
 * <p>A temporary file is used instead of :memory: to guarantee
 * compatibility with HikariCP, which may open multiple connections, and SQLite
 * in memory does not share state across different connections unless
 * cache=shared is used (not always available). With pool_size=1 and a temporary
 * file, the same per-test isolation is obtained without ambiguity.
 */
abstract class StorageTestBase {

    protected HikariStorage storage;
    protected LinkRepository links;
    protected SpaceRepository spaces;
    protected AuditRepository audit;

    private Path dbFile;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("discordtowny-test-", ".db");

        PluginConfig.Database dbConfig = new PluginConfig.Database(
                PluginConfig.Database.Type.SQLITE,
                "localhost",
                3306,
                dbFile.toAbsolutePath().toString(),
                "",
                "",
                "dt_",
                1,
                1,
                Duration.ofSeconds(5));

        storage = new HikariStorage(dbConfig, Logger.getLogger("StorageTest"));
        storage.initialize();
        links  = storage.links();
        spaces = storage.spaces();
        audit  = storage.audit();
    }

    @AfterEach
    void tearDown() throws IOException {
        storage.close();
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-shm"));
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-wal"));
        }
    }
}
