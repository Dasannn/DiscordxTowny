package com.discordtowny.storage;

import java.util.Optional;

/**
 * Ajustes internos del plugin, en pares clave-valor. Bloquea: ver {@link Storage}.
 *
 * <p>Existe para lo poco que el plugin necesita recordar entre arranques y no
 * encaja en ninguna tabla propia. El caso que lo motivo: el ID del rol global
 * de alcalde. Reconocerlo por su nombre no sirve, porque un administrador puede
 * renombrarlo en Discord y entonces el plugin dejaria de reconocer como suyo un
 * rol que si lo es.
 *
 * <p>Esto NO es configuracion del administrador: esa vive en config.yml y se
 * edita a mano. Aqui solo va estado que el plugin se escribe a si mismo.
 */
public interface SettingsRepository {

    /** ID del rol global de alcalde. */
    String KEY_MAYOR_ROLE_ID = "mayor_role_id";

    Optional<String> get(String key);

    /** Inserta o reemplaza. */
    void put(String key, String value);

    void delete(String key);
}
