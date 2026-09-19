package com.discordtowny.config;

import java.util.List;

/** Loads and validates config.yml and messages.yml. */
public interface ConfigLoader {

    /**
     * Reads and validates the configuration.
     *
     * @throws ConfigException if anything is invalid, indicating the exact key.
     */
    PluginConfig load() throws ConfigException;

    Messages messages();

    /**
     * Inspects the configuration without applying it.
     *
     * @return empty list if everything is fine, or one problem per element.
     */
    List<String> validate();
}
