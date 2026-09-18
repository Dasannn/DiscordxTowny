package com.discordtowny.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.discordtowny.config.PluginConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Implementacion de {@link Storage} sobre HikariCP.
 *
 * <p>Un unico camino de codigo para MySQL/MariaDB y SQLite: la diferencia
 * esta en la cadena de conexion y en ajustes del pool, no en los DAOs.
 *
 * <p>La contrasena y la cadena de conexion no viajan en mensajes de excepcion
 * ni en logs. Ver P7 de la constitucion.
 */
public final class HikariStorage implements Storage {

    private final PluginConfig.Database dbConfig;
    private final Logger logger;

    private HikariDataSource dataSource;
    private SqlLinkRepository linkRepo;
    private SqlSpaceRepository spaceRepo;
    private SqlAuditRepository auditRepo;

    public HikariStorage(PluginConfig.Database dbConfig, Logger logger) {
        this.dbConfig = dbConfig;
        this.logger = logger;
    }

    // --- Storage ---

    @Override
    public void initialize() throws StorageException {
        dataSource = buildDataSource();
        boolean isSqlite = dbConfig.type() == PluginConfig.Database.Type.SQLITE;
        try (Connection conn = dataSource.getConnection()) {
            new MigrationRunner(dbConfig.tablePrefix(), logger, isSqlite).run(conn);
        } catch (SQLException e) {
            // No incluir la cadena de conexion ni excepciones que puedan contenerla en el mensaje.
            throw new StorageException("No se pudo obtener conexion inicial de la base de datos");
        }
        String prefix = dbConfig.tablePrefix();
        linkRepo  = new SqlLinkRepository(dataSource, prefix);
        spaceRepo = new SqlSpaceRepository(dataSource, prefix, isSqlite);
        auditRepo = new SqlAuditRepository(dataSource, prefix);
        logger.info("Almacenamiento inicializado (" + dbConfig.type() + ").");
    }

    @Override
    public LinkRepository links() {
        assertInitialized();
        return linkRepo;
    }

    @Override
    public SpaceRepository spaces() {
        assertInitialized();
        return spaceRepo;
    }

    @Override
    public AuditRepository audit() {
        assertInitialized();
        return auditRepo;
    }

    @Override
    public boolean isHealthy() {
        if (dataSource == null || dataSource.isClosed()) return false;
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("Pool de base de datos cerrado.");
        }
    }

    // --- construccion del pool ---

    private HikariDataSource buildDataSource() throws StorageException {
        HikariConfig config = new HikariConfig();

        switch (dbConfig.type()) {
            case SQLITE -> configureSqlite(config);
            case MYSQL   -> configureMysql(config, "mysql");
            case MARIADB -> configureMysql(config, "mariadb");
            default -> throw new StorageException("Tipo de base de datos no reconocido: " + dbConfig.type());
        }

        config.setMaximumPoolSize(dbConfig.poolMaximumSize());
        config.setMinimumIdle(dbConfig.poolMinimumIdle());
        config.setConnectionTimeout(dbConfig.connectionTimeout().toMillis());
        config.setPoolName("DiscordTowny-Pool");

        try {
            return new HikariDataSource(config);
        } catch (Exception e) {
            // No propagar la excepcion original: puede contener la URL con credenciales.
            throw new StorageException(
                "No se pudo crear el pool de conexiones (" + dbConfig.type() + "). "
                + "Revisa host, puerto, nombre de base de datos y credenciales en config.yml.");
        }
    }

    private void configureSqlite(HikariConfig config) {
        // SQLite en modo WAL permite lecturas concurrentes mientras se escribe.
        // Con name == ":memory:" se crea en memoria con cache compartida para
        // que todas las conexiones del pool vean la misma base de datos (util
        // para tests). En produccion se usa un archivo en disco.
        String jdbcUrl;
        if (dbConfig.name().equals(":memory:")) {
            // file::memory:?cache=shared hace que todas las conexiones JDBC
            // compartan la misma BD en memoria dentro del mismo proceso.
            jdbcUrl = "jdbc:sqlite:file::memory:?cache=shared";
        } else {
            jdbcUrl = "jdbc:sqlite:" + sqliteFilePath();
        }
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName("org.sqlite.JDBC");
        // SQLite no admite pool real: un unico hilo de escritura.
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("foreign_keys", "ON");
    }

    private void configureMysql(HikariConfig config, String scheme) {
        // La contrasena va en setPassword, no en la URL, para que HikariCP
        // no la incluya en sus logs de depuracion.
        String url = String.format(
                "jdbc:%s://%s:%d/%s"
                + "?useSSL=false&characterEncoding=utf8&autoReconnect=false"
                + "&allowPublicKeyRetrieval=true",
                scheme, dbConfig.host(), dbConfig.port(), dbConfig.name());
        config.setJdbcUrl(url);
        config.setUsername(dbConfig.user());
        config.setPassword(dbConfig.password());
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.setConnectionTestQuery("SELECT 1");
    }

    /**
     * Ruta del archivo SQLite.
     *
     * <p>Si {@code dbConfig.name()} parece un path absoluto (comienza con
     * separador de directorio o letra de unidad), se usa tal cual; esto
     * permite que los tests pasen un archivo temporal. En produccion el
     * plugin sobrescribira este metodo o pasara el path correcto.
     */
    private String sqliteFilePath() {
        String name = dbConfig.name();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return "discordtowny.db";
    }

    private void assertInitialized() {
        if (dataSource == null) {
            throw new IllegalStateException("Storage no inicializado: llama a initialize() primero.");
        }
    }
}
