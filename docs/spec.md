# DiscordTowny — Functional Specification

What the plugin does, seen from the outside. No implementation decisions: those
go in `ARCHITECTURE.md`.

Status: **draft v1** — pending approval.
Governing this document: `docs/constitution.md`.

---

## 1. Actors

| Actor | Who they are |
|---|---|
| Resident | Player who belongs to a town |
| Mayor | Resident who is the mayor of their town according to Towny |
| Administrator | Server staff, with permission `discordtowny.admin` |
| Bot | Discord application that executes actions in the guild |

## 2. Account linking

### 2.1 Flow

1. The player executes `/dt link` in-game.
2. The plugin generates a single-use code of 6 alphanumeric characters without
   ambiguous characters, with configurable expiration (default 10 minutes).
3. The player executes `/link <código>` on Discord.
4. The bot validates the code, saves the UUID ↔ Discord ID pair, and responds
   ephemerally.
5. Immediately after, the plugin synchronizes the roles corresponding to
   that account.

### 2.2 Rules

- A UUID links to a single Discord ID and vice versa. An attempt to link
  an already-linked account is rejected with a message explaining how to unlink.
- The code expires over time and is invalidated upon use. Generating a new code
  invalidates the previous one.
- Failed attempts are rate-limited per Discord user to prevent brute force
  attacks on the code space.
- `/dt unlink` in-game and `/unlink` on Discord break the link and
  remove all roles granted by the plugin.
- An administrator can unlink a third party with
  `/dt admin unlink <jugador>`.

### 2.3 Mayor verification

Being a mayor is not declared: it is verified. On every synchronization, the plugin
queries Towny for who the town's mayor is and grants or revokes the
mayor role accordingly. A change of mayor in-game is reflected without
anyone running anything.

## 3. Channels and roles

### 3.1 Structure

- A container category exists, created by the bot the first time it is
  needed. Default name: `Comunidades`.
- **Discord limits to 50 channels per category.** When the current category
  fills up, the bot creates the next numbered one (`Comunidades 2`, `Comunidades 3`) and
  places new spaces there. This is transparent to the user: the actual ceiling
  becomes 500 channels per server.
- For each town with an active space, within that category:
  - a text channel, named by template (default `{town}`),
  - a voice channel, named by template (default `{town}`).
- Which channels are created (text, voice, or both) is configurable.

### 3.2 Roles

- One role per town, by template (default `{town}`).
- One global mayor role, configurable name (default `Alcalde`),
  shared by all mayors on the server.
- The bot's role must be above all roles it manages. If it is not,
  the plugin detects it on startup and warns in the console without attempting to operate.

### 3.3 Channel permissions

| Entity | Permission |
|---|---|
| `@everyone` | View channel: denied |
| Town role | View channel, send messages, connect, and speak: allowed |
| Mayor role | No special permissions at channel level |
| Bot | Manage channel |

The mayor role is a visible distinction and a key for certain commands,
not an access key to channels of other towns. A mayor only sees their own
town's channel, because they only have their own town's role.

No user can obtain a town role on their own. The only assignment method
is the bot, based on Towny's resident list.

## 4. In-game commands

Prefix `/dt`, alias `/discordtowny`.

| Command | Who | What it does |
|---|---|---|
| `/dt help` | Any player | Lists the commands available to the person executing it, with one explanatory line each |
| `/dt link` | Any player | Generates the link code |
| `/dt unlink` | Linked | Breaks their link |
| `/dt status` | Any player | Shows whether they are linked, to which account, and whether their town has a space |
| `/dt create` | Mayor | Creates their town's category, channels, and role |
| `/dt delete` | Mayor | Deletes their town's space, with confirmation |
| `/dt sync` | Mayor | Forces role synchronization for their town's residents |
| `/dt admin sync [town]` | Admin | Reconciles everything, or a specific town |
| `/dt admin unlink <jugador>` | Admin | Unlinks a third party |
| `/dt admin reload` | Admin | Reloads configuration and messages |
| `/dt admin list` | Admin | Lists registered spaces: town, status, channels, residents with role, and last activity |
| `/dt admin info <town>` | Admin | Details of a specific space, including its activity and detected inconsistencies |
| `/dt admin purge` | Admin | Permanently deletes archived spaces, with confirmation |
| `/dt admin update` | Admin | Checks and downloads the latest version right now, without waiting for the automatic cycle |
| `/dt admin update status` | Admin | Indicates the current version, available version, and whether a download is pending |
| `/dt admin prefix [texto\|reset]` | Admin | Shows, changes or restores the prefix the plugin puts before its chat messages |

`/dt help` only shows the commands that the person executing it can use: a player
without a town does not see mayor commands, and no one without permission sees
administration commands.

### 4.1 Rules for `/dt create`

It is rejected, with an explanatory message, if:

- the person executing it is not the town's mayor,
- the person executing it does not have a linked account,
- the town already has a space,
- the town does not reach the configured minimum residents,
- `max_towns` has been reached,
- the creation cooldown is still active,
- the bot is not connected or lacks permissions in the guild.

Upon creation, the bot assigns the town role to all already-linked residents and the
mayor role to whoever executed the command. Residents who link
later receive their role at that time.

## 5. Discord commands

Slash commands. Each can be disabled via configuration. Ephemeral
or public response, configurable per command.

| Command | What it shows |
|---|---|
| `/link <código>` | Links the account |
| `/unlink` | Breaks the link |
| `/town [nombre]` | Town card: mayor, residents, founding date, plots, bank, nation, ruin status. Without argument, author's town |
| `/residents [town]` | Resident list with their status, paginated |
| `/res [jugador]` | Resident card: town, rank, connection status, balance if economy exists |
| `/townlist [página]` | Ordered town list, paginated |
| `/mytown` | Shortcut to own town card |
| `/help` | Lists available Discord commands, with one line each |

### 5.1 Rules

- Data is read live from Towny at the time of responding.
- A command targeting a nonexistent town responds with a clear error, not with an
  empty embed.
- Information commands do not require linking, except those that depend on
  knowing who you are (`/mytown`, and `/town` and `/res` without an argument).
- Every command respects a configurable per-user cooldown.

### 5.1.1 Where linking commands may be used

`config.yml` carries an optional channel for the commands that carry a secret:
`/link` and `/unlink`. When it is set, those two commands work **only** in that
channel. Used anywhere else, the bot answers privately naming the right channel
and **does not redeem the code**, so the player simply tries again where they
should.

The code is not burned by the mistake. Refusing it and invalidating it would
punish a player for a wrong guess about which channel to use, and they would
have to request another one for no security gain: the code was already exposed
by being typed, and it expires on its own.

Information commands are unaffected. `/town`, `/res`, `/residents`, `/townlist`
and `/mytown` reveal nothing that is not already public on the server, so
confining them would be inconvenience without a reason.

When the setting is empty, linking works in any channel the bot can read. That
is the default, because a server with a single channel needs no rule.

### 5.2 Mandatory linking for access

Viewing or typing in a town channel requires having a linked account. An
unlinked resident remains a resident in-game, but receives no role and
cannot see the channel until they link. The rejection message explains how to do so.

## 6. Synchronization

### 6.1 Event-driven

The plugin reacts to Towny events and updates Discord immediately:

| Event | Effect |
|---|---|
| Resident joins a town | Receives the town role |
| Resident leaves or is kicked | Loses the town role |
| Change of mayor | The mayor role transfers from one to the other |
| Town renamed | Channels and role are renamed |
| Town deleted or in ruins | Starts the deletion policy |
| Player joins the server | Their roles are reconciled |

### 6.2 Periodic

A configurable job scans the state and corrects discrepancies: extra roles,
missing roles, orphaned channels, and towns without a space despite having it
registered. It can operate in repair mode or notice mode.

### 6.3 On demand

`/dt sync` for one's own town, `/dt admin sync` for everything.

### 6.4 Golden rule

In any discrepancy, Towny wins. If someone manually received the role of a town
they do not belong to, synchronization removes it.

## 7. Town space lifecycle

1. **Creation** — at the mayor's request, if conditions are met.
2. **Active** — synchronized via events and periodically.
3. **Archived** — when the town disappears, falls into ruins, or the mayor executes
   `/dt delete`. The channel is moved to a configurable archive category, the
   town role is deleted, and the channel remains **visible only to
   administrators**, in read-only mode. Nothing is deleted automatically.

   Former residents can no longer see the channel: the role disappears, and that role was
   what granted access. This choice is made because roles are the scarce resource (250 per
   server) and keeping them for every dead town would exhaust the quota. The history
   is preserved intact and returned to its residents if the town revives.
4. **Deletion** — never automatic. An administrator deletes archived
   spaces with `/dt admin purge`, with explicit confirmation.

If the town revives or is recreated with the same name while archived, the space
is restored with its history intact.

### 7.1 State visibility

The plugin records, for each space, its status, when it was created, when it was
archived, how many residents hold the role, and when activity last occurred in
its channels. This information is queried with `/dt admin list` and
`/dt admin info`, and is used to decide what to archive or purge.

## 8. Stored data

| Data | Purpose |
|---|---|
| UUID, Discord ID, linking date | Verified identity |
| Town, category ID, channels, and role | Knowing what the plugin manages |
| Space status, creation and archiving dates, last activity | Lifecycle and administration queries |
| Pending link codes | Verification, with expiration |
| Bot action log | Diagnostics and auditing |

The specific schema goes in `ARCHITECTURE.md`.

## 8.1 Discord log channel

The bot posts what it does to a configurable log channel: creations,
archivings, deletions, role changes, linkings, detected inconsistencies,
and errors. The level of detail is configurable.

To prevent this from affecting the server, messages are queued and sent
in batches from outside the main thread, at a configurable interval. If the
channel does not exist or the bot cannot write to it, the plugin continues operating and
logs the problem once to the console.

## 9. Configuration

According to the agreed table: Discord, database, structure, roles, limits,
lifecycle, synchronization, linking, commands, and messages. Messages live
in a separate file from `config.yml`.

Not configurable: the channel permission model, the database
schema, and the use of a single role per town.

### 9.1 Language

`config.yml` carries a `language` key that selects the language of every text
the plugin shows. It accepts two values, `en` and `es`. The default is `en`.

The plugin ships one message file per language, `messages_en.yml` and
`messages_es.yml`, and writes both to its folder on first run. There is no
single `messages.yml` any more. Both files belong to the server owner: an
existing text is never overwritten, and the owner may edit any text in them.

A version that adds new texts would otherwise leave an existing file without
them, and those texts would reach players in English until the owner deleted
the file and lost every edit. So on startup the plugin **adds the keys the file
lacks**, taking each value from the bundled file of that same language, and
leaves every key already present exactly as the owner wrote it. Adding a key is
the only write the plugin ever makes to these files: it never removes a key the
owner kept, never reorders what is there, and never rewrites the file's
comments or formatting.

The setting covers everything that reaches a person, in game and in Discord
alike: command replies, error messages, and the text of the embeds the bot
publishes. It does not reach the console, which stays in English so that logs
are the same for everyone reporting a problem.

Resolution of a text follows a fixed order, and it never ends in a blank:

1. The key in the selected language file.
2. The key in the bundled English file, when the selected file lacks it. This
   is what an edited or outdated file falls back to. The plugin warns once per
   missing key, naming the key and the file.
3. A visible placeholder naming the key, when English lacks it too. That means
   the plugin shipped incomplete, and it must be obvious rather than silent.

An unrecognized value does not stop the server. The plugin reports the exact
key, states which languages exist, falls back to `en`, and carries on: the
language of a message is not a reason to refuse to operate, and this is the
degraded mode the rest of the configuration already promises.

`/dt admin reload` re-reads the language and the message files, so changing
`language` takes effect without restarting the server.

**One language at a time.** Whatever a player reads is in the configured
language: command replies, error messages, embed field names, button captions
and list headings alike. A reply that is half translated is worse than one that
is not translated at all, because it reads as a bug.

**Domain terms are the exception, and they are invariant.** `town`, `nation` and
`resident` stay as they are in both languages. They are what Towny itself shows
and what the commands are called, so translating them in prose while the player
types `/town` would create the very mixture this rule exists to remove.

**Discord command metadata carries both languages.** The name and description a
slash command shows are registered with localizations, so each viewer sees their
own client's language. This is not governed by `language`, which is a
server-wide setting: two players in the same guild can read Discord in different
languages, and the plugin should respect that rather than impose the server's
choice on its own interface.

### 9.2 Permissions

Two nodes, declared by the plugin so that a permissions manager can discover
them:

| Node | Covers | Default |
|---|---|---|
| `discordtowny.use` | The player commands: `help`, `link`, `unlink`, `status`, `create`, `delete`, `sync` | everyone |
| `discordtowny.admin` | The whole `/dt admin` block | operators |

Both are declared in `paper-plugin.yml` with their defaults. Undeclared nodes are
invisible to LuckPerms and fall back to operator status, which is why every
command that checks a permission must have its node declared.

`/dt help` lists only what the person running it may actually use, so a player
without `discordtowny.admin` never sees the administration block.

Holding `discordtowny.admin` does not imply `discordtowny.use`: an operator who
was explicitly denied the player commands keeps that denial. Inheritance between
the two is the server owner's decision to configure, not ours to assume.

## 9.1 The chat prefix

Every message the plugin writes in the game begins with a prefix, defined in both
catalogs as `prefix` and rendered with Essentials-style `&` colour codes.

An administrator can change it from the game with `/dt admin prefix`, so a server
can put its own name and colours in front of the plugin's messages without editing
a file or restarting:

- with no argument, it shows the prefix in use, both as it looks and as it is
  written, so the administrator can copy and adapt it;
- with an argument, it becomes the new prefix, immediately and for everyone;
- with `reset`, the catalog's prefix returns.

A changed prefix is stored, survives a restart and a reload, and is not undone by
a catalog upgrade. It applies to what players read in chat. The console and the
plugin's Discord messages keep the catalog prefix, for the same reason the console
stays in English whatever the players read: a log has to remain identifiable.

Changing the prefix is a privileged action and is audited like the rest.

## 10. Errors and failures

- If the bot cannot connect, the Minecraft server operates
  normally and plugin commands respond that Discord is unavailable.
- If an operation fails halfway, the state is marked as inconsistent and
  the next reconciliation repairs it. A channel is never left accessible to
  anyone who should not see it.
- Errors are logged with context: town, action, and cause.
- Neither the token nor credentials ever appear in logs or in user-facing
  messages.

## 10.1 Updates

### Checking

The plugin periodically checks published releases in the official
GitHub repository and compares against its own version. The interval is configurable
and checking can be completely disabled.

When a new version is available, notice is given:

- in the console on startup,
- to administrators upon joining the server,
- in the Discord log channel, once per version.

The notice includes the available version, current version, and a summary of changes.

### Downloading

Downloading is **automatic**: upon detecting a new version, the plugin downloads it
without waiting for anyone. `/dt admin update` forces checking and downloading on the
spot. Automatic behavior can be disabled via configuration.

The download:

- comes only from the official repository, via HTTPS,
- is verified against the checksum published in the release, and discarded if it does not
  match,
- is placed into the server's `update` folder, without touching the jar in use.

### Applying

The new version takes effect **upon restarting the server**, via Paper's standard
mechanism. The plugin is never hot-swapped: doing so with
open connections to Discord and the database leaves corrupt state.

`/dt admin update status` indicates whether an update is downloaded and pending
a restart.

Once the update is downloaded, notice is given again stating that restarting
is sufficient to apply it.

### Rules

- If the release brings breaking configuration changes or requires migration,
  it is indicated in the notice and additional confirmation is required.

  A release counts as breaking when **either** its major version differs from the
  running one, **or** its notes contain the line `[breaking]`. The first rule is
  automatic and cannot be forgotten; the second exists because a change can break
  a configuration without earning a major bump. The release pipeline emits the
  marker, so the two are defined together and not inferred by guesswork.

  Such a release is **never staged automatically**, whatever `auto-download`
  says. It is announced, and it waits for an administrator to confirm. Until
  then, nothing is downloaded: a jar sitting in `update/` is applied by the next
  restart, and a restart is not a decision anyone takes deliberately.
- A network failure or incorrect checksum leaves nothing half-done: the
  download is discarded and reported.
- Without a connection to GitHub, the plugin functions normally and logs the failure
  once.

## 11. Out of scope for this version

Chat bridge, nations, multi-server, web panel, integrations with economy or
war plugins, and more than one guild.

## 12. Acceptance criteria

1. A player links their account and the link survives a restart.
2. A mayor executes `/dt create` and the category, channels, and role appear with the
   correct permissions.
3. A linked resident sees their town's channel; a player from another town does not
   see it or appear in the channel's member list.
4. Kicking a resident removes their role without anyone executing anything.
5. Changing mayors transfers the mayor role.
6. Deleting a channel manually and running `/dt admin sync` leaves the state consistent.
7. With the bot offline, the server starts and operates without cascading errors.
8. A user manually given another town's role by an admin loses it in the
   next reconciliation.
9. An unlinked resident does not see their town's channel; upon linking, they see it without
   executing anything else.
10. A deleted town leaves its channel archived and readable for administrators,
    never deleted without an administrator ordering it.
11. With the log channel active and many consecutive operations, the server does not
    drop ticks.
12. With `language: es` every text reaching a player is in Spanish, in game and
    in Discord; switching to `en` and running `/dt admin reload` changes all of
    them, with nothing left in the previous language.

## 13. Public documentation

The repository includes documentation written for players and server administrators,
not for developers:

- `README.md`: what the plugin is, what it solves, requirements, installation, and the
  command list.
- `docs/user-guide.md`: walkthrough in plain language — how to link the account,
  how to create the town space, what each person sees, and what happens when someone
  joins, leaves, or the town disappears. No code or architecture details.

Both are kept up to date as part of the work, not at the end.
