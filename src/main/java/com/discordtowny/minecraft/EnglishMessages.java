package com.discordtowny.minecraft;

import com.discordtowny.config.Messages;
import com.discordtowny.config.YamlMessages;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides bundled English messages for console output.
 *
 * <p>Spec 9.1 mandates that console messages remain in English regardless of the
 * configured server language, ensuring log files read consistently for support
 * and diagnostics.
 */
public final class EnglishMessages {

    private static volatile Messages instance;

    private EnglishMessages() {}

    /**
     * Returns the singleton instance of bundled English messages.
     */
    public static Messages bundled() {
        Messages current = instance;
        if (current != null) {
            return current;
        }
        synchronized (EnglishMessages.class) {
            if (instance != null) {
                return instance;
            }
            Map<String, String> map = new HashMap<>();
            try (InputStream in = EnglishMessages.class.getResourceAsStream("/messages_en.yml")) {
                if (in != null) {
                    try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                        YamlConfiguration yaml = new YamlConfiguration();
                        yaml.load(reader);
                        for (String key : yaml.getKeys(true)) {
                            if (yaml.isString(key)) {
                                map.put(key, yaml.getString(key));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Classpath resource load failure fallback
            }
            instance = new YamlMessages(map, map, "messages_en.yml", s -> {});
            return instance;
        }
    }
}
