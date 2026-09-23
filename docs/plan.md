# DiscordTowny — Development Plan

How it is built, in what order, and what can be done in parallel. Governed by
`docs/constitution.md`; implements `docs/spec.md` according to `ARCHITECTURE.md`.

Status: **approved and governing**. Changes to this document need the owner’s approval.

---

## 1. Method

- One worktree per task, branch with its own name, nobody touches `main`.
- Each branch passes review before being integrated. An agent other than the
  author reviews, and integration is performed by the architect.
- No branch is opened without the contract it requires in place (see Phase 1).
- A task that needs to touch files from another open task is not launched in
  parallel: it waits or is reordered.

## 2. Breakdown for Parallel Work

The dependency rule of the architecture defines the boundaries. Each zone
belongs to a single task at a time:

| Zone | Packages | Touches others |
|---|---|---|
| Data | `storage/` | No |
| Discord | `discord/` | No |
| Game | `minecraft/`, `towny/` | No |
| Domain | `link/`, `space/`, `sync/` | It is the center: worked on solo or coordinated |
| Updater | `update/` | No, it is independent |
| Configuration | `config/` | Consumed by everyone: closed early |
| Documentation | `README.md`, `docs/user-guide.md` | No |

Different zones, different agents, no merge conflicts. The domain is the
bottleneck: it is built first and touched little afterward.

## 3. Phases

### Phase 0 — Skeleton

A single agent. Nothing is parallelized until this is in place.

- Gradle project, Java 25, dependencies, shadow with relocation.
- Plugin that starts up and shuts down in Paper without doing anything else.
- Sample `config.yml` and `messages.yml`, complete according to the spec.
- Empty package structure, with the dependency rule documented.
- CI that compiles and runs tests on each push.

**Ends when**: the jar loads on a clean Paper server with Towny and does not
throw errors.

### Phase 1 — Contracts

A single agent, short and decisive. Defines the interfaces separating the zones:
storage, Discord operations, Towny reading, typed configuration. No
implementations.

**Ends when**: the signatures are settled and reviewed. From here on,
multiple tasks can proceed without interfering with each other.

**It is the most important living document in the project**: changing a contract
later forces coordinating multiple branches. Think it through once, properly.

### Phase 2 — Foundations, in Parallel

Three agents at once, three non-overlapping zones:

| Agent | What it builds |
|---|---|
| A | `storage/`: schema, migrations, DAOs, MariaDB and SQLite support |
| B | `discord/`: JDA connection, serialized operation queue, log queue |
| C | `config/` and `towny/`: configuration loading and validation, Towny read facade |

**Ends when**: each zone compiles, has its tests, and is verified
independently. `storage` against in-memory SQLite; `discord` against a test
guild; `towny` against a local server.

### Phase 3 — Linking

One agent, domain zone. The first end-to-end feature: `/dt link`,
`/link`, `/unlink`, codes with expiration and attempt tracking, persistence.

**Ends when**: a player links their account and the link survives a
restart. Acceptance criterion 1.

### Phase 4 — Spaces and Synchronization

The core. Done in two tasks, **in sequence** because they share the domain:

1. Space lifecycle: creation, renaming, archiving, restoration, with
   idempotent tasks on the queue.
2. Synchronization: Towny listeners, calculating applicable roles,
   periodic and on-demand reconciliation.

**Ends when**: acceptance criteria 2 through 6 and 8 are met.

### Phase 5 — Command Surface, in Parallel

Two agents, separate zones:

| Agent | What it builds |
|---|---|
| D | In-game commands: `help`, `status`, `delete`, `sync`, and the entire `admin` block |
| E | Discord commands: `/town`, `/res`, `/residents`, `/townlist`, `/mytown`, `/help`, with their embeds and pagination |

Both consume domain code that is already built; they do not modify it.

### Phase 6 — Independent, in Parallel

| Agent | What it builds |
|---|---|
| F | `update/`: checking, verified download, notifications, `/dt admin update` |
| G | `README.md` and `docs/user-guide.md`, in player language |

Neither touches the domain. They can start earlier if agents are free: the
updater only needs Phase 0, and documentation only needs the spec.

### Phase 7 — Hardening

A single agent, or the architect:

- Run through the twelve acceptance criteria one by one on a real server.
- Cause failures intentionally: bot down, database down, channel manually
  deleted, role manually assigned, cutoff midway through creation.
- Measure that the server does not drop ticks with the log channel at full capacity.
- Verify that no secrets are leaked in any log or message.

### Phase 8 — Release

- Semantic versioning, changelog.
- Pipeline that publishes the jar **and its SHA-256 checksum** to the GitHub release.
  Without a published checksum, the Phase 6 updater will not work.
- License and contributing guide.

## 3.1 Review

Review is just another task, with its own agent, and is not done by whoever
wrote the code.

### Roles

| Role | What it does | What it does not do |
|---|---|---|
| Author agent | Writes the task in their worktree | Does not review their own work or integrate |
| Reviewer agent | Reviews the branch and writes the report in `docs/revisiones/` | **Does not fix the code being reviewed** |
| Architect | Reads the report, reviews the fix, integrates into `main` | Does not write the tasks |

### Cycle

1. The author agent finishes their branch and declares it ready.
2. The reviewer agent reviews it and writes
   `docs/revisiones/<branch>.md`: what was reviewed, what was found, severity of each
   finding, and what needs to change.
3. The author agent fixes it on their branch and replies to the report.
4. The reviewer checks that each finding was resolved and closes the report.
5. The architect reads the closed report, reviews the changes from the fix, and
   integrates.

The reviewer does not touch the code because a fix from them would enter unreviewed.
The only exception is obvious typos in text or comments, noted in the
report.

Every review works through `docs/review-checklist.md`, which lists what this
project has actually got wrong: the thread rules, identity by ID and never by
name, success reported without doing the work, a failed read passing for an
answer, the message catalogue, and the ways a test can lie. It exists so that
the rigour of a review does not depend on how each assignment happened to be
worded that day.

### What it reviews

- Meets the acceptance criterion for its task.
- Does not invade zones belonging to other tasks or break the architecture's
  dependency rule.
- Respects the threading model: no Towny outside the main thread, no database
  or Discord inside it.
- Implements nothing that is not in the spec.
- Does not leak secrets in logs or user-facing messages.
- Partial failures do not leave open permissions.
- Has tests and up-to-date documentation.

### Report format

One finding per line, with file and line, severity (blocking, important,
minor), and proposed fix. No praise or lengthy summaries: the report is
read to act.

## 4. Order and Parallelism

```
Phase 0 ──> Phase 1 ──┬──> Phase 2 (A, B, C in parallel) ──> Phase 3 ──> Phase 4 ──┬──> Phase 5 (D, E in parallel) ──> Phase 7 ──> Phase 8
                      └──> Phase 6 (F, G in parallel, from the start) ─────────────┘
```

Concurrency peak: three agents in Phase 2, plus the two independent ones from
Phase 6. Five authors at once is the reasonable ceiling, with a reviewer agent
working behind them on branches as they become ready.

## 5. Risks

| Risk | What we do |
|---|---|
| Contract change halfway through Phase 2 | Phase 1 closed and reviewed before opening branches. A change forces stopping and coordinating |
| Discord rate limit during testing | Dedicated test guild, never production |
| Towny API different from expected | The `towny/` facade is validated in Phase 2, before building anything on top of it |
| Work leakage into domain from Phases 5 and 6 | During review: a command branch that modifies domain is sent back |
| Documentation becoming outdated | Phase 6 in parallel, and review verifies that a command change updates docs |

## 6. Definition of Done

A task is done when it compiles, has its tests, meets its assigned acceptance
criterion, does not invade outside zones, does not break the dependency rule,
and its documentation is up to date. Not before.
