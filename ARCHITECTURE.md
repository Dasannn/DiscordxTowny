# DiscordTowny — Architecture

How the plugin is built internally. This document is governed by
`docs/constitution.md`, and describes the implementation of `docs/spec.md`.

Status: **approved and governing**. Changes to this document need the owner’s approval.

---

## 1. Stack

| Element | Decision | Why |
|---|---|---|
| Language | Java 25 | Target server version |
| Server | Paper for Minecraft 26.2 | Project requirement |
| Build | Gradle with Kotlin DSL | Used by Paper and Towny; shadow for packaging |
| Discord | JDA | The reference library in the plugin ecosystem, with built-in rate limit handling |
| Database | HikariCP + JDBC, MariaDB/MySQL or SQLite | A single code path for both engines |
| Tests | JUnit 5 + Mockito | Standard, without involving a server |

A single jar is packaged with relocated JDA and HikariCP, to avoid colliding with
other plugins loading the same libraries.

## 2. Module structure

A single Gradle module. Packages by responsibility, not by technical layer:

```
com.discordtowny
├── DiscordTownyPlugin.java      startup, shutdown, wiring
├── model/                       immutable types crossing boundaries
├── config/                      loading config.yml and messages
├── storage/                     data access, migrations, DAOs
├── link/                        account linking and codes
├── space/                       town space lifecycle
├── sync/                        event-driven and periodic reconciliation
├── discord/                     JDA client, operation queue, slash commands, logs
├── minecraft/                   in-game commands, Towny listeners
├── towny/                       read facade over Towny API
└── update/                      checking and downloading new versions
```

**Dependency rule**: `minecraft` and `discord` are input and output adapters;
both depend on `link`, `space`, and `sync`. Never the other way around. `towny`
is the only gateway to the Towny API, and `storage` the only one to the database.
No class in `space` or `sync` imports JDA or Bukkit.

`model` does not depend on anything, and all others may depend on it. It exists
precisely for that reason: if immutable types lived in `link` or `space`,
`storage` would depend on them and they on `storage`, closing a package cycle.
Each package has its rule written in its `package-info`.

This rule is what allows multiple agents to work concurrently without colliding:
one agent builds `discord/`, another `storage/`, another `sync/`, against agreed
interfaces.

## 3. Threading model

Three separate worlds, with explicit boundaries:

| Thread | What runs |
|---|---|
| Server main thread | In-game commands, Towny listeners, all Towny API reads |
| Plugin pool | Database, reconciliation logic |
| JDA threads | Discord calls, responses to slash commands |

Hard rules:

1. The Towny API is read **only** on the main thread. Listeners capture what
   they need in an immutable object and pass it to the pool.
2. The database and Discord are touched **only** off the main thread.
3. Returning to the main thread is done with the Paper scheduler, and only if it
   is necessary to speak with Towny or a player.
4. Nothing blocks waiting for Discord. The result is handled asynchronously.

An in-game command, therefore, responds immediately with "working on it" and
confirms afterward.

## 4. Discord operation queue

Creating and deleting channels and roles is heavily constrained by rate limits
and, worse, these are non-atomic operations: creating a space involves four
calls that can fail on the third.

Design:

- Every guild mutation goes through a **serialized queue**. A single consumer.
  No creating ten spaces in parallel.
- Each operation is described as a **task with idempotent steps**. Before
  creating anything, it checks whether it already exists by its saved ID.
  Retrying a half-finished task does not duplicate channels.
- Retries with exponential backoff on transient failures. Permanent failure
  (missing permissions, reached a Discord limit) aborts the task and marks it.
- A failed task leaves the space in the `INCONSISTENT` state, and the
  reconciliation job picks it back up.

Reads to answer information commands do not go through the queue.

## 5. Data model

Configurable table prefix. Versioned schema with numbered migrations that run
on startup.

**`links`** — verified identity
| Field | Notes |
|---|---|
| `uuid` | Primary key |
| `discord_id` | Unique |
| `linked_at` | |
| `last_known_name` | For display only, never for identification |

**`link_codes`** — pending codes
| Field | Notes |
|---|---|
| `code` | Primary key |
| `uuid` | Unique: one active code per player |
| `expires_at` | Purged upon expiration |
| `attempts` | Brute force prevention |

**`spaces`** — a town's Discord space
| Field | Notes |
|---|---|
| `town_uuid` | Primary key. The Towny UUID, not the name: names change |
| `town_name` | Last known name, for display and rename detection |
| `category_id`, `text_channel_id`, `voice_channel_id`, `role_id` | Discord IDs, null if not applicable |
| `state` | `ACTIVE`, `ARCHIVED`, `INCONSISTENT` |
| `created_at`, `archived_at`, `last_activity_at` | For `/dt admin list` and `info` |

**`audit_log`** — what the bot did
| Field | Notes |
|---|---|
| `id`, `at`, `actor`, `action`, `target`, `result`, `detail` | Feeds the log channel and diagnostics |

Entities are identified by town UUID and Discord ID. Never by name: names get
renamed and break mapping.

## 6. Main flows

### 6.1 Linking

In-game: the code is generated, saved with an expiration, and shown to the player.
Discord: `/link` looks up the code, validates expiration and attempts, writes to
`links`, deletes the code, and queues a synchronization for that account.

Codes are generated using a cryptographically secure generator, not `Random`.

### 6.2 Space creation

1. On the main thread, everything verifiable with Towny is validated: the player is mayor,
   the town exists, meets the minimum resident count.
2. Plugin conditions are checked: no space exists yet, there is capacity, no active
   cooldown.
3. The task is queued, creating in order: category if missing, role, text
   channel, voice channel, permissions, and finally role assignment to linked
   residents.
4. Each step that produces an ID persists it before continuing. If the server crashes
   between steps, reconciliation finishes the job.

### 6.3 Player synchronization

Joining the server, linking, or changing towns: the set of roles corresponding
to the player according to Towny is calculated and compared with those held in Discord.
The difference is applied. Only roles managed by the plugin are touched; other
user roles are ignored.

### 6.4 Periodic reconciliation

Iterates through `spaces` and compares against Discord and Towny: channels that no
longer exist, deleted roles, towns without a space despite having one recorded, members
with roles they should not have, spaces in the `INCONSISTENT` state. Depending on the
configuration, it repairs or only reports. It runs in batches, with pauses, to avoid
saturating rate limits.

## 7. Configuration

Separate `config.yml` and `messages.yml`. They are loaded into typed objects once
and re-read with `/dt admin reload`. Nothing reads from `YamlConfiguration` at
runtime.

Values incoming from the file are validated on startup: correctly formatted IDs,
positive intervals, templates with known placeholders. An invalid configuration
is rejected with a message indicating which line is wrong, and the plugin starts
in degraded mode instead of operating with garbage.

The token and credentials are never printed: they are read into memory and marked
as sensitive in any configuration dump.

## 8. Log channel

Audit events are written to the database and, in parallel, queued for Discord.
A consumer flushes the queue at a configurable interval and sends messages
grouped into a single embed. The queue has a maximum size: if it fills up, the
least relevant events are discarded and the number of dropped events is recorded.
It never grows unbounded and never blocks the producer.

## 9. Towny integration

All contact with Towny goes through an internal facade. The rest of the plugin
does not know Towny classes, only the data it needs. That protects against Towny
API changes across versions: when it changes, only a single package is touched.

Listeners subscribe to events for residents, mayorship, renaming, deletion, and
town ruin. Each listener does the bare minimum on the main thread: read and
delegate.

## 10. Error handling

- Discord connection fails: the plugin enters degraded mode. In-game commands
  respond that Discord is unavailable, guild commands do not exist, and the
  server is unaffected.
- Database fails: commands requiring it are rejected with a clear message.
  No operations are performed on Discord without being able to persist the result.
- A task fails midway: the space remains `INCONSISTENT` and reconciliation
  picks it back up. Leaving a channel uncreated is preferred over creating a
  channel visible to unauthorized users.

## 10.1 Updater

Uses the JDK's built-in HTTP client against the GitHub releases API. No new
dependencies.

- The repository URL is a **code constant**, not configurable. Allowing an
  administrator to point the updater to another source turns an editable
  configuration into arbitrary code execution.
- The version is compared semantically, not as a text string.
- The response is cached and GitHub rate limits are respected. The check runs off
  the main thread, with a maximum timeout.
- The download is written to a temporary file, its SHA-256 is computed and
  compared against the one published in the release. Only if it matches is it
  moved to the server's `update` folder. Otherwise, the temporary file is deleted.
- The download size has a cap, so as not to fill the disk on an unexpected
  response.
- The updater neither reads nor writes any plugin state. It is independent from
  the rest: it can fail entirely without affecting anything else.

The download triggers automatically upon detecting a new version, and also on
demand with `/dt admin update`. It can be disabled via configuration.

What it **deliberately does not** do: hot reload classes or accept a source other
than the official one.

## 11. Testing

- The logic of `link`, `space`, and `sync` is tested with JUnit without a server
  or Discord, because it depends on neither. That is where the real value lies.
- `storage` is tested against in-memory SQLite, including migrations.
- Bukkit and JDA adapters are tested manually using a checklist; no mock
  servers are set up.
- Every acceptance criterion in the spec has its verification, either automated
  or documented manual.

## 12. Deliberate decisions

| Decision | Discarded alternative | Why |
|---|---|---|
| Single Gradle module | Multi-module | No external reuse that justifies it |
| Serialize Discord mutations | Parallelize with rate limit handling | Rate limits make it useless and multiply partial failures |
| Identify by town UUID | By name | Renames would break mapping |
| Archive without deleting | Automatic deletion | Conversation history cannot be recovered |
| Single global mayor role | One mayor role per town | Would double the consumption of the 250 role limit |
| Internal facade over Towny | Use its API throughout the code | Isolates API changes to a single package |
| No caching of Towny data | Cache with invalidation | Live reads are cheap and an out-of-sync cache is the worst kind of bug here |
| Update via Paper's `update` folder | Hot swapping the jar | Reloading a plugin with live connections corrupts state; Paper's mechanism already handles this |
| Fixed download source in code | Configurable source | An editable source turns configuration into arbitrary code execution |

## 13. Ready for nations, without building them

The model does not prevent adding nations later: `spaces` is identified by the
owning entity's UUID and could support a type, and synchronization already
calculates "roles corresponding to this player" as a set. Adding nations would
simply add a source to that calculation, not rewrite it. None of this is being
built now.
