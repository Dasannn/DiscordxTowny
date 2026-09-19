package com.discordtowny.discord;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.model.SpaceRequest;
import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSpace;
import com.discordtowny.storage.SettingsRepository;
import com.discordtowny.storage.SpaceRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JdaGuildOperationExecutor.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>that ApplyMemberRoles only touches roles managed by the plugin,</li>
 *   <li>that a retry does not duplicate already created categories or roles,</li>
 *   <li>that the token is masked in error messages.</li>
 * </ul>
 */
class JdaGuildOperationExecutorTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    private Guild guild;
    private PluginConfig config;
    private SpaceRepository spaces;
    private SettingsRepository settings;
    private PluginConfig.Roles rolesConfig;
    private PluginConfig.Structure structureConfig;
    private PluginConfig.Discord discordConfig;

    @BeforeEach
    void setUp() {
        guild = mock(Guild.class);
        config = mock(PluginConfig.class);
        spaces = mock(SpaceRepository.class);
        settings = mock(SettingsRepository.class);

        rolesConfig = mock(PluginConfig.Roles.class);
        structureConfig = mock(PluginConfig.Structure.class);
        discordConfig = mock(PluginConfig.Discord.class);

        when(config.roles()).thenReturn(rolesConfig);
        when(config.structure()).thenReturn(structureConfig);
        when(config.discord()).thenReturn(discordConfig);

        when(rolesConfig.mayorRoleName()).thenReturn("Alcalde");
        when(rolesConfig.townRoleName()).thenReturn("{town}");
        when(discordConfig.token()).thenReturn("token-secreto-12345");
    }

    @Test
    @DisplayName("ApplyMemberRoles only touches roles managed by the plugin")
    @SuppressWarnings("unchecked")
    void applyMemberRolesOnlyTouchesManagedRoles() {
        String memberId = "111222333";
        Member member = mock(Member.class);
        when(guild.getMemberById(memberId)).thenReturn(member);
        when(member.getRoles()).thenReturn(Collections.emptyList());

        // Managed role 1: role of a registered town
        String townRoleId = "role-town-1";
        Role townRole = mock(Role.class);
        when(townRole.getId()).thenReturn(townRoleId);
        when(townRole.getName()).thenReturn("TestTown");
        when(guild.getRoleById(townRoleId)).thenReturn(townRole);

        // Managed role 2: global mayor role
        String mayorRoleId = "role-mayor-global";
        Role mayorRole = mock(Role.class);
        when(mayorRole.getId()).thenReturn(mayorRoleId);
        when(mayorRole.getName()).thenReturn("Alcalde");
        when(guild.getRoleById(mayorRoleId)).thenReturn(mayorRole);

        // UNMANAGED role: server admin role
        String adminRoleId = "role-admin-server";
        Role adminRole = mock(Role.class);
        when(adminRole.getId()).thenReturn(adminRoleId);
        when(adminRole.getName()).thenReturn("Administrador");
        when(guild.getRoleById(adminRoleId)).thenReturn(adminRole);

        // Register townRole in SpaceRepository
        TownSpace registeredSpace = new TownSpace(
                UUID.randomUUID(), "TestTown",
                Optional.of("cat-1"), Optional.of("text-1"), Optional.of("voice-1"),
                Optional.of(townRoleId), SpaceState.ACTIVE,
                Instant.now(), Optional.empty(), Optional.empty());
        when(spaces.findAll()).thenReturn(List.of(registeredSpace));

        // Mock JDA actions
        AuditableRestAction<Void> addAction = mock(AuditableRestAction.class);
        when(guild.addRoleToMember(any(), any())).thenReturn(addAction);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);

        // Operation: attempt to assign town role, mayor role, and admin role
        var op = new GuildOperation.ApplyMemberRoles(
                memberId,
                List.of(townRoleId, mayorRoleId, adminRoleId),
                Collections.emptyList());

        OperationOutcome outcome = executor.execute(op);

        assertTrue(outcome.succeeded());
        // Managed roles must be granted
        verify(guild).addRoleToMember(member, townRole);
        verify(guild).addRoleToMember(member, mayorRole);
        // Unmanaged role must never be touched
        verify(guild, never()).addRoleToMember(member, adminRole);
    }

    @Test
    @DisplayName("ApplyMemberRoles does not revoke roles external to the plugin")
    @SuppressWarnings("unchecked")
    void applyMemberRolesDoesNotRevokeUnmanagedRoles() {
        String memberId = "111222333";
        Member member = mock(Member.class);
        when(guild.getMemberById(memberId)).thenReturn(member);

        // Roles the member currently has in Discord
        String townRoleId = "role-town-1";
        Role townRole = mock(Role.class);
        when(townRole.getId()).thenReturn(townRoleId);
        when(townRole.getName()).thenReturn("TestTown");
        when(guild.getRoleById(townRoleId)).thenReturn(townRole);

        String vipRoleId = "role-vip";
        Role vipRole = mock(Role.class);
        when(vipRole.getId()).thenReturn(vipRoleId);
        when(vipRole.getName()).thenReturn("VIP");
        when(guild.getRoleById(vipRoleId)).thenReturn(vipRole);

        when(member.getRoles()).thenReturn(List.of(townRole, vipRole));

        TownSpace registeredSpace = new TownSpace(
                UUID.randomUUID(), "TestTown",
                Optional.of("cat-1"), Optional.of("text-1"), Optional.of("voice-1"),
                Optional.of(townRoleId), SpaceState.ACTIVE,
                Instant.now(), Optional.empty(), Optional.empty());
        when(spaces.findAll()).thenReturn(List.of(registeredSpace));

        AuditableRestAction<Void> removeAction = mock(AuditableRestAction.class);
        when(guild.removeRoleFromMember(any(), any())).thenReturn(removeAction);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);

        // Request revoking town role and also an external role (VIP)
        var op = new GuildOperation.ApplyMemberRoles(
                memberId,
                Collections.emptyList(),
                List.of(townRoleId, vipRoleId));

        OperationOutcome outcome = executor.execute(op);

        assertTrue(outcome.succeeded());
        // Revokes the managed role
        verify(guild).removeRoleFromMember(member, townRole);
        // Does NOT revoke the unmanaged role (VIP)
        verify(guild, never()).removeRoleFromMember(member, vipRole);
    }

    @Test
    @DisplayName("ApplyMemberRoles recognizes the mayor role by persisted ID even if renamed")
    @SuppressWarnings("unchecked")
    void applyMemberRolesRecognizesMayorRoleByStableIdEvenIfRenamed() {
        String memberId = "111222333";
        Member member = mock(Member.class);
        when(guild.getMemberById(memberId)).thenReturn(member);

        String mayorRoleId = "role-mayor-renamed";
        Role mayorRole = mock(Role.class);
        when(mayorRole.getId()).thenReturn(mayorRoleId);
        // Renamed in Discord: different name than configured
        when(mayorRole.getName()).thenReturn("Burgomaestre");
        when(guild.getRoleById(mayorRoleId)).thenReturn(mayorRole);

        when(member.getRoles()).thenReturn(List.of(mayorRole));
        when(spaces.findAll()).thenReturn(List.of());

        // Stable identity previously persisted in SettingsRepository
        when(settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID)).thenReturn(Optional.of(mayorRoleId));

        AuditableRestAction<Void> removeAction = mock(AuditableRestAction.class);
        when(guild.removeRoleFromMember(any(), any())).thenReturn(removeAction);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);

        var op = new GuildOperation.ApplyMemberRoles(
                memberId,
                Collections.emptyList(),
                List.of(mayorRoleId));

        OperationOutcome outcome = executor.execute(op);

        assertTrue(outcome.succeeded());
        verify(guild).removeRoleFromMember(member, mayorRole);
    }

    @Test
    @DisplayName("ApplyMemberRoles does not revoke renamed mayor role without persisted ID")
    @SuppressWarnings("unchecked")
    void applyMemberRolesDoesNotRevokeRenamedMayorRoleWithoutPersistedId() {
        String memberId = "111222333";
        Member member = mock(Member.class);
        when(guild.getMemberById(memberId)).thenReturn(member);

        String mayorRoleId = "role-mayor-renamed";
        Role mayorRole = mock(Role.class);
        when(mayorRole.getId()).thenReturn(mayorRoleId);
        when(mayorRole.getName()).thenReturn("Burgomaestre");
        when(guild.getRoleById(mayorRoleId)).thenReturn(mayorRole);

        when(member.getRoles()).thenReturn(List.of(mayorRole));
        when(spaces.findAll()).thenReturn(List.of());

        // No previously persisted ID
        when(settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID)).thenReturn(Optional.empty());

        AuditableRestAction<Void> removeAction = mock(AuditableRestAction.class);
        when(guild.removeRoleFromMember(any(), any())).thenReturn(removeAction);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);

        var op = new GuildOperation.ApplyMemberRoles(
                memberId,
                Collections.emptyList(),
                List.of(mayorRoleId));

        OperationOutcome outcome = executor.execute(op);

        assertTrue(outcome.succeeded());
        // Since it is not recognized as managed, it is skipped and not revoked
        verify(guild, never()).removeRoleFromMember(member, mayorRole);
    }

    @Test
    @DisplayName("Space creation does not duplicate role or category if they already exist")
    void createSpaceDoesNotDuplicateExistingRoleOrCategory() {
        UUID townUuid = UUID.randomUUID();
        String townName = "Capital";
        SpaceRequest req = new SpaceRequest(townUuid, townName, UUID.randomUUID(), List.of(), "mayor-id");

        // Simulate that an earlier attempt created category and role before failing
        Category existingCat = mock(Category.class);
        when(existingCat.getId()).thenReturn("cat-saved");
        when(guild.getCategoryById("cat-saved")).thenReturn(existingCat);
        when(existingCat.getTextChannels()).thenReturn(Collections.emptyList());
        when(existingCat.getVoiceChannels()).thenReturn(Collections.emptyList());

        Role existingRole = mock(Role.class);
        when(existingRole.getId()).thenReturn("role-saved");
        when(guild.getRoleById("role-saved")).thenReturn(existingRole);

        Role existingMayorRole = mock(Role.class);
        when(existingMayorRole.getId()).thenReturn("role-mayor-saved");
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(existingMayorRole));

        TownSpace partialSpace = new TownSpace(
                townUuid, townName,
                Optional.of("cat-saved"), Optional.empty(), Optional.empty(),
                Optional.of("role-saved"), SpaceState.ACTIVE,
                Instant.now(), Optional.empty(), Optional.empty());
        when(spaces.findByTownUuid(townUuid)).thenReturn(Optional.of(partialSpace));

        when(structureConfig.categoryName()).thenReturn("Comunidades");
        when(structureConfig.createTextChannel()).thenReturn(false);
        when(structureConfig.createVoiceChannel()).thenReturn(false);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.CreateSpace(req));

        assertTrue(outcome.succeeded());
        // Must not create new category or new role
        verify(guild, never()).createCategory(any());
        verify(guild, never()).createRole();
    }

    @Test
    @DisplayName("The token never appears in executor error messages")
    void tokenNeverAppearsInErrorMessages() {
        when(structureConfig.categoryName()).thenReturn("Comunidades");
        when(spaces.findByTownUuid(any())).thenThrow(
                new RuntimeException("Connection failed with token token-secreto-12345 on host"));

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);
        var op = new GuildOperation.CreateSpace(new SpaceRequest(
                UUID.randomUUID(), "town", UUID.randomUUID(), List.of(), "mayor"));

        OperationOutcome outcome = executor.execute(op);

        assertFalse(outcome.succeeded());
        String reason = outcome.reason().orElse("");
        assertFalse(reason.contains("token-secreto-12345"),
                "The token must not appear in the error message");
        assertTrue(reason.contains("[TOKEN_OCULTO]"),
                "The token must be replaced by the protection mask");
    }

    @Test
    @DisplayName("Channel permissions deny VIEW_CHANNEL to @everyone and grant it to the town role")
    @SuppressWarnings("unchecked")
    void channelCreatedWithStrictPermissions() {
        UUID townUuid = UUID.randomUUID();
        String townName = "Segura";
        SpaceRequest req = new SpaceRequest(townUuid, townName, UUID.randomUUID(), List.of(), "mayor-id");

        Category category = mock(Category.class);
        when(category.getId()).thenReturn("cat-id");
        when(category.getTextChannels()).thenReturn(Collections.emptyList());
        when(category.getVoiceChannels()).thenReturn(Collections.emptyList());
        when(guild.getCategoriesByName("Comunidades", true)).thenReturn(List.of(category));

        Role townRole = mock(Role.class);
        when(townRole.getId()).thenReturn("role-id");
        when(guild.getRolesByName(townName, true)).thenReturn(List.of(townRole));

        Role mayorRole = mock(Role.class);
        when(mayorRole.getId()).thenReturn("role-mayor-id");
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(mayorRole));

        Role publicRole = mock(Role.class);
        when(guild.getPublicRole()).thenReturn(publicRole);

        net.dv8tion.jda.api.entities.SelfMember selfMember = mock(net.dv8tion.jda.api.entities.SelfMember.class);
        when(guild.getSelfMember()).thenReturn(selfMember);

        net.dv8tion.jda.api.requests.restaction.ChannelAction<net.dv8tion.jda.api.entities.channel.concrete.TextChannel> channelAction =
                mock(net.dv8tion.jda.api.requests.restaction.ChannelAction.class);
        when(category.createTextChannel(any())).thenReturn(channelAction);
        when(channelAction.addPermissionOverride(any(net.dv8tion.jda.api.entities.IPermissionHolder.class), any(), any()))
                .thenReturn(channelAction);

        net.dv8tion.jda.api.entities.channel.concrete.TextChannel createdChannel =
                mock(net.dv8tion.jda.api.entities.channel.concrete.TextChannel.class);
        when(createdChannel.getId()).thenReturn("text-ch-id");
        when(channelAction.complete()).thenReturn(createdChannel);

        when(structureConfig.categoryName()).thenReturn("Comunidades");
        when(structureConfig.textChannelName()).thenReturn("{town}");
        when(structureConfig.createTextChannel()).thenReturn(true);
        when(structureConfig.createVoiceChannel()).thenReturn(false);
        when(spaces.findByTownUuid(townUuid)).thenReturn(Optional.empty());

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.CreateSpace(req));

        assertTrue(outcome.succeeded());
        // Verify that VIEW_CHANNEL is denied to @everyone
        verify(channelAction).addPermissionOverride(
                eq(publicRole),
                isNull(),
                eq(java.util.EnumSet.of(net.dv8tion.jda.api.Permission.VIEW_CHANNEL)));

        // Verify that VIEW_CHANNEL and MESSAGE_SEND are granted to the town role
        verify(channelAction).addPermissionOverride(
                eq(townRole),
                eq(java.util.EnumSet.of(net.dv8tion.jda.api.Permission.VIEW_CHANNEL, net.dv8tion.jda.api.Permission.MESSAGE_SEND)),
                isNull());
    }

    @Test
    @DisplayName("createSpace does not duplicate text or voice channels if they already exist")
    void createSpaceDoesNotDuplicateExistingChannels() {
        UUID townUuid = UUID.randomUUID();
        String townName = "Metropolis";
        SpaceRequest req = new SpaceRequest(townUuid, townName, UUID.randomUUID(), List.of(), "mayor-id");

        Category category = mock(Category.class);
        when(category.getId()).thenReturn("cat-saved");
        when(guild.getCategoryById("cat-saved")).thenReturn(category);

        TextChannel existingText = mock(TextChannel.class);
        when(existingText.getId()).thenReturn("text-saved");
        when(existingText.getName()).thenReturn("Metropolis");
        when(category.getTextChannels()).thenReturn(List.of(existingText));
        when(guild.getTextChannelById("text-saved")).thenReturn(existingText);

        VoiceChannel existingVoice = mock(VoiceChannel.class);
        when(existingVoice.getId()).thenReturn("voice-saved");
        when(existingVoice.getName()).thenReturn("Metropolis");
        when(category.getVoiceChannels()).thenReturn(List.of(existingVoice));
        when(guild.getVoiceChannelById("voice-saved")).thenReturn(existingVoice);

        Role existingRole = mock(Role.class);
        when(existingRole.getId()).thenReturn("role-saved");
        when(guild.getRoleById("role-saved")).thenReturn(existingRole);

        Role existingMayorRole = mock(Role.class);
        when(existingMayorRole.getId()).thenReturn("role-mayor-saved");
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(existingMayorRole));

        TownSpace partialSpace = new TownSpace(
                townUuid, townName,
                Optional.of("cat-saved"), Optional.of("text-saved"), Optional.of("voice-saved"),
                Optional.of("role-saved"), SpaceState.INCONSISTENT,
                Instant.now(), Optional.empty(), Optional.empty());
        when(spaces.findByTownUuid(townUuid)).thenReturn(Optional.of(partialSpace));

        when(structureConfig.categoryName()).thenReturn("Comunidades");
        when(structureConfig.textChannelName()).thenReturn("{town}");
        when(structureConfig.voiceChannelName()).thenReturn("{town}");
        when(structureConfig.createTextChannel()).thenReturn(true);
        when(structureConfig.createVoiceChannel()).thenReturn(true);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.CreateSpace(req));

        assertTrue(outcome.succeeded());
        verify(category, never()).createTextChannel(any());
        verify(category, never()).createVoiceChannel(any());
        verify(guild, never()).createRole();
        verify(guild, never()).createCategory(any());
    }

    @Test
    @DisplayName("The mayor receives the mayor role and the town role upon creating space")
    @SuppressWarnings("unchecked")
    void mayorReceivesMayorRoleOnCreateSpace() {
        UUID townUuid = UUID.randomUUID();
        String townName = "Alcaldia";
        String mayorId = "mayor-discord-123";
        SpaceRequest req = new SpaceRequest(townUuid, townName, UUID.randomUUID(), List.of(), mayorId);

        Category category = mock(Category.class);
        when(category.getId()).thenReturn("cat-1");
        when(guild.getCategoriesByName("Comunidades", true)).thenReturn(List.of(category));

        Role townRole = mock(Role.class);
        when(townRole.getId()).thenReturn("role-town-id");
        when(guild.getRolesByName(townName, true)).thenReturn(List.of(townRole));

        Role mayorRole = mock(Role.class);
        when(mayorRole.getId()).thenReturn("role-mayor-id");
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(mayorRole));

        Member mayorMember = mock(Member.class);
        when(mayorMember.getRoles()).thenReturn(Collections.emptyList());
        when(guild.getMemberById(mayorId)).thenReturn(mayorMember);

        AuditableRestAction<Void> addAction = mock(AuditableRestAction.class);
        when(guild.addRoleToMember(any(), any())).thenReturn(addAction);

        when(structureConfig.categoryName()).thenReturn("Comunidades");
        when(structureConfig.createTextChannel()).thenReturn(false);
        when(structureConfig.createVoiceChannel()).thenReturn(false);
        when(spaces.findByTownUuid(townUuid)).thenReturn(Optional.empty());

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.CreateSpace(req));

        assertTrue(outcome.succeeded());
        verify(guild).addRoleToMember(mayorMember, mayorRole);
        verify(guild).addRoleToMember(mayorMember, townRole);
    }

    @Test
    @DisplayName("Retrieves member from Discord API when not in cache")
    @SuppressWarnings("unchecked")
    void retrievesMemberFromApiWhenNotInCache() {
        String memberId = "uncached-user-456";
        when(guild.getMemberById(memberId)).thenReturn(null);

        Member member = mock(Member.class);
        when(member.getRoles()).thenReturn(Collections.emptyList());

        net.dv8tion.jda.api.requests.restaction.CacheRestAction<Member> retrieveAction = mock(net.dv8tion.jda.api.requests.restaction.CacheRestAction.class);
        when(guild.retrieveMemberById(memberId)).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(member);

        String townRoleId = "role-town-uncached";
        Role townRole = mock(Role.class);
        when(townRole.getId()).thenReturn(townRoleId);
        when(townRole.getName()).thenReturn("TestTown");
        when(guild.getRoleById(townRoleId)).thenReturn(townRole);

        TownSpace space = new TownSpace(
                UUID.randomUUID(), "TestTown",
                Optional.of("cat-1"), Optional.of("text-1"), Optional.of("voice-1"),
                Optional.of(townRoleId), SpaceState.ACTIVE,
                Instant.now(), Optional.empty(), Optional.empty());
        when(spaces.findAll()).thenReturn(List.of(space));

        AuditableRestAction<Void> addAction = mock(AuditableRestAction.class);
        when(guild.addRoleToMember(any(), any())).thenReturn(addAction);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);
        var op = new GuildOperation.ApplyMemberRoles(memberId, List.of(townRoleId), List.of());

        OperationOutcome outcome = executor.execute(op);

        assertTrue(outcome.succeeded());
        verify(guild).retrieveMemberById(memberId);
        verify(guild).addRoleToMember(member, townRole);
    }

    @Test
    @DisplayName("PermissionException is classified as permanent failure")
    void permissionExceptionClassifiedAsPermanentFailure() {
        when(structureConfig.categoryName()).thenReturn("Comunidades");
        InsufficientPermissionException permEx = mock(InsufficientPermissionException.class);
        when(permEx.getMessage()).thenReturn("Missing permission MANAGE_CHANNEL");
        doThrow(permEx).when(guild).getCategoriesByName("Comunidades", true);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);
        var op = new GuildOperation.CreateSpace(new SpaceRequest(
                UUID.randomUUID(), "town", UUID.randomUUID(), List.of(), "mayor"));

        OperationOutcome outcome = executor.execute(op);

        assertFalse(outcome.succeeded());
        assertEquals(OperationOutcome.Status.PERMANENT_FAILURE, outcome.status());
    }

    @Test
    @DisplayName("Creates numbered category when the current one has 50 channels")
    @SuppressWarnings("unchecked")
    void createsNumberedCategoryWhenLimitReached() {
        UUID townUuid = UUID.randomUUID();
        String townName = "NuevaTown";
        SpaceRequest req = new SpaceRequest(townUuid, townName, UUID.randomUUID(), List.of(), "mayor");

        // Category 1: Comunidades full (50 channels)
        Category fullCat = mock(Category.class);
        when(fullCat.getId()).thenReturn("cat-full");
        List<net.dv8tion.jda.api.entities.channel.middleman.GuildChannel> fiftyChannels = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fiftyChannels.add(mock(net.dv8tion.jda.api.entities.channel.middleman.GuildChannel.class));
        }
        when(fullCat.getChannels()).thenReturn(fiftyChannels);
        when(guild.getCategoriesByName("Comunidades", true)).thenReturn(List.of(fullCat));

        // Comunidades 2 does not exist yet
        when(guild.getCategoriesByName("Comunidades 2", true)).thenReturn(Collections.emptyList());
        net.dv8tion.jda.api.requests.restaction.ChannelAction<Category> createCatAction = mock(net.dv8tion.jda.api.requests.restaction.ChannelAction.class);
        Category newCat = mock(Category.class);
        when(newCat.getId()).thenReturn("cat-num-2");
        when(newCat.getChannels()).thenReturn(Collections.emptyList());
        when(guild.createCategory("Comunidades 2")).thenReturn(createCatAction);
        when(createCatAction.complete()).thenReturn(newCat);

        Role townRole = mock(Role.class);
        when(townRole.getId()).thenReturn("role-1");
        when(guild.getRolesByName(townName, true)).thenReturn(List.of(townRole));

        Role mayorRole = mock(Role.class);
        when(mayorRole.getId()).thenReturn("role-mayor-id");
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(mayorRole));

        when(structureConfig.categoryName()).thenReturn("Comunidades");
        when(structureConfig.createTextChannel()).thenReturn(true);
        when(structureConfig.textChannelName()).thenReturn("{town}");
        when(structureConfig.createVoiceChannel()).thenReturn(false);
        Role publicRole = mock(Role.class);
        when(guild.getPublicRole()).thenReturn(publicRole);

        net.dv8tion.jda.api.requests.restaction.ChannelAction<TextChannel> textChAction = mock(net.dv8tion.jda.api.requests.restaction.ChannelAction.class);
        when(newCat.createTextChannel(any())).thenReturn(textChAction);
        when(textChAction.addPermissionOverride(any(), any(), any())).thenReturn(textChAction);
        TextChannel createdText = mock(TextChannel.class);
        when(createdText.getId()).thenReturn("text-num-2");
        when(textChAction.complete()).thenReturn(createdText);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.CreateSpace(req));

        assertTrue(outcome.succeeded());
        verify(guild).createCategory("Comunidades 2");
    }

    @Test
    @DisplayName("UNKNOWN_CHANNEL and UNKNOWN_ROLE in DeleteSpace are treated as idempotent success")
    @SuppressWarnings("unchecked")
    void unknownResourceTreatedAsSuccessOnDelete() {
        UUID townUuid = UUID.randomUUID();
        TownSpace space = new TownSpace(
                townUuid, "OldTown",
                Optional.of("cat-1"), Optional.of("text-deleted"), Optional.of("voice-deleted"),
                Optional.of("role-deleted"), SpaceState.ACTIVE,
                Instant.now(), Optional.empty(), Optional.empty());
        when(spaces.findByTownUuid(townUuid)).thenReturn(Optional.of(space));

        ErrorResponseException unknownRoleEx = mock(ErrorResponseException.class);
        when(unknownRoleEx.getErrorResponse()).thenReturn(ErrorResponse.UNKNOWN_ROLE);

        Role role = mock(Role.class);
        when(guild.getRoleById("role-deleted")).thenReturn(role);
        AuditableRestAction<Void> roleDelete = mock(AuditableRestAction.class);
        when(role.delete()).thenReturn(roleDelete);
        when(roleDelete.complete()).thenThrow(unknownRoleEx);

        ErrorResponseException unknownChannelEx = mock(ErrorResponseException.class);
        when(unknownChannelEx.getErrorResponse()).thenReturn(ErrorResponse.UNKNOWN_CHANNEL);

        TextChannel textCh = mock(TextChannel.class);
        when(guild.getTextChannelById("text-deleted")).thenReturn(textCh);
        AuditableRestAction<Void> textDelete = mock(AuditableRestAction.class);
        when(textCh.delete()).thenReturn(textDelete);
        when(textDelete.complete()).thenThrow(unknownChannelEx);

        VoiceChannel voiceCh = mock(VoiceChannel.class);
        when(guild.getVoiceChannelById("voice-deleted")).thenReturn(voiceCh);
        AuditableRestAction<Void> voiceDelete = mock(AuditableRestAction.class);
        when(voiceCh.delete()).thenReturn(voiceDelete);
        when(voiceDelete.complete()).thenThrow(unknownChannelEx);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.DeleteSpace(townUuid, "OldTown"));

        assertTrue(outcome.succeeded());
        verify(spaces).delete(townUuid);
    }

    @Test
    @DisplayName("restoreSpace persists the created role immediately before moving channels")
    @SuppressWarnings("unchecked")
    void restoreSpacePersistsRoleImmediately() {
        UUID townUuid = UUID.randomUUID();
        String townName = "Revivida";
        SpaceRequest req = new SpaceRequest(townUuid, townName, UUID.randomUUID(), List.of(), "mayor");

        TownSpace archivedSpace = new TownSpace(
                townUuid, townName,
                Optional.of("cat-active"), Optional.of("text-ch"), Optional.empty(),
                Optional.empty(), SpaceState.ARCHIVED,
                Instant.now(), Optional.of(Instant.now()), Optional.empty());
        when(spaces.findByTownUuid(townUuid)).thenReturn(Optional.of(archivedSpace));

        Category category = mock(Category.class);
        when(category.getId()).thenReturn("cat-active");
        when(guild.getCategoryById("cat-active")).thenReturn(category);
        when(category.getChannels()).thenReturn(Collections.emptyList());

        Role newRole = mock(Role.class);
        when(newRole.getId()).thenReturn("new-role-id");
        when(guild.getRolesByName(townName, true)).thenReturn(List.of(newRole));

        Role mayorRole = mock(Role.class);
        when(mayorRole.getId()).thenReturn("role-mayor-id");
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(mayorRole));

        TextChannel textCh = mock(TextChannel.class);
        when(guild.getTextChannelById("text-ch")).thenReturn(textCh);
        net.dv8tion.jda.api.managers.channel.concrete.TextChannelManager chManager = mock(net.dv8tion.jda.api.managers.channel.concrete.TextChannelManager.class);
        when(textCh.getManager()).thenReturn(chManager);
        when(chManager.setParent(category)).thenReturn(chManager);

        when(structureConfig.categoryName()).thenReturn("Comunidades");

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.RestoreSpace(req));

        assertTrue(outcome.succeeded());
        // Verify that spaces.save was called with a TownSpace that already contained new-role-id
        verify(spaces, atLeastOnce()).save(argThat(s -> s.roleId().isPresent() && s.roleId().get().equals("new-role-id")));
    }

    @Test
    @DisplayName("newEmptySpace initializes state as INCONSISTENT and transitions to ACTIVE upon completion")
    void newEmptySpaceStartsInconsistentAndCompletesActive() {
        UUID townUuid = UUID.randomUUID();
        String townName = "EstadoTest";
        SpaceRequest req = new SpaceRequest(townUuid, townName, UUID.randomUUID(), List.of(), "mayor");

        when(spaces.findByTownUuid(townUuid)).thenReturn(Optional.empty());

        Category category = mock(Category.class);
        when(category.getId()).thenReturn("cat-1");
        when(guild.getCategoriesByName("Comunidades", true)).thenReturn(List.of(category));

        Role townRole = mock(Role.class);
        when(townRole.getId()).thenReturn("role-1");
        when(guild.getRolesByName(townName, true)).thenReturn(List.of(townRole));

        Role mayorRole = mock(Role.class);
        when(mayorRole.getId()).thenReturn("role-mayor-id");
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(mayorRole));

        when(structureConfig.categoryName()).thenReturn("Comunidades");
        when(structureConfig.createTextChannel()).thenReturn(false);
        when(structureConfig.createVoiceChannel()).thenReturn(false);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.CreateSpace(req));

        assertTrue(outcome.succeeded());
        // The first save stored the category with state INCONSISTENT
        verify(spaces, atLeastOnce()).save(argThat(s -> s.state() == SpaceState.INCONSISTENT));
        // The last save stored the space with state ACTIVE
        verify(spaces, atLeastOnce()).save(argThat(s -> s.state() == SpaceState.ACTIVE));
    }

    @Test
    @DisplayName("End-to-end startup: ensureMayorRole adopts and persists with empty table and subsequent resolution works after renaming")
    void endToEndStartupAdoptsAndPersistsRoleAndResolvesByIdAfterRenaming() {
        java.util.Map<String, String> settingsTable = new java.util.HashMap<>();
        SettingsRepository settingsDouble = new SettingsRepository() {
            @Override
            public Optional<String> get(String key) {
                return Optional.ofNullable(settingsTable.get(key));
            }

            @Override
            public void put(String key, String value) {
                settingsTable.put(key, value);
            }

            @Override
            public void delete(String key) {
                settingsTable.remove(key);
            }
        };

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settingsDouble, LOGGER);

        // 1. With the settings table empty
        assertTrue(settingsDouble.get(SettingsRepository.KEY_MAYOR_ROLE_ID).isEmpty());

        // 2. Exactly one role exists with configured name ("Alcalde")
        String mayorRoleId = "role-mayor-estable-123";
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(mayorRoleId);
        when(role.getName()).thenReturn("Alcalde");
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(role));
        when(guild.getRoleById(mayorRoleId)).thenReturn(role);

        // 3. ensureMayorRole adopts the role and leaves it persisted
        Role adopted = executor.ensureMayorRole();
        assertEquals(mayorRoleId, adopted.getId());
        assertEquals(Optional.of(mayorRoleId), settingsDouble.get(SettingsRepository.KEY_MAYOR_ROLE_ID));

        // 4. Role is renamed in Discord
        when(role.getName()).thenReturn("Burgomaestre");
        when(guild.getRolesByName("Alcalde", true)).thenReturn(Collections.emptyList());

        // 5. Subsequent call resolves it by that persisted ID even if renamed
        Role subsequentlyResolved = executor.ensureMayorRole();
        assertEquals(mayorRoleId, subsequentlyResolved.getId());
        verify(guild, never()).createRole();

        // The gateway also resolves it by that persisted ID without looking up by name
        net.dv8tion.jda.api.JDA jda = mock(net.dv8tion.jda.api.JDA.class);
        var gateway = new JdaDiscordGateway(config, spaces, settingsDouble, LOGGER, jda, guild);
        assertEquals(Optional.of(mayorRoleId), gateway.mayorRoleId());
    }

    @Test
    @DisplayName("ensureMayorRole with multiple homonymous roles and without prior ID fails visibly")
    void ensureMayorRoleFailsVisiblyWithAmbiguousHomonymsWithoutPersistedId() {
        when(settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID)).thenReturn(Optional.empty());

        Role role1 = mock(Role.class);
        Role role2 = mock(Role.class);
        when(role1.getId()).thenReturn("role-1");
        when(role2.getId()).thenReturn("role-2");
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(role1, role2));

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);

        IllegalStateException ex = assertThrows(IllegalStateException.class, executor::ensureMayorRole);
        assertTrue(ex.getMessage().contains("Multiple roles exist with the name 'Alcalde'"));
        verify(settings, never()).put(any(), any());
    }

    @Test
    @DisplayName("ensureMayorRole propagates persistence failure without granting role")
    void ensureMayorRolePropagatesPersistenceFailureWithoutConfirmingOperation() {
        when(settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID)).thenReturn(Optional.empty());

        Role mayorRole = mock(Role.class);
        when(mayorRole.getId()).thenReturn("role-mayor-id");
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(mayorRole));

        doThrow(new RuntimeException("Database connection failure"))
                .when(settings).put(eq(SettingsRepository.KEY_MAYOR_ROLE_ID), any());

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, settings, LOGGER);

        assertThrows(RuntimeException.class, executor::ensureMayorRole);

        // In createSpace, failure prevents granting the role to mayor and the operation is not confirmed
        UUID townUuid = UUID.randomUUID();
        SpaceRequest req = new SpaceRequest(townUuid, "MyTown", UUID.randomUUID(), List.of(), "mayor-discord-id");
        Category category = mock(Category.class);
        when(category.getId()).thenReturn("cat-1");
        when(guild.getCategoriesByName("Comunidades", true)).thenReturn(List.of(category));
        Role townRole = mock(Role.class);
        when(townRole.getId()).thenReturn("role-town-id");
        when(guild.getRolesByName("MyTown", true)).thenReturn(List.of(townRole));
        when(structureConfig.categoryName()).thenReturn("Comunidades");
        when(structureConfig.createTextChannel()).thenReturn(false);
        when(structureConfig.createVoiceChannel()).thenReturn(false);

        OperationOutcome outcome = executor.execute(new GuildOperation.CreateSpace(req));
        assertFalse(outcome.succeeded());
        verify(guild, never()).addRoleToMember(any(), eq(mayorRole));
    }
}
