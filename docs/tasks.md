# DiscordTowny — Tasks

Project work unit. Each task card is the complete assignment for an agent:
a branch, a worktree, a zone of files.

Governed by `docs/constitution.md`. Implements `docs/spec.md` according to `ARCHITECTURE.md`,
in the order of `docs/plan.md`.

Status: **draft v1** — pending approval.

---

## Rules for Every Agent

Before writing a single line, read: `docs/constitution.md`, `docs/spec.md`,
`ARCHITECTURE.md`, and your task card.

- Work **only** on the files in your zone. If you need to touch another, stop and
  notify the architect.
- Do not implement anything not in the spec. If something is missing, notify; do
  not improvise.
- Respect the architecture's threading model. It is the source of half the bugs
  in this kind of plugin.
- Leave tests for what you write. Untested logic is not finished.
- Do not integrate into `main`. Do not review your own work.
- When done, declare the branch ready and state what was left out and why.

Task status values: `pending`, `in progress`, `in review`, `fixing`,
`ready`, `integrated`.

---

## T0 — Project Skeleton

- **Branch**: `feat/esqueleto` · **Phase** 0 · **Depends on**: nothing
- **Responsible**: architect · **Status**: integrated
- **Zone**: project root, `build.gradle.kts`, resources, CI

**Builds**

- Gradle project with Kotlin DSL, Java 25, shadow plugin with relocation
  of JDA and HikariCP.
- Main class that starts up and shuts down cleanly on Paper, with Towny as a
  hard dependency.
- Sample `config.yml` and `messages.yml`, complete according to the
  configuration section of the spec.
- Empty package tree from `ARCHITECTURE.md`, with the dependency rule
  written in a `package-info` or equivalent.
- CI that compiles and runs tests on each push.
- `.gitignore`, license.

**Acceptance**: the jar loads on a clean Paper server with Towny, starts up, and shuts
down without errors or warnings.

**Do not touch**: any functional logic.

---

## T1 — Contracts

- **Branch**: `feat/contratos` · **Phase** 1 · **Depends on**: T0
- **Responsible**: architect · **Status**: integrated
- **Zone**: interfaces in each package, without implementations

**Builds** the signatures separating the zones:

- Storage: operations on links, codes, spaces, and audit logging.
- Discord: enqueue an operation on the guild, query its result,
  publish to the log channel.
- Towny: reading town, residents, mayor, ruined status.
- Configuration: typed objects for each block in `config.yml`.
- Domain: immutable types crossing boundaries.

**Acceptance**: the signatures compile, are documented, and reviewed. No
domain interface mentions JDA, Bukkit, or JDBC types.

**Important**: changing a contract later forces coordinating multiple branches.
Think it through once, properly.

---

## T2 — Storage

- **Branch**: `feat/storage` · **Phase** 2 · **Depends on**: T1
- **Parallel to**: T3, T4 · **Status**: integrated
- **Zone**: `storage/`

**Builds**

- Connection pooling with HikariCP, MariaDB/MySQL, and SQLite with a single code path.
- Numbered migrations that run on startup, with a schema version table.
- Tables `links`, `link_codes`, `spaces`, `audit_log` per the architecture, with
  a configurable prefix.
- Implementation of storage contracts, entirely off the main thread.

**Acceptance**: tests with in-memory SQLite covering every operation and the
full migration chain. The same tests pass against MariaDB.

**Do not touch**: domain, Discord, Bukkit.

---

## T3 — Discord Client and Queues

- **Branch**: `feat/discord-core` · **Phase** 2 · **Depends on**: T1
- **Parallel to**: T2, T4 · **Status**: integrated
- **Zone**: `discord/`, without slash commands

**Builds**

- JDA connection with minimal required intents, clean startup and shutdown.
- Serialized guild mutation queue: single consumer, idempotent steps,
  retries with increasing delay, distinction between transient and
  permanent failure.
- Startup check ensuring the bot's role is above the roles it manages
  and that it has the required permissions.
- Log queue: batches messages, flushes on interval, maximum size, discards
  with drop count when full, never blocks the producer.
- Degraded mode: if there is no connection, the rest of the plugin can query it
  and continue.

**Acceptance**: against a test guild, creating and deleting channels and roles
works; interrupting an operation midway and retrying duplicates nothing; the
log channel withstands a burst without growing unbounded.

**Do not touch**: domain, storage, Bukkit.

---

## T4 — Configuration and Towny Facade

- **Branch**: `feat/config-towny` · **Phase** 2 · **Depends on**: T1
- **Parallel to**: T2, T3 · **Status**: integrated
- **Zone**: `config/`, `towny/`

**Builds**

- Loading `config.yml` and `messages.yml` into typed objects, once, with
  reload support.
- Startup validation: IDs with valid format, positive intervals, templates
  with known placeholders. Invalid configuration is rejected pointing out
  what is wrong, and the plugin starts degraded.
- Token and credentials never appear in logs or dumps.
- Read facade over Towny: town by UUID and by name, residents,
  mayor, ruined status, nation. Read-only, main thread only.

**Acceptance**: validation tests with valid and invalid configurations.
The facade returns correct data against a local server with Towny.

**Do not touch**: domain, Discord, storage.

---

## T5 — Account Linking

- **Branch**: `feat/vinculacion` · **Phase** 3 · **Depends on**: T2, T3, T4
- **Status**: integrated · **Zone**: `link/`, plus its commands in `minecraft/` and
  `discord/`

**Builds**

- Generation of 6-character codes without ambiguous characters, using a
  cryptographically secure generator, configurable expiration, and one active
  code per player.
- `/dt link`, `/dt unlink`, `/link`, `/unlink`, `/dt admin unlink <player>`.
- One UUID to one Discord ID and vice versa; attempt on an already-linked account
  is rejected explaining how to unlink.
- Failed attempt budget per Discord user.
- Upon linking, synchronization is triggered for that account.
- Unlinking removes all roles granted by the plugin.

**Acceptance**: criterion 1 of the spec. A link survives a restart. An
expired or already-used code is rejected.

**Do not touch**: `space/`, `sync/`.

---

## T6 — Space Lifecycle

- **Branch**: `feat/espacios` · **Phase** 4 · **Depends on**: T5
- **Sequential with**: T7 · **Status**: pending · **Zone**: `space/`

**Builds**

- `/dt create` with all its validations: is mayor, is linked, no existing
  space, minimum residents, `max_towns`, cooldown, bot available.
- Idempotent creation task: parent category if missing, role, text channel,
  voice channel, permissions, initial role assignment. Each ID is
  persisted before proceeding.
- Exact permissions from the spec: `@everyone` without view, town role with access.
- Town rename: renames channels and role.
- Archiving: channel set to read-only, moved to archive category, role
  deleted. Nothing deletes itself.
- Restoration if the town is revived while archived.
- `/dt delete` with confirmation.

**Acceptance**: criteria 2, 3, 10. Cutting the server off midway through creation and
reconciling leaves the space complete, without duplicates.

**Do not touch**: `sync/`, commands outside this task card.

---

## T7 — Synchronization and Reconciliation

- **Branch**: `feat/sincronizacion` · **Phase** 4 · **Depends on**: T6
- **Status**: pending · **Zone**: `sync/`, listeners in `minecraft/`

**Builds**

- Towny listeners: resident join and leave, kick, mayor change, rename,
  deletion, and ruin. Each reads and delegates; nothing heavy on the
  main thread.
- Calculation of "roles applicable to this player" as a set, and
  applying the difference. Only roles managed by the plugin are touched.
- Synchronization upon joining the server and upon linking.
- Periodic reconciliation job: missing channels, deleted roles,
  registered spaces without channels, members with roles they shouldn't have,
  `INCONSISTENTE` spaces. Repair mode or report-only mode. Batched, with pauses.
- `/dt sync` and `/dt admin sync [town]`.

**Acceptance**: criteria 4, 5, 6, 8. A manually granted role is removed on the
next pass. A manually deleted channel is detected and repaired.

**Do not touch**: `space/` except to consume it.

---

## T8 — In-Game Commands

- **Branch**: `feat/comandos-juego` · **Phase** 5 · **Depends on**: T7
- **Parallel to**: T9 · **Status**: pending · **Zone**: `minecraft/`

**Builds**

- `/dt help`, filtered by what the executor has permission to use.
- `/dt status`.
- Admin block: `list`, `info <town>`, `purge` with confirmation, `reload`.
- Full permission tree and argument autocompletion.
- Immediate response on asynchronous operations, with subsequent confirmation.

**Acceptance**: each command responds correctly with and without permissions, with and
without Discord available, inside and outside a town.

**Do not touch**: domain. If you need something it does not expose, notify.

---

## T9 — Discord Commands

- **Branch**: `feat/comandos-discord` · **Phase** 5 · **Depends on**: T7
- **Parallel to**: T8 · **Status**: pending · **Zone**: slash commands in
  `discord/`

**Builds**

- `/town`, `/res`, `/residents`, `/townlist`, `/mytown`, `/help`.
- Rich embeds, pagination where needed, live data read from Towny.
- Per-command configurable activation, cooldown, and ephemeral or public visibility.
- Clear errors for non-existent town or player, and for unlinked author on
  commands that require it.

**Acceptance**: each command responds within Discord's time limit,
even with many towns. No empty embed for a non-existent entity.

**Do not touch**: domain, the mutation queue.

---

## T10 — Updater

- **Branch**: `feat/actualizador` · **Phase** 6 · **Depends on**: T0
- **Parallel to**: almost everything · **Status**: pending · **Zone**: `update/`

**Builds**

- Periodic check against GitHub releases using the JDK HTTP client.
  No new dependencies.
- Repository URL **constant in code**, not configurable.
- Semantic version comparison. Response caching, respects rate limits,
  off the main thread, with maximum timeout.
- Automatic download upon detecting a new version, toggleable in
  configuration, and `/dt admin update` to force it.
- Download to temp file, SHA-256 verified against release checksum, and only
  then moved to the server's `update` folder. Size cap.
- Notifications: console on startup, admins on join, log channel once per
  version. And again once downloaded.
- `/dt admin update status`.

**Acceptance**: a mismatched checksum discards the download without leaving leftovers.
Without network, the plugin functions normally and logs the failure only once. The active jar
is never touched.

**Do not touch**: absolutely anything outside `update/` and its commands.

---

## T11 — Public Documentation

- **Branch**: `docs/publica` · **Phase** 6 · **Depends on**: approved spec
- **Parallel to**: almost everything · **Status**: integrated · **Zone**: `README.md`,
  `docs/user-guide.md`

**Builds**

- `README.md`: what it is, what it solves, requirements, installation, minimal
  configuration, command list, license.
- `docs/user-guide.md`: walkthrough in player language — linking the account,
  creating the town space, what each person sees, what happens when joining, leaving, or
  when the town disappears.
- No code or architecture details.
- Bot creation instructions and permissions required in Discord.

**Acceptance**: someone who has never seen the plugin installs and uses it following
only these two documents.

**Do not touch**: code.

---

## T12 — Hardening

- **Branch**: `chore/endurecimiento` · **Phase** 7 · **Depends on**: T8, T9, T10
- **Responsible**: architect · **Status**: pending

**Does**

- Run through the eleven acceptance criteria from the spec on a real server.
- Cause failures: bot down, database down, channel manually deleted, role
  manually assigned, cutoff midway through creation, burst of operations.
- Measure that the server does not drop ticks with the log channel saturated.
- Audit that no secrets are leaked in any log or error message.

**Acceptance**: all eleven criteria pass and no induced failure leaves open
permissions or unrecoverable state.

---

## T13 — Release

- **Branch**: `chore/release` · **Phase** 8 · **Depends on**: T12
- **Responsible**: architect · **Status**: pending

**Does**

- Semantic versioning and changelog.
- Pipeline that publishes the jar **and its SHA-256 checksum** to the GitHub release.
  Without a published checksum, T10 will not work.
- Contributing guide and issue templates.

**Acceptance**: a release published from the pipeline is detected and downloaded
correctly by the updater on an instance running the previous version.

---

## Quick Map

| Task | Phase | Depends on | Parallel to | Zone |
|---|---|---|---|---|
| T0 Skeleton | 0 | — | — | root |
| T1 Contracts | 1 | T0 | — | interfaces |
| T2 Storage | 2 | T1 | T3, T4 | `storage/` |
| T3 Discord and queues | 2 | T1 | T2, T4 | `discord/` |
| T4 Configuration and Towny | 2 | T1 | T2, T3 | `config/`, `towny/` |
| T5 Linking | 3 | T2, T3, T4 | T10, T11 | `link/` |
| T6 Spaces | 4 | T5 | T10, T11 | `space/` |
| T7 Synchronization | 4 | T6 | T10, T11 | `sync/` |
| T8 In-game commands | 5 | T7 | T9 | `minecraft/` |
| T9 Discord commands | 5 | T7 | T8 | `discord/` slash |
| T10 Updater | 6 | T0 | almost everything | `update/` |
| T11 Documentation | 6 | spec | almost everything | `README`, guide |
| T12 Hardening | 7 | T8, T9, T10 | — | all |
| T13 Release | 8 | T12 | — | pipeline |
