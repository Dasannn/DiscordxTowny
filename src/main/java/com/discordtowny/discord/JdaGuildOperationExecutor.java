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
 * Executes each {@link GuildOperation} case against a Discord guild.
 *
 * <p>Each step is <b>idempotent</b>: before creating anything, it checks
 * whether it already exists by its saved identifier. Retrying a half-finished
 * operation cannot duplicate channels or roles.
 *
 * <p>Channel permissions follow spec 3.3 and are NOT configurable:
 * {@code @everyone} cannot see the channel, only the town role has access.
 * A half-finished failure can never leave a channel visible to unauthorized
 * users, because permissions are applied upon creating the channel, before
 * it becomes visible.
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
        this.settings = java.util.Objects.requireNonNull(settings, "settings cannot be null");
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
            logger.warning("[Executor] Insufficient permissions or hierarchy violation in '"
                    + operation.describe() + "': " + safeMsg);
            OperationOutcome outcome = OperationOutcome.permanentFailure(
                    "Insufficient permissions or invalid hierarchy in '" + operation.describe() + "': " + safeMsg);
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
            logger.warning("[Executor] Unexpected error in '" + operation.describe()
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

        // 1. Container category (shared with 50-channel limit)
        Category category = ensureCategory(space, config.structure().categoryName(), channelsNeeded);
        space = withCategory(space, category.getId());
        spaces.save(space);

        // 2. Town role
        Role role = ensureRole(space, applyTemplate(config.roles().townRoleName(), req.townName()));
        space = withRole(space, role.getId());
        spaces.save(space);

        // 3. Text channel (if requested by config)
        if (config.structure().createTextChannel()) {
            TextChannel textCh = ensureTextChannel(space, category, role,
                    applyTemplate(config.structure().textChannelName(), req.townName()));
            if (textCh != null) {
                space = withTextChannel(space, textCh.getId());
                spaces.save(space);
            }
        }

        // 4. Voice channel (if requested by config)
        if (config.structure().createVoiceChannel()) {
            VoiceChannel voiceCh = ensureVoiceChannel(space, category, role,
                    applyTemplate(config.structure().voiceChannelName(), req.townName()));
            if (voiceCh != null) {
                space = withVoiceChannel(space, voiceCh.getId());
                spaces.save(space);
            }
        }

        // 5. Assign roles to linked residents and to the mayor (spec 4.1)
        assignRolesToMembers(role, req.linkedResidentDiscordIds());
        if (req.mayorDiscordId() != null && !req.mayorDiscordId().isBlank()) {
            Role mayorRole = ensureMayorRole();
            assignRoleToMember(mayorRole, req.mayorDiscordId());
            assignRoleToMember(role, req.mayorDiscordId());
        }

        // 6. Space completed successfully: mark as ACTIVE
        space = withState(space, SpaceState.ACTIVE);
        spaces.save(space);

        logger.info("[Executor] Space created for town '" + req.townName() + "'");
        return OperationOutcome.success();
    }

    // -- RenameSpace --

    private OperationOutcome renameSpace(GuildOperation.RenameSpace op) {
        Optional<TownSpace> opt = spaces.findByTownUuid(op.townUuid());
        if (opt.isEmpty()) {
            return OperationOutcome.permanentFailure(
                    "No space registered for town " + op.townUuid());
        }
        TownSpace space = opt.get();
        String newName = op.newName();

        // Rename role
        space.roleId().ifPresent(roleId -> {
            Role role = guild.getRoleById(roleId);
            if (role != null) {
                role.getManager()
                        .setName(applyTemplate(config.roles().townRoleName(), newName))
                        .complete();
            }
        });

        // Rename channels
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

        // Update name in DB
        TownSpace updated = new TownSpace(
                space.townUuid(), newName, space.categoryId(),
                space.textChannelId(), space.voiceChannelId(), space.roleId(),
                space.state(), space.createdAt(), space.archivedAt(), space.lastActivityAt());
        spaces.save(updated);

        logger.info("[Executor] Space renamed from '" + op.oldName() + "' to '" + newName + "'");
        return OperationOutcome.success();
    }

    // -- ArchiveSpace --

    private OperationOutcome archiveSpace(GuildOperation.ArchiveSpace op) {
        Optional<TownSpace> opt = spaces.findByTownUuid(op.townUuid());
        if (opt.isEmpty()) {
            return OperationOutcome.permanentFailure(
                    "No space registered for town " + op.townUuid());
        }
        TownSpace space = opt.get();

        // 1. Delete role (revokes access for everyone)
        space.roleId().ifPresent(roleId -> {
            Role role = guild.getRoleById(roleId);
            if (role != null) {
                role.delete().complete();
            }
        });

        // 2. Move channels to archive category and set to read-only for administrators
        int channelsNeeded = (space.textChannelId().isPresent() ? 1 : 0)
                + (space.voiceChannelId().isPresent() ? 1 : 0);
        Category archive = ensureCategoryWithCapacity(config.structure().archiveCategoryName(), channelsNeeded);

        space.textChannelId().ifPresent(chId -> {
            TextChannel ch = guild.getTextChannelById(chId);
            if (ch != null) {
                ch.getManager().setParent(archive).complete();
                // Visible only for administrators in read-only: @everyone cannot see the channel
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
                // Visible only for administrators in read-only: @everyone cannot see the channel
                ch.upsertPermissionOverride(guild.getPublicRole())
                        .deny(Permission.VOICE_CONNECT, Permission.VIEW_CHANNEL)
                        .complete();
                ch.upsertPermissionOverride(guild.getSelfMember())
                        .grant(Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL)
                        .complete();
            }
        });

        // 3. Update state in DB
        TownSpace archived = new TownSpace(
                space.townUuid(), space.townName(), space.categoryId(),
                space.textChannelId(), space.voiceChannelId(), Optional.empty(),
                SpaceState.ARCHIVED,
                space.createdAt(), Optional.of(Instant.now()), space.lastActivityAt());
        spaces.save(archived);

        logger.info("[Executor] Space archived for town '" + op.townName() + "'");
        return OperationOutcome.success();
    }

    // -- RestoreSpace --

    private OperationOutcome restoreSpace(SpaceRequest req) {
        Optional<TownSpace> opt = spaces.findByTownUuid(req.townUuid());
        if (opt.isEmpty()) {
            return OperationOutcome.permanentFailure(
                    "No archived space found for town " + req.townUuid());
        }
        TownSpace space = opt.get();

        // Mark as inconsistent during restoration
        space = withState(space, SpaceState.INCONSISTENT);
        spaces.save(space);

        // 1. Active category
        int channelsNeeded = (space.textChannelId().isPresent() ? 1 : 0)
                + (space.voiceChannelId().isPresent() ? 1 : 0);
        Category category = ensureCategory(space, config.structure().categoryName(), channelsNeeded);
        space = withCategory(space, category.getId());
        spaces.save(space);

        // 2. Create new role (the archived one was deleted) and persist immediately
        Role role = ensureRole(space, applyTemplate(config.roles().townRoleName(), req.townName()));
        space = withRole(space, role.getId());
        spaces.save(space);

        // 3. Move channels back and apply permissions (with bot permissions)
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

        // 4. Assign roles to linked residents and the mayor (spec 4.1)
        assignRolesToMembers(role, req.linkedResidentDiscordIds());
        if (req.mayorDiscordId() != null && !req.mayorDiscordId().isBlank()) {
            Role mayorRole = ensureMayorRole();
            assignRoleToMember(mayorRole, req.mayorDiscordId());
            assignRoleToMember(role, req.mayorDiscordId());
        }

        // 5. Update state to ACTIVE
        TownSpace restored = new TownSpace(
                space.townUuid(), req.townName(), space.categoryId(),
                space.textChannelId(), space.voiceChannelId(), Optional.of(role.getId()),
                SpaceState.ACTIVE,
                space.createdAt(), Optional.empty(), Optional.of(Instant.now()));
        spaces.save(restored);

        logger.info("[Executor] Space restored for town '" + req.townName() + "'");
        return OperationOutcome.success();
    }

    // -- DeleteSpace --

    private OperationOutcome deleteSpace(GuildOperation.DeleteSpace op) {
        Optional<TownSpace> opt = spaces.findByTownUuid(op.townUuid());
        if (opt.isEmpty()) {
            // No longer exists: idempotent
            return OperationOutcome.success();
        }
        TownSpace space = opt.get();

        // Delete role if it exists
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

        // Delete channels
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

        // Delete record
        spaces.delete(op.townUuid());

        logger.info("[Executor] Space permanently deleted for town '" + op.townName() + "'");
        return OperationOutcome.success();
    }

    // -- ApplyMemberRoles --

    private OperationOutcome applyMemberRoles(GuildOperation.ApplyMemberRoles op) {
        Member member = findMember(op.discordId());
        if (member == null) {
            // The user does not belong to the guild; this is not a permanent failure
            return OperationOutcome.success();
        }

        // Obtain the set of managed role IDs once (finding 10)
        Set<String> managedRoleIds = spaces.findAll().stream()
                .flatMap(s -> s.roleId().stream())
                .collect(Collectors.toSet());

        // Only touch roles managed by the plugin
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
     * Checks whether a role is managed by the plugin.
     * Only registered town roles and the global mayor role are managed roles.
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

    // -- Utilities --

    /** Applies the template by replacing {town} with the name. */
    private String applyTemplate(String template, String townName) {
        return template.replace("{town}", townName);
    }

    /**
     * Ensures that the category exists and has available capacity. Idempotent.
     * Discord limits categories to 50 channels each. If the category fills up, numbered
     * categories are created (Communities, Communities 2, Communities 3...).
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
     * Ensures that the mayor role exists and persists its identity.
     * This is the only authorized point to adopt or write mayor_role_id.
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
            throw new IllegalStateException("Multiple roles exist with the name '" + mayorRoleName
                    + "'. An administrator must keep only one or delete the surplus roles.");
        } else if (existing.size() == 1) {
            role = existing.getFirst();
        } else {
            var action = guild.createRole();
            if (action == null) {
                throw new IllegalStateException("Could not create mayor role '" + mayorRoleName + "' in Discord");
            }
            role = action.setName(mayorRoleName).complete();
        }

        if (role == null) {
            throw new IllegalStateException("Could not obtain or create mayor role '" + mayorRoleName + "'");
        }

        settings.put(SettingsRepository.KEY_MAYOR_ROLE_ID, role.getId());
        return role;
    }

    /** Ensures that the role exists; creates it if it does not. Idempotent. */
    private Role ensureRole(TownSpace space, String name) {
        if (space.roleId().isPresent()) {
            Role role = guild.getRoleById(space.roleId().get());
            if (role != null) {
                return role;
            }
        }
        // Search by name to avoid duplicates
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
                // Invalid color: ignored
            }
        });
        if (config.roles().townRoleHoisted()) {
            action.setHoisted(true);
        }
        return action.complete();
    }

    /** Creates a text channel with spec 3.3 permissions. Idempotent. */
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
        // Create with permissions: @everyone denied view, town role with access, bot with manage
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

    /** Creates a voice channel with spec 3.3 permissions. Idempotent. */
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

    /** Applies spec 3.3 permissions to an existing text channel (including bot). */
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

    /** Applies spec 3.3 permissions to an existing voice channel (including bot). */
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
     * Obtains a guild member. First queries the JDA cache; if not in cache,
     * retrieves them from the Discord API. Returns null if the user does not belong to the guild.
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

    /** Assigns a role to a list of guild members. */
    private void assignRolesToMembers(Role role, List<String> discordIds) {
        if (role == null || discordIds == null) return;
        for (String discordId : discordIds) {
            assignRoleToMember(role, discordId);
        }
    }

    /** Assigns a role to a guild member safely and idempotently. */
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

    /** Classifies a JDA error as transient or permanent. */
    private OperationOutcome classifyError(ErrorResponseException e, GuildOperation operation) {
        ErrorResponse response = e.getErrorResponse();
        String context = operation.describe();
        return switch (response) {
            case UNKNOWN_CHANNEL, UNKNOWN_ROLE -> {
                if (operation instanceof GuildOperation.DeleteSpace) {
                    yield OperationOutcome.success();
                }
                yield OperationOutcome.permanentFailure(
                        "Resource not found in '" + context + "': " + response);
            }
            case MISSING_PERMISSIONS, MISSING_ACCESS ->
                    OperationOutcome.permanentFailure(
                            "Insufficient permissions in '" + context + "': " + response);
            case MAX_CHANNELS, MAX_ROLES_PER_GUILD ->
                    OperationOutcome.permanentFailure(
                            "Discord limit reached in '" + context + "': " + response);
            default ->
                    OperationOutcome.transientFailure(
                            "Discord error in '" + context + "': " + response);
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

    /** Marks a space as inconsistent in the database if a failure occurs. */
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
                    logger.warning("[Executor] Space for '" + s.townName()
                            + "' marked as INCONSISTENT after failure");
                }
            });
        } catch (Exception e) {
            logger.warning("[Executor] Could not mark space as INCONSISTENT: " + e.getMessage());
        }
    }

    // -- TownSpace constructors with updated fields --

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
     * Removes the token from the error message, if present.
     * The token must NEVER appear in logs (P7 of the constitution).
     */
    private String sanitizeMessage(String message) {
        return DiscordSanitizer.sanitize(message, config.discord().token());
    }
}
