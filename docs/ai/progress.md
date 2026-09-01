# Progress

Format and archiving rule: `docs/ai/readme.md`.

## Archived 2026-09-01

This file had grown to 1916 lines (the archiving threshold in `docs/ai/readme.md` is 500) covering
2026-07-20 through 2026-08-13 without ever being cleared, in violation of its own stated process.
Every block was either explicitly marked done ("СДЕЛАНО") or ended in a handoff to `TODOS.md`
(the last entry, 2026-08-13, explicitly files its open question as TODOS G-1.82 and defers the
decision to the user) — nothing in it read as work still in flight in `progress.md`'s own terms.
Moved verbatim, in the original file's order, into:

- `docs/ai/archive/21-07-2026-foundational-tungsten-features.md` — the 2026-07-20/21 foundational
  packages (drop-in baritone->tungsten swap, MCP server, agent perception loop, movement primitives,
  worldedit-like `//set`, anti-cheat rotation, godbridge + its sneak-bridge prehistory, block
  placement, combat primitives + shield, bow trajectory engine, BreakRules, visible breaking + chase,
  need-fulfiller API, PVP rework, slime parkour, autotesting design, shredder pathfinder v2 including
  the old sprint-speed telly-bridge rewrite).
- `docs/ai/archive/23-07-2026-bugfixes-and-place-as-a-move.md` — 2026-07-22/23 bug fixes (combat
  void-safety, BUG #29 frozen camera, #34 parkour move-gen) and the original place-as-a-move /
  core-bridge work (now superseded by TODOS.md C5.5-C5.14).
- `docs/ai/archive/24-07-2026-pvp-suite-and-live-fixes.md` — the 2026-07-23/24 break primitive,
  LIVE-A follow fix, and the PvP audit that produced suite v1.
- `docs/ai/archive/10-08-2026-pvp-suite-fixes.md` — 2026-08-08/09/10 (ASSESS pass, allround/bow_flee,
  edge_duel knockback, the interleaved-arms methodology fix).
- `docs/ai/archive/12-08-2026-combat-measurement-methodology.md` — 2026-08-11/12 mob_skeleton
  methodology work (the A/B-blocking bug, the floating-island course-validity bug).
- `docs/ai/archive/13-08-2026-mob-skeleton-combat-investigation.md` — 2026-08-13, the four-session
  mob_skeleton/inRange-band investigation that ends by filing TODOS G-1.82.

No active IPI task is currently tracked here. The next one written to this file starts a fresh
`## <Название задачи>` block per the format in `docs/ai/readme.md`.
