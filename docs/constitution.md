# DiscordTowny — Constitution

Principles document. Defines what the project is, what it is not, and the rules that
no subsequent decision (spec, architecture, plan, tasks) may violate.

Status: **approved and governing**. Changes to this document need the owner’s approval.

---

## 1. Purpose

Paper plugin that connects a Minecraft server running Towny Advanced to a
Discord server, so that each town has its private space on Discord
(text and voice channel) automatically created and maintained by a bot, and
members can query their town data from Discord.

## 2. Product in one sentence

> The mayor types a command in-game and their town gets private channels on
> Discord with the correct permissions, forever, without admin intervention.

## 3. Scope v1

### In scope

- **Verified linking** of Minecraft account ↔ Discord account.
- **Channel management**: creation, renaming, and deletion of a town's
  channels, within a configurable container category (default
  `Comunidades`).
- **Role management**: per-town role and mayor role, assigned and revoked
  automatically by the bot.
- **Membership synchronization**: joining, leaving, being kicked from a town, or
  changing mayors is reflected in Discord.
- **Information commands on Discord**: detailed town data, resident data, and
  listings.
- **Persistence** in MySQL/MariaDB, with SQLite as an automatic fallback.

### Out of scope (v1)

These exclusions are deliberate. Adding any of them requires modifying
this document first.

- Chat bridge between town chat and Discord.
- Channels and roles for nations.
- Multi-server / BungeeCord or Velocity network support.
- Web panel or HTTP API.
- Integration with economy or war plugins (SiegeWar or others).
- Internationalization beyond a single configurable file-based language.

## 4. Platform

| Element | Decision |
|---|---|
| Server | Paper for Minecraft 26.2 |
| Java | 25 |
| Hard dependency | Towny Advanced |
| Discord | A single guild per plugin instance |
| License | Open source on GitHub |

The plugin does not start if Towny is not present. More than one guild
per Minecraft server is not supported in v1.

## 5. Principles

### P1 — Discord reflects Towny, never the other way around

Towny is the single source of truth on who belongs to which town. Discord is
a projection. In any discrepancy, Towny wins. There is no action on Discord
that modifies town membership.

### P2 — Channel membership is not voluntary

No user can join a town channel, self-assign a role, or request access.
The town role is granted exclusively by the bot based on Towny's resident
list. A user who is not a resident cannot see the channel.

### P3 — Verified identity or nothing

No permission is granted on an unverified identity. Linking is proven
with a one-time code generated in-game. The mayor role is granted only
after checking against the Towny API that the linked account is indeed
the mayor of that town.

### P4 — The main thread is sacred

No Discord API call happens on the server's main thread.
No database query happens on the main thread. Minecraft server
performance is never degraded because of Discord.

### P5 — State is reconciled, not assumed

An admin can delete a channel manually, Discord can fail a request, the
server can crash midway. The system assumes state gets out of sync
and must be able to detect and repair it, both periodically and on
demand with an administration command.

### P6 — Destructive actions are confirmed

Deleting a town's channels destroys conversation history. Any
destruction requires explicit confirmation or a configurable grace period.

### P7 — Secrets are not versioned

The bot token and database credentials live in local configuration,
never in the repository, never in logs, never in error messages
shown to users.

### P8 — Configurable where it matters, opinionated where it doesn't

Category names, channel and role name templates, and the deletion
policy are configurable. The internal architecture and permission model
are not.

### P9 — Fail visibly and safely

If Discord does not respond, the Minecraft server keeps running. Errors
are logged with sufficient context to diagnose them, and operations that
fail halfway do not leave open permissions.

## 6. Known limits

A Discord guild allows a maximum of 500 channels, 250 roles, and **50 channels
per category**. The latter forces splitting spaces into numbered categories
as they fill up. With two channels and one role per town, the practical
ceiling is around 240 towns. That limit is imposed by Discord and is not
negotiable. What is configurable is the plugin's own limit (`max_towns`)
and qualification criteria, so that the administrator decides which towns
receive a channel before hitting Discord's ceiling. Upon reaching the limit,
creation is rejected with a clear message, rather than failing opaquely.

Channel and role creation and deletion operations are heavily rate-limited
on Discord. The design must serialize and retry these operations, not fire
them in parallel.

## 7. Development rules

- The project is developed with multiple agents in parallel, one worktree per
  task, and no one works directly on `main`.
- Every branch undergoes code review before merging.
- Nothing is implemented that is not documented in the spec.
- Changing architecture or requirements requires prior approval and updating
  these documents.

## 8. Success criteria

A mayor executes an in-game command and, in under a minute, their town
has private channels on Discord with the correct residents inside and no one
else. A server admin does not have to touch anything.
