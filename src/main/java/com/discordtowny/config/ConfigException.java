package com.discordtowny.config;

/**
 * Configuracion invalida.
 *
 * <p>El mensaje debe decir que clave esta mal y que se esperaba. Nunca debe
 * incluir el valor de una clave sensible como el token o la contrasena.
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }
}
