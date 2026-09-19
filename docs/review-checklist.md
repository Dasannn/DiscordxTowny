# Review checklist

What every review of this project must check, written from the defects this
project has actually produced. Each item is here because it slipped through once.

A review that says "looks correct" without having worked through this list is not
a review. Equally, an item that genuinely does not apply to the branch takes one
line saying so — padding a report with irrelevant findings wastes the author's
turn as surely as missing a real one.

## 0. What the reviewer cannot do, and must not pretend to

The reviewing environment cannot run git, Gradle or the tests. The architect
compiles every branch and states the result. **Never present that as your own
execution**, and never conclude that something works because the build is green:
every blocking defect this project has shipped passed a green build.

State plainly at the end what you could not verify.

## 1. Threads

Three rules, and breaking any of them is a production crash or lost ticks:

- **Towny is read only on the server's main thread.** `LiveTownyFacade` throws
  otherwise. Follow every path that reaches `TownyFacade` and name the thread it
  lands on.
- **Database and Discord work never happens on the main thread.** Watch for a
  continuation attached to a future that completes on main: a non-async
  `thenAccept` inherits that thread. This shipped once in the Discord commands —
  the reply, the embed and the failure path were all built on the main thread.
- **A listener never blocks.** It reads, converts to plain values, delegates.

Check the failure path as carefully as the happy path: an error reply built on
the wrong thread is still work on the wrong thread.

## 2. Identity

**Everything is identified by UUID or by persisted ID, never by name.** Names
change, and a freed name gets taken by someone else.

This project has shipped three separate defects of this exact shape: a mayor role
adopted by name, a town role adopted by name — which handed every holder of a
same-named role access to a town's private channels — and a paginator that
carried a town's name between clicks, so a rename made it show a different town's
residents.

When no persisted ID exists yet, adopting the single match is acceptable only if
ambiguity fails visibly. Silently picking the first is not.

## 3. Success that did not do the work

The single most common defect here. Look for:

- a method that returns success without confirming the operation it dispatched;
- a branch that skips a step when a dependency is absent, then reports success;
- an optional dependency that, when null, turns the method into a no-op;
- a future that completes normally while carrying failures nobody reads.

## 4. A failed read is not an answer

`TownyFacade` returns empty only for a **confirmed absence** and throws
`TownyReadException` when the read failed. `DiscordGateway.existingResourceIds`
throws when Discord is unavailable rather than answering "none exist".

Check that the code tells absence from failure everywhere, and that a failure
never causes a destructive action. Reconciliation once archived a living town
because a momentary Towny failure looked exactly like a deletion.

Conversely, check that an empty answer is not treated as an outage, which would
make repairs never run.

## 5. Messages

- Every key the code uses must exist in **both** `messages_en.yml` and
  `messages_es.yml`, with the same placeholders. Thirty-nine keys once reached a
  branch resolving to `[missing message: ...]`, with the build green.
- **YAML 1.1 coerces unquoted `yes`, `no`, `on`, `off`, `true`, `false` and
  `null` into booleans and nulls.** A key called `no:` is not the string "no".
  This is invisible when reading the file.
- No player-visible text written in Java. Embed and button labels use the
  prefix-free accessor; a chat line uses the prefixed one.
- A reused key must **mean** what its block says. A disabled command answering
  with the "no permission" text sends the operator hunting for a permission that
  does not exist.

## 6. Tests

Assume a test is lying until its body proves otherwise. This project has caught
**nine** tests that supplied the behaviour they claimed to verify. Specifically:

- a test whose mock produces the very output the assertion checks;
- a test asserting what was **requested** instead of what **happened** — a
  revoke list submitted proves an intention, not a change;
- `contains(...)` where exactness matters: it passes when too much was done;
- an inline executor in a test about threading, which can never observe the
  thread;
- a stub that answers every key with the same value, making two different
  messages indistinguishable;
- a hand-written list of expected values that drifts from what the code does;
- matchers mixed with raw values in one Mockito call, which corrupts the
  verification rather than failing honestly.

For each new test, answer: **what change to the production code would make this
test fail?** If the answer is "none", say so.

## 7. Configuration and lifecycle

- `onDisable` must survive a start-up that never finished: every field can still
  be null, and one component throwing must not stop the rest closing.
- Reload must reach the components that hold configuration, or say honestly what
  it did not change.
- Nothing blocks the server thread during enable. An unreachable database once
  held the whole server on connection timeouts.
- Degraded mode is a decision taken quickly, not the result of a timeout.

## 8. Scope

The author works in an exclusive zone. Flag work outside it, but distinguish a
defect the branch **introduced** from one it merely **exposed** as the first real
consumer of an older package. Three times now, an integrated and reviewed package
turned out to be broken the moment something actually used it — that is a finding
about the older package, not misconduct by this author.

## Severity

- **blocking** — data loss, wrong access granted or removed, a crash, the server
  thread held, or an acceptance criterion that cannot pass.
- **important** — real defect with a bounded blast radius, or a test that does
  not prove its claim.
- **minor** — worth fixing, no user-visible consequence.

Say exactly what the fix must **achieve**, never write the patch. The author
fixes their own code; a reviewer who writes it removes the second pair of eyes.
