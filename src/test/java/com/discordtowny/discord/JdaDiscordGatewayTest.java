package com.discordtowny.discord;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.storage.SettingsRepository;
import com.discordtowny.storage.SpaceRepository;
import com.discordtowny.config.Messages;
import com.discordtowny.link.LinkService;
import com.discordtowny.towny.TownyFacade;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private PluginConfig.Commands commandsConfig;

    @BeforeEach
    void setUp() {
        config = mock(PluginConfig.class);
        spaces = mock(SpaceRepository.class);
        settings = mock(SettingsRepository.class);
        discordConfig = mock(PluginConfig.Discord.class);
        rolesConfig = mock(PluginConfig.Roles.class);
        commandsConfig = mock(PluginConfig.Commands.class);

        when(config.discord()).thenReturn(discordConfig);
        when(config.roles()).thenReturn(rolesConfig);
        when(config.commands()).thenReturn(commandsConfig);
        when(commandsConfig.byName(anyString())).thenReturn(Optional.empty());
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

    @Test
    @DisplayName("roleHolders() throws exception if gateway is not connected")
    void roleHoldersThrowsWhenDisconnected() {
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER);
        assertThrows(IllegalStateException.class, () -> gateway.roleHolders("role-123"),
                "Must throw IllegalStateException when gateway is unavailable");
    }

    @Test
    @DisplayName("roleHolders() returns empty set if role does not exist in guild")
    void roleHoldersReturnsEmptyWhenRoleNotFound() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);

        when(guild.getRoleById("role-unknown")).thenReturn(null);

        Set<String> holders = gateway.roleHolders("role-unknown");
        assertNotNull(holders);
        assertTrue(holders.isEmpty());
    }

    @Test
    @DisplayName("roleHolders() returns plain Discord IDs of members holding the role")
    void roleHoldersReturnsPlainDiscordIds() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);

        String roleId = "role-town-123";
        Role role = mock(Role.class);
        when(guild.getRoleById(roleId)).thenReturn(role);

        Member m1 = mock(Member.class);
        when(m1.getId()).thenReturn("discord-id-1");
        Member m2 = mock(Member.class);
        when(m2.getId()).thenReturn("discord-id-2");

        when(guild.getMembersWithRoles(role)).thenReturn(List.of(m1, m2));

        Set<String> holders = gateway.roleHolders(roleId);

        assertEquals(Set.of("discord-id-1", "discord-id-2"), holders);
    }

    @Test
    @DisplayName("roleHolders() returns empty set when no members hold the role")
    void roleHoldersReturnsEmptyWhenNoMembersHoldRole() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);

        String roleId = "role-empty";
        Role role = mock(Role.class);
        when(guild.getRoleById(roleId)).thenReturn(role);
        when(guild.getMembersWithRoles(role)).thenReturn(List.of());

        Set<String> holders = gateway.roleHolders(roleId);
        assertNotNull(holders);
        assertTrue(holders.isEmpty());
    }

    @Test
    @DisplayName("existingResourceIds() fails loudly by throwing IllegalStateException when gateway is unavailable")
    void existingResourceIdsThrowsWhenDisconnected() {
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER);
        assertThrows(IllegalStateException.class, () -> gateway.existingResourceIds(List.of("some-id")),
                "Must fail loudly when Discord is disconnected rather than reporting resources deleted");
    }

    @Test
    @DisplayName("existingResourceIds() returns empty set when input is empty or null")
    void existingResourceIdsReturnsEmptyWhenInputEmptyOrNull() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);

        assertTrue(gateway.existingResourceIds(List.of()).isEmpty());
        assertTrue(gateway.existingResourceIds(null).isEmpty());
    }

    @Test
    @DisplayName("existingResourceIds() returns empty set when none of the IDs exist in guild")
    void existingResourceIdsReturnsEmptyWhenNoneExist() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);

        when(guild.getRoleById("missing-role")).thenReturn(null);
        when(guild.getTextChannelById("missing-text")).thenReturn(null);
        when(guild.getVoiceChannelById("missing-voice")).thenReturn(null);

        Set<String> result = gateway.existingResourceIds(List.of("missing-role", "missing-text", "missing-voice"));
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("existingResourceIds() returns plain Discord IDs for existing roles and channels")
    void existingResourceIdsReturnsMatchingPlainIdsForRolesAndChannels() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);

        Role role = mock(Role.class);
        when(guild.getRoleById("role-1")).thenReturn(role);

        net.dv8tion.jda.api.entities.channel.concrete.TextChannel textCh =
                mock(net.dv8tion.jda.api.entities.channel.concrete.TextChannel.class);
        when(guild.getTextChannelById("text-1")).thenReturn(textCh);

        net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel voiceCh =
                mock(net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel.class);
        when(guild.getVoiceChannelById("vc-1")).thenReturn(voiceCh);

        Set<String> result = gateway.existingResourceIds(List.of("role-1", "text-1", "vc-1", "missing-id"));
        assertEquals(Set.of("role-1", "text-1", "vc-1"), result);
    }

    @Test
    @DisplayName("registerSlashCommands attaches listeners to JDA and updates commands in Guild")
    void registerSlashCommandsAttachesListenersAndUpdatesGuild() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        CommandListUpdateAction action = mock(CommandListUpdateAction.class);
        when(guild.updateCommands()).thenReturn(action);
        when(action.addCommands(anyCollection())).thenReturn(action);

        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);
        TownyFacade facade = mock(TownyFacade.class);
        LinkService linkService = mock(LinkService.class);
        Messages messages = mock(Messages.class);

        gateway.registerSlashCommands(facade, linkService, messages, Runnable::run);

        verify(jda, times(1)).addEventListener(any(LinkSlashCommands.class), any(TownySlashCommands.class));
        verify(guild, times(1)).updateCommands();
        verify(action, times(1)).addCommands(anyCollection());
        assertTrue(gateway.linkSlashCommands().isPresent());
        assertTrue(gateway.townySlashCommands().isPresent());
    }

    @Test
    @DisplayName("shutdown removes registered slash command listeners from JDA")
    void shutdownRemovesSlashCommandListenersFromJda() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        CommandListUpdateAction action = mock(CommandListUpdateAction.class);
        when(guild.updateCommands()).thenReturn(action);
        when(action.addCommands(anyCollection())).thenReturn(action);

        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER, jda, guild);
        gateway.registerSlashCommands(mock(TownyFacade.class), mock(LinkService.class), mock(Messages.class), Runnable::run);

        assertTrue(gateway.linkSlashCommands().isPresent());
        assertTrue(gateway.townySlashCommands().isPresent());

        gateway.shutdown();

        verify(jda, times(2)).removeEventListener(any());
        assertTrue(gateway.linkSlashCommands().isEmpty());
        assertTrue(gateway.townySlashCommands().isEmpty());
    }

    @Test
    @DisplayName("registerSlashCommands called before gateway is ready queues registration and applies on init")
    void registerSlashCommandsBeforeJdaReadyQueuesRegistration() {
        var gateway = new JdaDiscordGateway(config, spaces, settings, LOGGER);
        TownyFacade facade = mock(TownyFacade.class);
        LinkService linkService = mock(LinkService.class);
        Messages messages = mock(Messages.class);

        gateway.registerSlashCommands(facade, linkService, messages, Runnable::run);

        // Before init, slash command listeners are not instantiated yet
        assertTrue(gateway.linkSlashCommands().isEmpty());
        assertTrue(gateway.townySlashCommands().isEmpty());

        // Now gateway finishes connecting / init
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        CommandListUpdateAction action = mock(CommandListUpdateAction.class);
        when(guild.updateCommands()).thenReturn(action);
        when(action.addCommands(anyCollection())).thenReturn(action);

        gateway.initJdaForTest(jda, guild);

        verify(jda, times(1)).addEventListener(any(LinkSlashCommands.class), any(TownySlashCommands.class));
        verify(guild, times(1)).updateCommands();
        verify(action, times(1)).addCommands(anyCollection());
        assertTrue(gateway.linkSlashCommands().isPresent());
        assertTrue(gateway.townySlashCommands().isPresent());
    }
}

