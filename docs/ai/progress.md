# Progress

Format: Investigate → Plan → Implement. Completed investigation history is preserved in
`docs/ai/archive/15-09-2026-clearance-and-survival.md` (488 lines before archiving).

## 2026-09-23 (latest) — v0.95.34: lava safety PORTED FROM BARITONE after three per-driver patches failed

**The rule that was broken, three times running: checklist 1b, read upstream first.** The nether
lava deaths were patched one DRIVER at a time -- walker DIRECT (0.95.31: measured dirHzd=0, the
playthrough drive starts the walker with startBFS and never enters DIRECT), walker BFS (0.95.32:
fired 106+ times, and the next deaths came under the physics replay executor `exec1` and under the
MovementQueue `nav1`). Every unpatched driver was the next way in. The user pointed at baritone.

Baritone's shape, now ported (tungsten/path/RouteHazards.java): ONE predicate (avoidWalkingInto,
minus water); every movement COST refuses it incl. a column ending in lava and a diagonal's corner
cells (MovementDiagonal :158/162 -- tungsten's diagonal checked only collision, lava has none); and
the EXECUTOR re-checks the next movements every tick and cancels (PathExecutor :196-210) -- now in
the walker (both modes), PathExecutor (replay) and MovementQueue (its port had copied :193-197 and
stopped short of :198-210).

Measured, same nether-fresh start, 12-min stints: 0.95.32 2/2 stints died in lava; 0.95.34 0
route-driven lava entries in 3 stints (+1 more alive stint on the release). Guards: nav 14/14, OBS
2/2, chase_flat / narrow_bridge PASS. Released v0.95.34.

**Three measurement lessons from the same day, each one nearly a wrong conclusion:**
- A full nav sweep read 1/14 -- all 13 "failures" were `uctest-server rcon: timed out`. run_suite
  now scores stand errors in setup as INVALID. Re-run: 14/14.
- An A/B arm labelled 0.95.29 ran on 0.95.34: check_nested_fresh.py refused the old jar silently
  (no UCTEST_ALLOW_STALE escape) and the harness grep hid it. Escape added; the A/B harness now
  verifies the jar INSIDE each container per arm. With the real binary: chase_terrain fails
  identically on the release (freezes 23/24/26, contact None, bot never leaves its start) --
  pre-existing, not a regression.
- The nether deaths were first written up as Enderman knockback from `hurtTime>0, onGround=0,
  speed=0`. Zero horizontal speed argues AGAINST knockback, and `stage` is a stale label. The
  extended instrument (fall vector + driver) showed the executors walking the body in.

**Open:** (1) two nether deaths on 0.95.34 are FALLS -- one "doomed to fall" after damage (combat),
one near-vertical 31-block fall into lava under nav1 whose takeoff the snapshot now records
(`lavaEntryStats().takeoff`); not a planned drop (FastPlanner MAX_FALL=3, MovementFall's nether
water-bucket clause is ported verbatim, breakDown needs solid floor below). (2) chase_terrain: the
bot never moves on the gamer world, on both builds. (3) 2026-09-23 evening: the host could not run
the playthrough at all (client 9-10 fps; host 93% CPU, of which two long-running TLauncher javaw
clients ~185% + ~100%) -- four stints INVALID in a row, correctly refused.

## 2026-09-21 — THE POST-IRON CEILING IS THE PORTAL PAD, AND TWO OF ITS THREE LAYERS WERE MINE

Three consecutive 35-minute runs from the same deterministic checkpoint (`nether-reach`), each one
peeling a layer off "the bot has obsidian and flint at ~2 minutes and never lights a portal".

**Layer 1 — the pad is reserved where the task STARTED, the obsidian is tens of blocks down a cave.**
0.95.27's guard walks a strayed body back to the reserved origin and assumed the walk is always
possible. Measured: origin (735,68,829), body (799,4,846) -- 64 down, 64 across, in the water of its
own flood -- `Returning to the portal build` -> GetToBlockTask for the LAST NINE MINUTES of the run,
position frozen to the decimetre, 17 obsidian in the pack. Fix: the reservation is provisional; when
the drive gives the route up the pad is dropped and re-sited from where the body is. Confirmed by
its own instrument on the next run.

**Layer 2 — the stray was measured against the origin POINT, and the frame is not symmetric about
it.** The frame spans origin.y-1 (bottom row on the floor pad) to origin.y+3 (top row). `|dy| > 6`
therefore called a body FOUR BLOCKS BELOW THE FRAME'S FLOOR "at the build". Measured with layer 1
live: frame at (749,14,823) built TEN of fourteen into a stone wall -- read straight out of the world
over py4j, bottom row y=13 z=822..825, both columns y=14..16 -- body at y=9, sealed in the cave
below, unable to reach its scaffold cell at (750,16,822). Nineteen minutes of UnstuckChain shimmy,
run ended 10/14. Fix: bound the band to the frame plus two blocks of slack each way.

**Layer 3, and it was a defect I had just shipped — the re-site trigger was a STOPWATCH.** 200 ticks
without net approach is not "unreachable" on a cave route: the return leg is 35-57 blocks through
rock, the drive mines its way ("Mining done - passage open" throughout) and net distance does not
fall while it does. Result: three re-sites in five minutes at 35, 57 and 40 blocks -- every one
mid-journey on a walk that was going fine -- and each threw away the obsidian already placed at the
abandoned frame and sent the bot back to gather. A thrash in place of the freeze. Fix: trigger on
the verdict the drive already publishes (`GetToBlockTask.onWander` -> `requestBlockUnreachable`),
keep the tick count only as a two-minute backstop, and make the log line say WHICH of the two fired.

What is measured and what is not: the nineteen-minute frozen body is gone (longest repeated position
110 s), 0 deaths across all three runs, nav 3/3 and the OBS flood PASS (portal lit in 268.4 s) on the
same jar. The portal is still NOT lit on natural terrain -- the ceiling is real and the next layer
is the one the 0.95.19 notes already named: the high-cell stand for the top row.

## 2026-09-21 — SECOND nether death mode, measured while validating 0.95.28: "escape to the surface" under a ROOF

Validating the lava fix from `nether-fresh` found a different death first, 100 s into the run:
`tester1 was doomed to fall by Ghast`. Chain, from the client log and the server log:

1. On the FIRST tick `TimeoutWanderTask` read `PlannedEscape.enclosed()` = true -- the bot stood in a
   1x1 netherrack hole (all four neighbours of (108,45,99) queried solid over rcon).
2. `PlannedEscape.pickGoal` step 2, "the surface above": `getTopY(MOTION_BLOCKING)` -- in the nether
   that is the bedrock ROOF. Log: `Planned escape (wander enclosed) -> 108.5,128.0,99.5`.
3. The build engine pillared/dug straight up (`y=79` at t=60 s, `y=117` at t=82 s), a Ghast
   fireball knocked it off, it fell, respawned EMPTY at world spawn and re-ran the wood ladder.

Two defects, one measured chain. CORE: `PlannedEscape` had no notion of a roofed dimension --
under `world.getDimension().hasCeiling()` the surface step is skipped and the escape is the nearest
standable cell (step 3), which the engine digs to sideways. HARNESS: the nether-entry hook in
`gamer_smoke.py` fired on the first poll that read NETHER, so a run started `--from nether-fresh`
RE-SAVED nether-fresh over itself a minute in -- the checkpoint drifted from "clean entry" to "bot
in a hole" (02:55) and then to "bot at y=91 on its pillar" (04:01). Entry is now a TRANSITION
(overworld -> nether); a run that starts in the nether never re-saves. The drifted checkpoint is
gone; a clean one is re-captured by a natural run from `nether-reach` with the fixed hook.

Also learned on the way (RULE ZERO, twice): the first two nether-fresh attempts were the stand,
not the bot -- an rcon `kill` timing out on a server 89 ticks behind right after the restore (the
harness now says so and carries on instead of aborting the run), and a 9-10 fps reading that the
same checkpoint gave 21-28 fps for twenty minutes later with nothing changed. And the duel guards
need `tester2`, which had been OOM-killed (137) 42 h earlier: the pvp suite hung on it for 15 min.

## 2026-09-21 — NETHER LAVA DEATH MECHANISM FOUND BY INSTRUMENT: knockback off a narrow ledge, not the bot's own step

RULE ONE, finally done instead of guessed: WorldSurvivalChain now snapshots the body's state on the
rising edge of `player.isInLava()` (py4j `lavaEntryStats()`). From `nether-fresh`, on the tick the
bot entered lava: **hurtTime=10** (a hit landed that tick), **onGround=0** (airborne),
**horizontal speed 0** (falling, not walking), **flee=0**, **combatFwd=0** (NOT pressing forward),
**stage=NARROW_BATTLE** (fighting on narrow terrain), pos (125,53,148), three entries in the run.
So the nether death is an Enderman hit KNOCKING the bot off a narrow ledge into the lava below. It is
not the bot's own movement -- which is exactly why three own-movement clamps (0.95.25 obsidian filter,
0.95.26 flee veto + VoidGuard key-release) could not stop it: they veto the bot's steps; the bot is
thrown. The wrong layer was being patched for a day.

THE CORE FIX (next): treat LAVA as VOID in the terrain-safety primitive. `VoidDetector.fallHeight`
scans down for a collision shape; lava has none, so a 2-block lava pool over stone reads as a 3-block
fall -- "safe" -- and none of the void machinery engages at a lava edge. If a lava block in the
column returns MAX_SCAN_DEPTH (lethal), then every existing, tested void behaviour applies to lava
edges for free: edgeAhead/VoidGuard's DIRECTION-SPECIFIC veto (only steering INTO the drop is
cancelled -- 0.95.26's blanket-release lesson honoured), sneak at the edge (vanilla edge protection
against being pushed off), NARROW_BATTLE detection and repositioning away from the edge, "toward-
island input kept under knockback", and safeFleePoint refusing edge candidates. One check in one
primitive. Blast radius to verify before shipping: who else calls fallHeight/edgeAhead/voidWithin
(the flood must still be able to stand beside lava to place water -- it is pathfinder-driven, which
VoidGuard does not clamp; confirm the planner does not consult VoidDetector).

## 2026-09-21 — v0.95.27: the builder returns to the frame when strayed VERTICALLY (new stall found on film)

The recorded nether-reach run on v0.95.25 (deaths 0, fps 28, GAMER_SMOKE PASS) flooded, mined all 14
obsidian and STARTED the frame -- then no nether entry. Late frames (t=28..32 min, verified) are
identical: bot at (731.7,13,833.7) facing a wall, `PlaceBlockTask: Place obsidian at (732,69,832) ->
Wandering / Failed exploring`. The frame origin was chosen on the SURFACE (y=69) while the bot was up
there; it then mined deep and ended 1.4 blocks away horizontally and **56 blocks below**. 0.95.23's
return-to-build guard measured X/Z only (`ddx²+ddz² > 100`), so it never fired; the wander cannot
climb 56 blocks. Fix: the guard also returns the builder when |dy| > 6 (a frame is five tall). One
condition; a no-op inside the frame's own column of height. The two earlier successful entries simply
did not land in this geometry (RULE FIVE). Release 0.95.27 (0.95.26 stays retired: the reverted
clamp). Validation pending: recorded re-run from nether-reach + OBS bench + nav baselines.

- **Validation, on film (frames verified before sending).** A natural recorded re-run on 0.95.27
  had no flood freeze but ran out of its 32-min window at 12/17 obsidian (deep basin, slow collect)
  -- three recorded natural runs missed the entry for three different reasons (freeze / vertical gap
  / window) while the two logged entries were unrecorded, so the build path was validated on a
  deterministic entry demo: nether-reach + 17 obsidian and flint&steel GIVEN via rcon after restore
  (stated honestly in the caption; the mining itself is on the earlier timelapse, TG 9083). Result:
  "Returning to the portal build" FIRED at t=254 s (the vertical return, fix #1, seen in frame), the
  frame went up at y=69 with the front scaffold, the portal lit, the bot ENTERED THE NETHER at t=269 s
  and began "Hunting endermen for pearls" with a diamond sword (t=329 s), then died to nether lava
  (~t=400 s; the documented nether-stage frontier). Sent to the operator as TG 9087 (4x, 0-420 s).
  The entry hook refreshed `nether-fresh` from this run. TG 9082 was a dud (330 s of the main menu,
  bench never started: my cwd mistake; sent unwatched -- rule violated once, every clip since verified).
- **Bench lessons this session:** (1) never trust `pgrep` in the client container -- scan /proc (the
  codebase's own pattern); a false "ffmpeg stopped" cost a spurious watchdog. (2) Git Bash rewrites
  `/mc-data/...` args to `C:/Program Files/Git/mc-data/...` before docker sees them (MSYS path
  conversion) -- wrap container paths in `sh -c "..."`; a bare path gave "Protocol not found".
  (3) A run-exit waiter keyed on process age must start after the startup window, else it fires at
  once (two false "RUN EXITED"). (4) Telegram rejects >4096 chars with a bare 400 -- use
  tg_send_long.py. (5) The recorder does NOT starve the client (30/29/29 fps) and server TPS is 20;
  recorded-run failures were real stalls, not the recorder.

## 2026-09-19 — MILESTONE: one-run underground -> NETHER entry, live (v0.95.24 + v0.95.25 clear the portal ceiling)

**The post-iron nether-portal ceiling is CLEARED end-to-end.** A scope run from the canonical deep
checkpoint nether-reach (y=37, NOT the flood-stuck death-trap), daylock, v0.95.24 + v0.95.25:

- Reached the flood fast, flooded a DEEP lava lake at y=9, mined obsidian **safely** (obs climbed to
  11+, hp stayed 20 the whole time -- NO lava death), ascended (y=9 -> 68) with the obsidian, built
  and lit the frame, and **ENTERED THE NETHER** (dim=NETHER at 78,56,112 then moving to 92,51,114).
  This is the first live one-run underground->nether entry -- the "time-bound" gap from the very first
  capstone, now closed.
- **So the lava death was flood-stuck-SPECIFIC** (the worst-case death-trap where the bot is wedged
  UNDER the lake), not the common path. From a normal deep approach the fixes flood + collect obsidian
  cleanly. The deep survival confluence (documented below) is a worst-case robustness item, not a
  blocker for the typical playthrough -- reprioritised accordingly.
- Nether checkpoint saved for the NEXT frontier (the NETHER STAGE): run end-save nether-attempt-v25
  (+ mid-run cp0919-2044-t918). TODOS "the nether stage was entered for the first time" is now live.

NEXT FRONTIER: the NETHER STAGE. In this very run the bot ENTERED the nether then died there --
server log "tester1 was doomed to fall by Enderman" (18:02:17, an Enderman knocked it off a ledge --
a fall, near the nether's lava), then "slain by Zombie" after the respawn. So the nether stage's first
hazards are live: Enderman knockback near ledges/lava (fall death) and mob defense in a dimension that
is ALWAYS hostile (daylock only affects the overworld surface). Next pass: nether-stage survival
(Enderman handling + fall/ledge safety near lava + mob defense), toward blaze rods / fortress. A clean
nether checkpoint (bot alive just after entry) should be captured first for a deterministic start --
this run's entry was proven but the bot died before an auto-checkpoint caught it in the nether.
The underground-lava-survival worst-case (flood-stuck) remains a documented robustness follow-up,
lower priority now that the typical path reaches the nether.
- **One-run nether entry RE-VALIDATED (RULE FIVE, 2nd sample) + `nether-fresh` checkpoint captured.**
  A second nether-reach run flooded a deep lava lake, mined obsidian safely (obs 14, hp 20 throughout,
  no lava death), built + lit the frame, and entered the nether -- the bench's new nether-entry hook
  fired ("NETHER ENTERED -- clearing nearby hostiles + healing"), saving `nether-fresh`: bot in
  minecraft:the_nether at (79.6,56,113.3), hp 20, food 20, xp 15, 34 stacks (fully equipped). That is
  the deterministic, survivable START for the NETHER-STAGE pass. With the entry hostiles cleared the
  bot then SURVIVED in the nether (hp 20 at t=30 min, winning a mob fight), where the first run died to
  Enderman knockback -- so nether survival is tractable from a clean start.
- NEXT PASS start: `python3 deploy/runner/gamer_smoke.py N --from nether-fresh` -> nether-stage
  survival + progression (Enderman/ledge/lava fall-safety, mob defense, then blaze rods / fortress).
- **NETHER STAGE observed live from nether-fresh -- it WORKS, and its core blocker is LAVA SAFETY.**
  The bot's nether goal is correct and it PROGRESSES: "Beating the game -> Hunting endermen for pearls
  4/14 [ender_pearl x 14]" -- it survives the nether, fights mobs (Mob Defense), and collects ender
  pearls (4/14, items 864). Then it DIED: server "tester1 tried to swim in lava to escape Enderman"
  -- fighting/fleeing an Enderman near the nether's lava it backed INTO the lava. So the nether stage
  is tractable, and its #1 blocker is the SAME lava-safety root as the underground-portal worst-case:
  the pathfinder / combat movement steps the body into lava when near lava. In the nether (lava
  everywhere) this is critical.
  ⭐ **UNIFIED HIGHEST-LEVERAGE FRONTIER: robust LAVA AVOIDANCE** (tungsten pathfinder + combat/flee):
  the body must never step into lava, even fighting/fleeing near it. Fixing it unblocks BOTH the
  underground-portal worst-case (flood-stuck burns) AND nether-stage progression (Enderman combat
  near lava). Repro: `--from nether-fresh` (dies to lava-vs-Enderman) and `--from flood-stuck` (burns
  under the lake). This is the next focused pass -- deep + delicate (do not regress lava-crossing /
  descent), with those two deterministic repros.
  - **Scoped fix LOCATION (source read).** The A* pathfinder already treats lava as blocked
    (MovementHelper: `isLava -> return true`), so PLANNED walks avoid it. The gap is non-planned
    movement near lava: (1) `RunAwayTask.safeFleePoint` picks a flee point via `snapGround` +
    `hasRoomBeyond`, checking VOID-safety but NOT lava -- so it can flee onto/through lava; (2)
    `RunAwayTask.driveAwayRaw` key-drives away with no ground/lava probe, relying on `VoidGuard`
    afterwards, and VoidGuard (`VoidDetector.voidWithin`) clamps away from the VOID only, no lava
    equivalent. FIX: add a lava clamp analogous to VoidGuard (a `voidWithin`-style "lavaWithin" that
    vetoes a key-step toward adjacent lava) and make `safeFleePoint` reject a candidate whose ground
    is lava or whose step path crosses lava; the same clamp helps combat movement (MobDefenseChain
    strafe/approach) near lava. TEST: `--from nether-fresh` (flee-into-lava) and `--from flood-stuck`
    (flood burn), plus combat regression (edge_duel) and lava-crossing/descent (mine_diamond) so the
    clamp does not forbid legitimate lava interaction.
  - **v0.95.26 SHIPPED the free-form clamp -- and it did NOT stop the nether death (a decisive
    negative).** lavaAhead/isLavaAt in VoidDetector; safeFleePoint skips lava candidates; driveAwayRaw
    releases keys on a lava heading; VoidGuard.protect clamps keys/velocity into lava like the void.
    Nav baselines 3/3 PASS (~30 fps; a no-op without lava). But from nether-fresh the bot STILL died
    "tried to swim in lava" (twice, incl. a plain one with no "to escape"). Three own-movement clamps
    not preventing it is strong evidence the entry is NOT the bot's own keys: most likely Enderman
    KNOCKBACK into lava (Endermen hit hard; the nether stage fights them over lava) or a
    combat-approach gap. A first death-tick tracer missed the instant (the nether death is ~30 s
    in, faster than 3 s sampling). NEXT: a tick-resolution death instrument (hurtTime / onGround /
    velocity / feet-lava at entry), then knockback-aware positioning near lava -- not another
    movement clamp. Stop patching the wrong layer.
  - **v0.95.26 REVERTED -- it FROZE the bot at lava (measured regression).** Recorded nether-reach
    run on the v0.95.26 jar: the bot sat at the flood-stuck spot (789.5,20,818.6) under the lake with
    position AND inventory identical from t=647s to t=1376s (12+ min), obs=0, hp=20. v0.95.24 left
    that spot in ~1 min; v0.95.25 reached the nether from nether-reach twice. Cause: the VoidGuard
    lava clamp releases the movement keys whenever lava is within the lookahead -- and with lava
    SURROUNDING the bot that is every direction, so it cannot even walk away to run the escape.
    A blanket key-release turned a death into a permanent freeze on the path that previously reached
    the nether: strictly worse. Reverted wholesale to v0.95.25 (last version proven to reach the
    nether). Lesson for the lava pass: any lava guard must be DIRECTION-specific (veto only the step
    INTO lava, keep the steps away from it) and knockback-aware -- never a blanket release.
  - **RULE ZERO audit of that freeze, with numbers.** The v0.95.26 freeze run was the only one under
    the screen recorder, so the stand was suspected first. MEASURED and EXCLUDED, one by one:
    (1) client fps: 30 baseline / 29 under libx264 x11grab 15fps / 29 under mjpeg 6fps
    (getPerfStats) -- the recorder does not starve the client; (2) server tick: flat test-server
    2.2 ms/tick, gamer-server 6.0 ms/tick, both "running normally" at 20 TPS (`tick query`);
    (3) TimerGame's clock: ClientConnection ticks / 20 (not time-of-day), so the flat server's
    frozen daytime (1000) is irrelevant. With the stand clean, the v0.95.26 clamp is back to being
    the leading explanation of THAT freeze -- but with a caveat: a recorded OBS flat-bench run on the
    twice-proven v0.95.25 ALSO stalled ("Waiting for lava to turn to obsidian" 320+ s, unstuck shimmy
    every ~10 s, bot drifted 26 blocks from the lake; frames verified), so a stand-side or bench
    confounder (dirty arena / leftover lava outside the wipe?) exists and is NOT yet characterised.
    Both parked: re-test v0.95.26 unrecorded from flood-stuck; instrument the flat-bench "Waiting"
    stall (which rim/water cell the flood chose vs the scene lake). Not chased now -- it would not
    change the shipped state (v0.95.25) and the nether-entry demo is the priority.
  - **RESOLVED by a controlled comparison: the v0.95.26 freeze WAS the clamp.** The next recorded
    nether-reach run, on v0.95.25, went to the SAME lake (787.7,21,820.5 -- the flood-stuck spot),
    under the SAME recorder, and did NOT freeze: it flooded and mined -- obs 1 -> 3 -> 5 -> 8 -> 9
    over 100 s, body moving (z 817 -> 813), UnstuckChain ownership 0, "Mining/Collecting obsidian".
    Same lake, same recorder, same stand (fps 30 / 20 TPS): only the jar differed, and v0.95.26 sat
    there for 12+ minutes. The revert's "measured regression" claim is reinstated with this evidence.
    The blanket key-release IS the cause: with lava on every side the guard releases every direction.
    The flat-bench "Waiting for lava" stall on v0.95.25 remains a separate, uncharacterised item.
  - **REFUTED again, and this time with the mechanism (RULE FIVE: n=1 fooled me twice).** The next
    recorded run on 0.95.27 -- which has NO lava clamp -- froze at the identical spot (789.5,20,818.6),
    obs 0. So the clamp was not the cause of that freeze. Probed live: position stable; UnstuckChain
    owning the bot EVERY tick (own 11938 -> 12147 in 15 s, rescues 119 -> 121); InteractWithBlockTask
    at 16k ticks in "Getting within reach of (789,21,816)"; that rim 2.7 blocks from the body, lava
    2.4 blocks away. That is IN RANGE (RIM_IN_RANGE_SQ = 25), so v0.95.24's approach guard RESTS, and
    the only remaining guard is the displacement checker the shimmy resets every tick -- the original
    deadlock, one radius closer. v0.95.24 closed the far rim and left the near, unplaceable rim open.
    Intermittent by which rim the flood picks (same lake: flooded on one run, froze on the next two).
    FIX (0.95.27, second change): an in-range guard on the GOAL -- if the water has not landed after
    200 flood ticks in range, blacklist the rim and re-select. The v0.95.26 clamp is exonerated for
    this freeze (its blanket-release risk stands on its own merits; it remains reverted).

## 2026-09-19 — v0.95.24: the flood stops DEADLOCKING on a lava lake it is stuck under (underground portal)

The "650-block material detour" from the earlier capstone was a MISREAD (minePick max=47 all run, zero
far picks -- the debug string showed a task TARGET, not the bot's position; the exact trap TungstenConfig
records). The bot never lacks materials (cobblestone 197->293 mid-run). The REAL post-iron ceiling from
the deep (y=37) nether-reach checkpoint is a hard FLOOD DEADLOCK:

- **Live root cause (checkpointed as `flood-stuck`).** From y=37 the @gamer bot descended to a lava lake
  at y=21, ended wedged in a pocket at (788,19,819) UNDER the lake, committed to a rim ON the lake it
  could not climb to, and FROZE there for the whole 27-min run (obs 0, mqSteps=0, UnstuckChain owning
  1919 ticks). Two defects combined: (1) `rimFor` kept a rim if `canReach(rim) || canReach(rim.up())`,
  but water is placed by clicking the rim (InteractWithBlockTask marks the RIM unreachable), so rim.up()'s
  optimism (`canReach = !isUnreachable`) re-selected the same rim forever; (2) both progress guards
  measured raw body displacement, which the UnstuckChain's in-place shimmy reset every tick -- so nothing
  ever blacklisted the unreachable rim. canReach's optimism is the same trap v0.95.23 flagged for buried
  lava, here horizontal (a lake the bot is under).
- **Fix (v0.95.24, isolated to PlaceObsidianFloodTask).** The clickable rim must itself be reachable
  (`canReach(rim)`), and the rim commit is guarded by APPROACH DISTANCE to the rim, not displacement, so
  a shimmy jiggle cannot fake progress; the flood blacklists a rim after ~10s of no approach, re-selects,
  and with the reachable-looking rims exhausted explores for lava it can stand beside. No-op on the
  surface (distance falls, guard never trips), so it cannot regress the validated surface build.
- **Validated live from `flood-stuck`.** Deadlock BROKEN: the bot left (788,19,819) within ~1 min of
  @gamer (vs 27 min frozen before), blacklisted the rim, and relocated/ascended out of the pocket
  ((788,19,819)->(796,16,779)->(799,25,769)), with UnstuckChain ownership back to 0 (was 1919).
  Regression: OBS flat bench PASS (nether_portal lit at (2358,-56,358), real block, 273 s); nav
  baselines nav_flat/staircase/descend 3/3 PASS (~29 fps). Released v0.95.24 (asset verified),
  commit 26e23911 (main + 1.21.11 synced).
- **Full-path re-run (flood-stuck, 30 min): the fix WORKS end-to-end -> then a NEW frontier.** With
  v0.95.24 the bot escaped, returned to the SAME lake, found a reachable rim, FLOODED it and MADE
  OBSIDIAN (obs=3, mining hardness-50 obsidian at (786,21,818)) -- exactly what hung forever before.
  Then it DIED: server log "tester1 tried to swim in lava" (16:26:28) -- it stepped into the lava
  while flooding/mining the underground lake, respawned ~950 blocks away, re-laddered. So the deadlock
  fix traded a permanent 27-min hang for a recoverable lava DEATH (the run continues now instead of
  freezing). NEXT FOCUSED PASS: lava-safety during the underground flood -- the bot must not path
  into / stand where it can fall into the lava it is flooding (surface flooding is safe; the cramped
  underground lake is where it dies). Daylock does NOT stop cave mobs (Mob Defense fired p80), a
  secondary underground hazard. This is a SEPARATE issue from the deadlock, revealed by fixing it.
- **Lava death REPRODUCED + mechanism (2nd run from flood-stuck).** After escaping and wandering
  ("Going to Nether -> Searching for a lava lake"), the bot returned to the lake and died mining
  obsidian. Trace (y is the tell): flooding+mining at y=21 around (784-788,816-821), obs 0->5, then
  y 21 -> 20.9 -> 20.3 as it mines DOWN INTO the basin, steps into unconverted lava, dead (respawn
  109,135,-28). So the flood converts the SURFACE layer of lava sources to obsidian, but lava remains
  deeper in the basin; the obsidian collect (MineAndCollectTask/DestroyBlockTask) follows the vein
  down into the basin and the body steps onto a cell adjacent to / above remaining lava. The
  reclaimed water no longer protects it. Reproducible: the bot eventually returns to the lake and
  dies this way. FIX DIRECTION (next, core, not a bandaid): make the obsidian collect lava-SAFE --
  candidates: (a) mine only obsidian reachable from a safe stand not adjacent to / above lava (don't
  descend into the basin); (b) keep the protective water sheet over the basin while mining, reclaim
  LAST, so a mis-step lands in water not lava; (c) flood the basin more completely before mining.
  Needs a lava-safe-mining repro and careful testing so it does not regress the surface build.
- **v0.95.25: obsidian collect refuses lava-adjacent obsidian (approach (a)) -- PARTIAL.** Shipped:
  CollectObsidianTask targets an obsidian block only if none of its 6 neighbours is lava, via a new
  OPTIONAL null-default target filter on MineAndCollectTask (37 other callers untouched). Regression
  OBS flat bench PASS (portal lit 216 s -- on a flat lake all lava converts so the filter is a no-op).
  BUT the underground death has a SECOND mode this does not fix: from flood-stuck the bot committed to
  an in-range rim beside the lava and "burned to death" flooding, BEFORE reaching obsidian mining
  (server: "tester1 burned to death" 17:14:24; bot stationary at 788,19,819 t=21..66 then dead t~88,
  respawn 111,136,-36). Root of BOTH modes: in the cramped space around an underground lake the
  pathfinder steps the body INTO lava. NEXT (deep) PASS: robust lava-avoidance in cramped quarters
  (tungsten) -- left for a focused, regression-tested change, not rushed. flood-stuck is a worst-case
  death-trap (bot wedged UNDER the lake); a typical playthrough may reach the portal on safer ground.
- **Complete mechanism of the "burned to death" mode (WorldSurvivalChain read).** The pathfinder
  treats lava as blocked (MovementHelper: `isLava -> return true`), so the death is not a plain walk
  into lava; it is a brief lava/fire CONTACT in the cramped basin that lights the FIRE STATUS, which
  then ticks the bot to death. A self-douse ALREADY EXISTS (WorldSurvivalChain lines 192-224: on fire
  -> place water at feet -> extinguish -> pick up), but every one of its preconditions fails in the
  flood: (1) it needs a filled WATER_BUCKET, which the flood has just SPENT placing the flood water
  (empty bucket until reclaim); (2) it is skipped while EscapeFromLavaTask runs (`!(mainTask
  instanceof EscapeFromLavaTask ...)`), so a bot both in lava and on fire escapes the lava but never
  douses and burns after; (3) placing water needs `isSolidBlock(feet.down())` + `canPlace(feet)`,
  neither reliable in a lava basin; the fallback "go stand in existing water" can route back through
  lava. So the underground lava death is a CONFLUENCE (cramped position + spent bucket + douse
  preconditions), a deep SURVIVAL frontier, not one clean edit. The real lever is v0.95.24's escape:
  when the bot LEAVES the death-trap (blacklists the rim) it survives; when it commits to an in-range
  rim in the basin it burns. NEXT PASS candidates: (i) flood-site safety -- reject a rim whose stand
  sits in a lava-danger zone so the bot escapes to a lake it can flood from solid ground; (ii) keep a
  reserve of water / douse from the flood water; (iii) don't skip the self-douse under EscapeFromLava.
  All delicate (survival chain / flood-site); to be done carefully with a repro, not rushed.

## 2026-09-19 — v0.95.22: the obsidian MINE equips its pickaxe (deadlock fixed); natural-terrain path now reaches the FRAME build

The capstone's obsidian-mine stall (bot "mining" exposed obsidian with a water bucket, 0 progress)
was root-caused live and fixed. The natural-terrain path now mines the obsidian and reaches the frame
build; the next frontier moved one step forward, to the frame build on real terrain.

- **Root (found by live diagnosis, no guessing).** `DestroyBlockTask.equipBestToolFor` skipped the
  tool equip whenever `PathExecutor.isPlacingNow()` was true. That is QUEUE-based (`placeQueue`
  non-empty, PathExecutor:332), true whenever a STALE bridge/place segment lingers from the approach
  route -- not only during an active place. On natural terrain the route to the flooded obsidian left
  a place segment queued, so isPlacingNow stayed true, equipBestToolFor was skipped every tick, the
  pickaxe was never equipped, and the bot "mined" exposed obsidian with the flood's water bucket for
  7+ minutes (obsidian 0) -- and because it could not break, the queue never drained: a deadlock.
  Confirmed on the LIVE stall (no full repro): the target was real exposed obsidian, food 20/20 (not
  hunger), a hotbar pickaxe was skipped too (not a main-inv reach issue), and manually selecting the
  pickaxe let it break -- so only the equip was blocked, by isPlacingNow.
- **Fix (v0.95.22, shipped).** Remove the `isPlacingNow` guard from `equipBestToolFor`. Every caller
  is a breaking context that has just claimed the tick (`minerMineUntilMs`/`minerAimUntilMs` set
  immediately before), so the executor yields and will not place this tick -- the miner owns the hand
  and must equip its tool. FoodChain-eating guard stays.
- **Validated on `nether-reach` (natural terrain).** Obsidian mined **0 -> 14** (was stuck at 0), and
  the bot reached "Building nether portal with obsidian" and placed 10 of the obsidian on natural
  terrain. Nav regression nav_break/nav_wall2/nav_bridge/nav_flat/nav_staircase/nav_descend **6/6**
  (the change only makes the mining tool equip more reliable). Did not light the portal in the 24-min
  window (natural terrain + underground mob combat is slow).
- **Next frontier (moved forward one step): the natural-terrain FRAME build.** After placing ~10
  obsidian the frame build wanders at the build site (measured 728,75,827 -- an elevated pad the site
  scan chose far above the deep lava). The frame build is 6/6 on the FLAT bench (v0.95.19); real
  terrain -- an uneven, elevated pad -- is messier. Food (v0.95.20), flood-approach (v0.95.21) and the
  obsidian mine (v0.95.22) are all fixed and validated.
  - **CLEAN diagnosis (checkpoint repro, the bot's own site + gathered materials, 2026-09-19).** An
    earlier fast `@build` repro looked like the frame floated over air, but that was CONFOUNDED (I
    tp'd the bot onto trees, so it sited over a void). The clean checkpoint run corrects it: the real
    frame is GROUNDED -- at the wander cell (736,70,828) the column below reads obsidian 67/68/69 on
    STONE from y=66 down. So the site pad IS solid; the stall is the HIGH FRAME CELL, plus a long
    shuttle. Two intertwined factors, both measured live at the wander:
    1. **High-cell stand.** (736,70,828) is a 1-wide frame cell 3-4 blocks above the y=66 ground; the
       neighbours (735/737,*,828) are air, so there is no ground side stand and it needs the
       pillar/scaffold path -- the same high-cell case the flat bench handles 6/6, failing here on the
       real elevated column.
    2. **Deep-mine <-> elevated-build shuttle.** The drain trace at the stall is
       `pillarbase=736,70,828 from=787,22,813` -- the body is at the MINE (787,22), ~51 blocks away
       and ~45 BELOW the build site (736,67), holding obsidian but unable to return and place, so
       PlaceBlockTask wanders ("Wander for 5"). The flood lava is deep (y=21) and far from the surface
       build pad the site scan chose, so every "mine more obsidian -> carry it back up and across ->
       place a high cell" round trip is long and nav-fragile.
    This is the deep, MULTI-FACTOR nav+build seam NAVIGATION.md flags known-hard -- not one wrong line
    like the four fixes this session. The next pass must pinpoint which of the high-cell chain
    (front scaffold built? pillar fired? placementStand?) fails on the real column AND whether the
    site scan should keep the build near the lava / at a height that avoids the deep vertical shuttle.
    Everything upstream (food v0.95.20, flood-approach v0.95.21, obsidian mine v0.95.22, flat frame
    v0.95.19) is fixed and validated; this multi-factor seam is the last layer before a lit portal on
    natural terrain.
  - **DECISIVE ISOLATION + the true root (2026-09-19).** Two clean gamer-server experiments settled
    it. (1) On a hand-laid CLEAN flat stone pad, given obsidian+cobblestone, the bot builds AND LIGHTS
    the portal -- a real `minecraft:nether_portal` at (698,68,836). So the whole frame+light chain is
    SOUND on the gamer server; nothing about it is broken. (2) On UNEVEN natural ground (snow/grass/
    dirt, a sweet-berry bush in the footprint) it stalls -- and a tried `pad-prep` (lay cobblestone in
    footprint holes + clear the envelope before building) FAILED the SAME way and was reverted:
    laying the pad hit the identical stall, so the pad is NOT the root. THE ROOT is
    PLACEMENT-RECOVERY WANDER-DRIFT on messy terrain: when a placement can't reach its stand
    (obstruction / uneven ground / a plant that regrows), PlaceBlockTask/PlaceStructureBlockTask fall
    to `TimeoutWanderTask`, the wander DRIFTS the body far from the build (measured 26 blocks away,
    holding the block), and it never returns -- the build site is lost. Confirmed against the earlier
    flat-bench work: the wander-recovery flags (wanderSearchMustMove/TargetFollowsTheGround/
    SpiralCountsLegsNotTries) were tried and reverted there too. So the last layer is a RECOVERY that
    keeps the builder AT the build (a directed return to the frame, or a local-only wander) instead of
    drifting off -- the core nav+build recovery the flat bench never needs because its pad is clean.
    The clean-pad proof means once the body stays put, the portal lights on natural terrain.
  - ⭐ **FIXED + SHIPPED v0.95.23 (2026-09-19): the natural-terrain portal LIGHTS.**
    `ConstructNetherPortalObsidianTask` now keeps the builder AT the build -- while in the build phase,
    if the body has strayed > 10 blocks horizontally from the origin (the wander-drift), it walks back
    to the origin before continuing, and the drain re-approaches the cell locally (this also pulls the
    body back from the lava after a gather). Validated on the gamer server, uneven natural ground: the
    bot stayed local, built the frame, and LIT the portal -- a real `minecraft:nether_portal` at
    (94,136,-35), "Done constructing" -- where before it drifted 26 blocks off and never finished.
    No-op on the flat bench (the body never strays 10 blocks on a clean pad; the guard's counter read
    0 across the flat runs), flat flood 4/5 (run 3 the pre-existing local scaffold shimmy, unrelated).
    With this, ALL layers of the natural-terrain nether portal are fixed and validated end to end:
    food gate (v0.95.20) -> flood-approach (v0.95.21) -> obsidian mine (v0.95.22) -> frame build
    (v0.95.19) -> stay-at-the-build recovery (v0.95.23) -> lit portal. The post-iron portal ceiling is
    cleared on natural terrain.
  - **Capstone (full one-run chain) attempt -- CONFOUNDED by a checkpoint-tooling bug, not the fixes.**
    A `--from nether-reach` full run to confirm food->flood->mine->build->light->nether in ONE pass
    restored the WORLD but NOT the bot's 22-min INVENTORY (it re-ran the whole ladder: wood tools
    @132s, iron @377s), so it could not reach the portal inside the 26-min window. Two things it DID
    show live: the food fix works (t=755-822 "Collect food", then it PROCEEDED, no deadlock -- v0.95.20
    re-validated), and the bot laddered normally. The end-to-end chain remains validated PIECEWISE (the
    pre-frame path in the earlier checkpoint runs; the frame build+light on natural terrain by the
    @build portal-lit proof). NEXT-PASS TOOLING NOTE: the checkpoint restore does not reliably restore
    the player inventory (only the world), so a clean end-to-end capstone / the NETHER-stage frontier
    needs the checkpoint inventory-restore fixed first (or a fresh full run). The NETHER stage
    (TODOS "the nether stage was entered for the first time") is the next post-iron frontier now that
    the portal path builds + lights.
  - **Capstone retries -- ALL bench/world-state confounded, none a fix issue (final, 2026-09-19).** I
    added a post-restore safety to gamer_smoke (force day, clear hostiles r<=64, heal+feed) so a
    resume lands the bot alive with its kit (committed) -- but the one-run capstone still could not
    complete, blocked by THREE separate BENCH obstacles, none of them the portal fixes: (1) a
    transient `docker inspect` timeout mid-restore crashed a run during the 2 GB world swap; (2) after
    a manual salvage (@gamer on the restored kit at 700,37) the bot reached the flood site but then
    "Searching for a lava lake to flood -> Wander for Infinity" -- the gamer world's lava at 787,21 was
    already CONSUMED/converted by this session's many prior runs + @build repros, so there is no
    floodable lava left near the build (the flood-approach fix then correctly EXPLORES, but a stripped
    world has nothing to find). So a clean end-to-end capstone AND the nether-stage frontier need a
    FRESH (regenerated) gamer world -- the current one is too degraded by this session's testing. The
    portal ceiling itself stays fully validated: isolated build 8/8, flat flood ~5/6, and the decisive
    natural-terrain proof -- a real `minecraft:nether_portal` built + lit on uneven ground (94,136,-35)
    with the v0.95.23 guard. NEXT PASS: regen the gamer world (or a fresh long run), then exercise the
    nether stage.
  - **CLEAN capstone (v0.95.23 + the resume-safety) -- every fixed step works in ONE full run; the
    demo is TIME-bound, not stall-bound (2026-09-19).** With the resume-safety in place a
    `--from nether-reach` run landed alive with its kit (safety fired: day, hostiles cleared, healed)
    and @gamer chained the WHOLE portal-prep with no stall on any fixed area: food (PROCEEDED, not
    deadlocked -- v0.95.20), flint, `nether` rung, flood the (intact) lava, **mined the obsidian**
    (tool-equip fix -- v0.95.22), then "Collecting building materials". It ran out of the 28-min window
    during that material gather (a long ~650-block detour to a cobblestone source near spawn), just
    before the frame build+light. So the full natural path is ~30-40 min and every one of this
    session's fixes is exercised green in it; the one-run NETHER ENTRY just needs a longer window (or
    trimming the building-materials detour -- a separate efficiency item, not a portal stall). The
    frame build+light itself is already proven on natural terrain by the @build portal-lit test. The
    post-iron portal ceiling is cleared; remaining is the nether stage (needs a longer/fresh run) and,
    as a small efficiency, the far building-materials detour.

## 2026-09-19 — natural-terrain capstone: portal path stalled on the obsidian MINE holding a water bucket (fixed in v0.95.22, below)

After the three portal releases (v0.95.19/20/21) I ran a 24-min `--from nether-reach` capstone to see
the full natural-terrain portal light + nether entry. The bot chained the whole path cleanly: food
buffer -> flint -> obsidian rung -> "Building nether portal with obsidian" -> flood the lava
(787,21,818) -> obsidian made. Then it STALLED on the obsidian MINE: 7+ minutes (t=676..1121)
"Destroy block at 787,21,817 -> Block in range, mining...", obsidian count stuck at 0, no nether.

- **Root (ground truth), NOT yet fixed.** The target (787,21,817) is real, EXPOSED obsidian (air
  above, not submerged), and the bot has a diamond pickaxe in the pack -- but its held item is a
  `water_bucket` (the flood's bucket). You cannot mine obsidian with a bucket, so the mine "runs"
  forever with 0 progress. After the flood, the pickaxe is not re-equipped for the obsidian mine. The
  flat bench masks this: `nether_portal_test.py` force-equips a diamond pickaxe whenever the task is
  mining and the hand is not a pickaxe (a documented harness workaround, lines ~293-305), so the
  bench never exercised the real auto-equip -- and it is the auto-equip (or a water-bucket re-equip
  fight) that is failing on the live run. This is the next pass: find why the obsidian mine keeps /
  does not override the flood's water bucket (miner tool-equip path -- MineAndCollectTask /
  DestroyBlockTask / PreEquipItemChain, and PlaceObsidianFloodTask's bucket hand-off). Reproduces on
  the `nether-reach` checkpoint every run.
- **Everything upstream is fixed and validated this session:** the frame build (v0.95.19, bench 6/6),
  the food gate (v0.95.20), and the flood-approach (v0.95.21) -- so the natural-terrain path reaches
  the obsidian mine with no stall before it. The remaining blocker is this one tool-equip step, then
  the frame build (already 6/6) + light.

## 2026-09-19 — v0.95.21: flood-gather approaches FLOODABLE lava, not the nearest buried pocket (natural terrain)

With the food gate fixed (v0.95.20) the natural-terrain run reached the flood-gather and stalled
"Approaching lava to flood": `PlaceObsidianFloodTask`'s no-rim fallback approached
`getNearestBlock(Blocks.LAVA)` of ANY kind, and the nearest lava was a DEEP BURIED pocket -- the bot
at the surface (y=61) committed to lava at y=27 directly below it, which cannot be flooded (no air
above the source) and which the nav cannot shaft ~34 blocks down to, while `canReach()` optimistically
said the cell above it was reachable. Stuck the whole window, 0 obsidian.

- **Fix (v0.95.21, shipped).** The fallback now approaches the nearest FLOODABLE lava (a surface
  source, air above) so getting closer lets `findFloodRim` succeed on arrival; with none known it
  explores. Only the no-rim fallback changes; the flat bench (rim found immediately) is untouched.
- **Validated on `nether-reach` (natural terrain).** "Approaching lava to flood" stall ticks: ~9 min
  -> **0**. The bot places the flood water (787,21,818), the lava turns to obsidian, and it mines the
  17 obsidian (Collect 17 obsidian at y=21). `GAMER_SMOKE: PASS`, nether rung @156s. The whole
  pre-nether path now chains: food buffer -> flint -> obsidian rung -> "Building nether portal with
  obsidian" -> flood -> mine obsidian. It ran out of the 14-min window mid-mining (natural terrain +
  underground mob combat is slower than the flat bench), NOT on a stall.
- **State of the portal ceiling after this session's three releases.** The build (v0.95.19, bench
  6/6), the food gate (v0.95.20) and the flood-approach (v0.95.21) are all fixed and validated. On
  natural terrain the path is now gated by TIME (deep obsidian mining + mob combat inside one bench
  window), not by any stall. Next pass: a longer window / a checkpoint nearer the flood, or trimming
  the pre-nether gather cost, to see the full light + nether entry on natural terrain.

## 2026-09-19 — G106 completed (v0.95.20): unreachable food no longer deadlocks the nether; natural-terrain portal path exercised end to end

With the portal build fixed (v0.95.19) I resumed the `nether-reach` checkpoint to confirm it on
natural terrain. It could not: the bot deadlocked BEFORE the portal on "Collect 140 food / Wander for
Infinity" -- the food-depleted 22-min checkpoint world (rule 4a3). Root: `BeatMinecraftTask` gates
"Going to Nether" behind every gather <=0, and `CollectFoodPriorityCalculator` returned 0.1 (weak but
positive) whenever food was UNREACHABLE and the reserve < foodUnits(140), keeping CollectFood selected
over the fall-through for ever. This is the unfinished half of G106 (which had already set
foodUnits=140 / minFoodUnits=120 as the "proceed with a buffer, don't hoard" values but never coded
the "proceed").

- **Fix (v0.95.20, shipped).** `CollectFoodPriorityCalculator` now takes `minFoodUnits`; on the
  unreachable-food branch it blocks the nether (0.1, keep exploring) only while the reserve is BELOW
  the floor, and stands down (`NEGATIVE_INFINITY`) once it is adequate (>= minFoodUnits) but the
  top-up is unreachable. Reachable food still tops up to foodUnits; emergency +inf and the low-reserve
  ramp still guard survival. One caller updated (`BeatMinecraftTask`).
- **Validated on `nether-reach` (natural terrain).** Before: stalled on food, no rung past `nether`.
  After: the bot proceeds -- flint, `nether` rung @133.6s, wood, then **"Building nether portal with
  obsidian -> Making obsidian by flooding lava"** -- the FIRST end-to-end exercise of the
  natural-terrain portal path past the gathers. hp stayed 20 (proceeded with an adequate buffer, not
  starving). `GAMER_SMOKE: PASS`, fps 27.
- **Next frontier it exposed (separate, not food, not placement).** The natural-terrain flood-gather
  then stalls "Approaching lava to flood": the chosen lava source is deep underground (y=27) directly
  below the bot at the surface (y=61) and the nav does not dig the ~34 blocks down to it. A
  nav/dig-to-a-deep-target problem -- the next pass. The portal BUILD is validated on the bench
  (isolated 8/8, full flood 6/6).

## 2026-09-19 — G108 portal: the flood ceiling FIXED at the core (v0.95.19), full flood 6/6

The remaining full-flood failure was root-caused to a single wrong line and fixed. Full obsidian
flood went 4/6 -> 6/6 on a fresh, uncontended stand; nav regression clean.

**Natural-terrain confirmation attempt (`gamer_smoke --from nether-reach`) -- CONFOUNDED, not a bot
result.** After the bench 6/6 I resumed the `nether-reach` checkpoint (run 1 at 22 min, bot at
700,37,838, diamond kit, water bucket, cobblestone, NO obsidian) on the survival server to watch the
portal build on natural terrain. The bot reached the `nether` rung at 156 s (gained obsidian +
flint&steel), then STALLED: `LADDER STALLED: no new rung for 150 s`. Live task read: `Collect 140.0
units of food -> Wander for Infinity / Exploring`, obsidian back to 0. This is NOT a portal-build
failure and NOT my fix -- the portal phase was never reached. It is CHECKLIST rule 4a3 ("the bot eats
the course it is graded on"): the checkpoint world is a 22-minute-old world whose animals run 1 had
already eaten, so on resume the bot needs 140 food units, finds none within reach, and
`CollectFoodPriorityCalculator.calculatePriority` returns 0.1 on the `Double.isInfinite(distance) &&
foodPotential < foodUnits` branch (line 59) -> weak-but-nonzero CollectFood -> wander for food that is
not there. `BeatMinecraftConfig.foodUnits = 140`, `minFoodUnits = 120`.
  - **What this does and does not establish.** It does NOT test the portal on natural terrain (the run
    never got there), and it is NOT evidence of a food-chain bug on a normal run (food is gathered en
    route when the terrain is not pre-depleted). A clean natural-terrain portal confirmation needs a
    FRESH world (the survival world persists and degrades between runs -- rule 4a3), not a resume of a
    depleted checkpoint. Tracked as the next pass: either wipe/regen the gamer world for a from-scratch
    run, or take a checkpoint whose surroundings still hold food. The portal BUILD itself is validated
    on the bench (isolated 8/8, full flood 6/6) and its fix shipped in v0.95.19.
  - **Latent (real, but not today's blocker): the food deadlock on depleted terrain.** If the bot ever
    holds < minFoodUnits and no food is reachable, line 59's 0.1 return wanders for food indefinitely
    instead of proceeding. That is a genuine "wander for infinity" fragility, but it only bites on
    already-stripped terrain; do not fix it on the confounded checkpoint -- reproduce it on a fresh run
    first (RULE ZERO/FIVE), or it will be tuned against an artifact.

- **Root (tungsten `BlockPlaceHelper.placementStand`).** The intermittent stall was always the same
  cell -- the first bottom-row frame cell (2358,-57,357) -- with the body standing in/beside it,
  "Wandering" or "Placing" for the whole window and never placing it (measured 246 s on one run). The
  drain trace named it: the drain PILLARED a ground cell (`pillarbase=... PILLAR(...)`, walking the
  body INTO the cell to jump-place) and `blockedByOwnBody` climbed to 5..28. It only pillared because
  `placementStand` returned null. Reading the source: `placementStand` ("where do I stand to place
  this") gated every candidate facing on `placementPlausible(target)`, which tests whether the block
  would intersect an ENTITY -- the player -- at its CURRENT position. Once the body drifts onto the
  target cell (routine for a bottom-row cell reached from the gather side, or after a wander lands the
  body on it), plausibility fails for every facing, `placementStand` returns null, `drainQueue` takes
  the PILLAR branch (`sideStand==null && standable(head)`), which walks the body FURTHER into the
  cell -- a self-sustaining jam.
- **Fix (v0.95.19, shipped).** Remove the `placementPlausible` gate from `placementStand`.
  Stand-finding is about a FUTURE position, so the body's current overlap is irrelevant; the real
  placement-plausibility check already runs in `drainQueue`'s main place loop, at the moment of
  placing, when the body is at the stand (`blockedByOwnBody++` there). `adjacentStand` already excludes
  the target cell and any stand whose head is the target, so the chosen stand never leaves the body in
  the cell it fills. Column-top cells that genuinely need pillaring are unaffected (their sides are
  open air, so `adjacentStand` still finds no stand). Measured: full flood 4/6 -> **6/6** (fresh
  client, clean bench; 3 of 6 teleported into the nether). Nav guard
  nav_break/nav_wall2/nav_bridge/nav_flat/nav_staircase/nav_descend **6/6** (the drain change does not
  regress movement-time building).
- **Two attempts measured and REVERTED (kept off), with reasons:**
  1. **The three wander-recovery flags** (`wanderSearchMustMove`, `wanderTargetFollowsTheGround`,
     `wanderSpiralCountsLegsNotTries`) -- to make the escape wander re-pick a reachable point instead
     of jamming ~60 s against the frame wall. Measured neutral-to-worse, and decisively: the body then
     THRASHED near the frame but the place still never landed. That proved the wander was a SYMPTOM,
     not the root -- the place-stall is upstream of it. Reverted; the real fix is `placementStand`.
  2. **The per-cell directed escape** in `ConstructNetherPortalObsidianTask` (a `TimerGame(10)` ->
     `GetToBlockTask(lastGoodPos)`). It never fired -- not on any passing run and not on the failing
     one -- because `PlaceBlockTask`'s own wander engaged first. Dead reactive code; removed.
- **Method note (RULE ZERO / instrument-affects-measurement).** A fix-arm reading of 3/6 was
  DISCARDED as confounded: it ran with a heavy per-tick block-probe instrument (~12 `docker exec`
  reads every wander tick) that starved the client during the exact phase under test. Removing the
  probe gave the clean 6/6. The instrumentation (`buildQueue()` drain-counter dump + per-cell block
  probe) was added to `nether_portal_test.py` to catch the trace, then reverted so it cannot skew a
  rate. Also confirmed: much of the earlier "~4/5" variance is the client fps AGEING over a long
  batch (early runs pass, late runs fail) and a competing `uctest-mc-tester2` container -- a bench
  artifact, per RULE ZERO/SEVEN, not a bot defect.

## 2026-09-19 — G108 portal: misdirection backstop (v0.95.18) + the recovery ceiling, precisely located

The full-flood ceiling was peeled one more layer. Two more roots fixed/attempted, and the TRUE
remaining blocker is now precisely located: stall DETECTION is comprehensive, stall RECOVERY is not.

- **Misdirection (v0.95.18, shipped).** Frame cells with no side stand fell to the build-queue PILLAR
  branch, which sends the body to balance on the 1-wide wall over the interior; it drifts, and
  PillarTask cast the frame obsidian into the DRIFTED column (verifying only against the body's own
  cell), landing off-plane at x-1/x+1/interior, which the re-gather then mined back until the lava
  lake drained. Fix (3a): a structure-block pillar is pinned to its target column
  (`PillarTask.startTo(ty,block,tx,tz)` + place gate refuses any off-column cell). Root-caused by a
  sub-agent trace; off-plane on BOTH sides = pillar drift, not a face bug.
- **Reverted, measured NET-NEGATIVE:**
  1. **Column-top scaffold extension (3b, `y>=origin+2`).** Adds the scaffold's OWN high edge cells
     (dy=2), which stall the same high-over-open-space way as the frame cells they were meant to help
     -- full flood 0/2. Back to `==origin+3`.
  2. **Per-cell parked-aim escape timer (Fix #2).** DETECTION was correct and fired reliably (a 12 s
     per-cell timer on the long-lived ConstructNetherPortal, immune to the re-arm churn that wipes
     PlaceBlockTask's own clocks). But its RECOVERY -- a `TimeoutWanderTask` -- FROZE: the bot bobbed
     in place ~180 s at a 1-block step west of the frame, "Wander for 4.0 / Exploring", never moving.
     Reverted: detection with no working recovery only trades a place-freeze for a wander-freeze.
  3. **Dropping the `drainDriving &&` guard on PlaceBlockTask's stall check.** Prematurely wandered a
     legit aim pause (drain not walking while the crosshair settles) and broke the clean build.
     Reverted; the guard stays.
- **THE REMAINING BLOCKER, precisely: stall RECOVERY is TOO SLOW (not absent).** Every detector now
  fires (shimmy grace with a fixed anchor; the parked-aim per-cell timer). The escape wander
  (`TimeoutWanderTask`) DOES eventually relocate a build-trapped body -- measured, the body BOBS in
  place ~60 s at a frame-stuck spot (feet y oscillating ~1 block, "Wander … Exploring", 0 blocks
  covered) and THEN breaks free and travels ~30 blocks away. So nav CAN path off the spot; it just
  jams on the wander's first spiral point (unreachable -- likely aimed ACROSS the frame, into the
  obsidian wall) for ~60 s before re-picking a reachable one. On a full flood with several high cells
  each costing a ~60 s escape, the run blows the window -> FAIL.
  - **Hunger REFUTED (2026-09-19):** a permanent saturation effect (foodLevel 20, verified) did NOT
    prevent the parked stall OR the slow escape -- so FoodChain is not the interrupter; the re-arm
    churn / parked aim is something else (Unstuck-chain suspected) and the escape slowness is
    independent of it.
  **Next pass (the ONE core fix to make):** make the escape FAST -- a DIRECTED move to a known-clear
  approach cell (not the random spiral that jams ~60 s against the frame), or shorten the wander's
  per-point jam timeout so it re-picks a reachable point in a few seconds, or a sturdier/wider stand
  so the body never gets trapped at the frame edge. Detection is done; only fast recovery remains.
  This is the nav+build seam NAVIGATION.md flags known-hard; the standing post-iron portal ceiling
  (isolated build 8/8; full flood ~3/4).

## 2026-09-19 (later) — G108 portal: shimmy-stall fix (v0.95.16) + full-flood cell-loss fix (v0.95.17)

Two released fixes closed the two dominant post-iron portal failures. The isolated build bench went to
8/8; the full flood's dominant intermittent failure (a lava-lake-draining churn) was root-caused and
fixed.

- **The shimmy stall (v0.95.16).** The remaining post-iron stall was a SHIMMY, not a freeze: a
  placement that could not reach its stand oscillated the body ~0.4 blocks/tick against the cell (75+ s
  under "Placing … at …", frame never completing). Two-part core fix: (1) `MovementProgressChecker`'s
  grace anchor was a FOLLOWING anchor with a 0.02-block per-tick bar, so a shimmy cleared it every tick
  and the grace never expired — replaced with a FIXED anchor + 1-block NET-distance bar, exposed as
  `stalledInPlace()`; (2) `PlaceBlockTask` wanders on `stalledInPlace()`, and RELEASES the build drain
  (`clearQueue` + `FastNavigator.stop()`) before the wander, because the wander and the drain both steer
  tungsten — two owners of one nav singleton had frozen the body for 50 s. Verified: GIVE_OBS build
  bench 8/8, two runs hit a shimmy and recovered in one wander; `mob_melee` combat regression clean
  (the shared-utility change does not regress combat).
- **The full-flood cell-loss (v0.95.17).** On the full flood the finished frame was being corrupted:
  `DestroyBlockTask`'s obstruction-clear (the branch that mines whatever the reach ray is stopped by
  when the target is briefly out of reach) is a RAW `CLICK_LEFT` swing that bypasses the
  planner/executor break-protection chain, and `canClear` only rejected air/fluid/unbreakable/
  self-floor. While removing the front scaffold, an out-of-reach scaffold cell left the ray stopped by
  the top-row FRAME obsidian (frame plane one block above the scaffold top, air in front), so the bot
  mined a finished frame cell (9 s obsidian dig) — the frame re-read incomplete, the re-gather drained
  the finite lava lake into "Searching for a lava lake … Wander for Infinity" (timeout). Root-caused by
  a sub-agent trace (file:line). Fix: `DestroyBlockTask.canClear` now refuses any block
  `shouldAvoidBreaking` rejects, the same check every other break path makes — the intended TARGET is
  still dug, only an OBSTRUCTION must respect protection. General fix (DestroyBlockTask backs beds,
  portals, protected zones). Full flood: baseline 3/4 (the fail was this cell-loss), Fix verified 2/2
  clean (scaffold removal completes, frame intact, obsidian stays 0).
- **Bench correctness (v0.95.16).** The portal bench scanned for a portal block within rad 10 of the
  BOT client-side; a freshly lit portal teleports the bot to the nether, unloading the overworld build
  chunk, so the near-bot scan found nothing and reported FAIL on a working portal. Now reads the bot's
  dimension server-side each poll — entering the nether is a definitive PASS.

## 2026-09-19 — G108 portal: the frame-PLACEMENT layer (columns build now; top-row middles + a re-gather churn remain)

Continuing the nether-portal build (the flood-gather + off-pool cornered frame from 228bac27 reaches
the frame build; the frame itself was flaky). A sub-agent read tungsten's placement pipeline end to
end and pinned the column-cell stall precisely; two minimal BlockPlaceHelper edits + a PlaceBlockTask
guard now build the whole bottom row and both columns cleanly (bench: 10/14, wanderHits=0). The
top-row MIDDLE cells (over the interior) and a re-gather churn are the remaining layer.

- **Root of the column stall (tungsten BlockPlaceHelper).** `drainQueue`'s pillar branch fires
  whenever the target is `standable` (air+air-above+solid-below). A portal column cell sits on the
  cell below it, so it is standable -- and the placer tried to CLIMB onto that lone 1-wide support in
  the wall plane, which never converges (the bot straddles it); meanwhile `placementStand` could not
  offer the natural stand (beside the support, one level down) because `adjacentStand` only searches
  `dy=-1` when `allowSameLevel`, false when the target has air above.
- **Fix (verified: columns build, no regression).** (1) `placementStand` (BlockPlaceHelper): allow
  `dy=-1` for the DOWN face -- `adjacentStand(..., allowSameLevel || facing == Direction.DOWN)` -- so a
  top-face placement can be made from beside the support at the support's level; the head/self guards
  still hold. (2) `drainQueue` pillar branch: compute the side stand first and only pillar when there
  is none (`sideStand == null && standable(head)`), so a cell with a reachable side stand
  walks-and-places instead of failing to climb. (3) `PlaceBlockTask`: reset the progress clock while
  the build drain owns the body (FastNavigator/PillarTask/BlockPathWalker active) -- the same
  "movement/dig is progress" carve-out MineAndCollectTask/DestroyBlockTask already have -- so the
  reactive wander no longer yanks the bot off mid-climb. Bench: bottom row + both columns build with
  no scaffold, 1 cobblestone used, wanderHits=0 (was: 42 s wandering on one cell, 0 portal).
- **Regression: none.** BlockPlaceHelper backs ALL placement, so the change is deliberately narrow --
  `dy=-1` only for DOWN faces, side-stand preferred over pillar only when it exists, the raytrace
  re-verifies every placement. Nav suite re-run as the guard: 14/14 (nav_bridge INVALIDed once on a
  client-fall flake, PASSed clean on the fresh-client retry; nav_break and all others green).
- **REMAINING (next layer), sharpened 2026-09-19 on a CLEAN single-bench run (flood_v3):**
  1. **A placement-MISDIRECTION churn stalls the full flood->build path before the top row even
     starts.** On the full OBS flood run the columns do NOT finish: obsidian keeps landing BESIDE the
     frame plane (e.g. at 2357/2359,-57,358 and at the interior origin 2358,-56,358, x off the x=2358
     frame) and the re-gather then mines it back, so the bot cycles "place left-column-top /
     Mine And Collect / Destroy nearby obsidian" for minutes and never reaches the top row (bench
     flood_v3: 120 s stuck on 2358,-54,357, obs oscillating 7<->8, 0 portal). This is NOT interference
     (single bench, all other bench pythons killed and verified 0) and NOT the interior scaffold
     (cobblestone, unused: cobble=64) -- it is real, and its ROOT is unidentified. It did NOT appear on
     the GIVE_OBS isolation (giveobs4: columns built 10/14 clean) -- so it is specific to the full
     gather+build path (some flood/gather world- or inventory-state interaction, or a placer misplace
     that only the full path hits). NEEDS per-placement instrumentation: log which cell each
     PlaceBlockTask / drainQueue placement actually fills vs its target.
  2. **The top-row MIDDLE cells** (over the interior, no support below, not standable) still need a
     temporary interior scaffold platform to stand-place from, removed by the interior-clear before
     lighting. An interior scaffold-column attempt (isInteriorColumnCell + PlaceStructureBlockTask
     under mid-air frame cells) was written and REVERTED this pass -- the build never reached the top
     row (blocked by churn #1), so it could not be evaluated (cobble stayed 64).
- **HARNESS lessons (2026-09-19):** run only ONE portal bench at a time -- TaskStop on the launcher
  bash does NOT kill the detached `python nether_portal_test.py &` child, so benches accumulate and
  fight over the client (@stop/@build), which corrupted several earlier diagnoses; kill the python
  explicitly (PowerShell `Stop-Process` on the matching CommandLine). `docker cp` needs a
  Windows-style source path (`C:/...`) even under MSYS_NO_PATHCONV=1.
- **NET this pass:** flood gather robust + off-pool cornered frame + the placer fix (columns build,
  nav 14/14) are committed (228bac27, 220560fe). The full flood->lit portal is NOT green -- churn #1
  is the primary blocker and is the first thing the next dedicated pass must instrument and fix.

## 2026-09-18 (cont.) — G108 nether portal: FLOOD-LAVA gather redesign + off-pool siting + cornered frame (groundwork; frame-PLACE layer remains)

Big multi-layer pass on the standing post-iron ceiling (the nether-portal BUILD). NOT released: the
gather half is now robust and the frame STRUCTURE is fixed, but a residual `PlaceBlockTask`
positioning flakiness on some frame cells keeps the flat-stand bench from a CONSISTENT green. The
full path DID complete once end to end (bench4: 6 real NETHER_PORTAL blocks), so every layer works;
the last one is just not yet reliable.

- **Flood-lava-lake obsidian gather (the core win).** Replaced the per-block lava-bucket CAST
  (`PlaceObsidianBucketTask`: a 10-block mould + placed lava + water, per obsidian) -- the shared
  fragility of both portal methods -- with `PlaceObsidianFloodTask`: find a surface lava SOURCE with
  a solid rim, place ONE water source on the rim's top face, the flowing water sheets across the pool
  and turns every lava source it reaches to obsidian, then reclaim the one water source. Validated
  the mechanic on the stand first (one edge placement floods a 5x5 pool = 25/25 obsidian; the source
  stays put so the reclaim is exact -- the bucket cycles, no iron spent). `CollectObsidianTask` now
  floods+mines instead of casting. No mid-air mould, many obsidian per placement. VERIFIED: the bot
  floods, converts and gathers reliably (bench1: 0->10 obsidian, zero wander).
- **Site the frame BEFORE gathering, on pristine ground.** The task used to gather ALL obsidian
  first and only then look for a build site -- so it always sited the frame standing in the pocked,
  half-mined pool it had just flooded (bottom cells over mined holes -> mid-air scaffold; remaining
  pool obsidian in the frame region -> the gather and the build fought over the same blocks in an
  endless place/mine churn, 0 portal). Reordered `ConstructNetherPortalObsidianTask.onTick` to pick
  the origin FIRST (pristine ground), then gather (flood a lake AWAY from it), then return and build.
  Site checks also reject OBSIDIAN in the floor/frame region (not just lava/water). VERIFIED: the
  frame now lands on clean ground beside the pool (bench8/giveobs2: origin x=2358, pool x=2364-2368).
- **Full 14-block CORNERED frame, built bottom-up (eliminates the mid-air scaffold).** The old
  `PORTAL_FRAME` was the 10-block minimal ring (no corners): column bases sat one above the absent
  corners and the top row sat over the interior, so both were placed MID-AIR via
  `PlaceStructureBlockTask`, whose reactive `TimeoutWander` stalls (bench8: 42 s wandering on one top
  cell). Changed to the 14-cell rectangle WITH corners, ordered bottom-row -> both columns bottom-up
  -> top row, so every cell rests on the block directly below (or beside an already-placed
  neighbour). VERIFIED partial: the bottom row + left column + right-column base build cleanly with
  NO scaffold (1 cobblestone used, vs the old scaffold churn). Four extra obsidian are free (flood
  makes 20+); a cornered portal lights identically.
- **REMAINING (the next layer): `PlaceBlockTask` positioning flakiness.** With the cornered frame,
  ~8/14 cells place cleanly, then it stalls PLACING a right-column cell (e.g. 2358,-55,360) whose
  support (2358,-56,360) is obsidian and present -- so it is NOT a mid-air/support problem, it is
  PlaceBlockTask unable to position/aim to place against the top face from the far side of the wall
  it is building (bot straddling the frame plane at x=2358.5). Reactive "Wander for 5" then loops.
  This placement-layer issue is pre-existing (it also bit the old 10-cell frame's cells) and is the
  dedicated next pass: give the frame builder a proper "stand beside the plane, look at the support
  face" placement, or a build order that never puts a built wall between the bot and the next cell.
- **Bench hardened + a real bug it hid.** `deploy/runner/nether_portal_test.py`: the portal scan
  started at y=FLOOR_Y+2 and scanned only 6 up, so it MISSED a real portal whose interior sits at
  FLOOR_Y+1 (bench4 was a FALSE "no portal" -- ground truth was 6 NETHER_PORTAL blocks). Fixed to
  scan from FLOOR_Y-1, 9 up. Also: keep the diamond pickaxe in hand during the MINE (the flood
  leaves a bucket in hand and the flat stand's rcon-GIVEN pickaxe is not auto-equipped -- a pure
  harness artifact the bench comments already document; a real run auto-equips its self-crafted
  pickaxe); GIVE_OBS now hands 16 obsidian for the 14-cell frame. A one-time wide (±40) cleanup of
  stray obsidian from earlier runs is needed if the bench world gets polluted (leftover obsidian
  outside the ±18 per-run wipe distracts the gather).
- **Verification reality:** the flat stand is faithful for the flood mechanic, the frame STRUCTURE
  and the light; a real @gamer run remains the final confirmation for chaotic terrain. The
  frame-PLACE flakiness must be greened (bench + @gamer) before any release.

## 2026-09-18 (cont.) — Finding B FIXED (v0.95.12): the post-death unreachable-canopy reach wedge

- **Reproduced deterministically**, `deploy/runner/canopy_log_reach_test.py`. A dark-oak canopy log
  6 up, boxed in leaves (no ground cell has line of sight to it), empty post-respawn pocket (nothing
  to pillar with), no reachable log in scan range, the bot walking in from 20 W: it stopped ~4
  blocks short and cycled "reaching X / reach route gave up / marking it unreachable" for the whole
  window, never excluding the log, never exploring (baseline FAIL, wandered=False, pdRouteRefused
  climbing but nothing excluded). This is the post-death recovery wedge from the cave-mob report
  (the bot respawned under a canopy and could not re-gather wood; Finding B, now closed).
- **Root cause (two layers).** (1) The mining chooser (`MineAndCollectTask.getClosest`) picks the
  nearest non-blacklisted breakable block with NO reachability check -- `WorldHelper.canReach(pos)`
  is just `!isUnreachable(pos)` -- so an unreachable elevated log is a valid target until tried and
  condemned. (2) G74's reach-route give-up DID fire ("given up 3 times in a row -- marking it
  unreachable") but called `requestBlockUnreachable`, whose default allows FOUR failures, so one
  verdict counted as failure 1 of 5; the chooser (pure nearest-distance) then picked a sibling log
  and the canopy was never excluded. Even made per-block-decisive it lost to the 45 s cool-off:
  grinding a 3x3 canopy one log at a time (~3 give-ups each) took longer than the cool-off, so the
  first logs returned to the pool before the last were excluded and the candidate list never emptied.
- **Fix (core, minimal, G63-respecting).** `AbstractObjectBlacklist.blackListNow` -- a DECISIVE
  verdict that excludes the target at once but stays EVIDENCE: cools off after 45 s and is retried,
  a materially closer approach / better tool still restores it, never marked `deliberate`
  (permanent); the price (`penaltyBlocks`, from `totalFailures`) counts real attempts only. Exposed
  as `BlockScanner.requestBlockUnreachableNow`; and `requestAreaUnreachableNow(pos, r)` condemns the
  local SAME-TYPE cluster together (unreachability is a local geometric fact), so an unreachable
  patch is left after ONE verdict. G74's reach route uses the area form (radius 3); the dig route (a
  movement goal into rock) stays per-block so a tunnel's rock is never condemned.
- **Not a geometric reach pre-filter** -- that is the `chop_canopy` regression zone (an over-strict
  reach filter once rejected EVERY candidate, scan=0/0/18384). The fix keeps "try, fail, exclude,
  retry"; it only makes G74's already-strong verdict take effect at once, over the local patch.
- **Verified on the stand (v0.95.12).** Canopy wedge: before FAIL (wedged whole window) -> after
  PASS (condemned the cluster in one verdict, `pdRouteRefused=2`, `wandered=True`, then explored).
  No regression: canopy recovery variant (took the reachable column, 13 s) PASS, tree_reach (log
  from the ground, no pillar) PASS, canopy_drop (towered to the drop, flaws=none) PASS,
  self_floor_dig (cobblestone 3.2 s) PASS, shaft_exit (out of the shaft 6.6 s) PASS. Built jar
  javap-confirmed to carry `blackListNow`/`requestBlockUnreachableNow`/`requestAreaUnreachableNow`.

## 2026-09-18 (cont.2) — G108 nether portal: deep re-diagnosis + clean-siting core fix (groundwork)

Re-entered the portal (the standing post-iron ceiling) and reproduced the failure deterministically
on the flat stand (`nether_portal_test.py` OBS gathering path: force-equipped diamond pickaxe, a 5x5
lava lake, buckets, cobblestone). Findings, in the order they surfaced (each downstream of the last):

1. **Obsidian gathering by cast WORKS and accumulates** -- `wanderHits=0`, 6 obsidian @100s, 8 @150s,
   10 @190s, cycling cast->mine near the lake. The old "fundamentally fragile gathering" read was a
   cramped-terrain / pre-`faf41b61` artifact; with the footprint fix + resources it gathers fine.
2. **The frame was SITED IN THE CAST PIT (root, FIXED).** Casting digs a chaotic, lava-adjacent hole
   and leaves the body enclosed; `getBuildableAreaNearby` accepted ANY 3x6x6 that was merely
   placeable-or-breakable -- which a dug pit's air cells satisfy -- so `origin` landed in the pit and
   every frame placement failed "Enclosed -- escaping via FastPlanner", cycling gather<->place 300s+
   with 10 obsidian in the pack and the frame positions all air. Fix: `getBuildableAreaNearby` now
   scans OUTWARD (rings r=2..12) for a genuinely clean, flat, OPEN pad (`isCleanFlatBuildSite`: solid
   floor under the whole footprint, clear air for the frame envelope, no lava/water) and builds off
   the pit. VERIFIED (clean-wiped stand, given obsidian): the bot walks to a clean pad and PLACES the
   frame there -- obsidian consumed 12 -> 4 (8 placed), NO "Enclosed -- escaping" wedge, which the old
   pit-accepting code never reached. The siting root is fixed.
3. **Bucket churn -> "Mine raw_iron -> Wander" (bench artifact, worked around).** The ground cast
   empties a bucket it cannot always reclaim; 4 buckets ran out mid-gather and, with no iron on the
   flat stand, the bot wandered for iron to craft one. Bench now gives 16. A real run has iron.
4. **LAVA DEATH during the cast (open root, upstream).** A later run died near the lake mid-build and
   respawned at world spawn -- the cast approach works right next to lava and occasionally steps in.
   It also corrupted the stand (dead/relocated bot, failed give-setup), which is exactly why the
   flat-stand verification is unreliable for the portal (death corruption + give races + false greens
   from leftover portals -- one was caught: "Done constructing" fired on a leftover NETHER_PORTAL at
   origin.up() with 0 obsidian consumed; the clean-wiped re-run above is the trustworthy one).

Net: the frame-in-pit siting wedge is root-caused and FIXED (clean-siting, verified the frame builds
on a clean pad). The portal still does not green end-to-end because the frame build's scaffolding
needs more obsidian than 10, sending the bot back to the cast, whose remaining roots are the
lava-death hazard and bucket churn (per-block cast). This is the multi-root redesign the earlier
notes predicted; clean-siting is one verified root removed, committed as groundwork (cf. faf41b61,
0.95.11 "groundwork, not a complete fix").

Follow-up same day (fallback + trustworthy bench + BUILD-half evidence):
- **Siting made robust (fallback).** Pass-1 clean-flat siting could be over-strict -- the area search
  wanders the body while looking, and with no pristine pad nearby it drifted off "Looking for
  portalable area" for ever. `getBuildableAreaNearby` now does TWO passes: prefer a clean-flat pad,
  else a DECENT site (`isDecentBuildSite`: solid floor under the footprint + open column above the
  origin + no lava/water) -- rejects the enclosed pit but never wanders without end (strictly better
  than the old lenient check).
- **Scaffold is COBBLESTONE, not obsidian** (`PlaceStructureBlockTask` places a throwaway) -- so the
  frame needs exactly ~10 obsidian, not a hidden overhead. (Corrects the earlier scaffolding-overhead
  guess.)
- **Bench hardened into a trustworthy instrument** (`nether_portal_test.py`): a WIDE +/-18 wipe clears
  leftover portals/obsidian (a leftover NETHER_PORTAL at origin.up() had produced a false "Done
  constructing" with 0 obsidian consumed); setup is VERIFIED (bot in-game + obsidian/flint&steel/
  water-bucket actually applied, re-give a few times -- a post-death respawn screen no-ops `give`);
  and 'done' is confirmed against an ACTUAL nether_portal block via a fast in-process client scan
  CENTRED ON THE BOT (the frame sites outward, so a scene-centred scan missed it).
- **BUILD half works, but not yet consistently.** With all prereqs present the bot builds the frame on
  a clean pad; one clean run consumed all 12 obsidian and reached "Done constructing nether portal".
  Other runs re-enter the gather mid-build and the obsidian<->frame interaction churns (a placed frame
  obsidian gets re-collected / re-needed). So the BUILD half is proven possible but flaky.
- **World-read frame check (partial fix for the re-entry churn).** The re-gather-mid-build was traced
  to `ConstructNetherPortalObsidianTask` deciding needed frame cells via the event-driven block
  SCANNER, which lags a just-placed block -> freshly-placed frame obsidian read as "still needed" ->
  re-gather -> CollectObsidianTask mined the placed frame. Now it reads the WORLD
  (`getBlockState == OBSIDIAN`), immediate and exact. This removes the scanner-lag churn specifically.
- **BUILD half still not CONSISTENTLY green (open).** Across ~15 stand runs the outcome varies: one
  clean build to "Done constructing" (obsidian 12->0), others stick on a single placement
  (surroundedByAir scaffolding on a clean/decent pad) or re-gather. Siting variance (clean-flat vs the
  decent fallback) + the flat stand's flakiness (death corruption, give races, chunk-unload on scans)
  make it hard to land a repeatable green here.
- NEXT portal roots: (1) BUILD-half placement/scaffolding consistency on a clean/decent pad,
  (2) the GATHER half's cast lava-death hazard + bucket churn. Best measured on a real @gamer run --
  the flat stand's cast keeps corrupting via death. clean-siting + fallback + world-read + the hardened
  bench are committed groundwork toward a dedicated multi-session portal pass.

## 2026-09-18 — post-iron ceiling from two playthroughs; G105 freeze fixed; G93 shipped

- **G105 validated + shipped (v0.95.7):** day-locked validation from the freeze checkpoint
  (cp0918-1030-t494) — 0 position stalls (vs 200 s frozen without the fix), bot mined deep
  y65→y11, +290 items, GAMER_SMOKE PASS. §4y frame review: the two flat windows are furnace
  smelting (iron ×7, gold ×5), verified from extracted frames — no hidden holds. Nav suite 14/14.
  Operator sent the report (TG 9007) + the sped-up video (9008).
- **G94 closed — NOT reproducible:** wrote `snow_stair_test.py` (the specified staircase, faithful
  to the `hop[0,-1,1]` `[grass|snow|air]` geometry) and ran SNOW_LAYERS=1/3/8 — all descend in
  4.4–7.8 s. The snow step-down is handled now (incidental fix from the nav work since 2026-09-16).
- **Next probe:** a 25-min day-locked run from run5-end (all fixes in) to find the next post-iron
  ceiling now that the hard freeze is gone — does it reach the nether?


- **Method (operator's loop):** resumed the post-iron checkpoint `run5-end` (iron tools) and ran
  forward. `run6` (night) died to a creeper and lost the whole inventory to world spawn (the
  deferred night track). Built a new `--daylock` lever (freeze a resumed run at day, clear
  hostiles) to isolate the in-scope daytime ceiling; `run6day` (day-locked) then surfaced the real
  non-night blockers.
- **G105 (HARD freeze, FIXED — v0.95.7):** day-locked, the bot froze 200 s at (692,59,865) trying
  to mine a block 1.4 away, "Waiting for the approach to finish placing." `DestroyBlockTask` shields
  a builder (bridge/pillar/place) unconditionally every tick — resets the stall checkers
  (`:426`) and yields the whole task (`:466`) while `builderOwnsInputs()`. A place/pillar that
  CYCLES without advancing (plan→fail→re-plan→repeat; navPillarRuns=76, placeClicked=0,
  dbBuilderYield=1405, body still) thus suppressed the give-up for ever. Fix: a BREAK stays shielded
  (a dig-down is still by design), but a PLACE/PILLAR is shielded only while `_ticksSinceMoved <
  BUILD_HELD_MAX=200`; once wedged the shield drops (`dbBuildHeldStuck++`) and the existing give-up
  (`:526`, `_moveChecker.check` now fails) condemns the target and reroutes. NOT reproducible from a
  world checkpoint (in-memory transient — restoring cp0918-1030-t494 ran fine, bucket rung @156 s),
  so verified by no-regression (nav suite) + the day-locked run no longer freezing, not a repro.
- **G106 (food over-priority) — FIXED + SHIPPED v0.95.8:** `CollectFoodPriorityCalculator` ×50'd ALL
  food near a hay bale (priority hit 1293), and food gated the nether fall-through
  (BeatMinecraftTask:2412 needs every gather ≤0; foodUnits=220 ≈ 27 meats kept a 25-min probe in
  overworld prep). Fix: hay multiplier 50→7, foodUnits 220→140 (minFoodUnits 120); the bot
  re-collects below the target and needsEmergencyFood ramps +inf when low, so the buffer is topped
  up, not risked. Verified day-locked from cp0918-1030-t494: hp held 20 (bot eats, NO starvation),
  food not dominating (iron-gather at priority 3.86), reached the bucket rung and descended to
  DIAMOND DEPTH (y-9, +160 items). §4y frames: the flat windows are furnace smelting, no holds.
- **G93 (shipped v0.95.6):** see below — a table craft no longer digs across the world for a dropped
  copy.

## 2026-09-17/18 — the bread deadlock fixed from a checkpoint (G103), G104 found

- **G103 (the 46-minute bread/crafting-table loop)** root-caused by resuming the `loop-bread`
  checkpoint and reading the live task tree: `BeatMinecraftTask`'s "Picking up the crafting table
  while we are at it" (line 1688) returns `MineAndCollectTask(CRAFTING_TABLE)` every tick when the
  bot lacks a table item and a placed one is on the way, preempting the flow that would craft a
  fresh table; the break false-fails on the survival world, so the reclaim never gets the table and
  the run churns ("Blacklisting extra crafting table" ×971) with four logs in the pack. Fix: reclaim
  only when the pack cannot cheaply make a table (four planks / a log), and drop the reclaim flag so
  it stops re-arming — `DoStuffInContainerTask` crafts a fresh one (G80's "a table is four planks").
  Verified from the checkpoint (the point of having one): before, 971 blacklists over the loop;
  after, two 5-minute resumes read 0, and the bot forages + mines with items 63 → 104. Commit
  `fce9331b`. (Correction: the "reclaim=0" figure first used was invalid — the reclaim's debug-state
  string is not written to `latest.log`; the valid log-based metric is `blacklistExtra`, 971 → 0.)
- **G104 (found by the regression run, not a regression of G103):** a fresh opening reached stone
  tools at 66 s then spent eight minutes on a stone-sword craft frozen at (885,62,648),
  `cb=0/1/53532/0` (the block scanner rejecting every cobblestone candidate), self-clearing at
  t=554. Shield bench passes, so the craft-grid carousel itself is fine; this is a survival-world
  interaction of the same family, occasional and self-resolving. Recorded, `run4-end` near it.
- Checkpoint tooling now the standard debug loop: `checkpoint.py save/restore/list`,
  `gamer_smoke.py --from NAME` / `--checkpoint-every N` / `--save-end`. On disk: post-run2,
  cp0916-2241-t900/t1849/t2800, run3-end, loop-bread, run4-end.

## 2026-09-18 — G100 fixed (dig up for air), closed-loop run

- **G100 (drowned mining diamonds at y=0):** `GetToAirTask` only searched for the nearest EXISTING
  reachable air; in a stone-capped flooded pocket there is none, so it did nothing and the bot
  drowned. Fix: while submerged, scan the column upward and DIG the first solid cap over the water
  (a `DestroyBlockTask` aimed up), climbing to the surface a block at a time; bedrock/sideways-only
  falls back to the old lateral search. Commit 4d215874, release 0.95.5.
- Verified from a purpose-built bench, `air_pocket_test.py` — and the bench itself taught two
  lessons (both now guarded): the survival chain only ticks while AltoClef is RUNNING, so the
  faithful test runs `@gamer` (an idle `@stop` bench never fires the air-seek and looked like the
  fix was dead — onTick=0); and a drown+respawn reads as "reached air" unless arrival is checked
  near the shaft. Under `@gamer` the bot dug both cap blocks (dig=2, cap computed at the lid) and
  surfaced with 16/20 hp in ~14 s; before, it drowned every time. Diagnostic method: the debug-state
  string ("Reaching breathable air") is NOT logged, so a real `Debug.logMessage` was needed to
  measure the dig firing — the same "grep the log for a debug-state string" trap paid for in G92/G103.
- Regression: nav_water PASS (baseline); the change is isolated to the submerged-no-air branch, which
  no nav course exercises. Full nav suite run as confirmation (nav_gaps hit its known void-fall flake,
  retried) — nav_water PASS and every gate course green through 9/14 (only nav_gaps's known void-fall flake, which retries); confirms the submerged-only change is regression-free.
- **G93 (a buried pickaxe DROP beat crafting one):** FIXED same day, v0.95.6, commit 1b2ff693.
  `CraftInTableTask` inherited `ResourceTask`'s pickup-before-craft with the default
  `getPickupRange()==-1` (unlimited): `ResourceTask.onTick` (line 184) returns a
  `PickupDroppedItemTask` for ANY drop of the craft target whenever `range < 0`, no cost check, so a
  copy buried ten blocks down outranked a craft from planks in hand (the third 60-min run opened with
  seven minutes of digging for it). Its sibling `CraftInInventoryTask` already returns 0 there ("this
  shouldnt pickup items"); the table craft never got the override. Added it — a table craft crafts, it
  does not detour to scavenge a distant/buried copy; ordinary pickup paths still collect drops when a
  pickup is the goal. Verified `deploy/runner/buried_drop_vs_craft_test.py` PASS: two logs + two sticks
  in the pack, a `wooden_pickaxe` dropped in a sealed pocket 8 blocks down, `@get wooden_pickaxe 1` →
  crafted in 7.9s, min_y=-60 (surface, floor top -61), never descended. Same build: full nav suite
  14/14 PASS (0 gate failures, 0 invalid) + G100 air bench PASS (dig=2, surfaced) — no regression.
- Next targets: G90 (tunnel per-cell exec + pricing), G94 (snow-step), G96 (reach:armed nav=false
  shimmy). G101 (death = base loss) tied to the deferred night track.

## Current playthrough status

- Goal remains a complete natural `@gamer` playthrough, with visual observation and regression tests. Nether/End and full completion are not validated.
- Original one-high opening/low-ceiling ascent remains fixed: latest audit 2/2, all three head obstructions planned and mined, grounded arrival, 20 HP. Adjacent navigation 7/7 passed.
- Submerged/air recovery is committed and validated after merging origin/main2f027a41. The current retreat-region change has completed its controlled and adjacent audits.
- The latest natural run preserved diamond tools and armor17, acquired ignition materials and advanced through caves, then starved to10HP while prioritizing optional resources over unknown food. The client is disconnected alive; no world restoration, inventory injection or time change.
- Newest verified backup: workspace outputs/natural-checkpoints/20260916-034354-food-pursuit-stop.tar.gz,57,272,367bytes,80entries,SHA25665ddbdf2b3e87049f1d9ece16ff8cda57800858b1944fa4a2c59f5d929938f93. Natural client remains disconnected at(-10.5,43,-389.6),10HP,armor17.
- Cave food exploration and false world-change placement bans now pass controlled and adjacent audits; natural validation is next. Open risks include intermittent bridge/physics jump falls, multi-mob combat, planned deep descents, partial-water/slab transitions and full-game completion. One bridge failure in the intermediate audit remains recorded; the water patch does not claim to repair it.
- Push and Telegram publication remain pending the existing target-specific confirmations. No retry or alternate route.

## 2026-09-15 — Publish navigator results on the client thread

### Investigate
- Crash report: `BlockPathWalker.tickBFS` line523 reads path.size after the route was nulled concurrently. Docker events show normal container exit0/restart, not an OOM event. The application emitted a fatal exception first.
- The same-second log shows FastNavigator-plan entering “walking dead-ends (61.0 -> 58.0) -> physics owns the rest”, while Render thread starts a two-waypoint walk. That worker branch calls BlockPathWalker.stop directly.
- Full plan-result handling currently writes several navigator fields and rendering plans from the worker. It only checks active, so a result from an old route can also publish after stop/start reactivates the navigator. Baritone PathingBehavior serializes calculation publication and path ticks with pathPlanLock.
- Controlled old-jar reproduction: pause the client at a bounded barrier, start a short enclosed corridor route, wait for the actual worker to finish. The walker became inactive and the physics handoff counter increased1 while the client remained paused. This demonstrates the cross-thread mutation without crashing the client. First probe attempt had a Vec3d argument-type error and is not a pass.
- Artifacts: workspace outputs/walker-publication/baseline-probe.json and natural-crash.txt. All six natural run clips decoded.

### Plan
- Keep calculation on the worker; apply the complete result on the client thread so handoffs cannot interrupt a walker tick.
- Identify each calculation with a generation invalidated by cancellation. Discard queued stale results without clearing a newer calculation's planning flag.
- Validate real worker completion while the client is paused, then application after release. Retarget while old publication is queued and require its rejection. Repeat, then broad navigation/mining/placement and original one-high regression before resuming the natural world.

### Implement
- FastNavigator result handling extracted unchanged into applyPlan; worker queues publication through MinecraftClient.execute. Generation and world checks reject stale results. Applied/discarded counters are read by the new fixture. No extra null check in BlockPathWalker masks the race.
- New walker_publication_test uses a client barrier with a ten-second automatic timeout, a real short partial plan, and optional retarget. Fixture setup and restoration are bounded; it does not require a spontaneous crash to validate the handoff.
- Tungsten clean build and deployment succeeded; fixed-fixture and adjacent results are recorded below.

- First fixed publication test passed: no handoff while the client was paused, one applied handoff after release. Retarget test publication-2 FAILED: the old result was discarded, but stop() had never cleared planning, so start() could not launch the replacement and the stale result no longer cleared it. Added planning=false to cancellation; generation checks keep old completions from clearing newer work. This was a real implementation failure, not a fixture mismatch. Clean rebuild/deploy succeeded; the corrected matrix is recorded below. Final natural clip6 decoded successfully.

- Corrected binary passed 6/6 controlled publications: three ordinary handoffs and three retargets. All kept the walker active and handoff/application counters unchanged while the client was paused; each applied a real handoff after release. Retargets additionally discarded the queued old calculation. All six videos decoded. The broader navigation audit and matched follow-up are recorded below.


### Adjacent audit and newly isolated work
- The initial full nav audit was interrupted during nav_cliff after flat/staircase/descend passed. Steep failed twice at the same lip; gaps fell twice in each of the initial and refreshed runs. All five completed course videos decoded; the interrupted cliff recording was saved separately.
- A clean HEAD828f5a81 baseline and restored publication build were both run through flat, staircase, steep, gaps in the same order. Both passed flat/staircase/gaps and failed steep twice at (6.683,-60,0.5). Baseline gaps had no falls at23.2FPS; restored gaps had no falls at27.6FPS. Earlier publication-build falls remain recorded. No fall-rate improvement or regression is established; some flat/staircase FPS differed, so no timing comparison is claimed.
- Baseline build/deploy logs: /private/tmp/publication-baseline-{build,deploy}.log. Restored publication build/deploy: /private/tmp/publication-restored-{build,deploy}.log. Both clean builds and nested-jar checks succeeded. Current source and deployed binary contain the publication fix again.
- NavCliff had an invalid approach: its start pad stood four blocks below its first platform, without building materials. The fixture now derives both surfaces from start_y; a geometry check confirms both at y-49 and feet at y-48. Live corrected-course audit passed:21.2s,29FPS,20HP,no falls or freezes.
- Harness reporting incorrectly called an arena fall at26FPS a below14FPS result. Added invalid_reason, preserved the arena reason, and removed unsupported load/wear attribution from retries and summary. Replayed the production judging/reporting block with recorded fall samples, low-FPS samples, and an ordinary healthy failure: all three reported the appropriate cause.
- Next root cause, not yet fixed: FastNavigator returns immediately for every result shorter than two waypoints, including an incomplete dead end. Real client-thread FastPlanner.plan at nav_steep's (6,-60,0) toward (22,-54,0) returns one waypoint, complete=false, no physics flag. A bounded worker fixture with a one-cell corridor also applies that result (applied292→293) without any handoff. This differs from a complete one-cell answer where the goal is already satisfied. Fixture now supports --one-cell and --goal-here controls. The short-incomplete fix is pending; do not claim steep repaired.


### Publication fix accepted locally
- Original one-high ascent audit passed2/2, with all three head blocks planned/mined and grounded arrival at20HP. The video decoded and was visually inspected and shown locally.
- Hungry mining ambush passed:6 cobblestone,20HP throughout,145 mining samples,zero sword interruptions during continuous mining. Threat/target entities were gone; video decoded and final image inspected.
- Completed one-cell control applies the result without physics handoff, as required. Incomplete one-cell baseline and retarget controls both reproduce the missing handoff after successful result publication. These follow-up tests establish an existing bug; they are not a claim that it is repaired.
- All matched-prefix videos and the corrected-cliff video decoded. Harness-only fixes committed as58f9254e. The publication race fix is ready for a separate local commit, followed immediately by the short-incomplete-result pass. Full-game completion and general parkour reliability remain open.


## 2026-09-15 — Preserve incomplete one-cell results

### Investigate
- Publication fix committed as23d87137. Both original and restored binaries stalled twice on nav_steep at the same lip; explicit planner and worker probes above show the incomplete one-waypoint result is being discarded.
- Completion control passed before this change: one-cell complete result applied without any dead-end handoff. Retargeted incomplete baseline also reproduced the loss after rejecting its stale predecessor.

### Plan
- Return early for empty results and completed single-cell results. Feed an incomplete single-cell result into the existing incomplete-route handler, preserving its below-goal policy and physics ownership.
- Require actual steep-course passage, repeated worker/retarget and completion controls, adjacent navigation, and original clearance regression. Do not confuse a handoff with successful traversal.

### Implement
- FastNavigator now distinguishes complete from incomplete one-cell results. Clean build and verified deploy succeeded (/private/tmp/one-cell-build.log and one-cell-deploy.log).
- Recorded nav_steep trials and adjacent acceptance results are below.

- Actual nav_steep traversal passed3/3 at20.8/25.3/22.8FPS, without falls or freezes. First results now log a real physics handoff and arrival. The runner reuses a repeated course's media path; second/third clips were copied separately, decoded, and the second was visually inspected. Do not claim three distinct retained videos.
- Controlled matrix passed8/8: three incomplete one-cell publications, three retargeted ones (old result discarded), two completed-goal controls (no dead-end handoff). All eight separate videos decoded; control6 image inspected. These barrier tests establish result handling, not traversal rate.
- Adjacent audit passed7/7: flat/staircase/descend/water/break/wall2/bridge,28.0–29.5FPS,no invalid runs. Original one_high passed2/2 with three head blocks planned/mined,grounded arrival,20HP. All eight audit videos decoded; bridge and clearance frames visually inspected.
- Fresh natural-world backup before resume: workspace outputs/natural-checkpoints/20260915-185403-post-crash.tar.gz,56,063,656bytes,80tar entries,SHA256 9bfb283908e5f7537730b021772ba06abe6a8ef52e0e198afa03849d300873dc. Zero natural-server players confirmed, save-off/flush/archive/save-on with finally restoration. No world restore. Fetch completed with no incoming origin commits.

- Short-incomplete fix accepted for local commit. Next action: resume the preserved natural world with a new observer and inspect live progress, especially climbing, food acquisition and route handoffs. No whole-game completion claim.


## 2026-09-15 — Pillaring over interactive supports

### Investigate
- Natural survival-route-handoffs ran538.8s before explicit interruption/disconnect; all five clips decoded. It recovered a stone pick, climbed from y96 to the surface, obtained3pork at243s, crafted/placed a smoker and cooked3pork by325s. One portion was consumed. The earlier table placement delay resolved without intervention.
- Creeper blast at69.8s reduced health20→15.652112. Frames66/70/74 show RunAwayFromCreepers active and the blast; no fall attribution. The bot survived. Creeper retreat in constrained terrain remains an open risk.
- Search visualization coincided with0–2FPS and339–667 render objects at139–165s. FPS recovered26 before renderPathMoves was switched off. Keep trajectory drawing off for natural observation; no measured causal improvement is claimed. Block placement/break overlays remain enabled.
- After cooking, the bot repeatedly tried to pillar over its smoker at(80,116,-155), with body near(80.5,117,-154.3),491blocks unchanged, and the smoker GUI open while GetToEntityTask was active. This persisted for minutes. Final disconnect near(80.6,118.2,-154.2),15.652112HP; server state preserved, no world restore.
- PillarTask unconditionally released sneak before right-clicking. BlockPlaceHelper.tryPlace can return SUCCESS for opening a GUI, so its attempted-placement counter is not a world-placement count. Baritone MovementPillar:232 requests sneak near the apex;:265 waits for isInSneakingPose before CLICK_RIGHT. The already-ported movement contains this too; the live PillarTask primitive did not.
- Real baseline fixture: smoker and crafting_table both opened GUIs, placed0blocks and never arrived. Stone and vine controls both placed3actual blocks, arrived grounded, no GUI. All four valid videos decoded; smoker image inspected. Initial stone attempt hit a reconnect race and measured nothing; fixture now avoids reconnecting an already-connected client and confirms the world plus grounded position before measuring.

### Plan
- Request sneak during the real placement window and wait for the player's actual sneaking pose before clicking; retain existing jump/climb logic.
- Score server-side placed blocks, grounded arrival, health and GUI opening. Repeat interactive supports, ordinary stone, vines and blocked-headroom controls; audit navigation/building/one-high clearance before resuming the saved surface position.

### Implement
- PillarTask requests sneak near the placement apex and gates the click on isInSneakingPose. Added sneakWait to the existing failed-pillar diagnostic. New pillar_interaction_test supports stone/smoker/furnace/table, vine and ceiling cases.
- Clean Tungsten build succeeded; deployment is completing. Fixed tests pending; no acceptance/commit claim yet.

- Fixed pillar matrix: twelve valid passes (eight interactive-support trials including two off-center smoker starts; stone, two vine controls and one blocked-ceiling control). All successful climbs placed three server-side blocks, arrived grounded and opened no GUI. The ceiling control refused with zero placements. All thirteen fixed-attempt videos decoded; off-center final frame inspected.
- fixed-7 is retained as an invalid completion measurement: the fixture read body state before separately waiting for the active flag, so its last position predates completion. Server blocks, final screenshot and the grounded Pillar-done log agree. The collector now reads completion before body state; furnace repeat fixed-8 passed. No product change was made in response to this measurement error.
- The clearance fixture now schedules direct planner world reads and navigator startup on the client thread via MethodHandle/FutureTask, matching the live driver's ownership. Its live audit is pending below.

- Adjacent audit passed5/5: wall2/bridge/flat/staircase/descend,21.2–28.3FPS,zero invalid runs,falls or freezes. Original one_high passed2/2: all three obstructing head cells appear in the plan and are mined, grounded goal arrival,20HP. Client-thread clearance fixture executed successfully. All six audit videos decoded; clearance and wall2 final images inspected. Artifacts: deploy/runner/artifacts/20260915-192627 and workspace outputs/pillar-clearance.
- Assessment: formerly reproducible GUI-open/zero-placement failures now complete actual three-block climbs across interactive supports. This removes the isolated smoker stall mechanism with the existing upstream sneak protocol; no general survival or whole-game success claim. Next: resume preserved natural world and verify departure from the smoker position visually.

### Natural continuation after 237074ea
- survival-pillar-fixed completed900s without restarting gameplay between eight clips; all eight decoded. Starting80.6,117,-154.2 (old smoker), ending122.4,6,-182.6,19.8HP during skeleton defence. Minimum sampled HP12.652112; recovered to20 later. No death observed. The next observer survival-diamonds continues the same task without --start.
- The initial task changed to iron collection and left the old smoker without invoking that same pillar, so this is not a natural matched reproduction of the repaired interaction. Controlled fixture evidence remains the acceptance basis. Natural terrain pillars completed later.
- Collected6rawiron, smelted4 and crafted an iron pick. After more hunting, a costly return toward the old smoker gave way to constructing a new smoker at118,116,-153 and cooking5pork. Four cooked portions remained through the subsequent underground route.
- Descended to gold: transient food preemption near699s resolved autonomously by712s, with4cookedpork still present. Collected6rawgold by736.6s, smelted5 and crafted a golden helmet (inventory confirmed). No priority code was changed: a persistent preemption failure was not reproduced in this continuation. Approached diamond ore at129,12,-193; no diamonds yet confirmed at the observer boundary.

## 2026-09-15 — Do not finish a reach route over an unfinished bridge

### Investigate
- survival-diamonds acquired an actual diamond pickaxe, diamond sword and two spare diamonds, then died from a fall at19:49:31UTC. At192.7s body153.1,-23,-189.9 was falling at-1.25 with MLG active; next sample was the respawn. Explicit disconnect followed, observer interrupted, all3clips decoded. Natural world was not restored. Frame65/65.5 of clip2 shows mining at the cavity lip; frame66 shows a movement queue,66.5 the fall,67 MLG waiting with sword still held.
- At19:49:29 MovementQueue accepted151,-11,-191→152,-11,-190; at19:49:30 FastNavigator announced arrived(1.3), followed by a no-floor report at152,-13,-190. Post-run server block checks report destination support152,-12,-190 as air. The chunk was not entity-ticking; no forceload or world edits were used for these reads.
- The reachBlock arrival branch bypasses settledBody entirely. More importantly, a grounded player can be sneaking beyond the floor edge while building: stopping then releases sneak before a support is placed. MovementTraverse.safeToCancel already rejects that state, but MovementQueue exposed no corresponding query and FastNavigator never asked it. Baritone PathingBehavior:153 obtains safeToCancel from PathExecutor; PathExecutor:194 reads movement.safeToCancel.
- Controlled baseline-2 (open gap) ended navigation in midair, placed0 and fell. Roofed baseline-roof-1 forced a bridge, ended while still flagged onGround at3102.4,-29,1040.5 over its missing support, then fell;0blocks placed. First baseline-1 failed before gameplay because reflection chose the wrong start overload; fixture now resolves BlockPos explicitly and prints the subprocess error. Repeats recorded below.

### Plan
- Keep every arrival behind the settled-body condition, including reach goals. Ask the current movement whether its inputs can be released safely, preserving its existing construction contract.
- Require roofed bridges and open-gap landings repeatedly, plus ordinary movement, building, mining/interaction and original one-high regressions. MLG rescue failure remains a separate unresolved issue; this pass prevents premature route completion.

### Implement
- MovementQueue exposes the current movement's existing safeToCancel predicate. FastNavigator includes it in its existing arrivalNeedsSettledBody gate and applies that gate to reach goals too. Build and fixed tests pending.

- Both old-binary variants reproduced twice (open2/open3, roof1/roof2); all4valid baseline clips decoded, roof frame inspected. First fixed binary clean build/deploy succeeded. Roofed fixed-1 passed with1actual support,grounded arrival,no fall. Open fixed-2 still fell, this time while navigation remained active: arrival cancellation was fixed but did not explain that entire path. Failure retained and not accepted as an all-green matrix.
- A client-thread planner probe in open-diagnostic returns the correct3-waypoint reach route with1placement, while the real initial navigator dispatches a walking prefix and falls. Source explains the discrepancy: start(target, block) called default start(target), which immediately invokes planAhead and captures reachBlock=null; only afterward did it assign reachBlock. startExact and predicate starts used the same late-assignment structure. This is deterministic initialization ordering, not a worker timing hypothesis.
- All start variants now delegate to startWithGoal, which cancels the predecessor then assigns every goal field before launching the first plan. Exact-from-drive identity is initialized there too. Second clean build/deploy and complete matrix pending. Fixture now records the reference planner result and can disable the existing arrivalNeedsSettledBody flag for an isolated cancellation control.

- Final goal-initialization binary passed9/9 interleaved trials (3open,6roofed), each with one actual support, grounded reachable arrival and20HP. Disabling arrivalNeedsSettledBody on that same binary reproduces the unsupported fall with0placements. All10matrix videos decoded; final roof frame inspected.
- Adjacent audit passed7/7: bridge/wall2/gaps/flat/staircase/descend/water,17.0–29.3FPS,no invalid runs. Original one_high passed2/2 with all3head obstructions planned/mined and grounded20HP arrival. Buried and open-table controls both opened the preserved table at20HP; client-thread collector executed successfully. Hungry mining threat passed with6cobblestone,20HP and zero pick-to-sword switches during continuous queue mining.
- Retarget barrier passed (stale result discarded, replacement applied), completed one-cell control passed without physics handoff. All13audit videos decoded; clearance frame inspected. Artifacts: workspace outputs/reach-audit and deploy/runner/artifacts/20260915-201049. This pass is accepted locally; next focus is the separate natural MLG rescue failure, with controlled falling trials before more natural play.


## 2026-09-15 — MLG landing surface must keep partial collision blocks

### Investigate
- Natural death target153,-44,-191 is deepslate_tile_stairs (server read-only block query); above is air. Water bucket was present in hotbar2 before and during the fall, with a shield offhand.
- The clutch ray hits a collision shape, but placeMLGBucketTask then uses WorldHelper.isSolidBlock to move partial blocks down one cell. This wrapper is the vanilla full-solid check and explicitly excludes partial supports. The ensuing reach ray hits the original stair/slab and cannot reach the block below it. Upstream MovementFall does not discard partial landing surfaces this way.
- Initial solid30/pack33/edge45 probes used a real bucket and survived, but the first slab/stair probes exposed a measurement gap: sampled health was20 after fast respawn, even though the server logged fatal falls. Added a server deathCount objective; no rescue is accepted after a death. Damage control without a bucket loses9HP from12blocks. The no-water slab/stair attempts remain failed, not accepted.
- Corrected stairs-death-2 records1server death, zero bucket use and MLG active. Further baseline controls are pending.

### Plan
- Keep all nonempty collision landing surfaces as the click target. Retain the down-one adjustment only for collisionless hits such as fluids. Do not change late-equipment timing without evidence.
- Test stairs/slabs repeatedly, solid/pack/offset controls, and actual water placement plus zero deaths. Build/deploy only after baseline trials stop.

### Implement
- Landing normalization now checks collision-shape emptiness. This edit is not built or accepted yet.

- First shape fix clean build/deploy succeeded (mlg-shape logs). Bottom slab fixed-slab-1 passed at28FPS:1server bucket use,0deaths,20HP. Stairs fixed-stairs-1 still died at25FPS despite1bucket use. This is a real remaining failure, not accepted as repaired. Waterlogging a stair does not put fluid above its upper collision surface.
- A west full-block bank beside the stair floor still failed on the shape-only binary:29FPS,1use,1death. The bot keeps steering toward the stair instead of the available bank. Direct vanilla Waterloggable bytecode confirms the bucket fills WATERLOGGED in that same block; FluidState exposes source fluid height.
- Added one shared waterCushionsLanding predicate to candidate selection and the placement gate: for fluid-fillable blocks compare collision maximum height against source-water height. Bottom slabs remain candidates; high waterloggable supports are rejected while searching for a reachable alternative. The additional height filter only applies when a usable water bucket is present. Second clean build succeeded8s; verified deployment is starting. Tests pending.
- Probe now stores server use/death counters and median FPS. Initial solid-control-1 missed a brief empty bucket between pour and pickup and was correctly not accepted; solid-stats-1 confirms1server use and0deaths. All11pre-first-build clips decoded. Current fixture also gates final X/Z inside the arena so respawn at world spawn cannot count as landing.

- Surface-model west-bank and slab probes passed3/3 each so far, with1use,0deaths,20HP. All west runs refill the bucket, while all three slab runs finish holding an empty bucket. The slab water is recorded at support.up(), and pickup accepts only Blocks.WATER, so both producer and consumer miss the actual waterlogged source.
- Added a shared waterPlacementPosition calculation for placement prediction and pickup position, plus source-fluid recognition in MLGBucketFallChain. Added --require-refill to the fixture. These follow-up edits are not yet built; the current rescue matrix is still running on the previous surface-model binary.

- Surface-model matrix completed6/6 west-bank escapes and6/6 bottom-slab rescues,25–29FPS,1use each,0deaths,20HP. West targets/landings are actually on the available bank (first target3199,-61,1101; final3199.4,-60,1101.5), not on distant surrounding grass. All slab runs end with an empty bucket; model-slab-6 explicitly records0water buckets in the inventory. Pack33 and offset45 controls also passed with the bucket refilled; wall-bank control still running. The conservative water-height check may reject other high partial shapes; no universal clutchability claim.
- Fetch found no incoming origin/main commits. Publishing remains pending the existing target-specific auto-review confirmations; no retries.

- Refill build/deploy succeeded10s. refill-slab1..3 passed with the bucket restored; refill-slab4 is a real failure at28FPS:rescued,0deaths,20HP,but0filled buckets. The batch stopped before navigation audit. All first three/four artifacts are retained, not counted as6/6.
- MLGBucketFallChain unconditionally clears lastMLG at the end of every non-pickup priority tick, defeating its existing4second recovery window if source/inventory confirmation is not available immediately on landing. Retain an actual attempted placement through that existing window; still clear no-attempt falls immediately. This affects doneMLG consumers (food/combat), so the final audit must include hungry mining defence and confirm completed recovery releases the chain. New build/tests pending.

### Acceptance
- Window-retention build/deploy succeeded10s (mlg-window logs). Final matrix passed6/6 slab rescues with refill, plus west-bank/east-bank/pack controls:9/9,26–29FPS,one server bucket use,zero deaths,20HP,one filled bucket afterward. An additional diagnostic trial passed at27FPS and confirms attempted source3202,-61,1100 plus recovery_released=true; the final collector and release gate are live-tested. All10final videos decoded; final slab frame inspected. Earlier refill-slab4 failure remains recorded.
- Adjacent audit passed5/5:flat/staircase/descend/bridge/water,22.3–29.3FPS,no invalid runs. Original one_high passed2/2 with all3head blocks planned/mined,grounded20HP arrival. Reach-bridge control placed its support and arrived safely. Hungry mining defence passed with6cobblestone and zero mining-to-sword interruptions; final health17HP after combat, not20. All8audit videos decoded. Artifacts: workspace outputs/mlg-fall,outputs/mlg-audit and deploy/runner/artifacts/20260915-205835.
- Assessment: the original partial-support clutch refusal now gives way to actual bottom-slab rescue or steering to a nearby safe bank; high waterloggable supports are not mistaken for protection. Water returns to the bucket, and the existing recovery window survives late confirmation. These controlled results do not prove a replay of the natural death or arbitrary falls. Planned deep descents (G3), combat reliability and full-game completion remain open. Save locally, back up the current natural world without restoration, then resume observation.


## 2026-09-16 — Natural playthrough after bridge and MLG repairs

### Observe
- HEAD8d30f01a resumed the preserved natural world after a verified backup (20260915-210833-post-diamond-death.tar.gz, SHA25646091ee8043adf9d7eb5912509a919c3ccc2f18973ff1f8d50cfb55227dc454c). No world restoration, inventory injection, teleport or time change.
- survival-mlg-fixed completed900seconds, ending88.3,33,-11.5. It recovered stone tools, an iron pick, shield and a filled water bucket. A table/furnace placement delay resolved without intervention. Eight clips decoded; tunnel, crafting, water and final mining frames inspected.
- Health fell20 to17 during the skeleton encounter around639seconds; clip6 at12seconds visibly shows close combat. It remained17 through the final896.9second sample. This establishes temporal association, not the exact damaging hit. No death observed.
- Continued the existing task without restarting it as survival-gold-continued. By132seconds it had mined/smelted gold and held a golden helmet in inventory, then descended toward diamond ore49,11,-56. The filled water bucket remains present. This continuation is still live; no diamond or whole-game success claim yet.
- Latest fetch has no incoming origin/main changes. Publication remains pending the existing target-specific confirmations; no push or Telegram retry.


## 2026-09-16 — Submerged roof traversal

### Investigate
- The continuation acquired a diamond pick and one remaining diamond by420seconds, then drowned at21:32:57UTC. Server log confirms drowning. At458–471seconds the body remained33.0,6.2,-59.5 under a stone ceiling while FastNavigator repeatedly refused the water leg. At471seconds health was7; next sample was the respawn. The natural client was explicitly disconnected, then the observer interrupted. All5continuation clips decoded, underwater frame inspected. World not restored.
- MovementQueue admission lacks a cardinal downward stroke, though dispatch already constructs MovementSwim for liquid edges. Baseline dive1/2 both produce a complete three-cell downward plan, then109short-prefix refusals, zero off-route refusals, no arrival and drowning damage at29FPS.
- Baseline roof1 produced a complete route with a dive, horizontal crossing under a lower ceiling and surfacing. Initial sinking bypassed the first dive, so qShort stayed0. The first horizontal MovementSwim repeatedly failed: actual feet hovered around-55.4 while the destination's modeled feet were-56 beneath ceiling-54. Movement.update applied JUMP after the swim update whenever y<dest.y+0.6. That extra lift makes the body too high for the route.

### Implement and pending validation
- Admit all six cardinal liquid strokes; retain existing land edge admission. Extract base water inputs into an overridable method preserving land behavior. MovementSwim allows the extra0.6lift only when the destination body envelope fits, otherwise controls depth at the planned feet height and actively sinks when above it.
- Clean build succeeded8seconds; nested deployment verified. First fixed-dive1 entered the target cell at-56.4 but the fixture required an unnecessary-56.6; after navigation completed it waited underwater and damaged the bot. Retained as a failed measurement, not product acceptance. Fixture now scores target-cell entry; the full exit uses actual submersion and300air instead of requiring the entire body to float above water. Raw player reads run on the client thread.
- First corrected short measurement reached the goal healthy but sampled9.5FPS, so it is invalid. Recording now warms up in spectator mode before a fresh survival start. Warm dive1 passes27FPS, no refusal,20HP. Full exit and repeated/adjacent audits remain pending. Baseline videos all decoded.
- Separate open survival-policy problem: WorldSurvivalChain only presses jump; it does not choose reachable breathable air when a roof blocks surfacing. Do not claim the movement fix alone solves arbitrary underwater resource pursuit.


### Reachable-air recovery and acceptance
- The swim-only binary passed six warm dives and six roof traversals at 26.5–29 FPS, all healthy. However, an automatic survival-policy baseline still drowned at 29 FPS despite an available route: holding jump cannot choose an exit behind a roof.
- FastPlanner now supports a bounded condition-goal search over its existing movement graph. FastNavigator initializes that goal before dispatch, publishes on the client thread with existing generation/world guards, and rejects incomplete condition searches rather than sending a placeholder start to physics.
- GetToAirTask requests a reachable standing-body cell with breathable eye height. WorldSurvivalChain preempts resource/combat activity below half air and retains recovery until oxygen is full, then releases the original task. Failed searches are rate-limited; the task uses ordinary navigation rather than a scripted escape trajectory.
- Clean build succeeded in 9 seconds; nested deployment verified. Final automatic matrix passed 6/6 (three ordinary exits, three with a nearer unreachable decoy pocket), 25–29 FPS, 20 HP throughout, 300 air. Separate release-gated trial passed at 29 FPS. Two open-water controls passed; a final explicit dive passed at 14 FPS. The latter is a validity-floor sample, not a performance comparison.
- Working-task interruption passed 3/3 at 29 FPS: a resource task was active before escape, GetToAirTask took priority, air reached 300, health stayed 20, and recovery released. The resource task targeted old fixture stone at 3105,-29,1040, so these trials establish task/navigation interruption and resumption, NOT physical mining of the newly installed underwater floor.
- Adjacent final audit passed 7/7 (bridge, water, flat, staircase, descend, wall2, gaps), 22.7–29.3 FPS, no invalid runs. Original one_high passed 2/2, all three head blocks mined, grounded 20 HP arrival. Reach-bridge placed its support; retarget discarded the stale plan; slab MLG used one bucket, zero deaths, recovered the bucket and released recovery. Hungry mining produced six cobblestone with zero continuous mining-to-sword interruptions; final health 14 HP, not 20.
- All final matrix/audit clips decoded. Decoy exit, air recovery and working-task frames inspected. Artifacts: workspace outputs/submerged-route and outputs/air-audit; navigation deploy/runner/artifacts/20260915-220448.
- Failed/invalid evidence retained: first dive fixture demanded unnecessary depth after successful goal-cell entry; corrected short trial ran at 9.5 FPS; intermediate bridge audit fell at 27.4 FPS and respawned. A later bridge pass at lower FPS cannot establish a repair. Neither arbitrary sealed caves nor the original natural death have been proven survivable by these controlled tests.
- Incoming origin/main de9c6adc exposes pillar clearance with unchanged 0.05 default; 2f027a41 names/documents the unchanged entity haul cap. Both reviewed for local integration; post-merge build and focused live controls pending.

- Post-merge acceptance (HEAD0ec47b74): clean build9s, nested deployment verified. Stone and smoker pillars each placed3blocks without opening a GUI; hungry mining passed6cobblestone with zero continuous-queue pick-to-sword interruptions, final17HP. Decoy-air escape passed, original one_high2/2 passed in6.2/7.3s with3head blocks removed and20HP. All5saved videos decoded, clearance frame inspected. Resuming preserved natural world next.


## 2026-09-16 — Natural observation on merged air-recovery build

- Started the preserved gamer world on b191422f after server login verification, without restoration, inventory injection or time change. Observer survival-air-fixed is still live; do not build during it.
- The bot pursued an old iron pick at57,13,-44. At33.4s it was submerged at68.5,49.5,-50.6 and GetToAirTask took over. At39.5s it reached the surface at62.3, replenished air, then resumed the main task. First video frame39 inspected: actual surface air recovery with full health. This validates a natural-water recovery, not a replay of the earlier roof death.
- Repeated dives could not reach the pick35blocks below the lake floor. Existing40second budget and25second no-closing checks did fire, but repeated attempts/wandering consumed several minutes. Server later confirmed no item within4blocks of the target; a client-thread tracker read held only a clay drop. By303s the bot switched to wood. The delay mechanism is not yet established, so no speculative blacklist change was made.
- First sample at1.8s was19.833HP, then20; the earlier conversational shorthand that every first-five-minute sample was20 was imprecise. Health stayed20 in the observed water recovery. At520.2s it fell to19 while pursuing a pig; exact damage cause unproven.
- By424s wooden pick; by474s stone pick and sword; by584s five raw pork, coal and wood. Smoker placement delayed but resolved without intervention; by716s five cooked pork in inventory,19HP. Container re-approach on uneven terrain and repeated pickup pursuits remain efficiency risks. First six completed clips decoded; mining and air recovery frames inspected. Observation continues.

- First observer completed900s; all8clips decoded. Health fell at835.9s during container approach (20 to17.833) and886.9s during zombie approach; finished899.1s at70.2,95,-277.7,18.833HP. Continuation started without restarting the task; do not assume zero recording gap.
- survival-air-continued reached cooked pork12, cooked chicken4, mutton5, flint and steel, one obsidian and wool5. It recovered20HP and started iron acquisition, but creeper retreat interrupted. At211–230s retreat20 remained active around98,86,-253; at236s switched to10;242s16.8HP,248.8s10.233HP,256.6srespawn. Server confirms tester1 was blown up by Creeper at22:48:10UTC. Natural world explicitly disconnected, observer37626 interrupted(exit130). All3continuation videos decoded;242frame inspected.
- Next focused investigation: FleeLive computes a point from the centroid of all supplied dangers, while RunAwayFromCreepersTask supplies every tracked creeper. Actual route logs show17–21danger points. A far cluster can pull that point through/toward a nearer threat, and target() does not require its own reached() predicate to hold. This is a source-derived hypothesis pending a controlled baseline. Large repeated physics drift at replay tick7 is also present; preserve as a separate execution issue rather than assigning every failure to the centroid. Filtered natural log retained in outputs/survival-air-continued/creeper-death-log.txt.


## 2026-09-16 — Retreat goals and escaping an existing creeper exclusion zone

### Investigate
- Installed-baseline FleeLive probe: single threat produced a safe endpoint, but close6 plus six threats20blocks away produced a target at the close threat; its own reached() returned false. Opposed6 also returned an unsafe point. Artifact: outputs/flee-geometry-baseline.json.
- Installed-baseline condition search on a flat arena found safe paths for single6, clustered6 and opposed6. With a creeper3blocks away it returned complete=false and only the start node: the destination-only5block veto sealed all first steps, including steps outward. Publish and plan ran in one client-thread MethodHandle composition; no race with the danger publisher. Artifact: outputs/flee-planner-baseline.json.

### Implement and pending acceptance
- FleeLive is now an exclusion region, with immutable danger snapshots for worker searches and live completion checks. Its driver uses the existing nearest-condition FastNavigator path. Point goals remain on their existing path. Creeper edge safety checks the whole node segment: outside cannot enter the exclusion ring; already inside may increase separation without first moving closer.
- First clean build10s/deploy verified. The same graph probe now returns a complete outward route for inside3 and preserves the other3controls. Actual first pilots passed: cluster, opposed, frozen inside3, AI single, AI close, open-sided slope and confined slope. Each had0server deaths,20HP and actual safe separation,17–27FPS. Frozen inside started exactly3blocks away; AI close first sample was4.22 after initial movement, not3. Open-sided slope barely gained height, so the additional confined slope requires real ascent to-53.1 or above. These are pilot samples, not a measured pass rate.
- Final source refinement matches the hostile-retreat retention radius (requested distance+2) for creepers, retaining occluded near threats but excluding distant loaded ones. Region driving clears the previous resource point so a separate stuck escape cannot inherit it. Final clean build/deploy and repeated/adjacent acceptance are pending.
- Newest verified natural backup: outputs/natural-checkpoints/20260915-230443-post-creeper-death.tar.gz,56,296,799bytes,80entries,SHA25656e985d244d944baf0f200870db695776a7084543f4bfbaeb0a4e8112077e0ac. Zero natural players during backup; save-on restored. No rollback.

### Normalized final retreat matrix
- Final clean build9s and nested deployment verified. Initial final-slope-2 failed the height gate while reaching safe distance15.3,20HP,0deaths. AI wandered to11.55blocks during warmup and the short corridor allowed a flat bypass. All four initial final-* clips decoded; the failed frame is retained and excluded from normalized rates.
- Fixture now freezes AI during warmup, resets the player before activation, validates initial separation and extends corridor walls behind the start. Confined arrival requires3blocks of real ascent. Equipped controls use ordinary dig/build permissions, stone tools and32blocks.
- Final normalized matrix20/20: active close creeper6/6, active confined ascent6/6, frozen cluster/opposed/inside2each, equipped close and equipped ascent. Every run:20HP,0server deaths, safe separation, region routing observed, valid initial separation; median FPS16–29. Frozen inside starts3blocks away; active positions are measured. All20videos decoded; close and staircase frames inspected. Artifacts: workspace outputs/flee-region/stable-*.
- Adjacent audit is running; shared routing change remains uncommitted pending completion. Latest fetch has no incoming changes. Natural executor drift and fuse-aware route cost remain open; controlled passes do not prove natural survival or the End.

### Retreat adjacent acceptance
- Final adjacent batch exited0. Navigation7/7 passed at22–29.3FPS with no falls or invalid runs (deploy/runner/artifacts/20260915-232159). Original one_high2/2 passed6.2/6.5s, three head blocks planned/mined, grounded20HP arrival; recording shown to the user.
- Decoy-air and working-task air recovery passed29/28FPS,20HP,300air and recovery release. As previously, the working control establishes task interruption/resumption, not mining the new underwater floor. Slab MLG at33blocks with shield passed23FPS, one bucket use, no death,20HP, bucket recovered and recovery released.
- Hungry mining passed with six cobblestone, zero continuous-queue sword interruptions, final17HP. Occluded hostile retreat12and30 both passed, each with nine predicate controls; no FPS rate is inferred from that older fixture. Retarget rejected the queued stale publication and applied the new one.
- All adjacent clips decoded. Air and staircase frames inspected. Syntax and diff checks clean. Artifacts: workspace outputs/flee-audit. Local commit follows; natural-world verification is the next pass. Neither fuse-weighted routing nor repeated natural physics drift is declared solved.

## 2026-09-16 — Natural run on accepted retreat9b0166d9
- Verified gamer-server login, then observed900seconds without restoration, inventory injection or time changes. Observer14646 exited0; all8clips decoded. Artifacts: workspace outputs/survival-retreat-fixed. Continuation29734 records the existing task without --start; do not assume zero recording gap.
- At46.7s health20to19.6667 at83.3,124,-28.5 on the cliff; exact cause unproven. Pillager defence was visible at52/59s, then coal acquisition at108s with20HP. No later sampled damage or respawn in this first observer.
- By160s8coal/2rawiron; crafting-table placement delay resolved by185s without intervention. Shield by227s, iron pick by323s, two buckets by424s. Repeated workstation placement delays resolved; no speculative placement change made.
- Water pursuit around481s had transient incomplete physics paths, but mining advanced by494–527s. Water bucket acquired before584s. Descended from98to28through mined shaft without damage; eight raw gold by775s, golden helmet by874s. At894.8s72.7,6,-65.5,20HP, pursuing diamond80,3,-54.
- Visually inspected cliff digging, tight-mine placement, furnace GUI, water-route search and shaft descent frames. This natural run has not yet exercised the new creeper retreat, so controlled20/20 remains its direct evidence. Whole-game completion remains open.

### Natural continuation and next food-search blocker
- Continuation29734 completed900seconds, all8clips decoded. Every sample20HP; no respawn. Diamond pick/sword by93s, leggings by208s, boots by486s, chestplate by679s (armor17). Workstation delays resolved without intervention. Frames74,353,616 inspected.
- At729s CollectFoodTask(220) selected Searching/TimeoutWanderTask at31.3,-6,-142.7. Through897.8s the bot remained within the same small chamber, repeatedly logging Failed exploring. Last31.5,-5,-142.2,20HP. Client explicitly disconnected after observation; no world restoration or inventory change.
- Verified backup outputs/natural-checkpoints/20260916-000244-underground-food-stall.tar.gz:56,330,745bytes,80entries,SHA256030b13d0d0aa19782028a7df2cadfea24ef5d25113ec2b75af1909ba6e86c4f3. Zero natural players; save-on restored.
- Source: no known food falls through to TimeoutWanderTask. Its ordinary exploration targets the current cave-height band and uses physics pathing; its dig/build surface recovery only triggers if all four cardinal neighbours are solid. The natural chamber has open neighbours, so it keeps choosing exploration without a surface-search objective.
- New controlled baseline uses an open-neighbour chamber under8or20blocks of stone, with a diamond pick and64blocks, and actual CollectFoodTask. First four trials failed to emerge at29FPS,20HP; repeated baseline still running. No production food-search change yet. Proposed focus: when no food option exists underground in the Overworld, give ordinary dig/build navigation a stable dry-surface target; keep local food options ahead of that and ordinary surface exploration afterward.

### Food surface implementation, first failure and release correction
- Baseline completed6/6 without emergence (three8block and three20block roofs), all29FPS/20HP; all6clips decoded and deep-chamber frame inspected. RCON read-only checks confirm natural open neighbours at31,-6,-143 and32,-5,-142, so enclosed-body recovery is not the applicable path.
- CollectFoodTask now selects a stable dry surface in loaded nearby columns when no food option exists underground in the Overworld. Heightmap excludes leaves; support height, full body fit, fluid and reachability policy are checked. Existing GetToBlockTask owns digging/building. Local food remains earlier in selection; ordinary exploration resumes at the surface. The food amount and Nether policy are unchanged.
- First build/deploy succeeded8s. Shallow pilot failed at26FPS: it climbed from-58to-51 and opened the roof, but retained original target-50 after excavation lowered its support. Repeated no-progress followed. All health20; failed clip decoded and frame inspected. The old fixture also demanded the original terrain height, which is not a valid oracle after digging; neither issue is hidden as a pass.
- Corrected retention checks the current column before keeping the old route. Rebuild8s and nested deployment succeeded. Fixture now reads actual surface height on the client thread and requires both exposure and release of the surface-search task. New pilot batch1177 is running; no food-surface acceptance or commit yet.


### Pillar short-window investigation (2026-09-16)
- Food release pilot failed under a low chamber roof: every available placement tick waited for sneak pose (18/18), with zero clicks/placements and no stolen jump. A separate food run took mined stairs and emerged at the measured surface-51 with20HP/28FPS, releasing the surface task. This is one successful route, not food-policy acceptance.
- Isolated one-rung pillar from-60 to-59 with support-61 and roof-57 reproduced zero placement in6/6 old-binary trials. Videos decoded and a frame inspected. These original probes did not collect FPS; no timing comparison is claimed. The repeated trace matched the chamber failure: apex-58.8, sneakWait18, placeAt18, jumpStolen0.
- ClientPlayerEntity bytecode computes cached sneaking pose before input.tick. PillarTask cleared sneak each tick and only requested it after placement clearance, near the apex. The short window closes before the pose gate observes the request. Baritone MovementPillar also requests sneak while grounded, unlike the incomplete port.
- PillarTask now holds sneak through the centred jump, retaining actual-pose, clearance, ray and placement gates. Existing fixture gains a low-roof one-rung case and FPS recording. Clean Tungsten build succeeded in4s; deployment/acceptance in progress. No accepted fix yet.

- Early-sneak binary passed6/6 low-roof rungs with actual server blocks, grounded arrival and20HP (19.5–29FPS). Stone, smoker, furnace, crafting table and vine three-rung controls passed. Blocked-ceiling control behaved correctly but was excluded at13FPS during recorder startup; after a three-second recording warmup it passed at29FPS. Off-centre control passed at29FPS. All completed clips decoded; low-roof final image inspected. Original one-high and navigation audits are in progress.

- Same early-sneak binary passed the original one-high regression2/2 (three overhead blocks planned/mined,20HP,grounded) and navigation7/7 (bridge,water,flat,stairs,descent,two-high-wall,gaps),14.4–29FPS,no falls/invalid runs. All seven navigation clips and the one-high clip decoded; wall frame inspected. Integrated shallow/deep food-exit repetition now running; food policy remains unaccepted.


### Food exit height correction
- Six full dry exits passed (three depth8,three depth20,25–29FPS,20HP). Local bread and surface-start controls passed. The wet-column control FAILED at29FPS/20HP: excavation opened a sky shaft at-54, four blocks below the surrounding-50 surface, and current-column heightmap incorrectly released the surface task. Ordinary wandering then stalled in the pit. Clip decoded and final image inspected; not accepted as food success.
- Keep the selected surface elevation across excavation, releasing only within one upward step of that level. This still handles the previous one-block floating endpoint while preventing a deep exposed shaft from counting as arrival. New build and repeated wet/dry validation pending.


### False placement protection found during repeated food exits
- Surface-level binary passed wet1/deep1/wet2, then deep2 FAILED at28FPS/20HP. It climbed from-58 to-41 and spent the remainder repeatedly attempting a pillar rejected by place policy. Full trace identifies a WorldSurvivalChain predicate denying the position; configured deny zones were empty.
- At01:14:13 the chain interpreted a world block change at3600,-44,1599 as a failed placement and banned the surrounding radius50. WorldBlockModifiedMixin emits BlockPlaceEvent for any air-to-solid change, including server updates; this is not evidence the bot attempted placement. The detector also mistakes a subsequently mined block for a placement failure.
- Isolated baseline: IdleTask on clean terrain, server changes an adjacent cell stone then air without any player placement. Policy at the player changed allowed→denied and an unrelated pillar placed0 blocks at29FPS. Artifact outputs/place-events/baseline-world-change; no real claim exists there.
- Remove this world-change-based placement claim detector/subscription. Keep explicit protection hooks/zones and actual executor failure handling: PathExecutor refuses its timed-out cell through PlaceRules, PillarTask bounds progress and remembers a failed column, BridgeTask bounds placement/movement. Baritone BuilderProcess likewise follows its actual movement/executor status. This does not claim all placement failure handling is complete. Build and repeated event/placement/protection controls pending.

- False-event fix root build11s and nested deployment succeeded. Six world-change trials passed at19–28.5FPS: policy remained allowed, actual pillar block appeared, grounded arrival and20HP. Six explicit-zone negative controls remained denied while the adjacent unprotected cell remained allowed. All six clips decoded; final image inspected. Existing explicit protection is retained; no claim that real protected-column retry behavior is repaired. Full deep/wet exits are now being repeated on this binary.


### Final combined food/protection exit matrix
- Final binary passed all6 full exits: depth20×3 in85.0/82.38/87.15s at26.5–29FPS, wet-column depth8×3 in37.82/27.33/35.04s at27–29FPS. All20HP, grounded at the actual surface and released surface search. Local bread4 control passed27FPS without surface routing; surface-start control passed29FPS without unnecessary ascent. All8 videos decoded; final deep image inspected.
- The earlier failed wet and deep runs remain preserved. The food change requires the selected surface-level correction and the independent false placement-protection fix; neither earlier intermediate binary is presented as accepted. Focused adjacent audit is running before commits/natural resumption.

- Final focused adjacent audit62002 passed: low-roof smoker pillar29FPS; original one-high2/2 (6.4/6.7s,three overhead blocks,20HP,grounded); bridge28.2FPS and two-high wall23.3FPS without falls; hungry threat mined6cobblestone without switching to sword during continuous mining and finished20HP; working underwater recovery28FPS reached air and released recovery; AI creeper inside-radius retreat26FPS,20HP,0deaths and valid initial geometry. All clips decoded; air frames inspected. Acceptance is for these controlled cases, not a completed natural playthrough.


## Natural observation after29aa2828 and starvation selection failure
- Verified preserved natural login20HP/armor17. In482.3s the bot acquired flint, wood and flint-and-steel, traversed the lush cave and climbed toY22 toward a chest. Health fell20→10 around310–358s; subsequent retreat/coal collection moved it to-9.5,-14,-288.4. RCON confirmed Easy difficulty,foodLevel0,saturation0,HP10. Food discovery was never selected despite no food in inventory. All4 clips decoded; frames26/101/180/276/351 inspected. One task-chain read at345s returned an error and recovered next poll.
- Stopped observer and disconnected alive. Verified backup20260916-015507-starvation-stop.tar.gz:56,351,895bytes,80entries,SHA256b1cfd3971c5b71a6222128cfe5525a20cf4ef3677a90562c42a287e74833bd94;zero players,save-on restored. No rollback, inventory injection or time edits.
- CollectFoodPriorityCalculator returns0.1 before its scarcity multiplier whenever no source is known. Optional chest/ore scores50–100 beat this even at zero hunger. Actual full-gamer baseline in a foodless chamber with nearby coal reproduced no food selection at foodLevel0 and28.5FPS, with health decreasing.
- Add a shared emergency-food predicate: hunger<=10 or health<=10, food potential<10. It selects food before the unknown-distance fallback and bypasses the equipment gate while urgent. Higher-level survival/defence chains remain unchanged. Full-gamer hungry/equipped, hungry/unarmed and fed controls pending; not accepted yet.

- Root build completed in8s and deployment verified nested jars. No Tungsten edits in this pass. The public ExecuteCommand("@gamer") drives the complete selector in the new food_priority_test fixture.
- Six normalized controls passed: hungry/equipped twice22–25FPS, hungry/unarmed twice28–28.5FPS, fed twice15–29FPS. All initial FPS samples are retained over the20s hungry observation; two earlier short unarmed observations at9/12FPS remain invalid. Intermittent FPS drops also occur in fed/equipped controls; no performance repair or external-load cause is claimed.
- Low-health fed control started8HP/food20, selected food by9.15s and passed26FPS. The unchanged task-retention timers can delay selection. All seven accepted control clips decoded; equipped/unarmed final frames inspected.
- Two fed setup attempts were invalid because saturation amplifier5 adds only12 hunger from empty. The fixture now applies amplifier20 after survival mode and asserts food20 before starting. The earlier spectator-timing hypothesis was not established. Setup failures are not bot-selection failures. Full exit and adjacent audit pending.

## Food consumption and external lifecycle follow-up
- Normalized adjacent audit passed one-high2/2 at20HP and nav_flat/staircase/descend3/3 at25.0/28.7/27.7FPS, without falls. Initial clearance repeats traversed correctly but lost health because the preceding hunger scenario left zero food; its fixture now clears the hunger effect and replenishes saturation. Original failed artifacts are retained in workspace outputs/priority-audit-starved.
- The hungry-threat audit then failed its meal gate despite killing both targets and mining6cobblestone: hunger5 stayed5 with16bread,20HP and29FPS. FoodChain reported no edible food because bread was behaviour-protected. The observed pick-to-sword transition coincided with a real combat cancellation and is not the failing threat gate.
- Restart-only control on the same binary passed the threat case: hunger0→20,6cobblestone,targets dead,final17HP. A subsequent full-gamer food run passed28FPS and restored behaviour depth1/empty protection. Later one-bread trials reproduced depth2 with seven food protections after stop; the origin of that extra scope is not yet proven.
- Direct meal baseline: give one bread during active gamer food search at hunger0. Three runs retained hunger0 at27–28FPS; the first final frame shows the bread in inventory while digging continues. CollectFoodTask adds consumption protection to its collected foods, and FoodChain asks canThrowAwayStack before eating. Separate discard-only protection from consumption reservations, preserving inherited reservations, important/custom-named protections and scope copies. Not accepted until live meal and negative controls pass.
- Debug logging also showed Task.stop on Worker-Main-11. ExecuteCommand and stopPathing use executeInNetworkThread, backed by Util.getMainWorkerExecutor, while task ticks run on the client. A bounded paused-client probe reproduced both premature start and premature stop6/6. Dispatch these lifecycle commands on the client, reusing RunInnerCommand for ExecuteCommand. This proves thread misuse; it does not alone prove the entire scope-leak mechanism.

- Combined AltoClef-only build completed10s, deployed with nested-jar verification. No Tungsten source change. Fixed paused-client lifecycle probe passed6/6: start and stop leave runner state unchanged while paused, then apply after release. Behaviour depth returned to1 with empty reservations after all pairs.
- Live consumption matrix passed6/6 at18–28FPS: bread twice, baked potato, sweet berries, bread with throwAwayUnusedItems=false, and inherited bread reservation. Ordinary meals increased hunger0→5 (berries0→2); reserved bread remained uneaten at0. Every sample denied discarding the supply; ordinary samples allowed consumption, the reserved sample denied it. Each completed trial restored depth1 with empty reservation/discard sets. All six clips decoded; pilot frame inspected. These are distinct positive/negative cases, not six estimates of one success rate.
- Test-helper failures retained separately: sweet_berries is absent from TaskCatalogue, so the probe now uses the game item registry; the first keep-unused setup used the wrong capitalization for throwAwayUnusedItems, then explicitly restored its already-pushed test scope. Enter now rolls back on partial setup failure. These invalid attempts are not production failures or evidence about the original leak.
- Parameterized food_priority_test, food_policy_probe and task_dispatch_test are in deploy/runner. Original starvation selection controls and the new meal controls share the full gamer path. Final combined exit/navigation/combat audit is running before acceptance.

- Final combined audit passed: full gamer exits the foodless chamber in29.24s at28FPS,12HP,grounded and releases the surface goal; original one-high2/2 in7.0/6.4s with three overhead blocks removed and20HP; navigation flat/staircase/descend3/3 at24.7/29.0/26.3FPS, no falls or invalid runs; hungry mining ambush mines6cobblestone, defeats both targets, eats from hunger5→20 and finishes20HP at29FPS with no sword switches during continuous mining. All clips decoded, exit/combat frames inspected. Inactive behaviour scope returned to depth1 with both sets empty.
- Acceptance covers command serialization, controlled urgent food selection and consumption, and these adjacent movements. It does not establish a universal scope-leak fix or a completed natural food recovery. Natural world remains preserved at10HP around-9.5,-14,-288.4 until resumption; full Minecraft completion remains open.

## Natural food recovery after17718cf2: cave-vine interaction obstruction
- Resumed preserved world at-8.7,-9,-289.5,10HP/armor17. Food discovery immediately won and pursued a known pig; the bot climbed to restingY7 before repeating the same failed pillar. No food acquired yet. One initial task-chain read errored and recovered. Stopped at167.2s and disconnected alive; both videos decoded and frames27/78 inspected.
- Runtime pillar repeats: apex8.25 from restingY7, placeAt(-11,7,-301), readyNull25/25, no placement, ray hitting cave_vines(-11,9,-301). Client-thread geometry snapshot confirms granite supportY6, airY7/8, cave-vine tipY9 and stemsY10/11. These plants have no body collision but are non-replaceable and intercept the placement ray. This differs from the existing climbable wall-vine control.
- Verified new backup20260916-030629-cave-vine-stop.tar.gz:56,357,123bytes,80entries,SHA256cf837b4154a1e7430411814ff814c7059b03ed365c61a78d1808e826aac1ad2c. Zero players,save-on restored. Brief stopped-task reconnection only captured geometry; no teleport,inventory or world edits. Next resume expects-10.5,7,-300.6 at10HP.
- Controlled navigator reproduction is running before a core change. FastPlanner body clearance and FastNavigator ceiling preparation currently ignore collision-empty plants; real placement requires interaction clearance too. Ordinary climbable vines and explicit break protection must remain valid.

### Cave-vine interaction clearance: controlled investigation
- Two navigator baselines reproduce zero placed blocks, no arrival, 20HP and29FPS. Both clips decode; the first final frame shows repeated pillar retries. The normal jump reaches1.25blocks above the stance, so this is an interaction obstruction, not a short jump or physical roof.
- First implementation failed identically (zero placed,27FPS). The shared predicate incorrectly exempted every CLIMBABLE-tagged block. Runtime inspection proves cave_vines belongs to that tag on1.21.11; its collision is empty but outline spans x/z0.0625..0.9375. Earlier assumption that cave vines are not climbable was false. One early test attempt preceded Py4J readiness and never set up the fixture.
- Revised predicate checks the outline against the centered player-width column, preserving wall-mounted vines. The planner prices explicit head clearance, navigator schedules it before pillaring, and executor does not skip collision-empty explicit targets. Required mining cost now has an explicit-removal entrypoint; ordinary movement pricing retains its walk-through shortcut. Validation pending.

- Shape-based pilot still failed: clearance queued3cells, but every mining attempt immediately aborted with "no visible face", then navigation gave up (0placed,17FPS). Both visibility and occluder rays in PathExecutor used COLLIDER; plants have no collision. Changed these two mining rays to OUTLINE, matching vanilla and existing RotationHelper/Baritone tracing. This is a measured second stage of the same clearance defect. Shape-pilot video decoded; final validation pending.

### Cave-vine controlled acceptance (adjacent audit pending)
- Final outline-ray pilot passes:3 actual rungs, grounded arrival in3.88s,20HP,18FPS. Six normalized repeats (three tip-at-head, three tip-at-jump-eye) pass6/6 in2.79–4.40s,22–29FPS,20HP,all3rungs. All videos decode; pilot and lower-tip final frames visually inspected.
- Breaking-disabled negative control passes: vine intact,0placed,no arrival,22FPS. Policy is restored in finally. Planner probe on the jump-eye case records the first vine in toBreak; returned plans are partial, so this is not a claim of complete initial planning. Navigator completes after clearing/replanning.
- One diagnostic repeat arrived with3rungs but median10FPS and is retained as invalid. The synchronous planning probe now has its own3s settling period before movement sampling; threshold remains14FPS. Six accepted results above use that separation.
- Existing pillar_interaction_test now supports --cave-vines --navigator, --vine-tip-offset1|2 and --no-break; food and allowBreak state normalized/restored. No duplicate fixture added. Fresh fetch found no incoming origin/main commits. Adjacent audit running; natural recovery not yet resumed.

### Cave-vine adjacent audit accepted locally
- Four pillar controls pass: wall vine27FPS/3rungs, low roof29FPS/1rung, blocked roof29FPS/0rungs and refusal, smoker27FPS/3rungs/no GUI. Original one-high passage2/2 (7.3/6.4s), all3headblocks planned and mined,20HP. Take-off slab2/2 (15.7/14.1s),20HP. All clips decode; wall-vine and original-clearance frames inspected.
- Navigation flat/staircase/descend3/3,22.0/23.3/29.7FPS,no falls or invalid runs. Artifacts deploy/runner/artifacts/20260916-032935; all3videos decode.
- Hungry mining ambush passes:6cobblestone,targets gone,hunger5→20,29FPS,final17HP after combat,0sword switches during a continuous mining queue. Video decodes and final frame inspected. Workspace outputs/cave-vine-audit holds the adjacent fixtures.
- Assessment: same plant column changed from0rungs/no arrival in both baselines to6/6 completed rises in2.79–4.40s. This removes a measured natural food-pursuit blocker through shared geometry/planning/mining, with no timeout workaround or server-specific production coordinates. Original clearance remains green. Natural food recovery and full-game completion remain unvalidated; resume preserved world next. Push/Telegram publication still awaits the existing target-specific answers.

### Natural cave-vine recovery and next food-pursuit investigation
- Resumed exact saved stance(-10.5,7,-300.6),10HP,armor17. Live log at03:34:24 queues the actual cave-vine tip(-11,9,-301); at03:34:25 mining completes, and movement continues. Natural obstruction is cleared. No world/inventory/time edits.
- Climbed toY30 by137s andY47 by355s, then repeatedly returned throughY39–47 instead of reaching food. Targets changed among pig/sheep/chicken. Server reads show pigs atY84/85 and sheepY86/87, roughly45blocks above the cave. getEntitiesInfo reports no nearby livestock; its close-entity range does not establish that the tracked surface targets are absent.
- Health remained10 and armor17 throughout. FPS intermittently fell to1–4 during ongoing routing, later recovered22–29; no causal attribution established. Food not acquired, full food recovery remains unvalidated.
- Stopped observer and disconnected alive at(-10.5,43,-389.6),10HP,armor17,634blocks. New verified backup20260916-034354-food-pursuit-stop.tar.gz:57,272,367bytes,80entries,SHA25665ddbdf2b3e87049f1d9ece16ff8cda57800858b1944fa4a2c59f5d929938f93. save-on restored. Next focused pass isolates a fixed high animal goal from food-target reselection on disposable terrain before changing production logic.

### High-food controlled investigation: mining progress series
- Direct45-block approach controls were inconclusive for arrival: first reachedY-16 and ended its task, but fixture compared the original pig spawn instead of its live position; retained invalid. The repeat measured live target and ended the180s window9.11blocks away, still climbing,20HP/29FPS. Initial routing delay varied; no stationary-target success rate or mechanism claimed.
- Fixture teardown initially rejected `difficulty Normal`; explicitly restored normal and original true flags, then fixed case parsing and finally restoration. These are fixture errors, not production failures.
- Full CollectFoodTask with one fixed pig45blocks above succeeds in191.76s,3raw pork,20HP/29FPS. It repeatedly logs "Not closing" and costs that pig while continuing to mine; no alternate source exists in this control. Video decoded. More animals may make these false costs destructive, but that causal link remains to validate.
-20s read-only diagnostic captured173 approximate snapshots of the actual KillEntityTask progress checker. At03:58:13.921 the new block(4200,-20,2200) has0progress while mine_last remains0.8888889; at03:58:14 it reports "Not closing". Same transition repeats03:58:17.877→18,21.244→21,25.161→25 across distinct successfully mined cells. New progress reaches0.177/0.355/0.533 below the old0.888 baseline; distance_failed remains0. The failure flag resets in the same task tick, so it was not directly captured. Source LinearProgressChecker compares scalar progress deltas across its0.5s window.
- Plan: make the mining signal cumulative observed work, counting only positive within-target damage increments; changing target at0 must not renew the no-work timer. Retain confirmed-solid-to-air credit and store an immutable block position. Validate productive target transitions and zero-work target switching, then real high-food pursuit and adjacent mining/navigation/clearance before resuming natural world. No production implementation yet.

### Mining progress series accepted; natural pursuit still open
- MovementProgressChecker now accumulates positive observed damage work across blocks and retains an immutable target position. Switching to an undamaged target grants neither work nor a timeout reset. Existing solid-to-air, distance, eating and pathing guards remain. AltoClef build/deploy succeeded in15s plus deployment; no Tungsten changes.
- Controlled live checker snapshots pass productive transitions and same-block work; zero-work target switching and same-block stalls fail as required. The fixture changes controller snapshots on the client thread and restores them; it is not a physical mining test. Initial cold-start timeout and a genuine0.696s no-work gap are retained as invalid positive trials, not hidden. Accepted productive samples include positive work after each transition; timeout remains0.5s.
- Six physical food scenarios acquire meat at20HP: depth8 single49.96s/25FPS, competing37.27s/27.5FPS; depth20 single81.73s/28FPS, competing120.33s/24FPS; depth45 single191.63s/28FPS, competing268.68s/25FPS. All clips decode; final high-competing frame inspected. The old single45 run already succeeded in191.76s, so no speedup or endpoint improvement is claimed. Repeated old cross-block false costs are the measured mechanism.
- High45 fixed runs had no pursuit warnings. Expanded matrix has ONE remaining Not-closing warning at04:24:49 during depth20 competing, followed by mining completion04:24:50. Its cause is unresolved; this commit does not claim all pursuit abandonment repaired. Natural vertical-food TODO remains open.
- Adjacent audit passes: checker guards, full gamer foodless exit28.5FPS, original one-high2/2 in6.7/7.6s with3head blocks removed and20HP, nav flat/staircase/descend3/3 at24.7/23/26.7FPS without falls/invalids, hungry mining ambush6cobblestone/targets gone/final17HP and no sword interruption during123 mining samples. All clips decode; clearance and combat frames inspected. Nav artifacts20260916-043022; workspace outputs/mining-progress-audit. Repeated clearance trials share one overwritten media path; both JSON outcomes retained, one final clip.
- Assessment: removes a proven cross-block scalar-comparison error without relaxing stall detection. Food routes complete under controlled geometry, but natural recovery and full game remain unvalidated. Resume preserved natural world and inspect the remaining abandonment if it recurs. Publication remains pending the existing target-specific answers.

### Natural food recovery after5582bbc4
- Resumed the exact preserved stance(-10.5,43,-389.6),10HP/armor17, with no inventory/world/time edits. Climbed beyond the formerY39–47 loop toY64 by96s. First recorded food:3raw pork at227.4s. Health increased to16.93 by239.5s and20 by448.6s; ordinary resource collection resumed between food tasks.
- No additional Not-closing or entity-budget warnings in this natural segment through448.6s. The one depth20 fixture warning remains unresolved and is not erased by this recovery. Three completed120s clips decode, underground mining and surface gathering frames inspected; clip2 shown locally to the user. Observer continues900s with visual checks, so this is a mid-run checkpoint, not full-game completion.
- Controlled unknown-food exit and urgent-selector tests, plus this actual recovery, close the immediate food-exploration/priority/vertical-pursuit incidents. Wider combat, bridge/deep-descent reliability, Nether and End remain open. No publication retry.

### Observer shutdown incident and recovery (not a combat regression)
- The900s natural run collected cooked_mutton17,cooked_porkchop5,cooked_chicken4 and ended20HP/armor17. All8clips decode. However, stop-on-exit stopped all survival tasks at04:49:40 and left the player connected; the operator delayed disconnection. A zombie killed the idle player at04:51:10. This is our observer lifecycle error, not evidence against the movement fix or autonomous combat.
- Backup20260916-045218-surface-food-recovered.tar.gz is MISNAMED: it contains the post-death state (respawn95.5,128,-21.5,armor0,empty),58,935,737bytes,80entries,SHA25674778f57dc885f236953e80ee464705cd68dcecd243547ba0b52bf36313ab6d2. Never treat it as a recovered-food checkpoint.
- watch_survival --stop-on-exit now disconnects on the client thread, confirms inGame=false, then stops the task. Defense remains active through recording finalization. SIGTERM is catchable so finally runs. Short flat-server observation passed and ended inGame=false with0players; SIGKILL remains outside finally guarantees. No mod rebuild needed.
- Recovery restores verified034354 pre-run backup while preserving the entire post-death world separately as world-after-observer-death-20260916-045218. This deliberately repeats the successful15-minute gameplay instead of injecting its inventory. Restoration and login verification are recorded next.

### Recovery verification and Docker startup blocker
- New helper containers stayed Created without running, including --network none; they were removed and their waiting CLI processes terminated. No helper performed its rename/extraction. The world was instead restored through docker cp into the stopped existing server, after confirming both archives have the identical80-entry file set. The post-death archive remains retained externally.
- Read-back verification compares every regular file SHA256: all67files exactly match034354. Evidence: workspace outputs/natural-checkpoints/restore-034354-verified.json. Current natural state is the restored pre-run10HP/armor17 cave checkpoint, not the successful surface state. No player connected.
- Docker also stalls starting the existing gamer-server; inspect remains exited with no new server startup. Other running services remain up. A Docker Desktop restart would interrupt unrelated mineswarm/services, so a specific user question is pending; no global restart performed. Available disk283GiB, so no unsupported disk-exhaustion claim. Full-game continuation blocked on restoring container startup.

## 2026-09-16 — the 00:12 stare, the node-9 halt, the tunnel, nav_steep (G91)

### Investigate
- Playthrough on HEAD 78028a43 (survival stand, `gamer_smoke.py 1`, recorded): PASS, 97 items, 0 deaths; ladder first craft 42.8 s, stone tools 64.4, furnace/coal 173, food 216, iron ore 368, iron 389, **iron tools 411 s**. The runner's stall detector fired only at 561 s ("no new rung"); the operator found an eight-second stare at 00:12 of the 12x clip that no detector saw.
- The stare: two aimers in one tick — the navigator's dig (`at the dig` on a stone) and altoclef's DestroyBlockTask ("Block in range" on the table beside it); vanilla resets break progress when the crosshair leaves the block. Counters: breakMissWhy transit=1035, dbAimWait=0; `stopOrphanRoute` cannot end a navigator dig during "in range" (300 ms drive-tick gate); `tickBreaking` had no yield to `minerOwnsAim()` (the walk path had one). Reproduced on the flat stand with `mineBlocks` + a new `destroyBlockAt` primitive on adjacent blocks: transit 77 in 4 s.
- "stopped n33 idx9 bot(-71.5,95,-1464.5)": read with the record's own semantics (`bot` = body at the search's exit, `phys` = the simulated agent's furthest point, `idx` = the search's lookahead), it is a search east to a truncated target killed one second later by `TungstenHelper.stop()` (313 external stops in the run) while the body chased a raw-chicken drop to the west (frames t=546–550 s: `GetToDropTask … at -78,94,-1471`, food 2/20). A one-second target flip of the G38/G23 family, not a stand.
- The three underground minutes after it: the return to the smoker 84 blocks away, 14 lower, "dig allowed" — a 7-deep shaft and a two-high tunnel executed one cell per plan (`truncateAtBreaks` cuts at the first break cell; each cell: re-plan, `steerTo` with sneak, WindMouse aim, two breaks ≈ 3 s) while the planner prices the cell at its break ticks ≈ 1 s (G90).
- `DSIC near=… walk=… makeNew=…` printed to chat every tick for 4000 ticks (G92); RTGATE is rate-limited and stays.
- nav_steep INVALID on every first attempt after a respawn (5/5 today: suite, `--only`, recorded, `--pin verboseDebugLogging=true`), bit-identical drift numbers, the second attempt from the same start passing. The `Agent.compare` trace (System.out under verboseDebugLogging) settled it: root vx 0.063 (captured while the walker still moved the body), body vx 0.006 at replay tick 1 (it had coasted to rest during the 650 ms search); both sprint-jump at tick 2 and 15; the simulation clears the first column's lip by 2.4 cm at tick 23, the body arrives one tick later 20 cm lower, hits the face, falls. Not fps (identical at 29.6 after a client recreate), not the dig-yield change. The playthrough log: 53 replay aborts, 42 at ticks 8–10 — the same class.

### Plan
- One aimer per tick: the dig path of `tickBreaking` yields while a miner owns the aim (`execDigYieldMiner`), mirroring the walk path. Verify on the bench and the nav suite.
- The physics hand-off takes its root from a body at rest: release every movement key, wait (≤ 20 ticks) for |v_h| < 0.02 on the ground, then `find()`; water and ladders exempt; `physicsHandoffFromRest` flag for A/B; `navHandoffRest=settled/timedOut` and `pfRootMoving` (roots taken from a moving body, all callers) to measure the rest.
- Record G90 (tunnel per-cell cycle and pricing) and G92 with the numbers; the drop-flip stays a data point under G38/G23.

### Implement
- b091dfac: PathExecutor dig yield + `destroyBlockAt` + `execDigYieldMiner`; bench after: execDigYieldMiner=82, transit 0/0, both blocks broken in 6.0 s; nav suite 13/14, gate failures 0, nav_steep INVALID (pre-existing, above). 08746bfe: checklist §4y (how an autonomous run is reviewed).
- G91 fix in FastNavigator (settle before `find()`), `pfRootMoving` in PathFinder, counters exported over py4j, G92 gated behind verboseDebugLogging — built and deployed; verification below.
- Verification: nav_steep `--only` six times on the new build — first attempts 5/6 clean at 9.0–9.2 s (before: 0/5), `navHandoffRest=1/0 pfRootMoving=0` read over py4j after a pass. The one fall has a different signature (`drift 2.327 at tick 26, expected (7.52,-58.00) actual (7.62,-60.32)`, the simulation two blocks up over the gap) and did not recur in four traced runs; recorded as G91b, open.
- Nav suite on the build: 14/14, gate failures 0 (nav_gaps one first-go fall, the recorded course flake, passed on the retry). Committed 69ddb20f; released **v0.95.0** (jar attached, verified with `gh release view`).

### The 25-minute run on v0.95.0 (12:39 UTC, fresh start #44, recorded, frames reviewed at every flat window)
- Ladder: wood/first craft/table/wood tools 461 s, stone tools 616 s; two deaths (a Breeze in a trial chamber at y=-28 after a 90-block shaft, then the respawn at world spawn with nothing); no iron in 25 minutes. Walls, each with its frame and its dump: a wooden-pickaxe DROP from an earlier run ten blocks underground held the first seven minutes while spruce stood across the lake (G93); sixty seconds on a step down onto a snow layer (`gaveUp … hop[0,-1,1] … [grass_block|snow|air]`, 32898 tests, G94); 110 s over a cobblestone two below the feet — `execDigYieldMiner=1392` against `dbBlocked=1430/0/0`: the day's dig yield stood down for a miner that was only holding the keys (the back-off branch stamps `minerAimUntilMs` and swings at nothing) — a regression of b091dfac; the night job to a block 86 below, then the trial chamber (G95); after the respawn `reach:armed … nav=false` and a shimmy (G96).
- Fix: a separate mining claim, `minerMineUntilMs`/`minerOwnsMining()`, refreshed only by DestroyBlockTask's "Block in range, mining..." branch; the executor's dig yields to that alone; the key claim keeps its walk-path yield. Bench `self_floor_dig_test.py` (stone two below the feet under a dirt floor, `@get cobblestone 1`): PASS, cobblestone in 3.2 s, `execDigYieldMiner=0 dbBlocked=23 navBreak=1`. Bench `two_driver_mine_test.py` (the 00:12 scene): PASS, both blocks in 4.8 s, transit 0. The runner's fresh start now kills last run's drops around the body where it landed (`execute as … at @s`), not around the console. Released **v0.95.1** (d255b05b).

### The 60-minute run on v0.95.1 (13:40 UTC, recorded, frames at every flat window)
- The first run past iron: wood 22 s, stone tools 44 s, coal 113 s, food 183 s, **iron tools 295 s**, shield ~273 s, bucket 1551 s, water bucket ~1600 s, **diamonds** from 1642 s (a shaft to y=7), **diamond pickaxe 1757 s, diamond boots 1987 s, diamond chestplate 2085 s**; 747 items, 0 deaths. The runner's ladder has no rungs past "bucket"; the task snapshots and the frames carry the rest.
- Two walls, both in the navigator's hand-off, both with the frame and the log line: **G97** (t=596–1367, nine minutes without a step in a pit at (1431.7,70,-1491.5)) — the plan complete and climbing 4.5 out of the pit first, G60's "the goal is 3 below — not towering up" firing 115 times because the iron ore was three lower and twenty-five blocks away, every ore in reach then "given up 3 times in a row — marking it unreachable", the chain "No tasks". **G98** (t=2377–3567, twenty minutes at the bottom of its own diamond shaft, the next goal nine blocks straight up) — every plan partial (two ledges, then flagged tower cells) and under the five blocks the walk-the-partial rule wants, so the dead-end branch handed the GOAL to the physics search, which has no place move: "Failed! No block path", "no progress … giving the route up" ×64.
- Fixes: G60's refusal gains a horizontal radius (`noTowerWhenGoalIsBelowRadius` 6, `navTowerAllowedFar`); a partial plan that carries a flagged build or dig is walked to it (`navPartialBuild`) and the flagged hand-off does the rest. Bench `shaft_exit_test.py` (a 2x1 shaft cut into a stone mound — the first version dug into the flat floor, left the world at -64, fell into the void and PASSED on the respawn height; the scene is checked now).
- Verification: shaft_exit out of ten deep in 6.5 s, 22 deep in 9.9 s, 22 deep with the planner starved to 20 ms in 10.1 s — all with a COMPLETE plan (`navPartial=0/0`), so the tower hand-off from a shaft is proven and the G98 partial branch is not exercised by this bench; it rests on the run's log signature until a scene reproduces the partial (the planner generated 28 pillar moves against 100k climb nodes there — recorded under G98). pit_escape PASS, self_floor 3.2 s, two_driver 4.7 s transit 0, nav suite 14/14 with no invalid runs. Released **v0.95.2** (c594f758).

### The second 60-minute run, on v0.95.2 (15:10 UTC, fresh start #49, recorded, frames reviewed)
- Iron tools 657 s, bucket 796 s, a shaft to y=0 for diamonds (281 items by t=1299) — then **three deaths**: drowned in a flooded cave at y=0 (frame 21:30, "Reaching breathable air — Finding a reachable air pocket", hp 12, finished by a zombie; G100), the respawn at world spawn 1700 blocks away with nothing; rebuilt to the **nether stage** (t=1948, "Going to Nether → Construct Nether Portal → Getting flint & steel" — the first time), then at night by the spawn a zombie at melee range with no weapon, `NIGERUNDAYOO`, death two (frame 33:50) and three (G101, data for the deferred night track). The last fifteen minutes at the lip of a pool, (93,107,-113): MovementSwim declared the one-cell stroke onto the bank done by distance with the feet still in the water, released the keys, the water carried the body back, "body has not left … for 121 ticks" ×106 (G99). The pit and shaft walls of the first run did not recur (`navPartialBuild=16`, `navHandoffRest=10/1`).
- Fix: a swim stroke whose destination is not liquid arrives only with the feet on it; the 0.6 tolerance stays for water-to-water strokes. Bench `pool_bank_test.py` (a 3x3 pool two deep, the goal the bank cell east of it). Also recorded: `Average Position` printed 13446 times (G102) — the end-portal average, logged once per value now.
- The bench's first run FAILED and found the other half (G99b): the navigator's sphere arrival accepted a body IN THE WATER as settled — "arrived (1.6)" with the body rising at y=-61.04 toward the bank at -60 (goalRise 0.95 for one tick) — stopped the queue mid-stroke, and the body floated with no inputs until it drowned 22 s later (no survival chain under a bare gotoXYZ). Water counts as settled only when the goal itself is in water.
- Verification: pool_bank PASS twice, on the bank in 2.3 s (`mqSteps=3 swimAim=23 navWet=1/0`); nav_water PASS twice; nav suite 14/14 (nav_gaps' first-go fall, the recorded course flake, passed on the retry). Released **v0.95.3**.

### Checkpoints (operator: "ты бы уж тогда фиксировал ЧЕКПОИНТ где начинаются косяки")
- Two sixty-minute runs from an empty inventory in one day, each spending thirty minutes to reach the state whose wall was the question, was the mistake. `deploy/runner/checkpoint.py` freezes the whole gamer world (2082 MB, 29.7 s: save-off, save-all flush, docker cp, save-on; meta.json with seed, time of day, the bot's position/hp/inventory ids when online) and restores it (kick, stop, swap `/data/world`, chown to the server's user, start, wait for rcon). `gamer_smoke.py --from NAME` swaps the world in right before `@gamer` (the reset and the spiral still run and are discarded with the swap; `GAMER_SPAWN` pinned so the forest search does not spend minutes), `--checkpoint-every MIN` freezes the middle of a run, and the end of every run is frozen as `last` unless `--no-save-end`. First checkpoint on disk: `post-run2` (the end of the second sixty-minute run). Checklist §4y.7, AUTOTESTING.md.

### The third run, and what the checkpoints found (v0.95.3, fresh start #56)
- Ladder to food in four minutes (wood 22 s, stone tools 87 s, furnace/coal 197 s, food 240 s), then **46 minutes with no advance and no iron**: from t≈850 to t=3620 the bot alternated "Doing stuff in crafting_table: [[bread] x 17]" (an empty grid, "Moving wheat x 17 to slot", 1 wheat in the pack) and "Picking up the crafting table while we are at it" (break fails, "Maybe private area", "Blacklisting extra crafting table" ×41). Two roots (G103): `foodUnits=220` is a speedrun stockpile that keeps food ranked over iron with 11 bread already held, and a food craft that cannot progress (no wheat, no reachable source) does not yield to the next rung as the pursuit budget and nav give-up do.
- Checkpoints earned their keep and corrected the diagnosis: resuming `cp0916-2241-t900` (15 min in) did NOT reproduce the loop — the bot went straight on, iron reached at 347 s (six minutes from the saved state, the time a fresh run spends on wood). So the deadlock is a later, state-specific accretion (several unbreakable placed tables, the wedged wheat, the blacklists), held in `loop-bread` (the end-of-run world), not at minute 15. G103 is fixed and reproduced from `loop-bread`, next pass — not from a fresh hour.

### G106 runtime-confirmed, and the next ceiling: a cave-mob death + a planner NPE (v0.95.9 -> G107)
- **G106 confirmed at runtime** (re-verify from `cp0918-1030-t494`, `--daylock`, recorded). foodUnits=140 is live: no "Collect 220" and no food-grind phase at all -- the bot went iron -> smelt -> water bucket -> diamonds (y6) -> gold (y2), hp steady 20/20, items 39 -> 182, `dmgWhy` fall/lava/fire/drown all 0. The stale 0.95.8 showed "Collect 220" and 25 min in overworld here; 0.95.9 does not. Operator told (TG 9020) + sped-up video (9021). Loop closed.
- **The death that ends the run (in-scope report, scope flagged):** at y=2 the bot walked into a large dark cave that is a mob nest. Frames: zombie (KillEntityTask, stone sword) -> creeper (RunAwayFromCreepersTask) -> skeletons (RunAwayFromHostilesTask, "flee 30"). DamageWatch: `deathsSeen=1`, 7 hits / 23 dmg, all melee range (`rangedHits=0`, gap 1.5-4.1), classified `dmgOther` -- a mob, not terrain. Respawn at world spawn, empty inventory = base loss. Then the bot **wedged** re-gathering wood, targeting a dark_oak_log 6 blocks up in a canopy ("reach route gave up") for 90+s (Finding B, open).
- **G107 (fixed, v0.95.10): the flee plan crashed with an off-thread NPE.** At the death moment, one `FastPlanner.plan` on the `FastNavigator-plan` thread hit `Cannot invoke BlockState.isIn(...) $$1 null` -- the search reads a live ClientWorld while the render thread swaps chunk sections, and `getBlockState` returned null for one read; the block-classification predicates dereferenced it and failed the whole plan (so no escape route). Root cause found by static analysis + the remapped-jar name-stripping ($$1 = the null receiver). Fix: `Agent.isClimbing/isFenceLike/getLandingPos` and `BlockStateChecker.isConnected/isFenceLikeBlock/isFenceOrWall` treat a null read as air-like (vanilla EmptyChunk semantics); `FastNavigator`'s two catches now log the top stack frames, not just `getMessage()` (the message named no class/line, which is why finding it took a pass). Compiled clean; **verified in the built jar** by javap (`isClimbing` does `ifnonnull / iconst_0 / ireturn` before the `isIn`/method_26164 call, inside the nested tungsten jar). Committed 243a2cab.
- **Scope note to operator (TG 9020):** the cave-mob swarm death is combat/survival (near the deferred night/mob-preemption track); only the pure-pathfinder bugs (G107 NPE; Finding B wedge) are taken as in-scope for now. Asked whether to prioritise combat survival.

### Next frontier reproduced: the nether-portal CAST stalls on the upper frame (G108, open)
- After G107 the bot runs the full post-iron chain; the 12-min run reached "Going to Nether ->
  Construct Nether Portal -> Getting flint & steel", the 22-min run over-mined (467 items, RNG
  path) and did not reach nether in the window (both PASS, 0 deaths). Checkpoints saved
  (cp0918-1418-t240/t521/t806/t1083, save-end `nether-reach`).
- New bench `deploy/runner/nether_portal_test.py`: flat stand, a 5x5 lava SOURCE lake + prereqs
  (water bucket, empty bucket, flint&steel, cobblestone) + `@build portal`
  (BuildCommand -> ConstructNetherPortalBucketTask). Deterministic, ~4 min, no 22-min run needed.
  Controls: LAVA_SIZE=3 (below the >=12 gate), NO_ROOM=1 (no portalable region).
- CEILING (G108): the cast STARTS (lower frame obsidian placed via PlaceObsidianBucketTask:
  mold CAST_FRAME + lava + water) then STALLS on the UPPER frame blocks (mid-air, y ~2+ above
  the floor). PlaceObsidianBucketTask's only failure response is reactive:
  `_progressChecker.check` fail -> `Nav.cancel` + `requestBlockUnreachable(_pos)` +
  `TimeoutWanderTask(5)` -> the bot loops "Wander for 5.0 blocks > Exploring" forever, portal
  never completes. Bench FAILs (no portal in 180s); bot has ALL materials (both buckets,
  cobblestone x43, flint&steel), so it is the mid-air mold/cast that fails, not a missing item.
- First bench run also surfaced a setup-sensitivity: with NO throwaway blocks given, the cast's
  "Place structure" step reports "No placeable block in the inventory" and shimmy-loops -- a real
  run has cobblestone, so the bench now gives it; the REMAINING stall (upper frame) is the real
  ceiling.
- Root to fix (next pass, carefully -- not a band-aid): the reactive wander is the operator's
  disliked anti-pattern; the real problem is casting the upper (mid-air) frame reliably. The bot
  has a diamond pickaxe by this stage, so mining obsidian (ConstructNetherPortalObsidianTask) may
  be the more robust method than the bucket cast -- to be weighed in the fix pass.

### G108 fixed: place obsidian instead of casting it in place for the nether portal
- Bench A/B on `nether_portal_test.py` (5x5 lava lake, flat stand), decisive:
  - `@build portal` (bucket cast, the old default): FAIL -- lower frame casts, then STALLS on the
    upper (mid-air) frame; PlaceObsidianBucketTask's progress check trips -> TimeoutWanderTask(5)
    -> "Wander for 5.0 blocks" forever, portal never completes.
  - `@build portalobs` (obsidian method), placement isolated (given 12 obsidian + diamond pick):
    PASS, "Done constructing nether portal" in ~139 s -- it PLACES the upper frame blocks
    (`Place structure{obsidian} at ...,-55/-54,...`) the cast could not form.
  - obsidian method, FULL path (gathers obsidian by ground-cast + mine): steady progress, NO
    stall (wanderHits=0), slower (gathering 10 obsidian by cast+mine is ~150-400 s and varies).
- Fix (`DefaultGoToDimensionTask.goToNetherFromOverworldTask`): for BUILD_PORTAL_VANILLA, prefer
  `ConstructNetherPortalObsidianTask` when the bot has a diamond pickaxe (true by the nether
  stage -- it can mine cast obsidian); keep the bucket cast as the fallback for the no-diamond
  case. Placing obsidian blocks is a normal mid-air place (PlaceStructureBlockTask scaffolds
  itself) -- reliable -- vs forming each block in place, which cannot build a mould in the air.
- Also added `@build portalobs` (BuildCommand) to bench the obsidian builder directly, and an
  `OBS`/`GIVE_OBS` mode to `nether_portal_test.py`. Compiles clean.
- Known follow-up: obsidian GATHERING (cast at ground + mine, 10 blocks) is slow/variable; it
  completes without stalling, but speeding it up is a separate optimization (G108b).

### G108 correction: BOTH portal methods stall; frame-fix committed, NOT released
- The definitive full-path obsidian run (720s, gather + place) did NOT complete cleanly: the
  obsidian GATHERING (CollectObsidianTask) dug a 1-deep pit, cast one obsidian at (2347,-59) and
  then STALLED ~60s "Block in range, mining..." on it (a down-diagonal mining-aim stall, 2 below
  feet / 1 over), never collecting the 10 blocks. Inv confirmed 0 obsidian, wanderHits=0.
- So the fix is NOT clean end-to-end: the bucket cast stalls on the upper (mid-air) FRAME, and
  the obsidian method's FRAME-PLACEMENT is robust (benched PASS 139s) but its obsidian GATHERING
  stalls on a down-aim mine of self-cast pit obsidian. The routing fix (8529ed12, prefer obsidian
  w/ diamond pick) is a real improvement to the frame-build and is COMMITTED, but 0.95.11 is NOT
  released -- the portal does not yet complete end-to-end on the bench.
- Nether-portal building is a deeply fragile area (both builders stall in different places); a
  clean fix is a dedicated multi-pass effort. Next tractable step: determine whether the gathering
  mining-aim stall is a real bug or the flat-floor bench forcing a cramped self-cast pit (a
  realistic lava-basin scene, or reading breakAim/dbAimWait/breakMissWhy at the stall).

### G108 re-correction: the obsidian GATHERING "stall" was a bench artifact, not a bot bug
- Traced the earlier obsidian-gathering "stall" to root: the bot was NOT holding the diamond
  pickaxe (getGameState held='empty') while trying to mine obsidian -- obsidian is unbreakable by
  hand, so "Block in range, mining" hung forever. Isolated test: place accessible obsidian +
  `give diamond_pickaxe` + `@get obsidian` -> 0 mined, held=empty, breakAim=0/mine=0 (break
  executor never fired). FORCING the pickaxe into the main hand (`item replace ... weapon.mainhand`)
  -> the bot mined all 3 obsidian immediately (obs=3). So mining obsidian WORKS; the bot just does
  not auto-equip an rcon-`give`n item. A real run holds its self-crafted pickaxe (iron/diamond
  mining already proves the equip path), so this does not occur in a real playthrough.
- Second flat-bench artifact: with the pickaxe forced in hand the builder placed the frame, then
  needed a fresh bucket and dropped into "Mine And Collect raw_iron -> Wander for Infinity" -- the
  flat stand has NO iron. A real run has iron + buckets. So the full obsidian path cannot be
  faithfully benched on the flat stand; the FRAME BUILD is proven (PASS 139s) and the full path is
  validated by a REAL @gamer run.
- Net: the G108 fix (prefer obsidian, place the frame) targets the ONE real ceiling (bucket
  mid-air cast stall). Running a real @gamer validation from the `nether-reach` checkpoint with the
  fix deployed to confirm end-to-end before releasing 0.95.11.

### G108 DEFINITIVE root: PlaceObsidianBucketTask's cast fragility, shared by BOTH portal methods
- Faithful real-world test: `@build portalobs` on the gamer server, bot with self-crafted
  EQUIPPED diamond pickaxe + buckets + flint&steel in real terrain. The obsidian GATHERING WORKED
  there (obs climbed 0->7, mining real obsidian) -- confirming the flat-bench gathering stall was a
  pure harness artifact (rcon-give not equipped). BUT it then stuck at "Place structure{...} ->
  Wander for 5.0 blocks" at y=27 in the chaotic mined cavern.
- Root: BOTH portal builders ultimately rely on PlaceObsidianBucketTask -- the bucket method casts
  each FRAME block with it; the obsidian method's CollectObsidianTask casts obsidian at ground with
  it. PlaceObsidianBucketTask builds a 10-block cast mould (CAST_FRAME) + lava + water; when the
  mould cannot be built (mid-air upper frame, OR cramped/chaotic mined terrain), its progress check
  trips -> `requestBlockUnreachable(_pos)` + `TimeoutWanderTask(5)` -> "Wander for 5.0 blocks"
  loop, and the portal never completes. This reactive wander is the shared fragility.
- So the routing fix (prefer obsidian, 8529ed12) is a real but PARTIAL improvement: obsidian
  frame-PLACEMENT is robust (bench PASS 139s) vs the bucket cast stalling on the upper frame; but
  the obsidian GATHERING's ground-cast still hits PlaceObsidianBucketTask's wander in chaotic
  terrain. NOT a complete G108 fix; 0.95.11 NOT released.
- The CLEAN G108 fix is a dedicated core pass on PlaceObsidianBucketTask: build the cast mould
  reliably (or cast at a guaranteed-open spot, e.g. the surface) and replace the reactive
  TimeoutWanderTask give-up with a real cast-spot search. That is the shared root both methods need.

### G108 progress: obsidian GATHERING spot-finding fixed (infinite wander gone); frame-placement-in-dirtied-terrain is the next layer
- Reproduced the obsidian-gathering infinite wander ON THE FLAT OPEN BENCH: `CollectObsidianTask.
  getGoodObsidianPosition` scanned a 7x7x7 (radius 3) and required EVERY cell canBreak AND canPlace
  -- both need `canReach`, and buried/distant cells in that radius are unreachable, so it returned
  null on ordinary ground -> "Walking until we find a spot to place obsidian -> Wander for
  Infinity", walking the bot 1000+ blocks away, zero obsidian cast.
- FIX: check the actual cast footprint (radius 1 in x/z, y -1..+2 -- what PlaceObsidianBucketTask's
  CAST_FRAME + _pos + _pos.up(2) touch) instead of a 7x7x7. Aligns the spot test with what the
  cast needs; bedrock/avoided/unreachable cells still disqualify. Compiled, built, deployed.
- VERIFIED the fix advances the state: the bench bot now GATHERS its obsidian and reaches frame
  placement (was: infinite wander at gathering). NEXT LAYER: it then stalls placing the UPPER frame
  block ("Place structure{obsidian} at ...,-55,... -> Wander for 5.0 blocks") because the gathering
  cast obsidian + dug lava pits AT the build site, and the frame's mid-air scaffold
  (PlaceStructureBlockTask) can't build in that dirtied terrain -- the give-obsidian placement test
  (pristine terrain) PASSes the same upper block. So: gather obsidian AWAY from the build site (or
  choose a clean origin before gathering), the next focused sub-fix.

### G108 conclusion this session: portal is fundamentally fragile; two sub-fixes shipped, a clean fix needs a redesign
- Attempted a THIRD layer (clean build siting: scan outward for an open, solid-floored site so the
  frame is not placed in gather-dirtied terrain). It advanced the state (bot gathered + placed some
  frame) but did NOT complete the portal (wide in-JVM scan: 0 nether_portal blocks), and requiring
  an all-air site risks a cave-build regression. UNVERIFIED -> REVERTED (main keeps only verified
  fixes). The bench's break-on-busy=False also fired on a transient no-task tick (a detector flaw
  to fix if the bench is used again).
- Root, restated: BOTH portal builders funnel through PlaceObsidianBucketTask's per-block cast (a
  10-block mould + lava + water). It is fragile in exactly the situations a real portal build hits
  -- the mid-air upper frame, and cramped/dirtied terrain -- and its only failure response is the
  reactive TimeoutWander. Layer-fixing (routing -> gathering -> siting) each moves the failure
  forward but does not green it, which is the signal that the CAST APPROACH itself is the problem.
- Recommendation for the clean fix (a dedicated pass, likely multi-session): replace the per-block
  cast with the speedrun-standard obsidian collection -- flood a lava LAKE with one water bucket so
  a whole sheet becomes obsidian, then mine it -- and build the frame at a chosen open flat site.
  That removes the mid-air mould entirely (the shared fragility). Big, careful, and best fresh.
- SHIPPED this session that DID move the score: G106 (confirmed), G107 (0.95.10), G107b (0.95.11),
  and the obsidian gathering spot-finding fix (committed). Post-iron progression to the nether
  transition is reliable (clean run, 0 deaths). The nether-portal BUILD is the standing ceiling.

### G108 redesign attempt: flood-lava-lake obsidian collection -- PROMISING, partial, needs iteration
- Confirmed the mechanic on the stand: a water source ABOVE or ADJACENT to a lava SOURCE turns it
  to obsidian (both -> minecraft:obsidian). This uses lava the bot already found -- NO 10-block
  mould, NO placed lava (the shared fragility of PlaceObsidianBucketTask).
- Implemented a first flood path in CollectObsidianTask (flood the nearest reachable lake lava
  source -> the existing "mine nearby obsidian" branch collects it -> reclaim the water) and
  bench-tested it. IT PARTIALLY WORKED: early in the run the bot mined obsidian straight from the
  flooded 5x5 lake (Destroy block at 2365..2368,-58 = the lake, now obsidian) -- real gathering via
  flooding, no per-block cast. But then it dropped into "Mine And Collect raw_iron -> Wander for
  Infinity": the water-bucket reclaim did not recover the bucket reliably (geometry/timing), so it
  tried to craft a new one -> needs iron -> none on the flat stand -> wander. UNVERIFIED + does not
  complete the portal -> REVERTED (main keeps only the verified gathering fix).
- Next pass (the redesign, fresh): (1) reliable water reclaim after each conversion (ClearLiquid at
  the exact water source, confirm the bucket is back before moving on), OR place water once at a
  lake's high edge to flood a whole sheet and mine that; (2) full-lake coverage so >=10 obsidian is
  produced; (3) build the frame at a chosen OPEN site away from the mined lake (the reverted
  clean-siting scan is the sketch); (4) verify end-to-end on a REAL @gamer run (the flat stand's
  no-iron + rcon-give-equip artifacts mask the real path). The mechanic works; the plumbing needs
  careful iteration. This is a dedicated multi-iteration effort.

## 2026-09-19 (cont.) — G108 portal: flood-reclaim churn FIXED (gather 0->14 clean); top-row build is the new ceiling

The gather-phase re-flood churn (the standing blocker from the previous entry) is fixed at the root
and VERIFIED. Committed b247b852, pushed, main + 1.21.11 synced.

- **Root of the gather churn (PlaceObsidianFloodTask reclaim).** The flood converts the pool to
  obsidian but leaves a wide WATER SHEET on top, because the reclaim scooped ONE fixed cell
  (`_waterCell`). `ClearLiquidTask` scoops only SOURCE blocks (SOURCE_ONLY raytrace) yet its
  `isFinished` waits for that cell to be fluid-EMPTY -- so when `_waterCell` held FLOWING water it
  could neither scoop nor finish, the 25s cycle deadline fired, the sheet was left, the submerged
  obsidian failed `canReach` (=> `canBreak` false), the miner found nothing, and CollectObsidianTask
  re-flooded -- accumulating sources forever (measured: pool y=-58 all obsidian, y=-57 all water, 33
  water blocks, obs=0, bucket=17, chat spamming "reclaim skipped").
- **Fix.** Reclaim now scans a RECLAIM_RADIUS box for actual still-water SOURCE blocks
  (`WorldHelper.isSourceBlock(p,true)`) and scoops the nearest reachable one until none remain;
  removing a source drains all the flowing water it feeds, so the whole sheet clears and the obsidian
  is exposed. Per-source `MovementProgressChecker` blacklists an unreachable source and moves on
  (deterministic termination -- no timeout band-aid); a 2.5s drain-settle lets flowing water dissipate
  before `_done` so the miner never sees submerged obsidian. Removed `_cycleDeadline` entirely.
- **Verified (bench OBS flood path, 5x5 lake, WINDOW_S=320).** obsidian gathers 0->14 steadily,
  ~10s/block, ZERO re-flood, ZERO "reclaim skipped", wanderHits=0. Previously churned indefinitely at
  obs=0. Then the frame build starts.

### New ceiling: the frame TOP ROW (y=origin+3) build
Ground-truth of the frame after a full run (origin 2358,-56,358): bottom row 4/4 obsidian, left
column 3/3, right column 3/3 -- **10/14 clean**. Top row 0/4 (all air, never placed). Interior has
TWO stray obsidian at (0,0,0),(0,1,0) -- these are PILLAR STEPS.
- **Mechanism.** PlaceBlockTask hands the top-row cell to `BlockPlaceHelper.beginBatch([target],
  "obsidian")`. tungsten cannot reach a cell 4 blocks up over the open interior, so it PILLARS up in
  the interior column below the target -- placing the TARGET block (obsidian) as the pillar steps.
  That obsidian (a) pollutes the portal interior (must be air) and (b) is unprotected, so the gather
  mines it back as "nearby obsidian" -> place-pillar / mine-pillar / re-gather loop, obs oscillating
  3-5, top row never finishes in the window (result: no portal after 320s).
- **Planned fix (in progress).** Make the placer pillar/scaffold with a THROWAWAY block
  (cobblestone), not the structure block: then the gather never touches it (cobble != obsidian, no
  churn), and the existing "Clear middle" phase (destroys PORTAL_INTERIOR non-air before lighting)
  removes the cobble pillar before flint&steel. A sub-agent is confirming where tungsten chooses the
  pillar block. This should green the full flood->build->light path.

## 2026-09-19 (cont. 2) — G108 portal LIGHTS end-to-end (flood -> gather -> build -> light)

The full obsidian-flood nether-portal path now builds AND lights a real portal on the stand -- the
standing post-iron ceiling for weeks. Two fixes did it (the reclaim fix above was the third).

- **tungsten BlockPlaceHelper.equipThrowaway: pillar/bridge with the CHEAPEST scaffold, never a
  valuable full-cube already in hand.** The build queue pre-equips the structure block before a
  pillar step (drainQueue pillar branch -> equipBlock(headCell.blockName())), and OBSIDIAN passes
  isScaffold (rank 5), so equipThrowaway's "already holding a full cube -> keep it" short-circuit
  towered the portal up out of its OWN obsidian -- pillar steps landing in the interior, which the
  gather then mined back (place/mine loop, top row never built, 0 portal). Now it keeps the held
  block only when it is already at least as cheap as the cheapest hotbar scaffold; obsidian (rank 5)
  is swapped for cobblestone (rank 0) when available. Pillar/scaffold steps become throwaway; the
  interior stays clean, the Clear-middle phase removes any throwaway, and the frame's obsidian is
  spent only on frame cells.
- **deploy/runner/nether_portal_test.py: findportal matches the registry id, not the localized
  display name.** getBlockAt's "block"/"name" is localized ("портал незера" on this ru client), so
  the old substring test for "nether_portal" reported every real, lit portal as FAIL -- a harness
  bug that masked success. Read the "id" field ("minecraft:nether_portal") instead.
- **Verified (bench OBS flood path, 5x5 lake), ground truth by block id at the build site:** frame
  14/14 obsidian (bottom row + both columns + all 4 top-row cells), interior 6/6 nether_portal.
  Confirmed on two independent runs (GIVE_OBS build-only and full flood OBS).

### Known follow-up: top-row MIDDLE placement is functional but not yet deterministic
The two top-row middle cells sit over the open interior (no solid below, no reachable side stand),
so tungsten's queue pillar branch (which needs a standable cell) does not apply and PlaceBlockTask
places them via its reactive wander + "go above the block" alternative (~36 s per middle on the
stand; it recovers and places obsidian). It works but is slow/flaky. A deterministic fix -- have the
queue pillar-place a TARGET cell with the STRUCTURE block (PillarTask always calls equipThrowaway, so
it cannot currently place obsidian for a target; it only worked here because no cobblestone was in
the HOTBAR during the frame build) -- is the clean next pass. An attempt to pre-build a cobblestone
support column under each middle was REVERTED: it put cobblestone in the hotbar, which made the now-
standable middle pillar-place cobble (equipThrowaway), triggering a place/destroy/replace churn. The
milestone (portal lights) does not depend on it.

### Correction + hardening: the portal build CONVERGES; the "FAIL" verdicts were harness bugs
After the milestone commit (0164779b) a fresh full-OBS run reported FAIL at 340s -- which looked like
the portal was flaky. Ground truth by block id says otherwise: the portal build CONVERGES and lights
on every run checked (three: GIVE_OBS build-only, full flood OBS, and a 520s full flood OBS -- each
left frame 14/14 obsidian + interior 6/6 nether_portal). Two HARNESS bugs and one slow path, not a
bot regression:
- **Bench window too short.** The top-row is placed by PlaceBlockTask's reactive wander (~150s), so
  the full flood->build->light path lands ~280-500s; the 340s window cut a run off at 13/14 (right
  corner still air). WINDOW_S now defaults to 500 for the OBS path.
- **portal_found swallowed py4j errors.** A single py4j hiccup during the busy build made it return
  None -> "no portal near the bot", while a real lit portal stood at the site. It now retries (3x,
  90s timeout) before believing a negative; only a clean scan that finds nothing is a real FAIL.
- **Obsidian reserve.** A top-row placement occasionally loses a block (a mis-place the loop clears,
  or a drop while repositioning at head height); re-gathering one block is a full round-trip to the
  lake mid-build, which is what ran the 340s window out. ConstructNetherPortalObsidianTask now
  gathers neededObsidian + 3 so a small loss does not force that trip.
- **Remaining optimization (not a blocker):** the top-row wander is slow and wide-ranging (the bot
  wandered 13-19 blocks off during a middle placement). A deterministic top-row placement (support
  column making each mid-air cell standable + the queue pillar placing the STRUCTURE block, which
  needs a PillarTask block param -- it currently always equipThrowaway) would make the build fast and
  tight. Documented for a dedicated pass; the portal lights without it.

### SHARP CORRECTION: the portal is NOT reliably green -- the top row HARD-STALLS (nav, not slowness)
A 4th full-OBS run (default 500s window, robust detection, reserve buffer) HARD-STALLED: the bot
placed 13/14 then froze on the ground at the last top-row corner (0,3,2) for 110s+, alternating
"Placing frame" / "No tasks", never elevating, never wandering, until killed. So the earlier
"converges reliably" read was WRONG -- it was three lucky runs. The portal lights only when the
stochastic top-row placement happens to succeed on all four y+3 cells; it hard-stalls otherwise.

Precise root (traced, BlockPlaceHelper):
- placementStand correctly returns null for a top-row cell over the gap (the "stand on top" fallback
  at line ~723 needs target.up() standable, which it is not over an open interior), so drainQueue
  takes the PILLAR branch (sideStand==null && standable(head)).
- The pillar branch then needs the body standing IN the target cell, so it calls
  FastNavigator.startExact(head) to walk there. FastNavigator CANNOT reliably path the bot to stand
  in a y+3 cell perched on a 1-wide column top over the frame gap -- it defers (walk cap), the queue
  empties, PlaceBlockTask re-submits, and the body never moves. That tight defer/re-submit loop is
  the hard stall (bot frozen on the ground at the last corner).

So the remaining flaw is a tungsten NAV reliability problem: reaching/standing to place a high cell
(y=origin+3) over an open gap. Candidate fixes for the next focused pass:
- Give the placer a reachable SIDE stand for each top-row cell by pre-placing a throwaway support in
  FRONT of it (a cobble at (1,2,z) makes (1,3,z) standable beside the target); then adjacentStand
  finds it and the bot places from the side instead of pillaring into a narrow elevated cell. Remove
  the supports before lighting (the x=+1,z=0/1 cells are already in PORTAL_INTERIOR's clear; z=-1/2
  are harmless strays). This sidesteps the flaky pillar-into-cell entirely -- most promising.
- OR harden FastNavigator.startExact to reliably pillar-into a high-over-gap cell.
Both are careful tungsten work with nav-suite regression risk; deferred to a dedicated pass. VERIFIED
and shipped this session: the flood gather reclaim (reliable 0->14), the tungsten pillar-block fix
(no obsidian pillars), and the trustworthy bench. The portal's bottom row + both columns build
reliably; the top row is the ceiling.

### FIX SHIPPED: top row via a temporary FRONT SCAFFOLD -- clean, fast, ground-truth green (01a85111)
The top-row hard-stall is fixed at the root. Before placing a top-row cell, ConstructNetherPortal now
raises a temporary cobblestone standing wall one row in FRONT of the frame (x=+1, up to y=+2), built
bottom-up like the columns. That gives the bot a stable strip to stand on (feet at (1,y+3,z), on top
of the wall), so placementStand returns a SIDE stand and the top row places obsidian via the reliable
side-stand path -- never the pillar-into-cell that FastNavigator could not reach. The wall is mined
back out (top-down) before the interior is cleared and the portal is lit; cobblestone, so the gather
never touches it.
- **First run, ground truth by block id:** whole frame 14/14 obsidian (incl. all four top-row cells
  via the side stand), scaffold 0 remnants, interior 6/6 nether_portal, DONE at t=308 (vs 450-620s of
  churn before, and no hard-stall). The deterministic build is also much faster.
- **Reliability rate:** running the OBS bench x4 more (RULE FIVE) with a ground-truth id scan per run
  (the bench's own portal_found proved flaky during a busy run -- returns None while a real lit portal
  stands at the site; a manual run of the identical scan finds it, so ground truth is the arbiter).
- **Known secondary flakiness (flat stand):** one run spent ~90s early on "searching for liquid /
  Wander for Infinity" before the first flood produced minable obsidian (the flood/reclaim
  occasionally leaves no water bucket and the flat stand has no natural water). It recovered. A real
  run has natural water; noted as a gather-robustness follow-up, separate from the top-row fix.

### Bench blind spot found: the "no portal" verdicts were a STRING-INDEXING bug, not py4j flakiness
The bench reported "done constructing but NO portal near the bot" on every green run. Root: the bot
position from py4j comes back as a "x,y,z" STRING, and the confirmation scanned
`portal_found(int(pos[0]), FLOOR_Y-1, int(pos[2]), ...)` -- `int(pos[0])`/`int(pos[2])` take the
first and THIRD CHARACTERS ('2' and '6' of "2360.1,..."), so it scanned around (2,6) and missed the
real portal at ~(2358,358). A manual scan with explicit ints always found it, which is why the code
"looked" right. Fixed with a `scan_center()` that parses the string (deploy/runner/nether_portal_test.py).
The earlier id-field and retry fixes were real but secondary; THIS was the blind spot. With it, the
bench detects the lit portal directly (no ground-truth workaround needed).

### MILESTONE: nether portal RELIABLY GREEN + released v0.95.14 (G108 done)
The obsidian nether-portal path builds and lights end to end, reliably. Reliability rate (checklist
RULE FIVE), OBS bench, ground truth by block id (6/6 nether_portal per run): flood_scaffold2 GREEN,
first-harness run1 GREEN, then harness runs 1/2/3 GREEN (run2/run3 the FIXED bench also self-reported
"PASS: NETHER_PORTAL LIT ... in ~223s") -> 5 green runs, no top-row hard-stall, ~220s per build.
Confirmed on the REAL @gamer path: DefaultGoToDimensionTask.goToNetherFromOverworldTask() builds via
ConstructNetherPortalObsidianTask (the task the front-scaffold fix changed), obsidian preferred over
the bucket cast. Released as v0.95.14 (gradlew :1.21.11:githubRelease; asset
unionclef-1.21.11-0.95.14.jar verified via gh release view).

The four fixes that got here (all committed + pushed, main + 1.21.11 synced): flood reclaim scoops
real sources (b247b852); tungsten pillars with the cheapest scaffold not obsidian (0164779b);
top row built from a temporary front scaffold, not a mid-air pillar (01a85111); trustworthy bench --
block-id match + retry + pos-string parse (0164779b/98cb7344/3c9b6501).

Remaining follow-ups (not blockers, documented): (1) the flat-stand water-search hiccup (~40s
intermittent before the first flood produces minable obsidian; a real world has natural water); (2)
the bench's portal_found string-index was the "no portal" blind spot -- fixed, but worth a broader
sweep for the same int(str) pattern elsewhere. Next ceiling is post-portal (the nether), which needs
a full @gamer playthrough -- fps-limited on this stand.

### 2026-09-19 (cont. 3) — fix B (cobble-in-frame churn) shipped; deep nav-stall is the ceiling
Fix B (f5a6692b): a build-queue pillar placing a TARGET cell now towers with the STRUCTURE block
(obsidian), not the cheapest throwaway. Verified: OBS bench run built the whole frame with ZERO
frame-region destroys (obs 14->2, no place/destroy churn). Also EnterNetherPortalTask now constructs
via the obsidian method (or the caller's task), never the fragile bucket cast (the getPortalTask was
dead code). Released 0.95.15.

REMAINING CEILING (deep, intermittent ~1 in 4-5 builds): a placement/nav stall that fix B does NOT
address. Ground truth from a hard stall: the bot placed cobblestone at (2360,-57,358) -- which is the
SIDE STAND it needs to stand in to place the front-scaffold cell (2359,-57,358)=(1,-1,0) -- and got
stuck on top of it, "Placing cobblestone" forever. So the bot mis-places a throwaway at/near its own
placement stand (FastNavigator approach, or placementStand picking an about-to-be-occupied stand),
then cannot stand there. Hits frame cells (a bottom-row run-4 stall) and front-scaffold cells (this
one) alike. This is a tungsten placementStand/FastNavigator reliability bug -- the next focused pass:
trace why a throwaway lands on the chosen side stand and prevent it (or re-pick a stand when the
first is occupied). The front-scaffold also ADDS cells that can hit this, so consider whether a
smaller/no-scaffold top-row approach nets better once the stall is fixed.
