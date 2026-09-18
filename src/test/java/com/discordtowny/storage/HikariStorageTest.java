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
 * Tests de ciclo de vida y configuracion de {@link HikariStorage}.
 */
class HikariStorageTest {

    private static final Logger LOGGER = Logger.getLogger("HikariStorageTest");

    @Test
    void sqliteConservaPoolDeUnaConexionAunqueConfigTengaValoresSuperiores() throws IOException, SQLException {
        // En config.yml el valor por defecto de poolMaximumSize es 10 y poolMinimumIdle es 2.
        // Para SQLite estos valores nunca deben sobreescribir el limite de 1 conexion.
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

            // 1. Verificacion a nivel de HikariConfig
            HikariConfig config = storage.buildConfig();
            assertEquals(1, config.getMaximumPoolSize(),
                    "SQLite debe forzar maximumPoolSize a 1 independientemente de config.yml");
            assertEquals(1, config.getMinimumIdle(),
                    "SQLite debe forzar minimumIdle a 1 independientemente de config.yml");

            // 2. Verificacion en el DataSource activo tras inicializar
            storage.initialize();
            assertNotNull(storage.dataSource());
            assertEquals(1, storage.dataSource().getMaximumPoolSize(),
                    "El pool activo de SQLite debe tener tamano maximo 1");
            assertEquals(1, storage.dataSource().getMinimumIdle(),
                    "El pool activo de SQLite debe tener minimo idle 1");

            // 3. Verificacion operacional de concurrencia: al tomar la unica conexion,
            // pedir una segunda debe fallar por timeout al no haber mas conexiones.
            try (Connection conn1 = storage.dataSource().getConnection()) {
                assertNotNull(conn1);
                assertThrows(SQLException.class, () -> storage.dataSource().getConnection(),
                        "No se debe permitir obtener mas de 1 conexion simultanea en SQLite");
            }

            storage.close();
        } finally {
            Files.deleteIfExists(tmpDb);
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-shm"));
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-wal"));
        }
    }

    @Test
    void mysqlYMariaDbRespetanPoolConfigurado() throws StorageException {
        // Para MySQL y MariaDB, los valores configurados deben asignarse al pool sin forzarse a 1.
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
                "MySQL debe respetar poolMaximumSize configurado");
        assertEquals(2, builtMysql.getMinimumIdle(),
                "MySQL debe respetar poolMinimumIdle configurado");

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
                "MariaDB debe respetar poolMaximumSize configurado");
        assertEquals(4, builtMaria.getMinimumIdle(),
                "MariaDB debe respetar poolMinimumIdle configurado");
    }

    @Test
    void initializeLanzaIllegalStateExceptionSiYaEstaInicializado() throws IOException {
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

            // Llamar a initialize por segunda vez sin cerrar debe lanzar IllegalStateException
            // para evitar fugar el DataSource anterior.
            assertThrows(IllegalStateException.class, storage::initialize,
                    "Reinicializar un storage ya abierto debe lanzar IllegalStateException");

            storage.close();
        } finally {
            Files.deleteIfExists(tmpDb);
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-shm"));
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-wal"));
        }
    }

    @Test
    void initializePermitidoTrasCerrar() throws IOException {
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

            // Al cerrar el storage, debe permitirse inicializar nuevamente sin lanzar excepcion.
            storage.close();
            assertFalse(storage.isHealthy());

            assertDoesNotThrow(storage::initialize,
                    "Debe ser posible inicializar el storage tras haber sido cerrado");
            assertTrue(storage.isHealthy());

            storage.close();
        } finally {
            Files.deleteIfExists(tmpDb);
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-shm"));
            Files.deleteIfExists(tmpDb.resolveSibling(tmpDb.getFileName() + "-wal"));
        }
    }
}
