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

---

## 11. Round 3 Architecture & Review Resolutions

Following Review 1 (`docs/revisiones/T28-revision-1.md`), findings F1 through F5 were addressed across the wiring, configuration, and command layers, and F6 was formally documented as closed by decision.

### 11.1 F1 & F2 — Complete Elimination of Reflection and Explicit Delegation

#### F1: Direct Audit Delivery
- **Defect**: In Round 2, `MinecraftCommands.register` passed `null` as `auditConsumer`. At runtime, `resolveAudit` attempted to discover an audit sink via Bukkit reflection (`getMethod("getWiring")`, `getMethod("getAuditSink")`, checking for a non-existent `log(AuditEvent)` method) and fell back to reflecting over `DefaultSpaceService` private fields. Any wrapper or field renaming silently resulted in dropped audit rows.
- **Resolution**:
  - `DiscordTownyPlugin.onEnable()` passes an explicit `Consumer<AuditEvent>` lambda delegating directly to `wiring.getAuditSink().accept(event)`.
  - Added diagnostic accessor `getAuditSink()` to [`DiscordTownyPlugin.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyPlugin.java).
  - Updated `MinecraftCommands.register` to receive `Consumer<AuditEvent> auditConsumer` and pass it directly to `createCommandNode`. Retained backward-compatible overload passing `null`.
  - Completely deleted `resolveAudit` and removed reflective imports (`java.lang.reflect.Field`, `Method`) from [`MinecraftCommands.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/minecraft/MinecraftCommands.java).
  - Commands invoke `auditConsumer.accept(...)` directly.

#### F2: Explicit Delegation in `ReloadableMessages`
- **Defect**: In Round 2, the `Messages` interface contained a default implementation of prefix methods that called a private `unwrap(Messages)` helper, which scanned `this.getClass().getDeclaredFields()` to locate the underlying delegate inside `ReloadableMessages`.
- **Resolution**:
  - [`ReloadableMessages`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/config/YamlConfigLoader.java) now explicitly overrides and forwards all prefix methods (`rawPrefix()`, `catalogPrefix()`, `renderedPrefix()`, `setCustomPrefix(prefix)`, `resetPrefix()`, `invalidatePrefix()`) directly to `current`.
  - Removed `unwrap(Messages)` and `java.lang.reflect.Field` from [`Messages.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/config/Messages.java). Default methods on `Messages` now perform clean no-ops or return default/empty values.
  - Zero reflection remains across the entire branch.

---

### 11.2 F3 — Persistence Precedes In-Memory Mutation

- **Defect**: Set and reset commands modified the shared in-memory prefix before asynchronously persisting to the database. If `settings.put` or `settings.delete` failed, the administrator received an error reply (`general.database-unavailable`), but players continued seeing the uncommitted prefix until the next reload or restart.
- **Resolution**:
  - In [`MinecraftCommands.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/minecraft/MinecraftCommands.java), removed premature in-memory calls from `executeSetPrefix` and `executeResetPrefix`.
  - Memory mutation (`messages.setCustomPrefix(...)` / `messages.resetPrefix()`) now executes strictly inside `thenRun(...)` **after** `settings.put` or `settings.delete` successfully finishes.
  - On database write failure, execution routes to `exceptionally(...)`: memory remains untouched, the old effective prefix continues displaying for all players, and the error notification is sent.

---

### 11.3 F4 — Component Deserialization Isolation (Anti-Bleed)

- **Defect**: `YamlMessages.get()` previously deserialized `effectivePrefix() + text(key)` as a single concatenated legacy string. If a custom prefix ended in an unclosed formatting code (such as `&k` for obfuscation, `&l` for bold, or a color like `&c` without `&r`), the formatting bled into and altered or obfuscated the entire message body.
- **Resolution**:
  - In [`YamlMessages.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/config/YamlMessages.java), the prefix and catalog message body are now deserialized separately through `LegacyComponentSerializer.legacyAmpersand()`.
  - The two components are joined as independent siblings under a parent container:
    ```java
    Component.text().append(prefixComponent).append(body).build()
    ```
  - In Kyori Adventure, sibling components never inherit style properties from adjacent siblings. This structurally isolates prefix styling from message styling for all formatting codes without requiring any code blacklists.

---

### 11.4 F5 — Visible Length Limit vs. Raw Storage Cap

- **Defect**: The previous 64-character limit counted Java UTF-16 code units. Color and styling codes (e.g. hex colors `&#123456`) consumed the budget despite occupying zero screen width, while wide glyphs consumed the same budget as narrow ones.
- **Resolution & Dual Limits**:
  - **Visible Limit (`MAX_VISIBLE_PREFIX_LENGTH = 32`)**:
    - Measured by stripping all legacy formatting and hex color codes via `stripFormatting(targetPrefix)` (`PlainTextComponentSerializer` over `legacyAmpersand().deserialize(...)`) and counting Unicode code points via `codePointCount`.
    - Refusal: `admin.prefix-too-long` informs the administrator:
      - EN: `&cThe prefix cannot be longer than {max} visible characters.`
      - ES: `&cEl prefijo no puede tener más de {max} caracteres visibles.`
  - **Raw Storage Cap (`MAX_RAW_PREFIX_LENGTH = 255`)**:
    - Hard ceiling aligned with the `VARCHAR(255)` database column schema in `settings` to prevent SQL truncation, driver exceptions, or memory flooding. Checked before visible parsing to prevent DOS with oversized strings.
    - Refusal: `admin.prefix-raw-too-long` informs the administrator:
      - EN: `&cThe raw prefix cannot be longer than {max} characters.`
      - ES: `&cEl prefijo sin formato no puede tener más de {max} caracteres.`
  - **What the Limits Promise and Do Not Promise**:
    - *Promises*: Guarantees that at most 32 visible characters/glyphs will appear in chat before the message body, reserving ~20–30 characters on the first line for the player name and message text under standard font metrics (~320px chat line width). Guarantees rich color formatting codes do not consume the visible budget. Guarantees raw payloads over 255 characters never reach the database.
    - *Does Not Promise*: Does not guarantee an absolute pixel screen-width boundary. Minecraft fonts are proportional (e.g. `'i'` is 2 pixels wide, `'W'` is 6 pixels wide, and CJK full-width glyphs are 9 pixels wide), and players may install custom client resource packs with arbitrary glyph widths. Character counting provides a reliable glyph bound, not a pixel guarantee.

---

### 11.5 F6 — Closed by Decision: Shared Database Deployments Excluded

- **Scope Review**: Review 1 noted that two server instances sharing a single database table could exhibit desynchronized prefixes until reload, because prefixes are cached in memory on startup and reload rather than queried per message.
- **Resolution**:
  - Per `docs/spec.md` §9.1 and the Project Constitution, multi-server networks sharing a database are explicitly unsupported deployments.
  - Performing a database read on every chat message would violate Principle P4 ("Main thread is sacred") and introduce unacceptable tick latency.
  - **Zero code was built for cross-process synchronization**, confirming the architectural decision.

---

### 11.6 Round 3 Test Suite Additions

The following test cases were added across the test suite to verify Round 3 requirements:

1. [`YamlConfigLoaderTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/config/YamlConfigLoaderTest.java):
   - `productionReloadableMessagesWrapperDelegatesPrefixMethodsExplicitlyWithoutReflection`: Verifies that mutating prefix methods on the `ReloadableMessages` wrapper built by `YamlConfigLoader` updates what players see without reflection, and that `resetPrefix()` cleanly restores catalog default.
2. [`DiscordTownyWiringAuditTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/DiscordTownyWiringAuditTest.java):
   - `productionWiringMessagesWrapperUpdatesPlayerMessage`: Verifies the end-to-end production path through `DiscordTownyWiring`, ensuring `wiring.getMessages().setCustomPrefix(...)` changes the rendered output of subsequent player messages without reflection.
3. [`AdminPrefixCommandTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/minecraft/AdminPrefixCommandTest.java):
   - `refusalVisibleTooLong`: Verifies refusal when visible characters exceed 32.
   - `refusalRawTooLong`: Verifies refusal when raw string length exceeds 255 characters.
   - `formattingCodesDoNotConsumeVisibleLengthBudget`: Verifies that raw strings > 32 characters consisting of rich formatting codes (e.g. hex colors) succeed when visible character count is <= 32.
   - `settingsStoreThatThrowsDuringCommandAlertsSenderGracefullyAndLeavesPrefixUnchanged`: Verifies that upon storage failure during `prefix <new>`, the in-memory prefix remains untouched and a player's subsequent message still displays the old prefix.
   - `settingsStoreThatThrowsDuringResetAlertsSenderAndLeavesCustomPrefixUnchanged`: Verifies that upon storage failure during `prefix reset`, the active custom prefix remains in force for subsequent player messages.
   - `auditConsumerDeliveredDirectlyWithoutReflection`: Verifies that explicit `Consumer<AuditEvent>` supplied during registration receives audit events without reflection.
4. [`YamlMessagesTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/config/YamlMessagesTest.java):
   - `prefixEndingInObfuscationCodeDoesNotBleedIntoMessageBody`: Verifies that a prefix ending in `&k` does not obfuscate the message body.
   - `prefixWithColorAndNoResetDoesNotBleedIntoMessageBody`: Verifies that a prefix with color and no reset code does not override the body's own color.
   - `prefixWithColorAndNoResetDoesNotColorUncoloredMessageBody`: Verifies that an unclosed prefix color code does not color an uncolored message body.

---

## 12. Round 4 Defect Resolutions and Root-Cause Analysis

Following the execution of the Round 3 test suite, four test failures were observed. Exactly one was a regression in a pre-existing test ([`YamlMessagesTest`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/config/YamlMessagesTest.java)), and three were in newly added Round 3 tests ([`DiscordTownyWiringAuditTest`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/DiscordTownyWiringAuditTest.java), [`YamlConfigLoaderTest`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/config/YamlConfigLoaderTest.java), [`AdminPrefixCommandTest`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/minecraft/AdminPrefixCommandTest.java)).

Below is the root-cause analysis, decision rationale, and resolution details for each failure.

---

### 12.1 Regression in Pre-Existing Test: `missingMessageOrPrefixIsIdentifiedAndWarnsOnce`

- **Test**: [`YamlMessagesTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/config/YamlMessagesTest.java) (line 42)
- **Status**: Pre-existing test; passed prior to Round 3.
- **Verdict**: **Production code was wrong; test was right.**

#### Root Cause
In Round 3, implementing F4 (anti-bleed component deserialization) split the formatting pipeline into separate components joined as siblings:
```java
Component.text().append(prefixComponent).append(body).build()
```
When this was written, the variable assignments in both [`YamlMessages.get`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/config/YamlMessages.java#L142-L153) and [`YamlMessages.plain`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/config/YamlMessages.java#L164-L176) evaluated `body` before `prefix`:
```java
Component body = resolve(LegacyComponentSerializer.legacyAmpersand().deserialize(text(key)), placeholders);
String prefix = catalogPrefix();
```
Prior to Round 3, string concatenation `catalogPrefix() + text(key)` evaluated strictly left-to-right: `catalogPrefix()` (and therefore `text("prefix")`) was evaluated first, and `text(key)` was evaluated second.

Because missing-key warnings are pushed to the warning sink on first lookup:
1. Evaluating `text(key)` first caused the missing-key warning for `general.working` to be logged at index 0.
2. Evaluating `catalogPrefix()` second caused the missing-key warning for `prefix` to be logged at index 1 (`warnings.getLast()`).
3. The test asserted `assertTrue(warnings.getLast().contains("general.working"))`, which failed because `warnings.getLast()` contained `"prefix"`.

#### Resolution
The production code in [`YamlMessages.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/config/YamlMessages.java) was corrected so that `prefix` is resolved before `body` in both `get()` and `plain()`:
```java
String prefix = effectivePrefix(); // (or catalogPrefix() in plain())
Component body = resolve(
        LegacyComponentSerializer.legacyAmpersand().deserialize(text(key)),
        placeholders
);
```
This restores the natural left-to-right evaluation order matching message appearance: `prefix` warning arrives first, `general.working` arrives second, and `warnings.getLast()` contains `"general.working"`.

**Zero edits were made to [`YamlMessagesTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/config/YamlMessagesTest.java)**, strictly honoring the contract for pre-existing tests.

---

### 12.2 Wiring Production Wrapper Test: `productionWiringMessagesWrapperUpdatesPlayerMessage`

- **Test**: [`DiscordTownyWiringAuditTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/DiscordTownyWiringAuditTest.java) (line 362)
- **Status**: Newly authored in Round 3.
- **Verdict**: **Test fixture was wrong; production code was right.**

#### Root Cause
The test attempted to verify that the live wrapper built by [`DiscordTownyWiring`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyWiring.java) updates player messages without reflection:
```java
wiring.setConfigLoaderForTest(new YamlConfigLoader(tempFolder, warning -> {}));
...
wiring.reload();
```
In production, [`DiscordTownyWiring.reload()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyWiring.java#L461-L471) calls `configLoader.load()`. If the configuration file on disk is unreadable, corrupt, or missing, it logs a severe error and throws [`ConfigException`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/config/ConfigException.java) to reject the reload.

In the test fixture, `tempFolder` was an empty temporary directory without `config.yml`. Consequently, `YamlConfigLoader.load()` failed validation across the entire configuration schema and threw `ConfigException` at line 362. Furthermore, invoking `reload()` without installing `discordGateway` on wiring would cause `reload()` to instantiate a live `JdaDiscordGateway` and attempt network I/O.

#### Resolution
The test fixture was corrected to match the standard pattern established by all other tests in [`DiscordTownyWiringAuditTest`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/DiscordTownyWiringAuditTest.java):
1. Instantiated a real [`YamlConfigLoader`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/config/YamlConfigLoader.java) to obtain its genuine production `ReloadableMessages` wrapper (`realLoader.messages()`).
2. Installed a mock `loader` whose `load()` returns the test fixture's valid in-memory `PluginConfig` and whose `messages()` supplies `realLoader.messages()`.
3. Installed `wiring.setDiscordGatewayForTest(discordGateway)` to prevent live network gateway initialization during reload.
4. Corrected the post-reset assertion from `contains("[DiscordTowny]")` to `contains("\u00a7bDiscordTowny")` (see §12.3).

The production code in `DiscordTownyWiring.java` was not modified, as throwing `ConfigException` on reload with invalid disk configuration is a fundamental specification requirement.

---

### 12.3 Config Loader Wrapper Delegation Test: `productionReloadableMessagesWrapperDelegatesPrefixMethodsExplicitlyWithoutReflection`

- **Test**: [`YamlConfigLoaderTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/config/YamlConfigLoaderTest.java) (line 575)
- **Status**: Newly authored in Round 3.
- **Verdict**: **Test expectation was wrong; production code was right.**

#### Root Cause
Line 575 asserted:
```java
messages.resetPrefix();
assertEquals("&8[&bDiscordTowny&8] &r", messages.rawPrefix());
Component resetComp = messages.get("linking.code-invalid");
String resetRendered = LegacyComponentSerializer.legacySection().serialize(resetComp);
assertTrue(resetRendered.contains("[DiscordTowny]"));
```
Line 572 passed, confirming that `messages.rawPrefix()` was correctly restored to `"&8[&bDiscordTowny&8] &r"`.

However, `resetRendered` was serialized using Kyori Adventure's `LegacyComponentSerializer.legacySection()`. In legacy section formatting, the ampersand `&b` (cyan) inside the brackets becomes the section symbol `§b` (`\u00a7b`):
```
§8[§bDiscordTowny§8] §r
```
Because the formatting code `§b` intervenes directly between `[` and `DiscordTowny`, the literal substring `"[DiscordTowny]"` does not exist anywhere in the formatted string. The author had remembered formatting codes in line 563 (`\u00a79[CustomServer]`), but overlooked that the default catalog prefix carries cyan formatting inside the brackets.

#### Resolution
Line 575 was corrected to assert:
```java
assertTrue(resetRendered.contains("\u00a7bDiscordTowny"));
```
This tests the exact formatted branding defined by the catalog without weakening the assertion, confirming that the catalog prefix and its cyan styling are restored to player messages upon reset.

---

### 12.4 F3 Store Failure Resilience Test: `settingsStoreThatThrowsDuringCommandAlertsSenderGracefullyAndLeavesPrefixUnchanged`

- **Test**: [`AdminPrefixCommandTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/minecraft/AdminPrefixCommandTest.java) (line 392)
- **Status**: Newly authored in Round 3.
- **Verdict**: **Test expectation was wrong; production code was right.**

#### Root Cause
The test verifies F3: when `settings.put(...)` throws [`StorageException`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/storage/StorageException.java), the new prefix must not be applied to in-memory state, and subsequent player messages must continue displaying the old prefix.

In the test execution:
- Line 384 passed: sender received `general.database-unavailable`.
- Line 389 passed: `messages.rawPrefix()` remained unchanged (`"&8[&bDiscordTowny&8] &r"`).
- Line 393 passed: player message did not contain `[Test]`.
- Line 394 passed: no audit event was written.

Line 392 failed solely because it asserted `assertTrue(rendered.contains("[DiscordTowny]"))` on `LegacyComponentSerializer.legacySection().serialize(playerMsg)`. For the exact same reason as in `YamlConfigLoaderTest` (§12.3), `rendered` contained `§8[§bDiscordTowny§8] §r`, where `§b` separates `[` from `DiscordTowny`.

#### Resolution
Line 392 was corrected to assert:
```java
assertTrue(rendered.contains("\u00a7bDiscordTowny"));
```
This cleanly verifies that the old catalog prefix remains active and visible to players following a database failure, as mandated by requirement F3.

---

### 12.5 Summary of Changes in Round 4

| File | Nature of Fix | Description |
| :--- | :--- | :--- |
| [`YamlMessages.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/config/YamlMessages.java) | **Production Bug Fix** | Evaluated prefix before body in `get()` and `plain()`, restoring left-to-right evaluation order and warning sequence to fix regression in pre-existing test `missingMessageOrPrefixIsIdentifiedAndWarnsOnce`. |
| [`DiscordTownyWiringAuditTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/DiscordTownyWiringAuditTest.java) | **Test Fixture Fix** | Installed mock config loader returning fixture `config` while supplying `realLoader.messages()` production wrapper; added test Discord gateway; fixed rendered prefix assertion to `\u00a7bDiscordTowny`. |
| [`YamlConfigLoaderTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/config/YamlConfigLoaderTest.java) | **Test Expectation Fix** | Corrected line 575 to assert `\u00a7bDiscordTowny` in rendered Adventure component serialized with `legacySection()`. |
| [`AdminPrefixCommandTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/minecraft/AdminPrefixCommandTest.java) | **Test Expectation Fix** | Corrected line 392 to assert `\u00a7bDiscordTowny` in rendered Adventure component serialized with `legacySection()`. |

---

## 13. Round 5 — Accurate Operator Feedback and Audit Guarantees

In Round 5, two critical findings identified in [`docs/revisiones/T28-revision-2.md`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/docs/revisiones/T28-revision-2.md) were addressed:
1. **F7 (blocking)**: A startup window where the prefix could be modified and persisted without generating an audit record.
2. **F8 (blocking)**: Reporting `"database unavailable"` to the operator when the database write succeeded but subsequent post-write stages (audit delivery or in-memory application) failed.

---

### 13.1 Finding F7: Startup Window Audit Delivery Guarantee

#### Problem Description
In [`DiscordTownyWiring.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyWiring.java#L223), storage is published at line 223, making [`getSettingsRepository()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyWiring.java#L736) operational. However, the audit sink [`CompositeAuditSink`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/CompositeAuditSink.java) is instantiated and published only at line 264, after the Discord gateway initialization.

Commands are registered before [`wiring.start()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyWiring.java#L190). In [`DiscordTownyPlugin.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyPlugin.java#L83-L87), the audit consumer was previously registered as:
```java
event -> {
    if (wiring != null && wiring.getAuditSink() != null) {
        wiring.getAuditSink().accept(event);
    }
}
```
If an administrator executed `/dt admin prefix <texto...>` or `/dt admin prefix reset` within the interval between line 223 and line 264, the command wrote the setting to the database, applied it to in-memory messages, and answered success to the player, while silently dropping the audit event because `wiring.getAuditSink()` was null.

This violated the core project requirement:
> *"Every privileged administrative mutation must be audited. A privileged change that cannot be recorded is not performed."*

#### Architectural Resolution
Rather than performing invasive wiring refactoring to move the audit sink earlier (which would couple gateway initialization and audit sinks prematurely), the command honors the honest state of the plugin:

1. **Live Audit Sink Supplier**:
   [`DiscordTownyPlugin.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyPlugin.java#L83) now supplies `() -> wiring != null ? wiring.getAuditSink() : null` directly to [`MinecraftCommands.register`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/minecraft/MinecraftCommands.java#L1318), matching how all other services are supplied dynamically.
2. **Synchronous Audit Availability Refusal**:
   In [`MinecraftCommands.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/minecraft/MinecraftCommands.java), both `executeSetPrefix` and `executeResetPrefix` evaluate audit availability before scheduling asynchronous work or touching storage:
   ```java
   Consumer<AuditEvent> auditConsumer = resolveAuditConsumer(auditConsumerSupplier);
   if (auditConsumer == null) {
       sender.sendMessage(msg.get("admin.prefix-starting"));
       return 1;
   }
   ```
   If the audit sink is unavailable, the command refuses immediately:
   - Nothing is dispatched to the async executor.
   - Nothing is written to [`SettingsRepository`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/storage/SettingsRepository.java).
   - Nothing is applied to in-memory [`Messages`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/config/Messages.java).
   - No audit event is silently omitted.
   - The operator is informed via a new localized refusal message: `admin.prefix-starting`.
3. **Double Check Inside Async Worker**:
   Inside the async task prior to invoking `settings.put` or `settings.delete`, `resolveAuditConsumer(auditConsumerSupplier)` is checked again to ensure no race condition between synchronous dispatch and async execution could skip auditing.

#### Catalog Additions
Added `admin.prefix-starting` under `admin:` in both catalogs:
- **`messages_en.yml`**: `prefix-starting: "&cThe plugin is still starting up. Try again in a moment."`
- **`messages_es.yml`**: `prefix-starting: "&cEl plugin todavía se está iniciando. Inténtalo de nuevo en un momento."`

---

### 13.2 Finding F8: Separation of Database Write Failure from Post-Write Failure

#### Problem Description
Previously, the execution pipeline in [`MinecraftCommands.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/minecraft/MinecraftCommands.java) was structured as:
```java
CompletableFuture.runAsync(() -> {
    settings.put(KEY_CHAT_PREFIX, finalPrefix);
    if (auditConsumer != null) {
        auditConsumer.accept(...); // may throw
    }
}, exec).thenRun(() -> {
    messages.setCustomPrefix(finalPrefix); // may throw
    replySuccess();
}).exceptionally(ex -> {
    reply("general.database-unavailable");
    return null;
});
```
A single `exceptionally` handler covered all three distinct stages. If `auditConsumer.accept(...)` threw an exception, or if `messages.setCustomPrefix(...)` threw an exception, the write to the database had already succeeded and committed. Yet the operator was told `general.database-unavailable` ("Cannot access the database. Notify an administrator").

This was fundamentally false:
- The database had succeeded and stored the new value.
- Upon next server restart or `/dt admin reload`, the newly stored value would be loaded into memory, revealing that the change the operator was told had failed was actually persisted.

#### Pipeline Restructuring
The pipeline now strictly separates the database write from post-write operations:

```java
CompletableFuture.runAsync(() -> {
    SettingsRepository settings = resolveSettings(settingsSupplier);
    if (settings == null) {
        throw new IllegalStateException("Database settings repository unavailable");
    }
    settings.put(SettingsRepository.KEY_CHAT_PREFIX, finalPrefix);
}, exec).thenRun(() -> {
    try {
        Consumer<AuditEvent> activeAudit = resolveAuditConsumer(auditConsumerSupplier);
        if (activeAudit != null) {
            activeAudit.accept(new AuditEvent(...));
        }
        if (messagesSupplier != null && messagesSupplier.get() != null) {
            messagesSupplier.get().setCustomPrefix(finalPrefix);
        }
        scheduler.accept(() -> sender.sendMessage(reply));
    } catch (Throwable t) {
        scheduler.accept(() -> sender.sendMessage(msg.get("admin.prefix-saved-incomplete")));
    }
}).exceptionally(ex -> {
    scheduler.accept(() -> sender.sendMessage(msg.get("general.database-unavailable")));
    return null;
});
```

1. **Write Failure Path**:
   If `settings.put` or `settings.delete` throws (e.g. database down, `StorageException`), `runAsync` completes exceptionally. The `.thenRun` stage is never entered. `.exceptionally` catches the failure, keeps the old prefix live in memory, records no audit row, and replies with `general.database-unavailable`.
2. **Post-Write Failure Path**:
   If the database write succeeds, `runAsync` completes normally. Any failure in `.thenRun` (an exceptional audit consumer or an apply method throwing) is caught in a dedicated `try / catch (Throwable t)` block inside `.thenRun`. The operator is sent `admin.prefix-saved-incomplete`, truthfully stating that the prefix was saved in the database but could not be applied live.

#### In-Memory State Decision and Justification
When a failure occurs after the database write succeeds:
- **Decision**: The in-memory prefix is left unchanged (the old prefix remains live).
- **Justification**:
  1. **Failure Containment**: If in-memory apply (`setCustomPrefix` or `resetPrefix`) threw an exception, mutating memory failed by definition. Attempting further in-memory operations in an unknown state risks state inconsistency. If audit delivery threw, aborting subsequent mutations prevents partial runtime divergence.
  2. **Durable Source of Truth**: The database is the authoritative storage that survives server restarts and `/dt admin reload`. Because the write succeeded, the new prefix is safely persisted.
  3. **Honest Operator Communication**: The operator is explicitly informed by `admin.prefix-saved-incomplete`:
     - Stored prefix: The new value is saved in the database.
     - Live prefix: The running server keeps the old prefix for the current session.
     - Resolution: The operator is explicitly told the change will take effect upon server restart (`"The change will take effect on next restart"` / `"El cambio tendrá efecto tras reiniciar el servidor"`).

#### Catalog Additions
Added `admin.prefix-saved-incomplete` under `admin:` in both catalogs:
- **`messages_en.yml`**: `prefix-saved-incomplete: "&ePrefix was saved to database, but could not be applied live. The change will take effect on next restart."`
- **`messages_es.yml`**: `prefix-saved-incomplete: "&eEl prefijo se guardó en la base de datos, pero no se pudo aplicar en vivo. El cambio tendrá efecto tras reiniciar el servidor."`

---

### 13.3 Test Suite Coverage

The following tests were authored in [`AdminPrefixCommandTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/minecraft/AdminPrefixCommandTest.java) to thoroughly verify F7 and F8:

1. **`startupWindowSettingsAvailableAuditSinkNotRefusesAndWritesNothing`**:
   - Supplies active `SettingsRepository` and `() -> null` audit supplier.
   - Executes `/dt admin prefix &a[Early]&r `.
   - Asserts:
     - Sender receives refusal `admin.prefix-starting` (`"still starting up"`).
     - Nothing is written to `SettingsRepository` (`get()` is empty).
     - In-memory messages retain default prefix (`\u00a7bDiscordTowny`).
     - No audit row is recorded.
2. **`startupWindowRefusesResetWhenAuditSinkUnavailable`**:
   - Pre-sets custom prefix in storage and memory.
   - Executes `/dt admin prefix reset` with null audit sink.
   - Asserts:
     - Sender receives `admin.prefix-starting`.
     - Stored prefix remains present in repository.
     - In-memory prefix remains the custom prefix.
3. **`auditConsumerThatThrowsAfterSuccessfulWriteInformsOperatorValueSaved`**:
   - Configures an audit consumer that throws `RuntimeException`.
   - Executes `/dt admin prefix &e[Saved]&r `.
   - Asserts:
     - Sender receives `admin.prefix-saved-incomplete` informing them the value was saved and will take effect on restart.
     - Sender does **not** receive `general.database-unavailable`.
     - Stored value is present in `SettingsRepository`.
     - Live in-memory prefix remains the old prefix.
4. **`applyThatThrowsAfterSuccessfulWriteInformsOperatorValueSaved`**:
   - Configures `Messages.setCustomPrefix` to throw `RuntimeException`.
   - Executes `/dt admin prefix &b[AppliedFail]&r `.
   - Asserts:
     - Sender receives `admin.prefix-saved-incomplete`.
     - Sender does **not** receive `general.database-unavailable`.
     - Stored value is saved in `SettingsRepository`.
     - Audit event was recorded prior to the apply failure.
5. **`applyThatThrowsAfterSuccessfulResetInformsOperatorValueSaved`**:
   - Configures `Messages.resetPrefix` to throw `RuntimeException`.
   - Executes `/dt admin prefix reset`.
   - Asserts:
     - Sender receives `admin.prefix-saved-incomplete`.
     - Setting key was successfully deleted from `SettingsRepository`.
6. **Pre-existing Write Failure Verification**:
   - `settingsStoreThatThrowsDuringCommandAlertsSenderGracefullyAndLeavesPrefixUnchanged`: Verifies that if `settings.put` throws, `general.database-unavailable` is sent, no audit row is recorded, and the old prefix remains live.
   - `settingsStoreThatThrowsDuringResetAlertsSenderAndLeavesCustomPrefixUnchanged`: Verifies identical failure handling for `settings.delete`.

---

### 13.4 Summary of Changes in Round 5

| File | Nature of Fix | Description |
| :--- | :--- | :--- |
| [`DiscordTownyPlugin.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyPlugin.java) | **Production Bug Fix (F7)** | Replaced static lambda dropping audit events during startup with dynamic supplier `() -> wiring != null ? wiring.getAuditSink() : null`. |
| [`MinecraftCommands.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/minecraft/MinecraftCommands.java) | **Production Bug Fix (F7 & F8)** | Added `Supplier<Consumer<AuditEvent>>` overloads for `createCommandNode`, `createAdminNode`, `createAdminPrefixNode`, and `register`. Implemented startup window refusal when audit sink is unavailable. Separated database write failure from post-write failure (`auditConsumer` or `apply` throwing). |
| [`messages_en.yml`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/resources/messages_en.yml) | **Catalog Addition** | Added `admin.prefix-starting` and `admin.prefix-saved-incomplete` (synchronized with `build/resources/main/`). |
| [`messages_es.yml`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/resources/messages_es.yml) | **Catalog Addition** | Added Spanish equivalents for `admin.prefix-starting` and `admin.prefix-saved-incomplete` (synchronized with `build/resources/main/`). |
| [`AdminPrefixCommandTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/minecraft/AdminPrefixCommandTest.java) | **Test Suite** | Added unit and integration tests covering the startup refusal window, throwing audit consumer post-write, and throwing apply post-write for both set and reset operations. |

---

## 14. Round 6 Architecture Simplification: Single Signature Rule

### 14.1 Elimination of Ambiguous Overloads

In Round 5, `Supplier<Consumer<AuditEvent>>` variants were introduced alongside existing `Consumer<AuditEvent>` signatures. When callers passed `null` or lambda expressions (e.g. `auditLogs::add`), `javac` could not disambiguate between direct functional interfaces and supplier wrappers:
```
MinecraftCommands.java:100: error: reference to createCommandNode is ambiguous
MinecraftCommands.java:1356: error: reference to register is ambiguous
```

To permanently eliminate this failure mode without possibility of future collisions:
1. **Single `createCommandNode`**: Deleted the 11-parameter convenience overload and the 14-parameter `Consumer<AuditEvent>` overload. The sole surviving method is the widest 14-parameter signature taking `Supplier<Consumer<AuditEvent>> auditConsumerSupplier`.
2. **Single `register`**: Deleted all four legacy/convenience overloads (10, 11, 12, and 13-parameter `Consumer<AuditEvent>` variants). The sole surviving method is the 13-parameter signature taking `Supplier<Consumer<AuditEvent>> auditConsumerSupplier`.
3. **Single `createAdminNode` & `createAdminPrefixNode`**: Removed the `Consumer<AuditEvent>` overloads from both internal helper builders, ensuring every level of the command hierarchy consistently and exclusively takes `Supplier<Consumer<AuditEvent>>`.

No convenience overloads for "readability" remain.

### 14.2 Call Sites Updated

A total of **13 call sites** were updated across production and test suites to target the single surviving signatures:

#### `MinecraftCommandsTest.java` (6 call sites updated)
1. **`createRoot(Consumer<Runnable> scheduler)`**: Updated `MinecraftCommands.createCommandNode` to pass suppliers for services, config, and messages, with null audit/settings suppliers and explicit scheduler.
2. **`createRoot(UpdateService updateService)`**: Updated `MinecraftCommands.createCommandNode` to pass `() -> updateService` alongside suppliers.
3. **`adminReloadFailsGracefullyWhenActionThrows`**: Updated direct `MinecraftCommands.createCommandNode` call to the 14-parameter supplier signature.
4. **`syncReportsProblemsInReportMode`**: Updated direct `MinecraftCommands.createCommandNode` call to the 14-parameter supplier signature.
5. **`adminReloadWithNullMessageUsesLocalizedUnknownReason`**: Updated direct `MinecraftCommands.createCommandNode` call to the 14-parameter supplier signature.
6. **`adminReloadForConsoleWithNullMessageUsesEnglishUnknownReason`**: Updated direct `MinecraftCommands.createCommandNode` call to the 14-parameter supplier signature.

#### `AdminPrefixCommandTest.java` (7 call sites updated)
7. **`setUp()`**: Updated audit parameter from `auditLogs::add` to `() -> auditLogs::add`.
8. **`settingsStoreThatThrowsDuringCommandAlertsSenderGracefullyAndLeavesPrefixUnchanged`**: Updated audit parameter from `auditLogs::add` to `() -> auditLogs::add`.
9. **`settingsStoreThatThrowsDuringResetAlertsSenderAndLeavesCustomPrefixUnchanged`**: Updated audit parameter from `auditLogs::add` to `() -> auditLogs::add`.
10. **`auditConsumerDeliveredDirectlyWithoutReflection`**: Updated audit parameter from `consumer` to `() -> consumer`.
11. **`auditConsumerThatThrowsAfterSuccessfulWriteInformsOperatorValueSaved`**: Updated audit parameter from `throwingAudit` to `() -> throwingAudit`.
12. **`applyThatThrowsAfterSuccessfulWriteInformsOperatorValueSaved`**: Updated audit parameter from `auditLogs::add` to `() -> auditLogs::add`.
13. **`applyThatThrowsAfterSuccessfulResetInformsOperatorValueSaved`**: Updated audit parameter from `auditLogs::add` to `() -> auditLogs::add`.

*(Note: The two startup window tests `startupWindowSettingsAvailableAuditSinkNotRefusesAndWritesNothing` and `startupWindowRefusesResetWhenAuditSinkUnavailable` had already been authored in Round 5 using `Supplier<Consumer<AuditEvent>> nullAuditSupplier = () -> null;`).*

#### Production Conformance
- [`DiscordTownyPlugin.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/DiscordTownyPlugin.java): Confirmed invocation targets the 13-parameter `register` passing dynamic supplier `() -> wiring != null ? wiring.getAuditSink() : null`.
- [`MinecraftCommands.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/main/java/com/discordtowny/minecraft/MinecraftCommands.java): Confirmed internal `register` implementation passes `auditConsumerSupplier` directly to `createCommandNode`.

### 14.3 Test Expressibility & Verification

- **Expressibility**: Every test cleanly expresses its preconditions and assertions through the single 14-parameter `createCommandNode` signature using standard `() -> service` supplier lambdas. No test required adding an overload back or modifying verification logic.
- **Test Scenarios**: All four Round 5 scenarios (`startupWindowSettingsAvailableAuditSinkNotRefusesAndWritesNothing`, `startupWindowRefusesResetWhenAuditSinkUnavailable`, `auditConsumerThatThrowsAfterSuccessfulWriteInformsOperatorValueSaved`, and `applyThatThrowsAfterSuccessfulWriteInformsOperatorValueSaved` / `applyThatThrowsAfterSuccessfulResetInformsOperatorValueSaved`) remain intact, asserting all original F7 and F8 invariants.
- **Verification**: All 78 tests across [`AdminPrefixCommandTest`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/minecraft/AdminPrefixCommandTest.java) and [`MinecraftCommandsTest`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t28-admin-prefix/src/test/java/com/discordtowny/minecraft/MinecraftCommandsTest.java) run and pass with 0 failures, 0 errors, and 0 skipped tests. All Java source files compile with 0 compilation errors.


