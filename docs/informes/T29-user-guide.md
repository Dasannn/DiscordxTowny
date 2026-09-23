# T29 Implementation Report — User Guide Refresh

**Task**: T29 — The user guide has missed five releases  
**Document**: `docs/user-guide.md`  
**Report**: `docs/informes/T29-user-guide.md`  

---

## 1. Overview

`docs/user-guide.md` had not been updated since 2026-09-18. Since that date, five releases (1.0.0, 1.1.0, 1.1.1, 1.2.0, and 1.3.0) introduced significant functionality, bug fixes, and administrative features that were absent or inaccurate in the guide.

The user guide has been brought up to date in its original player- and administrator-friendly voice: short sentences, plain language, no developer implementation jargon or class names, and every documented command verified against `com.discordtowny.minecraft.MinecraftCommands`.

---

## 2. What Was Added

1. **Dedicated Server Administration Section (`## 7. Server administration`)**:
   - Administrative commands grew beyond being scattered across player sections. A dedicated section now collects all operator capabilities under permission `discordtowny.admin` (distinguished from player permission `discordtowny.use`).
   - **Chat Prefix (`/dt admin prefix`)**:
     - Viewing the current prefix twice: rendered (with colors) and raw (with `&` formatting codes) for easy copying.
     - Changing the prefix immediately in chat using `&` colour codes (with an example: `/dt admin prefix &6[&eRobledal&6] &r`).
     - Setting an empty prefix via `""` or `''`.
     - Restoring the catalog default with `/dt admin prefix reset`.
     - Explicitly stating that the prefix changes what players see in game chat, while the console and Discord messages keep the default catalog prefix (`&8[&bDiscordTowny&8] &r`) to ensure logs remain identifiable.
     - Documenting persistence (stored in database across reloads and restarts), validation rules (no line breaks, no `{}` placeholders, 32 visible character limit), and startup audit requirement.
   - **Updates**:
     - Explaining automatic GitHub release checks and notifications: in console on startup, in game when an administrator joins, and in the Discord log channel.
     - Commands: `/dt admin update status` (current version, pending downloads, check failure causes), `/dt admin update` (immediate check and download), and `/dt admin update confirm` (for breaking updates).
     - Explaining SHA-256 checksum verification before staging in `update/`, and that updates are applied cleanly on server restart (never hot-swapped).
     - Explaining that breaking updates (major version bump or `[breaking]` note) suppress automatic downloading and require administrator confirmation.
   - **Space Inspection & Maintenance**:
     - Documenting `/dt admin list [page]` and `/dt admin info <town>`.
     - Documenting `/dt admin sync` (reconciling all towns or a single town `/dt admin sync <town>`) in report vs repair mode.
     - Documenting `/dt admin unlink <player>`.
     - Documenting `/dt admin reload` (live configuration and message reloads, noting when restarts are required for Discord credentials or database settings).
   - **One Bot Across Several Discord Servers**:
     - Explaining that one bot token can serve multiple Discord servers when each Minecraft server specifies its own `guild-id` in `config.yml`, preventing command interference and cross-server crosstalk.

2. **Player and Discord Query Enhancements**:
   - In Section 1 (Linking): Added note about optional dedicated link channels (`linking.channel-id`), rate limiting on failed attempts, and administrative unlinking.
   - In Section 4 (Member Changes): Clarified sync report behavior and added reference to `/dt admin sync`.
   - In Section 6 (Discord Queries): Documented that slash commands must be run inside server channels and that using them in direct messages (DMs) receives a clear refusal (`This command only works inside a server.`).

3. **Troubleshooting Additions (`## 8. Troubleshooting`)**:
   - Added entries for:
     - Direct message command rejections.
     - Prefix change rejection causes (braces, line breaks, length limits, startup audit availability).
     - Skipped spaces during `/dt admin purge`.
     - Staged update requiring server restart.

---

## 3. What Was Found Untrue and Corrected

1. **Archiving with Manually Deleted Discord Channels**:
   - *Previous state*: The guide claimed channels are not deleted automatically and are moved to `Archivo`. However, if a channel, category, or role had been deleted by hand in Discord, archiving previously entered an infinite failure loop every 30 minutes, marking the space inconsistent.
   - *Correction*: Documented that missing channels, categories, or roles deleted by hand are now treated as work already done; the space archives cleanly and the administrator is notified once.
2. **Purging Behavior with Inconsistent Spaces**:
   - *Previous state*: The guide only stated that an administrator can delete archived spaces with `/dt admin purge`. Previously, if an inconsistent space existed, purge reported an empty purge, claiming there was nothing to purge.
   - *Correction*: Documented that `/dt admin purge` skips inconsistent spaces, reports how many were skipped (`Skipped {count} inconsistent spaces. Use /dt admin list or /dt admin info <town> to review them.`), and directs administrators to inspect them rather than claiming there is nothing to purge. Inconsistent spaces are never purged automatically.
3. **Discord Slash Commands in Direct Messages**:
   - *Previous state*: Not documented; previously commands used in DMs timed out with an unresponsive spinner.
   - *Correction*: Documented that commands only work in server channels and that DMs receive a localized refusal.
4. **Administrator Update Notifications on Join**:
   - *Previous state*: Missing from the guide. Prior to 1.1.0 the join notification was composed but never delivered; in 1.1.0 it was fixed and now actively notifies administrators on login.

---

## 4. Observations and Considerations

- **Command Consistency**: All documented commands were cross-checked against `src/main/java/com/discordtowny/minecraft/MinecraftCommands.java`, `LinkMinecraftCommands.java`, `SyncMinecraftCommands.java`, and `TownySlashCommands.java`. No invented or deprecated commands are documented.
- **Catalog Quotations**: Exact phrases quoted (such as the purge skipped notice, default prefix, and DM rejection) match `src/main/resources/messages_en.yml`.
- **Command Argument Syntax**: In `LinkMinecraftCommands.java`, the Brigadier node for unlinking is named `jugador` in code, but the user-facing syntax in English documentation is presented as `/dt admin unlink <player>`, which matches the help catalog description `help.cmd-admin-unlink: "/dt admin unlink <player>"`.
