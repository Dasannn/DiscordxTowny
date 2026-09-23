package com.discordtowny.minecraft;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.config.YamlMessages;
import com.discordtowny.model.AuditEvent;
import com.discordtowny.storage.SettingsRepository;
import com.discordtowny.storage.StorageException;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Unit and integration tests for the /dt admin prefix command and prefix behavior (T28).
 */
class AdminPrefixCommandTest {

    private Map<String, String> texts;
    private Messages messages;
    private InMemorySettingsRepository settingsRepo;
    private List<AuditEvent> auditLogs;
    private Player admin;
    private CommandSourceStack sourceStack;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private PluginConfig config;

    @BeforeEach
    void setUp() {
        texts = new HashMap<>();
        texts.put("prefix", "&8[&bDiscordTowny&8] &r");
        texts.put("test.msg", "Welcome {player}!");
        texts.put("admin.prefix-set", "&aPrefix changed to: {prefix}&r");
        texts.put("admin.prefix-reset", "&aPrefix restored to catalog default.");
        texts.put("admin.prefix-rendered", "&7Rendered: {prefix}&r");
        texts.put("admin.prefix-raw", "&7Raw: &f{raw}");
        texts.put("admin.prefix-placeholders", "&cThe prefix cannot contain placeholders or braces.");
        texts.put("admin.prefix-line-break", "&cThe prefix cannot contain line breaks.");
        texts.put("admin.prefix-too-long", "&cThe prefix cannot be longer than {max} visible characters.");
        texts.put("admin.prefix-raw-too-long", "&cThe raw prefix cannot be longer than {max} characters.");
        texts.put("admin.prefix-starting", "&cThe plugin is still starting up. Try again in a moment.");
        texts.put("admin.prefix-saved-incomplete", "&ePrefix was saved to database, but could not be applied live. The change will take effect on next restart.");
        texts.put("general.database-unavailable", "&cCannot access the database. Notify an administrator.");

        settingsRepo = new InMemorySettingsRepository();
        auditLogs = new ArrayList<>();

        messages = new YamlMessages(texts, warning -> {}, settingsRepo);

        admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.hasPermission("discordtowny.use")).thenReturn(true);
        when(admin.getName()).thenReturn("AdminAlice");

        sourceStack = mock(CommandSourceStack.class);
        when(sourceStack.getSender()).thenReturn(admin);

        PluginConfig.Linking linking = new PluginConfig.Linking(
                Duration.ofMinutes(10), 3, Duration.ofMinutes(15), true);
        PluginConfig.Limits limits = new PluginConfig.Limits(200, 2, Duration.ofSeconds(60));
        PluginConfig.Sync sync = new PluginConfig.Sync(Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPAIR, 20, Duration.ofSeconds(5));

        config = new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty()),
                new PluginConfig.Database(PluginConfig.Database.Type.SQLITE, "localhost", 3306, "db", "", "", "dt_", 1, 1, Duration.ofSeconds(5)),
                new PluginConfig.Structure("Cat", "Arch", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Mayor", "{town}", Optional.empty(), false),
                limits,
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                sync,
                linking,
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );

        LiteralCommandNode<CommandSourceStack> root = MinecraftCommands.createCommandNode(
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> settingsRepo,
                () -> auditLogs::add,
                () -> config,
                () -> messages,
                () -> messages,
                () -> {},
                Runnable::run,
                Runnable::run // Synchronous executor for deterministic testing
        );

        dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(root);
    }

    @Test
    void showWithNothingStoredDisplaysCatalogPrefixRenderedAndRaw() throws Exception {
        dispatcher.execute("dt admin prefix", sourceStack);

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(admin, times(2)).sendMessage(captor.capture());

        List<Component> sent = captor.getAllValues();
        String line1Plain = PlainTextComponentSerializer.plainText().serialize(sent.get(0));
        String line2Plain = PlainTextComponentSerializer.plainText().serialize(sent.get(1));

        // Line 1: Rendered
        assertTrue(line1Plain.contains("Rendered: [DiscordTowny]"), "Line 1 must contain rendered prefix: " + line1Plain);
        // Line 2: Raw (showing ampersand color codes)
        assertTrue(line2Plain.contains("Raw: &8[&bDiscordTowny&8] &r"), "Line 2 must contain raw formatting codes: " + line2Plain);

        // No audit row written for reading/showing
        assertTrue(auditLogs.isEmpty());
    }

    @Test
    void setPrefixUpdatesPlayerRenderedMessageWhilePlainRetainsCatalog() throws Exception {
        dispatcher.execute("dt admin prefix &6[Server]&r ", sourceStack);

        // Immediate player message check
        Component playerMsg = messages.get("test.msg", Map.of("player", "Bob"));
        String legacySection = LegacyComponentSerializer.legacySection().serialize(playerMsg);
        assertTrue(legacySection.contains("\u00a76[Server]\u00a7r Welcome Bob!"),
                "Player message must carry the custom prefix with formatting: " + legacySection);

        // plain() must STILL carry the catalog prefix
        String plain = messages.plain("test.msg", Map.of("player", "Bob"));
        assertTrue(plain.startsWith("[DiscordTowny] Welcome Bob!"),
                "plain() message must retain the catalog prefix: " + plain);

        // Settings repository must have persisted the custom prefix
        assertEquals("&6[Server]&r ", settingsRepo.get(SettingsRepository.KEY_CHAT_PREFIX).orElseThrow());

        // Verify admin notification
        verify(admin).sendMessage(argThat((Component c) ->
                PlainTextComponentSerializer.plainText().serialize(c).contains("Prefix changed to: [Server]")));

        // Audit log verified
        assertEquals(1, auditLogs.size());
        AuditEvent event = auditLogs.get(0);
        assertEquals("AdminAlice", event.actor());
        assertEquals("prefix", event.action());
        assertEquals("&6[Server]&r ", event.target());
        assertTrue(event.success());
    }

    @Test
    void setEmptyPrefixGivesNoPrefixAndNotConfusedWithReset() throws Exception {
        // Set empty prefix using quotes
        dispatcher.execute("dt admin prefix \"\"", sourceStack);

        // Messages for players have no prefix at all
        Component playerMsg = messages.get("test.msg", Map.of("player", "Bob"));
        String plainPlayer = PlainTextComponentSerializer.plainText().serialize(playerMsg);
        assertEquals("Welcome Bob!", plainPlayer);

        // plain() still has catalog prefix
        String plainConsole = messages.plain("test.msg", Map.of("player", "Bob"));
        assertEquals("[DiscordTowny] Welcome Bob!", plainConsole);

        // In storage: row exists with value "" (present, not empty Optional)
        Optional<String> stored = settingsRepo.get(SettingsRepository.KEY_CHAT_PREFIX);
        assertTrue(stored.isPresent(), "Stored prefix must be present as an empty string");
        assertEquals("", stored.get());

        // Verify audit log for empty prefix
        assertEquals(1, auditLogs.size());
        assertEquals("", auditLogs.get(0).target());

        // Contrast with reset:
        dispatcher.execute("dt admin prefix reset", sourceStack);

        // After reset, row in storage is completely deleted (empty Optional)
        Optional<String> afterReset = settingsRepo.get(SettingsRepository.KEY_CHAT_PREFIX);
        assertTrue(afterReset.isEmpty(), "Reset must delete the setting row");

        // Player message returns to catalog prefix
        Component resetMsg = messages.get("test.msg", Map.of("player", "Bob"));
        String plainReset = PlainTextComponentSerializer.plainText().serialize(resetMsg);
        assertEquals("[DiscordTowny] Welcome Bob!", plainReset);
    }

    @Test
    void resetAfterSetReturnsToCatalogPrefix() throws Exception {
        // Set a custom prefix first
        dispatcher.execute("dt admin prefix &a[Town]&r ", sourceStack);
        assertEquals("&a[Town]&r ", messages.rawPrefix());

        // Reset
        dispatcher.execute("dt admin prefix reset", sourceStack);

        assertEquals("&8[&bDiscordTowny&8] &r", messages.rawPrefix());
        Component playerMsg = messages.get("test.msg", Map.of("player", "Bob"));
        assertTrue(PlainTextComponentSerializer.plainText().serialize(playerMsg).startsWith("[DiscordTowny]"));

        // Verify admin notification
        verify(admin).sendMessage(argThat((Component c) ->
                PlainTextComponentSerializer.plainText().serialize(c).contains("Prefix restored to catalog default.")));

        // Audit row written on reset
        assertEquals(2, auditLogs.size());
        AuditEvent resetEvent = auditLogs.get(1);
        assertEquals("AdminAlice", resetEvent.actor());
        assertEquals("prefix", resetEvent.action());
        assertEquals("&8[&bDiscordTowny&8] &r", resetEvent.target());
        assertTrue(resetEvent.success());
    }

    @Test
    void refusalPlaceholderWithOpeningBrace() throws Exception {
        dispatcher.execute("dt admin prefix {town} > ", sourceStack);

        verify(admin).sendMessage(argThat((Component c) ->
                PlainTextComponentSerializer.plainText().serialize(c).contains("cannot contain placeholders or braces")));
        assertEquals("&8[&bDiscordTowny&8] &r", messages.rawPrefix());
        assertTrue(auditLogs.isEmpty(), "No audit event must be written on refusal");
    }

    @Test
    void refusalPlaceholderWithClosingBrace() throws Exception {
        dispatcher.execute("dt admin prefix town} > ", sourceStack);

        verify(admin).sendMessage(argThat((Component c) ->
                PlainTextComponentSerializer.plainText().serialize(c).contains("cannot contain placeholders or braces")));
        assertEquals("&8[&bDiscordTowny&8] &r", messages.rawPrefix());
        assertTrue(auditLogs.isEmpty(), "No audit event must be written on refusal");
    }

    @Test
    void refusalLineBreak() throws Exception {
        dispatcher.execute("dt admin prefix line1\nline2", sourceStack);

        verify(admin).sendMessage(argThat((Component c) ->
                PlainTextComponentSerializer.plainText().serialize(c).contains("cannot contain line breaks")));
        assertEquals("&8[&bDiscordTowny&8] &r", messages.rawPrefix());
        assertTrue(auditLogs.isEmpty(), "No audit event must be written on refusal");
    }

    @Test
    void refusalVisibleTooLong() throws Exception {
        String overlyLongPrefix = "a".repeat(33); // MAX_VISIBLE_PREFIX_LENGTH is 32
        dispatcher.execute("dt admin prefix " + overlyLongPrefix, sourceStack);

        verify(admin).sendMessage(argThat((Component c) -> {
            String text = PlainTextComponentSerializer.plainText().serialize(c);
            return text.contains("cannot be longer than 32 visible characters");
        }));
        assertEquals("&8[&bDiscordTowny&8] &r", messages.rawPrefix());
        assertTrue(auditLogs.isEmpty(), "No audit event must be written on refusal");
    }

    @Test
    void refusalRawTooLong() throws Exception {
        // Raw length > 255 but visible length small:
        // "&c" repeated 128 times is 256 characters, plus "a" = 257 characters
        String overlyLongRawPrefix = "&c".repeat(128) + "a";
        dispatcher.execute("dt admin prefix " + overlyLongRawPrefix, sourceStack);

        verify(admin).sendMessage(argThat((Component c) -> {
            String text = PlainTextComponentSerializer.plainText().serialize(c);
            return text.contains("raw prefix cannot be longer than 255 characters");
        }));
        assertEquals("&8[&bDiscordTowny&8] &r", messages.rawPrefix());
        assertTrue(auditLogs.isEmpty(), "No audit event must be written on refusal");
    }

    @Test
    void formattingCodesDoNotConsumeVisibleLengthBudget() throws Exception {
        // Raw length is 35 (which would exceed 32 under raw count),
        // but visible text is "[Server] " (9 characters <= 32).
        String formattedPrefix = "&#112233[&#445566Server&#778899] &r";
        dispatcher.execute("dt admin prefix " + formattedPrefix, sourceStack);

        assertEquals(formattedPrefix, messages.rawPrefix());
        assertEquals(1, auditLogs.size());
    }

    @Test
    void storedPrefixSurvivesSimulatedRestart() throws Exception {
        // Set prefix via command
        dispatcher.execute("dt admin prefix &5[Kingdom]&r ", sourceStack);
        assertEquals("&5[Kingdom]&r ", settingsRepo.get(SettingsRepository.KEY_CHAT_PREFIX).orElseThrow());

        // Simulate server restart: create brand new YamlMessages and MinecraftCommands tree
        // backed by the same settings repository
        Messages rebootedMessages = new YamlMessages(texts, warning -> {}, settingsRepo);
        assertEquals("&5[Kingdom]&r ", rebootedMessages.rawPrefix());

        Component rebootedPlayerMsg = rebootedMessages.get("test.msg", Map.of("player", "Charlie"));
        String legacySection = LegacyComponentSerializer.legacySection().serialize(rebootedPlayerMsg);
        assertTrue(legacySection.contains("\u00a75[Kingdom]\u00a7r Welcome Charlie!"));
    }

    @Test
    void settingsStoreThatThrowsKeepsPrintingMessagesWithCatalogPrefix() {
        SettingsRepository failingStore = new SettingsRepository() {
            @Override
            public Optional<String> get(String key) {
                throw new StorageException("Database connection failure");
            }
            @Override
            public void put(String key, String value) {
                throw new StorageException("Database connection failure");
            }
            @Override
            public void delete(String key) {
                throw new StorageException("Database connection failure");
            }
        };

        // Constructing Messages with failing store falls back to catalog prefix
        Messages resilientMessages = new YamlMessages(texts, warning -> {}, failingStore);
        assertEquals("&8[&bDiscordTowny&8] &r", resilientMessages.rawPrefix());

        // Messages still print without throwing exceptions
        assertDoesNotThrow(() -> {
            Component msg = resilientMessages.get("test.msg", Map.of("player", "Dave"));
            assertTrue(PlainTextComponentSerializer.plainText().serialize(msg).startsWith("[DiscordTowny]"));
        });
    }

    @Test
    void settingsStoreThatThrowsDuringCommandAlertsSenderGracefullyAndLeavesPrefixUnchanged() throws Exception {
        SettingsRepository failingStore = new SettingsRepository() {
            @Override
            public Optional<String> get(String key) {
                throw new StorageException("Database offline");
            }
            @Override
            public void put(String key, String value) {
                throw new StorageException("Database offline");
            }
            @Override
            public void delete(String key) {
                throw new StorageException("Database offline");
            }
        };

        LiteralCommandNode<CommandSourceStack> failingRoot = MinecraftCommands.createCommandNode(
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> failingStore,
                () -> auditLogs::add,
                () -> config,
                () -> messages,
                () -> messages,
                () -> {},
                Runnable::run,
                Runnable::run
        );

        CommandDispatcher<CommandSourceStack> failingDispatcher = new CommandDispatcher<>();
        failingDispatcher.getRoot().addChild(failingRoot);

        // Before command, prefix is catalog default
        assertEquals("&8[&bDiscordTowny&8] &r", messages.rawPrefix());

        failingDispatcher.execute("dt admin prefix &3[Test]&r ", sourceStack);

        // Sender receives database unavailable notification
        verify(admin).sendMessage(argThat((Component c) ->
                PlainTextComponentSerializer.plainText().serialize(c).contains("Cannot access the database")));

        // F3 requirement: On failure nothing changes and the refusal stands.
        // Player's next message still carries the old prefix after a failed write.
        assertEquals("&8[&bDiscordTowny&8] &r", messages.rawPrefix());
        Component playerMsg = messages.get("test.msg", Map.of("player", "Alice"));
        String rendered = LegacyComponentSerializer.legacySection().serialize(playerMsg);
        assertTrue(rendered.contains("\u00a7bDiscordTowny"));
        assertFalse(rendered.contains("[Test]"));
        assertTrue(auditLogs.isEmpty(), "No audit event should be recorded if write failed");
    }

    @Test
    void settingsStoreThatThrowsDuringResetAlertsSenderAndLeavesCustomPrefixUnchanged() throws Exception {
        // Set an active custom prefix first
        messages.setCustomPrefix("&6[Active]&r ");
        assertEquals("&6[Active]&r ", messages.rawPrefix());

        SettingsRepository failingStore = new SettingsRepository() {
            @Override
            public Optional<String> get(String key) {
                return Optional.of("&6[Active]&r ");
            }
            @Override
            public void put(String key, String value) {
                throw new StorageException("Database offline");
            }
            @Override
            public void delete(String key) {
                throw new StorageException("Database offline");
            }
        };

        LiteralCommandNode<CommandSourceStack> failingRoot = MinecraftCommands.createCommandNode(
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> failingStore,
                () -> auditLogs::add,
                () -> config,
                () -> messages,
                () -> messages,
                () -> {},
                Runnable::run,
                Runnable::run
        );

        CommandDispatcher<CommandSourceStack> failingDispatcher = new CommandDispatcher<>();
        failingDispatcher.getRoot().addChild(failingRoot);

        failingDispatcher.execute("dt admin prefix reset", sourceStack);

        // Sender receives database unavailable notification
        verify(admin).sendMessage(argThat((Component c) ->
                PlainTextComponentSerializer.plainText().serialize(c).contains("Cannot access the database")));

        // F3 requirement: On failed reset, prefix remains the custom one
        assertEquals("&6[Active]&r ", messages.rawPrefix());
        Component playerMsg = messages.get("test.msg", Map.of("player", "Alice"));
        String rendered = LegacyComponentSerializer.legacySection().serialize(playerMsg);
        assertTrue(rendered.contains("[Active]"));
        assertFalse(rendered.contains("[DiscordTowny]"));
        assertTrue(auditLogs.isEmpty(), "No audit event should be recorded if reset write failed");
    }

    @Test
    void auditConsumerDeliveredDirectlyWithoutReflection() throws Exception {
        List<AuditEvent> delivered = new ArrayList<>();
        Consumer<AuditEvent> consumer = delivered::add;

        LiteralCommandNode<CommandSourceStack> node = MinecraftCommands.createCommandNode(
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> settingsRepo,
                () -> consumer,
                () -> config,
                () -> messages,
                () -> messages,
                () -> {},
                Runnable::run,
                Runnable::run
        );

        CommandDispatcher<CommandSourceStack> customDispatcher = new CommandDispatcher<>();
        customDispatcher.getRoot().addChild(node);

        customDispatcher.execute("dt admin prefix &a[Audited]&r ", sourceStack);

        assertEquals(1, delivered.size(), "Explicitly supplied audit consumer must receive event");
        AuditEvent event = delivered.get(0);
        assertEquals("AdminAlice", event.actor());
        assertEquals("prefix", event.action());
        assertEquals("&a[Audited]&r ", event.target());
        assertTrue(event.success());
    }

    @Test
    void startupWindowSettingsAvailableAuditSinkNotRefusesAndWritesNothing() throws Exception {
        Supplier<Consumer<AuditEvent>> nullAuditSupplier = () -> null;
        LiteralCommandNode<CommandSourceStack> startupRoot = MinecraftCommands.createCommandNode(
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> settingsRepo,
                nullAuditSupplier,
                () -> config,
                () -> messages,
                () -> messages,
                () -> {},
                Runnable::run,
                Runnable::run
        );
        CommandDispatcher<CommandSourceStack> startupDispatcher = new CommandDispatcher<>();
        startupDispatcher.getRoot().addChild(startupRoot);

        startupDispatcher.execute("dt admin prefix &a[Early]&r ", sourceStack);

        // 1. Refusal message says plugin is still starting
        verify(admin).sendMessage(argThat((Component c) -> {
            String text = PlainTextComponentSerializer.plainText().serialize(c);
            return text.contains("still starting up");
        }));

        // 2. Nothing is written to settings
        assertTrue(settingsRepo.get(SettingsRepository.KEY_CHAT_PREFIX).isEmpty(),
                "Settings repository must not be written to when audit sink is unavailable");

        // 3. Nothing is applied in-memory
        assertEquals("&8[&bDiscordTowny&8] &r", messages.rawPrefix());
        Component playerMsg = messages.get("test.msg", Map.of("player", "Alice"));
        String rendered = LegacyComponentSerializer.legacySection().serialize(playerMsg);
        assertTrue(rendered.contains("\u00a7bDiscordTowny"));
        assertFalse(rendered.contains("[Early]"));

        // 4. No audit row written
        assertTrue(auditLogs.isEmpty());
    }

    @Test
    void startupWindowRefusesResetWhenAuditSinkUnavailable() throws Exception {
        messages.setCustomPrefix("&6[Active]&r ");
        settingsRepo.put(SettingsRepository.KEY_CHAT_PREFIX, "&6[Active]&r ");

        Supplier<Consumer<AuditEvent>> nullAuditSupplier = () -> null;
        LiteralCommandNode<CommandSourceStack> startupRoot = MinecraftCommands.createCommandNode(
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> settingsRepo,
                nullAuditSupplier,
                () -> config,
                () -> messages,
                () -> messages,
                () -> {},
                Runnable::run,
                Runnable::run
        );
        CommandDispatcher<CommandSourceStack> startupDispatcher = new CommandDispatcher<>();
        startupDispatcher.getRoot().addChild(startupRoot);

        startupDispatcher.execute("dt admin prefix reset", sourceStack);

        verify(admin).sendMessage(argThat((Component c) ->
                PlainTextComponentSerializer.plainText().serialize(c).contains("still starting up")));

        // Setting row still exists (nothing deleted)
        assertEquals("&6[Active]&r ", settingsRepo.get(SettingsRepository.KEY_CHAT_PREFIX).orElseThrow());
        // Prefix still active (nothing applied)
        assertEquals("&6[Active]&r ", messages.rawPrefix());
    }

    @Test
    void auditConsumerThatThrowsAfterSuccessfulWriteInformsOperatorValueSaved() throws Exception {
        Consumer<AuditEvent> throwingAudit = event -> {
            throw new RuntimeException("Simulated audit sink failure");
        };

        LiteralCommandNode<CommandSourceStack> throwingRoot = MinecraftCommands.createCommandNode(
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> settingsRepo,
                () -> throwingAudit,
                () -> config,
                () -> messages,
                () -> messages,
                () -> {},
                Runnable::run,
                Runnable::run
        );
        CommandDispatcher<CommandSourceStack> throwingDispatcher = new CommandDispatcher<>();
        throwingDispatcher.getRoot().addChild(throwingRoot);

        throwingDispatcher.execute("dt admin prefix &e[Saved]&r ", sourceStack);

        // 1. Operator is told the value was saved, not that the database failed
        verify(admin).sendMessage(argThat((Component c) -> {
            String text = PlainTextComponentSerializer.plainText().serialize(c);
            return text.contains("saved") && text.contains("restart");
        }));
        verify(admin, never()).sendMessage(argThat((Component c) ->
                PlainTextComponentSerializer.plainText().serialize(c).contains("Cannot access the database")));

        // 2. The value was actually saved in the settings repository
        assertEquals("&e[Saved]&r ", settingsRepo.get(SettingsRepository.KEY_CHAT_PREFIX).orElseThrow(),
                "Stored value must have changed in the database");

        // 3. Live in-memory prefix remains the old one
        assertEquals("&8[&bDiscordTowny&8] &r", messages.rawPrefix());
    }

    @Test
    void applyThatThrowsAfterSuccessfulWriteInformsOperatorValueSaved() throws Exception {
        Messages throwingMessages = new DelegatingMessages(new YamlMessages(texts, warning -> {}, settingsRepo)) {
            @Override
            public void setCustomPrefix(String prefix) {
                throw new RuntimeException("Simulated in-memory apply failure");
            }
        };

        LiteralCommandNode<CommandSourceStack> throwingRoot = MinecraftCommands.createCommandNode(
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> settingsRepo,
                () -> auditLogs::add,
                () -> config,
                () -> throwingMessages,
                () -> throwingMessages,
                () -> {},
                Runnable::run,
                Runnable::run
        );
        CommandDispatcher<CommandSourceStack> throwingDispatcher = new CommandDispatcher<>();
        throwingDispatcher.getRoot().addChild(throwingRoot);

        throwingDispatcher.execute("dt admin prefix &b[AppliedFail]&r ", sourceStack);

        // 1. Operator is told the value was saved, not that the database failed
        verify(admin).sendMessage(argThat((Component c) -> {
            String text = PlainTextComponentSerializer.plainText().serialize(c);
            return text.contains("saved") && text.contains("restart");
        }));
        verify(admin, never()).sendMessage(argThat((Component c) ->
                PlainTextComponentSerializer.plainText().serialize(c).contains("Cannot access the database")));

        // 2. The value was actually saved in the settings repository
        assertEquals("&b[AppliedFail]&r ", settingsRepo.get(SettingsRepository.KEY_CHAT_PREFIX).orElseThrow());

        // 3. Audit row was recorded
        assertEquals(1, auditLogs.size());
    }

    @Test
    void applyThatThrowsAfterSuccessfulResetInformsOperatorValueSaved() throws Exception {
        settingsRepo.put(SettingsRepository.KEY_CHAT_PREFIX, "&a[Custom]&r ");
        Messages throwingMessages = new DelegatingMessages(new YamlMessages(texts, warning -> {}, settingsRepo)) {
            @Override
            public void resetPrefix() {
                throw new RuntimeException("Simulated in-memory reset failure");
            }
        };

        LiteralCommandNode<CommandSourceStack> throwingRoot = MinecraftCommands.createCommandNode(
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> settingsRepo,
                () -> auditLogs::add,
                () -> config,
                () -> throwingMessages,
                () -> throwingMessages,
                () -> {},
                Runnable::run,
                Runnable::run
        );
        CommandDispatcher<CommandSourceStack> throwingDispatcher = new CommandDispatcher<>();
        throwingDispatcher.getRoot().addChild(throwingRoot);

        throwingDispatcher.execute("dt admin prefix reset", sourceStack);

        verify(admin).sendMessage(argThat((Component c) -> {
            String text = PlainTextComponentSerializer.plainText().serialize(c);
            return text.contains("saved") && text.contains("restart");
        }));
        verify(admin, never()).sendMessage(argThat((Component c) ->
                PlainTextComponentSerializer.plainText().serialize(c).contains("Cannot access the database")));

        // In storage, the key is deleted
        assertTrue(settingsRepo.get(SettingsRepository.KEY_CHAT_PREFIX).isEmpty());
    }

    private static class InMemorySettingsRepository implements SettingsRepository {
        private final Map<String, String> data = new ConcurrentHashMap<>();

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(data.get(key));
        }

        @Override
        public void put(String key, String value) {
            data.put(key, value);
        }

        @Override
        public void delete(String key) {
            data.remove(key);
        }
    }

    private static class DelegatingMessages implements Messages {
        private final Messages delegate;

        DelegatingMessages(Messages delegate) {
            this.delegate = delegate;
        }

        @Override
        public Component get(String key, Map<String, String> placeholders) {
            return delegate.get(key, placeholders);
        }

        @Override
        public String plain(String key, Map<String, String> placeholders) {
            return delegate.plain(key, placeholders);
        }

        @Override
        public String label(String key, Map<String, String> placeholders) {
            return delegate.label(key, placeholders);
        }

        @Override
        public String rawPrefix() {
            return delegate.rawPrefix();
        }

        @Override
        public String catalogPrefix() {
            return delegate.catalogPrefix();
        }

        @Override
        public Component renderedPrefix() {
            return delegate.renderedPrefix();
        }

        @Override
        public void setCustomPrefix(String prefix) {
            delegate.setCustomPrefix(prefix);
        }

        @Override
        public void resetPrefix() {
            delegate.resetPrefix();
        }

        @Override
        public void invalidatePrefix() {
            delegate.invalidatePrefix();
        }
    }
}
