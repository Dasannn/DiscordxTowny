package com.discordtowny.discord;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Checks at startup that the bot can operate:
 * <ul>
 *   <li>The bot's role is above all roles it manages.</li>
 *   <li>The bot has the required permissions in the guild.</li>
 * </ul>
 *
 * <p>If any condition is not met, returns the reason. The plugin must not
 * attempt to operate if this check fails.
 */
final class PermissionVerifier {

    /** Permissions that the bot needs to operate. */
    static final EnumSet<Permission> REQUIRED_PERMISSIONS = EnumSet.of(
            Permission.MANAGE_ROLES,
            Permission.MANAGE_CHANNEL,
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.VOICE_CONNECT
    );

    private PermissionVerifier() {}

    /**
     * Verifies that the bot can operate in the guild.
     *
     * @param guild  the guild where the plugin operates
     * @param managedRoleIds the IDs of the roles managed by the plugin
     * @return empty if all is well, or the reason why it cannot operate
     */
    static Optional<String> verify(Guild guild, List<String> managedRoleIds) {
        Member self = guild.getSelfMember();

        // 1. Check permissions
        List<Permission> missing = new ArrayList<>();
        for (Permission perm : REQUIRED_PERMISSIONS) {
            if (!self.hasPermission(perm)) {
                missing.add(perm);
            }
        }
        if (!missing.isEmpty()) {
            return Optional.of("The bot is missing permissions in the guild: " + missing);
        }

        // 2. Check that the bot's role is above the ones it manages
        List<Role> selfRoles = self.getRoles();
        if (selfRoles.isEmpty()) {
            return Optional.of("The bot has no assigned roles in the guild");
        }
        int highestBotPosition = selfRoles.stream()
                .mapToInt(Role::getPosition)
                .max()
                .orElse(0);

        List<String> problemRoles = new ArrayList<>();
        for (String roleId : managedRoleIds) {
            Role managed = guild.getRoleById(roleId);
            if (managed != null && managed.getPosition() >= highestBotPosition) {
                problemRoles.add(managed.getName() + " (position " + managed.getPosition() + ")");
            }
        }
        if (!problemRoles.isEmpty()) {
            return Optional.of(
                    "The bot's role (position " + highestBotPosition
                    + ") is not above the roles it manages: " + problemRoles);
        }

        return Optional.empty();
    }
}
