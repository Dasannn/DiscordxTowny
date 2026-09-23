# Security Policy

## Reporting a vulnerability

**Do not open a public issue for a security problem.**

This plugin controls who can see private Discord channels and stores the link
between a Minecraft account and a Discord account. A flaw that grants a role, a
channel, or someone else's identity is worth reporting privately first, so server
owners can update before the details are public.

Report it through GitHub's private advisory form:
<https://github.com/Dasannn/DiscordxTowny/security/advisories/new>

Please include what you did, what happened, and what you expected instead. A
proof of concept helps but is not required — a clear description of the flaw is
enough to start.

## What to expect

You will get an acknowledgement, and a fix or an explanation of why the behaviour
is intended. Once a fix is released, the advisory is published and you are
credited unless you ask otherwise.

## Supported versions

The latest release is the one that receives fixes. Older versions are not
patched; the plugin's own updater exists so a server can stay current.

## Outside this policy

The security of your Discord bot token is yours: it lives in
`plugins/DiscordTowny/config.yml` on your server and is never sent anywhere by
this plugin. If you believe your token has leaked, rotate it in the Discord
developer portal — that invalidates the old one immediately.
