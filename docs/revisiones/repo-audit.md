# Public repository audit

Scope: the 172 paths returned by `git ls-files` on `main`. This is a publication audit, not a build or runtime verification. Each tracked path is covered by exactly one of Keep, Remove, or Decide. No tracked file was changed.

## Keep

- `src/main/**` (82 files) — The plugin implementation, Paper metadata, default configuration, and both message catalogs are what operators run and contributors change; the bundled configuration contains placeholders, not live credentials.
- `src/test/**` (40 files) — Tests document expected behavior and give contributors a way to check changes.
- `build.gradle.kts`, `settings.gradle.kts`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` — Together they make the project reproducibly buildable; the wrapper JAR is a normal build tool artifact, not an accidental release binary.
- `.github/workflows/build.yml`, `.github/workflows/release.yml` — Show how builds and checksum-bearing releases are produced.
- `.github/ISSUE_TEMPLATE/bug_report.md`, `.github/ISSUE_TEMPLATE/feature_request.md`, `.github/ISSUE_TEMPLATE/config.yml` — Give users and contributors useful issue routes and prompt for reproduction details.
- `.gitignore` — Keeps local secrets, generated builds, and server state out of future commits. Its `docs/revisiones/` rule conflicts with the stated policy of publishing future reviews; tracked reviews stay tracked despite this rule.
- `README.md`, `docs/user-guide.md` — Installation and operation guidance. The README points to nonexistent `docs/guia-de-uso.md` instead of the tracked English guide, and its command list omits later admin commands; repair these navigation gaps.
- `CHANGELOG.md` — Helps operators judge upgrades and understand released behavior.
- `LICENSE` — Tells users and contributors their redistribution and modification rights.
- `PRIVACY.md`, `TERMS.md` — Explain the operator-hosted data model and terms to server owners and players; they are especially relevant to a Discord bot that links accounts.
- `CONTRIBUTING.md`, `ARCHITECTURE.md` — Give contributors the build, review, threading, and module rules they need to make safe changes.
- `AGENTS.md`, `CLAUDE.md`, `docs/review-checklist.md` — Give agent-assisted contributors the repository's working rules and the review defects that have recurred. `CLAUDE.md` is only a pointer, but that single line usefully directs another agent to the same rules.
- `docs/constitution.md`, `docs/spec.md`, `docs/tasks.md` — Explain the product constraints, expected behavior, and why individual fixes exist. The task cards are more useful to a contributor than commit subjects alone; T24–T28 also record accepted follow-ups. Correct the five governing documents' still-visible “draft v1 — pending approval” status before asking contributors to treat them as authoritative.
- `docs/revisiones/T24-revision-{8,9,10,11}.md`, `T25-revision-{1,2,3,4}.md`, `T26-revision-1.md`, `T27-revision-{1,2,3}.md`, `T28-revision-{1,2,3,4}.md` (all under `docs/revisiones/`) — Independent reviews show which defects blocked integration, which were closed, and which risks were accepted. A prospective operator can see the limits; a contributor can pick up the named follow-ups. Label superseded rounds clearly as history, since a reader landing on an early “blocked” verdict could otherwise mistake it for the current state.

The owner's earlier reasoning holds for task cards and reviews: the public record is honest and technically useful. Its cost is reader effort and a duty to distinguish historical findings from current guarantees. It does not mean every agent-produced document earns permanent publication.

## Remove

- `docs/informes/T23-fusion-catalogos.md`, `T24-updater-host.md`, `T25-join-notice.md`, `T26-guild-scoped.md`, `T27-archive-loop.md`, `T28-admin-prefix.md`, `T29-user-guide.md` (all under `docs/informes/`) — These seven implementation narratives largely restate the task cards, diffs, tests, and reviews. Several link to local worktrees that a public reader cannot open; some describe intermediate implementations later rejected by review. T29 claims a configuration key that the bundled `config.yml` does not contain, illustrating the cost of treating the reports as current instructions. A user gains little from them, and a contributor must check every claim against the final code. **What is lost:** detailed round-by-round implementation reasoning, test notes, and the author's account of decisions that the shorter task cards and reviews may not fully capture. If that archival value matters to the owner, retain them only after replacing local links and marking the text as historical; the recommendation here is to publish the more reliable task and independent review record instead.

## Decide

- `docs/plan.md` — **Question for the owner:** will this staged, exclusive-zone agent workflow govern future contributions, or is it a record of the completed build-out? If it is still the workflow, keep it and update its draft status and references; it helps contributors coordinate. If it is historical only, remove it from the active documentation set and stop telling contributors it governs every change. Removing it loses the original sequencing and division-of-work rationale, but it prevents a future contributor from following obsolete phases. This is the only tracked path whose verdict depends on that answer.

## Should not be public

- **Machine-specific path and user profile name:** `docs/informes/T25-join-notice.md`, `T26-guild-scoped.md`, `T27-archive-loop.md`, and `T28-admin-prefix.md` contain `file:///` links into the owner's Windows worktrees. They disclose the local profile and directory layout and are dead links for readers. Remove the reports as recommended, or replace those links with repository-relative paths before keeping them.
- **Live server identifiers:** `docs/tasks.md` around lines 970–972 and `docs/informes/T27-archive-loop.md` contain a copied production incident line with a town name, Discord channel ID, and Towny UUID. The technical failure can be explained with placeholders; those identifiers do not help a reader reproduce it. Redact them in the task card even if the report leaves. Removing a current file does not erase its Git history, so assess the history separately if those identifiers are sensitive to the server owner.
- I found no committed bot token, database password, webhook URL, private key, or internal hostname in the tracked source/configuration. The `config.yml` token is an explicit placeholder; `localhost` is a sample database default, not an internal host disclosure.

## Missing

- A `SECURITY.md` or equally clear private vulnerability-reporting route. This plugin controls private Discord channel access and stores account links; a reporter needs somewhere to send a role or identity bypass without publishing exploit details in a normal issue.
- Working discovery links in the README: a direct official Releases link and a link to the existing `docs/user-guide.md`. The guide itself is present; the current README target is not.
- A consistent current-status marker for the governing docs and an index of the accepted review follow-ups. The source material exists, but five documents still say approval is pending while `AGENTS.md` says the planning phase is approved and T0–T28 are integrated. A contributor cannot tell which statement is authoritative without tracing the history.
- A way for future reviews to be published under the stated workflow: `.gitignore` currently ignores `docs/revisiones/`, although 16 older review files are tracked. A new review written there will be invisible to normal `git add` unless explicitly forced.

If the owner changes nothing, existing installations still have the code, config defaults, and build pipeline they need. The immediate costs fall on new operators and contributors: operators following the README hit a missing guide link; contributors may treat draft labels and old blocked reviews as current, spend time checking stale implementation reports, or miss a new review because it is ignored. The copied live identifiers and local profile path remain public, and a security reporter has no stated private route. Those are concrete discoverability, maintenance, and disclosure costs; the mere presence of an honest process history is not itself a problem.
