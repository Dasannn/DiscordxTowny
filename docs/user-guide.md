# DiscordTowny User Guide

Imagine you play as `AnaCraft` and belong to `Robledal`. The server owner has already configured the bot following the [README](../README.md).

## 1. Link your account

The bot needs to know which Discord account is yours. Having the same name in Minecraft and Discord is no substitute for linking. Without it, the bot cannot give you the role or access to your town's channels.

1. In Minecraft, run `/dt link`.
2. You will receive a six-character code. For example, `A7K9MX`.
3. In the Discord server, select `/link` and enter your code. The example would be `/link A7K9MX`; use the code you received.
4. The bot confirms the link with a response only you can see. If your town already has a space, you receive your corresponding role and can see its channels.

The code lasts 10 minutes by default and only works once. If you run `/dt link` again, the previous code is no longer valid. Linking is preserved across Minecraft restarts.

Each Minecraft account links to a single Discord account, and vice versa. To change it, use `/dt unlink` in Minecraft or `/unlink` on Discord and link again. Unlinking removes all roles granted to you by the plugin.

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

If the mayor needs to force a check of their town, they can use `/dt sync`.

## 5. If the town is renamed or disappears

If `Robledal` changes its name, its channels and its role are renamed. A name change does not archive the space.

If the town falls into ruins or disappears, its space is archived. The mayor can also archive the space with `/dt delete`, with confirmation.

Upon archiving:
- Channels are moved to `Archivo` by default and become read-only.
- They remain visible **only to administrators**.
- The town role is deleted (roles are limited on Discord), so former residents no longer see the channels.
- **Channels are not deleted automatically.** Message history is preserved intact.

If the town revives or is recreated with the same name while the space remains archived, the space is restored with its history and residents regain access to their channels.

An administrator can permanently delete archived spaces with `/dt admin purge`, with confirmation. Restoration with history is only possible while the space remains archived, not after purging it.

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

By default, information responses are public, except `/mytown` and `/help`, which only you can see. The administrator can change this or disable commands. The cooldown between commands is 5 seconds per user by default.

## 7. Troubleshooting

### I can't see my town's channel

Run `/dt status`. Did you link the Discord account you are currently using? If not, follow the first step of this guide. Also verify that you are still a resident and that your town has an active space. If everything checks out, ask the mayor to use `/dt sync`.

### I can't create the space

Are you the mayor in Towny? Are you linked? Does the town have at least 2 residents under the default configuration? Check the command message: capacity may also be full, the cooldown between creations may still be active, a space may already exist, or the bot may lack connection or permissions.

### The bot does not assign roles

Ask the server owner to open **Server Settings → Roles** (or **Ajustes del servidor → Roles** in Spanish). The bot's role must be **above all town roles and `Alcalde`**. Having "Manage Roles" («Gestionar roles») is not enough if it is placed below them. The plugin detects the issue on startup and warns in the console.

### The code does not work

It may have expired, already been used, or been replaced by another. Generate a new one with `/dt link` and use it on Discord. If the account is already linked, follow the message to unlink it first. Failed attempts are limited.

### Discord is unavailable

The Minecraft server can keep running. Plugin commands indicate that Discord is unavailable. The administrator must check the bot connection and console errors.
