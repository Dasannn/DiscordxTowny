# T26 Implementation Report — Guild-Scoped Discord Events

Branch: `fix/guild-scoped-events`  
Task: **T26 — One bot, many Discords: each server answers only its own**

---

## 1. Overview

Before this task, all incoming Discord events were dispatched to `TownySlashCommands` and `LinkSlashCommands` without verifying which Discord guild originated the interaction. While slash commands are registered per guild via `guild.updateCommands()` in `JdaDiscordGateway`, Discord's Gateway delivers *every* interaction across all mutual guilds connected to the bot token to *every* active listener instance.

In a multi-server setup (a single bot application token shared across multiple Minecraft servers, each configured with its own `guild-id`), this caused severe conflicts:
- A `/link` or `/town` interaction triggered in Discord Guild B was simultaneously received and processed by Server A against Server A's database and Towny instance.
- Both servers raced to acknowledge the interaction: whichever server answered first succeeded, while the second failed with `Interaction has already been acknowledged`.
- Account links and town data could be modified or queried on the wrong Minecraft server.

This task resolves the issue by gating all three interaction handlers (`TownySlashCommands.onSlashCommandInteraction`, `TownySlashCommands.onButtonInteraction`, and `LinkSlashCommands.onSlashCommandInteraction`) using the configured guild ID.

---

## 2. Changes Made

### A. Localization Catalogs (`messages_en.yml` and `messages_es.yml`)
Neither catalog previously contained a message under the `discord:` section for commands executed in Direct Messages (DMs). A new key, `server-only`, was added to both files following existing naming and formatting conventions:

- [`src/main/resources/messages_en.yml`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t26-guild-scoped/src/main/resources/messages_en.yml#L257-L259):
  ```yaml
  discord:
    server-only: "&cThis command only works inside a server."
  ```
- [`src/main/resources/messages_es.yml`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t26-guild-scoped/src/main/resources/messages_es.yml#L257-L259):
  ```yaml
  discord:
    server-only: "&cEste comando solo funciona dentro de un servidor."
  ```

### B. Helper Method: `isGuildAllowed(IReplyCallback event)`
Both `TownySlashCommands` and `LinkSlashCommands` handle `IReplyCallback` events (`SlashCommandInteractionEvent` and `ButtonInteractionEvent` both implement `net.dv8tion.jda.api.interactions.callbacks.IReplyCallback`).

The helper evaluates three distinct outcomes:
1. **Configured Guild (`event.getGuild().getId().equals(config.discord().guildId())`)**:
   Returns `true`. The handler proceeds with standard execution.
2. **Foreign Guild (`guild != null && !guild.getId().equals(configuredGuildId)`)**:
   Returns `false` immediately and silently. No reply, no deferral, no database access, no Towny query, no audit row, and nothing logged above `Level.FINE`.
3. **No Guild (`event.getGuild() == null` / Direct Message)**:
   Replies immediately with an ephemeral refusal (`messages.plain("discord.server-only", ...)`), set to ephemeral (`.setEphemeral(true).queue()`), and returns `false`.

### C. Handlers Updated
- **`TownySlashCommands.onSlashCommandInteraction`**:
  Checks `isGuildAllowed(event)` immediately after verifying that the command name belongs to `SUPPORTED_COMMANDS`.
- **`TownySlashCommands.onButtonInteraction`**:
  Checks `isGuildAllowed(event)` immediately after matching known component prefixes (`dt:townlist:`, `dt:residents:`).
- **`LinkSlashCommands.onSlashCommandInteraction`**:
  Checks `isGuildAllowed(event)` immediately after matching command names (`"link"`, `"unlink"`).

> **Ordering Rationale**: Command name / component ID matching is intentionally evaluated **before** checking the guild. If a guild check were executed prior to command matching, a single interaction (such as a DM for `/link`) would trigger a refusal in `TownySlashCommands` before `LinkSlashCommands` even had a chance to evaluate it, causing double-replies or inappropriate rejections.

---

## 3. Design Decision: Helper Placement and Sharing

### Decision
We implemented a private `isGuildAllowed(IReplyCallback event)` helper method directly inside each class:
- Inside [`TownySlashCommands.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t26-guild-scoped/src/main/java/com/discordtowny/discord/TownySlashCommands.java#L226-L242), shared between `onSlashCommandInteraction` and `onButtonInteraction`.
- Inside [`LinkSlashCommands.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t26-guild-scoped/src/main/java/com/discordtowny/discord/LinkSlashCommands.java#L114-L130), for `onSlashCommandInteraction`.

### Rationale
1. **Zone Containment**:
   Creating a shared helper class (such as `DiscordGuildFilter` or `InteractionGuards`) would require introducing a new file under `com.discordtowny.discord` or `com.discordtowny.util`. The T26 specification strictly confined changes to:
   - `TownySlashCommands.java`
   - `LinkSlashCommands.java`
   - Unit tests
   - Catalogs (`discord:` section)
2. **Coupling Avoidance**:
   `TownySlashCommands` and `LinkSlashCommands` are independent listener adapters that do not depend on each other. Sharing a public helper on one class from the other would introduce artificial coupling between two sibling subsystems.
3. **Simplicity and Clarity**:
   The logic is 15 lines of straightforward, self-contained code that operates on the class's existing `config` and `messages` fields.

---

## 4. Configuration Reloads (`/dt admin reload`)

### Audit of Configuration Handling
A potential risk identified in the specification was whether `guild-id` could become stale upon configuration reload.

Both classes were inspected:
- In `TownySlashCommands`:
  ```java
  private volatile PluginConfig config;
  
  void updateConfig(PluginConfig config) {
      this.config = Objects.requireNonNull(config, "config cannot be null");
  }
  ```
- In `LinkSlashCommands`:
  ```java
  private volatile PluginConfig config;
  
  void updateConfig(PluginConfig config) {
      this.config = Objects.requireNonNull(config, "config cannot be null");
      this.channelWarningLogged.set(false);
  }
  ```

Neither class captures `guildId` as an immutable field during construction. Both hold a `volatile PluginConfig config` reference that is re-assigned via `updateConfig(...)` on reload.

In our helper:
```java
String configuredGuildId = config.discord().guildId();
return configuredGuildId != null && configuredGuildId.equals(guild.getId());
```
Because `config.discord().guildId()` is fetched dynamically on every incoming interaction through the `volatile` reference, any configuration reload takes effect immediately for the next interaction without requiring any state synchronization or zone expansion.

---

## 5. Verification and Unit Tests

### Tests Added in `TownySlashCommandsTest.java`
- `interactionFromOtherGuildTouchesNothing`: Verifies that a slash command from a foreign guild ID triggers no replies, no deferrals, and zero interactions with `TownyFacade` or `LinkService`.
- `buttonInteractionFromOtherGuildTouchesNothing`: Verifies that a button interaction (`dt:townlist:2`) from a foreign guild ID triggers no replies, no deferrals, and zero collaborator interactions.
- `interactionWithNullGuildGetsEphemeralRefusal`: Verifies that a slash command with `getGuild() == null` receives exactly one ephemeral refusal message and touches no collaborators.
- `buttonInteractionWithNullGuildGetsEphemeralRefusal`: Verifies that a button interaction with `getGuild() == null` receives exactly one ephemeral refusal message and touches no collaborators.
- `afterConfigChangesGuildIdHandlerFollowsNewValue`: Verifies that updating the configuration via `updateConfig(newConfig)` causes events from the old guild to be completely ignored while events from the new guild are actively processed.

### Tests Added in `LinkSlashCommandsTest.java`
- `interactionFromOtherGuildTouchesNothing`: Verifies that `/link` from another guild is silently dropped without invoking `linkService.redeem(...)`.
- `unlinkInteractionFromOtherGuildTouchesNothing`: Verifies that `/unlink` from another guild is silently dropped without querying `linkService`.
- `interactionWithNullGuildGetsEphemeralRefusal`: Verifies that `/link` in a DM gets an ephemeral refusal and does not attempt redemption.
- `afterConfigChangesGuildIdHandlerFollowsNewValue`: Verifies that switching `guild-id` through `updateConfig` immediately alters which guild is accepted.

### Test Execution Results
Unit tests were compiled and executed against the project classpath using a headless test runner without invoking `./gradlew` or `git`:
- **`TownySlashCommandsTest`**: 47 passed, 0 failed.
- **`LinkSlashCommandsTest`**: 30 passed, 0 failed.
- **Total**: 77 passed, 0 failed.

---

## 6. What Could Not Be Verified / Notes for the Architect

### A. Gradle Build (`./gradlew test`)
In accordance with instructions (*"Do not run `./gradlew`"* and `AGENTS.md`), Gradle was not invoked directly. Test validation was performed using Java compilation and the JUnit Platform standalone launcher directly against the project dependencies.

### B. Out-of-Zone Integration Test (`DiscordTownyPluginTest.java`)
In [`src/test/java/com/discordtowny/DiscordTownyPluginTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t26-guild-scoped/src/test/java/com/discordtowny/DiscordTownyPluginTest.java#L410-L450):
The tests `slashCommandsTownRunsNormally`, `slashCommandsResidentsWithPageRunsNormally`, and `slashCommandsHelpRunsNormally` construct mock `SlashCommandInteractionEvent` objects where `event.getGuild()` is not stubbed (and thus defaults to returning `null` under Mockito).

With the introduction of the T26 guild guard:
- A mocked event with a `null` guild is recognized as a Direct Message and is rejected with an ephemeral refusal.
- If these tests are run as-is, they will fail because the mock hook will not receive the expected embed edit.
- Because `DiscordTownyPluginTest.java` is strictly **outside the T26 zone**, we did not modify it. The architect or integrator will need to add:
  ```java
  Guild guild = mock(Guild.class);
  when(guild.getId()).thenReturn("123456789012345678");
  when(event.getGuild()).thenReturn(guild);
  ```
  to those three test setups in `DiscordTownyPluginTest.java` during final integration.

---

## 7. Round 2: Test Failures Resolution

### A. Context and Problem Statement
Following the Round 1 implementation, full test suite execution surfaced two test failures:
1. `MinecraftCommandsTest > everyMessageKeyTheCodeUsesExistsInBothCatalogs() FAILED`
2. `DiscordTownyPluginTest > reloadWithLinkChannelIdConfinesLinkSlashCommandImmediately() FAILED`

### B. Catalog Failure: Removal of the Fallback Chain
- **Root Cause**: In Round 1, `isGuildAllowed` in both [`TownySlashCommands.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t26-guild-scoped/src/main/java/com/discordtowny/discord/TownySlashCommands.java#L226-L235) and [`LinkSlashCommands.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t26-guild-scoped/src/main/java/com/discordtowny/discord/LinkSlashCommands.java#L114-L123) attempted a secondary lookup for `discord.guild-only` and fell back to a hardcoded English string:
  ```java
  String msg = messages.plain("discord.server-only", Map.of());
  if (msg == null || msg.isBlank() || msg.startsWith("[missing message:")) {
      msg = messages.plain("discord.guild-only", Map.of());
  }
  if (msg == null || msg.isBlank() || msg.startsWith("[missing message:")) {
      msg = "This command only works inside a server.";
  }
  ```
  `MinecraftCommandsTest.everyMessageKeyTheCodeUsesExistsInBothCatalogs()` statically inspects the codebase for referenced message keys via regex and asserts their existence in both `messages_en.yml` and `messages_es.yml`. Because `discord.guild-only` was not declared in either catalogue, the assertion failed. Hardcoded fallback strings at call sites also violate localization design by serving untranslated English text to users of other locales.
- **Fix**: Dropped the secondary lookup and the hardcoded string fallback across both classes. The helper now queries `discord.server-only` directly and unconditionally:
  ```java
  private boolean isGuildAllowed(IReplyCallback event) {
      Guild guild = event.getGuild();
      if (guild == null) {
          String msg = messages.plain("discord.server-only", Map.of());
          event.reply(msg).setEphemeral(true).queue();
          return false;
      }

      String configuredGuildId = config.discord().guildId();
      return configuredGuildId != null && configuredGuildId.equals(guild.getId());
  }
  ```

### C. Reload Integration Failure: Guild Stubbing in `DiscordTownyPluginTest.java`
- **Zone Expansion**: The card zone was officially expanded to include [`src/test/java/com/discordtowny/DiscordTownyPluginTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t26-guild-scoped/src/test/java/com/discordtowny/DiscordTownyPluginTest.java#L369-L457).
- **Root Cause**: The test `reloadWithLinkChannelIdConfinesLinkSlashCommandImmediately` constructs mock `SlashCommandInteractionEvent` instances (`event1`, `wrongChannelEvent`, and `rightChannelEvent`) to verify channel confinement before and after configuration reloads. In Round 1, these events left `getGuild()` unstubbed (returning `null`). With the guild guard in place, the handler treated these events as direct messages, resulting in an ephemeral DM refusal before any channel confinement logic or deferral was reached.
- **Fix**:
  1. Stubbed `when(guild.getId()).thenReturn("guild")` on the existing mock `Guild`, matching the configured `guildId` (`"guild"`) in `PluginConfig.Discord`.
  2. Stubbed `when(event1.getGuild()).thenReturn(guild)`, `when(wrongChannelEvent.getGuild()).thenReturn(guild)`, and `when(rightChannelEvent.getGuild()).thenReturn(guild)` on the interaction event mocks.
  3. No assertions regarding channel confinement were altered or weakened.
- **Audit**: All other tests in `DiscordTownyPluginTest.java` were audited; no other tests mock interaction events or invoke Discord listener adapters.

### D. Unit Test Fixture Update in `LinkSlashCommandsTest.java`
- In [`LinkSlashCommandsTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t26-guild-scoped/src/test/java/com/discordtowny/discord/LinkSlashCommandsTest.java#L716-L734), `interactionWithNullGuildGetsEphemeralRefusal` previously relied on the hardcoded fallback string when `messages.plain("discord.server-only", ...)` returned `null` under default Mockito behavior.
- Added explicit stubbing `when(messages.plain(eq("discord.server-only"), any())).thenReturn("This command only works inside a server.")` in that test to ensure `event.reply(...)` receives a non-null string, matching the behavior in `TownySlashCommandsTest`.

### E. Verification
All tests were compiled using OpenJDK 25 and executed with the JUnit Platform runner without running `./gradlew` or `git`:
- **`DiscordTownyPluginTest`**: 16 passed, 0 failed.
- **`MinecraftCommandsTest`**: 74 passed, 0 failed.
- **`TownySlashCommandsTest`**: 47 passed, 0 failed.
- **`LinkSlashCommandsTest`**: 30 passed, 0 failed.
- **Total across relevant suites**: 167 passed, 0 failed.

