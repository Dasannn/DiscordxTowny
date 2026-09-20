package com.discordtowny.discord;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.link.LinkService;
import com.discordtowny.model.AccountLink;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LinkSlashCommands}.
 */
class LinkSlashCommandsTest {

    private LinkService linkService;
    private PluginConfig config;
    private Messages messages;
    private LinkSlashCommands commands;

    private SlashCommandInteractionEvent event;
    private ReplyCallbackAction replyAction;
    private InteractionHook hook;
    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        linkService = mock(LinkService.class);
        messages = mock(Messages.class);

        PluginConfig.Linking linkingConfig = new PluginConfig.Linking(
                Duration.ofMinutes(10), 3, Duration.ofMinutes(15), true);

        config = new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty()),
                new PluginConfig.Database(PluginConfig.Database.Type.SQLITE, "localhost", 3306, "db", "", "", "dt_", 1, 1, Duration.ofSeconds(5)),
                new PluginConfig.Structure("Cat", "Arch", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Alcalde", "{town}", Optional.empty(), false),
                new PluginConfig.Limits(200, 2, Duration.ofSeconds(60)),
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                new PluginConfig.Sync(Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPAIR, 20, Duration.ofSeconds(5)),
                linkingConfig,
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of(
                        new PluginConfig.DiscordCommand("link", true, true),
                        new PluginConfig.DiscordCommand("unlink", true, true)
                ))
        );

        commands = new LinkSlashCommands(linkService, config, messages);

        event = mock(SlashCommandInteractionEvent.class);
        replyAction = mock(ReplyCallbackAction.class);
        hook = mock(InteractionHook.class);
        user = mock(User.class);

        when(event.getUser()).thenReturn(user);
        when(user.getId()).thenReturn("123456789012345678");

        when(event.deferReply(anyBoolean())).thenReturn(replyAction);
        doAnswer(invocation -> {
            Consumer<InteractionHook> callback = invocation.getArgument(0);
            callback.accept(hook);
            return null;
        }).when(replyAction).queue(any());

        WebhookMessageEditAction editAction = mock(WebhookMessageEditAction.class);
        when(hook.editOriginal(anyString())).thenReturn(editAction);
    }

    @Test
    void getCommandDataDefinesCorrectCommands() {
        List<SlashCommandData> data = LinkSlashCommands.getCommandData();
        assertEquals(2, data.size());

        SlashCommandData linkData = data.stream().filter(d -> d.getName().equals("link")).findFirst().orElseThrow();
        assertEquals("link", linkData.getName());
        assertEquals(1, linkData.getOptions().size());
        assertEquals("code", linkData.getOptions().getFirst().getName());
        assertEquals(OptionType.STRING, linkData.getOptions().getFirst().getType());
        assertTrue(linkData.getOptions().getFirst().isRequired());

        SlashCommandData unlinkData = data.stream().filter(d -> d.getName().equals("unlink")).findFirst().orElseThrow();
        assertEquals("unlink", unlinkData.getName());
        assertTrue(unlinkData.getOptions().isEmpty());
    }

    @Test
    void handleLinkSuccessfulRedemption() {
        when(event.getName()).thenReturn("link");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("ABC234");
        when(event.getOption("codigo")).thenReturn(opt);

        when(linkService.redeem("ABC234", "123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(LinkService.LinkResult.SUCCESS));
        when(messages.plain(eq("linking.link-success"), any())).thenReturn("Cuenta vinculada con exito");

        commands.onSlashCommandInteraction(event);

        verify(event).deferReply(true); // Ephemeral
        verify(hook).editOriginal("Cuenta vinculada con exito");
    }

    @Test
    void handleLinkInvalidCode() {
        when(event.getName()).thenReturn("link");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("MAL123");
        when(event.getOption("codigo")).thenReturn(opt);

        when(linkService.redeem("MAL123", "123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(LinkService.LinkResult.CODE_INVALID));
        when(messages.plain(eq("linking.code-invalid"), any())).thenReturn("Codigo invalido");

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("Codigo invalido");
    }

    @Test
    void handleLinkTooManyAttempts() {
        when(event.getName()).thenReturn("link");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("MAL123");
        when(event.getOption("codigo")).thenReturn(opt);

        when(linkService.redeem("MAL123", "123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(LinkService.LinkResult.TOO_MANY_ATTEMPTS));
        when(messages.plain(eq("linking.too-many-attempts"), any())).thenReturn("Demasiados intentos");

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("Demasiados intentos");
    }

    @Test
    void handleUnlinkLinkedUser() {
        when(event.getName()).thenReturn("unlink");
        UUID uuid = UUID.randomUUID();
        AccountLink link = new AccountLink(uuid, "123456789012345678", Instant.now(), "Jugador");

        when(linkService.findByDiscordId("123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(link)));
        when(linkService.unlink(uuid, "123456789012345678", link.linkedAt())).thenReturn(CompletableFuture.completedFuture(true));
        when(messages.plain(eq("linking.unlink-success"), any())).thenReturn("Vinculo eliminado");

        commands.onSlashCommandInteraction(event);

        verify(event).deferReply(true);
        verify(linkService).unlink(uuid, "123456789012345678", link.linkedAt());
        verify(hook).editOriginal("Vinculo eliminado");
    }

    @Test
    void handleUnlinkUnlinkedUser() {
        when(event.getName()).thenReturn("unlink");

        when(linkService.findByDiscordId("123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(messages.plain(eq("linking.not-linked"), any())).thenReturn("No tienes cuenta vinculada");

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("No tienes cuenta vinculada");
    }

    @Test
    void handleLinkResponseAlwaysEphemeralEvenIfConfigurationSaysFalse() {
        // Configuration with ephemeral = false for link
        PluginConfig nonEphemeralConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of(
                        new PluginConfig.DiscordCommand("link", true, false),
                        new PluginConfig.DiscordCommand("unlink", true, false)
                ))
        );
        LinkSlashCommands nonEphemeralCommands = new LinkSlashCommands(linkService, nonEphemeralConfig, messages);

        when(event.getName()).thenReturn("link");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("ABC234");
        when(event.getOption("codigo")).thenReturn(opt);

        when(linkService.redeem("ABC234", "123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(LinkService.LinkResult.SUCCESS));
        when(messages.plain(eq("linking.link-success"), any())).thenReturn("Cuenta vinculada con exito");

        nonEphemeralCommands.onSlashCommandInteraction(event);

        // Despite ephemeral=false in config, deferReply MUST be true
        verify(event).deferReply(true);
    }

    @Test
    void disabledCommandIsRejectedWithoutInvokingService() {
        PluginConfig disabledConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of(
                        new PluginConfig.DiscordCommand("link", false, true),
                        new PluginConfig.DiscordCommand("unlink", true, true)
                ))
        );
        LinkSlashCommands disabledCommands = new LinkSlashCommands(linkService, disabledConfig, messages);

        when(event.getName()).thenReturn("link");
        ReplyCallbackAction directReply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(directReply);
        when(directReply.setEphemeral(true)).thenReturn(directReply);
        when(messages.plain(eq("general.no-permission"), any())).thenReturn("No tienes permiso");

        disabledCommands.onSlashCommandInteraction(event);

        verify(event).reply("No tienes permiso");
        verify(directReply).setEphemeral(true);
        verify(linkService, never()).redeem(any(), any());
    }

    @Test
    void perUserCooldownRejectsRepeatedRequestWithoutInvokingService() {
        when(event.getName()).thenReturn("link");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("ABC234");
        when(event.getOption("codigo")).thenReturn(opt);

        when(linkService.redeem("ABC234", "123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(LinkService.LinkResult.SUCCESS));
        when(messages.plain(eq("linking.link-success"), any())).thenReturn("Cuenta vinculada");
        when(messages.plain(eq("space.cooldown"), any())).thenReturn("Espera cooldown");

        ReplyCallbackAction cooldownReply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(cooldownReply);
        when(cooldownReply.setEphemeral(true)).thenReturn(cooldownReply);

        // First attempt within cooldown: passes and is processed
        commands.onSlashCommandInteraction(event);
        verify(linkService, times(1)).redeem("ABC234", "123456789012345678");

        // Second immediate attempt by same user: blocked by cooldown without invoking service
        commands.onSlashCommandInteraction(event);
        verify(linkService, times(1)).redeem(any(), any());
        verify(event).reply("Espera cooldown");
    }

    @Test
    void getCommandDataFiltersDisabledCommandsAccordingToConfiguration() {
        PluginConfig disabledConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of(
                        new PluginConfig.DiscordCommand("link", false, true),
                        new PluginConfig.DiscordCommand("unlink", true, true)
                ))
        );

        List<SlashCommandData> filtered = LinkSlashCommands.getCommandData(disabledConfig);
        assertEquals(1, filtered.size());
        assertEquals("unlink", filtered.getFirst().getName());
    }

    @Test
    void handleLinkWithCodeOptionNameDirectly() {
        when(event.getName()).thenReturn("link");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("XYZ789");
        when(event.getOption("code")).thenReturn(opt);

        when(linkService.redeem("XYZ789", "123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(LinkService.LinkResult.SUCCESS));
        when(messages.plain(eq("linking.link-success"), any())).thenReturn("Linked successfully");

        commands.onSlashCommandInteraction(event);

        verify(event).deferReply(true);
        verify(hook).editOriginal("Linked successfully");
    }

    @Test
    void emptyLinkChannelSettingAllowsLinkInAnyChannel() {
        when(event.getName()).thenReturn("link");
        when(event.getChannelId()).thenReturn("any-channel-999");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("CODE12");
        when(event.getOption("code")).thenReturn(opt);

        when(linkService.redeem("CODE12", "123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(LinkService.LinkResult.SUCCESS));
        when(messages.plain(eq("linking.link-success"), any())).thenReturn("Linked successfully");

        commands.onSlashCommandInteraction(event);

        verify(event).deferReply(true);
        verify(linkService).redeem("CODE12", "123456789012345678");
        verify(hook).editOriginal("Linked successfully");
        verify(event, never()).reply(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void linkInWrongChannelRefusedEphemerallyWithChannelNameAndCodeRemainsRedeemableInRightChannel() {
        PluginConfig restrictedConfig = new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty(), Optional.of("111222333444555666")),
                config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(), config.commands()
        );
        LinkSlashCommands restrictedCommands = new LinkSlashCommands(linkService, restrictedConfig, messages);

        // Step 1: User runs /link in the wrong channel
        when(event.getName()).thenReturn("link");
        when(event.getChannelId()).thenReturn("999888777666555444");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("SECRET");
        when(event.getOption("code")).thenReturn(opt);

        ReplyCallbackAction wrongReplyAction = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(wrongReplyAction);
        when(wrongReplyAction.setEphemeral(true)).thenReturn(wrongReplyAction);

        ArgumentCaptor<Map<String, String>> placeholdersCaptor = ArgumentCaptor.forClass(Map.class);
        when(messages.plain(eq("linking.wrong-channel"), placeholdersCaptor.capture()))
                .thenReturn("Only allowed in <#111222333444555666>");

        restrictedCommands.onSlashCommandInteraction(event);

        // Verify refusal: answered privately and named the channel
        verify(event).reply("Only allowed in <#111222333444555666>");
        verify(wrongReplyAction).setEphemeral(true);
        verify(wrongReplyAction).queue();
        assertEquals("<#111222333444555666>", placeholdersCaptor.getValue().get("channel"));

        // Verify code was NOT redeemed on the refusal (remains valid)
        verify(linkService, never()).redeem(any(), any());
        verify(event, never()).deferReply(anyBoolean());

        // Step 2: Same user with same code tries again in the right channel
        SlashCommandInteractionEvent rightEvent = mock(SlashCommandInteractionEvent.class);
        when(rightEvent.getUser()).thenReturn(user);
        when(rightEvent.getName()).thenReturn("link");
        when(rightEvent.getChannelId()).thenReturn("111222333444555666");
        when(rightEvent.getOption("code")).thenReturn(opt);

        ReplyCallbackAction rightDeferAction = mock(ReplyCallbackAction.class);
        when(rightEvent.deferReply(true)).thenReturn(rightDeferAction);
        InteractionHook rightHook = mock(InteractionHook.class);
        doAnswer(inv -> {
            Consumer<InteractionHook> callback = inv.getArgument(0);
            callback.accept(rightHook);
            return null;
        }).when(rightDeferAction).queue(any());
        WebhookMessageEditAction editAction = mock(WebhookMessageEditAction.class);
        when(rightHook.editOriginal(anyString())).thenReturn(editAction);

        when(linkService.redeem("SECRET", "123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(LinkService.LinkResult.SUCCESS));
        when(messages.plain(eq("linking.link-success"), any())).thenReturn("Account linked successfully");

        restrictedCommands.onSlashCommandInteraction(rightEvent);

        // Verify redemption: not blocked by cooldown from previous refusal, and successfully redeemed
        verify(rightEvent, never()).reply(anyString());
        verify(rightEvent).deferReply(true);
        verify(linkService, times(1)).redeem("SECRET", "123456789012345678");
        verify(rightHook).editOriginal("Account linked successfully");
    }

    @Test
    @SuppressWarnings("unchecked")
    void unlinkInWrongChannelRefusedEphemerallyAndWorksInRightChannel() {
        PluginConfig restrictedConfig = new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty(), Optional.of("111222333444555666")),
                config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(), config.commands()
        );
        LinkSlashCommands restrictedCommands = new LinkSlashCommands(linkService, restrictedConfig, messages);

        // Step 1: /unlink in wrong channel
        when(event.getName()).thenReturn("unlink");
        when(event.getChannelId()).thenReturn("999888777666555444");

        ReplyCallbackAction wrongReplyAction = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(wrongReplyAction);
        when(wrongReplyAction.setEphemeral(true)).thenReturn(wrongReplyAction);

        ArgumentCaptor<Map<String, String>> placeholdersCaptor = ArgumentCaptor.forClass(Map.class);
        when(messages.plain(eq("linking.wrong-channel"), placeholdersCaptor.capture())).thenReturn("Wrong channel");

        restrictedCommands.onSlashCommandInteraction(event);

        verify(event).reply("Wrong channel");
        verify(wrongReplyAction).setEphemeral(true);
        verify(wrongReplyAction).queue();
        assertEquals("<#111222333444555666>", placeholdersCaptor.getValue().get("channel"));
        verify(linkService, never()).findByDiscordId(any());
        verify(linkService, never()).unlink(any(), any(), any());

        // Step 2: /unlink in right channel
        SlashCommandInteractionEvent rightEvent = mock(SlashCommandInteractionEvent.class);
        when(rightEvent.getUser()).thenReturn(user);
        when(rightEvent.getName()).thenReturn("unlink");
        when(rightEvent.getChannelId()).thenReturn("111222333444555666");

        ReplyCallbackAction rightDeferAction = mock(ReplyCallbackAction.class);
        when(rightEvent.deferReply(anyBoolean())).thenReturn(rightDeferAction);
        InteractionHook rightHook = mock(InteractionHook.class);
        doAnswer(inv -> {
            Consumer<InteractionHook> callback = inv.getArgument(0);
            callback.accept(rightHook);
            return null;
        }).when(rightDeferAction).queue(any());
        WebhookMessageEditAction editAction = mock(WebhookMessageEditAction.class);
        when(rightHook.editOriginal(anyString())).thenReturn(editAction);

        AccountLink link = new AccountLink(UUID.randomUUID(), "123456789012345678", Instant.now(), "Jugador");
        when(linkService.findByDiscordId("123456789012345678")).thenReturn(CompletableFuture.completedFuture(Optional.of(link)));
        when(linkService.unlink(link.uuid(), "123456789012345678", link.linkedAt())).thenReturn(CompletableFuture.completedFuture(true));
        when(messages.plain(eq("linking.unlink-success"), any())).thenReturn("Unlink success");

        restrictedCommands.onSlashCommandInteraction(rightEvent);

        verify(linkService).findByDiscordId("123456789012345678");
        verify(linkService).unlink(link.uuid(), "123456789012345678", link.linkedAt());
        verify(rightHook).editOriginal("Unlink success");
    }

    @Test
    void configuredChannelNotFoundWarnsOperatorOnceAndMaintainsRestriction() {
        List<String> warnings = new ArrayList<>();
        PluginConfig restrictedConfig = new PluginConfig(
                new PluginConfig.Discord("token", "guild-123", Optional.empty(), Optional.of("111222333444555666")),
                config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(), config.commands()
        );
        LinkSlashCommands restrictedCommands = new LinkSlashCommands(linkService, restrictedConfig, messages, warnings::add);

        Guild guild = mock(Guild.class);
        when(guild.getName()).thenReturn("Test Guild");
        when(guild.getId()).thenReturn("guild-123");
        when(guild.getGuildChannelById("111222333444555666")).thenReturn(null);

        when(event.getGuild()).thenReturn(guild);
        when(event.getName()).thenReturn("link");
        when(event.getChannelId()).thenReturn("999888777666555444");
        ReplyCallbackAction wrongReplyAction = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(wrongReplyAction);
        when(wrongReplyAction.setEphemeral(true)).thenReturn(wrongReplyAction);
        when(messages.plain(eq("linking.wrong-channel"), any())).thenReturn("Wrong channel");

        // First invocation: warns operator once
        restrictedCommands.onSlashCommandInteraction(event);

        assertEquals(1, warnings.size());
        String warningMsg = warnings.getFirst();
        assertTrue(warningMsg.contains("discord.link-channel-id"), "Must name the setting key");
        assertTrue(warningMsg.contains("111222333444555666"), "Must name the configured ID");
        assertTrue(warningMsg.contains("not found in guild"), "Must state what was observed without asserting deletion");
        assertFalse(warningMsg.toLowerCase().contains("deleted"), "Must not assert channel was deleted");

        // Guard is NOT silently disabled: refusal still sent
        verify(event).reply("Wrong channel");
        verify(wrongReplyAction).queue();
        verify(linkService, never()).redeem(any(), any());

        // Second invocation: does not warn again
        restrictedCommands.onSlashCommandInteraction(event);
        assertEquals(1, warnings.size(), "Must warn operator only once");
    }

    @Test
    void configuredChannelInaccessibleWarnsOperatorOnceAndMaintainsRestriction() {
        List<String> warnings = new ArrayList<>();
        PluginConfig restrictedConfig = new PluginConfig(
                new PluginConfig.Discord("token", "guild-123", Optional.empty(), Optional.of("111222333444555666")),
                config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(), config.commands()
        );
        LinkSlashCommands restrictedCommands = new LinkSlashCommands(linkService, restrictedConfig, messages, warnings::add);

        Guild guild = mock(Guild.class);
        when(guild.getName()).thenReturn("Test Guild");
        when(guild.getId()).thenReturn("guild-123");
        GuildChannel channel = mock(GuildChannel.class);
        when(guild.getGuildChannelById("111222333444555666")).thenReturn(channel);

        SelfMember selfMember = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(selfMember);
        when(selfMember.hasAccess(channel)).thenReturn(false);

        when(event.getGuild()).thenReturn(guild);
        when(event.getName()).thenReturn("unlink");
        when(event.getChannelId()).thenReturn("999888777666555444");
        ReplyCallbackAction wrongReplyAction = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(wrongReplyAction);
        when(wrongReplyAction.setEphemeral(true)).thenReturn(wrongReplyAction);
        when(messages.plain(eq("linking.wrong-channel"), any())).thenReturn("Wrong channel");

        restrictedCommands.onSlashCommandInteraction(event);

        assertEquals(1, warnings.size());
        String warningMsg = warnings.getFirst();
        assertTrue(warningMsg.contains("discord.link-channel-id"), "Must name the setting key");
        assertTrue(warningMsg.contains("111222333444555666"), "Must name the configured ID");
        assertTrue(warningMsg.contains("bot lacks access"), "Must identify access problem");

        // Restriction is still enforced
        verify(event).reply("Wrong channel");
        verify(wrongReplyAction).queue();
        verify(linkService, never()).findByDiscordId(any());
    }

    @Test
    void configuredChannelMissingMessageSendPermissionWarnsOperator() {
        List<String> warnings = new ArrayList<>();
        PluginConfig restrictedConfig = new PluginConfig(
                new PluginConfig.Discord("token", "guild-123", Optional.empty(), Optional.of("111222333444555666")),
                config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(), config.commands()
        );
        LinkSlashCommands restrictedCommands = new LinkSlashCommands(linkService, restrictedConfig, messages, warnings::add);

        Guild guild = mock(Guild.class);
        when(guild.getName()).thenReturn("Test Guild");
        when(guild.getId()).thenReturn("guild-123");
        GuildChannel channel = mock(GuildChannel.class);
        when(guild.getGuildChannelById("111222333444555666")).thenReturn(channel);

        SelfMember selfMember = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(selfMember);
        when(selfMember.hasAccess(channel)).thenReturn(true);
        when(selfMember.hasPermission(channel, Permission.MESSAGE_SEND)).thenReturn(false);

        when(event.getGuild()).thenReturn(guild);
        when(event.getName()).thenReturn("link");
        when(event.getChannelId()).thenReturn("999888777666555444");
        ReplyCallbackAction wrongReplyAction = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(wrongReplyAction);
        when(wrongReplyAction.setEphemeral(true)).thenReturn(wrongReplyAction);
        when(messages.plain(eq("linking.wrong-channel"), any())).thenReturn("Wrong channel");

        restrictedCommands.onSlashCommandInteraction(event);

        assertEquals(1, warnings.size());
        String warningMsg = warnings.getFirst();
        assertTrue(warningMsg.contains("discord.link-channel-id"), "Must name the setting key");
        assertTrue(warningMsg.contains("111222333444555666"), "Must name the configured ID");
        assertTrue(warningMsg.contains("MESSAGE_SEND"), "Must identify missing MESSAGE_SEND permission");

        // Restriction is still enforced
        verify(event).reply("Wrong channel");
        verify(wrongReplyAction).queue();
    }
}
