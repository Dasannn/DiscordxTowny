# T25 — fourth review

Scope: `f2b6dcd..4fe534b` (`fix/update-join-notice`). Review only; no production or test code changed. Gradle was not run; the architect reports the suite green at `4fe534b`.

## Findings

None. No F10 finding is warranted.

## Verified by inspection

- **F6 — closed:** `DiscordTownyWiring.reload()` reaches `dispatchPostStart(false)` on every successful healthy reload (`DiscordTownyWiring.java:639`). The production `postStartAction` is only `DiscordTownyPlugin::registerListeners` (`DiscordTownyPlugin.java:66`). That callback registers only `PlayerJoinSyncListener`, `TownySyncListener`, and `UpdateJoinListener`; each successful registration sets its own flag and is skipped on later reloads (`DiscordTownyPlugin.java:101-130`). A failed registration leaves its flag false for the next reload. `dispatchPostStart(false)` does not start services, schedule jobs, register commands, or increment a production counter. Reload does rebuild services, replace the periodic job and updater, and re-register slash commands (`DiscordTownyWiring.java:543-637`), but those operations already preceded the dispatch in the previous revision and are not repeated by this change. If recovery leaves `degraded` true, the dispatch is not reached (`DiscordTownyWiring.java:523`); the callback also guards `isDegraded()` and missing storage.
- **F7 — closed:** The reload test installs a thread-scoped `MockedStatic<Files>` before calling `wiring.reload()` on that same thread, and verifies no `Files.isRegularFile(stagedJar)` or `Files.size(stagedJar)` call there (`DiscordTownyPluginTest.java:631-644`). A constructor probe of that staged jar during reload would be seen and fail the test. The worker's probe is outside the thread-scoped mock; awaiting its seed future and asserting `true` confirms the staged jar was found. The production thread accessor and reference were removed.
- **F8 — closed for the production lifecycle:** A stopped service returns an immediate `false` seed future, and `stop()` settles any pending seed future (`DefaultUpdateService.java:405-426,513-532`). Reload stops the old updater and constructs a new one before calling `start()` (`DiscordTownyWiring.java:604-627`), so the stopped instance is never restarted by reload and the new instance gets a fresh seed future. Calling `start()` on the *same* stopped service is not a supported production path: its owned scheduler has been shut down, and `start()` does not reset `stopped`. This behavior predates this diff and does not leave reload answering false forever.
- **F9 — closed:** `isCachedUpdatePending()` is package-private (`DefaultUpdateService.java:558`) and has no production caller. The cross-package reload test uses the seed future result.

**Verdict: integrate.**
