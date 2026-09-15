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

## 2026-09-14 — Mac pipeline validation

### Investigate
- User request: update checkout, recover context, verify testing and Telegram video delivery.
- Updated cf82a5fc -> 3be654dd (2405 commits). See docs/STATUS-2026-09-14.md for the survival work checkpoint.
- Clients carried 0.60.0 and tester1 was outside the world. No test workflow exists under .github/workflows (only wiki.yml).
- The image lacks pkill; recording continued after the runner copied the MP4, producing a partial file.

### Plan
- Build and deploy current main, run navigation smoke tests with recording, inspect and decode the video, verify Telegram delivery.

### Implement
- :1.21.11:build -x check --offline succeeded with JDK 21. Version 0.94.0 built from 3be654dd is 59 commits newer than the release tag.
- UCTEST_GPU=0 deployment succeeded; nested jars matched, both Py4J endpoints answered.
- Recorder now uses Python /proc inspection scoped to suite ffmpeg processes and waits for exit before copying; launch and stop failures are checked.
- nav_flat/nav_staircase/nav_descend passed before and after this change. Second pass: 28.3/29.0/29.0 FPS, no falls or freezes. This is smoke coverage, not full regression or a reliability estimate.
- Artifacts: deploy/runner/artifacts/20260914-154558 and 20260914-154859. All three corrected recordings decoded without errors; flat-course frames reviewed through the finish.
- Reviewed flat-course video delivered through tg_video.py: ok=true, message 8792, direct connection. Credentials were not printed.
- G89 --nopick and open survival defects were not revalidated. Publication was initially blocked by automatic approval review; the user explicitly authorized continued work and publication on 2026-09-15. No release published yet.


## 2026-09-15 — Survival ascent handoff

### Investigate
- Fast-forwarded main to 6da576e6, built and deployed. Read the current gap triage against live sources before selecting a fix.
- Preflight risks: early tool/drop acquisition; mine/place ownership; deep falls beyond FastPlanner's three-block limit; land-to-water planning differences; low-health survival; unvalidated Nether and End progression. Existing tasks and ports are present, so these are test targets, not claims of missing implementations.
- DefaultGoToDimensionTask's Nether-to-Overworld helper constructs EnterNetherPortalTask(NETHER). This parameter is inconsistent with the helper's purpose, but Task.tick does not gate subtask ticking on isFinished; runtime impact remains unproven.
- Recorded ten-minute default survival baseline: wood, first craft and wooden tools at 113s, no stone tools, no deaths, median 29 FPS. The generic smoke reports PASS because any resource rung satisfies it; this is not a completion acceptance test.
- From ~113s onward: body (109.5,140,-46.7), target stone around (119,137,-52), pending break stand (109,141,-48). Replanning recurs without starting the dig. Screenshot and surrounding blocks show a one-block ascent out of leaves.
- A direct two-cell walker probe on that preserved geometry failed 6/6: inactive after two seconds, unchanged position and full health. BlockPathWalker accepts horizontal distance <1.5 before the jump. FastNavigator's later centering cannot perform a step-up. Baritone MovementAscend checks feet against destination cells.

### Plan
- Require destination-cell occupancy on ascending walker edges, retaining the edge's ascent identity after take-off. Compare the runtime switch off/on on the same geometry, run adjacent navigation gates, then resume survival.

### Implement
- Added walkerAscentNeedsAscent and the ascending-edge completion condition. Clean tungsten build and 1.21.11 build passed and deployed. Same-build control with the switch off failed 6/6; on passed 6/6, followed by another 6/6 with the checked-in probe (destination feet, grounded, no health loss). nav_flat/nav_staircase/nav_descend passed at 29/26/29 FPS; all recordings decode. Artifacts: deploy/runner/artifacts/20260915-065235. Survival continuation is running from the preserved stall position and inventory; no whole-game success claimed.
- Shared recorder finalization now serves both suite and gamer_smoke. The full 608s baseline recording decodes without errors. Reviewed failure excerpt delivered to operator Telegram, message 8812. Artifact: gamer_run1.mp4; retained excerpt in workspace outputs/unionclef-canopy-stall-before.mp4.


Probe replay (requires the recorded world's one-block step to remain intact):

```sh
python3 deploy/runner/walker_step_probe.py --server uctest-gamer-server --start 109.5 140 -46.7 --dest 109 141 -48 --ascent true --output /tmp/ascent-on.json
```

The probe accepts arbitrary start/destination coordinates for another existing fixture. Run `--ascent false` as the control; the runtime switch is restored on exit.
