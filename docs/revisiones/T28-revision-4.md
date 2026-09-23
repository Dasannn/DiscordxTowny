# T28 — fourth review

Scope: `51da859..7a05df9`. Verdict: **integrate with named follow-ups**.

## Findings

No blocking finding in this scope. The command resolves the audit supplier once before either write and calls that same consumer after the write. The new set and reset tests make a supplier return `null` after its first read and verify delivery to the captured consumer.

## Named follow-ups

- **F10 — A captured production sink can stop accepting audit work before dispatch.** `MinecraftCommands.java:1574-1603,1670-1698` holds the consumer across the asynchronous settings write. During reload, `DiscordTownyWiring.java:563-577` drains the old sink and replaces it; shutdown drains it before closing storage (`:414-442,671-697`). `CompositeAuditSink.accept` calls `record`, which catches executor rejection and returns normally. If the command captured the old sink, then reload drains it before the command calls `accept`, the setting can be saved and the command can send success although no audit row was queued. The null-after-first-read tests use a mock sink and do not exercise this lifecycle race. Coordinate command dispatch with sink retirement, or make rejection observable before reporting success. This is a narrow concurrent reload/shutdown case, so it need not hold this integration.
- **F11 — The incomplete reply does not identify every stored target unambiguously.** `MinecraftCommands.java:1588,1611-1615,1710-1715` and both catalogs' `admin.prefix-saved-incomplete` put the target in parentheses. A nonempty set value is inserted as raw text, including its `&` codes and spaces, which is accurate. An empty set value renders as `()`, with no explicit indication that the stored value is the legitimate empty prefix. Reset deletes the setting key, so the effective value is `messages.catalogPrefix()`; the reply says only “catalog default” / “el valor por defecto del catálogo” and does not give that actual raw value. Show an explicit empty marker for set and the catalog prefix's raw value for reset in this failure reply. The existing tests check nonempty sets and the generic reset label, not either case.

## Branch table

| Path | Stored value | Live value | Audit | Operator reply |
|---|---|---|---|---|
| Sink absent at entry | Unchanged | Unchanged | None | Auditing unavailable; change not made |
| `put` / `delete` fails | Unchanged | Unchanged | None | Database unavailable |
| Consumer throws after write | New prefix / deleted key | Old prefix | Dispatch failed | Saved target and audit stage; restart promised |
| In-memory apply throws after write | New prefix / deleted key | Usually old; mutator may change it before throwing | Dispatched | Saved target and live stage; restart promised |
| All stages succeed | New prefix / deleted key | New prefix / catalog default | Dispatched to captured sink | Success |
| Supplier returns `null` after pre-write read | New prefix / deleted key | New prefix / catalog default | **Dispatched to captured sink**; audited success in the new tests | Success |

“Dispatched” here means `accept` was called. The production sink queues audit persistence asynchronously; its return does not prove the row committed. F10 describes the additional case where the captured sink has already retired and may queue nothing.

## Message and test checks

The new auditing-unavailable refusal fits both normal startup and a stalled initialization: it describes current availability and does not promise startup will finish. Both language catalogs and the tests use the new text. The changed tests no longer assert the old startup or incomplete-reply wording; the architect reports the suite green at this tip. Static review only; I did not run Gradle.
