package com.discordtowny.minecraft;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.model.TownSnapshot;
import com.discordtowny.sync.SyncService;
import com.discordtowny.towny.TownyFacade;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        when(messages.label(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return key != null ? key : "";
        });
        when(messages.label(any(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return key != null ? key : "";
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
        when(messages.get("general.players-only")).thenReturn(Component.text("Solo jugadores"));

        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade);

        CommandSourceStack source = mock(CommandSourceStack.class);
        CommandSender console = mock(CommandSender.class);
        when(source.getSender()).thenReturn(console);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("sync").getCommand().run(ctx);

        verify(console, times(1)).sendMessage(EnglishMessages.bundled().get("general.players-only"));
        verifyNoInteractions(messages);
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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

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

        // No interim acknowledgment before async future completes
        verify(player, never()).sendMessage(any(Component.class));
        assertFalse(scheduled.get(), "Scheduler must not run before async future completes");

        // Complete the future off-thread
        SyncService.SyncReport report = new SyncService.SyncReport(1, 2, 0, 0, 0, List.of());
        CompletableFuture.runAsync(() -> asyncFuture.complete(report)).join();

        assertTrue(scheduled.get(), "Scheduler must have been invoked after completion");
        verify(player, times(1)).sendMessage(any(Component.class));
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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

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

        // No interim acknowledgment before async future completes
        verify(player, never()).sendMessage(any(Component.class));

        CompletableFuture.runAsync(() -> asyncFuture.completeExceptionally(new RuntimeException("DB offline"))).join();

        assertTrue(scheduled.get());
        verify(player, times(1)).sendMessage(any(Component.class));
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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

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

        // No interim acknowledgment before async future completes
        verify(player, never()).sendMessage(any(Component.class));

        CompletableFuture.runAsync(() -> asyncFuture.completeExceptionally(new RuntimeException("Discord gateway unavailable"))).join();

        assertTrue(scheduled.get());
        verify(player, times(1)).sendMessage(any(Component.class));
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
    void dtSyncRefusesWhenMissingUsePermission() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade);

        CommandSourceStack source = mock(CommandSourceStack.class);
        Player player = mock(Player.class);
        when(source.getSender()).thenReturn(player);
        when(player.hasPermission("discordtowny.use")).thenReturn(false);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("sync").getCommand().run(ctx);

        verify(player, times(1)).sendMessage(messages.get("general.no-permission"));
        verify(syncService, never()).syncTown(any());
        verify(syncService, never()).reconcileAll();
    }

    @Test
    void adminPermissionDoesNotGrantDtSync() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade);

        CommandSourceStack source = mock(CommandSourceStack.class);
        Player adminWithoutUse = mock(Player.class);
        when(source.getSender()).thenReturn(adminWithoutUse);
        when(adminWithoutUse.hasPermission("discordtowny.admin")).thenReturn(true);
        when(adminWithoutUse.hasPermission("discordtowny.use")).thenReturn(false);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("sync").getCommand().run(ctx);

        verify(adminWithoutUse, times(1)).sendMessage(messages.get("general.no-permission"));
        verify(syncService, never()).syncTown(any());
        verify(syncService, never()).reconcileAll();
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

        // No interim acknowledgment before async future completes
        verify(sender, never()).sendMessage(any(Component.class));
        verify(syncService, times(1)).reconcileAll();

        SyncService.SyncReport report = new SyncService.SyncReport(5, 3, 1, 0, 0, List.of());
        CompletableFuture.runAsync(() -> asyncFuture.complete(report)).join();

        assertTrue(scheduled.get());
        verify(sender, times(1)).sendMessage(any(Component.class));
        inOrder.verify(sender, times(1)).sendMessage(EnglishMessages.bundled().get("sync.finished"));
        inOrder.verifyNoMoreInteractions();
        verifyNoInteractions(messages);
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

        // No interim acknowledgment before async future completes
        verify(sender, never()).sendMessage(any(Component.class));
        verify(syncService, times(1)).syncTown(spartaUuid);

        CompletableFuture.runAsync(() -> asyncFuture.complete(new SyncService.SyncReport(1, 0, 0, 0, 0, List.of()))).join();

        assertTrue(scheduled.get());
        verify(sender, times(1)).sendMessage(any(Component.class));
        inOrder.verify(sender, times(1)).sendMessage(EnglishMessages.bundled().get("sync.finished"));
        inOrder.verifyNoMoreInteractions();
        verifyNoInteractions(messages);
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

        verify(sender, times(1)).sendMessage(EnglishMessages.bundled().get("general.town-not-found", Map.of("town", "Atlantis")));
        verify(syncService, never()).syncTown(any());
        verify(syncService, never()).reconcileAll();
        verifyNoInteractions(messages);
    }

    private static PluginConfig createConfig(PluginConfig.Sync.Mode mode) {
        return new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty()),
                new PluginConfig.Database(PluginConfig.Database.Type.SQLITE, "localhost", 3306, "db", "", "", "dt_", 1, 1, Duration.ofSeconds(5)),
                new PluginConfig.Structure("Cat", "Arch", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Alcalde", "{town}", Optional.empty(), false),
                new PluginConfig.Limits(200, 2, Duration.ofSeconds(60)),
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                new PluginConfig.Sync(Duration.ofMinutes(30), mode, 20, Duration.ofSeconds(5)),
                new PluginConfig.Linking(Duration.ofMinutes(10), 3, Duration.ofMinutes(15), true),
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );
    }

    @Test
    void dtSyncWithNormallyCompletedReportCarryingFailuresReportsErrorsAndDoesNotAnnounceCleanSuccess() throws Exception {
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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.isMayor(playerUuid)).thenReturn(true);
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.of(town));

        CompletableFuture<SyncService.SyncReport> asyncFuture = new CompletableFuture<>();
        when(syncService.syncTown(townUuid)).thenReturn(asyncFuture);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("sync").getCommand().run(ctx);

        // Normally completed future carrying failures inside the SyncReport
        SyncService.SyncReport failureReport = new SyncService.SyncReport(
                1, 0, 0, 1, 0, List.of(
                        new SyncService.SyncReport.Problem("sync.problem-towny-unavailable"),
                        new SyncService.SyncReport.Problem("sync.problem-grant-mayor-role-failed",
                                Map.of("role", "role-1", "discord", "user-1", "reason", "timeout"))));
        CompletableFuture.runAsync(() -> asyncFuture.complete(failureReport)).join();

        assertTrue(scheduled.get());

        // CRITICAL: Must NEVER send sync.finished when there are failures!
        verify(player, never()).sendMessage(messages.get("sync.finished"));

        // Must report the failure details to the player using message keys
        verify(messages).get(eq("sync.problems-header"), eq(Map.of("count", "2")));
        verify(messages).get(eq("sync.problem-entry"), eq(Map.of("problem", "sync.problem-towny-unavailable")));
        verify(messages).get(eq("sync.problem-entry"), eq(Map.of("problem", "sync.problem-grant-mayor-role-failed")));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(captor.capture());

        List<String> plainTexts = captor.getAllValues().stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                .toList();

        assertTrue(plainTexts.contains("sync.problems-header"),
                "Must report problems header key to sender instead of falsely announcing success");
        assertTrue(plainTexts.contains("sync.problem-entry"),
                "Must include problem entry key from the report");
    }

    @Test
    void dtAdminSyncInReportModeWithDiscrepanciesReportsUntouchedFindingsAndDoesNotAnnounceCleanSuccess() throws Exception {
        AtomicBoolean scheduled = new AtomicBoolean(false);
        Consumer<Runnable> scheduler = task -> {
            scheduled.set(true);
            task.run();
        };

        PluginConfig reportConfig = createConfig(PluginConfig.Sync.Mode.REPORT);

        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, reportConfig, messages, townyFacade, scheduler);

        CommandSourceStack source = mock(CommandSourceStack.class);
        CommandSender sender = mock(CommandSender.class);
        when(source.getSender()).thenReturn(sender);

        CompletableFuture<SyncService.SyncReport> asyncFuture = new CompletableFuture<>();
        when(syncService.reconcileAll()).thenReturn(asyncFuture);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("admin").getChild("sync").getCommand().run(ctx);

        // Report-mode normally completed report: discrepancies found, deliberately untouched (0 repaired)
        SyncService.SyncReport reportModeReport = new SyncService.SyncReport(
                5, 2, 1, 3, 0, List.of());
        CompletableFuture.runAsync(() -> asyncFuture.complete(reportModeReport)).join();

        assertTrue(scheduled.get());

        // CRITICAL: Must NEVER send sync.finished in report mode with discrepancies!
        verify(sender, never()).sendMessage(EnglishMessages.bundled().get("sync.finished"));

        // Must report what was found and deliberately not touched using bundled English messages
        verify(sender).sendMessage(EnglishMessages.bundled().get("sync.report-found", Map.of("count", "3")));
        verify(sender).sendMessage(EnglishMessages.bundled().get("sync.report-pending", Map.of("granted", "2", "revoked", "1")));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());

        List<String> plainTexts = captor.getAllValues().stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                .toList();

        String reportFound = EnglishMessages.bundled().plain("sync.report-found", Map.of("count", "3"));
        String reportPending = EnglishMessages.bundled().plain("sync.report-pending", Map.of("granted", "2", "revoked", "1"));

        assertTrue(plainTexts.contains(reportFound),
                "Must report sync.report-found text in report mode");
        assertTrue(plainTexts.contains(reportPending),
                "Must report sync.report-pending text in report mode");
        verifyNoInteractions(messages);
    }

    @Test
    void dtAdminSyncInReportModeCleanReportsNoDiscrepancies() throws Exception {
        AtomicBoolean scheduled = new AtomicBoolean(false);
        Consumer<Runnable> scheduler = task -> {
            scheduled.set(true);
            task.run();
        };

        PluginConfig reportConfig = createConfig(PluginConfig.Sync.Mode.REPORT);

        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, reportConfig, messages, townyFacade, scheduler);

        CommandSourceStack source = mock(CommandSourceStack.class);
        CommandSender sender = mock(CommandSender.class);
        when(source.getSender()).thenReturn(sender);

        CompletableFuture<SyncService.SyncReport> asyncFuture = new CompletableFuture<>();
        when(syncService.reconcileAll()).thenReturn(asyncFuture);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("admin").getChild("sync").getCommand().run(ctx);

        SyncService.SyncReport cleanReport = new SyncService.SyncReport(4, 0, 0, 0, 0, List.of());
        CompletableFuture.runAsync(() -> asyncFuture.complete(cleanReport)).join();

        assertTrue(scheduled.get());

        verify(sender, never()).sendMessage(EnglishMessages.bundled().get("sync.finished"));
        verify(sender).sendMessage(EnglishMessages.bundled().get("sync.report-clean", Map.of("spaces", "4")));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());

        List<String> plainTexts = captor.getAllValues().stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                .toList();

        String reportClean = EnglishMessages.bundled().plain("sync.report-clean", Map.of("spaces", "4"));
        assertTrue(plainTexts.contains(reportClean),
                "Must report sync.report-clean text in clean report mode");
        verifyNoInteractions(messages);
    }

    @Test
    void dtSyncWithUnrepairedInconsistenciesReportsUnrepairedKey() throws Exception {
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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.isMayor(playerUuid)).thenReturn(true);
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.of(town));

        CompletableFuture<SyncService.SyncReport> asyncFuture = new CompletableFuture<>();
        when(syncService.syncTown(townUuid)).thenReturn(asyncFuture);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("sync").getCommand().run(ctx);

        SyncService.SyncReport unrepairedReport = new SyncService.SyncReport(1, 0, 0, 3, 0, List.of());
        CompletableFuture.runAsync(() -> asyncFuture.complete(unrepairedReport)).join();

        assertTrue(scheduled.get());

        verify(player, never()).sendMessage(messages.get("sync.finished"));
        verify(messages).get(eq("sync.unrepaired"), eq(Map.of("count", "3")));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(captor.capture());

        List<String> plainTexts = captor.getAllValues().stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                .toList();

        assertTrue(plainTexts.contains("sync.unrepaired"),
                "Must report sync.unrepaired key when inconsistencies remain unrepaired");
    }

    @Test
    void dtSyncWithRepairedInconsistenciesAndFailuresReportsRepairedKey() throws Exception {
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
        when(player.hasPermission("discordtowny.use")).thenReturn(true);

        TownSnapshot town = mock(TownSnapshot.class);
        when(town.uuid()).thenReturn(townUuid);
        when(town.isMayor(playerUuid)).thenReturn(true);
        when(townyFacade.townOf(playerUuid)).thenReturn(Optional.of(town));

        CompletableFuture<SyncService.SyncReport> asyncFuture = new CompletableFuture<>();
        when(syncService.syncTown(townUuid)).thenReturn(asyncFuture);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("sync").getCommand().run(ctx);

        SyncService.SyncReport partialReport = new SyncService.SyncReport(
                1, 2, 1, 3, 2, List.of(
                        new SyncService.SyncReport.Problem(
                                "sync.problem-revoke-role-failed",
                                Map.of("role", "role-1", "discord", "user-1", "reason", "error"))));
        CompletableFuture.runAsync(() -> asyncFuture.complete(partialReport)).join();

        assertTrue(scheduled.get());

        verify(player, never()).sendMessage(messages.get("sync.finished"));
        verify(messages).get(eq("sync.repaired"), eq(Map.of("count", "2", "granted", "2", "revoked", "1")));
        verify(messages).get(eq("sync.problems-header"), eq(Map.of("count", "1")));
        verify(messages).get(eq("sync.problem-entry"), eq(Map.of("problem", "sync.problem-revoke-role-failed")));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(captor.capture());

        List<String> plainTexts = captor.getAllValues().stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                .toList();

        assertTrue(plainTexts.contains("sync.repaired"),
                "Must report sync.repaired key when inconsistencies are repaired");
        assertTrue(plainTexts.contains("sync.problems-header"),
                "Must report sync.problems-header key on failure");
        assertTrue(plainTexts.contains("sync.problem-entry"),
                "Must report sync.problem-entry key for problems");
    }

    @Test
    void dtAdminSyncInReportModeWithProblemsReportsProblemKeys() throws Exception {
        AtomicBoolean scheduled = new AtomicBoolean(false);
        Consumer<Runnable> scheduler = task -> {
            scheduled.set(true);
            task.run();
        };

        PluginConfig reportConfig = createConfig(PluginConfig.Sync.Mode.REPORT);

        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, reportConfig, messages, townyFacade, scheduler);

        CommandSourceStack source = mock(CommandSourceStack.class);
        CommandSender sender = mock(CommandSender.class);
        when(source.getSender()).thenReturn(sender);

        CompletableFuture<SyncService.SyncReport> asyncFuture = new CompletableFuture<>();
        when(syncService.reconcileAll()).thenReturn(asyncFuture);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        root.getChild("admin").getChild("sync").getCommand().run(ctx);

        SyncService.SyncReport.Problem problem = new SyncService.SyncReport.Problem(
                "sync.problem-batch-failed", Map.of("batch", "1", "error", "Discord rate limit"));
        SyncService.SyncReport reportWithProblem = new SyncService.SyncReport(
                2, 0, 0, 0, 0, List.of(problem));
        CompletableFuture.runAsync(() -> asyncFuture.complete(reportWithProblem)).join();

        assertTrue(scheduled.get());

        String renderedProblem = "Batch 1 failed: Discord rate limit";
        verify(sender, never()).sendMessage(EnglishMessages.bundled().get("sync.finished"));
        verify(sender).sendMessage(EnglishMessages.bundled().get("sync.report-clean", Map.of("spaces", "2")));
        verify(sender).sendMessage(EnglishMessages.bundled().get("sync.problems-header", Map.of("count", "1")));
        verify(sender).sendMessage(EnglishMessages.bundled().get("sync.problem-entry", Map.of("problem", renderedProblem)));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());

        List<String> plainTexts = captor.getAllValues().stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                .toList();

        String reportClean = EnglishMessages.bundled().plain("sync.report-clean", Map.of("spaces", "2"));
        String problemsHeader = EnglishMessages.bundled().plain("sync.problems-header", Map.of("count", "1"));
        String problemEntry = EnglishMessages.bundled().plain("sync.problem-entry", Map.of("problem", renderedProblem));

        assertTrue(plainTexts.contains(reportClean));
        assertTrue(plainTexts.contains(problemsHeader));
        assertTrue(plainTexts.contains(problemEntry));
        verifyNoInteractions(messages);
    }

    @Test
    void suggestionsCheckPrimaryThreadFirstAndNeverCallFacadeOffThread() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade);

        @SuppressWarnings("unchecked")
        com.mojang.brigadier.tree.ArgumentCommandNode<CommandSourceStack, String> townArgNode =
                (com.mojang.brigadier.tree.ArgumentCommandNode<CommandSourceStack, String>)
                        root.getChild("admin").getChild("sync").getChild("town");

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        com.mojang.brigadier.suggestion.SuggestionsBuilder builder =
                new com.mojang.brigadier.suggestion.SuggestionsBuilder("dt admin sync sp", 14);

        // In this test environment, Bukkit.getServer() is null or Bukkit.isPrimaryThread() is false.
        // Even if townyFacade would throw on any off-thread access, suggestions must not call it!
        doAnswer(inv -> {
            fail("townyFacade.isAvailable() must NEVER be called off the primary thread!");
            return false;
        }).when(townyFacade).isAvailable();

        doAnswer(inv -> {
            fail("townyFacade.allTowns() must NEVER be called off the primary thread!");
            return List.of();
        }).when(townyFacade).allTowns();

        CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> future =
                townArgNode.listSuggestions(ctx, builder);

        assertNotNull(future);
        com.mojang.brigadier.suggestion.Suggestions suggestions = future.join();
        assertNotNull(suggestions);

        // Verify facade was NEVER invoked off primary thread
        verify(townyFacade, never()).isAvailable();
        verify(townyFacade, never()).allTowns();
    }

    @Test
    void suggestionsOnPrimaryThreadProduceMatchingTowns() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade);

        @SuppressWarnings("unchecked")
        com.mojang.brigadier.tree.ArgumentCommandNode<CommandSourceStack, String> townArgNode =
                (com.mojang.brigadier.tree.ArgumentCommandNode<CommandSourceStack, String>)
                        root.getChild("admin").getChild("sync").getChild("town");

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        com.mojang.brigadier.suggestion.SuggestionsBuilder builder =
                new com.mojang.brigadier.suggestion.SuggestionsBuilder("dt admin sync sp", 14);

        TownSnapshot sparta = mock(TownSnapshot.class);
        when(sparta.name()).thenReturn("Sparta");
        TownSnapshot springfield = mock(TownSnapshot.class);
        when(springfield.name()).thenReturn("Springfield");
        TownSnapshot athens = mock(TownSnapshot.class);
        when(athens.name()).thenReturn("Athens");

        when(townyFacade.isAvailable()).thenReturn(true);
        when(townyFacade.allTowns()).thenReturn(List.of(sparta, springfield, athens));

        try (var bukkitMock = mockStatic(Bukkit.class)) {
            org.bukkit.Server server = mock(org.bukkit.Server.class);
            bukkitMock.when(Bukkit::getServer).thenReturn(server);
            bukkitMock.when(Bukkit::isPrimaryThread).thenReturn(true);

            CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> future =
                    townArgNode.listSuggestions(ctx, builder);

            assertNotNull(future);
            com.mojang.brigadier.suggestion.Suggestions suggestions = future.join();
            List<String> list = suggestions.getList().stream()
                    .map(com.mojang.brigadier.suggestion.Suggestion::getText)
                    .toList();

            assertTrue(list.contains("Sparta"));
            assertTrue(list.contains("Springfield"));
            assertFalse(list.contains("Athens"));
        }
    }

    @Test
    void suggestionsCatchExceptionsFromFacadeWithoutThrowing() throws Exception {
        LiteralCommandNode<CommandSourceStack> root = SyncMinecraftCommands.createCommandNode(
                syncService, messages, townyFacade);

        @SuppressWarnings("unchecked")
        com.mojang.brigadier.tree.ArgumentCommandNode<CommandSourceStack, String> townArgNode =
                (com.mojang.brigadier.tree.ArgumentCommandNode<CommandSourceStack, String>)
                        root.getChild("admin").getChild("sync").getChild("town");

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        com.mojang.brigadier.suggestion.SuggestionsBuilder builder =
                new com.mojang.brigadier.suggestion.SuggestionsBuilder("dt admin sync sp", 14);

        when(townyFacade.isAvailable()).thenThrow(new RuntimeException("Towny unavailable"));

        try (var bukkitMock = mockStatic(Bukkit.class)) {
            org.bukkit.Server server = mock(org.bukkit.Server.class);
            bukkitMock.when(Bukkit::getServer).thenReturn(server);
            bukkitMock.when(Bukkit::isPrimaryThread).thenReturn(true);

            CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> future =
                    townArgNode.listSuggestions(ctx, builder);

            assertNotNull(future);
            com.mojang.brigadier.suggestion.Suggestions suggestions = future.join();
            assertNotNull(suggestions);
            assertTrue(suggestions.isEmpty());
        }
    }
}
