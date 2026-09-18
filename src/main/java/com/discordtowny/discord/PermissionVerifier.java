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
 * Comprueba al arrancar que el bot puede operar:
 * <ul>
 *   <li>El rol del bot esta por encima de todos los roles que gestiona.</li>
 *   <li>El bot tiene los permisos necesarios en el guild.</li>
 * </ul>
 *
 * <p>Si alguna condicion no se cumple, devuelve la razon. El plugin no debe
 * intentar operar si esta comprobacion falla.
 */
final class PermissionVerifier {

    /** Permisos que el bot necesita para operar. */
    static final EnumSet<Permission> REQUIRED_PERMISSIONS = EnumSet.of(
            Permission.MANAGE_ROLES,
            Permission.MANAGE_CHANNEL,
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.VOICE_CONNECT
    );

    private PermissionVerifier() {}

    /**
     * Verifica que el bot puede operar en el guild.
     *
     * @param guild  el guild donde opera el plugin
     * @param managedRoleIds los IDs de los roles que gestiona el plugin
     * @return vacio si todo esta bien, o la razon por la que no puede operar
     */
    static Optional<String> verify(Guild guild, List<String> managedRoleIds) {
        Member self = guild.getSelfMember();

        // 1. Comprobar permisos
        List<Permission> missing = new ArrayList<>();
        for (Permission perm : REQUIRED_PERMISSIONS) {
            if (!self.hasPermission(perm)) {
                missing.add(perm);
            }
        }
        if (!missing.isEmpty()) {
            return Optional.of("Al bot le faltan permisos en el guild: " + missing);
        }

        // 2. Comprobar que el rol del bot esta por encima de los que gestiona
        List<Role> selfRoles = self.getRoles();
        if (selfRoles.isEmpty()) {
            return Optional.of("El bot no tiene ningun rol asignado en el guild");
        }
        int highestBotPosition = selfRoles.stream()
                .mapToInt(Role::getPosition)
                .max()
                .orElse(0);

        List<String> problemRoles = new ArrayList<>();
        for (String roleId : managedRoleIds) {
            Role managed = guild.getRoleById(roleId);
            if (managed != null && managed.getPosition() >= highestBotPosition) {
                problemRoles.add(managed.getName() + " (posicion " + managed.getPosition() + ")");
            }
        }
        if (!problemRoles.isEmpty()) {
            return Optional.of(
                    "El rol del bot (posicion " + highestBotPosition
                    + ") no esta por encima de los roles que gestiona: " + problemRoles);
        }

        return Optional.empty();
    }
}
