# DiscordTowny
DiscordTowny connects Towny Advanced with Discord from a Paper server.
Every town needs private channels: creating them and maintaining their permissions by hand does not scale. The mayor requests their space and the bot maintains access according to Towny.

## Requirements

- Paper for Minecraft **26.2**.
- **Java 25**.
- **Towny Advanced 0.103.2.0 or higher**. The plugin is compiled against that version on purpose: building against the oldest supported release turns compatibility into a compile error here rather than a crash on your server.
- A Discord server and your own bot, which you will create in the next step.
- SQLite, included as the default option, or a MySQL/MariaDB database with its credentials.

Each installation connects one Minecraft server to a single Discord server.

## Creating and inviting the bot

1. Open the [Discord developer portal](https://discord.com/developers/applications). Click **New Application**, type `DiscordTowny`, and create the application.
2. In the **Bot** tab, generate the token with **Reset Token** and save it (you will use it in `discord.token`; do not share it). In that same tab, scroll down to the **Privileged Gateway Intents** section and check the **Server Members Intent** box. If not enabled, Discord rejects the connection and the bot will not work.
3. Under **Installation**, enable **Guild Install**. Select **Discord Provided Link** as the install link.
4. In the **Guild Install** settings, select `bot` and `applications.commands`. Check **Manage Channels**, **Manage Roles**, **View Channels**, **Send Messages**, and **Connect**.
5. Open the install link, choose **Add to Server**, select your server, and authorize the bot. You need permission to manage that server.
6. In Discord, open **Server Settings → Roles**. **Place the bot's role above all roles it will manage: town roles and `Alcalde`.** If it is placed below them, it cannot assign them. The plugin warns in console on startup and does not operate with this incorrect hierarchy.

The portal steps can be consulted in the [official Discord guide](https://docs.discord.com/developers/quick-start/getting-started).

## Installation

1. Prepare Paper and Java with the specified versions. Install Towny Advanced and verify that it works.
2. Download the DiscordTowny JAR file from the official releases.
3. Stop Minecraft and place the JAR into the server's `plugins` folder.
4. Start the server once so that Paper automatically generates the `plugins/DiscordTowny/` folder and the default `config.yml` file.
5. Open `plugins/DiscordTowny/config.yml` and fill in the token, the Discord server ID, and the database as explained below.
6. Restart the server and check the console. Fix any warnings regarding the bot's connection or permissions.
7. Join the game and run `/dt help`. Follow the [user guide](docs/guia-de-uso.md) to link your account and create the first space.

## Minimum configuration

Edit these values in `plugins/DiscordTowny/config.yml`; keep the other blocks from the example file.

| Key | What to put |
|---|---|
| `discord.token` | The token you copied from the portal. Replace `PON_AQUI_TU_TOKEN`. |
| `discord.guild-id` | Your Discord server ID, in quotes. For example, `"123456789012345678"`; use your own. |
| `database.type` | `sqlite` to start without a separate database server. This is the default value. |

To copy the ID, enable **User Settings → Advanced → Developer Mode** in Discord. Right-click the server icon and select **Copy Server ID**.

With `sqlite`, the other values in the `database` block are ignored. To use MySQL or MariaDB, select `mysql` or `mariadb` and fill in `host`, `port`, `name`, `user`, and `password` with the details of an available database. The default port is `3306` and the default name is `discordtowny`.

Do not share the configuration file: it contains the token and, if you use them, database credentials.

Other initial values affecting players:

| Option | Default value |
|---|---|
| Channels per town | Text and voice, inside `Comunidades` |
| Category for archived spaces | `Archivo` |
| Distinctive role shared by mayors | `Alcalde` |
| `limits.min-residents` | 2 residents |
| `limits.max-towns` | 200 towns |
| `limits.creation-cooldown-seconds` | 60 seconds |
| `linking.code-expiry-minutes` | 10 minutes |
| `commands.cooldown-seconds` | 5 seconds per user on Discord |
| `language` | `en`. Set it to `es` for Spanish |

Texts live in `messages_en.yml` and `messages_es.yml` next to `config.yml`, and
`language` chooses between them. Both files are yours to edit; the plugin never
overwrites them. The console stays in English whichever you pick, so a log is
the same for everyone reporting a problem.

## In-game commands

You can substitute `/dt` with `/discordtowny`. Examples use the town `Robledal` and player `AnaCraft`.
"Admin" means holding the `discordtowny.admin` permission.

| Command or example | Who can use it | What it does |
|---|---|---|
| `/dt help` | Any player | Shows the commands they can use and their description. |
| `/dt link` | Any player | Generates a code to link their account. |
| `/dt unlink` | Linked player | Unlinks their account and removes roles granted by the plugin. |
| `/dt status` | Any player | Shows link status, Discord account, and whether their town has a space. |
| `/dt create` | Linked mayor | Creates their town's space if requirements are met. |
| `/dt delete` | Mayor | Archives their town's space, with confirmation. Preserves history. |
| `/dt sync` | Mayor | Synchronizes roles for residents of their town. |
| `/dt admin sync` | Admin | Checks and synchronizes all spaces. |
| `/dt admin sync Robledal` | Admin | Checks and synchronizes a specific town. |
| `/dt admin unlink AnaCraft` | Admin | Unlinks that player. |
| `/dt admin reload` | Admin | Reloads configuration and messages. |
| `/dt admin list` | Admin | Lists towns, states, channels, residents with roles, and last activity. |
| `/dt admin info Robledal` | Admin | Shows space details, activity, and detected issues. |
| `/dt admin purge` | Admin | Permanently deletes archived spaces, with confirmation. |
| `/dt admin update` | Admin | Checks for and downloads the latest version. |
| `/dt admin update status` | Admin | Shows current version, available version, and whether a download is pending. |

Updates are checked every 12 hours and downloaded automatically by default. They are applied when restarting the server. You can disable the check with `updates.check-enabled` or automatic download with `updates.auto-download`.

## Discord commands

Type `/` and select the bot's command. In the examples, enter the value indicated in the field shown by Discord; `A7K9MX` is just an example code.

| Command or example | Who can use it | What it shows or does |
|---|---|---|
| `/link A7K9MX` | Player with a valid in-game code | Links their Discord account with Minecraft. |
| `/unlink` | Linked user | Unlinks their account and removes plugin roles. |
| `/town Robledal` | Any user, without linking | Mayor, residents, founded date, plots, bank, nation, and ruined status. |
| `/town` | Linked user | Info card for their own town. |
| `/residents Robledal` | Any user, without linking | Paginated list of residents and their status. |
| `/res AnaCraft` | Any user, without linking | Town, rank, online status, and balance if economy is present. |
| `/res` | Linked user | Their own resident info card. |
| `/townlist` or `/townlist 2` | Any user, without linking | Sorted list of towns, by pages. |
| `/mytown` | Linked user | Info card for their own town. |
| `/help` | Any user, without linking | Available Discord commands and their description. |

Queries read current Towny data. The six information commands can be disabled in `commands`. Their responses are public by default, except for `/mytown` and `/help`, which are only visible to the person running them. Each has its own `ephemeral` setting. The linking confirmation is also private.

## Discord limits

Discord allows **500 channels and 250 roles per server**. With two channels and one role per town, the estimated ceiling is around **240 towns**, before accounting for other channels, categories, roles, and archived spaces. This is not a guaranteed capacity. The plugin's own limit starts at 200.

Additionally, Discord limits each category to **50 channels**. When a category fills up, the bot automatically creates additional numbered categories (`Comunidades 2`, `Comunidades 3`...) as needed to house new spaces. This is completely transparent: the real limit becomes that of the Discord server itself (500 channels). See the [official Discord limits](https://support.discord.com/hc/en-us/articles/33694251638295-Discord-Account-Caps-Server-Caps-and-More).

## License

[MIT](LICENSE).
