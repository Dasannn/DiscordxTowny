package com.discordtowny.storage;

/**
 * Fallo de base de datos.
 *
 * <p>El mensaje se muestra en consola: no debe contener credenciales. Al
 * construirla a partir de una causa, revisa que la cadena de conexion no viaje
 * en el texto.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
