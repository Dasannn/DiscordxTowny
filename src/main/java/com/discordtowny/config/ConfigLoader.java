package com.discordtowny.config;

import java.util.List;

/** Carga y valida config.yml y messages.yml. */
public interface ConfigLoader {

    /**
     * Lee y valida la configuracion.
     *
     * @throws ConfigException si algo es invalido, indicando la clave exacta.
     */
    PluginConfig load() throws ConfigException;

    Messages messages();

    /**
     * Revisa la configuracion sin aplicarla.
     *
     * @return lista vacia si todo esta bien, o un problema por elemento.
     */
    List<String> validate();
}
