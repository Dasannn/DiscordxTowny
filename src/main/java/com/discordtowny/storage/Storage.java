package com.discordtowny.storage;

/**
 * Acceso a la base de datos.
 *
 * <p><b>Todos los metodos de los repositorios bloquean.</b> Se llaman desde el
 * pool del plugin, nunca desde el hilo principal del servidor ni desde un hilo
 * de JDA. Se prefiere esto a devolver futuros en todas partes: el codigo que
 * los consume ya corre fuera del hilo principal, y encadenar futuros solo
 * anadiria ruido.
 *
 * <p>Una implementacion sirve tanto para MySQL/MariaDB como para SQLite: el
 * codigo es el mismo, cambia la cadena de conexion.
 */
public interface Storage extends AutoCloseable {

    /**
     * Abre el pool y aplica las migraciones pendientes.
     *
     * @throws StorageException si no se puede conectar o una migracion falla.
     *     Sin base de datos el plugin no opera sobre Discord: no se toca el
     *     guild sin poder persistir el resultado.
     */
    void initialize() throws StorageException;

    LinkRepository links();

    SpaceRepository spaces();

    AuditRepository audit();

    /** Cierto si el pool responde. Lo consultan los comandos antes de operar. */
    boolean isHealthy();

    @Override
    void close();
}
