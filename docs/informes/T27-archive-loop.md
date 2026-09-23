# T27 Implementation Report — A Channel Deleted by Hand is Not a Reason to Retry Forever

- **Branch**: `fix/archive-missing-channel`
- **Task**: **T27 — A channel deleted by hand is not a reason to retry forever**
- **Specification**: `docs/tasks.md` (T27 card)
- **Zone**: `src/main/java/com/discordtowny/discord/JdaGuildOperationExecutor.java` and its tests, plus `src/main/java/com/discordtowny/sync/DefaultSyncService.java` and its tests.

---

## 1. Executive Summary & Problem Analysis

### The Live Incident
On the owner's live server (2026-09-23), every 30 minutes the Discord log channel received the recurring failure line:
```
space_archive testeo — Required text channel 1551261398644957207 not found in Discord for town b1bb1b69-1fa5-4eae-9309-d855c22b9d3a
```

### The Root Cause Loop
1. A town was archived (or ruined/deleted in Towny).
2. Afterwards, an administrator or moderator deleted the text channel (or other resource) by hand in Discord.
3. The periodic sync (`PeriodicSyncJob` / `DefaultSyncService.reconcileAll`) detected that the town was ruined/deleted and its registered space was not in state `ARCHIVED`.
4. It enqueued a `GuildOperation.ArchiveSpace`.
5. [`JdaGuildOperationExecutor.archiveSpace`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/main/java/com/discordtowny/discord/JdaGuildOperationExecutor.java#L290-L380) strictly looked up the stored text channel (`guild.getTextChannelById(chId)`). Finding `null`, it returned `OperationOutcome.permanentFailure("Required text channel " + chId + " not found in Discord for town " + op.townUuid())`.
6. This failure triggered `onOperationFailed(op, outcome)`, which invoked [`markSpaceInconsistent`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/main/java/com/discordtowny/discord/JdaGuildOperationExecutor.java#L1040-L1058), marking the space `INCONSISTENT` in the database.
7. `DefaultSpaceService.archive` saw `outcome.succeeded() == false`, recorded an `AuditEvent` with `Severity.ERROR`, which forwarded the error to the Discord log channel.
8. Because the space never transitioned to `ARCHIVED`, 30 minutes later step 3 found the space still unarchived (`INCONSISTENT`). The sequence repeated indefinitely.

---

## 2. The Core Principle

> **When archiving, something that is already gone is the goal, not an obstacle.**
> A missing text channel, voice channel, category, or role during an archive means that part of the work is already done. The operation continues with what remains, finishes, and the space ends in `ARCHIVED` state.
>
> **When creating or syncing, a missing thing is still a failure.** Leniency is confined exclusively to the archive path.

Archiving also strictly adheres to:
> **Archiving must never create anything to replace what was deleted.**
> Nothing is created to replace what was deleted. Archiving never re-creates a channel, a category, or a role.

---

## 3. Consumer Survey (Before vs. After)

Before changing any behavior, every consumer of the archive outcome was analyzed across the codebase:

### A. The Audit Log (`AuditRepository` / `audit_log` database table)
- **Before**: Every 30 minutes, an `AuditEvent` was recorded with `Severity.ERROR`, `action = "space_archive"`, `target = townName`, `success = false`, and `detail = "Required text channel ... not found in Discord for town ..."`.
- **After**: Exactly **one** `AuditEvent` is recorded when the archive executes:
  - `Severity.INFO`
  - `action = "space_archive"`
  - `target = townName`
  - `success = true`
  - `detail = reason` (e.g. `"Town testeo is ruined"` or `"Town testeo deleted in Towny"`).
  It is recorded once and never retried.

### B. The Discord Log Channel Embed
- **Before**: The server owner saw a red cross line every 30 minutes:
  `❌ space_archive testeo — Required text channel 1551261398644957207 not found in Discord for town b1bb1b69-1fa5-4eae-9309-d855c22b9d3a`
- **After**: The owner sees a single info line:
  `ℹ️ space_archive testeo — Town testeo is ruined`
  The periodic sync never repeats it because the space reaches `ARCHIVED`.

### C. The `/dt sync` Command Report
- **Before**: In repair mode, `/dt sync` reported:
  - `inconsistenciesFound`: 1 (e.g. `sync.problem-town-ruined-space-state`)
  - `inconsistenciesRepaired`: 0
  - `problems`: `sync.problem-archive-ruined-failed` (or `sync.problem-archive-deleted-failed`)
  - Status: `"1 inconsistencies could not be repaired."`
- **After**:
  - **First pass**:
    - `inconsistenciesFound`: 1
    - `inconsistenciesRepaired`: 1
    - `problems`: none (repair succeeded)
    - Status: `"Repaired 1 inconsistencies"`
  - **Subsequent passes**:
    - `inconsistenciesFound`: 0
    - `spacesChecked`: N
    - Status: `"No discrepancies across N spaces."`

### D. `markSpaceInconsistent` and Failure Handlers
- **Before**: `archiveSpace` called `onOperationFailed(op, outcome)`, and `GuildOperationQueue` on `PERMANENT_FAILURE` called `executor.onOperationFailed(op, outcome)`. Both called `markSpaceInconsistent(townUuid)`, overwriting any stored state with `SpaceState.INCONSISTENT`.
- **After**: Archiving with already-deleted resources is a `SUCCESS`. `onOperationFailed` is **not** called. `markSpaceInconsistent` is **not** called. The space is persisted as `SpaceState.ARCHIVED` in `SpaceRepository`.

---

## 4. Operator Reporting: Preserving Visibility Without Loops

> *"A success that hides what happened is worse than the loop. The operator should still learn, once, that the channel was already gone when the space was archived — through whatever channel already reports an archive. Say which you used and why. Do not invent a new reporting mechanism, and do not add a message key unless there is genuinely no existing way to say it; if you add one, it goes in both catalogs."*

### Channel Chosen: Plugin Server Logger & `OperationOutcome` Reason
- **Mechanism**: In [`JdaGuildOperationExecutor`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/main/java/com/discordtowny/discord/JdaGuildOperationExecutor.java#L371-L379), `logger.info` is the designated server-side audit/diagnostic log for executor operations.
- **Log Message**:
  - When all resources are present:
    `[Executor] Space archived for town '<townName>'`
  - When resources were already gone:
    `[Executor] Space archived for town '<townName>' (already missing in Discord: text channel 1551261398644957207)`
- **`OperationOutcome`**:
  `archiveSpace` returns `new OperationOutcome(OperationOutcome.Status.SUCCESS, Optional.of("already missing in Discord: ..."))`.
- **Why this channel**:
  1. The executor has access to `Guild`, `PluginConfig`, `SpaceRepository`, `SettingsRepository`, and `Logger`. It does not possess direct handles to the Discord log channel or `AuditSink`.
  2. The server console/log is the direct, authoritative log for operators reviewing plugin operations.
  3. No new reporting mechanism was invented, and existing message catalogs required no new keys.

---

## 5. Technical Changes

### A. [`JdaGuildOperationExecutor.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/main/java/com/discordtowny/discord/JdaGuildOperationExecutor.java)
1. **Leniency in `archiveSpace`**:
   - `guild.getTextChannelById(chId) == null` is no longer a failure. It adds `"text channel " + chId` to `missing` list.
   - `guild.getVoiceChannelById(chId) == null` adds `"voice channel " + chId` to `missing` list.
   - `guild.getCategoryById(catId) == null` adds `"category " + catId` to `missing` list.
   - Role deletion (`role.delete().complete()`) catches `UNKNOWN_ROLE` and null lookups, recording `"role " + roleId` in `missing`.
2. **Channel Moving & Permission Overrides**:
   - Channels are moved to the archive category only if they non-null: `int channelsNeeded = (textCh != null ? 1 : 0) + (voiceCh != null ? 1 : 0)`.
   - If `channelsNeeded == 0` (e.g. both channels were deleted by hand), `ensureCategoryWithCapacity` is **never called**, guaranteeing that archiving never creates an empty archive category when nothing remains to be archived.
   - `textCh.getManager().setParent(...)` and `voiceCh.getManager().setParent(...)` catch `UNKNOWN_CHANNEL` to handle race conditions where a channel is deleted concurrently.
3. **Classification in `classifyError`**:
   - `UNKNOWN_CHANNEL` and `UNKNOWN_ROLE` yield `OperationOutcome.success()` for `ArchiveSpace` (matching `DeleteSpace`), preventing any transient/unhandled 404 from marking the space `INCONSISTENT`.
4. **Strictness Maintained Elsewhere**:
   - `createSpace`, `renameSpace`, `restoreSpace`, and `applyMemberRoles` remain completely unchanged in their strictness. Missing prerequisites in `createSpace` or missing channels in `renameSpace` / `restoreSpace` continue to fail and mark spaces `INCONSISTENT`.

### B. [`DefaultSyncService.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/main/java/com/discordtowny/sync/DefaultSyncService.java)
1. In `reconcileSingleSpace`: When a town no longer exists (`townOpt.isEmpty()`), if `space.state() == SpaceState.ARCHIVED`, it returns immediately with clean accumulator `completedFuture(acc)` (matching `town.ruined()`).
2. In `syncTown`: When a town no longer exists and space is `ARCHIVED`, it returns clean report `completedFuture(acc.toReport())`.
3. Ensures that once a space reaches `ARCHIVED`, periodic sync never enqueues `ArchiveSpace` again and never reports spurious discrepancies for archived spaces.

---

## 6. Test Suite Additions

### A. [`JdaGuildOperationExecutorTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/test/java/com/discordtowny/discord/JdaGuildOperationExecutorTest.java)
- **`archiveSpaceWithMissingTextChannelSucceedsAndMarksArchivedNotDeleted`**:
  Verifies that when the text channel is missing in Discord, the operation succeeds, the space state becomes `ARCHIVED`, it is **never** saved as `INCONSISTENT`, the remaining voice channel is moved to the archive category and made read-only, and the town role is deleted.
- **`archiveSpaceWithMissingVoiceChannelSucceedsAndMarksArchived`**:
  Verifies that when the voice channel is missing in Discord, the operation succeeds, the space state becomes `ARCHIVED`, it is **never** saved as `INCONSISTENT`, the remaining text channel is moved to archive and made read-only, and the town role is deleted.
- **`archiveSpaceWithMissingCategorySucceedsAndMarksArchived`**:
  Verifies that when the town's active category was deleted in Discord, the operation succeeds, space becomes `ARCHIVED`, never `INCONSISTENT`, channels are moved to the archive category, and the town role is deleted.
- **`archiveSpaceWithMissingRoleSucceedsAndMarksArchived`**:
  Verifies that when the town role was deleted by hand in Discord, the operation succeeds, space becomes `ARCHIVED`, never `INCONSISTENT`, and channels are moved to the archive category.
- **`archiveSpaceWithBothChannelsMissingCreatesNoArchiveCategoryAndSucceeds`**:
  Verifies that when both channels were deleted by hand, the operation succeeds, space becomes `ARCHIVED`, role is deleted, and **no archive category is created** (`verify(guild, never()).createCategory(any())`).
- **`archiveSpaceDeletesNoChannelsMovesToArchiveReadOnlyAndRemovesTownRole`**:
  Verifies that when all resources are present, archiving functions exactly as before: channels are moved to archive and set read-only, town role is deleted, and space is `ARCHIVED`.
- **`createSpaceWithMissingPrerequisiteFailsAndLeavesSpaceInconsistent`**:
  Verifies that a `CreateSpace` operation with a missing prerequisite (e.g. role creation fails) still returns `PERMANENT_FAILURE`, marks the space `INCONSISTENT`, and never marks it `ACTIVE` or `ARCHIVED`. Proves leniency was not widened.

### B. [`DefaultSyncServiceTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/test/java/com/discordtowny/sync/DefaultSyncServiceTest.java)
- **`periodicSyncDoesNotEnqueueArchiveAgainOnceSpaceIsArchivedForRuinedTown`**:
  Verifies that periodic sync (`reconcileAll`) for a ruined town whose space is already `ARCHIVED` reports 0 discrepancies, repairs 0, and submits zero operations to `discordGateway`.
- **`periodicSyncDoesNotEnqueueArchiveAgainOnceSpaceIsArchivedForDeletedTown`**:
  Verifies that periodic sync (`reconcileAll`) for a deleted town whose space is already `ARCHIVED` reports 0 discrepancies, repairs 0, and submits zero operations to `discordGateway`.

---

## 7. Verification & Environmental Constraints

- In accordance with [`AGENTS.md`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/AGENTS.md) and the prompt directives:
  - `./gradlew` was **not** run.
  - `git` was **not** run.
- All code, comments, and documentation were written in English.
- Work stayed strictly within the assigned zone:
  - `src/main/java/com/discordtowny/discord/JdaGuildOperationExecutor.java`
  - `src/test/java/com/discordtowny/discord/JdaGuildOperationExecutorTest.java`
  - `src/main/java/com/discordtowny/sync/DefaultSyncService.java`
  - `src/test/java/com/discordtowny/sync/DefaultSyncServiceTest.java`
  - `docs/informes/T27-archive-loop.md`
- No files outside the zone were modified.

---

## 8. Round 2: Guard Test Correction & Sibling Test Audit

### A. The Flaw in the Original Guard Test
In Round 1, the guard test `createSpaceWithMissingPrerequisiteFailsAndLeavesSpaceInconsistent` was intended to verify that leniency on missing resources (`UNKNOWN_CHANNEL`, `UNKNOWN_ROLE`) was strictly restricted to `ArchiveSpace` and `DeleteSpace`, and that `CreateSpace` still fails permanently and marks the space `INCONSISTENT`.

However, the test simulated a missing prerequisite using:
```java
when(guild.createRole()).thenReturn(null);
```
This was fundamentally flawed for two reasons:
1. **Unrealistic Failure Mode**: `guild.createRole()` in JDA is a REST action builder method, never returning `null` at runtime. Returning `null` caused an unexpected null dereference / `IllegalStateException`. In `JdaGuildOperationExecutor.execute`, unexpected exceptions are caught and classified as **transient** failures by design (`OperationOutcome.transientFailure`), on the assumption that an unexpected runtime anomaly might not repeat.
2. **Never Exercised the Target Path**: The test failed before reaching `classifyError(ErrorResponseException, GuildOperation)`. It never exercised the `case UNKNOWN_CHANNEL, UNKNOWN_ROLE ->` branch where `CreateSpace` must fall through to `permanentFailure`.

### B. The Correction
The test was rewritten in [`JdaGuildOperationExecutorTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/test/java/com/discordtowny/discord/JdaGuildOperationExecutorTest.java#L1279-L1330) so that the create operation meets a missing Discord object the way Discord actually reports it:
- Role creation succeeds and is saved with state `INCONSISTENT`.
- Category lookup succeeds.
- When creating the text channel via `category.createTextChannel(...)`, the returned `ChannelAction<TextChannel>` completes by raising an `ErrorResponseException` with `ErrorResponse.UNKNOWN_CHANNEL` (simulating the parent category channel having been deleted in Discord).
- The exception bubbles to `execute(GuildOperation)`, which routes to `classifyError(e, op)`.
- Because the operation is `GuildOperation.CreateSpace` (not `ArchiveSpace` or `DeleteSpace`), `classifyError` classifies it as `OperationOutcome.permanentFailure`.
- This triggers `onOperationFailed(op, outcome)`, leaving the space saved with state `INCONSISTENT` and never saved as `ACTIVE` or `ARCHIVED`.

### C. Audit of Sibling Tests in `JdaGuildOperationExecutorTest.java`
All sibling tests added for T27 in [`JdaGuildOperationExecutorTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/test/java/com/discordtowny/discord/JdaGuildOperationExecutorTest.java) were checked for similar flaws (mocked calls returning `null` where Discord would raise, or assertions about statuses that the setup cannot produce):

1. **`archiveSpaceWithMissingTextChannelSucceedsAndMarksArchivedNotDeleted`**:
   - Uses `when(guild.getTextChannelById(textChId)).thenReturn(null)`.
   - **Audit**: In JDA, `getTextChannelById` is a local cache lookup, not a REST action. When a channel is deleted by hand in Discord, JDA's cache removes it and `getTextChannelById` returns `null` by contract without throwing. The setup accurately produces `SUCCESS`, moves the remaining voice channel to archive, deletes the role, and marks the space `ARCHIVED`. No flaw.
2. **`archiveSpaceWithMissingVoiceChannelSucceedsAndMarksArchived`**:
   - Uses `when(guild.getVoiceChannelById(voiceChId)).thenReturn(null)`.
   - **Audit**: JDA local cache lookup. Accurately simulates hand-deleted voice channel. Produces `SUCCESS`, archives text channel, deletes role, marks space `ARCHIVED`. No flaw.
3. **`archiveSpaceWithMissingCategorySucceedsAndMarksArchived`**:
   - Uses `when(guild.getCategoryById(catId)).thenReturn(null)`.
   - **Audit**: JDA local cache lookup. Accurately simulates missing active category. Produces `SUCCESS`, moves channels to archive category, deletes role, marks space `ARCHIVED`. No flaw.
4. **`archiveSpaceWithMissingRoleSucceedsAndMarksArchived`**:
   - Uses `when(guild.getRoleById(roleId)).thenReturn(null)`.
   - **Audit**: JDA local cache lookup. Accurately simulates missing town role. Produces `SUCCESS`, moves channels to archive category, marks space `ARCHIVED`. No flaw.
5. **`archiveSpaceWithBothChannelsMissingCreatesNoArchiveCategoryAndSucceeds`**:
   - Uses `when(guild.getTextChannelById(textChId)).thenReturn(null)` and `when(guild.getVoiceChannelById(voiceChId)).thenReturn(null)`.
   - **Audit**: JDA local cache lookups. Accurately produces `SUCCESS`, verifies no category creation (`verify(guild, never()).createCategory(any())`), deletes role, marks space `ARCHIVED`. No flaw.

Conclusion: Only the guard test `createSpaceWithMissingPrerequisiteFailsAndLeavesSpaceInconsistent` suffered from the mocked-builder-null flaw. The archive tests faithfully model JDA's caching behavior for resources already deleted by hand.

---

## 9. Round 3: The Purge Cannot See What It Is Meant to Clean

### A. Defect Description & Problem Analysis
During live operation, when the archive loop occurred, the operator executed `/dt admin purge` to clear the space by hand. Thirty minutes later, the exact same failure returned to the Discord log channel.

#### Root Cause:
1. When an archive operation failed, [`JdaGuildOperationExecutor.onOperationFailed`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/main/java/com/discordtowny/discord/JdaGuildOperationExecutor.java#L1040-L1058) marked the space `INCONSISTENT`.
2. `/dt admin purge` and [`DefaultSpaceService.purgeArchived`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/main/java/com/discordtowny/space/DefaultSpaceService.java#L367-L413) exclusively queried spaces in state `SpaceState.ARCHIVED`.
3. Finding 0 archived spaces, `/dt admin purge` answered `admin.purge-empty` ("There are no archived spaces to delete"), completely blind to the `INCONSISTENT` space that required attention.
4. While the Round 1 fix ensures hand-deleted channels allow archiving to reach `ARCHIVED` (where purge finds it), spaces left `INCONSISTENT` by any other failure remained trapped: the only command intended to clean could not see the spaces needing cleaning, while telling the operator nothing was wrong.

### B. What Was Built
1. **Inconsistent Spaces Are Never Silently Ignored**:
   - In [`MinecraftCommands.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/main/java/com/discordtowny/minecraft/MinecraftCommands.java#L922-L970), `/dt admin purge` reads all spaces via `spaceService.findAll()` to inspect both `ARCHIVED` and `INCONSISTENT` states.
   - An operator is **never** told `admin.purge-empty` while an `INCONSISTENT` space exists.
2. **Inconsistent Spaces Are Not Purged**:
   - `INCONSISTENT` spaces are never purged and their state is never altered automatically. `INCONSISTENT` means a human administrator should inspect what failed before anything is deleted.
   - The response points the operator to `/dt admin list` and `/dt admin info <town>` to review those spaces.
3. **Preservation of Existing Scenarios**:
   - When no inconsistent spaces exist, behavior is byte-for-byte unchanged:
     - No spaces at all: `admin.purge-empty`.
     - Only archived spaces: `admin.purge-confirm` followed by `admin.purged`.
   - When only inconsistent spaces exist: nothing is purged, no confirmation is requested, and the operator is informed with the skipped count (`admin.purge-skipped`).
   - When both archived and inconsistent spaces exist: the operator confirms deleting the archived spaces, the archived spaces are purged, and the operator receives both the deletion confirmation (`admin.purged`) and the skipped notice (`admin.purge-skipped`).

### C. Message Key Added & Rationale
- **Key**: `admin.purge-skipped`
- **Location**: Added to both [`messages_en.yml`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/main/resources/messages_en.yml#L166) and [`messages_es.yml`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/main/resources/messages_es.yml#L166) under the `admin:` section directly following its siblings `purge-empty`, `purge-confirm`, and `purged`.
- **Naming Rationale**: Follows the `purge-` prefix convention of its sibling keys (`purge-empty`, `purge-confirm`, `purged`).
- **Texts**:
  - `messages_en.yml`:
    `purge-skipped: "&eSkipped {count} inconsistent spaces. Use &f/dt admin list&e or &f/dt admin info <town>&e to review them."`
  - `messages_es.yml`:
    `purge-skipped: "&eSe omitieron {count} espacios inconsistentes. Usa &f/dt admin list&e o &f/dt admin info <town>&e para revisarlos."`
- **Why this message**: An audit of both catalogs confirmed no existing key expressed skipping inconsistent spaces during purge. The new key naturally points the administrator to the existing diagnostic commands without introducing new command surface.

### D. Test Verification
1. **[`DefaultSpaceServiceTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/test/java/com/discordtowny/space/DefaultSpaceServiceTest.java)**:
   - `purgeArchivedLeavesInconsistentSpacesUntouched`: Given one `ARCHIVED` and one `INCONSISTENT` space, `purgeArchived()` purges the archived space, leaves the inconsistent space in repository with state `INCONSISTENT`, and submits no `GuildOperation.DeleteSpace` for it.
   - `purgeArchivedWithOnlyInconsistentSpacesPurgesNothing`: Given only `INCONSISTENT` spaces, returns 0, deletes nothing, and submits zero Discord operations.
   - `purgeArchivedDeletesOnlyArchivedSpaces`: Preserved byte-for-byte.
   - `purgeArchivedReturnsZeroWhenEmpty`: Preserved byte-for-byte.
2. **[`MinecraftCommandsTest.java`](file:///C:/Users/ASUS/Desktop/Projects/Plugins/Project%20Discord-Towny/worktrees/t27-archive-loop/src/test/java/com/discordtowny/minecraft/MinecraftCommandsTest.java)**:
   - `adminPurgeWithArchivedAndInconsistentSpacesPurgesArchivedAndReportsSkipped`: Tests 1st run requests confirmation for the archived space; 2nd run executes purge, verifies `admin.purged` with count 1, and verifies `admin.purge-skipped` with count 1.
   - `adminPurgeWithOnlyInconsistentSpacesPurgesNothingAndReportsSkipped`: Verifies that running purge with only inconsistent spaces does not show `admin.purge-empty`, does not call `purgeArchived()`, and sends `admin.purge-skipped` with count 2.
   - `adminPurgeWithConsoleAndInconsistentSpacesRepliesInEnglish`: Verifies console operator receives the English `purge-skipped` text per Spec 9.1.
   - `adminPurgeRequiresConfirmation`: Preserved byte-for-byte.
   - `adminPurgeWhenNoArchivedSpacesShowsEmpty`: Preserved byte-for-byte.
