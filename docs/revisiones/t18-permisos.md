# T18 — Permission nodes review

Review of the uncommitted diff against HEAD `13824c8`, branch `feat/permisos`, on 2026-09-20. Read the complete review checklist, spec §9.2, T18 card, constitution, architecture, and plan. **Verdict: changes required.** No implementation files changed.

File references below use `M` = `src/main/java/com/discordtowny/minecraft/MinecraftCommands.java`, `L` = `LinkMinecraftCommands.java`, and `S` = `SyncMinecraftCommands.java` in the same directory. Test references use the corresponding classes under `src/test/java/com/discordtowny/minecraft/`.

## Blocking

- **B1 — `M:226`, `MinecraftCommandsTest:1299`: admin bypasses the use denial for `/dt help`.** With `admin=true, use=false`, help executes and prints administration entries. Spec §9.2 explicitly includes `help` among the commands governed by `use`; the new test positively requires this exception. Require `use` for a player's help invocation, even when they hold `admin`, and make the independence test reject this case. Direct `/dt admin ...` access must remain governed solely by `admin`. This is an introduced defect, not permission inheritance in the YAML.
- **B2 — `M:773`, `L:111`, `S:208`: every denied admin command misses the required catalog explanation.** The parent `requires` rejects parsing before any command body runs. Typing `/dt admin reload`, `list`, `info <town>`, `purge`, `unlink <jugador>`, `sync [town]`, `update`, `update status`, or `update confirm` without `admin` produces a Brigadier syntax/unknown-command response, never `general.no-permission`. Preserve authorization and provide the catalog refusal required by T18; a message inside an unreachable executor cannot achieve that. The mechanism predates T18, but the task's explicit refusal criterion remains unmet.
- **B3 — `M:260–279`: help does not accurately describe the available command surface.** Console help advertises `/dt status`, whose body always rejects the console at `M:300`. Help also omits the registered `admin update`, `admin update status`, and `admin update confirm` routes for eligible players and console. The non-player branch checks no node before listing administration commands. Make help reflect sender eligibility and the complete registered surface, including updates. These omissions/excess entries predate T18 but remain inside its help acceptance requirement; any needed catalog additions require coordination with the catalog owner.

## Noted debt

- **Important — `MinecraftCommandsTest:1348`: the YAML test is not a Paper-loader test.** Bukkit `YamlConfiguration` treats dots as path separators, so these assertions cannot distinguish literal `discordtowny.use` keys from incorrectly nested `discordtowny: {use: ...}` mappings. Paper reads the raw entries as permission names. The current file is correct, but this test could pass after a breaking key-layout change. Verify the literal permission-name map or Paper's parsed permissions, including defaults and empty children. [Bukkit YAML conversion](https://github.com/PaperMC/Paper/blob/main/paper-api/src/main/java/org/bukkit/configuration/file/YamlConfiguration.java), [Paper permission serializer](https://github.com/PaperMC/Paper/blob/main/paper-server/src/main/java/io/papermc/paper/plugin/provider/configuration/serializer/PermissionConfigurationSerializer.java).
- **Minor — `M:773`, `docs/user-guide.md`: document client command-tree refresh after online permission changes.** This branch supplies no Minecraft command refresh hook, and `/dt admin reload` does not call `Player.updateCommands()`. Explain the visibility behavior below; this is documentation work for the owning zone, not a request for a new permission integration.

## Complete command and console audit

`DiscordTownyPlugin.java:76` registers only the combined `MinecraftCommands` tree. `M:1039` and `M:1048` attach the admin factories from the other two classes. The standalone Link/Sync registration methods are not called by startup, but their trees were reviewed too. `/discordtowny` is an alias of the same registered tree (`M:1296`, `L:359`, `S:358`), not a permission bypass.

In the table, `use` and `admin` mean the full `discordtowny.*` nodes. “Parent requires” means Brigadier checks the ancestor during dispatch; the leaf need not repeat it. All six player-only actions first reject a non-player with `general.players-only` and return, then test `use`, before accessing identity, Towny, confirmation state, or services.

| Route | Guard and location | Console outcome |
|---|---|---|
| `/dt` (all three trees) | **No permission check**, `M:176`, `L:107`, `S:95`; no executor | Incomplete/unknown command; no action or player access |
| `/dt help` | Body checks `use OR admin` for players, `M:223–228`; **no check for non-players**, `M:269` | English help; inaccurate entries described in B3 |
| `/dt status` | Body `use`, `M:304`; player-only return at `M:300` | Players-only refusal |
| `/dt link` | Body `use`, `M:396`; standalone `L:134`; player-only at `M:392` / `L:130` | Players-only refusal |
| `/dt unlink` | Body `use`, `M:447`; standalone `L:180`; player-only at `M:443` / `L:176` | Players-only refusal |
| `/dt create` | Body `use`, `M:497`; player-only at `M:493`, then mayor/domain checks | Players-only refusal |
| `/dt delete` (initial and repeated confirmation) | Body `use`, `M:614`; player-only at `M:610`, then mayor/confirmation checks | Players-only refusal |
| `/dt sync` | Body `use`, `M:684`; standalone `S:142`; player-only at `M:680` / `S:138`, then mayor check | Players-only refusal |
| `/dt admin` | Parent `requires(admin)`, `M:773`, `L:111`, `S:208`; no executor | Passes normal console permission check, but incomplete command |
| `/dt admin reload` | Parent requires, leaf `M:776` | Runs reload and replies in English |
| `/dt admin list` | Parent requires, leaf `M:798` | Runs list and replies in English |
| `/dt admin info <town>` | Parent requires, argument executor `M:880` | Uses supplied town, not sender identity |
| `/dt admin purge` (initial and repeated confirmation) | Parent requires, leaf `M:989` | Works; confirmation key is `console` (`M:1000`) |
| `/dt admin unlink <jugador>` | Parent requires in combined/standalone trees; `L:290`, attached at `M:1039` | Resolves target UUID; never casts sender to player |
| `/dt admin sync` | Parent requires in combined/standalone trees; `S:263`, attached at `M:1048` | Reconciles all; no player identity needed |
| `/dt admin sync <town>` | Same parent; argument executor `S:303` | Resolves supplied town and synchronizes its UUID |
| `/dt admin update` | Parent requires, executor `M:1079` | Works; confirmation key is `console` (`M:1097`) |
| `/dt admin update status` | Parent requires, leaf `M:1145` | Works without player identity |
| `/dt admin update confirm` | Parent requires, leaf `M:1183` | Works; confirmation key is `console` (`M:1209`) |

Bare `admin info` and `admin unlink` also have no executor: they require their argument. They remain behind the admin parent. None of the executable admin routes is unguarded merely because its body has no permission check. **No command lets console fall through into code that requires a Player.** The genuinely unchecked surface is the non-executable `/dt` root and the non-player help branch.

Console can still administer the server: its normal Paper permission behavior admits `admin`, and T18 adds no `use` requirement or Player restriction to that block. It is still subject to the sender's effective `admin` result if the server customizes console permissions. Paper's console delegates to normal permission checks when its all-permissions setting is disabled. [Paper console implementation](https://github.com/PaperMC/Paper/blob/main/paper-server/src/main/java/org/bukkit/craftbukkit/command/CraftConsoleCommandSender.java).

## Hiding, refusing, and online changes

The split is consistent across all three classes: ordinary player commands remain in the client tree and check `use` in their bodies; the complete admin subtree uses its parent's `requires`. Thus a denied player still sees `help`, `link`, `unlink`, `status`, `create`, `delete`, and `sync` in tab completion. Typing a player action gives the catalog refusal; non-mayors likewise see mayor commands and receive the existing mayor refusal. Help filters its printed list separately.

That direction is reasonable for discovery of public commands and hiding staff commands; moving the player denial into `requires` would lose its explanation too. No individual admin leaf has accidentally received the player mechanism, nor vice versa. However, **using only `requires` for the admin block is insufficient under the approved catalog-refusal requirement** (B2). An exception accepting hidden commands' generic syntax errors would require an explicit requirement change, not an assumption in this review. [Paper requirements documentation](https://docs.papermc.io/paper/dev/command-api/basics/requirements/).

`requires` is not evaluated only when sending the client tree: Brigadier also calls `canUse(source)` while parsing a fresh server-side command. Consequently, after an effective admin revocation, stale client suggestions do not preserve authorization; a newly submitted admin command is denied. After a grant, the client can temporarily omit/red-mark the command until refreshed, even though a command submitted to the server can pass the current check. [Brigadier dispatcher, `parseNodes`](https://github.com/Mojang/brigadier/blob/master/src/main/java/com/mojang/brigadier/CommandDispatcher.java).

The client list changes on a tree resend, such as reconnecting or `Player.updateCommands()`; a permissions plugin may arrange that refresh itself. Do not promise that every LuckPerms/version/configuration combination leaves the client stale, or that `/dt admin reload` refreshes it. Player body guards and help read current effective permissions on each invocation. This potential display lag is acceptable as documented behavior, not an access-control blocker. [Paper `Player.updateCommands()` API](https://jd.papermc.io/paper/1.21.11/org/bukkit/entity/Player.html#updateCommands()).

## Permission declaration

`src/main/resources/paper-plugin.yml:10–16` is correctly placed: top-level `permissions`, containing the literal keys `discordtowny.use` and `discordtowny.admin`, each with a nonblank description and its own default. PaperPluginMeta flattens its permission configuration into the root; its serializer reads `node.node("permissions").raw()` and passes each literal entry name to Bukkit's permission loader. This is supported in `paper-plugin.yml`, not merely legacy `plugin.yml`. [PaperPluginMeta](https://github.com/PaperMC/Paper/blob/main/paper-server/src/main/java/io/papermc/paper/plugin/provider/configuration/PaperPluginMeta.java), [permission serializer](https://github.com/PaperMC/Paper/blob/main/paper-server/src/main/java/io/papermc/paper/plugin/provider/configuration/serializer/PermissionConfigurationSerializer.java).

Unquoted `default: true` is intentionally a YAML boolean: `Permission.loadPermission` converts the value to a string and resolves the default. It means enabled for operators and non-operators; `op` means operators only. Neither node declares children, so the plugin creates no inheritance edge in either direction. A shared dotted prefix does not establish such an edge. An owner can configure relationships externally. Since `use` defaults true, an admin with no explicit `use` setting may use player commands through that default, not through `admin`; the decisive scenario is an explicit `use=false`. The YAML preserves it; help's Java exception does not. [Bukkit permission loader](https://github.com/PaperMC/Paper/blob/main/paper-api/src/main/java/org/bukkit/permissions/Permission.java), [Paper permission defaults](https://docs.papermc.io/paper/dev/plugin-yml/#permissions).

The declarations are suitable for discovery by a permissions manager. Actual `/lp` completion and runtime registration were not exercised.

## Test audit: production changes that would make each changed test fail

There are **7 new tests and 27 existing tests with added `use=true` setup**. The latter preserve their existing behavioral scenarios; they do not test default permission resolution. Names below are exact. Every test has an identifiable production mutation that would fail it; none is wholly independent of production behavior.

### New tests

| Test | Mutation detected / limitation |
|---|---|
| `LinkMinecraftCommandsTest.linkAndUnlinkRefuseWhenMissingUsePermission` | Remove either body guard, change its refusal key, or call the corresponding link service despite denial. Checks one then two refusals and no code generation/unlink. |
| `LinkMinecraftCommandsTest.adminPermissionDoesNotGrantLinkOrUnlink` | Allow `admin` as an alternative to `use` in either Link body; denial/service assertions fail. Permission values are mocked, so this does not exercise Bukkit inheritance. |
| `SyncMinecraftCommandsTest.dtSyncRefusesWhenMissingUsePermission` | Remove the standalone sync guard, change the denial message, or call sync/reconciliation despite denial. |
| `SyncMinecraftCommandsTest.adminPermissionDoesNotGrantDtSync` | Add an admin override to standalone player sync; the required refusal fails. Does not exercise Bukkit inheritance. |
| `MinecraftCommandsTest.playerCommandsDeniedWhenLackingUsePermission` | Remove the help denial with both nodes false, remove any of the six action guards, change refusal keys, or invoke the checked services. Counts six action refusals and separately checks help. |
| `MinecraftCommandsTest.adminPermissionDoesNotImplyUsePermissionForPlayerCommands` | Add an admin override to any of the six action guards, or expose their help entries to this admin. **Also fails if B1 is fixed**, because it currently requires help to succeed with `use=false`. Its final direct `admin list` executor call bypasses ancestor predicates and cannot prove dispatcher access survives a use denial. |
| `MinecraftCommandsTest.paperPluginYmlDeclaresPermissionNodesWithCorrectDefaults` | Remove either declaration, change a default, blank a description, or add a `children` block, including `admin -> use`. It does catch declared inheritance, but not all incorrect raw YAML layouts (noted debt). |

**There are tests that fail if admin starts implying use:** the three admin-without-use command tests detect Java overrides for all six player-only actions, and the descriptor test detects adding YAML children. It would be incorrect to report that independence is wholly untested. What is missing is a correct help-independence assertion and a real permission-resolution/server integration check. The ordinary help tests also have partial negative lists rather than exact command inventories, explaining why incomplete help survives.

All new command tests call the real executor with mocked services; distinct message keys produce distinct components (the Link tests specifically stub the denial). They genuinely check denial, rather than merely asserting a mock's return value. They bypass Brigadier parsing and cannot detect adding an inappropriate `requires` to a player command. The existing, unchanged `MinecraftCommandsTest.adminCommandsEnforcePermissionThroughBrigadierDispatch` at line 757 does exercise the dispatcher for admin list, including an admin whose mocked `use` is false; it currently expects a syntax exception for denial, not the catalog explanation.

### Changed existing tests — MinecraftCommandsTest

| Test | Example production mutation that fails it |
|---|---|
| `helpFiltersCommandsForRegularPlayerOutsideTown` | Omit a required general entry or expose a checked mayor/admin entry. |
| `helpFiltersCommandsForMayorInTown` | Remove mayor entries or expose a checked admin entry. |
| `helpFiltersCommandsForAdminPlayer` | Remove one of the six asserted admin entries or expose create/delete without mayorship. |
| `statusShowsLinkedAndActiveSpaceForTownResident` | Stop emitting linked or active-space status for present records. Message stubs do not distinguish placeholder values. |
| `statusShowsUnlinkedAndNoTownForNewPlayer` | Select linked/in-town message keys for absent records. |
| `createFailsWhenOutsideTown` | Remove the no-town refusal or change its key. |
| `createFailsWhenNotMayor` | Remove the mayor refusal or invoke creation despite it. |
| `createFailsWhenDiscordUnavailable` | Remove the gateway refusal or change its key. |
| `createFailsWhenMayorNotLinked` | Proceed to creation without the mayor link or omit `linking.link-required`. |
| `createSucceedsWhenConditionsMet` | Change the captured request UUID, mayor, linked ID list, population, or omit creation/success reply. This proves adapter dispatch, not real Discord creation. |
| `createWithPartiallyLinkedResidentsOnlyIncludesLinkedDiscordIdsInRequest` | Include an unlinked resident, omit a linked one, or use linked count instead of total population in the request. Exact list assertion is useful. |
| `deleteRequiresConfirmationBeforeArchiving` | Archive on first invocation or fail to archive on the second. |
| `asynchronousCommandRepliesThroughSchedulerOnMainThreadAndNeverOnWorkerThread` | Send the create completion reply directly from its worker instead of enqueuing it. Uses a real worker and a separately drained queue. |
| `syncReportsBothRepairsAndProblemsInRepairMode` | Omit repairs/problems or emit clean success for the partial report. |
| `syncReportsProblemsInReportMode` | Omit report/pending/problem keys or emit the clean-report key. |
| `syncReportsUnrepairedWhenInconsistenciesRemainAndProblemListEmpty` | Omit unrepaired status or emit clean success. Its message stub ignores the count, so changing only the count can pass. |
| `syncReportsFinishedWhenCleanInRepairMode` | Stop emitting started/finished for a clean result. |

### Changed existing tests — LinkMinecraftCommandsTest

| Test | Example production mutation that fails it |
|---|---|
| `asynchronousResponsesAreScheduledOnMainThreadScheduler` | Remove scheduling after code-generation completion. Its inline scheduler proves delegation only, not the actual reply thread. |
| `asynchronousErrorsAreScheduledOnMainThreadScheduler` | Remove scheduling from unlink's error path. Same inline-scheduler limitation. |

### Changed existing tests — SyncMinecraftCommandsTest

| Test | Example production mutation that fails it |
|---|---|
| `dtSyncFailsWhenPlayerNotInTown` | Remove/change the no-town refusal or dispatch sync anyway. |
| `dtSyncFailsWhenPlayerNotMayor` | Remove/change the mayor refusal or dispatch sync anyway. |
| `dtSyncDispatchesTownSyncAndSchedulesResponseOnMainThread` | Stop dispatching the requested town, bypass the scheduler, or change started/finished ordering. Inline scheduler does not establish the reply thread. |
| `dtSyncSchedulesDatabaseErrorResponseOnFailure` | Bypass scheduling or map the database failure to the wrong key. |
| `dtSyncSchedulesDiscordErrorResponseOnDiscordFailure` | Bypass scheduling or map the Discord failure to the database key. |
| `dtSyncWithNormallyCompletedReportCarryingFailuresReportsErrorsAndDoesNotAnnounceCleanSuccess` | Emit clean success, omit problems, or change the verified problem arguments. Presence checks do not establish an exact reply inventory. |
| `dtSyncWithUnrepairedInconsistenciesReportsUnrepairedKey` | Emit clean success, omit unrepaired status, or change its explicitly verified count. |
| `dtSyncWithRepairedInconsistenciesAndFailuresReportsRepairedKey` | Omit repairs/problems, emit clean success, or change explicitly verified repair/problem arguments. |

## Remaining checklist and verification limits

- **Threads:** added guards and help checks execute synchronously before service work. Towny reads for help/status/create/delete/player sync, admin target unlink, and named admin sync/info originate in command execution on main; no added continuation moves them. Services retain their asynchronous database/Discord work and scheduled replies, including errors. The existing admin-info suggestion callback (`M:869`) lacks the primary-thread guard present in `S:291`; this is inherited, outside the permission diff. Admin list/info's existing gateway inspections are cache reads, not new REST work. No listener changed.
- **Identity:** T18 changes no target resolution or persisted identity. Player actions still use UUIDs; admin arguments are resolved before dispatch. Existing name fallback in admin info is unchanged.
- **Success and failed reads:** denial returns before domain work or success messages. No new success/no-op or destructive absence path was added. Existing help catch-and-hide behavior (`M:242`) and status/sync read-failure handling are unchanged; T18 does not repair them.
- **Messages:** the only newly used refusal key, `general.no-permission`, exists in both catalogs at line 13, has no placeholders, and is sent through the prefixed accessor. `general.players-only` exists at line 16 in both. No player-visible Java literal or problematic YAML key was introduced; `default: true` is intentionally boolean.
- **Configuration/lifecycle:** no startup/shutdown or configuration propagation changes. Permission declarations load as plugin metadata; command visibility refresh is separate from the existing configuration/messages reload.
- **Scope:** all seven author-modified files remain in T18's production/test `minecraft/` zone or `paper-plugin.yml`; no domain change or architecture change. Only this review file was added by the reviewer.

I inspected Git status/diff and source; Git is available here despite the checklist's generic environment note. **I did not run Gradle, compile, execute tests, launch Paper/LuckPerms, or commit.** The architect reports **570 passing tests**, after correcting `createCode` to `generateCode`; that is supplied evidence, not my execution. Test mutation effects above are reasoned from source, not executed mutation tests. Paper/Brigadier behavior was checked against upstream source/docs; live target-server console behavior, permission-manager discovery, client refresh timing, and parsed runtime permission objects remain unverified.
