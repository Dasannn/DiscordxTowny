# Revisión: feat/storage

Revisado: 11 archivos (storage/ y build.gradle.kts), 1778 líneas
Build: verde, 52 tests

## Hallazgos

| # | Severidad | Archivo:línea | Problema | Corrección propuesta |
| 1 | bloqueante | src/main/java/com/discordtowny/storage/HikariStorage.java:102 | `buildDataSource()` sobrescribe incondicionalmente el tamaño del pool para SQLite. Aunque `configureSqlite()` fija `maximumPoolSize(1)` y `minimumIdle(1)` (necesario porque SQLite no admite escrituras concurrentes), las líneas 102-103 sobreescriben dichos valores con `dbConfig.poolMaximumSize()` (10 por defecto en `config.yml`) y `dbConfig.poolMinimumIdle()` (2 por defecto). En producción con SQLite esto provoca bloqueos y excepciones `SQLITE_BUSY` (database is locked) bajo concurrencia. | Asignar `dbConfig.poolMaximumSize()` y `dbConfig.poolMinimumIdle()` únicamente cuando el motor sea MySQL/MariaDB (dentro de `configureMysql()`), o respetar el límite de 1 si el tipo es SQLite. |
| 2 | menor | src/main/java/com/discordtowny/storage/SqlAuditRepository.java:109 | Uso indebido de `rs.wasNull()` tras leer otra columna en `mapEvent()`. En JDBC, `wasNull()` comprueba el valor de la última columna leída (`success` en la línea 108, en lugar de `detail` leída en la línea 101). Aunque queda protegido por `detail == null`, si `success` fuera nulo descartaría el detalle erróneamente, además de alterar el orden de lectura secuencial recomendado. | Eliminar la llamada a `rs.wasNull()` y comprobar únicamente `detail == null ? Optional.empty() : Optional.of(detail)`. |
| 3 | menor | src/test/java/com/discordtowny/storage/MigrationTest.java:24 | Aserción trivial en el test `migrationesAplicadasDesde0()`. Solo comprueba que `storage.isHealthy()` sea cierto tras el `setUp()`, sin verificar en la base de datos que la tabla `schema_version` haya registrado la versión 4 ni la existencia real de las tablas. | Consultar la tabla de versiones (`schema_version`) para comprobar que el valor registrado es 4 y comprobar la presencia física de las tablas del esquema. |
| 4 | menor | src/main/java/com/discordtowny/storage/HikariStorage.java:39 | Re-inicialización sin control de fuga de recursos. Si se llama a `initialize()` más de una vez en la misma instancia, se crea un nuevo `HikariDataSource` y se sobreescribe la referencia sin cerrar el pool previo, fugando conexiones activas. | Lanzar `IllegalStateException` al inicio de `initialize()` si `dataSource != null && !dataSource.isClosed()`. |

Veredicto: requiere correcciones
