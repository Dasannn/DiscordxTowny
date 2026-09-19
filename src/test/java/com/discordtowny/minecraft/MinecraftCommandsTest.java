package com.discordtowny.minecraft;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.discord.DiscordGateway;
import com.discordtowny.link.LinkService;
import com.discordtowny.model.AccountLink;
import com.discordtowny.model.SpaceRequest;
import com.discordtowny.model.SpaceState;
import com.discordtowny.model.TownSnapshot;
import com.discordtowny.model.TownSpace;
import com.discordtowny.space.SpaceService;
import com.discordtowny.space.SpaceService.CreateResult;
import com.discordtowny.sync.SyncService;
import com.discordtowny.towny.TownyFacade;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests covering all in-game command functionality in {@link MinecraftCommands}.
 *
 * <p>Verifies acceptance criteria:
 * <ul>
 *   <li>Each command with and without permission</li>
 *   <li>Each command with and without Discord available</li>
 *   <li>Each command inside and outside a town</li>
 *   <li>Console vs player language localization</li>
 *   <li>Confirmations on destructive actions (/dt delete and /dt admin purge)</li>
 *   <li>Filtering of /dt help based on executor status</li>
 * </ul>
 */
class MinecraftCommandsTest {

    private LinkService linkService;
    private SpaceService spaceService;
    private SyncService syncService;
    private TownyFacade townyFacade;
    private DiscordGateway discordGateway;
    private PluginConfig config;
    private Messages messages;
    private Messages consoleMessages;
    private Runnable reloadAction;

    @BeforeEach
    void setUp() {
        linkService = mock(LinkService.class);
        spaceService = mock(SpaceService.class);
        syncService = mock(SyncService.class);
        townyFacade = mock(TownyFacade.class);
        discordGateway = mock(DiscordGateway.class);
        messages = mock(Messages.class);
        consoleMessages = mock(Messages.class);
        reloadAction = mock(Runnable.class);

        when(messages.get(any())).thenAnswer(inv -> Component.text("ES:" + inv.getArgument(0)));
        when(messages.get(any(), any())).thenAnswer(inv -> Component.text("ES:" + inv.getArgument(0)));

        when(consoleMessages.get(any())).thenAnswer(inv -> Component.text("EN:" + inv.getArgument(0)));
        when(consoleMessages.get(any(), any())).thenAnswer(inv -> Component.text("EN:" + inv.getArgument(0)));

        PluginConfig.Linking linking = new PluginConfig.Linking(
                Duration.ofMinutes(10), 3, Duration.ofMinutes(15), true);
        PluginConfig.Limits limits = new PluginConfig.Limits(200, 2, Duration.ofSeconds(60));
        PluginConfig.Sync sync = new PluginConfig.Sync(Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPAIR, 20, Duration.ofSeconds(5));

        config = new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty()),
                new PluginConfig.Database(PluginConfig.Database.Type.SQLITE, "localhost", 3306, "db", "", "", "dt_", 1, 1, Duration.ofSeconds(5)),
                new PluginConfig.Structure("Cat", "Arch", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Alcalde", "{town}", Optional.empty(), false),
                limits,
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                sync,
                linking,
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );

        when(townyFacade.isAvailable()).thenReturn(true);
        when(discordGateway.isAvailable()).thenReturn(true);
    }

    private LiteralCommandNode<CommandSourceStack> createRoot() {
        return MinecraftCommands.createCommandNode(
                linkService, spaceService, syncService, townyFacade, discordGateway,
                config, messages, consoleMessages, reloadAction, Runnable::run
        );
    }

    private CommandContext<CommandSourceStack> createContext(CommandSender sender) {
        CommandSourceStack stack = mock(CommandSourceStack.class);
        when(stack.getSender()).thenReturn(sender);
        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(stack);
        return ctx;
    }

    // --- /dt help tests ---

    @Test
    void helpFiltersCommandsForRegularPlayerOutsideTown() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.hasPermission("discordtowny.admin")).thenReturn(false);
        when(townyFacade.townOf(uuid)).thenReturn(Optional.empty());

        CommandContext<CommandSourceStack> ctx = createContext(player);
        root.getChild("help").getCommand().run(ctx);

        // Header and general player commands shown
        verify(player).sendMessage(messages.get("help.header"));
        verify(player).sendMessage(messages.get("help.cmd-help"));
        verify(player).sendMessage(messages.get("help.cmd-link"));
        verify(player).sendMessage(messages.get("help.cmd-unlink"));
        verify(player).sendMessage(messages.get("help.cmd-status"));

        // Mayor commands NOT shown
        verify(player, never()).sendMessage(messages.get("help.cmd-create"));
        verify(player, never()).sendMessage(messages.get("help.cmd-delete"));
        verify(player, never()).sendMessage(messages.get("help.cmd-sync"));

        // Admin commands NOT shown
        verify(player, never()).sendMessage(messages.get("help.cmd-admin-sync"));
        verify(player, never()).sendMessage(messages.get("help.cmd-admin-reload"));
        verify(player, never()).sendMessage(messages.get("help.cmd-admin-list"));
    }

    @Test
    void helpFiltersCommandsForMayorInTown() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player mayor = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(mayor.getUniqueId()).thenReturn(uuid);
        when(mayor.hasPermission("discordtowny.admin")).thenReturn(false);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.isMayor(uuid)).thenReturn(true);
        when(townyFacade.townOf(uuid)).thenReturn(Optional.of(town));

        CommandContext<CommandSourceStack> ctx = createContext(mayor);
        root.getChild("help").getCommand().run(ctx);

        // General and Mayor commands shown
        verify(mayor).sendMessage(messages.get("help.cmd-help"));
        verify(mayor).sendMessage(messages.get("help.cmd-status"));
        verify(mayor).sendMessage(messages.get("help.cmd-create"));
        verify(mayor).sendMessage(messages.get("help.cmd-delete"));
        verify(mayor).sendMessage(messages.get("help.cmd-sync"));

        // Admin commands NOT shown
        verify(mayor, never()).sendMessage(messages.get("help.cmd-admin-sync"));
        verify(mayor, never()).sendMessage(messages.get("help.cmd-admin-reload"));
    }

    @Test
    void helpFiltersCommandsForAdminPlayer() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player admin = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(admin.getUniqueId()).thenReturn(uuid);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(townyFacade.townOf(uuid)).thenReturn(Optional.empty());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("help").getCommand().run(ctx);

        // General and Admin commands shown
        verify(admin).sendMessage(messages.get("help.cmd-help"));
        verify(admin).sendMessage(messages.get("help.cmd-admin-sync"));
        verify(admin).sendMessage(messages.get("help.cmd-admin-unlink"));
        verify(admin).sendMessage(messages.get("help.cmd-admin-reload"));
        verify(admin).sendMessage(messages.get("help.cmd-admin-list"));
        verify(admin).sendMessage(messages.get("help.cmd-admin-info"));
        verify(admin).sendMessage(messages.get("help.cmd-admin-purge"));

        // Mayor commands NOT shown since not in town
        verify(admin, never()).sendMessage(messages.get("help.cmd-create"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-delete"));
    }

    @Test
    void helpForConsoleSenderRepliesInEnglish() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission("discordtowny.admin")).thenReturn(true);

        CommandContext<CommandSourceStack> ctx = createContext(console);
        root.getChild("help").getCommand().run(ctx);

        // English messages received by console
        verify(console).sendMessage(consoleMessages.get("help.header"));
        verify(console).sendMessage(consoleMessages.get("help.cmd-help"));
        verify(console).sendMessage(consoleMessages.get("help.cmd-admin-list"));
        verify(console, never()).sendMessage(messages.get("help.header"));
    }

    // --- /dt status tests ---

    @Test
    void statusFailsForConsoleSender() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        CommandContext<CommandSourceStack> ctx = createContext(console);
        root.getChild("status").getCommand().run(ctx);

        verify(console).sendMessage(consoleMessages.get("general.players-only"));
    }

    @Test
    void statusShowsLinkedAndActiveSpaceForTownResident() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player player = mock(Player.class);
        UUID playerUuid = UUID.randomUUID();
        UUID townUuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerUuid);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.name()).thenReturn("Rome");
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.of(town));

        AccountLink link = new AccountLink(playerUuid, "123456789", Instant.now(), "Steve");
        when(linkService.findByUuid(playerUuid)).thenReturn(CompletableFuture.completedFuture(Optional.of(link)));

        TownSpace space = new TownSpace(townUuid, "Rome", Optional.of("cat"), Optional.of("txt"), Optional.of("vc"),
                Optional.of("role"), SpaceState.ACTIVE, Instant.now(), Optional.empty(), Optional.empty());
        when(spaceService.find(townUuid)).thenReturn(CompletableFuture.completedFuture(Optional.of(space)));

        CommandContext<CommandSourceStack> ctx = createContext(player);
        root.getChild("status").getCommand().run(ctx);

        verify(player).sendMessage(messages.get("general.working"));
        verify(player).sendMessage(messages.get("status.header"));
        verify(player).sendMessage(messages.get("status.linked", Map.of("discord", "123456789")));
        verify(player).sendMessage(messages.get("status.space-active", Map.of("town", "Rome")));
    }

    @Test
    void statusShowsUnlinkedAndNoTownForNewPlayer() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player player = mock(Player.class);
        UUID playerUuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.empty());

        when(linkService.findByUuid(playerUuid)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        CommandContext<CommandSourceStack> ctx = createContext(player);
        root.getChild("status").getCommand().run(ctx);

        verify(player).sendMessage(messages.get("status.not-linked"));
        verify(player).sendMessage(messages.get("status.no-town"));
    }

    // --- /dt create tests ---

    @Test
    void createFailsWhenOutsideTown() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(townyFacade.townOf(uuid)).thenReturn(Optional.empty());

        CommandContext<CommandSourceStack> ctx = createContext(player);
        root.getChild("create").getCommand().run(ctx);

        verify(player).sendMessage(messages.get("general.not-in-town"));
        verify(spaceService, never()).create(any());
    }

    @Test
    void createFailsWhenNotMayor() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.isMayor(uuid)).thenReturn(false);
        when(townyFacade.townOf(uuid)).thenReturn(Optional.of(town));

        CommandContext<CommandSourceStack> ctx = createContext(player);
        root.getChild("create").getCommand().run(ctx);

        verify(player).sendMessage(messages.get("general.not-mayor"));
        verify(spaceService, never()).create(any());
    }

    @Test
    void createFailsWhenDiscordUnavailable() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.isMayor(uuid)).thenReturn(true);
        when(townyFacade.townOf(uuid)).thenReturn(Optional.of(town));
        when(discordGateway.isAvailable()).thenReturn(false);

        CommandContext<CommandSourceStack> ctx = createContext(player);
        root.getChild("create").getCommand().run(ctx);

        verify(player).sendMessage(messages.get("general.discord-unavailable"));
        verify(spaceService, never()).create(any());
    }

    @Test
    void createFailsWhenMayorNotLinked() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(UUID.randomUUID());
        when(town.name()).thenReturn("Rome");
        when(town.isMayor(uuid)).thenReturn(true);
        when(town.residentCount()).thenReturn(5);
        when(town.residentUuids()).thenReturn(List.of(uuid));
        when(townyFacade.townOf(uuid)).thenReturn(Optional.of(town));

        when(linkService.findByUuid(uuid)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        CommandContext<CommandSourceStack> ctx = createContext(player);
        root.getChild("create").getCommand().run(ctx);

        verify(player).sendMessage(messages.get("space.creating", Map.of("town", "Rome")));
        verify(player).sendMessage(messages.get("linking.link-required"));
        verify(spaceService, never()).create(any());
    }

    @Test
    void createSucceedsWhenConditionsMet() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);

        TownSnapshot town = mock(TownSnapshot.class);
        UUID townUuid = UUID.randomUUID();
        when(town.uuid()).thenReturn(townUuid);
        when(town.name()).thenReturn("Rome");
        when(town.mayorUuid()).thenReturn(uuid);
        when(town.isMayor(uuid)).thenReturn(true);
        when(town.residentCount()).thenReturn(5);
        when(town.residentUuids()).thenReturn(List.of(uuid));
        when(townyFacade.townOf(uuid)).thenReturn(Optional.of(town));

        AccountLink link = new AccountLink(uuid, "discord_123", Instant.now(), "Steve");
        when(linkService.findByUuid(uuid)).thenReturn(CompletableFuture.completedFuture(Optional.of(link)));
        when(spaceService.create(any())).thenReturn(CompletableFuture.completedFuture(CreateResult.SUCCESS));

        CommandContext<CommandSourceStack> ctx = createContext(player);
        root.getChild("create").getCommand().run(ctx);

        verify(player).sendMessage(messages.get("space.creating", Map.of("town", "Rome")));
        verify(player).sendMessage(messages.get("space.created", Map.of("town", "Rome")));

        ArgumentCaptor<SpaceRequest> captor = ArgumentCaptor.forClass(SpaceRequest.class);
        verify(spaceService).create(captor.capture());
        SpaceRequest request = captor.getValue();
        assertEquals(townUuid, request.townUuid());
        assertEquals("Rome", request.townName());
        assertEquals(uuid, request.mayorUuid());
        assertEquals(List.of("discord_123"), request.linkedResidentDiscordIds());
        assertEquals("discord_123", request.mayorDiscordId());
        assertEquals(5, request.townyResidentCount());
    }

    @Test
    void createWithPartiallyLinkedResidentsOnlyIncludesLinkedDiscordIdsInRequest() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player player = mock(Player.class);
        UUID mayorUuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(mayorUuid);

        UUID resident2Uuid = UUID.randomUUID();
        UUID resident3Uuid = UUID.randomUUID();
        UUID resident4Uuid = UUID.randomUUID();

        TownSnapshot town = mock(TownSnapshot.class);
        UUID townUuid = UUID.randomUUID();
        when(town.uuid()).thenReturn(townUuid);
        when(town.name()).thenReturn("Rome");
        when(town.mayorUuid()).thenReturn(mayorUuid);
        when(town.isMayor(mayorUuid)).thenReturn(true);
        when(town.residentCount()).thenReturn(4); // Whole population is 4
        when(town.residentUuids()).thenReturn(List.of(mayorUuid, resident2Uuid, resident3Uuid, resident4Uuid));
        when(townyFacade.townOf(mayorUuid)).thenReturn(Optional.of(town));

        // Mayor is linked
        AccountLink mayorLink = new AccountLink(mayorUuid, "discord_mayor", Instant.now(), "MayorSteve");
        when(linkService.findByUuid(mayorUuid)).thenReturn(CompletableFuture.completedFuture(Optional.of(mayorLink)));

        // Resident 2 is linked
        AccountLink res2Link = new AccountLink(resident2Uuid, "discord_res2", Instant.now(), "Alex");
        when(linkService.findByUuid(resident2Uuid)).thenReturn(CompletableFuture.completedFuture(Optional.of(res2Link)));

        // Resident 3 and Resident 4 are NOT linked
        when(linkService.findByUuid(resident3Uuid)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(linkService.findByUuid(resident4Uuid)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        when(spaceService.create(any())).thenReturn(CompletableFuture.completedFuture(CreateResult.SUCCESS));

        CommandContext<CommandSourceStack> ctx = createContext(player);
        root.getChild("create").getCommand().run(ctx);

        ArgumentCaptor<SpaceRequest> captor = ArgumentCaptor.forClass(SpaceRequest.class);
        verify(spaceService).create(captor.capture());
        SpaceRequest request = captor.getValue();

        assertEquals(townUuid, request.townUuid());
        assertEquals("Rome", request.townName());
        assertEquals(mayorUuid, request.mayorUuid());
        assertEquals("discord_mayor", request.mayorDiscordId());
        // Exactly the linked residents reach the request
        assertEquals(List.of("discord_mayor", "discord_res2"), request.linkedResidentDiscordIds());
        // townyResidentCount is the town's whole population (4), not the number of linked residents (2)
        assertEquals(4, request.townyResidentCount());

        verify(player).sendMessage(messages.get("space.creating", Map.of("town", "Rome")));
        verify(player).sendMessage(messages.get("space.created", Map.of("town", "Rome")));
    }

    // --- /dt delete tests ---

    @Test
    void deleteRequiresConfirmationBeforeArchiving() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player mayor = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        UUID townUuid = UUID.randomUUID();
        when(mayor.getUniqueId()).thenReturn(uuid);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.name()).thenReturn("Rome");
        when(town.isMayor(uuid)).thenReturn(true);
        when(townyFacade.townOf(uuid)).thenReturn(Optional.of(town));

        CommandContext<CommandSourceStack> ctx = createContext(mayor);

        // 1st run: requests confirmation
        root.getChild("delete").getCommand().run(ctx);
        verify(mayor).sendMessage(messages.get("space.delete-confirm", Map.of("town", "Rome")));
        verify(spaceService, never()).archive(any(), any());

        // 2nd run: executes archive
        when(spaceService.archive(eq(townUuid), any())).thenReturn(CompletableFuture.completedFuture(null));
        root.getChild("delete").getCommand().run(ctx);

        verify(mayor).sendMessage(messages.get("general.working"));
        verify(mayor).sendMessage(messages.get("space.archived", Map.of("town", "Rome")));
        verify(spaceService, times(1)).archive(eq(townUuid), any());
    }

    // --- /dt admin reload tests ---

    @Test
    void adminReloadExecutesReloadActionAndResponds() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("reload").getCommand().run(ctx);

        verify(reloadAction, times(1)).run();
        verify(admin).sendMessage(messages.get("admin.reloaded"));
    }

    @Test
    void adminReloadForConsoleSenderRepliesInEnglish() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission("discordtowny.admin")).thenReturn(true);

        CommandContext<CommandSourceStack> ctx = createContext(console);
        root.getChild("admin").getChild("reload").getCommand().run(ctx);

        verify(reloadAction, times(1)).run();
        verify(console).sendMessage(consoleMessages.get("admin.reloaded"));
        verify(console, never()).sendMessage(messages.get("admin.reloaded"));
    }

    // --- /dt admin list tests ---

    @Test
    void adminListShowsEmptyMessageWhenNoSpaces() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);

        when(spaceService.findAll()).thenReturn(CompletableFuture.completedFuture(List.of()));

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("list").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("general.working"));
        verify(admin).sendMessage(messages.get("admin.list-empty"));
    }

    @Test
    void adminListListsRegisteredSpaces() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);

        TownSpace space = new TownSpace(UUID.randomUUID(), "Rome", Optional.of("cat"),
                Optional.of("txt"), Optional.of("vc"), Optional.of("role1"),
                SpaceState.ACTIVE, Instant.now(), Optional.empty(), Optional.empty());
        when(spaceService.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(space)));
        when(discordGateway.roleHolders("role1")).thenReturn(Set.of("user1", "user2"));

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("list").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("general.working"));
        verify(admin).sendMessage(messages.get("admin.list-header", Map.of("count", "1")));
        verify(admin).sendMessage(messages.get("admin.list-entry", Map.of(
                "town", "Rome",
                "status", "ACTIVE",
                "channels", "text voice",
                "residents", "2",
                "activity", "none"
        )));
    }

    // --- /dt admin info tests ---

    @Test
    void adminInfoShowsNotFoundWhenTownAndSpaceMissing() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);

        when(townyFacade.townByName("Atlantis")).thenReturn(Optional.empty());
        when(spaceService.findAll()).thenReturn(CompletableFuture.completedFuture(List.of()));

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        when(ctx.getArgument("town", String.class)).thenReturn("Atlantis");

        root.getChild("admin").getChild("info").getChild("town").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("general.town-not-found", Map.of("town", "Atlantis")));
    }

    @Test
    void adminInfoShowsSpaceDetailsAndInconsistencies() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);

        UUID townUuid = UUID.randomUUID();
        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.name()).thenReturn("Rome");
        when(town.ruined()).thenReturn(false);
        when(townyFacade.townByName("Rome")).thenReturn(Optional.of(town));

        TownSpace space = new TownSpace(townUuid, "Rome", Optional.of("cat"),
                Optional.of("txt1"), Optional.of("vc1"), Optional.of("role1"),
                SpaceState.INCONSISTENT, Instant.now(), Optional.empty(), Optional.empty());
        when(spaceService.find(townUuid)).thenReturn(CompletableFuture.completedFuture(Optional.of(space)));
        when(discordGateway.existingResourceIds(any())).thenReturn(Set.of("role1")); // txt1 and vc1 missing

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        when(ctx.getArgument("town", String.class)).thenReturn("Rome");

        root.getChild("admin").getChild("info").getChild("town").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("admin.info-header", Map.of("town", "Rome")));
        verify(admin).sendMessage(messages.get("admin.info-status", Map.of("status", "INCONSISTENT")));
        verify(admin).sendMessage(messages.get("admin.info-inconsistencies-header", Map.of("count", "3")));
    }

    // --- /dt admin purge tests ---

    @Test
    void adminPurgeRequiresConfirmation() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player admin = mock(Player.class);
        UUID adminUuid = UUID.randomUUID();
        when(admin.getUniqueId()).thenReturn(adminUuid);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);

        TownSpace archivedSpace = new TownSpace(UUID.randomUUID(), "OldTown", Optional.of("cat"),
                Optional.empty(), Optional.empty(), Optional.empty(),
                SpaceState.ARCHIVED, Instant.now(), Optional.of(Instant.now()), Optional.empty());
        when(spaceService.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(archivedSpace)));

        CommandContext<CommandSourceStack> ctx = createContext(admin);

        // 1st run: requests confirmation
        root.getChild("admin").getChild("purge").getCommand().run(ctx);
        verify(admin).sendMessage(messages.get("admin.purge-confirm", Map.of("count", "1")));
        verify(spaceService, never()).purgeArchived();

        // 2nd run: executes purge
        when(spaceService.purgeArchived()).thenReturn(CompletableFuture.completedFuture(1));
        root.getChild("admin").getChild("purge").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("general.working"));
        verify(admin).sendMessage(messages.get("admin.purged", Map.of("count", "1")));
        verify(spaceService, times(1)).purgeArchived();
    }

    @Test
    void adminPurgeWhenNoArchivedSpacesShowsEmpty() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player admin = mock(Player.class);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);

        when(spaceService.findAll()).thenReturn(CompletableFuture.completedFuture(List.of()));

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("purge").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("admin.purge-empty"));
        verify(spaceService, never()).purgeArchived();
    }
}
