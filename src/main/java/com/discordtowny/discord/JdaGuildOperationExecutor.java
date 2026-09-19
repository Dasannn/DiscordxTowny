package com.discordtowny.discord;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.model.SpaceRequest;
import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSpace;
import com.discordtowny.storage.SettingsRepository;
import com.discordtowny.storage.SpaceRepository;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Ejecuta cada caso de {@link GuildOperation} contra un guild de Discord.
 *
 * <p>Cada paso es <b>idempotente</b>: antes de crear algo se comprueba si ya
 * existe por su identificador guardado. Reintentar una operacion a medias no
 * puede duplicar canales ni roles.
 *
 * <p>Los permisos de los canales siguen spec 3.3 y NO son configurables:
 * {@code @everyone} sin ver el canal, solo el rol de la town con acceso.
 * Un fallo a medias jamas puede dejar un canal visible para quien no debe,
 * porque los permisos se aplican al crear el canal, antes de que sea visible.
 */
final class JdaGuildOperationExecutor implements GuildOperationExecutor {

    private final Guild guild;
    private final PluginConfig config;
    private final SpaceRepository spaces;
    private final SettingsRepository settings;
    private final Logger logger;

    JdaGuildOperationExecutor(Guild guild, PluginConfig config,
                              SpaceRepository spaces, SettingsRepository settings, Logger logger) {
        this.guild = guild;
        this.config = config;
        this.spaces = spaces;
        this.settings = java.util.Objects.requireNonNull(settings, "settings no puede ser nulo");
        this.logger = logger;
    }

    @Override
    public OperationOutcome execute(GuildOperation operation) {
        try {
            return switch (operation) {
                case GuildOperation.CreateSpace op -> createSpace(op.request());
                case GuildOperation.RenameSpace op -> renameSpace(op);
                case GuildOperation.ArchiveSpace op -> archiveSpace(op);
                case GuildOperation.RestoreSpace op -> restoreSpace(op.request());
                case GuildOperation.DeleteSpace op -> deleteSpace(op);
                case GuildOperation.ApplyMemberRoles op -> applyMemberRoles(op);
            };
        } catch (PermissionException e) {
            String safeMsg = sanitizeMessage(e.getMessage());
            logger.warning("[Ejecutor] Permisos insuficientes o violacion de jerarquia en '"
                    + operation.describe() + "': " + safeMsg);
            OperationOutcome outcome = OperationOutcome.permanentFailure(
                    "Permisos insuficientes o jerarquia invalida en '" + operation.describe() + "': " + safeMsg);
            onOperationFailed(operation, outcome);
            return outcome;
        } catch (ErrorResponseException e) {
            OperationOutcome outcome = classifyError(e, operation);
            if (!outcome.succeeded() && outcome.status() == OperationOutcome.Status.PERMANENT_FAILURE) {
                onOperationFailed(operation, outcome);
            }
            return outcome;
        } catch (Exception e) {
            String safeMsg = sanitizeMessage(e.getMessage());
            logger.warning("[Ejecutor] Error inesperado en '" + operation.describe()
                    + "': " + safeMsg);
            return OperationOutcome.transientFailure(safeMsg);
        }
    }

    // -- CreateSpace --

    private OperationOutcome createSpace(SpaceRequest req) {
        Optional<TownSpace> existing = spaces.findByTownUuid(req.townUuid());
        TownSpace space = existing.orElse(newEmptySpace(req));

        int channelsNeeded = (config.structure().createTextChannel() ? 1 : 0)
                + (config.structure().createVoiceChannel() ? 1 : 0);

        // 1. Categoria contenedora (compartida con limite de 50 canales)
        Category category = ensureCategory(space, config.structure().categoryName(), channelsNeeded);
        space = withCategory(space, category.getId());
        spaces.save(space);

        // 2. Rol de la town
        Role role = ensureRole(space, applyTemplate(config.roles().townRoleName(), req.townName()));
        space = withRole(space, role.getId());
        spaces.save(space);

        // 3. Canal de texto (si la config lo pide)
        if (config.structure().createTextChannel()) {
            TextChannel textCh = ensureTextChannel(space, category, role,
                    applyTemplate(config.structure().textChannelName(), req.townName()));
            if (textCh != null) {
                space = withTextChannel(space, textCh.getId());
                spaces.save(space);
            }
        }

        // 4. Canal de voz (si la config lo pide)
        if (config.structure().createVoiceChannel()) {
            VoiceChannel voiceCh = ensureVoiceChannel(space, category, role,
                    applyTemplate(config.structure().voiceChannelName(), req.townName()));
            if (voiceCh != null) {
                space = withVoiceChannel(space, voiceCh.getId());
                spaces.save(space);
            }
        }

        // 5. Asignar roles a residentes vinculados y al alcalde (spec 4.1)
        assignRolesToMembers(role, req.linkedResidentDiscordIds());
        if (req.mayorDiscordId() != null && !req.mayorDiscordId().isBlank()) {
            Role mayorRole = ensureMayorRole();
            assignRoleToMember(mayorRole, req.mayorDiscordId());
            assignRoleToMember(role, req.mayorDiscordId());
        }

        // 6. Espacio completado con exito: marcar como ACTIVE
        space = withState(space, SpaceState.ACTIVE);
        spaces.save(space);

        logger.info("[Ejecutor] Espacio creado para town '" + req.townName() + "'");
        return OperationOutcome.success();
    }

    // -- RenameSpace --

    private OperationOutcome renameSpace(GuildOperation.RenameSpace op) {
        Optional<TownSpace> opt = spaces.findByTownUuid(op.townUuid());
        if (opt.isEmpty()) {
            return OperationOutcome.permanentFailure(
                    "No hay espacio registrado para la town " + op.townUuid());
        }
        TownSpace space = opt.get();
        String newName = op.newName();

        // Renombrar rol
        space.roleId().ifPresent(roleId -> {
            Role role = guild.getRoleById(roleId);
            if (role != null) {
                role.getManager()
                        .setName(applyTemplate(config.roles().townRoleName(), newName))
                        .complete();
            }
        });

        // Renombrar canales
        space.textChannelId().ifPresent(chId -> {
            TextChannel ch = guild.getTextChannelById(chId);
            if (ch != null) {
                ch.getManager()
                        .setName(applyTemplate(config.structure().textChannelName(), newName))
                        .complete();
            }
        });

        space.voiceChannelId().ifPresent(chId -> {
            VoiceChannel ch = guild.getVoiceChannelById(chId);
            if (ch != null) {
                ch.getManager()
                        .setName(applyTemplate(config.structure().voiceChannelName(), newName))
                        .complete();
            }
        });

        // Actualizar nombre en BD
        TownSpace updated = new TownSpace(
                space.townUuid(), newName, space.categoryId(),
                space.textChannelId(), space.voiceChannelId(), space.roleId(),
                space.state(), space.createdAt(), space.archivedAt(), space.lastActivityAt());
        spaces.save(updated);

        logger.info("[Ejecutor] Espacio renombrado de '" + op.oldName() + "' a '" + newName + "'");
        return OperationOutcome.success();
    }

    // -- ArchiveSpace --

    private OperationOutcome archiveSpace(GuildOperation.ArchiveSpace op) {
        Optional<TownSpace> opt = spaces.findByTownUuid(op.townUuid());
        if (opt.isEmpty()) {
            return OperationOutcome.permanentFailure(
                    "No hay espacio registrado para la town " + op.townUuid());
        }
        TownSpace space = opt.get();

        // 1. Eliminar el rol (quita acceso a todos)
        space.roleId().ifPresent(roleId -> {
            Role role = guild.getRoleById(roleId);
            if (role != null) {
                role.delete().complete();
            }
        });

        // 2. Mover canales a la categoria de archivo y ponerlos en solo lectura para administradores
        int channelsNeeded = (space.textChannelId().isPresent() ? 1 : 0)
                + (space.voiceChannelId().isPresent() ? 1 : 0);
        Category archive = ensureCategoryWithCapacity(config.structure().archiveCategoryName(), channelsNeeded);

        space.textChannelId().ifPresent(chId -> {
            TextChannel ch = guild.getTextChannelById(chId);
            if (ch != null) {
                ch.getManager().setParent(archive).complete();
                // Visible solo para administradores en solo lectura: @everyone no ve el canal
                ch.upsertPermissionOverride(guild.getPublicRole())
                        .deny(Permission.MESSAGE_SEND, Permission.VIEW_CHANNEL)
                        .complete();
                ch.upsertPermissionOverride(guild.getSelfMember())
                        .grant(Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL)
                        .complete();
            }
        });

        space.voiceChannelId().ifPresent(chId -> {
            VoiceChannel ch = guild.getVoiceChannelById(chId);
            if (ch != null) {
                ch.getManager().setParent(archive).complete();
                // Visible solo para administradores en solo lectura: @everyone no ve el canal
                ch.upsertPermissionOverride(guild.getPublicRole())
                        .deny(Permission.VOICE_CONNECT, Permission.VIEW_CHANNEL)
                        .complete();
                ch.upsertPermissionOverride(guild.getSelfMember())
                        .grant(Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL)
                        .complete();
            }
        });

        // 3. Actualizar estado en BD
        TownSpace archived = new TownSpace(
                space.townUuid(), space.townName(), space.categoryId(),
                space.textChannelId(), space.voiceChannelId(), Optional.empty(),
                SpaceState.ARCHIVED,
                space.createdAt(), Optional.of(Instant.now()), space.lastActivityAt());
        spaces.save(archived);

        logger.info("[Ejecutor] Espacio archivado para town '" + op.townName() + "'");
        return OperationOutcome.success();
    }

    // -- RestoreSpace --

    private OperationOutcome restoreSpace(SpaceRequest req) {
        Optional<TownSpace> opt = spaces.findByTownUuid(req.townUuid());
        if (opt.isEmpty()) {
            return OperationOutcome.permanentFailure(
                    "No hay espacio archivado para la town " + req.townUuid());
        }
        TownSpace space = opt.get();

        // Marcar como inconsistente durante la restauracion
        space = withState(space, SpaceState.INCONSISTENT);
        spaces.save(space);

        // 1. Categoria activa
        int channelsNeeded = (space.textChannelId().isPresent() ? 1 : 0)
                + (space.voiceChannelId().isPresent() ? 1 : 0);
        Category category = ensureCategory(space, config.structure().categoryName(), channelsNeeded);
        space = withCategory(space, category.getId());
        spaces.save(space);

        // 2. Crear nuevo rol (el archivado fue eliminado) y persistir de inmediato
        Role role = ensureRole(space, applyTemplate(config.roles().townRoleName(), req.townName()));
        space = withRole(space, role.getId());
        spaces.save(space);

        // 3. Mover canales de vuelta y aplicar permisos (con permisos del bot)
        space.textChannelId().ifPresent(chId -> {
            TextChannel ch = guild.getTextChannelById(chId);
            if (ch != null) {
                ch.getManager().setParent(category).complete();
                applyChannelPermissions(ch, role);
            }
        });

        space.voiceChannelId().ifPresent(chId -> {
            VoiceChannel ch = guild.getVoiceChannelById(chId);
            if (ch != null) {
                ch.getManager().setParent(category).complete();
                applyVoiceChannelPermissions(ch, role);
            }
        });

        // 4. Asignar roles a residentes vinculados y al alcalde (spec 4.1)
        assignRolesToMembers(role, req.linkedResidentDiscordIds());
        if (req.mayorDiscordId() != null && !req.mayorDiscordId().isBlank()) {
            Role mayorRole = ensureMayorRole();
            assignRoleToMember(mayorRole, req.mayorDiscordId());
            assignRoleToMember(role, req.mayorDiscordId());
        }

        // 5. Actualizar estado a ACTIVE
        TownSpace restored = new TownSpace(
                space.townUuid(), req.townName(), space.categoryId(),
                space.textChannelId(), space.voiceChannelId(), Optional.of(role.getId()),
                SpaceState.ACTIVE,
                space.createdAt(), Optional.empty(), Optional.of(Instant.now()));
        spaces.save(restored);

        logger.info("[Ejecutor] Espacio restaurado para town '" + req.townName() + "'");
        return OperationOutcome.success();
    }

    // -- DeleteSpace --

    private OperationOutcome deleteSpace(GuildOperation.DeleteSpace op) {
        Optional<TownSpace> opt = spaces.findByTownUuid(op.townUuid());
        if (opt.isEmpty()) {
            // Ya no existe: idempotente
            return OperationOutcome.success();
        }
        TownSpace space = opt.get();

        // Borrar rol si existe
        space.roleId().ifPresent(roleId -> {
            Role role = guild.getRoleById(roleId);
            if (role != null) {
                try {
                    role.delete().complete();
                } catch (ErrorResponseException e) {
                    if (e.getErrorResponse() != ErrorResponse.UNKNOWN_ROLE) {
                        throw e;
                    }
                }
            }
        });

        // Borrar canales
        space.textChannelId().ifPresent(chId -> {
            TextChannel ch = guild.getTextChannelById(chId);
            if (ch != null) {
                try {
                    ch.delete().complete();
                } catch (ErrorResponseException e) {
                    if (e.getErrorResponse() != ErrorResponse.UNKNOWN_CHANNEL) {
                        throw e;
                    }
                }
            }
        });

        space.voiceChannelId().ifPresent(chId -> {
            VoiceChannel ch = guild.getVoiceChannelById(chId);
            if (ch != null) {
                try {
                    ch.delete().complete();
                } catch (ErrorResponseException e) {
                    if (e.getErrorResponse() != ErrorResponse.UNKNOWN_CHANNEL) {
                        throw e;
                    }
                }
            }
        });

        // Borrar registro
        spaces.delete(op.townUuid());

        logger.info("[Ejecutor] Espacio borrado definitivamente para town '" + op.townName() + "'");
        return OperationOutcome.success();
    }

    // -- ApplyMemberRoles --

    private OperationOutcome applyMemberRoles(GuildOperation.ApplyMemberRoles op) {
        Member member = findMember(op.discordId());
        if (member == null) {
            // El usuario no pertenece al servidor; no es un fallo permanente
            return OperationOutcome.success();
        }

        // Obtener el conjunto de IDs de roles gestionados una sola vez (hallazgo 10)
        Set<String> managedRoleIds = spaces.findAll().stream()
                .flatMap(s -> s.roleId().stream())
                .collect(Collectors.toSet());

        // Solo tocamos roles gestionados por el plugin
        for (String roleId : op.grantRoleIds()) {
            Role role = guild.getRoleById(roleId);
            if (role != null && isManagedRole(role, managedRoleIds) && !member.getRoles().contains(role)) {
                guild.addRoleToMember(member, role).complete();
            }
        }

        for (String roleId : op.revokeRoleIds()) {
            Role role = guild.getRoleById(roleId);
            if (role != null && isManagedRole(role, managedRoleIds) && member.getRoles().contains(role)) {
                guild.removeRoleFromMember(member, role).complete();
            }
        }

        return OperationOutcome.success();
    }

    /**
     * Comprueba si un rol esta gestionado por el plugin.
     * Solo son roles gestionados los de las towns registradas y el rol global de alcalde.
     */
    private boolean isManagedRole(Role role, Set<String> managedRoleIds) {
        if (role == null) {
            return false;
        }
        if (managedRoleIds.contains(role.getId())) {
            return true;
        }
        Optional<String> mayorId = settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID);
        if (mayorId.isPresent()) {
            return role.getId().equals(mayorId.get());
        }
        return role.getName().equalsIgnoreCase(config.roles().mayorRoleName());
    }

    // -- Utilidades --

    /** Aplica la plantilla reemplazando {town} por el nombre. */
    private String applyTemplate(String template, String townName) {
        return template.replace("{town}", townName);
    }

    /**
     * Asegura que la categoria existe y tiene capacidad disponible. Idempotente.
     * Discord limita a 50 canales por categoria. Si la categoria se llena, se crean
     * categorias numeradas (Comunidades, Comunidades 2, Comunidades 3...).
     */
    private Category ensureCategory(TownSpace space, String baseName, int channelsNeeded) {
        if (space.categoryId().isPresent()) {
            Category cat = guild.getCategoryById(space.categoryId().get());
            if (cat != null && cat.getChannels().size() + channelsNeeded <= 50) {
                return cat;
            }
        }
        return ensureCategoryWithCapacity(baseName, channelsNeeded);
    }

    private Category ensureCategoryWithCapacity(String baseName, int channelsNeeded) {
        int index = 1;
        while (true) {
            String name = (index == 1) ? baseName : baseName + " " + index;
            List<Category> existing = guild.getCategoriesByName(name, true);
            for (Category cat : existing) {
                if (cat.getChannels() == null || cat.getChannels().size() + channelsNeeded <= 50) {
                    return cat;
                }
            }
            if (existing.isEmpty()) {
                var action = guild.createCategory(name);
                return action != null ? action.complete() : null;
            }
            index++;
        }
    }

    /**
     * Asegura que el rol de alcalde existe y persiste su identidad.
     * Es el unico punto autorizado para adoptar o escribir mayor_role_id.
     */
    Role ensureMayorRole() {
        Optional<String> persistedId = settings.get(SettingsRepository.KEY_MAYOR_ROLE_ID);
        if (persistedId.isPresent()) {
            Role role = guild.getRoleById(persistedId.get());
            if (role != null) {
                return role;
            }
        }

        String mayorRoleName = config.roles().mayorRoleName();
        List<Role> existing = guild.getRolesByName(mayorRoleName, true);
        Role role;
        if (existing.size() > 1) {
            throw new IllegalStateException("Existen multiples roles con el nombre '" + mayorRoleName
                    + "'. Un administrador debe dejar uno solo o borrar los sobrantes.");
        } else if (existing.size() == 1) {
            role = existing.getFirst();
        } else {
            var action = guild.createRole();
            if (action == null) {
                throw new IllegalStateException("No se pudo crear el rol de alcalde '" + mayorRoleName + "' en Discord");
            }
            role = action.setName(mayorRoleName).complete();
        }

        if (role == null) {
            throw new IllegalStateException("No se pudo obtener ni crear el rol de alcalde '" + mayorRoleName + "'");
        }

        settings.put(SettingsRepository.KEY_MAYOR_ROLE_ID, role.getId());
        return role;
    }

    /** Asegura que el rol existe; si no, lo crea. Idempotente. */
    private Role ensureRole(TownSpace space, String name) {
        if (space.roleId().isPresent()) {
            Role role = guild.getRoleById(space.roleId().get());
            if (role != null) {
                return role;
            }
        }
        // Buscar por nombre para evitar duplicados
        List<Role> existing = guild.getRolesByName(name, true);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        var action = guild.createRole();
        if (action == null) {
            return null;
        }
        action.setName(name);
        config.roles().townRoleColor().ifPresent(color -> {
            try {
                action.setColor(java.awt.Color.decode(color));
            } catch (NumberFormatException ignored) {
                // Color invalido: se ignora
            }
        });
        if (config.roles().townRoleHoisted()) {
            action.setHoisted(true);
        }
        return action.complete();
    }

    /** Crea un canal de texto con los permisos de la spec 3.3. Idempotente. */
    private TextChannel ensureTextChannel(TownSpace space, Category category,
                                          Role townRole, String name) {
        if (space.textChannelId().isPresent()) {
            TextChannel ch = guild.getTextChannelById(space.textChannelId().get());
            if (ch != null) {
                return ch;
            }
        }
        if (category != null && category.getTextChannels() != null) {
            for (TextChannel existing : category.getTextChannels()) {
                if (existing.getName().equalsIgnoreCase(name)) {
                    return existing;
                }
            }
        }
        if (category == null) {
            return null;
        }
        var action = category.createTextChannel(name);
        if (action == null) {
            return null;
        }
        // Crear con permisos: @everyone sin ver, rol de town con acceso, bot con gestion
        action.addPermissionOverride(guild.getPublicRole(),
                null,
                EnumSet.of(Permission.VIEW_CHANNEL));
        if (townRole != null) {
            action.addPermissionOverride(townRole,
                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND),
                    null);
        }
        if (guild.getSelfMember() != null) {
            action.addPermissionOverride(guild.getSelfMember(),
                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL,
                            Permission.MESSAGE_SEND),
                    null);
        }
        return action.complete();
    }

    /** Crea un canal de voz con los permisos de la spec 3.3. Idempotente. */
    private VoiceChannel ensureVoiceChannel(TownSpace space, Category category,
                                            Role townRole, String name) {
        if (space.voiceChannelId().isPresent()) {
            VoiceChannel ch = guild.getVoiceChannelById(space.voiceChannelId().get());
            if (ch != null) {
                return ch;
            }
        }
        if (category != null && category.getVoiceChannels() != null) {
            for (VoiceChannel existing : category.getVoiceChannels()) {
                if (existing.getName().equalsIgnoreCase(name)) {
                    return existing;
                }
            }
        }
        if (category == null) {
            return null;
        }
        var action = category.createVoiceChannel(name);
        if (action == null) {
            return null;
        }
        action.addPermissionOverride(guild.getPublicRole(),
                null,
                EnumSet.of(Permission.VIEW_CHANNEL));
        if (townRole != null) {
            action.addPermissionOverride(townRole,
                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT,
                            Permission.VOICE_SPEAK),
                    null);
        }
        if (guild.getSelfMember() != null) {
            action.addPermissionOverride(guild.getSelfMember(),
                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL,
                            Permission.VOICE_CONNECT),
                    null);
        }
        return action.complete();
    }

    /** Aplica los permisos de la spec 3.3 a un canal de texto existente (incluye bot). */
    private void applyChannelPermissions(TextChannel ch, Role townRole) {
        var p1 = ch.upsertPermissionOverride(guild.getPublicRole());
        if (p1 != null) {
            p1.deny(Permission.VIEW_CHANNEL).complete();
        }
        if (townRole != null) {
            var p2 = ch.upsertPermissionOverride(townRole);
            if (p2 != null) {
                p2.grant(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND).complete();
            }
        }
        if (guild.getSelfMember() != null) {
            var p3 = ch.upsertPermissionOverride(guild.getSelfMember());
            if (p3 != null) {
                p3.grant(Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL, Permission.MESSAGE_SEND).complete();
            }
        }
    }

    /** Aplica los permisos de la spec 3.3 a un canal de voz existente (incluye bot). */
    private void applyVoiceChannelPermissions(VoiceChannel ch, Role townRole) {
        var p1 = ch.upsertPermissionOverride(guild.getPublicRole());
        if (p1 != null) {
            p1.deny(Permission.VIEW_CHANNEL).complete();
        }
        if (townRole != null) {
            var p2 = ch.upsertPermissionOverride(townRole);
            if (p2 != null) {
                p2.grant(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK).complete();
            }
        }
        if (guild.getSelfMember() != null) {
            var p3 = ch.upsertPermissionOverride(guild.getSelfMember());
            if (p3 != null) {
                p3.grant(Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL, Permission.VOICE_CONNECT).complete();
            }
        }
    }

    /**
     * Obtiene un miembro del guild. Primero consulta la cache de JDA; si no esta en cache,
     * lo recupera de la API de Discord. Devuelve null si el usuario no pertenece al servidor.
     */
    private Member findMember(String discordId) {
        if (discordId == null || discordId.isBlank()) {
            return null;
        }
        Member member = guild.getMemberById(discordId);
        if (member != null) {
            return member;
        }
        try {
            var action = guild.retrieveMemberById(discordId);
            if (action == null) {
                return null;
            }
            return action.complete();
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER
                    || e.getErrorResponse() == ErrorResponse.UNKNOWN_USER) {
                return null;
            }
            throw e;
        }
    }

    /** Asigna un rol a una lista de miembros del guild. */
    private void assignRolesToMembers(Role role, List<String> discordIds) {
        if (role == null || discordIds == null) return;
        for (String discordId : discordIds) {
            assignRoleToMember(role, discordId);
        }
    }

    /** Asigna un rol a un miembro del guild de forma segura e idempotente. */
    private void assignRoleToMember(Role role, String discordId) {
        if (role == null) return;
        Member member = findMember(discordId);
        if (member != null && !member.getRoles().contains(role)) {
            var action = guild.addRoleToMember(member, role);
            if (action != null) {
                action.complete();
            }
        }
    }

    /** Clasifica un error de JDA como transitorio o permanente. */
    private OperationOutcome classifyError(ErrorResponseException e, GuildOperation operation) {
        ErrorResponse response = e.getErrorResponse();
        String context = operation.describe();
        return switch (response) {
            case UNKNOWN_CHANNEL, UNKNOWN_ROLE -> {
                if (operation instanceof GuildOperation.DeleteSpace) {
                    yield OperationOutcome.success();
                }
                yield OperationOutcome.permanentFailure(
                        "Recurso no encontrado en '" + context + "': " + response);
            }
            case MISSING_PERMISSIONS, MISSING_ACCESS ->
                    OperationOutcome.permanentFailure(
                            "Permisos insuficientes en '" + context + "': " + response);
            case MAX_CHANNELS, MAX_ROLES_PER_GUILD ->
                    OperationOutcome.permanentFailure(
                            "Limite de Discord alcanzado en '" + context + "': " + response);
            default ->
                    OperationOutcome.transientFailure(
                            "Error de Discord en '" + context + "': " + response);
        };
    }

    @Override
    public void onOperationFailed(GuildOperation operation, OperationOutcome outcome) {
        extractTownUuid(operation).ifPresent(this::markSpaceInconsistent);
    }

    private Optional<UUID> extractTownUuid(GuildOperation op) {
        return switch (op) {
            case GuildOperation.CreateSpace c -> Optional.of(c.request().townUuid());
            case GuildOperation.RenameSpace r -> Optional.of(r.townUuid());
            case GuildOperation.ArchiveSpace a -> Optional.of(a.townUuid());
            case GuildOperation.RestoreSpace res -> Optional.of(res.request().townUuid());
            case GuildOperation.DeleteSpace d -> Optional.of(d.townUuid());
            case GuildOperation.ApplyMemberRoles ignored -> Optional.empty();
        };
    }

    /** Marca un espacio como inconsistente en la base de datos si ocurre un fallo. */
    private void markSpaceInconsistent(UUID townUuid) {
        if (townUuid == null) return;
        try {
            spaces.findByTownUuid(townUuid).ifPresent(s -> {
                if (s.state() != SpaceState.INCONSISTENT) {
                    TownSpace updated = new TownSpace(
                            s.townUuid(), s.townName(), s.categoryId(),
                            s.textChannelId(), s.voiceChannelId(), s.roleId(),
                            SpaceState.INCONSISTENT,
                            s.createdAt(), s.archivedAt(), s.lastActivityAt());
                    spaces.save(updated);
                    logger.warning("[Ejecutor] Espacio de '" + s.townName()
                            + "' marcado como INCONSISTENT tras fallo");
                }
            });
        } catch (Exception e) {
            logger.warning("[Ejecutor] No se pudo marcar espacio como INCONSISTENT: " + e.getMessage());
        }
    }

    // -- Constructores de TownSpace con campos actualizados --

    private TownSpace newEmptySpace(SpaceRequest req) {
        return new TownSpace(
                req.townUuid(), req.townName(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                SpaceState.INCONSISTENT,
                Instant.now(), Optional.empty(), Optional.of(Instant.now()));
    }

    private TownSpace withCategory(TownSpace s, String categoryId) {
        return new TownSpace(s.townUuid(), s.townName(),
                Optional.ofNullable(categoryId), s.textChannelId(), s.voiceChannelId(), s.roleId(),
                s.state(), s.createdAt(), s.archivedAt(), s.lastActivityAt());
    }

    private TownSpace withRole(TownSpace s, String roleId) {
        return new TownSpace(s.townUuid(), s.townName(),
                s.categoryId(), s.textChannelId(), s.voiceChannelId(), Optional.ofNullable(roleId),
                s.state(), s.createdAt(), s.archivedAt(), s.lastActivityAt());
    }

    private TownSpace withTextChannel(TownSpace s, String channelId) {
        return new TownSpace(s.townUuid(), s.townName(),
                s.categoryId(), Optional.ofNullable(channelId), s.voiceChannelId(), s.roleId(),
                s.state(), s.createdAt(), s.archivedAt(), s.lastActivityAt());
    }

    private TownSpace withVoiceChannel(TownSpace s, String channelId) {
        return new TownSpace(s.townUuid(), s.townName(),
                s.categoryId(), s.textChannelId(), Optional.ofNullable(channelId), s.roleId(),
                s.state(), s.createdAt(), s.archivedAt(), s.lastActivityAt());
    }

    private TownSpace withState(TownSpace s, SpaceState state) {
        return new TownSpace(s.townUuid(), s.townName(),
                s.categoryId(), s.textChannelId(), s.voiceChannelId(), s.roleId(),
                state, s.createdAt(), s.archivedAt(), s.lastActivityAt());
    }

    /**
     * Elimina el token del mensaje de error, si aparece.
     * El token NUNCA debe aparecer en logs (P7 de la constitucion).
     */
    private String sanitizeMessage(String message) {
        return DiscordSanitizer.sanitize(message, config.discord().token());
    }
}
