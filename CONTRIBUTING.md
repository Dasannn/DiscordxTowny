# Contributing

## Before you write code

Read `docs/constitution.md`, `docs/spec.md`, `ARCHITECTURE.md`, `docs/plan.md`
and `docs/tasks.md`. They govern every decision. A change that contradicts them
needs the documents changed first, and that needs approval.

Code, comments and documentation are written in English. Player-facing text is
never hardcoded: it belongs in `messages_en.yml` and `messages_es.yml`, and both
catalogues must carry the same keys.

## Rules that are not style preferences

- **Towny runs on the main thread. The database and Discord never do.** Crossing
  those boundaries is the one mistake this codebase cannot tolerate.
- **A Discord interaction is acknowledged within three seconds** or it fails
  permanently for the user.
- **A failed read is not proof of absence.** Never delete, archive or mark
  anything as gone because a lookup failed.
- Console output and logs are English even when the player's language is not.

## Building

```
./gradlew build
```

The build runs the tests and verifies the shaded jar can still resolve its JDBC
driver, which relocation has broken before in a way no unit test could see.

## Releasing

Tag `v<version>` on `main`. The pipeline builds, publishes the jar and publishes
its SHA-256. Without that checksum the updater refuses the release, so never
attach a jar to a release by hand.
