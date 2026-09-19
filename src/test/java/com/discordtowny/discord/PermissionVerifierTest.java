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
 * Unit tests for bot permission and role hierarchy verification.
 */
class PermissionVerifierTest {

    @Test
    @DisplayName("Returns empty when the bot has all permissions and its role is above")
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

        assertTrue(result.isEmpty(), "Must succeed when permissions and roles are valid");
    }

    @Test
    @DisplayName("Detects when the bot is missing a required permission")
    void missingPermissionDetected() {
        Guild guild = mock(Guild.class);
        SelfMember self = mock(SelfMember.class);

        when(guild.getSelfMember()).thenReturn(self);
        // Has all permissions except MANAGE_ROLES
        when(self.hasPermission(any(Permission.class))).thenAnswer(invocation -> {
            Permission perm = invocation.getArgument(0);
            return perm != Permission.MANAGE_ROLES;
        });

        Optional<String> result = PermissionVerifier.verify(guild, List.of());

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("MANAGE_ROLES"),
                "The message must indicate the missing permission");
    }

    @Test
    @DisplayName("Detects when the bot has no assigned roles")
    void botHasNoRoles() {
        Guild guild = mock(Guild.class);
        SelfMember self = mock(SelfMember.class);

        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(any(Permission.class))).thenReturn(true);
        when(self.getRoles()).thenReturn(List.of());

        Optional<String> result = PermissionVerifier.verify(guild, List.of());

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("has no assigned roles"),
                "Must warn that the bot has no roles");
    }

    @Test
    @DisplayName("Detects when the bot role is not above managed roles")
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
        assertTrue(result.get().contains("is not above the roles it manages"),
                "Must warn that the bot role is not above managed roles");
    }
}
