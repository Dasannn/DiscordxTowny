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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
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
    private volatile PluginConfig config;
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

    void updateConfig(PluginConfig config) {
        this.config = java.util.Objects.requireNonNull(config, "config cannot be null");
    }

    PluginConfig config() {
        return config;
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
        } catch (IllegalStateException e) {
            String safeMsg = sanitizeMessage(e.getMessage());
            logger.warning("[Executor] Conflict or unrecoverable state in '"
                    + operation.describe() + "': " + safeMsg);
            OperationOutcome outcome = OperationOutcome.permanentFailure(safeMsg);
            onOperationFailed(operation, outcome);
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

        // 1. Town role first: role has no category dependency and channels need it for permissions.
        // If a role already exists with this name and is not owned by this town,
        // ensureRole throws IllegalStateException (F2) rather than adopting an unowned role.
        Role role = ensureRole(space, applyTemplate(config.roles().townRoleName(), req.townName()));
        if (role == null) {
            throw new IllegalStateException("Could not ensure town role for " + req.townName());
        }
        space = withRole(space, role.getId());
        spaces.save(space);

        // 2. Reclaim already-created channels across all managed categories (F1).
        // This covers the return-to-write crash window without duplicating channels.
        space = reclaimExistingChannels(space, role, req.townName());

        // 3. Determine genuinely missing channels
        boolean wantsText = config.structure().createTextChannel();
        boolean wantsVoice = config.structure().createVoiceChannel();

        boolean textMissing = wantsText && (space.textChannelId().isEmpty()
                || guild.getTextChannelById(space.textChannelId().get()) == null);
        boolean voiceMissing = wantsVoice && (space.voiceChannelId().isEmpty()
                || guild.getVoiceChannelById(space.voiceChannelId().get()) == null);

        int channelsNeeded = (textMissing ? 1 : 0) + (voiceMissing ? 1 : 0);

        // 4. Ensure container category with capacity for genuinely missing channels
        Category category = ensureCategory(space, config.structure().categoryName(), channelsNeeded);
        if (category == null) {
            throw new IllegalStateException("Could not ensure category for town " + req.townName());
        }
        space = withCategory(space, category.getId());
        spaces.save(space);

        // 5. Text channel (if requested)
        if (wantsText) {
            TextChannel textCh;
            if (space.textChannelId().isPresent()) {
                textCh = guild.getTextChannelById(space.textChannelId().get());
                if (textCh != null) {
                    // Reapply permissions to ensure postconditions are met (F2)
                    applyChannelPermissions(textCh, role);
                } else {
                    textCh = createTextChannel(category, role,
                            applyTemplate(config.structure().textChannelName(), req.townName()));
                    space = withTextChannel(space, textCh.getId());
                    spaces.save(space);
                }
            } else {
                textCh = createTextChannel(category, role,
                        applyTemplate(config.structure().textChannelName(), req.townName()));
                space = withTextChannel(space, textCh.getId());
                spaces.save(space);
            }
        }

        // 6. Voice channel (if requested)
        if (wantsVoice) {
            VoiceChannel voiceCh;
            if (space.voiceChannelId().isPresent()) {
                voiceCh = guild.getVoiceChannelById(space.voiceChannelId().get());
                if (voiceCh != null) {
                    // Reapply permissions to ensure postconditions are met (F2)
                    applyVoiceChannelPermissions(voiceCh, role);
                } else {
                    voiceCh = createVoiceChannel(category, role,
                            applyTemplate(config.structure().voiceChannelName(), req.townName()));
                    space = withVoiceChannel(space, voiceCh.getId());
                    spaces.save(space);
                }
            } else {
                voiceCh = createVoiceChannel(category, role,
                        applyTemplate(config.structure().voiceChannelName(), req.townName()));
                space = withVoiceChannel(space, voiceCh.getId());
                spaces.save(space);
            }
        }

        // 7. Assign roles to linked residents and mayor (spec 4.1)
        assignRolesToMembers(role, req.linkedResidentDiscordIds());
        if (req.mayorDiscordId() != null && !req.mayorDiscordId().isBlank()) {
            Role mayorRole = ensureMayorRole();
            assignRoleToMember(mayorRole, req.mayorDiscordId());
            assignRoleToMember(role, req.mayorDiscordId());
        }

        // 8. Space completed successfully: mark as ACTIVE
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

        // 1. Verify required role exists (F5)
        Role role = null;
        if (space.roleId().isPresent()) {
            String roleId = space.roleId().get();
            role = guild.getRoleById(roleId);
            if (role == null) {
                OperationOutcome outcome = OperationOutcome.permanentFailure(
                        "Required role " + roleId + " not found in Discord for town " + op.townUuid());
                onOperationFailed(op, outcome);
                return outcome;
            }
        }

        // 2. Verify required channels exist (F5)
        TextChannel textCh = null;
        if (space.textChannelId().isPresent()) {
            String chId = space.textChannelId().get();
            textCh = guild.getTextChannelById(chId);
            if (textCh == null) {
                OperationOutcome outcome = OperationOutcome.permanentFailure(
                        "Required text channel " + chId + " not found in Discord for town " + op.townUuid());
                onOperationFailed(op, outcome);
                return outcome;
            }
        }

        VoiceChannel voiceCh = null;
        if (space.voiceChannelId().isPresent()) {
            String chId = space.voiceChannelId().get();
            voiceCh = guild.getVoiceChannelById(chId);
            if (voiceCh == null) {
                OperationOutcome outcome = OperationOutcome.permanentFailure(
                        "Required voice channel " + chId + " not found in Discord for town " + op.townUuid());
                onOperationFailed(op, outcome);
                return outcome;
            }
        }

        // Rename role
        if (role != null) {
            role.getManager()
                    .setName(applyTemplate(config.roles().townRoleName(), newName))
                    .complete();
        }

        // Rename channels
        if (textCh != null) {
            textCh.getManager()
                    .setName(applyTemplate(config.structure().textChannelName(), newName))
                    .complete();
        }

        if (voiceCh != null) {
            voiceCh.getManager()
                    .setName(applyTemplate(config.structure().voiceChannelName(), newName))
                    .complete();
        }

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

        // Verify required channels exist before archiving (F5)
        TextChannel textCh = null;
        if (space.textChannelId().isPresent()) {
            String chId = space.textChannelId().get();
            textCh = guild.getTextChannelById(chId);
            if (textCh == null) {
                OperationOutcome outcome = OperationOutcome.permanentFailure(
                        "Required text channel " + chId + " not found in Discord for town " + op.townUuid());
                onOperationFailed(op, outcome);
                return outcome;
            }
        }

        VoiceChannel voiceCh = null;
        if (space.voiceChannelId().isPresent()) {
            String chId = space.voiceChannelId().get();
            voiceCh = guild.getVoiceChannelById(chId);
            if (voiceCh == null) {
                OperationOutcome outcome = OperationOutcome.permanentFailure(
                        "Required voice channel " + chId + " not found in Discord for town " + op.townUuid());
                onOperationFailed(op, outcome);
                return outcome;
            }
        }

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

        if (textCh != null) {
            textCh.getManager().setParent(archive).complete();
            // Visible only for administrators in read-only: @everyone cannot see the channel
            var p1 = textCh.upsertPermissionOverride(guild.getPublicRole());
            if (p1 != null) {
                p1.deny(Permission.MESSAGE_SEND, Permission.VIEW_CHANNEL).complete();
            }
            if (guild.getSelfMember() != null) {
                var p2 = textCh.upsertPermissionOverride(guild.getSelfMember());
                if (p2 != null) {
                    p2.grant(Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL).complete();
                }
            }
        }

        if (voiceCh != null) {
            voiceCh.getManager().setParent(archive).complete();
            // Visible only for administrators in read-only: @everyone cannot see the channel
            var p1 = voiceCh.upsertPermissionOverride(guild.getPublicRole());
            if (p1 != null) {
                p1.deny(Permission.VOICE_CONNECT, Permission.VIEW_CHANNEL).complete();
            }
            if (guild.getSelfMember() != null) {
                var p2 = voiceCh.upsertPermissionOverride(guild.getSelfMember());
                if (p2 != null) {
                    p2.grant(Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL).complete();
                }
            }
        }

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

        // Verify required channels exist in Discord before restoring (F5)
        TextChannel textCh = null;
        if (space.textChannelId().isPresent()) {
            String chId = space.textChannelId().get();
            textCh = guild.getTextChannelById(chId);
            if (textCh == null) {
                OperationOutcome outcome = OperationOutcome.permanentFailure(
                        "Required text channel " + chId + " not found in Discord for town " + req.townUuid());
                onOperationFailed(new GuildOperation.RestoreSpace(req), outcome);
                return outcome;
            }
        }

        VoiceChannel voiceCh = null;
        if (space.voiceChannelId().isPresent()) {
            String chId = space.voiceChannelId().get();
            voiceCh = guild.getVoiceChannelById(chId);
            if (voiceCh == null) {
                OperationOutcome outcome = OperationOutcome.permanentFailure(
                        "Required voice channel " + chId + " not found in Discord for town " + req.townUuid());
                onOperationFailed(new GuildOperation.RestoreSpace(req), outcome);
                return outcome;
            }
        }

        // 1. Active category
        int channelsNeeded = (space.textChannelId().isPresent() ? 1 : 0)
                + (space.voiceChannelId().isPresent() ? 1 : 0);
        Category category = ensureCategory(space, config.structure().categoryName(), channelsNeeded);
        if (category == null) {
            throw new IllegalStateException("Could not obtain active category for town " + req.townName());
        }
        space = withCategory(space, category.getId());
        spaces.save(space);

        // 2. Create new role (the archived one was deleted) and persist immediately
        Role role = ensureRole(space, applyTemplate(config.roles().townRoleName(), req.townName()));
        if (role == null) {
            throw new IllegalStateException("Could not create town role for town " + req.townName());
        }
        space = withRole(space, role.getId());
        spaces.save(space);

        // 3. Move channels back and apply permissions (with bot permissions)
        if (textCh != null) {
            textCh.getManager().setParent(category).complete();
            applyChannelPermissions(textCh, role);
        }

        if (voiceCh != null) {
            voiceCh.getManager().setParent(category).complete();
            applyVoiceChannelPermissions(voiceCh, role);
        }

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

        // Obtain the set of managed role IDs once
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
     * Reclaims already created channels across all managed categories (F1).
     * Proves ownership via town role permission overrides and claimed channel sets (F2).
     */
    private TownSpace reclaimExistingChannels(TownSpace space, Role townRole, String townName) {
        // The templates are read inside each branch: a disabled channel type has
        // no configured name, and reading it here would fail before we know
        // whether we even need it.

        UUID thisTown = space.townUuid();
        Set<String> claimedChannelIds = spaces.findAll().stream()
                .filter(s -> !s.townUuid().equals(thisTown))
                .flatMap(s -> java.util.stream.Stream.of(s.textChannelId(), s.voiceChannelId()))
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());

        List<Category> managedCategories = getManagedCategories();

        // 1. Reclaim unrecorded text channel
        if (config.structure().createTextChannel() && space.textChannelId().isEmpty()) {
            String expectedTextName = applyTemplate(config.structure().textChannelName(), townName);
            List<TextChannel> candidates = new ArrayList<>();
            for (Category cat : managedCategories) {
                if (cat.getTextChannels() == null) continue;
                for (TextChannel ch : cat.getTextChannels()) {
                    if (ch.getName().equalsIgnoreCase(expectedTextName)) {
                        if (claimedChannelIds.contains(ch.getId())) {
                            continue;
                        }
                        if (ch.getPermissionOverride(townRole) != null) {
                            candidates.add(ch);
                        } else {
                            throw new IllegalStateException("Text channel name collision: channel '"
                                    + expectedTextName + "' exists in category '" + cat.getName()
                                    + "' but ownership by role " + townRole.getId() + " cannot be proven");
                        }
                    }
                }
            }

            if (candidates.size() > 1) {
                throw new IllegalStateException("Ambiguous text channel: multiple channels match name '"
                        + expectedTextName + "' with town role permissions");
            } else if (candidates.size() == 1) {
                TextChannel reclaimed = candidates.getFirst();
                space = withTextChannel(space, reclaimed.getId());
                if (reclaimed.getParentCategory() != null) {
                    space = withCategory(space, reclaimed.getParentCategory().getId());
                }
                spaces.save(space);
                logger.info("[Executor] Reclaimed unrecorded text channel " + reclaimed.getId()
                        + " for town '" + townName + "'");
            }
        }

        // 2. Reclaim unrecorded voice channel
        if (config.structure().createVoiceChannel() && space.voiceChannelId().isEmpty()) {
            String expectedVoiceName = applyTemplate(config.structure().voiceChannelName(), townName);
            List<VoiceChannel> candidates = new ArrayList<>();
            for (Category cat : managedCategories) {
                if (cat.getVoiceChannels() == null) continue;
                for (VoiceChannel ch : cat.getVoiceChannels()) {
                    if (ch.getName().equalsIgnoreCase(expectedVoiceName)) {
                        if (claimedChannelIds.contains(ch.getId())) {
                            continue;
                        }
                        if (ch.getPermissionOverride(townRole) != null) {
                            candidates.add(ch);
                        } else {
                            throw new IllegalStateException("Voice channel name collision: channel '"
                                    + expectedVoiceName + "' exists in category '" + cat.getName()
                                    + "' but ownership by role " + townRole.getId() + " cannot be proven");
                        }
                    }
                }
            }

            if (candidates.size() > 1) {
                throw new IllegalStateException("Ambiguous voice channel: multiple channels match name '"
                        + expectedVoiceName + "' with town role permissions");
            } else if (candidates.size() == 1) {
                VoiceChannel reclaimed = candidates.getFirst();
                space = withVoiceChannel(space, reclaimed.getId());
                if (reclaimed.getParentCategory() != null) {
                    space = withCategory(space, reclaimed.getParentCategory().getId());
                }
                spaces.save(space);
                logger.info("[Executor] Reclaimed unrecorded voice channel " + reclaimed.getId()
                        + " for town '" + townName + "'");
            }
        }

        return space;
    }

    /** Returns all categories managed by the plugin (active categories and archive categories). */
    private List<Category> getManagedCategories() {
        String baseName = config.structure().categoryName();
        String archiveName = config.structure().archiveCategoryName();
        Set<Category> result = new LinkedHashSet<>();

        if (guild.getCategories() != null) {
            for (Category cat : guild.getCategories()) {
                if (isManagedCategory(cat, baseName, archiveName)) {
                    result.add(cat);
                }
            }
        }
        if (baseName != null) {
            List<Category> byBase = guild.getCategoriesByName(baseName, true);
            if (byBase != null) {
                result.addAll(byBase);
            }
        }
        if (archiveName != null) {
            List<Category> byArchive = guild.getCategoriesByName(archiveName, true);
            if (byArchive != null) {
                result.addAll(byArchive);
            }
        }
        return new ArrayList<>(result);
    }

    private boolean isManagedCategory(Category cat, String baseName, String archiveName) {
        if (cat == null || cat.getName() == null) return false;
        String name = cat.getName();
        return (baseName != null && (name.equalsIgnoreCase(baseName)
                || name.toLowerCase().startsWith(baseName.toLowerCase() + " ")))
                || (archiveName != null && (name.equalsIgnoreCase(archiveName)
                || name.toLowerCase().startsWith(archiveName.toLowerCase() + " ")));
    }

    /**
     * Ensures that the category exists and has available capacity. Idempotent.
     * Discord limits categories to 50 channels each. If the category fills up, numbered
     * categories are created (Communities, Communities 2, Communities 3...).
     */
    private Category ensureCategory(TownSpace space, String baseName, int channelsNeeded) {
        if (space.categoryId().isPresent()) {
            Category cat = guild.getCategoryById(space.categoryId().get());
            if (cat != null && (cat.getChannels() == null || cat.getChannels().size() + channelsNeeded <= 50)) {
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

    /**
     * Ensures that the town role exists. Idempotent.
     * Identity is a persisted ID, never a name (F2). If there is no stored ID and a role
     * with matching name already exists in Discord, ownership cannot be proven and
     * adopting it would grant strangers access to private channels: it must fail visibly (P9).
     */
    private Role ensureRole(TownSpace space, String name) {
        if (space.roleId().isPresent()) {
            Role role = guild.getRoleById(space.roleId().get());
            if (role != null) {
                return role;
            }
        }
        // If no stored ID (or stored role was deleted), check for collisions.
        // An existing unmanaged role cannot be adopted by name.
        List<Role> existing = guild.getRolesByName(name, true);
        if (!existing.isEmpty()) {
            throw new IllegalStateException("Role name collision: a role named '" + name
                    + "' already exists in Discord but is not owned by town space " + space.townUuid()
                    + ". Identity must be a persisted ID; refusing to adopt unowned role.");
        }
        var action = guild.createRole();
        if (action == null) {
            throw new IllegalStateException("Could not create role '" + name + "' in Discord");
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
        Role role = action.complete();
        if (role == null) {
            throw new IllegalStateException("Failed to complete creation of role '" + name + "' in Discord");
        }
        return role;
    }

    /**
     * Creates a text channel in the given category with spec 3.3 permissions.
     * Rejects creation if an unowned channel with that name already exists in the category (F2).
     */
    private TextChannel createTextChannel(Category category, Role townRole, String name) {
        if (category == null) {
            throw new IllegalStateException("Category cannot be null when creating text channel '" + name + "'");
        }
        if (category.getTextChannels() != null) {
            for (TextChannel existing : category.getTextChannels()) {
                if (existing.getName().equalsIgnoreCase(name)) {
                    throw new IllegalStateException("Text channel name collision: channel '" + name
                            + "' already exists in category '" + category.getName()
                            + "' without proven ownership");
                }
            }
        }
        var action = category.createTextChannel(name);
        if (action == null) {
            throw new IllegalStateException("Could not create text channel '" + name + "' in Discord");
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
        TextChannel created = action.complete();
        if (created == null) {
            throw new IllegalStateException("Failed to complete creation of text channel '" + name + "'");
        }
        return created;
    }

    /**
     * Creates a voice channel in the given category with spec 3.3 permissions.
     * Rejects creation if an unowned channel with that name already exists in the category (F2).
     */
    private VoiceChannel createVoiceChannel(Category category, Role townRole, String name) {
        if (category == null) {
            throw new IllegalStateException("Category cannot be null when creating voice channel '" + name + "'");
        }
        if (category.getVoiceChannels() != null) {
            for (VoiceChannel existing : category.getVoiceChannels()) {
                if (existing.getName().equalsIgnoreCase(name)) {
                    throw new IllegalStateException("Voice channel name collision: channel '" + name
                            + "' already exists in category '" + category.getName()
                            + "' without proven ownership");
                }
            }
        }
        var action = category.createVoiceChannel(name);
        if (action == null) {
            throw new IllegalStateException("Could not create voice channel '" + name + "' in Discord");
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
        VoiceChannel created = action.complete();
        if (created == null) {
            throw new IllegalStateException("Failed to complete creation of voice channel '" + name + "'");
        }
        return created;
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
