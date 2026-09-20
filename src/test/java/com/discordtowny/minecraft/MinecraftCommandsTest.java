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
import com.discordtowny.update.UpdateService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.yaml.snakeyaml.Yaml;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        when(messages.label(any())).thenAnswer(inv -> "ES:" + inv.getArgument(0));
        when(messages.label(any(), any())).thenAnswer(inv -> "ES:" + inv.getArgument(0));
        when(messages.label(eq("admin.channel-text"))).thenReturn("texto");
        when(messages.label(eq("admin.channel-voice"))).thenReturn("voz");
        when(messages.label(eq("admin.none"))).thenReturn("sin registrar");
        when(messages.label(eq("admin.not-applicable"))).thenReturn("N/A");
        when(messages.label(eq("admin.state-active"))).thenReturn("activo");
        when(messages.label(eq("admin.state-archived"))).thenReturn("archivado");
        when(messages.label(eq("admin.state-inconsistent"))).thenReturn("inconsistente");
        when(messages.label(eq("general.unknown"))).thenReturn("desconocido");
        when(messages.label(eq("updates.result-network-error"))).thenReturn("error de red");
        when(messages.label(eq("updates.result-checksum-mismatch"))).thenReturn("error de checksum");
        when(messages.label(eq("updates.result-too-large"))).thenReturn("archivo demasiado grande");
        when(messages.label(eq("updates.result-io-error"))).thenReturn("error de entrada/salida");
        when(messages.label(eq("help.description"))).thenReturn("Comandos de DiscordTowny dentro del juego");
        when(messages.label(eq("space.internal-error"))).thenReturn("Error interno");

        when(consoleMessages.get(any())).thenAnswer(inv -> Component.text("EN:" + inv.getArgument(0)));
        when(consoleMessages.get(any(), any())).thenAnswer(inv -> Component.text("EN:" + inv.getArgument(0)));
        when(consoleMessages.label(any())).thenAnswer(inv -> "EN:" + inv.getArgument(0));
        when(consoleMessages.label(any(), any())).thenAnswer(inv -> "EN:" + inv.getArgument(0));
        when(consoleMessages.label(eq("admin.channel-text"))).thenReturn("text");
        when(consoleMessages.label(eq("admin.channel-voice"))).thenReturn("voice");
        when(consoleMessages.label(eq("admin.none"))).thenReturn("none");
        when(consoleMessages.label(eq("admin.not-applicable"))).thenReturn("N/A");
        when(consoleMessages.label(eq("admin.state-active"))).thenReturn("active");
        when(consoleMessages.label(eq("admin.state-archived"))).thenReturn("archived");
        when(consoleMessages.label(eq("admin.state-inconsistent"))).thenReturn("inconsistent");
        when(consoleMessages.label(eq("general.unknown"))).thenReturn("unknown");
        when(consoleMessages.label(eq("updates.result-network-error"))).thenReturn("network error");
        when(consoleMessages.label(eq("updates.result-checksum-mismatch"))).thenReturn("checksum mismatch");
        when(consoleMessages.label(eq("updates.result-too-large"))).thenReturn("file too large");
        when(consoleMessages.label(eq("updates.result-io-error"))).thenReturn("I/O error");
        when(consoleMessages.label(eq("help.description"))).thenReturn("DiscordTowny in-game commands");
        when(consoleMessages.label(eq("space.internal-error"))).thenReturn("Internal error");

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
        return createRoot(Runnable::run);
    }

    private LiteralCommandNode<CommandSourceStack> createRoot(java.util.function.Consumer<Runnable> scheduler) {
        return MinecraftCommands.createCommandNode(
                linkService, spaceService, syncService, townyFacade, discordGateway,
                config, messages, consoleMessages, reloadAction, scheduler
        );
    }

    private LiteralCommandNode<CommandSourceStack> createRoot(UpdateService updateService) {
        return MinecraftCommands.createCommandNode(
                linkService, spaceService, syncService, townyFacade, discordGateway,
                updateService, config, messages, consoleMessages, reloadAction, Runnable::run
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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);
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
        verify(player, never()).sendMessage(messages.get("help.cmd-admin-update"));
        verify(player, never()).sendMessage(messages.get("help.cmd-admin-update-status"));
        verify(player, never()).sendMessage(messages.get("help.cmd-admin-update-confirm"));
    }

    @Test
    void helpFiltersCommandsForMayorInTown() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player mayor = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(mayor.getUniqueId()).thenReturn(uuid);
        when(mayor.hasPermission("discordtowny.use")).thenReturn(true);
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
        verify(mayor, never()).sendMessage(messages.get("help.cmd-admin-update"));
        verify(mayor, never()).sendMessage(messages.get("help.cmd-admin-update-status"));
        verify(mayor, never()).sendMessage(messages.get("help.cmd-admin-update-confirm"));
    }

    @Test
    void helpFiltersCommandsForAdminPlayer() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player admin = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(admin.getUniqueId()).thenReturn(uuid);
        when(admin.hasPermission("discordtowny.use")).thenReturn(true);
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
        verify(admin).sendMessage(messages.get("help.cmd-admin-update"));
        verify(admin).sendMessage(messages.get("help.cmd-admin-update-status"));
        verify(admin).sendMessage(messages.get("help.cmd-admin-update-confirm"));

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
        verify(console).sendMessage(consoleMessages.get("help.cmd-admin-sync"));
        verify(console).sendMessage(consoleMessages.get("help.cmd-admin-unlink"));
        verify(console).sendMessage(consoleMessages.get("help.cmd-admin-reload"));
        verify(console).sendMessage(consoleMessages.get("help.cmd-admin-list"));
        verify(console).sendMessage(consoleMessages.get("help.cmd-admin-info"));
        verify(console).sendMessage(consoleMessages.get("help.cmd-admin-purge"));
        verify(console).sendMessage(consoleMessages.get("help.cmd-admin-update"));
        verify(console).sendMessage(consoleMessages.get("help.cmd-admin-update-status"));
        verify(console).sendMessage(consoleMessages.get("help.cmd-admin-update-confirm"));

        // Player-only commands NOT advertised to console
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-status"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-link"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-unlink"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-create"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-delete"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-sync"));

        verify(console, never()).sendMessage(messages.get("help.header"));
    }

    @Test
    void helpForConsoleSenderLackingAdminPermissionShowsOnlyHelp() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission("discordtowny.admin")).thenReturn(false);

        CommandContext<CommandSourceStack> ctx = createContext(console);
        root.getChild("help").getCommand().run(ctx);

        verify(console).sendMessage(consoleMessages.get("help.header"));
        verify(console).sendMessage(consoleMessages.get("help.cmd-help"));

        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-status"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-admin-sync"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-admin-unlink"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-admin-reload"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-admin-list"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-admin-info"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-admin-purge"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-admin-update"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-admin-update-status"));
        verify(console, never()).sendMessage(consoleMessages.get("help.cmd-admin-update-confirm"));
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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);
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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);
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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

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
        when(mayor.hasPermission("discordtowny.use")).thenReturn(true);

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
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

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
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

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
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

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
                "status", "activo",
                "channels", "texto voz",
                "residents", "2",
                "activity", "sin registrar"
        )));
    }

    @Test
    void adminListForConsoleSenderRepliesWithEnglishChannelAndActivityLabels() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission("discordtowny.admin")).thenReturn(true);

        TownSpace space = new TownSpace(UUID.randomUUID(), "Rome", Optional.of("cat"),
                Optional.of("txt"), Optional.of("vc"), Optional.of("role1"),
                SpaceState.ACTIVE, Instant.now(), Optional.empty(), Optional.empty());
        when(spaceService.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(space)));
        when(discordGateway.roleHolders("role1")).thenReturn(Set.of("user1", "user2"));

        CommandContext<CommandSourceStack> ctx = createContext(console);
        root.getChild("admin").getChild("list").getCommand().run(ctx);

        verify(console).sendMessage(consoleMessages.get("general.working"));
        verify(console).sendMessage(consoleMessages.get("admin.list-header", Map.of("count", "1")));
        verify(console).sendMessage(consoleMessages.get("admin.list-entry", Map.of(
                "town", "Rome",
                "status", "active",
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
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

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
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

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
        verify(admin).sendMessage(messages.get("admin.info-status", Map.of("status", "inconsistente")));
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
        verify(admin).sendMessage(messages.get("general.working"));
        verify(admin).sendMessage(messages.get("admin.purge-confirm", Map.of("count", "1")));
        verify(spaceService, never()).purgeArchived();

        // 2nd run: executes purge
        when(spaceService.purgeArchived()).thenReturn(CompletableFuture.completedFuture(1));
        root.getChild("admin").getChild("purge").getCommand().run(ctx);

        verify(admin, times(2)).sendMessage(messages.get("general.working"));
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

        verify(admin).sendMessage(messages.get("general.working"));
        verify(admin).sendMessage(messages.get("admin.purge-empty"));
        verify(spaceService, never()).purgeArchived();
    }

    @Test
    void adminReloadFailsGracefullyWhenActionThrows() throws Exception {
        Runnable failingReload = mock(Runnable.class);
        doThrow(new RuntimeException("Configuration syntax error")).when(failingReload).run();

        LiteralCommandNode<CommandSourceStack> root = MinecraftCommands.createCommandNode(
                linkService, spaceService, syncService, townyFacade, discordGateway,
                config, messages, consoleMessages, failingReload, Runnable::run
        );
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("reload").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("admin.reload-failed", Map.of("reason", "Configuration syntax error")));
    }

    @Test
    void adminCommandsEnforcePermissionThroughBrigadierDispatch() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(root);

        // Player without permission
        Player unauthorizedPlayer = mock(Player.class);
        when(unauthorizedPlayer.hasPermission("discordtowny.admin")).thenReturn(false);
        CommandSourceStack unauthorizedStack = mock(CommandSourceStack.class);
        when(unauthorizedStack.getSender()).thenReturn(unauthorizedPlayer);

        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("dt admin list", unauthorizedStack));

        // Player with permission
        Player authorizedAdmin = mock(Player.class);
        when(authorizedAdmin.hasPermission("discordtowny.admin")).thenReturn(true);
        CommandSourceStack authorizedStack = mock(CommandSourceStack.class);
        when(authorizedStack.getSender()).thenReturn(authorizedAdmin);
        when(spaceService.findAll()).thenReturn(CompletableFuture.completedFuture(List.of()));

        int result = dispatcher.execute("dt admin list", authorizedStack);
        assertEquals(1, result);
        verify(authorizedAdmin).sendMessage(messages.get("admin.list-empty"));
    }

    @Test
    void asynchronousCommandRepliesThroughSchedulerOnMainThreadAndNeverOnWorkerThread() throws Exception {
        BlockingQueue<Runnable> schedulerQueue = new LinkedBlockingQueue<>();
        LiteralCommandNode<CommandSourceStack> root = createRoot(schedulerQueue::add);

        Player mayor = mock(Player.class);
        UUID mayorUuid = UUID.randomUUID();
        UUID townUuid = UUID.randomUUID();
        when(mayor.getUniqueId()).thenReturn(mayorUuid);
        when(mayor.hasPermission("discordtowny.use")).thenReturn(true);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.name()).thenReturn("Rome");
        when(town.isMayor(mayorUuid)).thenReturn(true);
        when(town.residentCount()).thenReturn(1);
        when(town.residentUuids()).thenReturn(List.of(mayorUuid));
        when(townyFacade.townOf(mayorUuid)).thenReturn(Optional.of(town));

        AccountLink mayorLink = new AccountLink(mayorUuid, "discord_123", Instant.now(), "MayorSteve");
        when(linkService.findByUuid(mayorUuid)).thenReturn(CompletableFuture.completedFuture(Optional.of(mayorLink)));

        CompletableFuture<CreateResult> delayedFuture = new CompletableFuture<>();
        when(spaceService.create(any())).thenReturn(delayedFuture);

        AtomicReference<Thread> replyThread = new AtomicReference<>();
        doAnswer(inv -> {
            replyThread.set(Thread.currentThread());
            return null;
        }).when(mayor).sendMessage(any(Component.class));

        CommandContext<CommandSourceStack> ctx = createContext(mayor);
        root.getChild("create").getCommand().run(ctx);

        verify(mayor).sendMessage(messages.get("space.creating", Map.of("town", "Rome")));
        verify(mayor, never()).sendMessage(messages.get("space.created", Map.of("town", "Rome")));

        Thread workerThread = new Thread(() -> delayedFuture.complete(CreateResult.SUCCESS), "async-worker-thread");
        workerThread.start();
        workerThread.join();

        verify(mayor, never()).sendMessage(messages.get("space.created", Map.of("town", "Rome")));
        assertNotEquals(workerThread, replyThread.get(), "Completion callback must not execute on worker thread");

        Runnable scheduledTask = schedulerQueue.poll(2, TimeUnit.SECONDS);
        assertNotNull(scheduledTask, "Scheduler should have received the response task");

        Thread mainThread = Thread.currentThread();
        scheduledTask.run();

        verify(mayor).sendMessage(messages.get("space.created", Map.of("town", "Rome")));
        assertEquals(mainThread, replyThread.get(), "Reply must execute on the thread running the scheduler");
    }

    // --- /dt sync tests ---

    @Test
    void syncReportsBothRepairsAndProblemsInRepairMode() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player mayor = mock(Player.class);
        UUID mayorUuid = UUID.randomUUID();
        UUID townUuid = UUID.randomUUID();
        when(mayor.getUniqueId()).thenReturn(mayorUuid);
        when(mayor.hasPermission("discordtowny.use")).thenReturn(true);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.isMayor(mayorUuid)).thenReturn(true);
        when(townyFacade.townOf(mayorUuid)).thenReturn(Optional.of(town));

        SyncService.SyncReport report = new SyncService.SyncReport(
                1, 1, 0, 2, 1, List.of("Discord role missing"),
                PluginConfig.Sync.Mode.REPAIR, 0, 0
        );
        when(syncService.syncTown(townUuid)).thenReturn(CompletableFuture.completedFuture(report));

        CommandContext<CommandSourceStack> ctx = createContext(mayor);
        root.getChild("sync").getCommand().run(ctx);

        verify(mayor).sendMessage(messages.get("sync.started"));
        verify(mayor).sendMessage(messages.get("sync.repaired", Map.of("count", "1", "granted", "1", "revoked", "0")));
        verify(mayor).sendMessage(messages.get("sync.problems-header", Map.of("count", "1")));
        verify(mayor).sendMessage(messages.get("sync.problem-entry", Map.of("problem", "Discord role missing")));
        verify(mayor, never()).sendMessage(messages.get("sync.finished"));
    }

    @Test
    void syncReportsProblemsInReportMode() throws Exception {
        PluginConfig.Sync reportModeSync = new PluginConfig.Sync(
                Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPORT, 20, Duration.ofSeconds(5));
        PluginConfig reportConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), reportModeSync, config.linking(),
                config.logging(), config.updates(), config.commands()
        );

        LiteralCommandNode<CommandSourceStack> root = MinecraftCommands.createCommandNode(
                linkService, spaceService, syncService, townyFacade, discordGateway,
                reportConfig, messages, consoleMessages, reloadAction, Runnable::run
        );

        Player mayor = mock(Player.class);
        UUID mayorUuid = UUID.randomUUID();
        UUID townUuid = UUID.randomUUID();
        when(mayor.getUniqueId()).thenReturn(mayorUuid);
        when(mayor.hasPermission("discordtowny.use")).thenReturn(true);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.isMayor(mayorUuid)).thenReturn(true);
        when(townyFacade.townOf(mayorUuid)).thenReturn(Optional.of(town));

        SyncService.SyncReport report = new SyncService.SyncReport(
                1, 0, 0, 1, 0, List.of("Role discord_role missing"),
                PluginConfig.Sync.Mode.REPORT, 1, 0
        );
        when(syncService.syncTown(townUuid)).thenReturn(CompletableFuture.completedFuture(report));

        CommandContext<CommandSourceStack> ctx = createContext(mayor);
        root.getChild("sync").getCommand().run(ctx);

        verify(mayor).sendMessage(messages.get("sync.started"));
        verify(mayor).sendMessage(messages.get("sync.report-found", Map.of("count", "1")));
        verify(mayor).sendMessage(messages.get("sync.report-pending", Map.of("granted", "1", "revoked", "0")));
        verify(mayor).sendMessage(messages.get("sync.problems-header", Map.of("count", "1")));
        verify(mayor).sendMessage(messages.get("sync.problem-entry", Map.of("problem", "Role discord_role missing")));
        // No matcher here: mixing any() with a literal in the same call corrupts
        // Mockito's matcher stack. The rendered message is predictable, so demand it.
        verify(mayor, never()).sendMessage(messages.get("sync.report-clean", Map.of("spaces", "1")));
    }

    @Test
    void syncReportsUnrepairedWhenInconsistenciesRemainAndProblemListEmpty() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player mayor = mock(Player.class);
        UUID mayorUuid = UUID.randomUUID();
        UUID townUuid = UUID.randomUUID();
        when(mayor.getUniqueId()).thenReturn(mayorUuid);
        when(mayor.hasPermission("discordtowny.use")).thenReturn(true);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.isMayor(mayorUuid)).thenReturn(true);
        when(townyFacade.townOf(mayorUuid)).thenReturn(Optional.of(town));

        SyncService.SyncReport report = new SyncService.SyncReport(
                1, 0, 0, 3, 1, List.of(),
                PluginConfig.Sync.Mode.REPAIR, 0, 0
        );
        when(syncService.syncTown(townUuid)).thenReturn(CompletableFuture.completedFuture(report));

        CommandContext<CommandSourceStack> ctx = createContext(mayor);
        root.getChild("sync").getCommand().run(ctx);

        verify(mayor).sendMessage(messages.get("sync.started"));
        verify(mayor).sendMessage(messages.get("sync.repaired", Map.of("count", "1", "granted", "0", "revoked", "0")));
        verify(mayor).sendMessage(messages.get("sync.unrepaired", Map.of("count", "2")));
        verify(mayor, never()).sendMessage(messages.get("sync.finished"));
    }

    @Test
    void syncReportsFinishedWhenCleanInRepairMode() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player mayor = mock(Player.class);
        UUID mayorUuid = UUID.randomUUID();
        UUID townUuid = UUID.randomUUID();
        when(mayor.getUniqueId()).thenReturn(mayorUuid);
        when(mayor.hasPermission("discordtowny.use")).thenReturn(true);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.isMayor(mayorUuid)).thenReturn(true);
        when(townyFacade.townOf(mayorUuid)).thenReturn(Optional.of(town));

        SyncService.SyncReport report = new SyncService.SyncReport(
                1, 0, 0, 0, 0, List.of(),
                PluginConfig.Sync.Mode.REPAIR, 0, 0
        );
        when(syncService.syncTown(townUuid)).thenReturn(CompletableFuture.completedFuture(report));

        CommandContext<CommandSourceStack> ctx = createContext(mayor);
        root.getChild("sync").getCommand().run(ctx);

        verify(mayor).sendMessage(messages.get("sync.started"));
        verify(mayor).sendMessage(messages.get("sync.finished"));
    }

    // --- /dt admin update tests ---

    @Test
    void adminUpdateWhenDisabledReportsDisabled() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot((UpdateService) null);
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("update").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("updates.disabled"));
    }

    @Test
    void adminUpdateWhenUpdatePendingReportsAlreadyDownloaded() throws Exception {
        UpdateService updateService = mock(UpdateService.class);
        when(updateService.isUpdatePending()).thenReturn(true);
        when(updateService.currentVersion()).thenReturn("1.0.0");
        when(updateService.getAvailableUpdate()).thenReturn(Optional.of(new UpdateService.Release("2.0.0", "url", "hash", "notes")));

        LiteralCommandNode<CommandSourceStack> root = createRoot(updateService);
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("update").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("updates.downloaded", Map.of("latest", "2.0.0")));
        verify(updateService, never()).checkForUpdate();
    }

    @Test
    void adminUpdateWhenUpToDateReportsUpToDate() throws Exception {
        UpdateService updateService = mock(UpdateService.class);
        when(updateService.isUpdatePending()).thenReturn(false);
        when(updateService.checkForUpdate()).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        LiteralCommandNode<CommandSourceStack> root = createRoot(updateService);
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("update").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("general.working"));
        verify(admin).sendMessage(messages.get("updates.up-to-date"));
        verify(updateService, never()).download(any());
    }

    @Test
    void adminUpdateNonBreakingDownloadsSuccessfully() throws Exception {
        UpdateService updateService = mock(UpdateService.class);
        UpdateService.Release release = new UpdateService.Release("1.1.0", "url", "hash", "notes");
        when(updateService.isUpdatePending()).thenReturn(false);
        when(updateService.checkForUpdate()).thenReturn(CompletableFuture.completedFuture(Optional.of(release)));
        when(updateService.isBreaking(release)).thenReturn(false);
        when(updateService.download(release)).thenReturn(CompletableFuture.completedFuture(UpdateService.DownloadResult.SUCCESS));

        LiteralCommandNode<CommandSourceStack> root = createRoot(updateService);
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("update").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("general.working"));
        verify(updateService).download(release);
        verify(admin).sendMessage(messages.get("updates.downloaded", Map.of("latest", "1.1.0")));
    }

    @Test
    void adminUpdateBreakingRequiresConfirmationAndSecondExecutionDownloads() throws Exception {
        MinecraftCommands.clearPendingConfirmationsForTest();
        UpdateService updateService = mock(UpdateService.class);
        UpdateService.Release release = new UpdateService.Release("2.0.0", "url", "hash", "notes [breaking]");
        when(updateService.isUpdatePending()).thenReturn(false);
        when(updateService.checkForUpdate()).thenReturn(CompletableFuture.completedFuture(Optional.of(release)));
        when(updateService.isBreaking(release)).thenReturn(true);
        when(updateService.download(release)).thenReturn(CompletableFuture.completedFuture(UpdateService.DownloadResult.SUCCESS));

        LiteralCommandNode<CommandSourceStack> root = createRoot(updateService);
        Player admin = mock(Player.class);
        UUID adminUuid = UUID.randomUUID();
        when(admin.getUniqueId()).thenReturn(adminUuid);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);

        CommandContext<CommandSourceStack> ctx = createContext(admin);

        // 1st run: prompts for confirmation, never stages automatically
        root.getChild("admin").getChild("update").getCommand().run(ctx);
        verify(admin).sendMessage(messages.get("general.working"));
        verify(admin).sendMessage(messages.get("updates.confirm-breaking", Map.of("latest", "2.0.0")));
        verify(updateService, never()).download(any());

        // 2nd run: confirmed, downloads
        root.getChild("admin").getChild("update").getCommand().run(ctx);
        verify(updateService, times(1)).download(release);
        verify(admin).sendMessage(messages.get("updates.downloaded", Map.of("latest", "2.0.0")));
    }

    @Test
    void adminUpdateConfirmDownloadsWhenAwaitingConfirmation() throws Exception {
        MinecraftCommands.clearPendingConfirmationsForTest();
        UpdateService updateService = mock(UpdateService.class);
        UpdateService.Release release = new UpdateService.Release("2.0.0", "url", "hash", "notes [breaking]");
        when(updateService.isUpdatePending()).thenReturn(false);
        when(updateService.getAvailableUpdate()).thenReturn(Optional.of(release));
        when(updateService.isBreaking(release)).thenReturn(true);
        when(updateService.download(release)).thenReturn(CompletableFuture.completedFuture(UpdateService.DownloadResult.SUCCESS));

        LiteralCommandNode<CommandSourceStack> root = createRoot(updateService);
        Player admin = mock(Player.class);
        UUID adminUuid = UUID.randomUUID();
        when(admin.getUniqueId()).thenReturn(adminUuid);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("update").getChild("confirm").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("general.working"));
        verify(updateService).download(release);
        verify(admin).sendMessage(messages.get("updates.downloaded", Map.of("latest", "2.0.0")));
    }

    @Test
    void adminUpdateConfirmReportsNoConfirmationNeededWhenNoneAwaiting() throws Exception {
        UpdateService updateService = mock(UpdateService.class);
        when(updateService.isUpdatePending()).thenReturn(false);
        when(updateService.getAvailableUpdate()).thenReturn(Optional.empty());

        LiteralCommandNode<CommandSourceStack> root = createRoot(updateService);
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("update").getChild("confirm").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("updates.no-confirmation-needed"));
        verify(updateService, never()).download(any());
    }

    @Test
    void adminUpdateStatusReportsCurrentAndAvailableReleaseWithBreakingWarning() throws Exception {
        UpdateService updateService = mock(UpdateService.class);
        UpdateService.Release release = new UpdateService.Release("2.0.0", "url", "hash", "Major overhaul [breaking]\nNew features");
        when(updateService.currentVersion()).thenReturn("1.0.0");
        when(updateService.isUpdatePending()).thenReturn(false);
        when(updateService.getAvailableUpdate()).thenReturn(Optional.of(release));
        when(updateService.isBreaking(release)).thenReturn(true);

        LiteralCommandNode<CommandSourceStack> root = createRoot(updateService);
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("update").getChild("status").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("updates.status-current", Map.of("current", "1.0.0")));
        verify(admin).sendMessage(messages.get("updates.available", Map.of("latest", "2.0.0", "current", "1.0.0")));
        verify(admin).sendMessage(messages.get("updates.breaking", Map.of("latest", "2.0.0")));
        verify(admin).sendMessage(messages.get("updates.summary", Map.of("summary", "Major overhaul New features")));
    }

    @Test
    void adminUpdateStatusReportsUpToDateWhenNoUpdateAvailable() throws Exception {
        UpdateService updateService = mock(UpdateService.class);
        when(updateService.currentVersion()).thenReturn("1.0.0");
        when(updateService.isUpdatePending()).thenReturn(false);
        when(updateService.getAvailableUpdate()).thenReturn(Optional.empty());

        LiteralCommandNode<CommandSourceStack> root = createRoot(updateService);
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("update").getChild("status").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("updates.status-current", Map.of("current", "1.0.0")));
        verify(admin).sendMessage(messages.get("updates.up-to-date"));
    }

    @Test
    void adminUpdateStatusReportsPendingDownload() throws Exception {
        UpdateService updateService = mock(UpdateService.class);
        UpdateService.Release release = new UpdateService.Release("1.5.0", "url", "hash", "notes");
        when(updateService.currentVersion()).thenReturn("1.0.0");
        when(updateService.isUpdatePending()).thenReturn(true);
        when(updateService.getAvailableUpdate()).thenReturn(Optional.of(release));

        LiteralCommandNode<CommandSourceStack> root = createRoot(updateService);
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("update").getChild("status").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("updates.status-current", Map.of("current", "1.0.0")));
        verify(admin).sendMessage(messages.get("updates.downloaded", Map.of("latest", "1.5.0")));
    }

    @Test
    void adminUpdateChecksumMismatchReportsError() throws Exception {
        UpdateService updateService = mock(UpdateService.class);
        UpdateService.Release release = new UpdateService.Release("1.1.0", "url", "hash", "notes");
        when(updateService.isUpdatePending()).thenReturn(false);
        when(updateService.checkForUpdate()).thenReturn(CompletableFuture.completedFuture(Optional.of(release)));
        when(updateService.isBreaking(release)).thenReturn(false);
        when(updateService.download(release)).thenReturn(CompletableFuture.completedFuture(UpdateService.DownloadResult.CHECKSUM_MISMATCH));

        LiteralCommandNode<CommandSourceStack> root = createRoot(updateService);
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("update").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("updates.checksum-mismatch"));
    }

    @Test
    void adminUpdateDownloadFailureReportsError() throws Exception {
        UpdateService updateService = mock(UpdateService.class);
        UpdateService.Release release = new UpdateService.Release("1.1.0", "url", "hash", "notes");
        when(updateService.isUpdatePending()).thenReturn(false);
        when(updateService.checkForUpdate()).thenReturn(CompletableFuture.completedFuture(Optional.of(release)));
        when(updateService.isBreaking(release)).thenReturn(false);
        when(updateService.download(release)).thenReturn(CompletableFuture.completedFuture(UpdateService.DownloadResult.NETWORK_ERROR));

        LiteralCommandNode<CommandSourceStack> root = createRoot(updateService);
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("update").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("updates.download-failed", Map.of("reason", "error de red")));
    }

    @Test
    void adminListWithMissingRoleCountUsesLocalizedNotApplicable() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        TownSpace space = new TownSpace(UUID.randomUUID(), "Rome", Optional.of("cat"),
                Optional.of("txt"), Optional.of("vc"), Optional.empty(),
                SpaceState.ACTIVE, Instant.now(), Optional.empty(), Optional.empty());
        when(spaceService.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(space)));

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("list").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("admin.list-entry", Map.of(
                "town", "Rome",
                "status", "activo",
                "channels", "texto voz",
                "residents", "N/A",
                "activity", "sin registrar"
        )));
    }

    @Test
    void adminReloadWithNullMessageUsesLocalizedUnknownReason() throws Exception {
        Runnable failingReload = () -> {
            throw new RuntimeException((String) null);
        };
        LiteralCommandNode<CommandSourceStack> root = MinecraftCommands.createCommandNode(
                linkService, spaceService, syncService, townyFacade, discordGateway,
                config, messages, consoleMessages, failingReload, Runnable::run
        );
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("reload").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("admin.reload-failed", Map.of("reason", "desconocido")));
    }

    @Test
    void adminReloadForConsoleWithNullMessageUsesEnglishUnknownReason() throws Exception {
        Runnable failingReload = () -> {
            throw new RuntimeException((String) null);
        };
        LiteralCommandNode<CommandSourceStack> root = MinecraftCommands.createCommandNode(
                linkService, spaceService, syncService, townyFacade, discordGateway,
                config, messages, consoleMessages, failingReload, Runnable::run
        );
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission("discordtowny.admin")).thenReturn(true);

        CommandContext<CommandSourceStack> ctx = createContext(console);
        root.getChild("admin").getChild("reload").getCommand().run(ctx);

        verify(console).sendMessage(consoleMessages.get("admin.reload-failed", Map.of("reason", "unknown")));
    }

    @Test
    void adminReloadUpdatesBukkitCommandHelpDescription() throws Exception {
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        org.bukkit.command.CommandMap commandMap = mock(org.bukkit.command.CommandMap.class);
        org.bukkit.command.Command dtCommand = mock(org.bukkit.command.Command.class);
        org.bukkit.command.Command aliasCommand = mock(org.bukkit.command.Command.class);

        when(server.getCommandMap()).thenReturn(commandMap);
        when(commandMap.getCommand("dt")).thenReturn(dtCommand);
        when(commandMap.getCommand("discordtowny")).thenReturn(aliasCommand);

        try (var bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);
            bukkitMock.when(Bukkit::getCommandMap).thenReturn(commandMap);

            LiteralCommandNode<CommandSourceStack> root = createRoot();
            Player admin = mock(Player.class);
            when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
            when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

            CommandContext<CommandSourceStack> ctx = createContext(admin);
            root.getChild("admin").getChild("reload").getCommand().run(ctx);

            verify(dtCommand).setDescription("Comandos de DiscordTowny dentro del juego");
            verify(aliasCommand).setDescription("Comandos de DiscordTowny dentro del juego");
        }
    }

    @Test
    void everyMessageKeyTheCodeUsesExistsInBothCatalogs() throws Exception {
        YamlConfiguration en = new YamlConfiguration();
        try (InputStream in = getClass().getResourceAsStream("/messages_en.yml")) {
            assertNotNull(in, "messages_en.yml must exist on classpath");
            en.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        YamlConfiguration es = new YamlConfiguration();
        try (InputStream in = getClass().getResourceAsStream("/messages_es.yml")) {
            assertNotNull(in, "messages_es.yml must exist on classpath");
            es.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        // The keys are read from the source rather than listed by hand. A list
        // written by hand fails for keys nobody uses and, worse, stays silent
        // about a key that is used and was never added — which is exactly how
        // 39 keys once reached a release resolving to "[missing message: ...]".
        java.util.regex.Pattern call = java.util.regex.Pattern.compile(
                "(?:get|plain|label)\\(\\s*\"([a-z][a-z0-9.-]+)\"");
        java.nio.file.Path sources = java.nio.file.Path.of("src", "main", "java");
        assertTrue(java.nio.file.Files.isDirectory(sources), "sources must be readable from the test working directory");

        java.util.Set<String> used = new java.util.TreeSet<>();
        try (var walk = java.nio.file.Files.walk(sources)) {
            for (java.nio.file.Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                var matcher = call.matcher(java.nio.file.Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    if (matcher.group(1).contains(".")) {
                        used.add(matcher.group(1));
                    }
                }
            }
        }
        assertFalse(used.isEmpty(), "the scan must find keys, otherwise it proves nothing");

        for (String key : used) {
            assertTrue(en.isString(key) && !en.getString(key, "").isBlank(),
                    "Key used in code but missing from messages_en.yml: " + key);
            assertTrue(es.isString(key) && !es.getString(key, "").isBlank(),
                    "Key used in code but missing from messages_es.yml: " + key);
        }
    }

    @Test
    void playerCommandsDeniedWhenLackingUsePermission() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();

        // /dt help without any permission refuses
        Player unprivileged = mock(Player.class);
        when(unprivileged.getUniqueId()).thenReturn(UUID.randomUUID());
        when(unprivileged.hasPermission("discordtowny.use")).thenReturn(false);
        when(unprivileged.hasPermission("discordtowny.admin")).thenReturn(false);
        CommandContext<CommandSourceStack> unprivilegedCtx = createContext(unprivileged);

        root.getChild("help").getCommand().run(unprivilegedCtx);
        verify(unprivileged).sendMessage(messages.get("general.no-permission"));
        verify(unprivileged, never()).sendMessage(messages.get("help.header"));

        // Player commands refuse and never invoke services
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission("discordtowny.use")).thenReturn(false);
        CommandContext<CommandSourceStack> ctx = createContext(player);

        root.getChild("status").getCommand().run(ctx);
        root.getChild("link").getCommand().run(ctx);
        root.getChild("unlink").getCommand().run(ctx);
        root.getChild("create").getCommand().run(ctx);
        root.getChild("delete").getCommand().run(ctx);
        root.getChild("sync").getCommand().run(ctx);

        verify(player, times(6)).sendMessage(messages.get("general.no-permission"));
        verify(linkService, never()).findByUuid(any());
        verify(linkService, never()).generateCode(any(), any());
        verify(linkService, never()).unlink(any());
        verify(spaceService, never()).create(any());
        verify(spaceService, never()).archive(any(), any());
        verify(syncService, never()).syncTown(any());
    }

    @Test
    void adminPermissionDoesNotImplyUsePermissionForPlayerCommands() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = createRoot();

        Player admin = mock(Player.class);
        UUID adminUuid = UUID.randomUUID();
        when(admin.getUniqueId()).thenReturn(adminUuid);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.hasPermission("discordtowny.use")).thenReturn(false);

        CommandContext<CommandSourceStack> ctx = createContext(admin);

        // /dt help refuses for player lacking discordtowny.use, even if holding admin
        root.getChild("help").getCommand().run(ctx);
        verify(admin, never()).sendMessage(messages.get("help.header"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-help"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-admin-sync"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-admin-unlink"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-admin-reload"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-admin-list"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-admin-info"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-admin-purge"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-admin-update"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-admin-update-status"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-admin-update-confirm"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-link"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-unlink"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-status"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-create"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-delete"));
        verify(admin, never()).sendMessage(messages.get("help.cmd-sync"));

        // Player commands refuse for admin lacking discordtowny.use
        root.getChild("status").getCommand().run(ctx);
        root.getChild("link").getCommand().run(ctx);
        root.getChild("unlink").getCommand().run(ctx);
        root.getChild("create").getCommand().run(ctx);
        root.getChild("delete").getCommand().run(ctx);
        root.getChild("sync").getCommand().run(ctx);

        verify(admin, times(7)).sendMessage(messages.get("general.no-permission"));
        verify(spaceService, never()).create(any());
        verify(spaceService, never()).archive(any(), any());
        verify(syncService, never()).syncTown(any());

        // Admin command still works as expected
        when(spaceService.findAll()).thenReturn(CompletableFuture.completedFuture(List.of()));
        root.getChild("admin").getChild("list").getCommand().run(ctx);
        verify(admin).sendMessage(messages.get("admin.list-empty"));
    }

    @Test
    void paperPluginYmlDeclaresPermissionNodesWithCorrectDefaults() throws Exception {
        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try (InputStream in = getClass().getResourceAsStream("/paper-plugin.yml")) {
            assertNotNull(in, "paper-plugin.yml must exist on classpath");
            root = yaml.load(in);
        }

        assertNotNull(root, "paper-plugin.yml must parse to a map");
        Object permissionsObj = root.get("permissions");
        assertInstanceOf(Map.class, permissionsObj, "paper-plugin.yml must have a permissions section");

        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) permissionsObj;

        // Raw keys must be literal node names, not nested under "discordtowny"
        assertTrue(permissions.containsKey("discordtowny.use"), "permissions must contain raw key discordtowny.use");
        assertTrue(permissions.containsKey("discordtowny.admin"), "permissions must contain raw key discordtowny.admin");
        assertFalse(permissions.containsKey("discordtowny"), "permissions must not have a nested 'discordtowny' section");
        assertEquals(Set.of("discordtowny.use", "discordtowny.admin"), permissions.keySet(),
                "permissions must only declare discordtowny.use and discordtowny.admin");

        // discordtowny.use
        assertInstanceOf(Map.class, permissions.get("discordtowny.use"), "discordtowny.use must be a mapping");
        @SuppressWarnings("unchecked")
        Map<String, Object> useNode = (Map<String, Object>) permissions.get("discordtowny.use");
        assertEquals(Boolean.TRUE, useNode.get("default"), "discordtowny.use default must be boolean true");
        assertNotNull(useNode.get("description"), "description must not be null");
        assertFalse(String.valueOf(useNode.get("description")).isBlank(), "description must not be blank");
        assertNull(useNode.get("children"), "discordtowny.use must not define children");

        // discordtowny.admin
        assertInstanceOf(Map.class, permissions.get("discordtowny.admin"), "discordtowny.admin must be a mapping");
        @SuppressWarnings("unchecked")
        Map<String, Object> adminNode = (Map<String, Object>) permissions.get("discordtowny.admin");
        assertEquals("op", adminNode.get("default"), "discordtowny.admin default must be 'op'");
        assertNotNull(adminNode.get("description"), "description must not be null");
        assertFalse(String.valueOf(adminNode.get("description")).isBlank(), "description must not be blank");
        assertNull(adminNode.get("children"), "discordtowny.admin must not define children");
    }
}
