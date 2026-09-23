# T19 — Sync report and admission audit review

**Verdict: changes required.** Reviewed the uncommitted changes against HEAD on `fix/informe-sync`, after reading the complete review checklist, T19 card, governing documents, and spec 9.1 and 12. The architect reports **616 passing tests**; that is not a reviewer execution.

Paths below are relative to this worktree. Java production paths begin with `src/main/java/com/discordtowny/`; test paths begin with `src/test/java/com/discordtowny/`.

**Blocking findings**

- **B1 — `minecraft/MinecraftCommands.java:520`, `space/DefaultSpaceService.java:142`: the normal offline `/dt create` still leaves no audit row.** The command returns immediately when the gateway is null or unavailable, before obtaining/calling `SpaceService`. The new event only covers callers that actually reach the service, including a disconnect after the command's check and permission failures. `MinecraftCommandsTest.createFailsWhenDiscordUnavailable` explicitly verifies that the service is never called. This guard predates T19: this is an exposed integration gap, not an unauthorized change by the author. The architect must connect the actual command refusal to asynchronous auditing while preserving its refusal, response, and admission behavior. A command-to-database regression must exercise an already-offline gateway; direct service tests do not meet that acceptance criterion.
- **B2 — `sync/DefaultSyncService.java:240`, `:425`, `:627`, `:976`, `:1261`: English sentences still reach players inside `{error}` and `{reason}`.** `safeError` and `safeReason` preserve raw exception/outcome text and use English `unknown` as a fallback; they do not localize it. For example, `JdaDiscordGateway.existingResourceIds` throws `Discord is unavailable`, producing Spanish text ending in that English sentence. `LiveTownyFacade` supplies `Towny: read could not be completed`; internal fallbacks add `Read failed against Towny` and `Towny call threw exception: ...`. `TownyTownsResult.unavailable()` also reaches the mayor-audit problem as English. Localize player-facing causes, including fallbacks, while retaining English diagnostic detail for logs/console; merely translating the enclosing sentence cannot satisfy spec 9.1/12.
- **B3 — `src/main/resources/messages_es.yml:70`, `:79`, `:81`, `:83`; `sync/DefaultSyncService.java:500`: the new Spanish templates themselves remain mixed-language.** Examples include `Fallo al archivar el space...`, `no tiene space registrado`, literal `ARCHIVED`/`INCONSISTENT`, and a `{state}` populated with `SpaceState.toString()` (`ACTIVE`, etc.). Only `town`, `nation`, and `resident` receive the documented domain-term exception. Translate ordinary prose and displayed states; existing `admin.state-*` labels demonstrate the established translations. This fails even without any external error message.

**Important test findings**

- **I1 — `DiscordTownyWiringAuditTest.java:139`: the refusal-row assertion races the audit worker.** `create(req).join()` guarantees dispatch, not database completion; the immediate `recent(...)` can run before the write. The later direct `record(...).get(...)` cannot establish ordering for the earlier query. Await observable persistence or drain with a bounded, deterministic arrangement, and close the wiring afterward. Preserve a separate assertion on the real refusal; a manually supplied event must not substitute for it.
- **I2 — `MinecraftCommandsTest.java:96`, `:911`, `:941`; `SyncMinecraftCommandsTest.java:59`, `:496`, `:718`; `sync/DefaultSyncServiceTest.java:2138`, `:2176`, `:2263`: the tests do not establish the claimed complete language boundary.** The first command fixture discards placeholder values in `get`, and the second returns the key from `label`; emitting `problemKeys()` instead of localized problems would still pass these player tests. Both new producer scenarios exercise the same unjustified-town-role message, not every producer; the catalog test compares catalogs only. Make rendered values distinguishable from keys and exercise actual failure producers, including the English internal fallback and state cases. Tests should reject the English fragments in B2/B3, several of which current assertions explicitly require.

**Producer and accessor trace**

All files in `sync/` were inspected: `DefaultSyncService`, `SyncService`, `PeriodicSyncJob`, `SyncScheduler`, and `package-info`. Every direct report-problem construction in production now uses `SyncReport.Problem`: **54 sites, 44 distinct literal catalog keys**. No current direct problem key is a finished English sentence or a concatenation containing a town name. Town names are supplied as placeholder values. The accumulator and both report constructors take `Problem`, and merging preserves that structure. The remaining language leaks are in placeholder values and catalog wording, as identified above.

The three retained occurrences of `Towny is unavailable` have different outcomes:

| Location in `sync/DefaultSyncService.java` | Actual destination |
|---|---|
| 170, `IllegalStateException` in `syncPlayer` | Internal exception. Towny/player-join listeners log it; the link flow propagates failure to a catalog-based generic reply. No current caller displays this exception sentence in a sync report. Safe to retain as an English diagnostic. |
| 1208, `TownyLookupResult.unavailable().errorMessage` | Internal and currently unused for display on the unavailable path. Both town lookup consumers branch on `isUnavailable()` and emit dedicated keys before consulting `errorMessage`. Safe for current callers. |
| 1243, `TownyTownsResult.unavailable().errorMessage` | **Player-visible.** If Towny becomes unavailable between the initial `reconcileAll` availability check and the mayor audit, the combined unavailable/failed-read branch at 972 passes this string to `{error}` at 976. Spanish output becomes `Error de lectura de Towny durante la auditoría del rol de alcalde: Towny is unavailable`. Not internal-only. |

The remaining concatenations with town names at 257, 457, and 504 are archive audit reasons. `executeArchive` passes them to `SpaceService.archive`, which records them in audit detail; archive failure becomes a separate localized report problem. They are not concatenated sync-report sentences. `PeriodicSyncJob` logs its own English lifecycle/exception diagnostics and retains completed reports; it does not render report details to players.

The accessor ambiguity is reduced, **not made impossible**:

- `problemDetails()` returns immutable structured problems; `problemKeys()` explicitly documents raw keys; `problems(Messages)` renders through prefix-free `Messages.label(key, placeholders)`. The old zero-argument `problems()` is gone. Ordinary typed calls cannot pass `List<String>` to a report constructor or accumulator.
- `Problem(String key)` checks only null. `new Problem("Town X failed")` remains legal and would render a missing-message marker containing that prose. Arbitrary strings can also enter placeholders, which is already happening in B2. The contract does not validate key existence, key syntax, or placeholder names.
- `problemKeys()`, `Problem.key()`, and `Problem.toString()` still produce strings a caller could mistakenly display; `toString()` is key-plus-map diagnostic output. No current command does so. There is no compatibility accessor silently presenting keys as finished display text, but there is also no type-level prohibition on all future misuse. This is a contract limitation, not a separate observed command defect.

Both command implementations are migrated correctly in the current production diff. `MinecraftCommands` uses `problemKeys()` for presence/count and `problems(messages)` for entries in both modes (1346–1368); `SyncMinecraftCommands` does the same (416–468). The resulting problem label is placed inside the prefixed `sync.problem-entry`, avoiding an extra prefix. Each resolves player versus console messages before the asynchronous operation. Wiring sets console messages to bundled English on startup and reload (`DiscordTownyWiring.java:145,457`); both adapters also fall back to `EnglishMessages.bundled()` for console. Thus console report templates remain English regardless of player language. The command changes fix rendering, not the English values supplied by producers.

**Audit semantics, ordering, and remaining silence**

For a request reaching `DefaultSpaceService.create`, the new event is emitted before returning `DISCORD_UNAVAILABLE`: severity `ERROR`, action `space_create`, requesting mayor's Discord ID, town name as the existing audit target convention, `success == false`, and a nonempty cause. `SqlAuditRepository.record` persists that boolean as `success = 0`; it cannot be mistaken for a successful creation by consumers reading the result. The three causes distinguish an unavailable gateway, a permission warning, and an exception while verifying permissions. Successful creation still emits its completion event only.

The decision is unchanged: gateway unavailable refuses; a nonempty permission warning refuses; an exception from permission verification refuses; empty or null warning permits admission. The check is still after the existing preconditions and before reservations, cooldown recording, or submitting a guild mutation. Auditing failures are caught and do not turn refusal into success or another result. An exception thrown by `isAvailable()` itself is still outside the helper's `try`, as before.

Other admission exits remain silent: invalid requests (exceptional completion, line 85), `MAYOR_NOT_LINKED` (92), `TOO_FEW_RESIDENTS` (101), a pending town or existing active/archived/interrupted-archive space (`ALREADY_EXISTS`, 116/125), `ON_COOLDOWN` (131), and `LIMIT_REACHED` (137). Repository/admission exceptions also escape before the completion-audit chain. These are **noted inherited audit debt**, distinct from the explicitly required offline refusal in B1. The command's unlinked-mayor early return is likewise silent. Extending coverage should preserve all these decisions and their order.

The new event is constructed and dispatched from `DefaultSpaceService`'s worker executor, using an immutable request and clock; it performs no Towny reads. Production wiring supplies the common pool for space/sync work and a separate `dt-audit-worker` executor for the composite sink. Database recording and Discord logging are queued there, and a Discord sink failure does not suppress the database task. No new storage or Discord operation is placed on the server thread.

Sync Towny reads remain inside `callTowny`, which production wiring supplies with the Paper main-thread executor. Subsequent repository/Discord work resumes through `thenComposeAsync(..., executor)`; changed completion handlers only accumulate counts and problem data. Listeners capture IDs/names and delegate without waiting. No threading boundary changed in this diff; inline test executors do not independently prove the production boundaries.

For a **completed service refusal followed by normal shutdown**, dispatch happens before the refusal future completes, and `CompositeAuditSink.record` increments pending writes and queues the database task synchronously. `DiscordTownyWiring.stop` drains the audit executor before closing storage (`:406–416`, `:616`); queued writes are not discarded merely because the refusal returned immediately. The existing queued-event shutdown test covers that general sink ordering, although no changed test holds this particular refusal write and then stops the wiring.

An unconditional “cannot be lost” claim would be false: the existing drain is bounded to three seconds and invokes `shutdownNow()` on timeout, logging dropped events (`CompositeAuditSink.java:153–168`); failed writes, interruption, and abrupt process termination are not durable delivery. Also, stopping while creation is still pending in the common pool is outside the completed-refusal ordering above: wiring does not await domain producers before closing audit admission. These are inherited lifecycle limits, not new T19 regressions; the card explicitly excludes changing the audit sink. The new code introduces no additional loss window after a successfully dispatched, completed refusal.

**Catalog verification**

A read-only source check matched all 54 `Problem` construction sites, including repeated exception/outcome branches, against both catalogs. The 44 new keys exist in the same order in EN/ES, and every supplied placeholder set exactly matches both templates. Removing only the new sync entries reproduces each HEAD catalog exactly: no old text or ordering changed. A PyYAML YAML 1.1 parse confirmed every sync key and value is a string; the new values are quoted and no new boolean/null key trap is present. These are static checks, not Java test execution.

The complete producer/template placeholder comparison is below. Every name has the prefix `sync.problem-`; a dash means no placeholders.

| Key suffix | Placeholders |
|---|---|
| town-uuid-null | — |
| towny-unavailable | — |
| towny-read-failed-town | town, error |
| town-no-longer-exists | town, uuid |
| archive-deleted-failed | town |
| town-not-found | town |
| town-no-space | town |
| batch-pause-failed | error |
| batch-failed | batch, error |
| mayor-audit-failed | error |
| reconcile-space-failed | town, uuid, error |
| towny-unavailable-space | town |
| towny-read-failed-space | town, uuid, error |
| town-ruined-space-state | town, state |
| archive-ruined-failed | town |
| town-alive-space-archived | town |
| restore-space-failed | town |
| space-inconsistent | town |
| resume-restoration-failed | town |
| repair-inconsistent-failed | town, reason |
| town-renamed | old, new |
| rename-failed | old, new, reason |
| verify-resources-failed | town, error |
| no-channels-registered | town |
| missing-channels | town |
| missing-role | town |
| repair-channels-role-failed | town, reason |
| resident-missing-roles | discord, town, roles |
| resident-unjustified-roles | discord, roles |
| adjust-roles-failed | discord, reason |
| role-holders-empty-town | town, role |
| member-unjustified-town-role | discord, town, role |
| revoke-role-failed | role, discord, reason |
| lookup-role-holders-failed | town, role, error |
| mayor-role-id-failed | error |
| lookup-mayor-holders-failed | role, error |
| mayor-holders-null | role |
| mayor-audit-towny-failed | error |
| mayor-holders-empty | role |
| member-unjustified-mayor-role | discord, role |
| revoke-mayor-role-failed | role, discord, reason |
| mayor-missing-role-town-without-space | discord, town, role |
| grant-mayor-role-failed | role, discord, reason |
| audit-mayor-role-exception | error |

The new Spanish entries preserve `town` and `resident`; none introduces a translated `nation` (the term is absent from these entries). That invariant passes. It does not excuse the extra English prose/state values in B2/B3. There is no current missing-placeholder mismatch; completeness of substitution and completeness of translation are separate properties.

**Changed-test mutation audit**

The mutations below are reasoned from the test bodies; none was applied or executed. Fixture/import changes are supporting code, not additional tests. “Fails” identifies an observable production regression, not an endorsement of every claim in a test's name.

For `sync/DefaultSyncServiceTest.java`:

| Test (line) | Production change that would make it fail; limit of the assertion |
|---|---|
| `reportOnlyModeWithBrokenStateAssertsZeroDiscordOperationsAndZeroRepositoryWrites` (344) | Remove all report problems, or submit mutations/write state in REPORT mode. The changed nonempty assertion alone does not distinguish keys from prose. |
| `syncTownAdjustsRolesForAllResidentsAndReturnsRealCounts` (569) | Add a spurious problem on the clean repair path, or break the asserted role counts/operations. The accessor-only edit adds no localization protection. |
| `reportModeDetectsMissingRoleAssignmentAndReportsItWithoutMutations` (704) | Replace `resident-missing-roles` with an English sentence key, lose the Discord ID, or stop rendering the asserted EN/ES fragments. Other producers are not covered. |
| `memberWhoIsResidentKeepsIt` (1119) | Report a problem or revoke the legitimate resident's role. Its changed empty-list assertion is unrelated to translation. |
| `reportModeFindsTheSameCaseReportsItAndSubmitsZeroOperations` (1165) | Remove/change `member-unjustified-town-role`, its Discord/town values, or mutate in REPORT mode. Render assertions identify values, not complete Spanish prose. |
| `emptyOrFailedHolderLookupRevokesNothingAndIsReportedAsAProblem` (1238) | Lose/change the empty/failed-holder keys or town/role/error values, or revoke on lookup failure. The Spanish failed-read assertion actually requires the English `disconnected` fragment. |
| `deletedDiscordChannelWithPersistedIdIsDetectedAndRepairedInRepairMode` (1323) | Lose/change `missing-channels`, the town, or its asserted catalog fragments; skip the expected repair. |
| `deletedDiscordChannelWithPersistedIdInReportModeReportsDiscrepancyWithoutModifications` (1361) | Lose/change that same problem or mutate in REPORT mode. |
| `deletedDiscordRoleWithPersistedIdIsDetectedAndRepairedInRepairMode` (1407) | Lose/change `missing-role`, the town, or the repair submission/count. |
| `discordOutageDuringResourceExistenceCheckDoesNotTreatOutageAsDeletion` (1443) | Replace `verify-resources-failed`, lose its town/error, or treat outage as missing resources and submit creation. It permits the English exception inside Spanish output. |
| `townyReadExceptionInSyncTownReportsProblemAndLeavesAccessUntouched` (1584) | Lose/change `towny-read-failed-town`, its UUID/error, or mutate access on a failed read. It explicitly requires `Towny read timed out` in Spanish output. |
| `townyReadExceptionInReconcileAllReportsProblemAndProtectsLivingTown` (1619) | Lose/change `towny-read-failed-space` or its town/UUID, skip later processing, or archive the failed-read town. |
| `revivedTownRestoresArchivedSpaceToActiveAndRestoresResidentsAccess` (1679) | Lose/change `town-alive-space-archived`, its town, or restoration behavior. Translating `ARCHIVED` correctly would currently fail its Spanish assertion. |
| `spaceFailureInEarlierBatchDoesNotAbortLaterBatchesAndRetainsProblemsInReport` (1820) | Drop the earlier failed-space problem/town/UUID or abort later batches. Matching the town name in both rendered outputs does not prove their language. |
| `auditMayorRoleInReportModeDoesNotMutateAndReportsProposedRevocation` (1939) | Lose/change `member-unjustified-mayor-role` or its member/role IDs, or revoke instead of proposing. Rendering assertions check IDs only. |
| `auditMayorRoleEmptyOrFailedLookupRevokesNothing` (2074) | Lose/change `lookup-mayor-holders-failed`, `mayor-holders-empty`, or `mayor-audit-towny-failed`, lose required role/error values, or revoke on these failures. It requires English `Gateway timeout` in Spanish and does not cover Towny changing to unavailable. |
| `noProblemEmittedBySyncServiceContainsFinishedEnglishSentence` (2138) | Change the unjustified-town-role producer's key to prose, add a malformed key on that exercised path, or suppress its only problem. It checks key syntax only, not placeholders or all producers. |
| `syncReportRendersInBothEnglishAndSpanishWithoutMissingKeysOrUnresolvedPlaceholders` (2176) | Remove the exercised unjustified-town-role key from a catalog, omit one of its placeholders, or break rendering. Despite IDs named `txt-missing`/`vc-missing`, the fixture reports every stored resource as present; no missing-channel producer is exercised. Replacing Spanish wording with English while keeping placeholders would pass. |
| `syncReportEnforcesStructuredProblemContract` (2216) | Break key extraction, rendering for its two handcrafted problems, mode/proposal getters, or null guards/defaults. It tests no producer and never attempts an English string as a `Problem` key. The expected Spanish text itself contains `space`; the extra supplied `role` placeholder is unused. |
| `allSyncProblemKeysInCatalogHaveMatchingPlaceholdersAndInvariantDomainTerms` (2263) | Remove/add/reorder one catalog key, change placeholders in one language, or introduce one of the specifically forbidden translations. **No production Java producer change is required or observed by this test.** Changing both templates to the same unsupplied placeholder passes; so does English prose that avoids its forbidden-word list. |

For the remaining changed tests:

| File / test (line) | Production change that would make it fail; limit of the assertion |
|---|---|
| `space/DefaultSpaceServiceTest.createFailsWithDiscordUnavailableWhenGatewayOffline` (397) | Delete the new refusal audit, change its failure flag/severity/identity/action, lose its cause, or submit a guild operation. It reads a real SQLite row through the real sink, but the audit executor is inline; it proves service behavior, not asynchronous shutdown or the command path. |
| `space/DefaultSpaceServiceTest.createFailsWithDiscordUnavailableWhenMissingPermissions` (422) | Delete the permission-refusal audit, lose its permission cause/failure metadata, or admit the request. Same inline-executor limitation; no new test covers a thrown permission verification. |
| `space/DefaultSpaceServiceTest.createSucceedsWhenPreconditionsMet` (487) | Duplicate the healthy admission/completion audit, omit it, or mark it failed/wrong severity. Exactly one actual row is asserted. |
| `DiscordTownyWiringAuditTest.wiringWritesAuditRowToDatabaseWithUnconfiguredLogChannel` (115) | Delete the service refusal event or disconnect database delivery and its refusal-row assertion fails. Its real asynchronous sink makes that assertion racy (I1); the manually recorded second event tests sink functionality only. It never executes `/dt create`. |
| `minecraft/MinecraftCommandsTest.syncReportsBothRepairsAndProblemsInRepairMode` (911) | Omit the problem entry/header or repaired summary, or announce clean success. Replacing localized entry text with raw keys still passes because the `get` stub ignores its placeholder map. |
| `minecraft/MinecraftCommandsTest.syncReportsProblemsInReportMode` (941) | Omit the report problem/header/proposal summary or announce a clean report. The same stub masks incorrect problem text and placeholder values. |
| `minecraft/SyncMinecraftCommandsTest.dtSyncWithNormallyCompletedReportCarryingFailuresReportsErrorsAndDoesNotAnnounceCleanSuccess` (496) | Drop the entries, change the count, or announce clean success. It verifies key-valued entries because `label` is an identity stub; using raw `problemKeys()` still passes. The scheduler runs inline and proves invocation, not a real main-thread handoff. |
| `minecraft/SyncMinecraftCommandsTest.dtSyncWithRepairedInconsistenciesAndFailuresReportsRepairedKey` (718) | Omit/change the repaired counts, problem count/entry, or announce clean success. Same identity-rendering and inline-scheduler limitations. |
| `minecraft/SyncMinecraftCommandsTest.dtAdminSyncInReportModeWithProblemsReportsProblemKeys` (781) | Render the console problem as a raw key, lose `{batch}`/`{error}`, use the player catalog, or omit the entry. This one uses real bundled English rendering and verifies the actual rendered component, so it does catch that regression. |

Thus **yes**, removing the service refusal audit breaks real-row tests, and changing several exercised producer keys back to prose breaks structured-key assertions. **No**, the suite does not prove every producer is protected, that a player never receives English via placeholders, or that the actual offline command writes a row. The existing unchanged command test preserves the bypass in B1. A bare string added to `List<Problem>` also fails typed compilation; wrapping prose in `new Problem(...)` remains possible.

**Remaining checklist coverage and verification limits**

Identity/access: the diff adds no name-based resource adoption or access decisions; synchronization continues using town UUIDs and persisted Discord IDs. Audit targets retain the preexisting town-name convention, with its rename/diagnostic ambiguity, rather than introducing a new identity mechanism. Failed-read versus confirmed-absence branches and mutation success checks are preserved; problems do not count as successful repairs, and the new audit event does not claim success. No new listener blocking, startup work, schema/config setting, or reload mechanism is introduced. Existing message fallback/reload selection remains in use.

Scope: the task card names `sync/` and `space/`; this review assignment explicitly includes both catalogs and the architect's `minecraft/` adaptation. The cross-package test updates support that adaptation and audit wiring. The audit sink itself is unchanged. B1 needs architect coordination with the command boundary, rather than an unreviewed domain workaround. Only this report was written by the reviewer.

Git was available and used for the explicitly requested HEAD comparison, despite the checklist's generic environment warning. Read-only source/catalog checks were performed. No Gradle, Java tests, mutation tests, live Paper/Towny/Discord scenario, shutdown timing experiment, or commit was run. The architect's 616-test result has not been independently reproduced; runtime validation of the fixes remains outstanding.
