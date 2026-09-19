package com.discordtowny.discord;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.storage.SettingsRepository;
import com.discordtowny.storage.SpaceRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JdaDiscordGateway without network connection.
 */
class JdaDiscordGatewayTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    private PluginConfig config;
    private SpaceRepository spaces;
    private SettingsRepository settings;
    private PluginConfig.Discord discordConfig;
    private PluginConfig.Roles rolesConfig;

    @BeforeEach
    void setUp() {
        config = mock(PluginConfig.class);
        spaces = mock(SpaceRepository.class);
        settings = mock(SettingsRepository.class);
        discordConfig = mock(PluginConfig.Discord.class);
        rolesConfig = mock(PluginConfig.Roles.class);

        when(config.discord()).thenReturn(discordConfig);
        when(config.roles()).thenReturn(rolesConfig);
        when(discordConfig.token()).thenReturn("mi-token-super-secreto-999");
        when(rolesConfig.mayorRoleName()).thenReturn("Alcalde");
    }

    @Test
    @DisplayName("isAvailable is false before connecting")
    void isAvailableFalseBeforeConnect() {
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER);
        assertFalse(gateway.isAvailable(), "Gateway must not be available before connecting");
    }

    @Test
    @DisplayName("submit() with disconnected gateway returns transient failure without throwing exception")
    void submitWhenDisconnectedDoesNotThrow() {
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER);
        var op = new GuildOperation.DeleteSpace(UUID.randomUUID(), "town");

        CompletableFuture<OperationOutcome> future = gateway.submit(op);

        assertNotNull(future);
        assertFalse(future.isCompletedExceptionally(), "The future must never complete exceptionally");
        OperationOutcome outcome = future.join();
        assertFalse(outcome.succeeded());
        assertEquals(OperationOutcome.Status.TRANSIENT_FAILURE, outcome.status());
        assertTrue(outcome.reason().orElse("").contains("Discord is unavailable"));
    }

    @Test
    @DisplayName("verifyPermissions() with disconnected gateway returns warning")
    void verifyPermissionsWhenDisconnected() {
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER);
        Optional<String> error = gateway.verifyPermissions();

        assertTrue(error.isPresent());
        assertTrue(error.get().contains("Discord is not connected"));
    }

    @Test
    @DisplayName("sanitizeMessage hides token if it appears in message")
    void sanitizeMessageHidesToken() {
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER);
        String raw = "Error en login con token mi-token-super-secreto-999 en servidor";
        String sanitized = gateway.sanitizeMessage(raw);

        assertFalse(sanitized.contains("mi-token-super-secreto-999"),
                "Token must not appear in sanitized message");
        assertTrue(sanitized.contains("[TOKEN_OCULTO]"),
                "Token must be replaced by mask");
    }

    @Test
    @DisplayName("mayorRoleId() throws exception if gateway is not connected")
    void mayorRoleIdThrowsWhenDisconnected() {
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER);
        assertThrows(IllegalStateException.class, gateway::mayorRoleId,
                "Must not return empty silently if role could not be resolved due to disconnection");
    }

    @Test
    @DisplayName("mayorRoleId() uses persisted ID even if role was renamed in Discord")
    void mayorRoleIdUsesPersistedIdEvenIfRenamedInDiscord() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);

        String savedId = "role-mayor-saved";
        when(settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID)).thenReturn(Optional.of(savedId));

        Role role = mock(Role.class);
        when(role.getId()).thenReturn(savedId);
        when(role.getName()).thenReturn("Burgomaestre");
        when(guild.getRoleById(savedId)).thenReturn(role);

        Optional<String> result = gateway.mayorRoleId();

        assertTrue(result.isPresent());
        assertEquals(savedId, result.get());
        verify(guild, never()).getRolesByName(any(), anyBoolean());
    }

    @Test
    @DisplayName("mayorRoleId() returns empty if role with persisted ID no longer exists in Discord")
    void mayorRoleIdReturnsEmptyWhenPersistedRoleNoLongerExistsInGuild() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);

        String savedId = "role-deleted";
        when(settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID)).thenReturn(Optional.of(savedId));
        when(guild.getRoleById(savedId)).thenReturn(null);

        Optional<String> result = gateway.mayorRoleId();

        assertTrue(result.isEmpty(), "Must be a confirmed absence without searching by name");
        verify(guild, never()).getRolesByName(any(), anyBoolean());
    }

    @Test
    @DisplayName("mayorRoleId() queries by name if there was no prior ID without adopting or persisting")
    void mayorRoleIdResolvesByNameWithoutPersistingWhenNoPriorIdExists() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);

        when(settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID)).thenReturn(Optional.empty());

        Role role = mock(Role.class);
        when(role.getId()).thenReturn("new-mayor-id");
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(role));

        Optional<String> result = gateway.mayorRoleId();

        assertTrue(result.isPresent());
        assertEquals("new-mayor-id", result.get());
        verify(settings, never()).put(any(), any());
    }

    @Test
    @DisplayName("verifyPermissions() only reads and does not persist the mayor role identity")
    void verifyPermissionsDoesNotPersistMayorRoleId() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);

        when(settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID)).thenReturn(Optional.empty());
        when(spaces.findAll()).thenReturn(List.of());

        Role role = mock(Role.class);
        when(role.getId()).thenReturn("mayor-id-1");
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(role));
        net.dv8tion.jda.api.entities.SelfMember selfMember = mock(net.dv8tion.jda.api.entities.SelfMember.class);
        when(guild.getSelfMember()).thenReturn(selfMember);
        when(selfMember.hasPermission(any(net.dv8tion.jda.api.Permission.class))).thenReturn(true);
        when(guild.getBotRole()).thenReturn(mock(Role.class));
        when(guild.getPublicRole()).thenReturn(mock(Role.class));

        gateway.verifyPermissions();

        verify(settings, never()).put(any(), any());
    }

    @Test
    @DisplayName("mayorRoleId() returns empty if no persisted ID and no match by name in Discord")
    void mayorRoleIdReturnsEmptyWhenNoPriorIdAndNotFoundByName() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);

        when(settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID)).thenReturn(Optional.empty());
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of());

        Optional<String> result = gateway.mayorRoleId();

        assertTrue(result.isEmpty());
        verify(settings, never()).put(any(), any());
    }
}
