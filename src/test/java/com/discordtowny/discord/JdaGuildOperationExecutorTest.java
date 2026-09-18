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
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
}
