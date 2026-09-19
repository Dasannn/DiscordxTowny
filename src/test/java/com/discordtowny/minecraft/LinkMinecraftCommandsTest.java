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
}
