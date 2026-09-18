package com.discordtowny.discord;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pruebas unitarias de la verificacion de permisos y jerarquia de roles del bot.
 */
class PermissionVerifierTest {

    @Test
    @DisplayName("Devuelve vacio cuando el bot tiene todos los permisos y su rol esta por encima")
    void validPermissionsAndRoleHierarchy() {
        Guild guild = mock(Guild.class);
        SelfMember self = mock(SelfMember.class);
        Role botRole = mock(Role.class);
        Role managedRole = mock(Role.class);

        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(any(Permission.class))).thenReturn(true);
        when(self.getRoles()).thenReturn(List.of(botRole));
        when(botRole.getPosition()).thenReturn(10);

        when(guild.getRoleById("role-town-1")).thenReturn(managedRole);
        when(managedRole.getPosition()).thenReturn(5);
        when(managedRole.getName()).thenReturn("TownRole");

        Optional<String> result = PermissionVerifier.verify(guild, List.of("role-town-1"));

        assertTrue(result.isEmpty(), "Debe ser exitoso cuando los permisos y roles son validos");
    }

    @Test
    @DisplayName("Detecta cuando al bot le falta un permiso obligatorio")
    void missingPermissionDetected() {
        Guild guild = mock(Guild.class);
        SelfMember self = mock(SelfMember.class);

        when(guild.getSelfMember()).thenReturn(self);
        // Tiene todos los permisos excepto MANAGE_ROLES
        when(self.hasPermission(any(Permission.class))).thenAnswer(invocation -> {
            Permission perm = invocation.getArgument(0);
            return perm != Permission.MANAGE_ROLES;
        });

        Optional<String> result = PermissionVerifier.verify(guild, List.of());

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("MANAGE_ROLES"),
                "El mensaje debe senalar el permiso faltante");
    }

    @Test
    @DisplayName("Detecta cuando el bot no tiene ningun rol asignado")
    void botHasNoRoles() {
        Guild guild = mock(Guild.class);
        SelfMember self = mock(SelfMember.class);

        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(any(Permission.class))).thenReturn(true);
        when(self.getRoles()).thenReturn(List.of());

        Optional<String> result = PermissionVerifier.verify(guild, List.of());

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("no tiene ningun rol"),
                "Debe avisar que el bot no tiene roles");
    }

    @Test
    @DisplayName("Detecta cuando el rol del bot no esta por encima de los roles gestionados")
    void botRoleNotAboveManagedRoles() {
        Guild guild = mock(Guild.class);
        SelfMember self = mock(SelfMember.class);
        Role botRole = mock(Role.class);
        Role managedRole = mock(Role.class);

        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(any(Permission.class))).thenReturn(true);
        when(self.getRoles()).thenReturn(List.of(botRole));
        when(botRole.getPosition()).thenReturn(4);

        when(guild.getRoleById("role-town-1")).thenReturn(managedRole);
        when(managedRole.getPosition()).thenReturn(6);
        when(managedRole.getName()).thenReturn("TownRoleMayor");

        Optional<String> result = PermissionVerifier.verify(guild, List.of("role-town-1"));

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("no esta por encima"),
                "Debe avisar que el rol del bot no esta por encima de los roles gestionados");
    }
}
