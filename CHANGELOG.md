# Changelog

This project follows [Semantic Versioning](https://semver.org/). The version in a
release tag is `v<major>.<minor>.<patch>`; the tag drives the jar name and the
version the plugin reports, so they can never disagree.

The updater refuses any release that does not publish a SHA-256 alongside the jar.

## [Unreleased]

## [1.2.0] - 2026-09-23

### Added

- One bot token can serve several Discord servers. Each Minecraft server is
  configured with its own `guild-id` and its own channels, and answers only the
  interactions from that server. Previously every instance processed every other
  server's commands against its own database and its own Towny, and they raced to
  answer the same interaction. A command used in a direct message now gets a
  refusal in the reader's language instead of a spinner that times out.

### Fixed

- Archiving a space whose Discord channel had been deleted by hand failed every
  thirty minutes, forever: the channel was required to exist, the failure marked
  the space inconsistent rather than archived, and the next synchronisation tried
  again. Deleting the channel achieves what archiving intended, so a missing
  channel, category or role is now that part of the work already done. The archive
  completes and the operator is told once what was already gone.
- `/dt admin purge` reported an empty purge while an inconsistent space existed,
  because it looks only at archived ones — so the single command that cleans could
  not see the single space that needed cleaning. It now says how many it skipped
  and where to review them. It still never deletes them: an inconsistent space is
  one a human should look at first.

## [1.1.1] - 2026-09-23

No functional change. Published to exercise the updater end to end against a
real release, which is the only way to confirm the delivery-host fix in 1.1.0:
every test of it is a test of our own idea of what GitHub does.

## [1.1.0] - 2026-09-23

### Fixed

- The updater could not reach the release it found. GitHub delivers a release
  asset from `release-assets.githubusercontent.com`, where the repository appears
  as a numeric id, and the policy demanded a path naming the repository, which
  that host can never carry. A server running an older build stayed silently out
  of date while `/dt admin update status` told its administrator there was
  nothing new: the failure existed only as a console warning. The update
  subcommands now report a check that failed instead of answering as though
  nothing had happened.
- Checksum recognition in a release body settled on one rule: a line declares a
  checksum when it carries a SHA-256 keyword or when its first field is
  hash-shaped beside a release artifact. Ordinary prose cannot make a valid
  release unavailable, and a release is still only accepted when a valid checksum
  is bound to its jar.
- Administrators were never told on join that an update was waiting. The notice
  was composed and never delivered: nothing in production called it. It now
  arrives, reads no file on the join thread, and is retried on a reload if its
  listener ever failed to register.

## [1.0.0] - 2026-09-20

First public release.

### Added

- Discord spaces for Towny towns: a category with channels and a role, created,
  archived and purged from Minecraft or from Discord.
- Account linking between a Minecraft player and a Discord user, with expiring
  codes, an attempt limit, and an optional channel that `/link` is restricted to.
- Role synchronisation for residents, mayors and nation members, with `/dt sync`
  reporting what it changed and what it could not.
- A paginated administration list that marks the spaces whose Discord channels
  were deleted by hand.
- Configurable language, with a full Spanish and English catalogue. Console and
  logs stay in English whatever the players read.
- An audit log of every privileged action and every refusal, including refusals
  caused by Discord being unavailable.
- An updater that checks GitHub releases and stages a verified jar in the update
  folder, refusing anything whose published checksum does not match.
- Permissions `discordtowny.use` and `discordtowny.admin`, declared independently
  so a permissions manager can grant one without the other.
- Configuration reload that applies what can be applied live and says plainly
  what needs a restart.
