# T28 — first review

Scope: `main..9db5593`. Verdict: **blocked**.

## Findings

**F1 — Production audit delivery depends on reflection and can silently disappear.** `src/main/java/com/discordtowny/minecraft/MinecraftCommands.java:1221`, `:1777-1820`. Registration passes `null` as `auditConsumer`. `resolveAudit` then asks the plugin's wiring for a `log(AuditEvent)` method on `CompositeAuditSink`, which does not exist, and falls back to reflecting over `DefaultSpaceService` fields. That happens to find its `auditSink` today, but any service wrapper or field change returns a no-op consumer, so successful prefix changes have no audit row. Pass the existing wiring audit sink explicitly through registration and use its `accept` method. Do not discover it through Bukkit or another service's private fields.

**F2 — The live message wrapper is mutated through private-field reflection.** `src/main/java/com/discordtowny/config/Messages.java:75-109`; `src/main/java/com/discordtowny/config/YamlConfigLoader.java:849`, `:911-940`. On a server, `DiscordTownyWiring.messages` is `ReloadableMessages`, not the `YamlMessages` used in the command tests. Startup and the command call the wrapper's default `setCustomPrefix`/`resetPrefix`, which scan its private fields to find the current delegate. This presently reaches the delegate, so the stored prefix does reach players, but the route is implicit and can fail silently if the wrapper changes. Implement explicit delegation on `ReloadableMessages` and verify the production loader/wiring path in a test.

**F3 — A failed database write leaves an uncommitted prefix in use.** `src/main/java/com/discordtowny/minecraft/MinecraftCommands.java:1649-1659`, `:1722-1735`. Set and reset change the shared in-memory messages before the asynchronous `put` or `delete`. On storage failure, the administrator is told the database is unavailable, but players continue seeing the failed change until reload or restart. Keep the old effective value on failure, or apply the new value only after persistence succeeds. The existing store-failure test checks the error reply but not subsequent player messages.

**F4 — Formatting in a prefix can hide the message body.** `src/main/java/com/discordtowny/config/YamlMessages.java:142-144`; `src/main/java/com/discordtowny/minecraft/MinecraftCommands.java:1703-1719`. The renderer deserializes `effectivePrefix() + text(key)` as one string. A permitted prefix ending in `&k` leaves obfuscation active for the reply, so players cannot read it. Deserialize the prefix separately from the catalog message, or otherwise close prefix formatting before the message body. Add a check for a prefix with active formatting at its end. The present brace, newline, and length checks do not address this.

**F5 — The 64-character check does not enforce its stated screen-width purpose.** `src/main/java/com/discordtowny/minecraft/MinecraftCommands.java:1717`; `docs/informes/T28-admin-prefix.md` §4. It counts Java UTF-16 units, not rendered width. A 64-character prefix of wide glyphs can still push the reply off screen, while colour codes consume the limit without taking width. If the requirement is to preserve room for real text, validate rendered width or use a conservative visible-text limit with a documented ceiling. The database column size only justifies an upper storage bound.

**F6 — Changes made by another process remain invisible indefinitely.** `src/main/java/com/discordtowny/config/YamlMessages.java:57-78`; `src/main/java/com/discordtowny/DiscordTownyWiring.java:224-229`, `:533-538`. Production reads the setting at startup and reload; no production call invalidates the cached value afterward. Two instances sharing the settings table can show different prefixes forever. Refresh on a bounded asynchronous interval or define an explicit synchronization point, while keeping database reads off the main thread. The constitution excludes multi-server networks, so this needs a scope decision if shared-database instances are unsupported.

## Checks that passed

- The rejected static mutable supplier and Discord-gateway settings lookup are gone. `DiscordTownyPlugin.register` supplies `wiring.getSettingsRepository()` directly; startup and reload read the same repository and apply its value to the live messages wrapper. There are two `createCommandNode` overloads.
- There is no database read per message. Production reads on startup and reload; `YamlMessages` caches its value after construction.
- `plain()` uses `catalogPrefix()`, so console and Discord text retain the catalog prefix. SQL `put` stores `""` as a present value; `delete` makes it absent. The command treats `""` and `''` as empty, and reset deletes the row.
- Braces, `\n`, `\r`, and raw length over 64 are refused before storage and audit. Accepted set/reset events name `sender.getName()` and target the new value or catalog default, subject to F1.

Review was static only. The architect reported the suite green on this tip; I did not run Gradle.
