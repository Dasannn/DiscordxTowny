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
 * Base para los tests de almacenamiento: arranca un HikariStorage contra
 * SQLite en un archivo temporal y lo cierra al terminar cada test.
 *
 * <p>Se usa un archivo temporal en lugar de :memory: para garantizar
 * compatibilidad con HikariCP, que puede abrir varias conexiones y SQLite
 * en memoria no comparte estado entre conexiones distintas a menos que
 * se use cache=shared (no siempre disponible). Con pool_size=1 y archivo
 * temporal se obtiene el mismo aislamiento por test sin ambiguedad.
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
