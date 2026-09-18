package com.discordtowny.discord;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.model.SpaceRequest;
import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSpace;
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
 * Pruebas unitarias de JdaGuildOperationExecutor.
 *
 * <p>Demuestra:
 * <ul>
 *   <li>que ApplyMemberRoles solo toca roles gestionados por el plugin,</li>
 *   <li>que un reintento no duplica categorias ni roles ya creados,</li>
 *   <li>que el token se oculta en los mensajes de error.</li>
 * </ul>
 */
class JdaGuildOperationExecutorTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    private Guild guild;
    private PluginConfig config;
    private SpaceRepository spaces;
    private PluginConfig.Roles rolesConfig;
    private PluginConfig.Structure structureConfig;
    private PluginConfig.Discord discordConfig;

    @BeforeEach
    void setUp() {
        guild = mock(Guild.class);
        config = mock(PluginConfig.class);
        spaces = mock(SpaceRepository.class);

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
    @DisplayName("ApplyMemberRoles solo toca los roles gestionados por el plugin")
    @SuppressWarnings("unchecked")
    void applyMemberRolesOnlyTouchesManagedRoles() {
        String memberId = "111222333";
        Member member = mock(Member.class);
        when(guild.getMemberById(memberId)).thenReturn(member);
        when(member.getRoles()).thenReturn(Collections.emptyList());

        // Rol gestionado 1: rol de una town registrada
        String townRoleId = "role-town-1";
        Role townRole = mock(Role.class);
        when(townRole.getId()).thenReturn(townRoleId);
        when(townRole.getName()).thenReturn("TestTown");
        when(guild.getRoleById(townRoleId)).thenReturn(townRole);

        // Rol gestionado 2: rol global de alcalde
        String mayorRoleId = "role-mayor-global";
        Role mayorRole = mock(Role.class);
        when(mayorRole.getId()).thenReturn(mayorRoleId);
        when(mayorRole.getName()).thenReturn("Alcalde");
        when(guild.getRoleById(mayorRoleId)).thenReturn(mayorRole);

        // Rol NO gestionado: rol de admin del servidor
        String adminRoleId = "role-admin-server";
        Role adminRole = mock(Role.class);
        when(adminRole.getId()).thenReturn(adminRoleId);
        when(adminRole.getName()).thenReturn("Administrador");
        when(guild.getRoleById(adminRoleId)).thenReturn(adminRole);

        // Registrar el townRole en SpaceRepository
        TownSpace registeredSpace = new TownSpace(
                UUID.randomUUID(), "TestTown",
                Optional.of("cat-1"), Optional.of("text-1"), Optional.of("voice-1"),
                Optional.of(townRoleId), SpaceState.ACTIVE,
                Instant.now(), Optional.empty(), Optional.empty());
        when(spaces.findAll()).thenReturn(List.of(registeredSpace));

        // Mock de las acciones de JDA
        AuditableRestAction<Void> addAction = mock(AuditableRestAction.class);
        when(guild.addRoleToMember(any(), any())).thenReturn(addAction);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, LOGGER);

        // Operacion: intentar asignar el rol de town, el de alcalde y el de admin
        var op = new GuildOperation.ApplyMemberRoles(
                memberId,
                List.of(townRoleId, mayorRoleId, adminRoleId),
                Collections.emptyList());

        OperationOutcome outcome = executor.execute(op);

        assertTrue(outcome.succeeded());
        // Se deben conceder los roles gestionados
        verify(guild).addRoleToMember(member, townRole);
        verify(guild).addRoleToMember(member, mayorRole);
        // Jamas debe tocarse el rol no gestionado
        verify(guild, never()).addRoleToMember(member, adminRole);
    }

    @Test
    @DisplayName("ApplyMemberRoles no retira roles ajenos al plugin")
    @SuppressWarnings("unchecked")
    void applyMemberRolesDoesNotRevokeUnmanagedRoles() {
        String memberId = "111222333";
        Member member = mock(Member.class);
        when(guild.getMemberById(memberId)).thenReturn(member);

        // Roles que el miembro tiene actualmente en Discord
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

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, LOGGER);

        // Solicitar revocar el rol de town y tambien un rol ajeno (VIP)
        var op = new GuildOperation.ApplyMemberRoles(
                memberId,
                Collections.emptyList(),
                List.of(townRoleId, vipRoleId));

        OperationOutcome outcome = executor.execute(op);

        assertTrue(outcome.succeeded());
        // Retira el rol gestionado
        verify(guild).removeRoleFromMember(member, townRole);
        // NO retira el rol no gestionado (VIP)
        verify(guild, never()).removeRoleFromMember(member, vipRole);
    }

    @Test
    @DisplayName("Creacion de espacio no duplica rol ni categoria si ya existen")
    void createSpaceDoesNotDuplicateExistingRoleOrCategory() {
        UUID townUuid = UUID.randomUUID();
        String townName = "Capital";
        SpaceRequest req = new SpaceRequest(townUuid, townName, UUID.randomUUID(), List.of(), "mayor-id");

        // Simular que un intento anterior creo categoria y rol antes de fallar
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

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.CreateSpace(req));

        assertTrue(outcome.succeeded());
        // No debe crear nueva categoria ni nuevo rol
        verify(guild, never()).createCategory(any());
        verify(guild, never()).createRole();
    }

    @Test
    @DisplayName("El token nunca aparece en mensajes de error del ejecutor")
    void tokenNeverAppearsInErrorMessages() {
        when(structureConfig.categoryName()).thenReturn("Comunidades");
        when(spaces.findByTownUuid(any())).thenThrow(
                new RuntimeException("Conexion fallida con token token-secreto-12345 en el host"));

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, LOGGER);
        var op = new GuildOperation.CreateSpace(new SpaceRequest(
                UUID.randomUUID(), "town", UUID.randomUUID(), List.of(), "mayor"));

        OperationOutcome outcome = executor.execute(op);

        assertFalse(outcome.succeeded());
        String reason = outcome.reason().orElse("");
        assertFalse(reason.contains("token-secreto-12345"),
                "El token no debe aparecer en el mensaje de error");
        assertTrue(reason.contains("[TOKEN_OCULTO]"),
                "El token debe ser reemplazado por la mascara de proteccion");
    }

    @Test
    @DisplayName("Los permisos de canal deniegan VIEW_CHANNEL a @everyone y lo conceden al rol de la town")
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

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.CreateSpace(req));

        assertTrue(outcome.succeeded());
        // Verificar que se deniega VIEW_CHANNEL a @everyone
        verify(channelAction).addPermissionOverride(
                eq(publicRole),
                isNull(),
                eq(java.util.EnumSet.of(net.dv8tion.jda.api.Permission.VIEW_CHANNEL)));

        // Verificar que se concede VIEW_CHANNEL y MESSAGE_SEND al rol de la town
        verify(channelAction).addPermissionOverride(
                eq(townRole),
                eq(java.util.EnumSet.of(net.dv8tion.jda.api.Permission.VIEW_CHANNEL, net.dv8tion.jda.api.Permission.MESSAGE_SEND)),
                isNull());
    }

    @Test
    @DisplayName("createSpace no duplica canales de texto ni de voz si ya existen")
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

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.CreateSpace(req));

        assertTrue(outcome.succeeded());
        verify(category, never()).createTextChannel(any());
        verify(category, never()).createVoiceChannel(any());
        verify(guild, never()).createRole();
        verify(guild, never()).createCategory(any());
    }

    @Test
    @DisplayName("El alcalde recibe su rol de alcalde y el rol de town al crear espacio")
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

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.CreateSpace(req));

        assertTrue(outcome.succeeded());
        verify(guild).addRoleToMember(mayorMember, mayorRole);
        verify(guild).addRoleToMember(mayorMember, townRole);
    }

    @Test
    @DisplayName("Recupera miembro de la API de Discord cuando no esta en cache")
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

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, LOGGER);
        var op = new GuildOperation.ApplyMemberRoles(memberId, List.of(townRoleId), List.of());

        OperationOutcome outcome = executor.execute(op);

        assertTrue(outcome.succeeded());
        verify(guild).retrieveMemberById(memberId);
        verify(guild).addRoleToMember(member, townRole);
    }

    @Test
    @DisplayName("PermissionException se clasifica como fallo permanente")
    void permissionExceptionClassifiedAsPermanentFailure() {
        when(structureConfig.categoryName()).thenReturn("Comunidades");
        InsufficientPermissionException permEx = mock(InsufficientPermissionException.class);
        when(permEx.getMessage()).thenReturn("Missing permission MANAGE_CHANNEL");
        doThrow(permEx).when(guild).getCategoriesByName("Comunidades", true);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, LOGGER);
        var op = new GuildOperation.CreateSpace(new SpaceRequest(
                UUID.randomUUID(), "town", UUID.randomUUID(), List.of(), "mayor"));

        OperationOutcome outcome = executor.execute(op);

        assertFalse(outcome.succeeded());
        assertEquals(OperationOutcome.Status.PERMANENT_FAILURE, outcome.status());
    }

    @Test
    @DisplayName("Crea categoria numerada cuando la actual tiene 50 canales")
    @SuppressWarnings("unchecked")
    void createsNumberedCategoryWhenLimitReached() {
        UUID townUuid = UUID.randomUUID();
        String townName = "NuevaTown";
        SpaceRequest req = new SpaceRequest(townUuid, townName, UUID.randomUUID(), List.of(), "mayor");

        // Categoria 1: Comunidades llena (50 canales)
        Category fullCat = mock(Category.class);
        when(fullCat.getId()).thenReturn("cat-full");
        List<net.dv8tion.jda.api.entities.channel.middleman.GuildChannel> fiftyChannels = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fiftyChannels.add(mock(net.dv8tion.jda.api.entities.channel.middleman.GuildChannel.class));
        }
        when(fullCat.getChannels()).thenReturn(fiftyChannels);
        when(guild.getCategoriesByName("Comunidades", true)).thenReturn(List.of(fullCat));

        // Comunidades 2 no existe aun
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

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.CreateSpace(req));

        assertTrue(outcome.succeeded());
        verify(guild).createCategory("Comunidades 2");
    }

    @Test
    @DisplayName("UNKNOWN_CHANNEL y UNKNOWN_ROLE en DeleteSpace son tratados como exito idempotente")
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

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.DeleteSpace(townUuid, "OldTown"));

        assertTrue(outcome.succeeded());
        verify(spaces).delete(townUuid);
    }

    @Test
    @DisplayName("restoreSpace persiste el rol creado de inmediato antes de mover canales")
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
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(mayorRole));

        TextChannel textCh = mock(TextChannel.class);
        when(guild.getTextChannelById("text-ch")).thenReturn(textCh);
        net.dv8tion.jda.api.managers.channel.concrete.TextChannelManager chManager = mock(net.dv8tion.jda.api.managers.channel.concrete.TextChannelManager.class);
        when(textCh.getManager()).thenReturn(chManager);
        when(chManager.setParent(category)).thenReturn(chManager);

        when(structureConfig.categoryName()).thenReturn("Comunidades");

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.RestoreSpace(req));

        assertTrue(outcome.succeeded());
        // Verificar que spaces.save se llamo con un TownSpace que ya contenia new-role-id
        verify(spaces, atLeastOnce()).save(argThat(s -> s.roleId().isPresent() && s.roleId().get().equals("new-role-id")));
    }

    @Test
    @DisplayName("newEmptySpace inicializa el estado como INCONSISTENT y pasa a ACTIVE al completar")
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
        when(guild.getRolesByName("Alcalde", true)).thenReturn(List.of(mayorRole));

        when(structureConfig.categoryName()).thenReturn("Comunidades");
        when(structureConfig.createTextChannel()).thenReturn(false);
        when(structureConfig.createVoiceChannel()).thenReturn(false);

        var executor = new JdaGuildOperationExecutor(guild, config, spaces, LOGGER);
        OperationOutcome outcome = executor.execute(new GuildOperation.CreateSpace(req));

        assertTrue(outcome.succeeded());
        // El primer save guardo la categoria con estado INCONSISTENT
        verify(spaces, atLeastOnce()).save(argThat(s -> s.state() == SpaceState.INCONSISTENT));
        // El ultimo save guardo el espacio con estado ACTIVE
        verify(spaces, atLeastOnce()).save(argThat(s -> s.state() == SpaceState.ACTIVE));
    }
}
