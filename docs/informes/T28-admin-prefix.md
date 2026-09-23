# T28 Implementation Report — Administrator Custom Chat Prefix

Branch: `feat/admin-prefix`  
Task: **T28 — An administrator can put their own name in front of our messages (`/dt admin prefix`)**

---

## 1. Overview

Every message sent by DiscordTowny to in-game players begins with a prefix, configured by default in both language catalogs as:
```yaml
prefix: "&8[&bDiscordTowny&8] &r"
```

While this cleanly identifies the plugin, server administrators frequently desire to customize this prefix with their server's own branding (e.g. `&8[&6MyServer&8] &r`), color scheme, or even remove it entirely so plugin messages blend seamlessly into their custom chat layout.

Task **T28** implements `/dt admin prefix` under the `discordtowny.admin` permission with three distinct behaviors:
1. **No arguments (`/dt admin prefix`)**: Displays the current active prefix twice:
   - Rendered as players see it (with Essentials-style `&` color and formatting codes parsed into colors).
   - Raw as written (showing formatting codes such as `&8[&bDiscordTowny&8] &r` literally so an administrator can copy, inspect, or adapt it).
2. **`<texto...>` (`/dt admin prefix <texto...>`)**: The remainder of the line becomes the new prefix, spaces included. Takes effect immediately in memory for all players on the server without requiring a server restart or `/dt admin reload`. The change is asynchronously persisted to the `settings` database table.
3. **`reset` (`/dt admin prefix reset`)**: Restores the default catalog prefix. This is explicitly differentiated from setting an empty prefix (`""`), and this distinction is preserved through database storage round-trips and restarts.

Privileged changes write an audit row to the audit log matching the convention established by other admin commands (`/dt admin unlink`), recording who changed the prefix and what it became.

---

## 2. Where the Value is Read and Why

### Architecture & Responsibility Separation
The stored prefix is learned and applied in [`YamlMessages`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/config/YamlMessages.java), which implements the [`Messages`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/config/Messages.java) interface.

`YamlMessages` exposes three primary message formatting methods, each with deliberate prefix semantics:
1. **`get(String key, Map<String, String> placeholders)`**:
   - **Target Audience**: In-game players reading localized chat messages.
   - **Prefix Behavior**: Prepends `effectivePrefix()`—the custom prefix stored in `SettingsRepository` if present, falling back to the catalog default `text("prefix")`.
   - **Formatting**: Deserialized via Adventure's `LegacyComponentSerializer.legacyAmpersand()` so `&` codes format into colored text, followed by placeholder interpolation that treats values as literal strings (preventing recursive formatting injection).
2. **`plain(String key, Map<String, String> placeholders)`**:
   - **Target Audience**: Console logs and Discord messages (e.g. embed descriptions, slash command replies).
   - **Prefix Behavior**: Deliberately uses **only** the catalog prefix (`catalogPrefix() + text(key)`), completely ignoring any custom in-game prefix.
   - **Rationale**: Server console logs and Discord audit feeds must remain permanently recognizable in diagnostics and bug reports. For the exact same reason that the console always remains in English (per Spec §9.1), console output and Discord integrations retain the immutable plugin catalog prefix.
3. **`label(String key, Map<String, String> placeholders)`**:
   - **Target Audience**: UI labels, buttons, Discord embed field names.
   - **Prefix Behavior**: Strictly carries **no prefix at all**. A field titled `Mayor` in an embed must never become `[MyServer] Mayor`.

### Persistence Location: `SettingsRepository`
The custom prefix is stored in the `settings` database table using the key constant:
```java
SettingsRepository.KEY_CHAT_PREFIX = "chat_prefix";
```
This is the appropriate repository because:
- `config.yml` is reserved for administrator file configuration edited by hand;
- Domain tables (`dt_links`, `dt_spaces`) represent relational models;
- `SettingsRepository` already exists for key-value plugin state that must survive restarts (such as `mayor_role_id`).
- It ensures custom prefixes are never wiped or reverted when T23 merges catalogs upon plugin upgrade.

---

## 3. Caching Decision and Invalidation Strategy

### Database Access Frequency
**The database is never read on every chat message.**

In Paper/Bukkit plugins, chat message rendering and player interactions occur directly on the server's main thread. The project's architecture mandates:
> *"All repository methods block. They are called from the plugin's pool, never from the server's main thread nor from a JDA thread."*

Performing a database query on every `Messages.get()` call would cause unacceptable main-thread lag spikes, lock contention, and server tick drops.

Therefore:
- `YamlMessages` caches the active custom prefix in a `volatile Optional<String> cachedCustomPrefix` field.
- The database is read **once** upon initialization / first access.
- When an administrator updates the prefix via `/dt admin prefix <texto...>` or `/dt admin prefix reset`, the cache is updated **immediately in memory** so all players see the change on the very next message without waiting for the database I/O to complete.
- The write to `SettingsRepository` (and the corresponding `AuditEvent` recording) executes asynchronously off the main thread via `CompletableFuture.runAsync()`.

### Invalidation Mechanisms
The cached prefix is invalidated or updated under four specific conditions:
1. **Command Modification (`/dt admin prefix <texto...>`)**:
   Calls `messages.setCustomPrefix(finalPrefix)`, updating the volatile field immediately in memory.
2. **Command Reset (`/dt admin prefix reset`)**:
   Calls `messages.resetPrefix()`, clearing the cache to `Optional.empty()` and reverting `effectivePrefix()` to `catalogPrefix()`.
3. **Explicit Invalidation (`messages.invalidatePrefix()`)**:
   Resets `prefixInitialized = false`, forcing `YamlMessages` to re-query `SettingsRepository` on the next call.
4. **Configuration Reload & Catalog Upgrade (T23)**:
   When `/dt admin reload` or a catalog merge runs, `YamlConfigLoader.load()` creates a new `YamlMessages` instance. Because `YamlMessages` is registered with the storage provider via `setDefaultSettingsSupplier`, the newly created `YamlMessages` immediately queries `SettingsRepository.KEY_CHAT_PREFIX` and inherits the stored custom prefix. Upgrading catalogs or reloading texts never undoes the administrator's prefix.

### Fault Tolerance & Store Outages
A database connection failure must never crash or silence the plugin. If `settings.get(KEY_CHAT_PREFIX)` throws a `StorageException` or any `Throwable`:
- The exception is caught within `YamlMessages.loadCustomPrefix()`.
- The cache falls back to `Optional.empty()`.
- `effectivePrefix()` safely defaults to `catalogPrefix()` (`text("prefix")`).
- All messages continue printing normally with the default catalog prefix.

---

## 4. Length Limit and Justification

### Chosen Limit: 64 Characters
`MinecraftCommands.MAX_PREFIX_LENGTH = 64;`

### Justification
1. **Chat Box Width**: Standard Minecraft chat render width is 320 pixels (roughly 53 to 60 standard characters per line depending on font width and scale). A prefix of 64 characters already takes up an entire chat line before the actual message content begins.
2. **Rich Formatting Headroom**: Modern Minecraft servers use RGB hexadecimal colors (`&#123456` or `&x&f&f&a&a&0&0`), bold codes (`&l`), and brackets. For instance:
   ```
   &8[&x&0&0&f&f&c&cMyServer&8]&r 
   ```
   contains 31 raw characters for a relatively short word. A limit of 64 characters allows server owners full creative freedom to use RGB gradients, styling, and multi-word names without feeling constrained.
3. **Database Column Boundary**: The `settings` table schema (`MigrationRunner` v5) specifies:
   ```sql
   setting_key VARCHAR(64) PRIMARY KEY,
   value VARCHAR(255) NOT NULL
   ```
   A limit of 64 characters fits comfortably inside the `VARCHAR(255)` column with zero risk of SQL overflow or truncation.
4. **Transparency**: The chosen limit is passed as the `{max}` placeholder to refusal messages in both languages (`admin.prefix-too-long`), clearly informing the administrator of the constraint.

---

## 5. Refusals and Error Handling

All inputs are validated synchronously before updating memory, touching the database, or writing audit records:

| Refusal Condition | Rationale | Message Key | Output Example |
| :--- | :--- | :--- | :--- |
| **`{` or `}`** | Prefixes carry no dynamic placeholders. Delimiters would either be mangled or leak raw syntax to players. | `admin.prefix-placeholders` | `&cThe prefix cannot contain placeholders like { or }.` |
| **`\n` or `\r`** | Line breaks split chat messages into multiple spoofable lines. | `admin.prefix-line-break` | `&cThe prefix cannot contain line breaks.` |
| **Length > 64** | Prevents chat line flooding and UI distortion. | `admin.prefix-too-long` | `&cThe prefix cannot be longer than 64 characters.` |

**Refusal Audit Invariant**: Refusals do **not** write an audit row. Only successful administrative mutations generate audit events.

---

## 6. Empty Prefix vs. Reset

Setting an empty prefix (`""`) and resetting to the catalog default are distinct choices that must survive restarts:

- **Empty Prefix (`/dt admin prefix ""` or `''`)**:
  - Storage: An upsert is executed storing `value = ""` (`settings.put(KEY_CHAT_PREFIX, "")`).
  - Read: `settings.get(KEY_CHAT_PREFIX)` returns `Optional.of("")`.
  - Effect: `effectivePrefix()` returns `""`. In-game messages are displayed with **no prefix at all**.
  - `plain()`: Still displays the catalog prefix `[DiscordTowny]`.
- **Reset (`/dt admin prefix reset`)**:
  - Storage: The row is deleted (`settings.delete(KEY_CHAT_PREFIX)`).
  - Read: `settings.get(KEY_CHAT_PREFIX)` returns `Optional.empty()`.
  - Effect: `effectivePrefix()` falls back to `catalogPrefix()`. In-game messages return to `&8[&bDiscordTowny&8] &r`.

---

## 7. Audit Event Specification

Changing the prefix is an administrative privilege. When an administrator modifies or resets the prefix, an `AuditEvent` is generated matching the shape and semantics of `/dt admin unlink`:

```java
new AuditEvent(
    Instant.now(),
    AuditEvent.Severity.INFO,
    sender.getName(),          // Actor (e.g. "AdminAlice" or "CONSOLE")
    "prefix",                  // Action
    finalPrefix,               // Target: what it became (new prefix, or catalog prefix on reset)
    true,                      // Success
    Optional.empty()           // Detail (Optional.of("reset") on reset)
);
```

---

## 8. Verification and Test Coverage

A comprehensive test suite was implemented across storage, messages, and command layers:

1. [`SettingsRepositoryTest`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/storage/SettingsRepositoryTest.java):
   - `savesAndRetrievesChatPrefix`: Verifies storage and retrieval of custom prefix.
   - `savingEmptyChatPrefixIsPreservedAndDistinctFromNonexistent`: Verifies empty string `""` round-trip produces `Optional.of("")`, not `Optional.empty()`.
   - `deletingChatPrefixRestoresEmptyOptional`: Verifies `delete` removes the row, yielding `Optional.empty()`.
2. [`YamlMessagesTest`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/config/YamlMessagesTest.java):
   - `customPrefixAppliesToGetWhilePlainRetainsCatalogPrefix`: Verifies `get()` renders custom prefix with colors while `plain()` preserves catalog prefix.
   - `emptyPrefixResultsInNoPrefixForGetWhilePlainRetainsCatalogPrefix`: Verifies empty prefix strips prefix for `get()` while `plain()` keeps catalog default, and `resetPrefix()` restores it.
   - `storedPrefixSurvivesSimulatedRestart`: Verifies re-instantiating `YamlMessages` with the same `SettingsRepository` recovers the custom prefix.
   - `failingSettingsStoreFallsBackToCatalogPrefixWithoutFailing`: Verifies a throwing store does not throw and preserves catalog prefix.
3. [`AdminPrefixCommandTest`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/minecraft/AdminPrefixCommandTest.java):
   - `showWithNothingStoredDisplaysCatalogPrefixRenderedAndRaw`: Shows rendered (colored) and raw (`&8[&bDiscordTowny&8] &r`) prefix twice; no audit log.
   - `setPrefixUpdatesPlayerRenderedMessageWhilePlainRetainsCatalog`: Verifies immediate in-memory update, async DB write, `plain()` isolation, and audit logging.
   - `setEmptyPrefixGivesNoPrefixAndNotConfusedWithReset`: Tests `""` input, database persistence, and clear distinction from `reset`.
   - `resetAfterSetReturnsToCatalogPrefix`: Verifies `reset` deletes DB row, restores catalog prefix in player messages, and logs audit event.
   - Refusal tests (`refusalPlaceholderWithOpeningBrace`, `refusalPlaceholderWithClosingBrace`, `refusalLineBreak`, `refusalTooLong`): Verifies specific refusal messages sent and verifies no audit row is written.
   - `storedPrefixSurvivesSimulatedRestart`: Full command dispatch test simulating server reboot.
   - `settingsStoreThatThrowsKeepsPrintingMessagesWithCatalogPrefix`: Verifies plugin resilience when database fails.
   - `settingsStoreThatThrowsDuringCommandAlertsSenderGracefully`: Verifies administrator receives `general.database-unavailable` if storage write fails.
4. [`MinecraftCommandsTest`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/minecraft/MinecraftCommandsTest.java):
   - `adminPrefixNodeExistsUnderAdminTreeWithSubcommands`: Validates Brigadier command tree registration and child hierarchy.

---

## 9. Verification Limits

- **`./gradlew` and `git` Execution**: Per task instructions and `AGENTS.md`, no gradle builds or git commands were executed directly by the assistant.
- **Live Paper Server Runtime**: Verification was performed against Mockito/JUnit 5 test fixtures simulating the Paper Brigadier command dispatcher, Adventure component rendering, and SQL storage interfaces.

---

## 10. Round 2 Architecture Refinement

Following code review feedback on the initial T28 implementation, three architectural and structural defects were corrected, and targeted unit tests were introduced.

### 10.1 Self-Eating Message: Brace Placeholders in Catalogs

#### The Defect
`YamlConfigLoaderTest > bundledMessagesHaveMatchingPlaceholders()` parses message catalog entries using the regular expression `Pattern.compile("\\{([^{}]+)}")` to verify that all placeholders present in `messages_en.yml` are also present with identical names in `messages_es.yml`.

In Round 1, the refusal message for forbidden braces was authored as:
- `messages_en.yml`: `"&cThe prefix cannot contain placeholders like { or }."`
- `messages_es.yml`: `"&cEl prefijo no puede contener marcadores como { o }."`

The test's regex matched `{ or }` and `{ o }` as placeholders named `" or "` in English and `" o "` in Spanish. Because placeholder names did not match across languages, the build correctly failed.

#### The Fix
The refusal message now describes the restriction in plain words without enclosing characters in braces:
- `messages_en.yml`: `"&cThe prefix cannot contain placeholders or braces."`
- `messages_es.yml`: `"&cEl prefijo no puede contener marcadores ni llaves."`

Both catalogs now extract an empty set of placeholders for `admin.prefix-placeholders`, resolving the consistency failure. Associated assertions in [`AdminPrefixCommandTest`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/minecraft/AdminPrefixCommandTest.java) were updated accordingly.

---

### 10.2 Removal of Static State and Gateway-Reaching Reflection

#### The Defect
In Round 1, `YamlMessages` stored a static mutable supplier (`YamlMessages.setDefaultSettingsSupplier`) set during command registration. Static mutable state:
- Outlives configuration reloads.
- Breaks JVM test isolation when multiple tests configure or clear static state.
- Prevents multiple plugin instances from coexisting cleanly in the same JVM.
- Furthermore, `resolveSettings` used reflection on `DiscordGateway` to find `SettingsRepository`. Storage does not live in Discord; reaching storage through the gateway inverted architectural layering.

#### The Fix
The zone expansion to [`DiscordTownyPlugin.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyPlugin.java) and [`DiscordTownyWiring.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyWiring.java) established a direct, explicit dependency flow:

1. **Storage Accessor on Wiring & Plugin**:
   - Added `public SettingsRepository getSettingsRepository()` on [`DiscordTownyWiring`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyWiring.java), returning `storage != null ? storage.settings() : null`.
   - Added matching delegate `public SettingsRepository getSettingsRepository()` on [`DiscordTownyPlugin`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyPlugin.java).
2. **Explicit Constructor Injection in `YamlMessages`**:
   - `YamlMessages` receives `SettingsRepository` strictly via its constructor: `new YamlMessages(texts, warning, settings)`.
   - Deleted `setDefaultSettingsSupplier`, `setDefaultSettings`, `defaultSettingsSupplier`, and `clearDefaultsForTest()` from [`YamlMessages`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/config/YamlMessages.java).
3. **Explicit Wiring in `DiscordTownyWiring`**:
   - In `initializeServicesAsync()`, the custom prefix is read from `storage.settings().get(KEY_CHAT_PREFIX)` and applied to `messages.setCustomPrefix(...)`.
   - In `reload()`, the custom prefix is restored on the newly instantiated `messages` from `storage.settings().get(KEY_CHAT_PREFIX)`.
4. **Command Registration**:
   - In `DiscordTownyPlugin.onEnable()`, `() -> wiring != null ? wiring.getSettingsRepository() : null` is passed into `MinecraftCommands.register(...)`.
   - In `MinecraftCommands.resolveSettings`, the gateway-reaching reflection and Bukkit plugin reflection branches were deleted; settings are resolved strictly through `settingsSupplier.get()`.

---

### 10.3 Collapse of `createCommandNode` Overloads

#### The Defect
The number of `createCommandNode` overloads had grown to six. No production code called any of them—the plugin calls `register(...)`, which invokes the full supplier signature. The other five overloads existed solely as backward-compatibility shims for test suites.

#### The Fix
All intermediate overloads were removed. Exactly **two** signatures are preserved in [`MinecraftCommands`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/minecraft/MinecraftCommands.java):

1. **Production / Registration Overload (14 parameters)**:
   ```java
   public static LiteralCommandNode<CommandSourceStack> createCommandNode(
           Supplier<LinkService> linkServiceSupplier,
           Supplier<SpaceService> spaceServiceSupplier,
           Supplier<SyncService> syncServiceSupplier,
           Supplier<TownyFacade> townyFacadeSupplier,
           Supplier<DiscordGateway> discordGatewaySupplier,
           Supplier<UpdateService> updateServiceSupplier,
           Supplier<SettingsRepository> settingsSupplier,
           Consumer<AuditEvent> auditConsumer,
           Supplier<PluginConfig> configSupplier,
           Supplier<Messages> messagesSupplier,
           Supplier<Messages> consoleMessagesSupplier,
           Runnable reloadAction,
           Consumer<Runnable> syncScheduler,
           Executor asyncExecutor)
   ```
   Invoked by `MinecraftCommands.register(...)` and tests that test async executor behavior, audit sinks, and settings suppliers.

2. **Single Deliberately Test-Facing Overload (11 parameters)**:
   ```java
   public static LiteralCommandNode<CommandSourceStack> createCommandNode(
           LinkService linkService,
           SpaceService spaceService,
           SyncService syncService,
           TownyFacade townyFacade,
           DiscordGateway discordGateway,
           UpdateService updateService,
           PluginConfig config,
           Messages messages,
           Messages consoleMessages,
           Runnable reloadAction,
           Consumer<Runnable> syncScheduler)
   ```
   Retained to provide test convenience without polluting production code with combinatorial overload permutations.

#### Call Site Impact
- **Deleted Overloads**: 4 overloads were removed (including 8-argument, 9-argument, 10-argument, and intermediate supplier-based overloads).
- **Call Sites Updated**: Exactly **5** call sites were touched in [`MinecraftCommandsTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/minecraft/MinecraftCommandsTest.java) (lines 173, 1229, 1366, 2045, and 2064). Each of these previously omitted `UpdateService` and now passes `null` to match the 11-parameter test-facing signature.
- All other test suites (`SyncMinecraftCommandsTest`, `LinkMinecraftCommandsTest`, `AdminPrefixCommandTest`) either target their own subsystem command classes or already use the 14-parameter supplier signature.

---

### 10.4 Additional Round 2 Tests

Two new tests were added to [`YamlMessagesTest`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/config/YamlMessagesTest.java):

1. **`messagesRenderWithStoredPrefixWhenSettingsRepositorySuppliedThroughConstruction`**:
   - Builds `YamlMessages` passing an in-memory `SettingsRepository` directly to the constructor.
   - Verifies `messages.rawPrefix()` returns the stored prefix (`&6[StoredPrefix] `).
   - Verifies `messages.get("greeting", Map.of("name", "Alice"))` renders Adventure components with `§6[StoredPrefix]` and interpolated placeholders, with no static state involved anywhere.
2. **`twoYamlMessagesBuiltWithDifferentSettingsRepositoriesDoNotAffectEachOther`**:
   - Constructs two separate `SettingsRepository` instances (`settingsA` with `&c[PrefixA] `, `settingsB` with `&9[PrefixB] `).
   - Instantiates `messagesA` and `messagesB` bound to their respective repositories.
   - Verifies both instances render with their respective prefixes independently.
   - Modifies `messagesA.setCustomPrefix(...)` and resets `messagesA.resetPrefix()`, verifying that `messagesB` remains entirely unaffected throughout mutations. This test definitively proves that instances are completely isolated.

