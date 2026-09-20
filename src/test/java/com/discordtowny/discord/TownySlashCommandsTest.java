package com.discordtowny.discord;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.link.LinkService;
import com.discordtowny.model.AccountLink;
import com.discordtowny.model.ResidentSnapshot;
import com.discordtowny.model.TownSnapshot;
import com.discordtowny.towny.TownyFacade;
import com.discordtowny.towny.TownyReadException;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TownySlashCommands}.
 *
 * <p>Verifies slash commands: /town, /mytown, /res, /residents, /townlist, /help.
 * Covers non-existent entities, failed Towny reads, unlinked authors, disabled commands,
 * cooldowns, pagination, and main thread hopping.
 */
class TownySlashCommandsTest {

    private TownyFacade townyFacade;
    private LinkService linkService;
    private Messages messages;
    private PluginConfig config;
    private Executor trackingExecutor;
    private Executor asyncExecutor;
    private int mainThreadHopCount;
    private Clock fixedClock;
    private TownySlashCommands commands;

    private SlashCommandInteractionEvent event;
    private ReplyCallbackAction replyAction;
    private InteractionHook hook;
    private User user;
    @SuppressWarnings("rawtypes")
    private WebhookMessageEditAction editAction;

    private final String discordUserId = "123456789012345678";
    private final UUID playerUuid = UUID.randomUUID();
    private final UUID townUuid = UUID.randomUUID();
    private final UUID mayorUuid = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        townyFacade = mock(TownyFacade.class);
        linkService = mock(LinkService.class);
        messages = mock(Messages.class);

        mainThreadHopCount = 0;
        trackingExecutor = runnable -> {
            mainThreadHopCount++;
            runnable.run();
        };
        asyncExecutor = Runnable::run;

        fixedClock = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneId.of("UTC"));

        config = new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty()),
                new PluginConfig.Database(PluginConfig.Database.Type.SQLITE, "localhost", 3306, "db", "", "", "dt_", 1, 1, Duration.ofSeconds(5)),
                new PluginConfig.Structure("Communities", "Archive", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Mayor", "{town}", Optional.empty(), false),
                new PluginConfig.Limits(200, 2, Duration.ofSeconds(60)),
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                new PluginConfig.Sync(Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPAIR, 20, Duration.ofSeconds(5)),
                new PluginConfig.Linking(Duration.ofMinutes(10), 5, Duration.ofMinutes(15), true),
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of(
                        new PluginConfig.DiscordCommand("town", true, false),
                        new PluginConfig.DiscordCommand("mytown", true, true),
                        new PluginConfig.DiscordCommand("res", true, false),
                        new PluginConfig.DiscordCommand("residents", true, false),
                        new PluginConfig.DiscordCommand("townlist", true, false),
                        new PluginConfig.DiscordCommand("help", true, true)
                ))
        );

        commands = new TownySlashCommands(townyFacade, linkService, config, messages, trackingExecutor, asyncExecutor, fixedClock);

        event = mock(SlashCommandInteractionEvent.class);
        replyAction = mock(ReplyCallbackAction.class);
        hook = mock(InteractionHook.class);
        user = mock(User.class);
        editAction = mock(WebhookMessageEditAction.class);

        when(event.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(discordUserId);

        when(event.reply(anyString())).thenReturn(replyAction);
        when(replyAction.setEphemeral(anyBoolean())).thenReturn(replyAction);
        doAnswer(inv -> null).when(replyAction).queue();

        when(event.deferReply(anyBoolean())).thenReturn(replyAction);
        doAnswer(inv -> {
            Consumer<InteractionHook> callback = inv.getArgument(0);
            callback.accept(hook);
            return null;
        }).when(replyAction).queue(any());

        when(hook.editOriginal(anyString())).thenReturn(editAction);
        when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(editAction);
        when(hook.editOriginalEmbeds(any(MessageEmbed[].class))).thenReturn(editAction);
        when(editAction.setComponents(any(Collection.class))).thenReturn(editAction);
        when(editAction.setComponents(any(ActionRow.class))).thenReturn(editAction);
        when(editAction.setComponents(any(ActionRow[].class))).thenReturn(editAction);
        doAnswer(inv -> null).when(editAction).queue();

        // Default facade availability
        when(townyFacade.isAvailable()).thenReturn(true);

        // Default message responses: answer with keys so tests assert against keys, never literals
        // Every key answers with its own name, so an assertion names the key and
        // rewording a message in the YAML can never break a test.
        org.mockito.stubbing.Answer<String> byKey = inv -> {
            String key = inv.getArgument(0);
            Map<String, String> placeholders = inv.getArgument(1);
            if ("embed.page".equals(key) && placeholders != null) {
                return "embed.page:" + placeholders.get("current") + "/" + placeholders.get("total");
            }
            return key;
        };
        when(messages.plain(anyString(), anyMap())).thenAnswer(byKey);
        when(messages.label(anyString(), anyMap())).thenAnswer(byKey);
        when(messages.label(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- Command Data & Registration ---

    @Test
    void getCommandDataRegistersAllEnabledCommands() {
        List<SlashCommandData> data = TownySlashCommands.getCommandData(config);
        assertEquals(6, data.size());

        assertTrue(data.stream().anyMatch(c -> c.getName().equals("town")));
        assertTrue(data.stream().anyMatch(c -> c.getName().equals("mytown")));
        assertTrue(data.stream().anyMatch(c -> c.getName().equals("res")));
        assertTrue(data.stream().anyMatch(c -> c.getName().equals("residents")));
        assertTrue(data.stream().anyMatch(c -> c.getName().equals("townlist")));
        assertTrue(data.stream().anyMatch(c -> c.getName().equals("help")));
    }

    @Test
    void getCommandDataExcludesDisabledCommands() {
        PluginConfig disabledConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of(
                        new PluginConfig.DiscordCommand("town", false, false),
                        new PluginConfig.DiscordCommand("mytown", true, true),
                        new PluginConfig.DiscordCommand("res", false, false),
                        new PluginConfig.DiscordCommand("residents", true, false),
                        new PluginConfig.DiscordCommand("townlist", true, false),
                        new PluginConfig.DiscordCommand("help", true, true)
                ))
        );

        List<SlashCommandData> data = TownySlashCommands.getCommandData(disabledConfig);
        assertEquals(4, data.size());
        assertFalse(data.stream().anyMatch(c -> c.getName().equals("town")));
        assertFalse(data.stream().anyMatch(c -> c.getName().equals("res")));
    }

    // --- Disabled Command ---

    @Test
    void disabledCommandIsRejectedWithoutInvokingTowny() {
        PluginConfig disabledConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of(
                        new PluginConfig.DiscordCommand("town", false, false)
                ))
        );
        TownySlashCommands disabledCommands = new TownySlashCommands(townyFacade, linkService, disabledConfig, messages, trackingExecutor, fixedClock);

        when(event.getName()).thenReturn("town");

        disabledCommands.onSlashCommandInteraction(event);

        verify(event).reply("general.command-disabled");
        verify(replyAction).setEphemeral(true);
        verifyNoInteractions(townyFacade);
        verifyNoInteractions(linkService);
    }

    // --- Cooldown ---

    @Test
    void commandOnCooldownIsRejectedWithoutInvokingTowny() {
        when(event.getName()).thenReturn("town");
        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));

        TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, List.of(playerUuid), false, Optional.of("Empire"), 10, 100.0, 1000L);
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.of(town));

        // First call succeeds
        commands.onSlashCommandInteraction(event);
        assertEquals(1, mainThreadHopCount);

        // Second call with same user immediately after
        commands.onSlashCommandInteraction(event);

        // Rejected on cooldown
        verify(event).reply(contains("general.cooldown"));
        verify(replyAction).setEphemeral(true);
        // Towny not invoked a second time
        assertEquals(1, mainThreadHopCount);
    }

    // --- Selective Linking (Spec 5.1) ---

    @Test
    void unlinkedAuthorExecutingTownCommandWithNameSucceedsWithoutTouchingLinkService() {
        when(event.getName()).thenReturn("town");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("Rome");
        when(event.getOption("name")).thenReturn(opt);

        TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, List.of(mayorUuid), false, Optional.empty(), 10, 100.0, 1000L);
        when(townyFacade.townByName("Rome")).thenReturn(Optional.of(town));
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "Caesar", Optional.of("Rome"), Optional.of(townUuid), true, true, 0, 0)));

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginalEmbeds(any(MessageEmbed.class));
        verifyNoInteractions(linkService);
    }

    @Test
    void unlinkedAuthorExecutingTownCommandWithoutArgIsRejectedWithExplanation() {
        when(event.getName()).thenReturn("town");
        when(linkService.findByDiscordId(discordUserId)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("linking.link-required");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verifyNoInteractions(townyFacade);
    }

    @Test
    void unlinkedAuthorExecutingMyTownCommandIsRejectedWithExplanation() {
        when(event.getName()).thenReturn("mytown");
        when(linkService.findByDiscordId(discordUserId)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("linking.link-required");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verifyNoInteractions(townyFacade);
    }

    @Test
    void unlinkedAuthorExecutingResCommandWithoutArgIsRejectedWithExplanation() {
        when(event.getName()).thenReturn("res");
        when(linkService.findByDiscordId(discordUserId)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("linking.link-required");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verifyNoInteractions(townyFacade);
    }

    @Test
    void unlinkedAuthorExecutingResCommandWithNameSucceedsWithoutTouchingLinkService() {
        when(event.getName()).thenReturn("res");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("Alice");
        when(event.getOption("resident")).thenReturn(opt);

        UUID aliceUuid = UUID.randomUUID();
        ResidentSnapshot res = new ResidentSnapshot(aliceUuid, "Alice", Optional.of("Rome"), Optional.of(townUuid), false, true, 0L, 75.25);
        when(townyFacade.residentByName("Alice")).thenReturn(Optional.of(res));

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginalEmbeds(any(MessageEmbed.class));
        verifyNoInteractions(linkService);
    }

    @Test
    void unlinkedAuthorExecutingResidentsCommandWithoutArgIsRejectedWithExplanation() {
        when(event.getName()).thenReturn("residents");
        when(linkService.findByDiscordId(discordUserId)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("linking.link-required");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verifyNoInteractions(townyFacade);
    }

    @Test
    void unlinkedAuthorExecutingResidentsCommandWithTownSucceedsWithoutTouchingLinkService() {
        when(event.getName()).thenReturn("residents");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("Rome");
        when(event.getOption("town")).thenReturn(opt);

        TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, List.of(mayorUuid), false, Optional.empty(), 10, 100.0, 1000L);
        when(townyFacade.townByName("Rome")).thenReturn(Optional.of(town));
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "Caesar", Optional.of("Rome"), Optional.of(townUuid), true, true, 0, 0)));

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginalEmbeds(any(MessageEmbed.class));
        verifyNoInteractions(linkService);
    }

    @Test
    void unlinkedAuthorExecutingTownlistCommandSucceedsWithoutTouchingLinkService() {
        when(event.getName()).thenReturn("townlist");

        TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, List.of(mayorUuid), false, Optional.empty(), 10, 100.0, 1000L);
        when(townyFacade.allTowns()).thenReturn(List.of(town));
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "Caesar", Optional.of("Rome"), Optional.of(townUuid), true, true, 0, 0)));

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginalEmbeds(any(MessageEmbed.class));
        verifyNoInteractions(linkService);
    }

    @Test
    void databaseOutageDoesNotAffectIdentityIndependentCommands() {
        when(linkService.findByDiscordId(anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Database down")));

        // /town Rome still works
        when(event.getName()).thenReturn("town");
        OptionMapping townOpt = mock(OptionMapping.class);
        when(townOpt.getAsString()).thenReturn("Rome");
        when(event.getOption("name")).thenReturn(townOpt);

        TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, List.of(mayorUuid), false, Optional.empty(), 10, 100.0, 1000L);
        when(townyFacade.townByName("Rome")).thenReturn(Optional.of(town));
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "Caesar", Optional.of("Rome"), Optional.of(townUuid), true, true, 0, 0)));

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginalEmbeds(any(MessageEmbed.class));
        verify(hook, never()).editOriginal("general.database-unavailable");
    }

    // --- Non-Existent Entities ---

    @Test
    void nonExistentTownRespondsWithClearMessageAndNeverAnEmptyEmbed() {
        when(event.getName()).thenReturn("town");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("Atlantis");
        when(event.getOption("name")).thenReturn(opt);

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));
        when(townyFacade.townByName("Atlantis")).thenReturn(Optional.empty());

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("general.town-not-found");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
    }

    @Test
    void nonExistentResidentRespondsWithClearMessageAndNeverAnEmptyEmbed() {
        when(event.getName()).thenReturn("res");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("Ghost");
        when(event.getOption("resident")).thenReturn(opt);

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));
        when(townyFacade.residentByName("Ghost")).thenReturn(Optional.empty());

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("general.resident-not-found");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
    }

    @Test
    void townCommandWithoutArgWhenNotInTownRespondsWithClearMessageAndNeverAnEmptyEmbed() {
        when(event.getName()).thenReturn("town");

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.empty());

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("general.not-in-town");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
    }

    @Test
    void myTownWhenNotInTownRespondsWithClearMessageAndNeverAnEmptyEmbed() {
        when(event.getName()).thenReturn("mytown");

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.empty());

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("general.not-in-town");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
    }

    @Test
    void residentsCommandForNonExistentTownRespondsWithClearMessageAndNeverAnEmptyEmbed() {
        when(event.getName()).thenReturn("residents");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("Atlantis");
        when(event.getOption("town")).thenReturn(opt);

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));
        when(townyFacade.townByName("Atlantis")).thenReturn(Optional.empty());

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("general.town-not-found");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
    }

    // --- Failed Towny Reads ---

    @Test
    void failedTownyReadRespondsWithErrorMessageAndNeverAnEmptyEmbed() {
        when(event.getName()).thenReturn("town");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("Rome");
        when(event.getOption("name")).thenReturn(opt);

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));
        when(townyFacade.townByName("Rome")).thenThrow(new TownyReadException("Towny crashed"));

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("general.towny-read-failed");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
    }

    @Test
    void failedTownyReadDueToUnavailableRespondsWithErrorMessageAndNeverAnEmptyEmbed() {
        when(event.getName()).thenReturn("town");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("Rome");
        when(event.getOption("name")).thenReturn(opt);

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));
        when(townyFacade.isAvailable()).thenReturn(false);

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("general.towny-read-failed");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
    }

    // --- Successful Card Embeds ---

    @Test
    void townCommandByNameDisplaysCompleteTownCardEmbed() {
        when(event.getName()).thenReturn("town");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("Rome");
        when(event.getOption("name")).thenReturn(opt);

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));

        TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, List.of(playerUuid, mayorUuid), false, Optional.of("Empire"), 24, 1500.50, 1600000000000L);
        ResidentSnapshot mayorRes = new ResidentSnapshot(mayorUuid, "Caesar", Optional.of("Rome"), Optional.of(townUuid), true, true, 0, 500.0);

        when(townyFacade.townByName("Rome")).thenReturn(Optional.of(town));
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(mayorRes));

        commands.onSlashCommandInteraction(event);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();

        assertEquals("embed.town: Rome", embed.getTitle());
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.mayor") && "Caesar".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.residents") && "2".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.nation") && "Empire".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.plots") && "24".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.bank") && "1500.50".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.ruined") && "embed.no".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.founded") && "<t:1600000000:D>".equals(f.getValue())));
    }

    @Test
    void myTownCommandDisplaysAuthorsTownCard() {
        when(event.getName()).thenReturn("mytown");

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));

        TownSnapshot town = new TownSnapshot(townUuid, "Sparta", mayorUuid, List.of(playerUuid), true, Optional.empty(), 5, 20.0, 0L);
        ResidentSnapshot mayorRes = new ResidentSnapshot(mayorUuid, "Leonidas", Optional.of("Sparta"), Optional.of(townUuid), true, false, 1500000000000L, 10.0);

        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.of(town));
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(mayorRes));

        commands.onSlashCommandInteraction(event);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();

        assertEquals("embed.town: Sparta [EMBED.RUINED]", embed.getTitle());
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.mayor") && "Leonidas".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.ruined") && "embed.yes".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.nation") && "embed.none".equals(f.getValue())));
    }

    @Test
    void resCommandDisplaysResidentCard() {
        when(event.getName()).thenReturn("res");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("Alice");
        when(event.getOption("resident")).thenReturn(opt);

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));

        UUID aliceUuid = UUID.randomUUID();
        ResidentSnapshot res = new ResidentSnapshot(aliceUuid, "Alice", Optional.of("Rome"), Optional.of(townUuid), false, true, 0L, 75.25);
        when(townyFacade.residentByName("Alice")).thenReturn(Optional.of(res));

        commands.onSlashCommandInteraction(event);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();

        assertEquals("embed.resident: Alice", embed.getTitle());
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.town") && "Rome".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.rank") && "embed.resident".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.status") && "embed.online".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.balance") && "75.25".equals(f.getValue())));
    }

    @Test
    void resCommandDisplaysMayorRankAndOfflineStatus() {
        when(event.getName()).thenReturn("res");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("Bob");
        when(event.getOption("resident")).thenReturn(opt);

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));

        UUID bobUuid = UUID.randomUUID();
        ResidentSnapshot res = new ResidentSnapshot(bobUuid, "Bob", Optional.empty(), Optional.empty(), true, false, 1500000000000L, 0.0);
        when(townyFacade.residentByName("Bob")).thenReturn(Optional.of(res));

        commands.onSlashCommandInteraction(event);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();

        assertEquals("embed.resident: Bob", embed.getTitle());
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.town") && "embed.none".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.rank") && "embed.mayor".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.status") && "embed.offline".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("embed.last-online")));
    }

    // --- Pagination ---

    @Test
    void townlistLongEnoughToPaginateSplitsIntoPagesAndShowsFirstPage() {
        when(event.getName()).thenReturn("townlist");
        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));

        List<TownSnapshot> allTowns = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            String name = String.format("Town%02d", i);
            UUID id = UUID.randomUUID();
            allTowns.add(new TownSnapshot(id, name, mayorUuid, List.of(mayorUuid), false, Optional.empty(), i, i * 10.0, 0L));
        }
        when(townyFacade.allTowns()).thenReturn(allTowns);
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "MayorBob", Optional.empty(), Optional.empty(), true, true, 0, 0)));

        commands.onSlashCommandInteraction(event);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();

        assertEquals("embed.town-list-title", embed.getTitle());
        assertTrue(embed.getFooter().getText().contains("embed.page:1/3"));
        assertTrue(embed.getDescription().contains("1. Town01"));
        assertTrue(embed.getDescription().contains("10. Town10"));
        assertFalse(embed.getDescription().contains("11. Town11"));

        // Verify pagination buttons
        ArgumentCaptor<ActionRow> rowCaptor = ArgumentCaptor.forClass(ActionRow.class);
        verify(editAction).setComponents(rowCaptor.capture());
        ActionRow row = rowCaptor.getValue();
        assertEquals(2, row.getButtons().size());
        Button prev = row.getButtons().get(0);
        Button next = row.getButtons().get(1);
        assertEquals("embed.previous", prev.getLabel());
        assertEquals("embed.next", next.getLabel());
        assertTrue(prev.isDisabled(), "Previous button on page 1 must be disabled");
        assertFalse(next.isDisabled(), "Next button on page 1 must be enabled");
    }

    @Test
    void townlistSecondPageShowsNextBatch() {
        when(event.getName()).thenReturn("townlist");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsInt()).thenReturn(2);
        when(event.getOption("page")).thenReturn(opt);

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));

        List<TownSnapshot> allTowns = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            String name = String.format("Town%02d", i);
            UUID id = UUID.randomUUID();
            allTowns.add(new TownSnapshot(id, name, mayorUuid, List.of(mayorUuid), false, Optional.empty(), i, i * 10.0, 0L));
        }
        when(townyFacade.allTowns()).thenReturn(allTowns);
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "MayorBob", Optional.empty(), Optional.empty(), true, true, 0, 0)));

        commands.onSlashCommandInteraction(event);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();

        assertTrue(embed.getFooter().getText().contains("embed.page:2/3"));
        assertFalse(embed.getDescription().contains("10. Town10"));
        assertTrue(embed.getDescription().contains("11. Town11"));
        assertTrue(embed.getDescription().contains("20. Town20"));
        assertFalse(embed.getDescription().contains("21. Town21"));

        ArgumentCaptor<ActionRow> rowCaptor = ArgumentCaptor.forClass(ActionRow.class);
        verify(editAction).setComponents(rowCaptor.capture());
        ActionRow row = rowCaptor.getValue();
        Button prev = row.getButtons().get(0);
        Button next = row.getButtons().get(1);
        assertEquals("embed.previous", prev.getLabel());
        assertEquals("embed.next", next.getLabel());
        assertFalse(prev.isDisabled(), "Previous button on page 2 must be enabled");
        assertFalse(next.isDisabled(), "Next button on page 2 must be enabled");
    }

    @Test
    void residentsListLongEnoughToPaginateSplitsIntoPages() {
        when(event.getName()).thenReturn("residents");
        OptionMapping townOpt = mock(OptionMapping.class);
        when(townOpt.getAsString()).thenReturn("Rome");
        when(event.getOption("town")).thenReturn(townOpt);

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));

        List<UUID> residentUuids = new ArrayList<>();
        residentUuids.add(mayorUuid);
        for (int i = 1; i <= 14; i++) {
            UUID id = UUID.randomUUID();
            residentUuids.add(id);
            when(townyFacade.resident(id)).thenReturn(Optional.of(new ResidentSnapshot(id, String.format("Res%02d", i), Optional.of("Rome"), Optional.of(townUuid), false, i % 2 == 0, 0, 0)));
        }
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "Caesar", Optional.of("Rome"), Optional.of(townUuid), true, true, 0, 0)));

        TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, residentUuids, false, Optional.empty(), 10, 100.0, 0L);
        when(townyFacade.townByName("Rome")).thenReturn(Optional.of(town));

        commands.onSlashCommandInteraction(event);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();

        assertEquals("embed.residents: Rome", embed.getTitle());
        assertTrue(embed.getFooter().getText().contains("embed.page:1/2"));
        assertTrue(embed.getDescription().contains("Caesar"));
        assertTrue(embed.getDescription().contains("embed.mayor"));
        assertTrue(embed.getDescription().contains("embed.online"));

        ArgumentCaptor<ActionRow> rowCaptor = ArgumentCaptor.forClass(ActionRow.class);
        verify(editAction).setComponents(rowCaptor.capture());
        ActionRow row = rowCaptor.getValue();
        assertEquals(2, row.getButtons().size());
        Button prev = row.getButtons().get(0);
        Button next = row.getButtons().get(1);
        assertEquals("embed.previous", prev.getLabel());
        assertEquals("embed.next", next.getLabel());
        assertTrue(prev.isDisabled());
        assertFalse(next.isDisabled());
    }

    // --- Interactive Buttons ---

    @Test
    void townlistInteractiveButtonUpdatesPage() {
        ButtonInteractionEvent btnEvent = mock(ButtonInteractionEvent.class);
        when(btnEvent.getUser()).thenReturn(user);
        when(btnEvent.getComponentId()).thenReturn("dt:townlist:2");

        MessageEditCallbackAction editCallback = mock(MessageEditCallbackAction.class);
        when(btnEvent.deferEdit()).thenReturn(editCallback);
        doAnswer(inv -> {
            Consumer<InteractionHook> cb = inv.getArgument(0);
            cb.accept(hook);
            return null;
        }).when(editCallback).queue(any());

        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));

        List<TownSnapshot> allTowns = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            String name = String.format("Town%02d", i);
            UUID id = UUID.randomUUID();
            allTowns.add(new TownSnapshot(id, name, mayorUuid, List.of(mayorUuid), false, Optional.empty(), i, i * 10.0, 0L));
        }
        when(townyFacade.allTowns()).thenReturn(allTowns);
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "MayorBob", Optional.empty(), Optional.empty(), true, true, 0, 0)));

        commands.onButtonInteraction(btnEvent);

        verify(btnEvent).deferEdit();
        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();
        assertTrue(embed.getFooter().getText().contains("embed.page:2/3"));
    }

    @Test
    void residentsInteractiveButtonUpdatesPage() {
        ButtonInteractionEvent btnEvent = mock(ButtonInteractionEvent.class);
        when(btnEvent.getUser()).thenReturn(user);
        when(btnEvent.getComponentId()).thenReturn("dt:residents:2:" + townUuid);

        MessageEditCallbackAction editCallback = mock(MessageEditCallbackAction.class);
        when(btnEvent.deferEdit()).thenReturn(editCallback);
        doAnswer(inv -> {
            Consumer<InteractionHook> cb = inv.getArgument(0);
            cb.accept(hook);
            return null;
        }).when(editCallback).queue(any());

        List<UUID> residentUuids = new ArrayList<>();
        residentUuids.add(mayorUuid);
        for (int i = 1; i <= 14; i++) {
            UUID id = UUID.randomUUID();
            residentUuids.add(id);
            when(townyFacade.resident(id)).thenReturn(Optional.of(new ResidentSnapshot(id, String.format("Res%02d", i), Optional.of("Rome"), Optional.of(townUuid), false, true, 0, 0)));
        }
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "Caesar", Optional.of("Rome"), Optional.of(townUuid), true, true, 0, 0)));

        TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, residentUuids, false, Optional.empty(), 10, 100.0, 0L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));

        commands.onButtonInteraction(btnEvent);

        verify(btnEvent).deferEdit();
        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();
        assertTrue(embed.getFooter().getText().contains("embed.page:2/2"));
    }

    @Test
    void residentsPaginationResolvesByUuidWhenTownIsRenamedBetweenClicks() {
        ButtonInteractionEvent btnEvent = mock(ButtonInteractionEvent.class);
        when(btnEvent.getUser()).thenReturn(user);
        when(btnEvent.getComponentId()).thenReturn("dt:residents:2:" + townUuid);

        MessageEditCallbackAction editCallback = mock(MessageEditCallbackAction.class);
        when(btnEvent.deferEdit()).thenReturn(editCallback);
        doAnswer(inv -> {
            Consumer<InteractionHook> cb = inv.getArgument(0);
            cb.accept(hook);
            return null;
        }).when(editCallback).queue(any());

        List<UUID> residentUuids = new ArrayList<>();
        residentUuids.add(mayorUuid);
        for (int i = 1; i <= 14; i++) {
            UUID id = UUID.randomUUID();
            residentUuids.add(id);
            when(townyFacade.resident(id)).thenReturn(Optional.of(new ResidentSnapshot(id, String.format("Res%02d", i), Optional.of("NewRome"), Optional.of(townUuid), false, true, 0, 0)));
        }
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "Caesar", Optional.of("NewRome"), Optional.of(townUuid), true, true, 0, 0)));

        // Town was renamed to "NewRome"
        TownSnapshot renamedTown = new TownSnapshot(townUuid, "NewRome", mayorUuid, residentUuids, false, Optional.empty(), 10, 100.0, 0L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(renamedTown));

        commands.onButtonInteraction(btnEvent);

        verify(townyFacade).town(townUuid);
        verify(townyFacade, never()).townByName(anyString());
        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();
        assertEquals("embed.residents: NewRome", embed.getTitle());
        assertTrue(embed.getFooter().getText().contains("embed.page:2/2"));
    }

    @Test
    void residentsPaginationIdentifiesTownByUuidWhenOldNameIsReusedByDifferentTown() {
        ButtonInteractionEvent btnEvent = mock(ButtonInteractionEvent.class);
        when(btnEvent.getUser()).thenReturn(user);
        UUID originalTownUuid = UUID.randomUUID();
        when(btnEvent.getComponentId()).thenReturn("dt:residents:2:" + originalTownUuid);

        MessageEditCallbackAction editCallback = mock(MessageEditCallbackAction.class);
        when(btnEvent.deferEdit()).thenReturn(editCallback);
        doAnswer(inv -> {
            Consumer<InteractionHook> cb = inv.getArgument(0);
            cb.accept(hook);
            return null;
        }).when(editCallback).queue(any());

        List<UUID> residentUuids = new ArrayList<>();
        residentUuids.add(mayorUuid);
        for (int i = 1; i <= 14; i++) {
            UUID id = UUID.randomUUID();
            residentUuids.add(id);
            when(townyFacade.resident(id)).thenReturn(Optional.of(new ResidentSnapshot(id, String.format("Res%02d", i), Optional.of("OldRomeCapital"), Optional.of(originalTownUuid), false, true, 0, 0)));
        }
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "Caesar", Optional.of("OldRomeCapital"), Optional.of(originalTownUuid), true, true, 0, 0)));

        TownSnapshot originalTown = new TownSnapshot(originalTownUuid, "OldRomeCapital", mayorUuid, residentUuids, false, Optional.empty(), 10, 100.0, 0L);
        when(townyFacade.town(originalTownUuid)).thenReturn(Optional.of(originalTown));

        // Another unrelated town has taken the name "OldRome"
        UUID impostorTownUuid = UUID.randomUUID();
        TownSnapshot impostorTown = new TownSnapshot(impostorTownUuid, "OldRome", UUID.randomUUID(), List.of(), false, Optional.empty(), 1, 0.0, 0L);
        when(townyFacade.townByName("OldRome")).thenReturn(Optional.of(impostorTown));

        commands.onButtonInteraction(btnEvent);

        verify(townyFacade).town(originalTownUuid);
        verify(townyFacade, never()).townByName(anyString());
        verify(townyFacade, never()).town(impostorTownUuid);
        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();
        assertEquals("embed.residents: OldRomeCapital", embed.getTitle());
    }

    @Test
    void disabledCommandRejectsTownlistButtonWithoutInvokingTowny() {
        PluginConfig disabledConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of(
                        new PluginConfig.DiscordCommand("town", true, false),
                        new PluginConfig.DiscordCommand("mytown", true, true),
                        new PluginConfig.DiscordCommand("res", true, false),
                        new PluginConfig.DiscordCommand("residents", true, false),
                        new PluginConfig.DiscordCommand("townlist", false, false),
                        new PluginConfig.DiscordCommand("help", true, true)
                ))
        );
        TownySlashCommands testCommands = new TownySlashCommands(
                townyFacade, linkService, disabledConfig, messages, trackingExecutor, asyncExecutor, fixedClock);

        ButtonInteractionEvent btnEvent = mock(ButtonInteractionEvent.class);
        when(btnEvent.getUser()).thenReturn(user);
        when(btnEvent.getComponentId()).thenReturn("dt:townlist:2");
        ReplyCallbackAction btnReplyAction = mock(ReplyCallbackAction.class);
        when(btnEvent.reply(anyString())).thenReturn(btnReplyAction);
        when(btnReplyAction.setEphemeral(anyBoolean())).thenReturn(btnReplyAction);
        doAnswer(inv -> null).when(btnReplyAction).queue();

        testCommands.onButtonInteraction(btnEvent);

        verify(btnEvent).reply("general.command-disabled");
        verify(btnReplyAction).setEphemeral(true);
        verify(btnEvent, never()).deferEdit();
        verifyNoInteractions(townyFacade);
    }

    @Test
    void disabledCommandRejectsResidentsButtonWithoutInvokingTowny() {
        PluginConfig disabledConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of(
                        new PluginConfig.DiscordCommand("town", true, false),
                        new PluginConfig.DiscordCommand("mytown", true, true),
                        new PluginConfig.DiscordCommand("res", true, false),
                        new PluginConfig.DiscordCommand("residents", false, false),
                        new PluginConfig.DiscordCommand("townlist", true, false),
                        new PluginConfig.DiscordCommand("help", true, true)
                ))
        );
        TownySlashCommands testCommands = new TownySlashCommands(
                townyFacade, linkService, disabledConfig, messages, trackingExecutor, asyncExecutor, fixedClock);

        ButtonInteractionEvent btnEvent = mock(ButtonInteractionEvent.class);
        when(btnEvent.getUser()).thenReturn(user);
        when(btnEvent.getComponentId()).thenReturn("dt:residents:2:" + townUuid);
        ReplyCallbackAction btnReplyAction = mock(ReplyCallbackAction.class);
        when(btnEvent.reply(anyString())).thenReturn(btnReplyAction);
        when(btnReplyAction.setEphemeral(anyBoolean())).thenReturn(btnReplyAction);
        doAnswer(inv -> null).when(btnReplyAction).queue();

        testCommands.onButtonInteraction(btnEvent);

        verify(btnEvent).reply("general.command-disabled");
        verify(btnReplyAction).setEphemeral(true);
        verify(btnEvent, never()).deferEdit();
        verifyNoInteractions(townyFacade);
    }

    @Test
    void buttonInteractionOnCooldownIsRejectedWithoutInvokingTowny() {
        ButtonInteractionEvent firstEvent = mock(ButtonInteractionEvent.class);
        when(firstEvent.getUser()).thenReturn(user);
        when(firstEvent.getComponentId()).thenReturn("dt:townlist:2");
        MessageEditCallbackAction editCallback = mock(MessageEditCallbackAction.class);
        when(firstEvent.deferEdit()).thenReturn(editCallback);
        doAnswer(inv -> {
            Consumer<InteractionHook> cb = inv.getArgument(0);
            cb.accept(hook);
            return null;
        }).when(editCallback).queue(any());

        when(townyFacade.allTowns()).thenReturn(List.of());

        // First click succeeds
        commands.onButtonInteraction(firstEvent);
        verify(firstEvent).deferEdit();
        assertEquals(1, mainThreadHopCount);

        // Second click immediately after by same user
        ButtonInteractionEvent secondEvent = mock(ButtonInteractionEvent.class);
        when(secondEvent.getUser()).thenReturn(user);
        when(secondEvent.getComponentId()).thenReturn("dt:townlist:3");
        ReplyCallbackAction btnReplyAction = mock(ReplyCallbackAction.class);
        when(secondEvent.reply(anyString())).thenReturn(btnReplyAction);
        when(btnReplyAction.setEphemeral(anyBoolean())).thenReturn(btnReplyAction);
        doAnswer(inv -> null).when(btnReplyAction).queue();

        commands.onButtonInteraction(secondEvent);

        // Rejected on cooldown
        verify(secondEvent).reply(contains("general.cooldown"));
        verify(btnReplyAction).setEphemeral(true);
        verify(secondEvent, never()).deferEdit();
        // Towny not invoked a second time
        assertEquals(1, mainThreadHopCount);
    }

    @Test
    void residentsFinalPageShowsExactRemainingItemsAndDisablesNextButton() {
        when(event.getName()).thenReturn("residents");
        OptionMapping townOpt = mock(OptionMapping.class);
        when(townOpt.getAsString()).thenReturn("Rome");
        when(event.getOption("town")).thenReturn(townOpt);
        OptionMapping pageOpt = mock(OptionMapping.class);
        when(pageOpt.getAsInt()).thenReturn(2);
        when(event.getOption("page")).thenReturn(pageOpt);

        List<UUID> residentUuids = new ArrayList<>();
        residentUuids.add(mayorUuid);
        for (int i = 1; i <= 13; i++) {
            UUID id = UUID.randomUUID();
            residentUuids.add(id);
            when(townyFacade.resident(id)).thenReturn(Optional.of(new ResidentSnapshot(id, String.format("Res%02d", i), Optional.of("Rome"), Optional.of(townUuid), false, true, 0, 0)));
        }
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "Caesar", Optional.of("Rome"), Optional.of(townUuid), true, true, 0, 0)));

        TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, residentUuids, false, Optional.empty(), 10, 100.0, 0L);
        when(townyFacade.townByName("Rome")).thenReturn(Optional.of(town));

        commands.onSlashCommandInteraction(event);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();

        assertTrue(embed.getFooter().getText().contains("embed.page:2/2"));
        // Caesar (mayor) and Res01..Res09 are page 1. Res10..Res13 are page 2.
        assertFalse(embed.getDescription().contains("Caesar"));
        assertFalse(embed.getDescription().contains("Res01"));
        assertTrue(embed.getDescription().contains("Res10"));
        assertTrue(embed.getDescription().contains("Res11"));
        assertTrue(embed.getDescription().contains("Res12"));
        assertTrue(embed.getDescription().contains("Res13"));

        ArgumentCaptor<ActionRow> rowCaptor = ArgumentCaptor.forClass(ActionRow.class);
        verify(editAction).setComponents(rowCaptor.capture());
        ActionRow row = rowCaptor.getValue();
        Button prev = row.getButtons().get(0);
        Button next = row.getButtons().get(1);
        assertFalse(prev.isDisabled(), "Previous button on page 2 must be enabled");
        assertTrue(next.isDisabled(), "Next button on final page must be disabled");
    }

    @Test
    void residentsPaginationClampsOutOfRangePageNumber() {
        when(event.getName()).thenReturn("residents");
        OptionMapping townOpt = mock(OptionMapping.class);
        when(townOpt.getAsString()).thenReturn("Rome");
        when(event.getOption("town")).thenReturn(townOpt);
        OptionMapping pageOpt = mock(OptionMapping.class);
        when(pageOpt.getAsInt()).thenReturn(99);
        when(event.getOption("page")).thenReturn(pageOpt);

        List<UUID> residentUuids = new ArrayList<>();
        residentUuids.add(mayorUuid);
        for (int i = 1; i <= 13; i++) {
            UUID id = UUID.randomUUID();
            residentUuids.add(id);
            when(townyFacade.resident(id)).thenReturn(Optional.of(new ResidentSnapshot(id, String.format("Res%02d", i), Optional.of("Rome"), Optional.of(townUuid), false, true, 0, 0)));
        }
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "Caesar", Optional.of("Rome"), Optional.of(townUuid), true, true, 0, 0)));

        TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, residentUuids, false, Optional.empty(), 10, 100.0, 0L);
        when(townyFacade.townByName("Rome")).thenReturn(Optional.of(town));

        commands.onSlashCommandInteraction(event);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();

        // Clamped to max page 2
        assertTrue(embed.getFooter().getText().contains("embed.page:2/2"));
        assertTrue(embed.getDescription().contains("Res13"));
    }

    @Test
    void residentsPaginationHandlesTownShrinkageBetweenClicks() {
        ButtonInteractionEvent btnEvent = mock(ButtonInteractionEvent.class);
        when(btnEvent.getUser()).thenReturn(user);
        when(btnEvent.getComponentId()).thenReturn("dt:residents:2:" + townUuid);

        MessageEditCallbackAction editCallback = mock(MessageEditCallbackAction.class);
        when(btnEvent.deferEdit()).thenReturn(editCallback);
        doAnswer(inv -> {
            Consumer<InteractionHook> cb = inv.getArgument(0);
            cb.accept(hook);
            return null;
        }).when(editCallback).queue(any());

        // Shrunk to 1 resident (1 page total)
        List<UUID> residentUuids = List.of(mayorUuid);
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "Caesar", Optional.of("Rome"), Optional.of(townUuid), true, true, 0, 0)));

        TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, residentUuids, false, Optional.empty(), 10, 100.0, 0L);
        when(townyFacade.town(townUuid)).thenReturn(Optional.of(town));

        commands.onButtonInteraction(btnEvent);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();

        // Clamped to page 1/1
        assertTrue(embed.getFooter().getText().contains("embed.page:1/1"));
        assertTrue(embed.getDescription().contains("Caesar"));
        // When totalPages == 1, buttons are cleared
        verify(editAction).setComponents(Collections.emptyList());
    }

    @Test
    void residentsPaginationWhenTownDeletedBetweenClicksReturnsTownNotFound() {
        ButtonInteractionEvent btnEvent = mock(ButtonInteractionEvent.class);
        when(btnEvent.getUser()).thenReturn(user);
        when(btnEvent.getComponentId()).thenReturn("dt:residents:2:" + townUuid);

        MessageEditCallbackAction editCallback = mock(MessageEditCallbackAction.class);
        when(btnEvent.deferEdit()).thenReturn(editCallback);
        doAnswer(inv -> {
            Consumer<InteractionHook> cb = inv.getArgument(0);
            cb.accept(hook);
            return null;
        }).when(editCallback).queue(any());

        // Town was deleted
        when(townyFacade.town(townUuid)).thenReturn(Optional.empty());

        commands.onButtonInteraction(btnEvent);

        verify(hook).editOriginal(contains("general.town-not-found"));
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(editAction).setComponents(Collections.emptyList());
    }

    @Test
    void townlistDisplaysUnknownWhenMayorNotFound() {
        when(event.getName()).thenReturn("townlist");
        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));

        TownSnapshot town = new TownSnapshot(townUuid, "GhostTown", mayorUuid, List.of(), false, Optional.empty(), 1, 0.0, 0L);
        when(townyFacade.allTowns()).thenReturn(List.of(town));
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.empty());

        commands.onSlashCommandInteraction(event);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();

        assertTrue(embed.getDescription().contains("embed.unknown"));
    }

    @Test
    void townlistWhenNoTownsDisplaysClearMessage() {
        when(event.getName()).thenReturn("townlist");
        when(linkService.findByDiscordId(discordUserId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(new AccountLink(playerUuid, discordUserId, Instant.now(), "Steve"))));

        when(townyFacade.allTowns()).thenReturn(List.of());

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("general.no-towns-found");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
    }

    // --- Help Command ---

    @Test
    void helpCommandDisplaysAvailableCommandsAndWorksForUnlinkedAuthor() {
        when(event.getName()).thenReturn("help");

        // Unlinked author
        when(linkService.findByDiscordId(discordUserId)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        commands.onSlashCommandInteraction(event);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();

        assertEquals("embed.help-title", embed.getTitle());
        assertTrue(embed.getDescription().contains("embed.help-intro"));
        assertTrue(embed.getDescription().contains("/town"));
        assertTrue(embed.getDescription().contains("/mytown"));
        assertTrue(embed.getDescription().contains("/res"));
        assertTrue(embed.getDescription().contains("/residents"));
        assertTrue(embed.getDescription().contains("/townlist"));
        assertTrue(embed.getDescription().contains("/link"));
        assertTrue(embed.getDescription().contains("/unlink"));
        assertTrue(embed.getDescription().contains("/help"));

        // Towny is not touched for help
        verifyNoInteractions(townyFacade);
    }

    // --- Thread-Aware Execution Proof ---

    @Test
    void slashCommandTownyReadsOnMainThreadAndDiscordWorkOffMainThread() throws Exception {
        ExecutorService mainService = Executors.newSingleThreadExecutor(r -> new Thread(r, "server-main"));
        ExecutorService asyncService = Executors.newSingleThreadExecutor(r -> new Thread(r, "discord-worker"));
        try {
            TownySlashCommands threadedCommands = new TownySlashCommands(
                    townyFacade, linkService, config, messages, mainService, asyncService, fixedClock);

            when(event.getName()).thenReturn("town");
            OptionMapping opt = mock(OptionMapping.class);
            when(opt.getAsString()).thenReturn("Rome");
            when(event.getOption("name")).thenReturn(opt);

            TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, List.of(mayorUuid), false, Optional.empty(), 1, 0, 0);
            AtomicReference<String> townyThread = new AtomicReference<>();
            when(townyFacade.townByName("Rome")).thenAnswer(inv -> {
                townyThread.set(Thread.currentThread().getName());
                return Optional.of(town);
            });
            when(townyFacade.resident(mayorUuid)).thenAnswer(inv -> {
                townyThread.set(Thread.currentThread().getName());
                return Optional.empty();
            });

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> discordThread = new AtomicReference<>();
            when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenAnswer(inv -> {
                discordThread.set(Thread.currentThread().getName());
                latch.countDown();
                return editAction;
            });

            threadedCommands.onSlashCommandInteraction(event);

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Discord hook was not invoked within timeout");
            assertEquals("server-main", townyThread.get());
            assertEquals("discord-worker", discordThread.get());
            assertNotEquals("server-main", discordThread.get());
            assertNotEquals("discord-worker", townyThread.get());
        } finally {
            mainService.shutdownNow();
            asyncService.shutdownNow();
        }
    }

    @Test
    void slashCommandFailureContinuationRunsOffMainThread() throws Exception {
        ExecutorService mainService = Executors.newSingleThreadExecutor(r -> new Thread(r, "server-main"));
        ExecutorService asyncService = Executors.newSingleThreadExecutor(r -> new Thread(r, "discord-worker"));
        try {
            TownySlashCommands threadedCommands = new TownySlashCommands(
                    townyFacade, linkService, config, messages, mainService, asyncService, fixedClock);

            when(event.getName()).thenReturn("town");
            OptionMapping opt = mock(OptionMapping.class);
            when(opt.getAsString()).thenReturn("Rome");
            when(event.getOption("name")).thenReturn(opt);

            AtomicReference<String> townyThread = new AtomicReference<>();
            when(townyFacade.townByName("Rome")).thenAnswer(inv -> {
                townyThread.set(Thread.currentThread().getName());
                throw new TownyReadException("Towny boom");
            });

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> discordThread = new AtomicReference<>();
            when(hook.editOriginal("general.towny-read-failed")).thenAnswer(inv -> {
                discordThread.set(Thread.currentThread().getName());
                latch.countDown();
                return editAction;
            });

            threadedCommands.onSlashCommandInteraction(event);

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Discord hook failure callback was not invoked within timeout");
            assertEquals("server-main", townyThread.get());
            assertEquals("discord-worker", discordThread.get());
            assertNotEquals("server-main", discordThread.get());
            assertNotEquals("discord-worker", townyThread.get());
        } finally {
            mainService.shutdownNow();
            asyncService.shutdownNow();
        }
    }

    @Test
    void buttonInteractionTownyReadsOnMainThreadAndDiscordWorkOffMainThread() throws Exception {
        ExecutorService mainService = Executors.newSingleThreadExecutor(r -> new Thread(r, "server-main"));
        ExecutorService asyncService = Executors.newSingleThreadExecutor(r -> new Thread(r, "discord-worker"));
        try {
            TownySlashCommands threadedCommands = new TownySlashCommands(
                    townyFacade, linkService, config, messages, mainService, asyncService, fixedClock);

            ButtonInteractionEvent btnEvent = mock(ButtonInteractionEvent.class);
            when(btnEvent.getUser()).thenReturn(user);
            when(btnEvent.getComponentId()).thenReturn("dt:townlist:1");

            MessageEditCallbackAction editCallback = mock(MessageEditCallbackAction.class);
            when(btnEvent.deferEdit()).thenReturn(editCallback);
            doAnswer(inv -> {
                Consumer<InteractionHook> cb = inv.getArgument(0);
                cb.accept(hook);
                return null;
            }).when(editCallback).queue(any());

            TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, List.of(mayorUuid), false, Optional.empty(), 1, 0, 0);
            AtomicReference<String> townyThread = new AtomicReference<>();
            when(townyFacade.allTowns()).thenAnswer(inv -> {
                townyThread.set(Thread.currentThread().getName());
                return List.of(town);
            });
            when(townyFacade.resident(mayorUuid)).thenAnswer(inv -> {
                townyThread.set(Thread.currentThread().getName());
                return Optional.empty();
            });

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> discordThread = new AtomicReference<>();
            when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenAnswer(inv -> {
                discordThread.set(Thread.currentThread().getName());
                latch.countDown();
                return editAction;
            });

            threadedCommands.onButtonInteraction(btnEvent);

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Discord hook was not invoked within timeout");
            assertEquals("server-main", townyThread.get());
            assertEquals("discord-worker", discordThread.get());
            assertNotEquals("server-main", discordThread.get());
            assertNotEquals("discord-worker", townyThread.get());
        } finally {
            mainService.shutdownNow();
            asyncService.shutdownNow();
        }
    }

    @Test
    void buttonInteractionFailureContinuationRunsOffMainThread() throws Exception {
        ExecutorService mainService = Executors.newSingleThreadExecutor(r -> new Thread(r, "server-main"));
        ExecutorService asyncService = Executors.newSingleThreadExecutor(r -> new Thread(r, "discord-worker"));
        try {
            TownySlashCommands threadedCommands = new TownySlashCommands(
                    townyFacade, linkService, config, messages, mainService, asyncService, fixedClock);

            ButtonInteractionEvent btnEvent = mock(ButtonInteractionEvent.class);
            when(btnEvent.getUser()).thenReturn(user);
            when(btnEvent.getComponentId()).thenReturn("dt:townlist:1");

            MessageEditCallbackAction editCallback = mock(MessageEditCallbackAction.class);
            when(btnEvent.deferEdit()).thenReturn(editCallback);
            doAnswer(inv -> {
                Consumer<InteractionHook> cb = inv.getArgument(0);
                cb.accept(hook);
                return null;
            }).when(editCallback).queue(any());

            AtomicReference<String> townyThread = new AtomicReference<>();
            when(townyFacade.allTowns()).thenAnswer(inv -> {
                townyThread.set(Thread.currentThread().getName());
                throw new TownyReadException("Towny read failure");
            });

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> discordThread = new AtomicReference<>();
            when(hook.editOriginal("general.towny-read-failed")).thenAnswer(inv -> {
                discordThread.set(Thread.currentThread().getName());
                latch.countDown();
                return editAction;
            });

            threadedCommands.onButtonInteraction(btnEvent);

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Discord hook failure callback was not invoked within timeout");
            assertEquals("server-main", townyThread.get());
            assertEquals("discord-worker", discordThread.get());
            assertNotEquals("server-main", discordThread.get());
            assertNotEquals("discord-worker", townyThread.get());
        } finally {
            mainService.shutdownNow();
            asyncService.shutdownNow();
        }
    }

    @Test
    void townCommandWorksInAnyChannelRegardlessOfLinkChannelSetting() {
        PluginConfig restrictedConfig = new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty(), Optional.of("111222333444555666")),
                config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(), config.commands()
        );
        TownySlashCommands restrictedCommands = new TownySlashCommands(
                townyFacade, linkService, restrictedConfig, messages, trackingExecutor, asyncExecutor, fixedClock);
        LinkSlashCommands linkingCommands = new LinkSlashCommands(linkService, restrictedConfig, messages);

        when(event.getName()).thenReturn("town");
        when(event.getChannelId()).thenReturn("other-channel-999888777");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("Rome");
        when(event.getOption("name")).thenReturn(opt);

        TownSnapshot town = new TownSnapshot(townUuid, "Rome", mayorUuid, List.of(mayorUuid), false, Optional.empty(), 10, 100.0, 1000L);
        when(townyFacade.townByName("Rome")).thenReturn(Optional.of(town));
        when(townyFacade.resident(mayorUuid)).thenReturn(Optional.of(new ResidentSnapshot(mayorUuid, "Caesar", Optional.of("Rome"), Optional.of(townUuid), true, true, 0, 0)));

        // Dispatch through linking listener first (simulating JDA event distribution),
        // verifying that linking listener does not wrongly intercept or refuse the information command.
        linkingCommands.onSlashCommandInteraction(event);
        restrictedCommands.onSlashCommandInteraction(event);

        verify(hook).editOriginalEmbeds(any(MessageEmbed.class));
        verify(event, never()).reply(anyString());
    }

    @Test
    void linkingListenerIgnoresAllInformationCommandsInAnyChannel() {
        PluginConfig restrictedConfig = new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty(), Optional.of("111222333444555666")),
                config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(), config.commands()
        );
        LinkSlashCommands linkingCommands = new LinkSlashCommands(linkService, restrictedConfig, messages);

        for (String commandName : List.of("town", "residents", "res", "townlist", "mytown", "help")) {
            SlashCommandInteractionEvent infoEvent = mock(SlashCommandInteractionEvent.class);
            when(infoEvent.getName()).thenReturn(commandName);
            when(infoEvent.getChannelId()).thenReturn("other-channel-999888777");

            linkingCommands.onSlashCommandInteraction(infoEvent);

            verify(infoEvent, never()).reply(anyString());
            verify(infoEvent, never()).deferReply(anyBoolean());
        }
    }
}
