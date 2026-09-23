# T28 — third review

Scope: `300c41e..51da859`. Verdict: **blocked**.

## Finding

**F9 — A completed prefix change can still have no audit event.** In `MinecraftCommands.java:1582-1610` and `:1683-1713`, the command checks the audit supplier before writing the setting, then resolves it a third time after the write. At that point `activeAudit == null` simply skips `accept` and continues to apply the prefix and send the success reply. A supplier that changes from non-null to null between these reads makes this deterministic. The production supplier reads `DiscordTownyWiring.auditSink`, which is replaced during reload and cleared during shutdown (`DiscordTownyWiring.java:563-577,671-681`). Thus the pre-write guard does not establish that the post-write audit dispatch occurs. Keep the checked sink for the operation or treat loss of the sink after persistence as an incomplete change; a successful privileged mutation must not silently omit its audit event. Add one check that changes the supplied sink after the pre-write read.

## Named follow-ups

- **Operator reply after an audit failure.** `admin.prefix-saved-incomplete` in both catalogs says the value could not be applied live, but does not say that audit dispatch failed. It also does not repeat the stored raw prefix (or explicitly name the catalog default for reset), so the operator cannot confirm from the reply which value a restart will load. The set command input and the reset command provide context, but the requested post-write explanation is incomplete. Include the saved target and failed stage in a later message pass.
- **Persistent audit unavailability.** The refusal accurately prevents a write while the sink is absent. If service initialization never reaches sink construction, the command keeps refusing, appropriately preserving the audit requirement. The catalog's “still starting up; try again in a moment” then becomes misleading; use an availability message that also fits a stalled startup.

## Branch checks

| Path | Stored value | Live value | Operator reply |
|---|---|---|---|
| Audit absent at entry or immediately before write | Unchanged | Unchanged | Starting-up refusal; no audit event |
| `settings.put` / `delete` fails | Unchanged | Unchanged | Database unavailable; no audit event |
| Audit consumer throws after successful write | New prefix / deleted key | Old prefix | Saved but not applied live; restart promised |
| In-memory apply throws after successful write | New prefix / deleted key | Usually old; a mutator could have changed it before throwing | Same incomplete reply; audit already dispatched |
| All stages succeed | New prefix / deleted key | New prefix / catalog default | Success reply |
| Audit supplier becomes null only after write | New prefix / deleted key | New prefix / catalog default | **Success reply with no audit event (F9)** |

The production audit sink's `accept` queues work and does not itself prove that a database audit row was committed. This review keeps the F9 claim to the command's demonstrable null-supplier path.

## Collapse and boundaries

There is one `MinecraftCommands.createCommandNode` and one `MinecraftCommands.register` signature. Production passes `wiring::getAuditSink` through an explicit supplier. Tests that formerly passed a `Consumer<AuditEvent>` now return that same consumer from a supplier; the other former direct-instance tests return their same service objects. Their deliberate `null` settings/audit suppliers still mean unavailable dependencies. The `MinecraftCommands` path contains no reflection or static lookup of plugin wiring, and the diff introduces no sideways access.

Static review only. I did not run Git mutations or Gradle; the architect reports the suite green at `51da859`.
