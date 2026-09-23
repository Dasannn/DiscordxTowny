# DiscordTowny User Guide

Imagine you play as `AnaCraft` and belong to `Robledal`. The server owner has already configured the bot following the [README](../README.md).

## 1. Link your account

The bot needs to know which Discord account is yours. Having the same name in Minecraft and Discord is no substitute for linking. Without it, the bot cannot give you the role or access to your town's channels.

1. In Minecraft, run `/dt link`.
2. You will receive a six-character code. For example, `A7K9MX`.
3. In the Discord server, select `/link` and enter your code. The example would be `/link A7K9MX`; use the code you received. If the server owner configured a dedicated channel for linking, use `/link` in that channel.
4. The bot confirms the link with a response only you can see. If your town already has a space, you receive your corresponding role and can see its channels.

The code lasts 10 minutes by default and only works once. If you run `/dt link` again, the previous code is no longer valid. Failed attempts are rate-limited. Linking is preserved across Minecraft restarts.

Each Minecraft account links to a single Discord account, and vice versa. To change it, use `/dt unlink` in Minecraft or `/unlink` on Discord and link again. Unlinking removes all roles granted to you by the plugin. An administrator can also remove a link with `/dt admin unlink <player>`.

You can check your account and space status with `/dt status`.

## 2. Create your town space

If you are the mayor of `Robledal`, run `/dt create` in Minecraft. Only whoever is listed as mayor in Towny and has a linked account can do this.

With default configuration, a text channel and a voice channel appear with the town's name, inside `Comunidades`. The bot creates that category when needed for the first time and adds numbered categories (`Comunidades 2`, `Comunidades 3`...) as the limit of 50 channels per category fills up. The server owner can configure text-only, voice-only, or both.

The bot creates a role for `Robledal` and assigns it to its linked residents. You also receive the distinctive `Alcalde` role, shared by all mayors. Anyone who links later receives their role at that moment.

To create the space:

- You must be the mayor and have a linked account.
- The town must not already have a space.
- It must meet the minimum resident count: 2 by default.
- Capacity must remain: the plugin's limit is 200 towns by default, subject to Discord limits.
- The cooldown between creations must have ended: 60 seconds by default.
- The bot must be connected and have the required permissions.

If something fails, the command explains why.

## 3. Who can see the channels

The access granted by the plugin is only for linked residents of that town. They can view and write in their text channel, and connect and speak in the voice channel. An unlinked resident remains in the town in-game, but does not receive the Discord role.

There is no button to self-join or self-assign the role. The bot follows Towny's resident list. If someone manually receives the role of another town, the next synchronization removes it.

The `Alcalde` role is a distinction. It does not grant access to channels of other towns.

## 4. When town members change

No commands need to be run for these changes:

| What happens in Minecraft | What happens in Discord |
|---|---|
| You join a town with a space | If you are linked, you receive its role and access to its channels. |
| You leave or are kicked | You lose that town's role and the access it granted. |
| The mayor changes | The previous mayor loses the distinction and the new one receives it if linked. |
| You link after joining | You receive the roles that correspond to you at that moment. |

The plugin also checks your roles upon joining the Minecraft server. By default, it checks spaces every 30 minutes to correct discrepancies. The administrator can change that interval or configure it to only warn about issues.

If the mayor needs to force a check of their town, they can use `/dt sync`. The command reports what changed or what could not be adjusted. Administrators can check all towns or a specific town with `/dt admin sync [town]`.

## 5. If the town is renamed or disappears

If `Robledal` changes its name, its channels and its role are renamed. A name change does not archive the space.

If the town falls into ruins or disappears, its space is archived. The mayor can also archive the space with `/dt delete`, with confirmation.

Upon archiving:
- Channels are moved to `Archivo` by default and become read-only.
- They remain visible **only to administrators**.
- The town role is deleted (roles are limited on Discord), so former residents no longer see the channels.
- **Channels are not deleted automatically.** Message history is preserved intact.

If channels, categories, or roles were already deleted by hand in Discord, archiving treats that part of the work as already done. The space archives cleanly, and the operator is told once what was already gone.

If the town revives or is recreated with the same name while the space remains archived, the space is restored with its history and residents regain access to their channels.

An administrator can permanently delete archived spaces with `/dt admin purge`, with confirmation. If any spaces are inconsistent, `/dt admin purge` skips them and reports how many it skipped so an administrator can review them. Restoration with history is only possible while the space remains archived, not after purging it.

## 6. Query information from Discord

Type `/`, select the bot's command, and fill in the field with the example value.

| Example | What you can check |
|---|---|
| `/town Robledal` | Mayor, residents, founding date, plots, bank, nation, and whether it is in ruins. |
| `/town` | The same card, but for your town. You need to be linked. |
| `/mytown` | A shortcut to check your town. You need to be linked. |
| `/residents Robledal` | The resident list with their status, split into pages. |
| `/res AnaCraft` | Town, rank, and connection status of that player; also balance if economy exists. |
| `/res` | Your resident card. You need to be linked. |
| `/townlist` or `/townlist 2` | Ordered list of towns; the second example requests page 2. |
| `/help` | Available commands, with an explanation of each. |

Named queries and listings do not require linking. In `/residents Robledal`, specify the town as in the example to query its resident list.

Data is queried from Towny when responding. If you look up a nonexistent town, you receive a clear error.

Commands must be run inside a Discord server channel. If you send a command in a direct message to the bot, it refuses and explains that the command only works inside a server.

If one bot application is shared across several Discord servers, each Minecraft server is configured with its own `guild-id` and answers only the commands in its own server.

By default, information responses are public, except `/mytown` and `/help`, which only you can see. The administrator can change this or disable commands. The cooldown between commands is 5 seconds per user by default.

## 7. Server administration

Commands in this section require the `discordtowny.admin` permission. Regular player commands require `discordtowny.use`.

### The chat prefix

The plugin puts a prefix before every message it writes in chat. By default, it is `&8[&bDiscordTowny&8] &r`, displayed as `[DiscordTowny] `. An administrator can change it from Minecraft with `/dt admin prefix`:

- `/dt admin prefix` — shows the prefix in use twice: rendered as players see it, and raw with its colour codes so you can copy and edit it.
- `/dt admin prefix <text>` — changes the prefix immediately for all players in chat. It supports standard Minecraft `&` colour codes. For example:
  `/dt admin prefix &6[&eRobledal&6] &r`
- `/dt admin prefix ""` — sets an empty prefix. Plugin messages will appear without any tag in front.
- `/dt admin prefix reset` — restores the catalog default prefix (`&8[&bDiscordTowny&8] &r`).

The prefix setting is saved in the database. It survives server restarts, configuration reloads, and catalog updates.

The custom prefix changes what players read in chat. The server console and the plugin's Discord messages keep the original catalog prefix so server logs remain identifiable.

The prefix cannot contain line breaks, cannot contain `{` or `}` braces, and cannot exceed 32 visible characters (or 255 characters with formatting codes). Changing the prefix is audited; during server startup while auditing is starting up, changes are refused until startup completes.

### Checking and managing spaces

- `/dt admin list` or `/dt admin list [page]` — lists all registered spaces, their status (`active`, `archived`, or `inconsistent`), resident count, channels (`text`, `voice`, or `deleted` if missing on Discord), and last activity.
- `/dt admin info <town>` — shows detailed information for a town's space, including its Discord IDs and any detected inconsistencies.
- `/dt admin sync` — checks and reconciles all town spaces. You can also target a single town with `/dt admin sync <town>`.
- `/dt admin unlink <player>` — removes a player's link and revokes their plugin roles on Discord.

### Archiving and purging

When a town disappears, its space is archived. If a channel, category, or role was already deleted by hand in Discord, the plugin treats that work as already done and completes the archive.

To permanently delete archived spaces, an administrator can use `/dt admin purge`. The command requires confirmation by running it a second time within 30 seconds.

If inconsistent spaces exist, `/dt admin purge` skips them and reports how many it skipped:
`Skipped {count} inconsistent spaces. Use /dt admin list or /dt admin info <town> to review them.`
It never purges inconsistent spaces automatically. An administrator should review them first.

### Updates

The plugin periodically checks published releases on GitHub against its own version. When a new release is available, administrators are notified:

- In the server console on startup.
- In Minecraft when an administrator joins the server.
- In the Discord log channel (once per release).

You can check and download updates with:

- `/dt admin update status` — shows the current version and whether an update is available or downloaded and awaiting a restart. If a check failed, it explains why.
- `/dt admin update` — checks for updates immediately and downloads the new release.

Every download is verified against the SHA-256 checksum published alongside the release. If the checksum does not match, the file is discarded and nothing is staged.

Once verified, the update jar is placed into the server's `update/` folder. The plugin is never replaced while the server is running. Simply restart the Minecraft server to apply the update.

If a release brings breaking changes (a new major version or marked `[breaking]`), automatic download is suppressed. The plugin warns that confirmation is required. An administrator can repeat `/dt admin update` or run `/dt admin update confirm` within 30 seconds to proceed with the download.

### Configuration reloads

- `/dt admin reload` — reloads configuration and message files. Language changes take effect immediately. If settings that require a server restart were changed (such as the bot token, guild ID, or database connection), the command reminds you that a restart is required.

### One bot across several Discord servers

One bot token can serve multiple Discord servers. Each Minecraft server is configured with its own `guild-id` in `config.yml` and only interacts with that Discord server. This keeps town channels, roles, and slash commands completely separated between servers.

## 8. Troubleshooting

### I can't see my town's channel

Run `/dt status`. Did you link the Discord account you are currently using? If not, follow the first step of this guide. Also verify that you are still a resident and that your town has an active space. If everything checks out, ask the mayor to use `/dt sync`.

### I can't create the space

Are you the mayor in Towny? Are you linked? Does the town have at least 2 residents under the default configuration? Check the command message: capacity may also be full, the cooldown between creations may still be active, a space may already exist, or the bot may lack connection or permissions.

### The bot does not assign roles

Ask the server owner to open **Server Settings → Roles** (or **Ajustes del servidor → Roles** in Spanish). The bot's role must be **above all town roles and `Alcalde`**. Having "Manage Roles" («Gestionar roles») is not enough if it is placed below them. The plugin detects the issue on startup and warns in the console.

### The code does not work

It may have expired, already been used, or been replaced by another. Generate a new one with `/dt link` and use it on Discord. If the account is already linked, follow the message to unlink it first. Failed attempts are rate-limited.

### Discord is unavailable

The Minecraft server can keep running. Plugin commands indicate that Discord is unavailable. The administrator must check the bot connection and console errors.

### The command does not work in Discord direct messages

DiscordTowny slash commands only work inside a Discord server channel. If you use a command in a direct message with the bot, it will decline and tell you that the command only works inside a server.

### The prefix change was rejected

Check the message you received. The prefix cannot contain line breaks, cannot contain `{` or `}` braces, and cannot exceed 32 visible characters (or 255 characters with formatting codes). Also, changes cannot be made during the first moments of server startup while auditing is starting up.

### `/dt admin purge` says spaces were skipped

The command only purges archived spaces. Any space marked inconsistent (for example, if a channel was missing before archiving or there was a database mismatch) is skipped for safety. Run `/dt admin list` or `/dt admin info <town>` to inspect what needs attention.

### An update is downloaded but not applied

The plugin stages updates in the server's `update/` folder. Paper applies the new jar when the server restarts. Restart the Minecraft server to complete the update.
