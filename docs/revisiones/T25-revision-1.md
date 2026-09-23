# T25 — first review

Scope: `9f11371..bebdee1` (`fix/update-join-notice`). The T25 card is absent from this worktree's `docs/tasks.md` (which ends at T24), so the requested join contract and `docs/spec.md` §10.1 supplied the acceptance criteria.

## Findings

- **F1 — important — `src/main/java/com/discordtowny/DiscordTownyPlugin.java:64-68`:** The only registration is inside the successful post-start branch. If startup degrades because storage fails, `DiscordTownyWiring.reload()` can recover storage and create an update service (`DiscordTownyWiring.java:491-613`), but it never dispatches post-start again. Every later admin join then misses the notice. Register the update listener when recovery makes it eligible, without duplicate registration, and exercise that lifecycle path in a test.
- **F2 — blocking — `src/main/java/com/discordtowny/minecraft/UpdateJoinListener.java:73` (existing callee `src/main/java/com/discordtowny/update/DefaultUpdateService.java:729` and `:489-491`):** `PlayerJoinEvent` runs on the server thread. `notifyAdminOnJoin()` calls `isUpdatePending()`, which performs synchronous `Files.isRegularFile` and `Files.size` on the update jar. Filesystem metadata can stall the join thread, violating the listener and updater threading contract. Make the join notice consume a cached pending state, or otherwise keep file I/O off the server thread. This defect is in the pre-existing service, exposed by its first production join caller.
- **F3 — important — `src/main/java/com/discordtowny/minecraft/UpdateJoinListener.java:74-76`:** Catching `Throwable` swallows fatal JVM errors as well as ordinary notification failures, and `Level.FINE` makes a failed admin notice invisible at normal log levels. Catch recoverable failures and report them at a visible level with context. The required silent paths for null service and degraded mode remain silent because they return before this catch.

## Verified by inspection

- On a normal successful startup, the `MONITOR` listener checks `discordtowny.admin`, degraded state, service presence, and the config switch before passing `player::sendMessage` to the service. The service renders an available, downloaded, or failed-check notice from its stored result. Non-admin, disabled switch, null service, and degraded listener paths send nothing and log no warning.
- The join path does not call `checkForUpdate()` or an HTTP supplier. The service's periodic check is scheduled separately. The interface methods are appropriately abstract: a default no-op would allow the original silent failure. Editing `UpdateService.java` was necessary for the listener to call those methods through its interface, though the author should have reported the zone crossing before making it.
- Listener tests cover delivery and the silent paths; the real-service test checks cached delivery with no extra transport call. They do not test registration after degraded recovery, so they cannot detect F1. The mock delivery test supplies its own message, but the real-service test avoids relying on that mock for formatting.
- Identity, Towny reads, database and Discord calls, secrets, and message catalog changes are not introduced by this diff.

I did not run Gradle or a live Paper server, as instructed. The architect reports the suite green on this tip; that does not verify the lifecycle or server-thread behavior above.

**Verdict: blocked.**
