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

# Estado del proyecto

La fase de planificación **está cerrada y aprobada**. Los cinco documentos
existen y mandan sobre cualquier decisión: `docs/constitution.md`,
`docs/spec.md`, `ARCHITECTURE.md`, `docs/plan.md` y `docs/tasks.md`.

**Se implementa código.** No vuelvas a proponer planificación ni entrevistas:
esa etapa ya pasó.

## Cómo se trabaja

- Cada agente recibe una ficha de `docs/tasks.md` y trabaja en su propio
  worktree, sobre una zona de archivos exclusiva.
- No salgas de tu zona. Si necesitas algo de otro paquete, dilo en tu informe
  en lugar de tocarlo.
- Nadie hace merge ni toca `master`: integra el arquitecto.
- Cada rama la revisa un agente distinto del autor, que escribe su informe en
  `docs/revisiones/` y **no corrige el código que revisa**.
- Si el entorno te impide compilar o hacer commit, **dilo claramente y sigue
  con el resto del trabajo**. No supongas que algo funciona sin comprobarlo, y
  no te detengas por ello si puedes avanzar en lo demás.

## Estado por tarea

Ver `docs/tasks.md`. Integradas: T0, T1, T2, T3, T4.
