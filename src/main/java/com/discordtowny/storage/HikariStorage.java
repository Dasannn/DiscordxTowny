package com.discordtowny.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.discordtowny.config.PluginConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * HikariCP implementation of {@link Storage}.
 *
 * <p>A single code path for MySQL/MariaDB and SQLite: the difference lies
 * in the connection string and pool settings, not in the DAOs.
 *
 * <p>The password and connection string do not travel in exception messages
 * or logs. See P7 in the constitution.
 */
public final class HikariStorage implements Storage {

    private final PluginConfig.Database dbConfig;
    private final Logger logger;

    private HikariDataSource dataSource;
    private SqlLinkRepository linkRepo;
    private SqlSpaceRepository spaceRepo;
    private SqlAuditRepository auditRepo;
    private SqlSettingsRepository settingsRepo;

    public HikariStorage(PluginConfig.Database dbConfig, Logger logger) {
        this.dbConfig = dbConfig;
        this.logger = logger;
    }

    // --- Storage ---

    @Override
    public void initialize() throws StorageException {
        if (dataSource != null && !dataSource.isClosed()) {
            throw new IllegalStateException("Storage already initialized: close current pool before reinitializing.");
        }
        dataSource = buildDataSource();
        boolean isSqlite = dbConfig.type() == PluginConfig.Database.Type.SQLITE;
        try (Connection conn = dataSource.getConnection()) {
            new MigrationRunner(dbConfig.tablePrefix(), logger, isSqlite).run(conn);
        } catch (SQLException e) {
            // Do not include the connection string or exceptions that might contain it in the message.
            throw new StorageException("Failed to obtain initial database connection");
        }
        String prefix = dbConfig.tablePrefix();
        linkRepo  = new SqlLinkRepository(dataSource, prefix);
        spaceRepo = new SqlSpaceRepository(dataSource, prefix, isSqlite);
        auditRepo = new SqlAuditRepository(dataSource, prefix);
        settingsRepo = new SqlSettingsRepository(dataSource, prefix, isSqlite);
        logger.info("Storage initialized (" + dbConfig.type() + ").");
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
    public SettingsRepository settings() {
        assertInitialized();
        return settingsRepo;
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
            logger.info("Database pool closed.");
        }
    }

    // --- pool construction ---

    HikariConfig buildConfig() throws StorageException {
        HikariConfig config = new HikariConfig();

        switch (dbConfig.type()) {
            case SQLITE -> configureSqlite(config);
            case MYSQL   -> configureMysql(config, "mysql");
            case MARIADB -> configureMysql(config, "mariadb");
            default -> throw new StorageException("Unrecognized database type: " + dbConfig.type());
        }

        config.setConnectionTimeout(dbConfig.connectionTimeout().toMillis());
        config.setPoolName("DiscordTowny-Pool");
        return config;
    }

    private HikariDataSource buildDataSource() throws StorageException {
        HikariConfig config = buildConfig();

        try {
            return new HikariDataSource(config);
        } catch (Exception e) {
            // Do not propagate the original exception: it may contain the URL with credentials.
            throw new StorageException(
                "Failed to create connection pool (" + dbConfig.type() + "). "
                + "Check host, port, database name, and credentials in config.yml.");
        }
    }

    private void configureSqlite(HikariConfig config) {
        // SQLite in WAL mode allows concurrent reads while writing.
        // With name == ":memory:" it is created in memory with shared cache so
        // that all pool connections see the same database (useful for tests).
        // In production, a disk file is used.
        String jdbcUrl;
        if (dbConfig.name().equals(":memory:")) {
            // file::memory:?cache=shared makes all JDBC connections share
            // the same in-memory DB within the same process.
            jdbcUrl = "jdbc:sqlite:file::memory:?cache=shared";
        } else {
            jdbcUrl = "jdbc:sqlite:" + sqliteFilePath();
        }
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName("org.sqlite.JDBC");
        // SQLite does not support a true pool: a single writer thread.
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("foreign_keys", "ON");
    }

    private void configureMysql(HikariConfig config, String scheme) {
        // The password goes in setPassword, not in the URL, so that HikariCP
        // does not include it in its debug logs.
        String url = String.format(
                "jdbc:%s://%s:%d/%s"
                + "?useSSL=false&characterEncoding=utf8&autoReconnect=false"
                + "&allowPublicKeyRetrieval=true",
                scheme, dbConfig.host(), dbConfig.port(), dbConfig.name());
        config.setJdbcUrl(url);
        config.setUsername(dbConfig.user());
        config.setPassword(dbConfig.password());
        config.setMaximumPoolSize(dbConfig.poolMaximumSize());
        config.setMinimumIdle(dbConfig.poolMinimumIdle());
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.setConnectionTestQuery("SELECT 1");
    }

    /**
     * SQLite file path.
     *
     * <p>If {@code dbConfig.name()} looks like an absolute path (starts with
     * a directory separator or drive letter), it is used as is; this allows
     * tests to pass a temporary file. In production the plugin will overwrite
     * this method or pass the correct path.
     */
    private String sqliteFilePath() {
        String name = dbConfig.name();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return "discordtowny.db";
    }

    HikariDataSource dataSource() {
        return dataSource;
    }

    private void assertInitialized() {
        if (dataSource == null) {
            throw new IllegalStateException("Storage not initialized: call initialize() first.");
        }
    }
}
