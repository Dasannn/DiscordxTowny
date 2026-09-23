# Agent Operating Rules

This repository may be developed by multiple AI agents concurrently.

## General rules

- Read the project documentation before implementing anything.
- Never work directly on main unless explicitly instructed.
- One task per worktree.
- Do not modify another agent's worktree.
- Do not merge into main.
- Do not change architecture or requirements without approval.
- Do not implement requirements that are not documented.
- Write code, comments, and documentation in English.

## Sources of truth

When available, read:

1. `docs/constitution.md`
2. `docs/spec.md`
3. `ARCHITECTURE.md`
4. `docs/plan.md`
5. `docs/tasks.md`

## Missing documentation

If required project documentation does not exist yet, do not start implementation.

Help define and create the documentation first.

# Project Status

The planning phase **is closed and approved**. The five documents exist and
govern every decision: `docs/constitution.md`, `docs/spec.md`, `ARCHITECTURE.md`,
`docs/plan.md`, and `docs/tasks.md`.

**Code is being implemented.** Do not propose planning or interviews again:
that stage is over.

## How we work

- Each agent receives a task card from `docs/tasks.md` and works in their own
  worktree, on an exclusive file zone.
- Do not leave your zone. If you need something from another package, state it in
  your report instead of touching it.
- Nobody merges or touches `master`: the architect integrates.
- Each branch is reviewed by an agent other than the author, who writes their
  report in `docs/revisiones/` and **does not fix the code they review**.
- If the environment prevents you from compiling or committing, **state it
  clearly and continue with the rest of the work**. Do not assume something works
  without verifying it, and do not stop because of it if you can make progress on
  the rest.
- **Do not run `./gradlew` unless your assignment requests it.** A clean build
  takes between one and three minutes, and several agents have burned their
  entire turn waiting for it without writing a single line. The architect
  compiles and verifies each branch on their own before integrating it. Spend
  your turn reading and editing code.
- Write code, comments, and documentation in English from here on.

## Two habits that cost T28 seven rounds

- **Never reach for a dependency. Ask for it.** If what you need does not arrive
  through your constructor or your parameters, say so in your report and stop.
  Do not park it in a static field, do not pull it out of another service, and
  **never** use reflection: `getDeclaredField`, `getDeclaredMethod` and
  `setAccessible` have no place in this codebase. Every one of those works on the
  day it is written and fails in silence later, which is the worst failure we can
  ship. A zone that gives you no door is the architect's mistake to fix, and it
  has been fixed three times already when an agent reported it.
- **Do not add an overload to spare a call site.** Overloads that exist only so
  old test calls keep compiling grew to six in one class, were collapsed to two,
  and regrew until two shapes differing solely by how a type was wrapped became
  ambiguous to the compiler. Change the call sites instead: it is a bigger diff
  once and a smaller problem forever.

## Status by task

See `docs/tasks.md`. Integrated: T0-T28. No task is in flight.
