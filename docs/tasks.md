# DiscordTowny — Tasks

Project work unit. Each task card is the complete assignment for an agent:
a branch, a worktree, a zone of files.

Governed by `docs/constitution.md`. Implements `docs/spec.md` according to `ARCHITECTURE.md`,
in the order of `docs/plan.md`.

Status: **approved and governing**. Changes to this document need the owner’s approval.

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
- **Sequential with**: T7 · **Status**: integrated · **Zone**: `space/`

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
- **Status**: integrated · **Zone**: `sync/`, listeners in `minecraft/`

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
- **Parallel to**: T9 · **Status**: integrated · **Zone**: `minecraft/`

**Builds**

- `/dt help`, filtered by what the executor has permission to use.
- `/dt status`.
- Admin block: `list`, `info <town>`, `purge` with confirmation, `reload`.
- Full permission tree and argument autocompletion.
- Immediate response on asynchronous operations, with subsequent confirmation.

**Inherited from T7, and not optional**

- `DefaultSyncService` needs a main-thread executor supplied at construction:
  `runnable -> Bukkit.getScheduler().runTask(plugin, runnable)`. `LiveTownyFacade`
  throws on any read off the main thread, so wiring it without one is a crash,
  not a slow path.
- `PeriodicSyncJob` needs to be started on enable and stopped on disable. Nothing
  starts it today, so the configured `sync.interval-minutes` does nothing until
  this card wires it.

**Inherited from T14, and not optional**

- Console output must stay English whatever `language` says, and today it does
  not: running `/dt link`, `/dt unlink` or `/dt admin unlink` from the console
  replies in the player language. Spec 9.1 is explicit that the console stays
  English so a log reads the same for everyone reporting a problem. This card
  rewrites command registration anyway, so it fixes it here.
- Slash-command names and descriptions are hard-coded English with no catalog
  keys. Discord localises those per viewer's client locale, a different
  mechanism from `language`. Documented debt; do not invent keys for it without
  the architect deciding the mechanism first.

**Acceptance**: each command responds correctly with and without permissions, with and
without Discord available, inside and outside a town.

**Do not touch**: domain. If you need something it does not expose, notify.

---

## T9 — Discord Commands

- **Branch**: `feat/comandos-discord` · **Phase** 5 · **Depends on**: T7
- **Parallel to**: T8 · **Status**: integrated · **Zone**: slash commands in
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
- **Parallel to**: almost everything · **Status**: integrated · **Zone**: `update/`

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
- **Responsible**: architect · **Status**: integrated — twelve of twelve acceptance criteria passed on a live server

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
- **Responsible**: architect · **Status**: integrated (v1.0.0 published with its SHA-256)

**Does**

- Semantic versioning and changelog.
- Pipeline that publishes the jar **and its SHA-256 checksum** to the GitHub release.
  Without a published checksum, T10 will not work.
- Contributing guide and issue templates.

**Acceptance**: a release published from the pipeline is detected and downloaded
correctly by the updater on an instance running the previous version.

---

## T14 — Configurable Language

- **Branch**: `feat/language` · **Phase** 6 · **Depends on**: T4
- **Parallel to**: almost everything · **Status**: integrated · **Zone**:
  `config/`, `src/main/resources/messages_*.yml`, `src/main/resources/config.yml`

**Builds**

- A `language` key in `config.yml` accepting `en` and `es`, default `en`.
- Two bundled message files, `messages_en.yml` and `messages_es.yml`, written
  to the plugin folder on first run and never overwritten afterwards. The
  current `messages.yml` becomes `messages_es.yml`; the English file is a
  translation of it, key for key.
- The loader picks the file from the configured language and keeps the bundled
  English texts as the fallback for keys the selected file lacks, warning once
  per missing key with the key and the file named.
- An unrecognized value reports the key, lists the accepted languages, falls
  back to `en` and lets the plugin start.
- `/dt admin reload` re-reads the language and both files.
- `/link` registers its Discord option as `code`. It already reads `codigo` as
  a fallback, so guilds that registered the old name keep working.

**Acceptance**: acceptance criterion 12 of the spec. Plus: a message file with
a key deleted falls back to English for that key and warns once, not once per
call; an invalid `language` starts the plugin in English naming the key; and
`/dt admin reload` after editing `language` changes the texts without a restart.

**Do not touch**: the wording of any existing message, the `Messages` interface
seen by its callers, and any package other than `config/`. If a text is missing
from `messages.yml` altogether, report it instead of inventing it.

---

## T15 — A channel for the commands that carry a secret

- **Branch**: `feat/canal-vinculacion` · **Phase** 6 · **Depends on**: T9
- **Status**: integrated · **Zone**: `discord/` slash commands, `config/`,
  `src/main/resources/config.yml`

**Builds**

- An optional `discord.link-channel-id` in `config.yml`, surfaced through
  `PluginConfig` like any other setting.
- When set, `/link` and `/unlink` work only in that channel. Elsewhere the bot
  answers privately, names the right channel, and **does not redeem the code**.
- The refused code stays valid: the player retries where they should.
- Information commands are untouched.
- Empty means no restriction, which is the default.

**Acceptance**: spec 5.1.1. With the setting empty everything behaves as today;
with it set, `/link` in the wrong channel is refused without burning the code and
the reply names the channel; `/town` keeps working anywhere.

**Do not touch**: the linking domain. This is a guard in front of an existing
command, not a change to how linking works.

---

## T16 — Persist the audit log

- **Branch**: `fix/auditoria` · **Phase** 6 · **Depends on**: T2
- **Status**: integrated · **Zone**: `link/`, `space/`, `update/`, the wiring

**Why**

Found on a live server. `docs/spec.md` section 8 lists the bot action log among
the stored data, migration v1 creates `dt_audit_log`, `AuditRepository` and
`SqlAuditRepository` exist and are tested — and **nothing outside `storage/` ever
calls `storage.audit()`**. Every `AuditEvent` goes only to
`DiscordGateway.log(...)`, so an operator with no log channel configured, which
is the default, keeps no record at all. The published `PRIVACY.md` already tells
players this log is kept.

**Builds**

- Every audit event reaches the database as well as the log channel.
- `discord/` does not gain a dependency on `storage/`: publishing to Discord and
  recording to the database are two sinks, and the wiring is what composes them.
- A failure of one sink does not prevent the other. A log channel that is down
  must not cost the database row, and vice versa.

**Acceptance**: after creating a space, linking an account and archiving a town,
`dt_audit_log` holds a row per action with its reason. With the log channel
unconfigured the rows are still written.

**Do not touch**: the events themselves, or what they carry.

---

## T17 — One language at a time

- **Branch**: `feat/idioma-completo` · **Phase** 6 · **Depends on**: T14
- **Status**: integrated · **Zone**: `discord/` slash commands, `minecraft/`,
  the two message catalogs

**Why**

On a live server the Spanish setting still produces English: embed field names
sit next to Spanish values, and every slash command's description is English
regardless. A half-translated reply reads as a bug.

**Builds**

- Every player-visible string follows the configured language: replies, embed
  field names, button captions, list headings, empty-state text.
- `town`, `nation` and `resident` stay **invariant** in both catalogs, per spec
  9.1. They are what Towny shows and what the commands are called.
- Slash command names and descriptions registered with **localizations for both
  languages**, so each viewer sees their own client's language. This is not
  governed by `language`.
- A sweep for any remaining literal that reaches a person from Java.

**Acceptance**: with `language: es` nothing a player reads is in English except
the invariant domain terms; with `language: en` the same in reverse; the console
stays English either way. A Discord client set to Spanish shows Spanish command
descriptions while one set to English shows English, in the same guild.

**Do not touch**: console and log output, which stays English by spec 9.1.

---

## T18 — Permission nodes

- **Branch**: `feat/permisos` · **Phase** 6 · **Depends on**: T8
- **Status**: integrated · **Zone**: `minecraft/`, `src/main/resources/paper-plugin.yml`

**Why**

The plugin uses exactly one permission node, `discordtowny.admin`, and **declares
none**. An undeclared node is invisible to a permissions manager and falls back
to operator status. The player commands check nothing at all: anyone can run
them.

**Builds**

- `discordtowny.use` for the player commands, default everyone.
- `discordtowny.admin` for the `/dt admin` block, default operators.
- Both **declared in `paper-plugin.yml`** with their defaults and a description,
  so LuckPerms can list and complete them.
- Every command checks its node. A player command refuses with an explanation
  from the message catalog; the `/dt admin` block is **hidden** from anyone who
  may not use it, which is what Brigadier's `requires` does. A refusal message
  there would advertise the staff surface to every player, so the generic
  "unknown command" is the wanted behaviour, not a gap.
- `/dt help` keeps listing only what the runner may use.

**Acceptance**: with LuckPerms, denying `discordtowny.use` to a player blocks the
player commands and leaves the administration block governed by its own node;
granting `discordtowny.admin` alone does not grant the player commands; both
nodes appear in `/lp` completion.

**Do not touch**: the domain. This is a guard in front of the commands.

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
| T14 Language | 6 | T4 | almost everything | `config/`, messages |

## T19 — What the sync report says, and what it does not record

- **Branch**: `fix/informe-sync` · **Phase** 6 · **Depends on**: T16, T17
- **Status**: integrated · **Zone**: `sync/`, `space/`

**Why**

Two gaps found while integrating T16 and T17, both left alone on purpose
because they were outside the zone of the task that found them.

`DefaultSyncService` builds its problem descriptions as English literals —
`Towny is unavailable`, `Space for town … is missing required channels` — and
`/dt sync` hands them to the player unchanged. T17 translated everything else
a player reads; these are the remainder, and they cannot be fixed from the
command side because that is not where the text is written.

Separately, when Discord is unavailable `/dt create` is refused at admission
and **writes no audit row at all**. The operator whose bot is down gets nothing
in `dt_audit_log` to say that someone tried. That is the run where the record
matters most.

**Builds**

- Sync problems carry a key and its placeholders rather than a finished
  sentence, so the command can render them in the configured language and the
  log can keep them English.
- An admission refusal is audited, with the reason, without pretending the
  operation succeeded.

**Acceptance**: with `language: es`, no part of a `/dt sync` report reaches a
player in English except the invariant domain terms; the console report stays
English. With Discord unreachable, `/dt create` leaves a row in `dt_audit_log`
recording the refusal and its cause.

**Do not touch**: the audit sink itself, and the decision to refuse. The
refusal is correct; it is the silence about it that is not.

---

## T20 — The commands stop saying they are thinking

- **Branch**: `fix/acuses` · **Phase** 6 · **Depends on**: T17
- **Status**: integrated · **Zone**: `minecraft/`, `discord/`, the two catalogs

**Why**

A command that answers "Sincronizando..." and then answers again is talking
twice about one action. The owner asked for the acknowledgements to go: the
result is the message that matters, and on a fast server the two arrive close
enough together to read as a glitch.

**Builds**

- Remove the interim acknowledgements a command sends before its real reply:
  the sync "working on it", the create "creating", and any other message whose
  only content is that the plugin received the command.
- The final reply stays exactly as it is, in the configured language.
- Where an operation is genuinely slow and Discord requires an acknowledgement
  to keep the interaction alive, that is a protocol requirement, not a message:
  keep the deferral, drop the text.
- Remove the orphaned catalog keys in **both** files, and nothing else.

**Acceptance**: a player running a command sees exactly one reply, the one that
reports the outcome. No slash command interaction expires or errors for lack of
an acknowledgement.

**Do not touch**: the outcome messages, the error paths, or the deferral
mechanics that keep a Discord interaction alive.

---

## T21 — Reload cannot change anything about Discord

- **Branch**: `fix/recarga-discord` · **Phase** 6 · **Depends on**: T15
- **Status**: integrated · **Zone**: `discord/`, `DiscordTownyWiring.java`

**Why**

Found on a live server. An operator added `discord.link-channel-id` and ran
`/dt admin reload`; the setting had no effect, and `/link` ran unconfined in
every channel as if the key were absent.

`JdaDiscordGateway` holds its `PluginConfig` in a final field set at
construction, and reload never rebuilds the gateway - it only asks it to
register the slash commands again, which it does with its own stale config.
So no `discord.*` setting can be changed without a full restart, and nothing
says so: the reload reports success.

This was predicted in `docs/revisiones/t15-canal-link.md` and deferred; the
live run confirmed it.

**Builds**

- A reload applies the new configuration to the Discord side, or refuses to
  claim it did.
- Whatever cannot be applied without reconnecting is named explicitly, so an
  operator knows a restart is needed instead of believing the change took
  effect.
- The token and the guild are the obvious cases that need a reconnection:
  decide deliberately and document the decision rather than silently ignoring
  a change.

**Acceptance**: with the server running, adding `discord.link-channel-id` and
running `/dt admin reload` either confines `/link` immediately, or the reload
states that the change needs a restart. A reload never reports success for a
setting it did not apply.

**Do not touch**: the linking logic, or the guard itself. Both are correct;
they are simply never given the new value.

---

## T22 — The admin list tells the truth, and fits on the screen

- **Branch**: `feat/lista-admin` · **Phase** 6 · **Depends on**: T18, T19
- **Status**: integrated · **Zone**: `minecraft/`, `sync/`, the two catalogs

**Why**

Both found on a live server.

An administrator deleted the channels of an archived space by hand in Discord.
`/dt admin list` still lists that space as if it existed. The plugin is
reporting its own database rather than what Discord actually holds, and an
operator cleaning up has no way to tell which rows are now orphans.

Separately, the list prints every space in one burst. On a server with many
towns it scrolls the chat away, which makes the command useless exactly when
it matters most.

**Builds**

- An archived space whose channels no longer exist is reported as such, or
  reconciled. Decide which, and say why in the report: purging a row is
  destructive and must not happen silently as a side effect of listing.
- `/dt admin list` paginates, like `/townlist` already does on the Discord
  side. Follow that command's page size and argument shape rather than
  inventing a second convention.
- The page indicator and any new wording come from both catalogs.

**Acceptance**: with more spaces than one page, `/dt admin list` shows a page
and says how to reach the next. An archived space whose Discord channels were
deleted by hand is visibly distinguished from one whose channels are intact.

**Do not touch**: `/dt admin purge`, which already exists to delete archived
spaces deliberately. Listing must never delete anything.

---

---

## T23 — Keep an edited catalog complete across upgrades

- **Branch**: `feat/fusion-catalogos` · **Phase** 9 · **Depends on**: T14
- **Responsible**: agent · **Status**: integrated (seven rounds; reviewed five times)
- **Zone**: `src/main/java/com/discordtowny/config/YamlConfigLoader.java` and its
  tests. Nothing else.

**Why**

`saveDefaultFile` writes a catalog only when it is absent, so a version that adds
texts leaves every existing installation without them. Those texts then fall back
to bundled English, and the only way out is deleting the file and losing every
translation the owner edited. This already happened in production: T21 and T22
added five keys and a live server rendered them in English until they were copied
in by hand.

**Builds**

- On startup, for each of `messages_en.yml` and `messages_es.yml` that already
  exists, add the keys present in the bundled file of **that same language** and
  missing from the owner's file. A missing Spanish key takes the bundled Spanish
  text, never the English one.
- A key already present is left exactly as written: same value, same position,
  same quoting. Never remove a key the owner kept, even one the plugin no longer
  uses; they may be using it in a fork.
- Report what was added, once, through the existing warning channel, in English
  like every other log line.

**Constraints that decide whether this is correct**

- **Do not re-serialize the file.** Loading it with `YamlConfiguration` and
  saving it back destroys the owner's comments, ordering and quoting. The file
  must be edited as text.
- **Do not append a block at the end.** A second `admin:` at the end of a file
  that already has one is either invalid YAML or silently shadows the first. A
  missing key is inserted inside its existing section; only a section that is
  absent altogether is appended whole.
- **A file that does not parse is not merged.** It is left untouched and the
  existing fallback warning applies: a syntax error is not a licence to rewrite
  somebody's file.
- **Write once, atomically**, and never leave a half-written catalog behind if
  the write fails. A corrupted catalog is worse than an incomplete one.
- The 9.1 invariants still hold: `town`, `nation` and `resident` stay
  untranslated in both catalogs.

**Acceptance**: a catalog with keys removed and other values edited, plus its own
comments and a custom key of the owner's, comes back with the missing keys added
in their proper sections, every edited value intact, every comment intact, and
the custom key still present. Deleting a section entirely and restarting restores
that section. A file with a syntax error is left byte-for-byte unchanged.

## T24 — The updater can reach the release it found

- **Branch**: `fix/updater-delivery-host` · **Responsible**: agent · **Status**: integrated (sixteen rounds; reviewed eleven times)
- **Follow-up F22**: a body line carrying a `sha256` keyword whose filename is not an artifact of the release — `invalid  sha256/other.zip` with `other.zip` unpublished — leaves no invalid outcome, so it fails to veto. It cannot authorize an unverified install: a release is accepted only when a valid checksum is bound to our jar. The keyword arm stays conjunctive with the artifact gate on purpose; making it unconditional brings back the false refusals of F19, since `Uses  sha256 checksums` has the same two fields.
- **Zone**: `src/main/java/com/discordtowny/update/`, its tests, the `updates:`
  section of both catalogs, and the `/dt admin update` subcommands in
  `MinecraftCommands.java` together with their tests. The command is in the zone
  because the defect lives there: a service that knows the check failed changes
  nothing while the command that answers the admin does not ask it.

**Why**

Version 1.0.0 is published, with its jar and its checksum, and a server running
an older build never sees it. The live test on 2026-09-22 produced this, in the
server log and nowhere else:

```
Unable to check for updates: could not reach GitHub (Untrusted destination
rejected by official source policy:
https://release-assets.githubusercontent.com/github-production-release-asset/1376593898/...)
```

`UpdateSourcePolicy.isAllowedDeliveryRedirectUri` requires the path of the
redirect target to name `Dasannn/DiscordxTowny`. GitHub's release asset delivery
does not work that way any more: it redirects to
`release-assets.githubusercontent.com`, where the repository appears as a numeric
id and the path carries a signed token. The host matches the pattern; the path
cannot match, ever. So fetching the `.sha256` asset is rejected, the whole check
is abandoned, and the update is never offered.

The second half is worse. `/dt admin update status` answered `Estás en la última
versión` — the same sentence it uses when there genuinely is nothing new. The
failure existed only as a console warning. A server that cannot reach GitHub
stays silently out of date while telling its admin the opposite.

**What has to be true**

- A release published by the official repository is reachable: the check
  completes, the update is offered, and `confirm` downloads it.
- The path of a delivery redirect is no longer required to name the repository,
  because it cannot. What must be required instead: the redirect chain **starts**
  at an allowed URL on `api.github.com` or `github.com` for the official
  repository, every hop is HTTPS, and the final host is one of the known GitHub
  delivery hosts, matched exactly rather than by a loose suffix.
- The published SHA-256 remains what authorizes installing a jar. It always was
  the real defence; the path never was. A delivered file that does not match is
  still discarded, and a release with no published checksum is still refused.
- **A check that could not run is never reported as up to date.** The admin who
  runs the command is told the check failed and why, in their language. The
  existing "further network errors will be silenced" behaviour stays: the point
  is not to repeat the warning, it is not to claim success.

**Constraints that decide whether this is correct**

- Do not widen the policy into "any githubusercontent.com host". A suffix match
  on an attacker-controlled subdomain is how this kind of allowlist is defeated.
  Name the hosts.
- Do not follow a redirect that leaves HTTPS, and do not follow an unbounded
  chain.
- A release whose assets are ambiguous, or whose body declares a checksum that
  is not a valid 64-hex token, is still refused outright. That rule is not
  relaxed by this task.

**Acceptance**: with 1.0.0 published and the plugin running an older version,
`/dt admin update` followed by `/dt admin update status` offers 1.0.0 and warns
that it is a breaking change; `confirm` leaves a jar in the update folder whose
SHA-256 equals the published one, with the running jar untouched. With the
network unreachable, `status` says the check failed and never says the server is
up to date.

**Follow-up left open by the fifth review:** U+2028 and U+2029 are refused by the
same guard as U+0085, but only U+0085 is exercised by a test. Flow mappings and
anchors are refused rather than merged; an owner who uses either keeps a file
that can never be upgraded, which was accepted deliberately and can be revisited.

## T25 — The admin who joins is told the update exists

- **Branch**: `fix/update-join-notice` · **Responsible**: agent · **Status**: integrated (five rounds; reviewed four times, the fourth found nothing)
- **Zone**: `src/main/java/com/discordtowny/minecraft/` join listeners and their
  tests, `src/main/java/com/discordtowny/DiscordTownyPlugin.java`,
  `DiscordTownyWiring.java`, `src/main/java/com/discordtowny/update/`, and the
  `updates:` section of both catalogs if a message is missing.
- **Zone widened after the first review**: it started at the listener only, on the
  assumption that `notifyAdminOnJoin` already did the right thing. It does not:
  it reaches the filesystem, which is harmless from a command and not harmless on
  the join thread. A defect the task exposes is inside the task.

**Why**

`DefaultUpdateService.notifyAdminOnJoin` exists, is tested five times, and is
called by nothing but those tests. Production never invokes it: the only join
listener registered at `DiscordTownyPlugin.java:65` is `PlayerJoinSyncListener`,
which dispatches account synchronization and nothing else.

So the notice `spec.md` §10.1 promises — "to administrators upon joining the
server" — has never once been delivered on a running server. Of the three notices
that section requires, console at startup and the Discord log channel work; this
one is a method waiting for a caller.

**What has to be true**

- A player who joins holding `discordtowny.admin` receives the update notice, once
  per join, when the service has something to say.
- A player without that permission receives nothing, ever.
- The notice is the one `notifyAdminOnJoin` already composes — available, breaking,
  downloaded, and the failure line when the last check failed. Do not write a
  second copy of that text.
- `updates.notify-admins-on-join: false` in the config suppresses it, as
  `shouldNotifyAdminsOnJoin` already says.
- Nothing is sent on the main thread that can block it: the service answers from
  its last cached result, so no check is started by a join.
- A degraded plugin, a null update service, or a service that has never completed a
  check sends nothing and logs nothing at warning level. Joining a broken server
  must not spam its admins.

**Notes for whoever takes it**

The wiring point is the same lambda that registers the sync listeners. Decide
whether the notice belongs in `PlayerJoinSyncListener` or in a listener of its own
and say why in the report; a listener whose name says "sync" growing an update
notice is the kind of thing the next reader trips over.

## T26 — One bot, many Discords: each server answers only its own

- **Branch**: `fix/guild-scoped-events` · **Responsible**: agent · **Status**: integrated (two rounds; reviewed once, the finding was against this card)
- **Zone**: `src/main/java/com/discordtowny/discord/TownySlashCommands.java`,
  `src/main/java/com/discordtowny/discord/LinkSlashCommands.java`, their tests,
  `src/test/java/com/discordtowny/DiscordTownyPluginTest.java`, and the `discord:`
  section of both catalogs if a message is missing.
- **Zone widened after round 1**: the plugin test builds interaction mocks that
  never declared a guild, because nothing used to ask. The guard makes them direct
  messages. A test the change invalidates is part of the change.

**Why**

The bot is meant to serve several Discord servers at once: one token, and each
Minecraft server configured through its own `config.yml` with its own `guild-id`
and channels. That is the intended deployment, stated by the owner on 2026-09-23.

Commands are registered per guild — `guild.updateCommands()` — so each Discord
only shows the commands its own Minecraft server registered. But JDA delivers
**every** interaction from **every** guild the bot belongs to, to **every**
connected instance, and no handler filters by guild:

```java
public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    String name = event.getName().toLowerCase(Locale.ROOT);
    if (!SUPPORTED_COMMANDS.contains(name)) {
        return;
    }
```

So with two Minecraft servers on one token, a `/link` used in Discord B is also
processed by server A, against A's database and A's Towny. A link can be written
to the wrong server. Both instances then reply to the same interaction: the first
wins and the second fails with "Interaction has already been acknowledged".

**What has to be true**

- An interaction whose guild is not the configured `guild-id` is ignored
  completely: no reply, no deferral, no database read or write, no Towny call, and
  no audit row. Another instance owns it and will answer.
- This holds for slash commands in both command classes and for button
  interactions, which carry the same exposure through their component ids.
- An interaction with no guild at all — a direct message — is refused, not ignored:
  these are guild commands and a user deserves an answer saying so.
- The handler compares against the current configuration, not a value captured
  when the listener was built: whatever `JdaDiscordGateway.updateConfig` delivers
  is what the guard uses.
- **Corrected after the first review.** This bullet first demanded that the guard
  follow a `guild-id` changed by `/dt admin reload`. It cannot, and must not: T21
  made a changed token or guild id require a restart, and `reload()` refuses the
  change and says so. The card was wrong about the system, not the code.
- Ignoring is silent at normal log levels. A bot in twenty guilds must not fill the
  console with a line per foreign interaction.

**Notes for whoever takes it**

Check how each class holds its configuration before assuming a field is current.
If it is a snapshot, say so in the report rather than reaching outside the zone.

## T27 — A channel deleted by hand is not a reason to retry forever

- **Branch**: `fix/archive-missing-channel` · **Responsible**: agent · **Status**: integrated (five rounds; reviewed three times)
- **Named follow-ups**, from the second and third reviews:
  - **F5** — the success note is raw English assembled from ids
    (`already missing in Discord: text channel 1551…`). It reaches an operator, so
    it belongs in both catalogs. Left for a localisation pass.
  - **F6** — the retry is not guaranteed to converge. If JDA's cache keeps a
    deleted channel through a prolonged gateway outage, every attempt takes the
    transient path, retries exhaust, the space is marked `INCONSISTENT`, and the
    next periodic pass repeats it: the old loop with more attempts per cycle. It
    resolves once the cache learns of the deletion. Needs either an authoritative
    way to settle a source that stays cached, or an explicit statement that an
    operator must intervene after exhaustion.
  - **F7** — a destination category deleted *after* `ensureCategoryWithCapacity`
    returns it is not covered by the null guard; its return value is not evidence
    that the category is still live.
  - **F8** — the archive is no longer atomic and now runs more than once. Role
    deleted, then text moved, then voice failing, then a retry is plausible and
    believed idempotent, but no test exercises it.
- **Zone**: `src/main/java/com/discordtowny/discord/JdaGuildOperationExecutor.java`
  and its tests, `src/main/java/com/discordtowny/sync/DefaultSyncService.java` and
  its tests, and `src/main/java/com/discordtowny/space/DefaultSpaceService.java`
  with its tests for the purge visibility below, and
  `src/main/java/com/discordtowny/minecraft/MinecraftCommands.java` with its tests,
  because that is where the operator is answered — an omission in the zone the
  architect first wrote, not an agent leaving it.

**Why**

Found on the live server on 2026-09-23. Every thirty minutes, the Discord log
channel receives the same line:

```
space_archive <town> — Required text channel <id> not found in
Discord for town <uuid>
```

The channel was deleted by hand after the town was archived. The loop:

1. The periodic sync sees a space whose town should be archived and whose stored
   state is not `ARCHIVED`.
2. It enqueues the archive operation.
3. `JdaGuildOperationExecutor` requires the stored text channel to exist
   (`JdaGuildOperationExecutor.java:232-242`), does not find it, and returns
   `permanentFailure`.
4. The failure marks the space `INCONSISTENT` — never `ARCHIVED`.
5. Thirty minutes later, step 1 sees it again. Forever.

**What has to be true**

- Archiving a space whose channel no longer exists **succeeds**. Deleting the
  channel by hand achieves what archiving intended; there is nothing left to do.
  The stored state becomes `ARCHIVED`, the loop ends, and the operator is told once
  that the channel was already gone — not once every thirty minutes.
- The same holds for a missing voice channel, a missing category and a missing
  role during an archive: absence is the desired end state.
- **This applies to archiving only.** Creating a space, granting roles or syncing
  members must keep failing when something they need is missing: there the absence
  is a real problem and the operator has to know.
- A space whose channel is missing must not be left `INCONSISTENT` by the archive
  path. `INCONSISTENT` means "a human should look at this"; a channel deleted on
  purpose after archiving is not that.
- Nothing is created to replace what was deleted. Archiving never re-creates a
  channel, a category or a role.

**Second defect, found while the first was being fixed**

The owner ran `/dt admin purge` to clear the offending space by hand, and thirty
minutes later the message came back. `DefaultSpaceService.purgeArchived` selects
only `findByState(SpaceState.ARCHIVED)`, and the space is `INCONSISTENT` —
precisely because the failing archive marked it so. **The only command that cleans
cannot see the only space that needs cleaning**, and it answers `admin.purge-empty`
as though nothing were wrong.

Fixing the archive resolves the owner's case: the space will reach `ARCHIVED` and
purge will find it. The trap stays for any space left `INCONSISTENT` by some other
failure, and that is what has to change:

- `/dt admin purge` tells the operator how many inconsistent spaces it skipped,
  instead of reporting an empty purge while such a space exists.
- It does **not** purge them. `INCONSISTENT` means a human should look; deleting
  channels nobody has looked at is worse than saying nothing.
- If a message is missing for that count, it goes in both catalogs.

**Notes for whoever takes it**

Read `markSpaceInconsistent` (around line 1039) and every caller of
`onOperationFailed` before deciding where the change belongs. The fix may be as
small as classifying a missing-channel outcome during archive as success, but
check what else reads that outcome first: the audit log, the Discord log channel,
and the sync report all consume it, and a success that lies about what happened is
worse than the loop.

## T28 — An administrator can put their own name in front of our messages

- **Branch**: `feat/admin-prefix` · **Responsible**: agent · **Status**: integrated (seven rounds; reviewed four times)
- **Named follow-ups**:
  - **F10** — the audit sink is captured before the settings write and used after
    it. A reload that drains and replaces that sink in between can leave the value
    saved, the operator told it succeeded, and nothing queued. Narrow: it needs a
    reload concurrent with the command.
  - **F11** — the partial-failure reply puts the stored value in parentheses. A
    legitimately empty prefix renders as `()`, and a reset names "the catalog
    default" without showing what that default actually is.
- **Zone**: `src/main/java/com/discordtowny/config/YamlMessages.java` and
  `Messages.java`, `src/main/java/com/discordtowny/minecraft/MinecraftCommands.java`,
  `src/main/java/com/discordtowny/storage/` for the stored value,
  `src/main/java/com/discordtowny/DiscordTownyPlugin.java` and
  `DiscordTownyWiring.java` so the settings repository can be handed to the
  commands explicitly, their tests, and the `admin:` section of both catalogs.
- **Zone widened after round 1**: the first zone gave no way to pass the settings
  repository from the plugin, so the agent reached it through the Discord gateway
  and parked it in a static setter. A dependency the task needs has to have a door.
- **Spec**: `docs/spec.md` §9.1, added for this task.

**Why**

Every message the plugin writes begins with `prefix`, defined in both catalogs as
`&8[&bDiscordTowny&8] &r`. A server that wants its own name and colours in front of
those messages has to edit a catalog file and reload. The owner asked for a command
instead.

Nothing has to be built for colour: `YamlMessages.get` already renders
`prefix + text` through `legacyAmpersand()`, so Essentials-style `&` codes work
today. What is missing is a way to change the text and have it stick.

**What has to be true**

- `/dt admin prefix` shows the prefix in use twice: rendered as players see it, and
  raw as it is written, so the administrator can copy it and adapt it.
- `/dt admin prefix <texto...>` sets it, for everyone, without a restart or a
  reload. The rest of the line is the prefix, spaces included.
- `/dt admin prefix reset` restores the catalog's prefix.
- The value is stored in `SettingsRepository`, which already holds this kind of
  thing. It survives a restart, a reload and a catalog upgrade: T23 merges
  catalogs, and a stored prefix must not be undone by that merge.
- The catalog `prefix` remains the default and the fallback: with nothing stored,
  behaviour is exactly what it is today, and if the store is unreachable the plugin
  still prints messages rather than failing.
- It applies to what players read in chat. The console and the plugin's Discord
  messages keep the catalog prefix — the same reason the console stays in English:
  a log has to stay identifiable. `plain()` feeds both of those.
- Changing it is a privileged action: it writes an audit row like the others,
  naming who changed it and to what.

**Limits that matter**

- A prefix is not a message: it carries no placeholders. `{` and `}` are refused
  rather than silently mangled.
- Refuse a line break, and refuse anything long enough to push real text off the
  screen. Choose a limit, say what it is in the refusal, and justify the number in
  the report.
- The empty prefix is legitimate — a server may want none — and is not the same as
  `reset`. Make sure the distinction survives storage.

**Notes for whoever takes it**

`YamlMessages.isUsable` already treats `prefix` as the one key whose blank value is
valid; understand why before touching it. Check every caller of `get`, `plain` and
`label` before deciding where the stored value is read: `label` deliberately
carries no prefix.
