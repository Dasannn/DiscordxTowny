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

# **To-Develop:**

We are starting a new project from scratch.

Do not implement any code yet.

You are acting as the lead product architect for this project.

Before doing anything:

1. Read CLAUDE.md and all files it imports.
2. Inspect the repository.
3. Interview me to understand exactly what I want to build.

We will create the project specification in stages:

1. constitution.md
2. spec.md
3. ARCHITECTURE.md
4. plan.md
5. tasks.md

Do NOT generate all documents at once.

For each stage:

- ask me the necessary questions
- identify ambiguities
- propose the document
- wait for my feedback
- revise it
- only then proceed to the next stage

Do not write application code until the planning phase has been approved.