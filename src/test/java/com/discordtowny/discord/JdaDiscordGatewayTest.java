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
 * Pruebas unitarias de JdaDiscordGateway sin conexion de red.
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
    @DisplayName("isAvailable es falso antes de conectar")
    void isAvailableFalseBeforeConnect() {
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER);
        assertFalse(gateway.isAvailable(), "El gateway no debe estar disponible antes de conectar");
    }

    @Test
    @DisplayName("submit() con gateway desconectado devuelve fallo transitorio sin lanzar excepcion")
    void submitWhenDisconnectedDoesNotThrow() {
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER);
        var op = new GuildOperation.DeleteSpace(UUID.randomUUID(), "town");

        CompletableFuture<OperationOutcome> future = gateway.submit(op);

        assertNotNull(future);
        assertFalse(future.isCompletedExceptionally(), "El futuro nunca debe completarse excepcionalmente");
        OperationOutcome outcome = future.join();
        assertFalse(outcome.succeeded());
        assertEquals(OperationOutcome.Status.TRANSIENT_FAILURE, outcome.status());
        assertTrue(outcome.reason().orElse("").contains("Discord no esta disponible"));
    }

    @Test
    @DisplayName("verifyPermissions() con gateway desconectado devuelve aviso")
    void verifyPermissionsWhenDisconnected() {
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER);
        Optional<String> error = gateway.verifyPermissions();

        assertTrue(error.isPresent());
        assertTrue(error.get().contains("Discord no esta conectado"));
    }

    @Test
    @DisplayName("sanitizeMessage oculta el token si aparece en el mensaje")
    void sanitizeMessageHidesToken() {
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER);
        String raw = "Error en login con token mi-token-super-secreto-999 en servidor";
        String sanitized = gateway.sanitizeMessage(raw);

        assertFalse(sanitized.contains("mi-token-super-secreto-999"),
                "El token no debe aparecer en el mensaje saneado");
        assertTrue(sanitized.contains("[TOKEN_OCULTO]"),
                "El token debe ser reemplazado por la mascara");
    }

    @Test
    @DisplayName("mayorRoleId() lanza excepcion si el gateway no esta conectado")
    void mayorRoleIdThrowsWhenDisconnected() {
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER);
        assertThrows(IllegalStateException.class, gateway::mayorRoleId,
                "No debe devolver vacio silenciosamente si no pudo resolver el rol por desconexion");
    }

    @Test
    @DisplayName("mayorRoleId() usa el ID persistido aunque el rol haya sido renombrado en Discord")
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
    @DisplayName("mayorRoleId() devuelve vacio si el rol con ID persistido ya no existe en Discord")
    void mayorRoleIdReturnsEmptyWhenPersistedRoleNoLongerExistsInGuild() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);

        String savedId = "role-deleted";
        when(settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID)).thenReturn(Optional.of(savedId));
        when(guild.getRoleById(savedId)).thenReturn(null);

        Optional<String> result = gateway.mayorRoleId();

        assertTrue(result.isEmpty(), "Debe ser una ausencia comprobada sin buscar por nombre");
        verify(guild, never()).getRolesByName(any(), anyBoolean());
    }

    @Test
    @DisplayName("mayorRoleId() busca por nombre la primera vez y lo persiste si no habia ID previo")
    void mayorRoleIdResolvesByNameAndPersistsWhenNoPriorIdExists() {
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
        verify(settings).put(SettingsRepository.KEY_MAYOR_ROLE_ID, "new-mayor-id");
    }

    @Test
    @DisplayName("mayorRoleId() devuelve vacio si no hay ID persistido ni coincide por nombre en Discord")
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
