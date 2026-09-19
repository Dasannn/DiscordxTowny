# Privacy Policy

DiscordTowny is a Minecraft server plugin. It runs on the server operator's own
machine and connects their Minecraft server to their own Discord server. **There
is no central service**: the authors of the plugin receive no data from it, and
have no way to reach any installation.

The data controller is the operator of the Minecraft server you play on. This
document describes what the software stores so that operators can honour their
own obligations and players know what is kept.

## What the plugin stores

On the operator's database — SQLite in the plugin folder, or a MySQL/MariaDB
server they configure:

| Data | Why |
|---|---|
| Minecraft account UUID | Identifies the player across name changes. |
| Discord account ID | The other half of the link. |
| Last known Minecraft name | Shown in admin listings so a UUID is readable. |
| Date the link was created | Lets an administrator audit a link. |
| Pending link codes, with the UUID that requested them | Expire automatically. |
| Town identifiers, and the IDs of the channels and role created for them | Lets the plugin manage what it created without touching anything else. |
| A log of the actions taken, with the reason | Lets an operator see why a role or channel changed. |

The plugin does **not** store message content, does not read conversations, and
does not bridge chat between Minecraft and Discord.

## What is sent to Discord

The plugin asks Discord to create channels and roles for a town, and to grant or
remove those roles. To do that it sends the Discord account IDs involved. Discord
processes that data under its own privacy policy.

If the operator enables the log channel, the plugin publishes a summary of what it
did — for example that a player linked their account, or that a town's space was
archived. That is visible to whoever can read that channel.

## Update checks

The plugin can check GitHub for new releases. That is an ordinary HTTPS request to
GitHub's public API, from the operator's server. It carries no player data. It can
be disabled in the configuration.

## How long it is kept

Until it is deleted. A player who unlinks their account removes their link. An
administrator can remove a link on someone's behalf. Link codes expire on their
own. Deleting the plugin's database removes everything.

## Rights

Because the data lives on the operator's server, requests to access or delete it
go to that operator, not to the authors of this plugin.

## Changes

This document lives in the repository and changes with it. Its history is public:
<https://github.com/Dasannn/DiscordxTowny/commits/main/PRIVACY.md>
