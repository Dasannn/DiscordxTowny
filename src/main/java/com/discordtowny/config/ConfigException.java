package com.discordtowny.config;

/**
 * Invalid configuration.
 *
 * <p>The message must state which key is wrong and what was expected. It must
 * never include the value of a sensitive key such as the token or password.
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }
}
