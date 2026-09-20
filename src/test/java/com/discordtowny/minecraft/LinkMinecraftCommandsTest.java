package com.discordtowny.minecraft;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.link.LinkService;
import com.discordtowny.towny.TownyFacade;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * Unit tests for Brigadier command definition in {@link LinkMinecraftCommands}.
 */
class LinkMinecraftCommandsTest {

    private LinkService linkService;
    private PluginConfig config;
    private Messages messages;
    private TownyFacade townyFacade;

    @BeforeEach
    void setUp() {
        linkService = mock(LinkService.class);
        messages = mock(Messages.class);
        townyFacade = mock(TownyFacade.class);

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
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );
    }

    @Test
    void commandTreeContainsCorrectStructure() {
        LiteralCommandNode<CommandSourceStack> root = LinkMinecraftCommands.createCommandNode(
                linkService, config, messages, townyFacade);

        assertEquals("dt", root.getName());

        // Verify subcommands /dt link and /dt unlink
        CommandNode<CommandSourceStack> linkNode = root.getChild("link");
        assertNotNull(linkNode, "Subcommand link must exist");

        CommandNode<CommandSourceStack> unlinkNode = root.getChild("unlink");
        assertNotNull(unlinkNode, "Subcommand unlink must exist");

        // Verify /dt admin unlink <jugador>
        CommandNode<CommandSourceStack> adminNode = root.getChild("admin");
        assertNotNull(adminNode, "Subcommand admin must exist");

        CommandNode<CommandSourceStack> adminUnlink = adminNode.getChild("unlink");
        assertNotNull(adminUnlink, "Subcommand admin unlink must exist");

        CommandNode<CommandSourceStack> playerArg = adminUnlink.getChild("jugador");
        assertNotNull(playerArg, "Argument <jugador> must exist");
    }

    @Test
    @SuppressWarnings("unchecked")
    void asynchronousResponsesAreScheduledOnMainThreadScheduler() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.function.Consumer<Runnable> scheduler = task -> {
            scheduled.set(true);
            task.run();
        };

        LiteralCommandNode<CommandSourceStack> root = LinkMinecraftCommands.createCommandNode(
                linkService, config, messages, townyFacade, scheduler);

        CommandSourceStack source = mock(CommandSourceStack.class);
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        when(source.getSender()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

        CompletableFuture<Optional<String>> asyncFuture = new CompletableFuture<>();
        when(linkService.generateCode(any(), any())).thenReturn(asyncFuture);

        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx = mock(com.mojang.brigadier.context.CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("link").getCommand().run(ctx);

        assertFalse(scheduled.get(), "Scheduler must not have been invoked before completing future");

        // Complete the future from another pool thread
        CompletableFuture.runAsync(() -> asyncFuture.complete(Optional.of("ABC234"))).join();

        assertTrue(scheduled.get(), "Response must be scheduled on the scheduler upon completion");
    }

    @Test
    @SuppressWarnings("unchecked")
    void asynchronousErrorsAreScheduledOnMainThreadScheduler() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.function.Consumer<Runnable> scheduler = task -> {
            scheduled.set(true);
            task.run();
        };

        LiteralCommandNode<CommandSourceStack> root = LinkMinecraftCommands.createCommandNode(
                linkService, config, messages, townyFacade, scheduler);

        CommandSourceStack source = mock(CommandSourceStack.class);
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        when(source.getSender()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

        CompletableFuture<Boolean> asyncFuture = new CompletableFuture<>();
        when(linkService.unlink(any())).thenReturn(asyncFuture);

        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx = mock(com.mojang.brigadier.context.CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("unlink").getCommand().run(ctx);

        assertFalse(scheduled.get(), "Scheduler must not have been invoked before error");

        // Complete exceptionally from another thread
        CompletableFuture.runAsync(() -> asyncFuture.completeExceptionally(new RuntimeException("Error simulado"))).join();

        assertTrue(scheduled.get(), "Error handler must be scheduled on the scheduler");
    }

    @Test
    @SuppressWarnings("unchecked")
    void consoleSenderRepliesInEnglishForLinkAndUnlink() throws Exception {
        Messages consoleMessages = mock(Messages.class);
        net.kyori.adventure.text.Component englishPlayersOnly = net.kyori.adventure.text.Component.text("This command only works in-game.");
        when(consoleMessages.get("general.players-only")).thenReturn(englishPlayersOnly);

        net.kyori.adventure.text.Component spanishPlayersOnly = net.kyori.adventure.text.Component.text("Este comando solo funciona dentro del juego.");
        when(messages.get("general.players-only")).thenReturn(spanishPlayersOnly);

        LiteralCommandNode<CommandSourceStack> root = LinkMinecraftCommands.createCommandNode(
                linkService, config, messages, consoleMessages, townyFacade, Runnable::run);

        org.bukkit.command.CommandSender console = mock(org.bukkit.command.ConsoleCommandSender.class);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(console);

        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx = mock(com.mojang.brigadier.context.CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        // /dt link as console
        root.getChild("link").getCommand().run(ctx);
        verify(console, times(1)).sendMessage(englishPlayersOnly);
        verify(console, never()).sendMessage(spanishPlayersOnly);

        // /dt unlink as console
        root.getChild("unlink").getCommand().run(ctx);
        verify(console, times(2)).sendMessage(englishPlayersOnly);
    }

    @Test
    @SuppressWarnings("unchecked")
    void consoleSenderRepliesInEnglishForAdminUnlink() throws Exception {
        Messages consoleMessages = mock(Messages.class);
        net.kyori.adventure.text.Component englishWorking = net.kyori.adventure.text.Component.text("Working on it...");
        net.kyori.adventure.text.Component englishUnlinked = net.kyori.adventure.text.Component.text("Steve no longer has a linked account.");
        when(consoleMessages.get("general.working")).thenReturn(englishWorking);
        when(consoleMessages.get(eq("admin.unlinked"), any())).thenReturn(englishUnlinked);

        UUID targetUuid = UUID.randomUUID();
        com.discordtowny.model.ResidentSnapshot resident = mock(com.discordtowny.model.ResidentSnapshot.class);
        when(resident.uuid()).thenReturn(targetUuid);
        when(townyFacade.isAvailable()).thenReturn(true);
        when(townyFacade.residentByName("Steve")).thenReturn(Optional.of(resident));
        when(linkService.unlink(targetUuid)).thenReturn(CompletableFuture.completedFuture(true));

        LiteralCommandNode<CommandSourceStack> root = LinkMinecraftCommands.createCommandNode(
                linkService, config, messages, consoleMessages, townyFacade, Runnable::run,
                name -> "Steve".equals(name) ? targetUuid : null);

        org.bukkit.command.CommandSender console = mock(org.bukkit.command.ConsoleCommandSender.class);
        when(console.hasPermission("discordtowny.admin")).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(console);

        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx = mock(com.mojang.brigadier.context.CommandContext.class);
        when(ctx.getSource()).thenReturn(source);
        when(ctx.getArgument("jugador", String.class)).thenReturn("Steve");

        root.getChild("admin").getChild("unlink").getChild("jugador").getCommand().run(ctx);

        verify(console, times(1)).sendMessage(englishWorking);
        verify(console, times(1)).sendMessage(englishUnlinked);
        verify(messages, never()).get(eq("admin.unlinked"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void playerSenderRepliesInConfiguredLanguageForAdminUnlink() throws Exception {
        Messages consoleMessages = mock(Messages.class);
        net.kyori.adventure.text.Component spanishWorking = net.kyori.adventure.text.Component.text("Trabajando en ello...");
        net.kyori.adventure.text.Component spanishUnlinked = net.kyori.adventure.text.Component.text("Steve ya no tiene cuenta vinculada.");
        when(messages.get("general.working")).thenReturn(spanishWorking);
        when(messages.get(eq("admin.unlinked"), any())).thenReturn(spanishUnlinked);

        UUID targetUuid = UUID.randomUUID();
        com.discordtowny.model.ResidentSnapshot resident = mock(com.discordtowny.model.ResidentSnapshot.class);
        when(resident.uuid()).thenReturn(targetUuid);
        when(townyFacade.isAvailable()).thenReturn(true);
        when(townyFacade.residentByName("Steve")).thenReturn(Optional.of(resident));
        when(linkService.unlink(targetUuid)).thenReturn(CompletableFuture.completedFuture(true));

        LiteralCommandNode<CommandSourceStack> root = LinkMinecraftCommands.createCommandNode(
                linkService, config, messages, consoleMessages, townyFacade, Runnable::run,
                name -> "Steve".equals(name) ? targetUuid : null);

        org.bukkit.entity.Player adminPlayer = mock(org.bukkit.entity.Player.class);
        when(adminPlayer.hasPermission("discordtowny.admin")).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(adminPlayer);

        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx = mock(com.mojang.brigadier.context.CommandContext.class);
        when(ctx.getSource()).thenReturn(source);
        when(ctx.getArgument("jugador", String.class)).thenReturn("Steve");

        root.getChild("admin").getChild("unlink").getChild("jugador").getCommand().run(ctx);

        verify(adminPlayer, times(1)).sendMessage(spanishWorking);
        verify(adminPlayer, times(1)).sendMessage(spanishUnlinked);
        verify(consoleMessages, never()).get(eq("admin.unlinked"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void linkAndUnlinkRefuseWhenMissingUsePermission() throws Exception {
        net.kyori.adventure.text.Component noPerm = net.kyori.adventure.text.Component.text("No permission");
        when(messages.get("general.no-permission")).thenReturn(noPerm);

        LiteralCommandNode<CommandSourceStack> root = LinkMinecraftCommands.createCommandNode(
                linkService, config, messages, townyFacade);

        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        when(player.hasPermission("discordtowny.use")).thenReturn(false);
        when(player.hasPermission("discordtowny.admin")).thenReturn(false);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);

        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx = mock(com.mojang.brigadier.context.CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        // /dt link refused
        root.getChild("link").getCommand().run(ctx);
        verify(player, times(1)).sendMessage(noPerm);
        verify(linkService, never()).generateCode(any(), any());

        // /dt unlink refused
        root.getChild("unlink").getCommand().run(ctx);
        verify(player, times(2)).sendMessage(noPerm);
        verify(linkService, never()).unlink(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminPermissionDoesNotGrantLinkOrUnlink() throws Exception {
        net.kyori.adventure.text.Component noPerm = net.kyori.adventure.text.Component.text("No permission");
        when(messages.get("general.no-permission")).thenReturn(noPerm);

        LiteralCommandNode<CommandSourceStack> root = LinkMinecraftCommands.createCommandNode(
                linkService, config, messages, townyFacade);

        org.bukkit.entity.Player adminOnly = mock(org.bukkit.entity.Player.class);
        when(adminOnly.hasPermission("discordtowny.admin")).thenReturn(true);
        when(adminOnly.hasPermission("discordtowny.use")).thenReturn(false);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(adminOnly);

        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx = mock(com.mojang.brigadier.context.CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        // /dt link refused despite admin permission
        root.getChild("link").getCommand().run(ctx);
        verify(adminOnly, times(1)).sendMessage(noPerm);
        verify(linkService, never()).generateCode(any(), any());

        // /dt unlink refused despite admin permission
        root.getChild("unlink").getCommand().run(ctx);
        verify(adminOnly, times(2)).sendMessage(noPerm);
        verify(linkService, never()).unlink(any());
    }
}
