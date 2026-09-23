package com.discordtowny.storage;

import java.util.Optional;

/**
 * Internal plugin settings, in key-value pairs. Blocks: see {@link Storage}.
 *
 * <p>Exists for the few things the plugin needs to remember between restarts
 * that do not fit into any dedicated table. The case that motivated it: the ID
 * of the global mayor role. Recognizing it by its name does not work, because an
 * administrator could rename it in Discord and then the plugin would stop
 * recognizing as its own a role that actually is.
 *
 * <p>This is NOT administrator configuration: that lives in config.yml and is
 * edited by hand. Here only goes state that the plugin writes to itself.
 */
public interface SettingsRepository {

    /** ID of the global mayor role. */
    String KEY_MAYOR_ROLE_ID = "mayor_role_id";

    /** Custom chat prefix set by administrators. */
    String KEY_CHAT_PREFIX = "chat_prefix";

    Optional<String> get(String key);

    /** Inserts or replaces. */
    void put(String key, String value);

    void delete(String key);
}
