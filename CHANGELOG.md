# Changelog

This project follows [Semantic Versioning](https://semver.org/). The version in a
release tag is `v<major>.<minor>.<patch>`; the tag drives the jar name and the
version the plugin reports, so they can never disagree.

The updater refuses any release that does not publish a SHA-256 alongside the jar.

## [Unreleased]

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
