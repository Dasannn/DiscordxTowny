package com.discordtowny.minecraft;

import com.discordtowny.config.Messages;
import com.discordtowny.model.TownSnapshot;
import com.discordtowny.sync.SyncService;
import com.discordtowny.towny.TownyFacade;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Brigadier command definitions in {@link SyncMinecraftCommands}.
 */
class SyncMinecraftCommandsTest {

    private SyncService syncService;
    private Messages messages;
    private TownyFacade townyFacade;

    @BeforeEach
    void setUp() {
        syncService = mock(SyncService.class);
        messages = mock(Messages.class);
        townyFacade = mock(TownyFacade.class);

        when(messages.get(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return Component.text(key != null ? key : "");
        });
        when(messages.get(any(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return Component.text(key != null ? key : "");
        });
        when(townyFacade.isAvailable()).thenReturn(true);
    }

    @Test
    void commandTreeContainsSyncAndAdminSyncStructure() {
        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade);

        assertEquals("dt", root.getName());

        // /dt sync
        CommandNode<CommandSourceStack> syncNode = root.getChild("sync");
        assertNotNull(syncNode, "Subcommand /dt sync must exist");

        // /dt admin
        CommandNode<CommandSourceStack> adminNode = root.getChild("admin");
        assertNotNull(adminNode, "Subcommand /dt admin must exist");

        // /dt admin sync
        CommandNode<CommandSourceStack> adminSyncNode = adminNode.getChild("sync");
        assertNotNull(adminSyncNode, "Subcommand /dt admin sync must exist");

        // /dt admin sync <town>
        CommandNode<CommandSourceStack> townArgNode = adminSyncNode.getChild("town");
        assertNotNull(townArgNode, "Argument <town> in /dt admin sync <town> must exist");
    }

    @Test
    void dtSyncFailsForConsoleSender() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade);

        CommandSourceStack source = mock(CommandSourceStack.class);
        CommandSender console = mock(CommandSender.class);
        when(source.getSender()).thenReturn(console);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("sync").getCommand().run(ctx);

        verify(console, times(1)).sendMessage(messages.get("general.players-only"));
        verify(syncService, never()).syncTown(any());
    }

    @Test
    void dtSyncFailsWhenPlayerNotInTown() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade);

        CommandSourceStack source = mock(CommandSourceStack.class);
        Player player = mock(Player.class);
        UUID playerUuid = UUID.randomUUID();
        when(source.getSender()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(playerUuid);

        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("sync").getCommand().run(ctx);

        verify(player, times(1)).sendMessage(messages.get("general.not-in-town"));
        verify(syncService, never()).syncTown(any());
    }

    @Test
    void dtSyncFailsWhenPlayerNotMayor() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade);

        CommandSourceStack source = mock(CommandSourceStack.class);
        Player player = mock(Player.class);
        UUID playerUuid = UUID.randomUUID();
        when(source.getSender()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(playerUuid);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.isMayor(playerUuid)).thenReturn(false);
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.of(town));

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("sync").getCommand().run(ctx);

        verify(player, times(1)).sendMessage(messages.get("general.not-mayor"));
        verify(syncService, never()).syncTown(any());
    }

    @Test
    void dtSyncDispatchesTownSyncAndSchedulesResponseOnMainThread() throws Exception {
        AtomicBoolean scheduled = new AtomicBoolean(false);
        Consumer<Runnable> scheduler = task -> {
            scheduled.set(true);
            task.run();
        };

        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade, scheduler);

        CommandSourceStack source = mock(CommandSourceStack.class);
        Player player = mock(Player.class);
        UUID playerUuid = UUID.randomUUID();
        UUID townUuid = UUID.randomUUID();

        when(source.getSender()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(playerUuid);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.isMayor(playerUuid)).thenReturn(true);
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.of(town));

        CompletableFuture<SyncService.SyncReport> asyncFuture = new CompletableFuture<>();
        when(syncService.syncTown(townUuid)).thenReturn(asyncFuture);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        InOrder inOrder = inOrder(player);

        root.getChild("sync").getCommand().run(ctx);

        // Immediate acknowledgment on main thread
        inOrder.verify(player, times(1)).sendMessage(messages.get("sync.started"));
        assertFalse(scheduled.get(), "Scheduler must not run before async future completes");

        // Complete the future off-thread
        SyncService.SyncReport report = new SyncService.SyncReport(1, 2, 0, 0, 0, List.of());
        CompletableFuture.runAsync(() -> asyncFuture.complete(report)).join();

        assertTrue(scheduled.get(), "Scheduler must have been invoked after completion");
        inOrder.verify(player, times(1)).sendMessage(messages.get("sync.finished"));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void dtSyncSchedulesDatabaseErrorResponseOnFailure() throws Exception {
        AtomicBoolean scheduled = new AtomicBoolean(false);
        Consumer<Runnable> scheduler = task -> {
            scheduled.set(true);
            task.run();
        };

        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade, scheduler);

        CommandSourceStack source = mock(CommandSourceStack.class);
        Player player = mock(Player.class);
        UUID playerUuid = UUID.randomUUID();
        UUID townUuid = UUID.randomUUID();

        when(source.getSender()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(playerUuid);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.isMayor(playerUuid)).thenReturn(true);
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.of(town));

        CompletableFuture<SyncService.SyncReport> asyncFuture = new CompletableFuture<>();
        when(syncService.syncTown(townUuid)).thenReturn(asyncFuture);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        InOrder inOrder = inOrder(player);

        root.getChild("sync").getCommand().run(ctx);

        // Immediate acknowledgment on main thread
        inOrder.verify(player, times(1)).sendMessage(messages.get("sync.started"));

        CompletableFuture.runAsync(() -> asyncFuture.completeExceptionally(new RuntimeException("DB offline"))).join();

        assertTrue(scheduled.get());
        inOrder.verify(player, times(1)).sendMessage(messages.get("general.database-unavailable"));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void dtSyncSchedulesDiscordErrorResponseOnDiscordFailure() throws Exception {
        AtomicBoolean scheduled = new AtomicBoolean(false);
        Consumer<Runnable> scheduler = task -> {
            scheduled.set(true);
            task.run();
        };

        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade, scheduler);

        CommandSourceStack source = mock(CommandSourceStack.class);
        Player player = mock(Player.class);
        UUID playerUuid = UUID.randomUUID();
        UUID townUuid = UUID.randomUUID();

        when(source.getSender()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(playerUuid);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.isMayor(playerUuid)).thenReturn(true);
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.of(town));

        CompletableFuture<SyncService.SyncReport> asyncFuture = new CompletableFuture<>();
        when(syncService.syncTown(townUuid)).thenReturn(asyncFuture);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        InOrder inOrder = inOrder(player);

        root.getChild("sync").getCommand().run(ctx);

        // Immediate acknowledgment on main thread
        inOrder.verify(player, times(1)).sendMessage(messages.get("sync.started"));

        CompletableFuture.runAsync(() -> asyncFuture.completeExceptionally(new RuntimeException("Discord gateway unavailable"))).join();

        assertTrue(scheduled.get());
        inOrder.verify(player, times(1)).sendMessage(messages.get("general.discord-unavailable"));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void adminNodeRequiresAdminPermission() {
        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade);

        CommandNode<CommandSourceStack> adminNode = root.getChild("admin");

        CommandSourceStack adminSource = mock(CommandSourceStack.class);
        CommandSender adminSender = mock(CommandSender.class);
        when(adminSource.getSender()).thenReturn(adminSender);
        when(adminSender.hasPermission("discordtowny.admin")).thenReturn(true);

        CommandSourceStack regularSource = mock(CommandSourceStack.class);
        CommandSender regularSender = mock(CommandSender.class);
        when(regularSource.getSender()).thenReturn(regularSender);
        when(regularSender.hasPermission("discordtowny.admin")).thenReturn(false);

        assertTrue(adminNode.canUse(adminSource), "Admin node must be accessible with discordtowny.admin");
        assertFalse(adminNode.canUse(regularSource), "Admin node must be denied without discordtowny.admin");
    }

    @Test
    void dtAdminSyncAllDispatchesReconcileAllAndSchedulesResponse() throws Exception {
        AtomicBoolean scheduled = new AtomicBoolean(false);
        Consumer<Runnable> scheduler = task -> {
            scheduled.set(true);
            task.run();
        };

        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade, scheduler);

        CommandSourceStack source = mock(CommandSourceStack.class);
        CommandSender sender = mock(CommandSender.class);
        when(source.getSender()).thenReturn(sender);

        CompletableFuture<SyncService.SyncReport> asyncFuture = new CompletableFuture<>();
        when(syncService.reconcileAll()).thenReturn(asyncFuture);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        InOrder inOrder = inOrder(sender);

        CommandNode<CommandSourceStack> adminSync = root.getChild("admin").getChild("sync");
        adminSync.getCommand().run(ctx);

        inOrder.verify(sender, times(1)).sendMessage(messages.get("sync.started"));
        verify(syncService, times(1)).reconcileAll();

        SyncService.SyncReport report = new SyncService.SyncReport(5, 3, 1, 0, 0, List.of());
        CompletableFuture.runAsync(() -> asyncFuture.complete(report)).join();

        assertTrue(scheduled.get());
        inOrder.verify(sender, times(1)).sendMessage(messages.get("sync.finished"));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void dtAdminSyncSpecificTownDispatchesTownSync() throws Exception {
        AtomicBoolean scheduled = new AtomicBoolean(false);
        Consumer<Runnable> scheduler = task -> {
            scheduled.set(true);
            task.run();
        };

        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade, scheduler);

        CommandSourceStack source = mock(CommandSourceStack.class);
        CommandSender sender = mock(CommandSender.class);
        when(source.getSender()).thenReturn(sender);

        UUID spartaUuid = UUID.randomUUID();
        TownSnapshot sparta = mock(TownSnapshot.class);
        when(sparta.uuid()).thenReturn(spartaUuid);
        when(townyFacade.townByName("Sparta")).thenReturn(Optional.of(sparta));

        CompletableFuture<SyncService.SyncReport> asyncFuture = new CompletableFuture<>();
        when(syncService.syncTown(spartaUuid)).thenReturn(asyncFuture);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);
        when(ctx.getArgument("town", String.class)).thenReturn("Sparta");

        InOrder inOrder = inOrder(sender);

        CommandNode<CommandSourceStack> townArgNode = root.getChild("admin").getChild("sync").getChild("town");
        townArgNode.getCommand().run(ctx);

        inOrder.verify(sender, times(1)).sendMessage(messages.get("sync.started"));
        verify(syncService, times(1)).syncTown(spartaUuid);

        CompletableFuture.runAsync(() -> asyncFuture.complete(new SyncService.SyncReport(1, 0, 0, 0, 0, List.of()))).join();

        assertTrue(scheduled.get());
        inOrder.verify(sender, times(1)).sendMessage(messages.get("sync.finished"));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void dtAdminSyncUnknownTownRepliesTownNotFound() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade);

        CommandSourceStack source = mock(CommandSourceStack.class);
        CommandSender sender = mock(CommandSender.class);
        when(source.getSender()).thenReturn(sender);

        when(townyFacade.townByName("Atlantis")).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);
        when(ctx.getArgument("town", String.class)).thenReturn("Atlantis");

        CommandNode<CommandSourceStack> townArgNode = root.getChild("admin").getChild("sync").getChild("town");
        townArgNode.getCommand().run(ctx);

        verify(sender, times(1)).sendMessage(messages.get("general.town-not-found", Map.of("town", "Atlantis")));
        verify(syncService, never()).syncTown(any());
        verify(syncService, never()).reconcileAll();
    }
}
