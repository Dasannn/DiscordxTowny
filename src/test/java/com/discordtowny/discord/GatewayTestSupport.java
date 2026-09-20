package com.discordtowny.discord;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.storage.SettingsRepository;
import com.discordtowny.storage.SpaceRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;

import java.util.logging.Logger;

/**
 * Lets a test outside this package build a gateway that is already connected.
 *
 * <p>The constructor that takes a {@link JDA} is package-private on purpose: JDA
 * types do not belong in this plugin's public API. A wiring test still needs one,
 * so the door is here, in test code, rather than widened in the production class.
 */
public final class GatewayTestSupport {

    private GatewayTestSupport() {
    }

    public static JdaDiscordGateway connectedGateway(
            PluginConfig config,
            SpaceRepository spaces,
            SettingsRepository settings,
            Logger logger,
            JDA jda,
            Guild guild) {
        return new JdaDiscordGateway(config, spaces, settings, logger, jda, guild);
    }
}
