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
