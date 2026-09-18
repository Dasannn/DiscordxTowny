package com.discordtowny.discord;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.model.SpaceRequest;
import com.discordtowny.model.TownSpace;
import com.discordtowny.storage.SpaceRepository;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

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
    private final Logger logger;

    JdaGuildOperationExecutor(Guild guild, PluginConfig config,
                              SpaceRepository spaces, Logger logger) {
        this.guild = guild;
        this.config = config;
        this.spaces = spaces;
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
        } catch (ErrorResponseException e) {
            return classifyError(e, operation.describe());
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

        // 1. Categoria contenedora (compartida)
        Category category = ensureCategory(space, config.structure().categoryName());
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
            space = withTextChannel(space, textCh.getId());
            spaces.save(space);
        }

        // 4. Canal de voz (si la config lo pide)
        if (config.structure().createVoiceChannel()) {
            VoiceChannel voiceCh = ensureVoiceChannel(space, category, role,
                    applyTemplate(config.structure().voiceChannelName(), req.townName()));
            space = withVoiceChannel(space, voiceCh.getId());
            spaces.save(space);
        }

        // 5. Asignar roles a residentes vinculados
        assignRolesToMembers(role, req.linkedResidentDiscordIds());

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

        // 2. Mover canales a la categoria de archivo y ponerlos en solo lectura
        Category archive = ensureCategoryByName(config.structure().archiveCategoryName());

        space.textChannelId().ifPresent(chId -> {
            TextChannel ch = guild.getTextChannelById(chId);
            if (ch != null) {
                ch.getManager().setParent(archive).complete();
                // Solo lectura: @everyone sin escribir
                ch.upsertPermissionOverride(guild.getPublicRole())
                        .deny(Permission.MESSAGE_SEND, Permission.VIEW_CHANNEL)
                        .complete();
            }
        });

        space.voiceChannelId().ifPresent(chId -> {
            VoiceChannel ch = guild.getVoiceChannelById(chId);
            if (ch != null) {
                ch.getManager().setParent(archive).complete();
                ch.upsertPermissionOverride(guild.getPublicRole())
                        .deny(Permission.VOICE_CONNECT, Permission.VIEW_CHANNEL)
                        .complete();
            }
        });

        // 3. Actualizar estado en BD
        TownSpace archived = new TownSpace(
                space.townUuid(), space.townName(), space.categoryId(),
                space.textChannelId(), space.voiceChannelId(), Optional.empty(),
                com.discordtowny.model.SpaceState.ARCHIVED,
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

        // 1. Categoria activa
        Category category = ensureCategory(space, config.structure().categoryName());

        // 2. Crear nuevo rol (el archivado fue eliminado)
        Role role = ensureRole(space, applyTemplate(config.roles().townRoleName(), req.townName()));
        space = withRole(space, role.getId());

        // 3. Mover canales de vuelta y aplicar permisos
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

        // 4. Asignar roles a residentes vinculados
        assignRolesToMembers(role, req.linkedResidentDiscordIds());

        // 5. Actualizar estado
        TownSpace restored = new TownSpace(
                space.townUuid(), req.townName(), space.categoryId(),
                space.textChannelId(), space.voiceChannelId(), Optional.of(role.getId()),
                com.discordtowny.model.SpaceState.ACTIVE,
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
                role.delete().complete();
            }
        });

        // Borrar canales
        space.textChannelId().ifPresent(chId -> {
            TextChannel ch = guild.getTextChannelById(chId);
            if (ch != null) {
                ch.delete().complete();
            }
        });

        space.voiceChannelId().ifPresent(chId -> {
            VoiceChannel ch = guild.getVoiceChannelById(chId);
            if (ch != null) {
                ch.delete().complete();
            }
        });

        // Borrar registro
        spaces.delete(op.townUuid());

        logger.info("[Ejecutor] Espacio borrado definitivamente para town '" + op.townName() + "'");
        return OperationOutcome.success();
    }

    // -- ApplyMemberRoles --

    private OperationOutcome applyMemberRoles(GuildOperation.ApplyMemberRoles op) {
        Member member = guild.getMemberById(op.discordId());
        if (member == null) {
            // El usuario quiza salio del guild; no es un fallo permanente
            return OperationOutcome.success();
        }

        // Solo tocamos roles gestionados por el plugin
        for (String roleId : op.grantRoleIds()) {
            Role role = guild.getRoleById(roleId);
            if (role != null && isManagedRole(role) && !member.getRoles().contains(role)) {
                guild.addRoleToMember(member, role).complete();
            }
        }

        for (String roleId : op.revokeRoleIds()) {
            Role role = guild.getRoleById(roleId);
            if (role != null && isManagedRole(role) && member.getRoles().contains(role)) {
                guild.removeRoleFromMember(member, role).complete();
            }
        }

        return OperationOutcome.success();
    }

    /**
     * Comprueba si un rol esta gestionado por el plugin.
     * Solo son roles gestionados los de las towns registradas y el rol global de alcalde.
     */
    private boolean isManagedRole(Role role) {
        if (role == null) {
            return false;
        }
        if (role.getName().equalsIgnoreCase(config.roles().mayorRoleName())) {
            return true;
        }
        String roleId = role.getId();
        return spaces.findAll().stream()
                .anyMatch(s -> s.roleId().filter(roleId::equals).isPresent());
    }

    // -- Utilidades --

    /** Aplica la plantilla reemplazando {town} por el nombre. */
    private String applyTemplate(String template, String townName) {
        return template.replace("{town}", townName);
    }

    /** Asegura que la categoria existe; si no, la crea. Idempotente. */
    private Category ensureCategory(TownSpace space, String name) {
        // Si ya tenemos el ID guardado, comprobamos que sigue viva
        if (space.categoryId().isPresent()) {
            Category cat = guild.getCategoryById(space.categoryId().get());
            if (cat != null) {
                return cat;
            }
        }
        // Buscar por nombre antes de crear
        return ensureCategoryByName(name);
    }

    private Category ensureCategoryByName(String name) {
        List<Category> existing = guild.getCategoriesByName(name, true);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        return guild.createCategory(name).complete();
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
        var action = guild.createRole().setName(name);
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
        for (TextChannel existing : category.getTextChannels()) {
            if (existing.getName().equalsIgnoreCase(name)) {
                return existing;
            }
        }
        // Crear con permisos: @everyone sin ver, rol de town con acceso
        TextChannel ch = category.createTextChannel(name)
                .addPermissionOverride(guild.getPublicRole(),
                        null,
                        EnumSet.of(Permission.VIEW_CHANNEL))
                .addPermissionOverride(townRole,
                        EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND),
                        null)
                .addPermissionOverride(guild.getSelfMember(),
                        EnumSet.of(Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL,
                                Permission.MESSAGE_SEND),
                        null)
                .complete();
        return ch;
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
        for (VoiceChannel existing : category.getVoiceChannels()) {
            if (existing.getName().equalsIgnoreCase(name)) {
                return existing;
            }
        }
        VoiceChannel ch = category.createVoiceChannel(name)
                .addPermissionOverride(guild.getPublicRole(),
                        null,
                        EnumSet.of(Permission.VIEW_CHANNEL))
                .addPermissionOverride(townRole,
                        EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT,
                                Permission.VOICE_SPEAK),
                        null)
                .addPermissionOverride(guild.getSelfMember(),
                        EnumSet.of(Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL,
                                Permission.VOICE_CONNECT),
                        null)
                .complete();
        return ch;
    }

    /** Aplica los permisos de la spec 3.3 a un canal de texto existente. */
    private void applyChannelPermissions(TextChannel ch, Role townRole) {
        ch.upsertPermissionOverride(guild.getPublicRole())
                .deny(Permission.VIEW_CHANNEL)
                .complete();
        ch.upsertPermissionOverride(townRole)
                .grant(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)
                .complete();
    }

    /** Aplica los permisos de la spec 3.3 a un canal de voz existente. */
    private void applyVoiceChannelPermissions(VoiceChannel ch, Role townRole) {
        ch.upsertPermissionOverride(guild.getPublicRole())
                .deny(Permission.VIEW_CHANNEL)
                .complete();
        ch.upsertPermissionOverride(townRole)
                .grant(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)
                .complete();
    }

    /** Asigna un rol a una lista de miembros del guild. */
    private void assignRolesToMembers(Role role, List<String> discordIds) {
        for (String discordId : discordIds) {
            Member member = guild.getMemberById(discordId);
            if (member != null && !member.getRoles().contains(role)) {
                guild.addRoleToMember(member, role).complete();
            }
        }
    }

    /** Clasifica un error de JDA como transitorio o permanente. */
    private OperationOutcome classifyError(ErrorResponseException e, String context) {
        ErrorResponse response = e.getErrorResponse();
        return switch (response) {
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

    // -- Constructores de TownSpace con campos actualizados --

    private TownSpace newEmptySpace(SpaceRequest req) {
        return new TownSpace(
                req.townUuid(), req.townName(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                com.discordtowny.model.SpaceState.ACTIVE,
                Instant.now(), Optional.empty(), Optional.of(Instant.now()));
    }

    private TownSpace withCategory(TownSpace s, String categoryId) {
        return new TownSpace(s.townUuid(), s.townName(),
                Optional.of(categoryId), s.textChannelId(), s.voiceChannelId(), s.roleId(),
                s.state(), s.createdAt(), s.archivedAt(), s.lastActivityAt());
    }

    private TownSpace withRole(TownSpace s, String roleId) {
        return new TownSpace(s.townUuid(), s.townName(),
                s.categoryId(), s.textChannelId(), s.voiceChannelId(), Optional.of(roleId),
                s.state(), s.createdAt(), s.archivedAt(), s.lastActivityAt());
    }

    private TownSpace withTextChannel(TownSpace s, String channelId) {
        return new TownSpace(s.townUuid(), s.townName(),
                s.categoryId(), Optional.of(channelId), s.voiceChannelId(), s.roleId(),
                s.state(), s.createdAt(), s.archivedAt(), s.lastActivityAt());
    }

    private TownSpace withVoiceChannel(TownSpace s, String channelId) {
        return new TownSpace(s.townUuid(), s.townName(),
                s.categoryId(), s.textChannelId(), Optional.of(channelId), s.roleId(),
                s.state(), s.createdAt(), s.archivedAt(), s.lastActivityAt());
    }

    /**
     * Elimina el token del mensaje de error, si aparece.
     * El token NUNCA debe aparecer en logs (P7 de la constitucion).
     */
    private String sanitizeMessage(String message) {
        if (message == null) return "error desconocido";
        String token = config.discord().token();
        if (token != null && !token.isEmpty() && message.contains(token)) {
            return message.replace(token, "[TOKEN_OCULTO]");
        }
        return message;
    }
}
