# T25 Implementation Report — Admin Join Update Notice

Branch: `fix/update-join-notice`  
Task: **T25 — the admin who joins is told the update exists**

---

## 1. Overview

Before this task, `DefaultUpdateService.notifyAdminOnJoin(Consumer<String>)` existed and was unit-tested, but was never wired to any Bukkit event listener. As a consequence, administrators joining the server were never notified when an update was available, breaking, downloaded, or when the update check failed.

This task resolves the issue by creating a dedicated listener (`UpdateJoinListener`) and registering it in `DiscordTownyPlugin` alongside `PlayerJoinSyncListener`.

---

## 2. What Was Wired and Where

### A. Contract Addition in `UpdateService`
- **File**: [`src/main/java/com/discordtowny/update/UpdateService.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/UpdateService.java)
- Added `default boolean shouldNotifyAdminsOnJoin()` and `default void notifyAdminOnJoin(Consumer<String> messageSender)` to the interface contract.
- These methods match the exact signatures already implemented in [`DefaultUpdateService.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L712-L757). Neither `DefaultUpdateService.java` nor its existing notice formatting logic was modified.

### B. Dedicated Listener: `UpdateJoinListener`
- **File**: [`src/main/java/com/discordtowny/minecraft/UpdateJoinListener.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/minecraft/UpdateJoinListener.java)
- Listens to `PlayerJoinEvent` at `EventPriority.MONITOR`.
- Follows the constructor and registration pattern of `PlayerJoinSyncListener`, taking suppliers (`Supplier<UpdateService>` and `BooleanSupplier degradedSupplier`) so wiring remains lazy.
- On player join:
  1. Checks if the player has `MinecraftCommands.PERMISSION_ADMIN` (`discordtowny.admin`). If not, returns immediately.
  2. Checks if the plugin is in degraded mode (`degradedSupplier.getAsBoolean()`). If so, returns immediately without logging warnings.
  3. Checks if the update service is null. If so, returns immediately without logging warnings.
  4. Checks if admin join notifications are enabled (`updateService.shouldNotifyAdminsOnJoin()`). If not, returns immediately.
  5. Invokes `updateService.notifyAdminOnJoin(player::sendMessage)`, which delivers the cached update notice directly to the player without starting any network check.

### C. Wiring in `DiscordTownyPlugin`
- **File**: [`src/main/java/com/discordtowny/DiscordTownyPlugin.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/DiscordTownyPlugin.java#L65-L71)
- Registered `UpdateJoinListener.register(this, w::getUpdateService, w::isDegraded);` beside `PlayerJoinSyncListener.register(...)` inside the post-start callback.

---

## 3. Design Decision: Dedicated Listener vs. `PlayerJoinSyncListener`

### Decision
We implemented the notice in a **small dedicated listener (`UpdateJoinListener`) registered beside `PlayerJoinSyncListener`**, rather than modifying `PlayerJoinSyncListener`.

### Rationale
1. **Single Responsibility Principle (SRP)**:
   - `PlayerJoinSyncListener` is explicitly designed and documented to reconcile Discord roles and update last known player names in `LinkRepository`.
   - Update checking and admin alerting belong to the update subsystem, not account or town synchronization.
2. **Maintainability and Cognitive Load**:
   - As noted in the task specification: *"A class whose name says 'sync' quietly growing an update notice is the kind of thing the next reader trips over."*
   - Placing update notifications in an `Update*` listener keeps the codebase intuitive for future maintainers.
3. **Decoupled Dependencies**:
   - `PlayerJoinSyncListener` requires `LinkRepository` and `SyncService`. Update notifications do not touch storage or sync.
   - Forcing `PlayerJoinSyncListener` to accept `UpdateService` would introduce unnecessary coupling between unrelated subsystems.
4. **Isolated Failure Domain & Testing**:
   - A separate listener allows independent unit testing without needing to mock database repositories, schedulers, or synchronization services.

---

## 4. Tests

- **File**: [`src/test/java/com/discordtowny/minecraft/UpdateJoinListenerTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/test/java/com/discordtowny/minecraft/UpdateJoinListenerTest.java)

The test suite tests directly through `UpdateJoinListener` and covers all five required behaviors plus edge cases:

1. **Admin joins, update available**:
   `adminJoinsWithUpdateAvailable_noticeReachesPlayer` verifies that when an administrator joins, the formatted notice from the service reaches `player.sendMessage(...)`. Also verified with multiple notice lines in `adminJoinsWithMultipleNotices_allNoticesReachPlayer`.
2. **Non-admin joins**:
   `nonAdminJoins_nothingIsSent` verifies that a player without `discordtowny.admin` triggers no messages and no interaction with `notifyAdminOnJoin`.
3. **Config switch off**:
   `configSwitchOff_nothingIsSentEvenForAdmin` verifies that when `shouldNotifyAdminsOnJoin()` returns `false`, nothing is sent to an administrator.
4. **Update service null or plugin degraded**:
   - `updateServiceNull_nothingSentAndNoWarningLogged` verifies that a null update service results in no messages and zero warning-level log records.
   - `pluginDegraded_nothingSentAndNoWarningLogged` verifies that degraded mode results in no messages, no service calls, and zero warning-level log records.
5. **Join does not start a check**:
   `joinDoesNotStartACheck_standInRecordsZeroChecks` verifies with `RecordingUpdateService` that `checkForUpdate()` is called exactly 0 times upon player join.
6. **Integration with `DefaultUpdateService`**:
   `integrationWithDefaultUpdateService_sendsCachedNoticeWithoutCheck` tests `UpdateJoinListener` against a real `DefaultUpdateService` instance with cached results, confirming the notice reaches the player without generating any extra HTTP requests.
7. **Robustness and lazy wiring**:
   `nullEventOrPlayer_safelyIgnored` and `lazySupplierReflectsChangesToUpdateService` verify null-safety and lazy resolution over time.

---

## 5. What Could Not Be Verified

- **Automated Gradle Build (`./gradlew test`)**:
  Per explicit instructions (*"Do not run `./gradlew`"* and `AGENTS.md`), Gradle was not executed in this environment. Verification was performed via structural inspection, type alignment, and unit test design matching existing codebase patterns.
- **Live Paper Server**:
  A live Paper server was not launched to simulate network player packets.

---

## 6. Round 2 — Remediation of F1, F2, and F3

Following the first review in [`docs/revisiones/T25-revision-1.md`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/docs/revisiones/T25-revision-1.md), the task scope was broadened to include [`src/main/java/com/discordtowny/update/`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/) and [`src/main/java/com/discordtowny/DiscordTownyWiring.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/DiscordTownyWiring.java) to resolve the root causes of all three findings.

### A. F2 (Blocking): Eliminating Filesystem Access on the Join Thread

#### The Problem
[`PlayerJoinEvent`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/minecraft/UpdateJoinListener.java) executes synchronously on Paper's server main thread. The pre-existing [`DefaultUpdateService.notifyAdminOnJoin`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L747) called [`isUpdatePending()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L503), which executed synchronous `Files.isRegularFile` and `Files.size` metadata checks against disk. A slow, networked, or stalled filesystem would directly stall player joins on the main server thread.

#### The Solution
1. **Cached Pending State**:
   - Introduced `private final AtomicBoolean stagedUpdatePending = new AtomicBoolean(false);` in [`DefaultUpdateService`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java).
   - Added [`isCachedUpdatePending()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/UpdateService.java#L44) to the [`UpdateService`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/UpdateService.java) interface and implemented it in [`DefaultUpdateService`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L515).
2. **Off-Thread Startup Seeding**:
   - During [`DefaultUpdateService`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java) initialization (which runs on a virtual thread off the server main thread during normal plugin startup), `probeDiskForStagedUpdate()` checks whether a verified staged jar already exists in `updateFolder` and seeds `stagedUpdatePending`.
3. **Lifecycle Synchronization**:
   - When [`performDownload`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L1751) completes publication of the staged jar into the update folder, `stagedUpdatePending.set(true)` is recorded in memory.
   - If publication fails and rolls back in [`publishExecutable`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L1837), `stagedUpdatePending` is re-probed from disk.
   - Whenever background checks run in [`doCheckForUpdate`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java), `stagedUpdatePending` is refreshed.
4. **Zero Filesystem Access on Join**:
   - [`notifyAdminOnJoin`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L747) now checks `if (stagedUpdatePending.get())` directly instead of calling [`isUpdatePending()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L503).
   - Zero `Files` calls or disk I/O are performed anywhere on the join path.

#### Design Decision: Retaining Current Behaviour in `isUpdatePending()`
We chose to **keep the current disk-querying behaviour for existing callers of [`isUpdatePending()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L503)**, while updating the cache on every invocation:
- **Rationale**:
  - Existing callers include commands executed deliberately by operators, specifically `/dt admin update` and `/dt admin update status` in [`MinecraftCommands.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/minecraft/MinecraftCommands.java#L1020). An operator checking update status expects physical ground truth from the filesystem (for instance, detecting if a staged jar was manually dropped into or deleted from `update/` via SFTP/SSH).
  - Existing test suites in [`DefaultUpdateServiceTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/test/java/com/discordtowny/update/DefaultUpdateServiceTest.java) and [`MinecraftCommandsTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/test/java/com/discordtowny/minecraft/MinecraftCommandsTest.java) directly manipulate disk files and assert [`isUpdatePending()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L503).
  - By having [`isUpdatePending()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L503) query the disk and synchronize `stagedUpdatePending.set(exists)`, both command consumers and existing tests retain full physical fidelity, while the sensitive join thread consumes strictly the cached in-memory state.

---

### B. F1 (Important): Listener Registration Survives Degraded Recovery

#### The Problem
During an initial degraded startup (e.g. database offline), post-start was dispatched while degraded, causing [`DiscordTownyPlugin`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/DiscordTownyPlugin.java#L64) to skip registering event listeners. When an administrator later ran `/dt admin reload`, [`DiscordTownyWiring.reload()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/DiscordTownyWiring.java#L451) successfully re-established storage and constructed the [`DefaultUpdateService`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java), but never dispatched post-start again. As a result, [`UpdateJoinListener`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/minecraft/UpdateJoinListener.java) remained permanently unregistered.

#### The Solution
1. **Recovery Dispatch in Wiring**:
   - In [`DiscordTownyWiring.reload()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/DiscordTownyWiring.java#L451), we track `boolean wasDegraded = this.degraded;`.
   - When storage recovers and domain services + updater are initialized (`if (!degraded && storage != null)`), if `wasDegraded` was true, wiring calls `dispatchPostStart(false)`.
2. **Exact-Once Idempotent Registration**:
   - In [`DiscordTownyPlugin.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/DiscordTownyPlugin.java), introduced `AtomicBoolean updateListenerRegistered` and `AtomicBoolean syncListenersRegistered`.
   - In `registerListeners(DiscordTownyWiring w)`, listener registration is guarded with `compareAndSet(false, true)`:
     ```java
     if (updateListenerRegistered.compareAndSet(false, true)) {
         UpdateJoinListener.register(this, w::getUpdateService, w::isDegraded);
     }
     ```
   - In [`UpdateJoinListener.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/minecraft/UpdateJoinListener.java#L45), added `REGISTERED_PLUGINS` tracking set to guarantee that even direct invocations of `register(Plugin, ...)` cannot register duplicate listener instances with Paper's `PluginManager`.
   - On a degraded start, 0 registrations occur. On the first recovery reload, the listener is registered once. On subsequent reloads, `compareAndSet` and the `REGISTERED_PLUGINS` guard prevent any duplicate registration, ensuring the join notice arrives exactly once per join.

---

### C. F3 (Important): Failed Notice Visibility and Context

#### The Problem
In [`UpdateJoinListener.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/minecraft/UpdateJoinListener.java):
1. `catch (Throwable)` swallowed severe JVM errors (such as `OutOfMemoryError` or `LinkageError`).
2. Logging at `Level.FINE` suppressed failure messages under standard production logging configurations, hiding failed notifications from operators.
3. The log message provided no context on which player failed to be notified or why.

#### The Solution
- Modified the catch block in [`UpdateJoinListener.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/minecraft/UpdateJoinListener.java#L82) to catch recoverable `Exception` instead of `Throwable`. Fatal JVM errors now propagate normally.
- Elevated the log level to `Level.WARNING`.
- Contextualized the log message with the player's username, player UUID, and the exception message:
  ```java
  try {
      updateService.notifyAdminOnJoin(player::sendMessage);
  } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Failed to notify admin " + player.getName() + " (" + player.getUniqueId() + ") on join: " + e.getMessage(), e);
  }
  ```
- Any delivery failure is caught and logged without disrupting player connection or throwing out of `onPlayerJoin`.
- Silent paths (non-admin, degraded mode, null service, disabled config) return before the try block, remaining completely silent.

---

### D. Comprehensive Verification and Tests Added

1. **F2 (No Filesystem Access on Join Path)**:
   - [`joinPathPerformsNoFilesystemAccess_standInThrowsIfDiskChecked`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/test/java/com/discordtowny/minecraft/UpdateJoinListenerTest.java#L358): A stand-in [`UpdateService`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/UpdateService.java) throws `AssertionError` if [`isUpdatePending()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L503) is called. Verifies the notice is delivered and `isUpdatePending()` is never invoked on player join.
   - [`defaultUpdateService_notifyAdminOnJoinReadsCachedStateWithoutDisk`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/test/java/com/discordtowny/minecraft/UpdateJoinListenerTest.java#L430): Subclasses [`DefaultUpdateService`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java) to fail if [`isUpdatePending()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L503) is called during [`notifyAdminOnJoin`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/update/DefaultUpdateService.java#L747). Verifies `stagedUpdatePending` is read and delivered with zero disk I/O.
   - [`cachedUpdatePending_seededAtStartupAndUpdatedOnDownload`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/test/java/com/discordtowny/update/DefaultUpdateServiceTest.java#L6038): Tests that pre-existing staged jars seed `isCachedUpdatePending()` during initialization, and download completion updates it.
2. **F3 (Visible Failure Reporting and Non-Breaking Join)**:
   - [`failedNoticeLogsWarningWithPlayerAndFailure_doesNotBreakJoin`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/test/java/com/discordtowny/minecraft/UpdateJoinListenerTest.java#L487): Asserts that an exception thrown by `notifyAdminOnJoin` does not break `onPlayerJoin` and logs at `Level.WARNING` containing the player's name, UUID, and exception message.
   - [`fatalJvmErrorPropagatesOut`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/test/java/com/discordtowny/minecraft/UpdateJoinListenerTest.java#L518): Asserts that `OutOfMemoryError` propagates out of `onPlayerJoin`.
3. **F1 (Lifecycle Recovery and Exact-Once Registration)**:
   - [`lifecycle_degradedStartThenRecoveryRegistersOnce_secondRecoveryDoesNotDuplicate`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/test/java/com/discordtowny/minecraft/UpdateJoinListenerTest.java#L537): Exercises the full lifecycle through [`DiscordTownyWiring`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/DiscordTownyWiring.java):
     1. Starts degraded: 0 listeners registered, player join receives nothing.
     2. Reload recovers storage and updater: listener registered once, player join receives notice.
     3. Reload a second time: listener is NOT registered again (count remains 1), player join still receives notice once.
   - [`updateJoinListenerRegister_idempotentPerPlugin`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/test/java/com/discordtowny/minecraft/UpdateJoinListenerTest.java#L625): Asserts Paper's `PluginManager.registerEvents` is called exactly once when `UpdateJoinListener.register` is invoked repeatedly for the same plugin.
   - [`reloadAfterDegradedStartRecoversAndDispatchesPostStartOnce`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/test/java/com/discordtowny/DiscordTownyPluginTest.java#L560): Verifies [`DiscordTownyWiring.reload()`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t25-join-notice/src/main/java/com/discordtowny/DiscordTownyWiring.java#L451) dispatches post-start on degraded recovery, and does not re-dispatch on subsequent reloads.

