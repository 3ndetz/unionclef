# Jump Bridge Schema

Jump bridge mode (`bridgingMode = "jump"`) — sprint-jump forward, place blocks mid-air.

Legend:
- `0` — air (no block)
- `1` — solid block
- `x` — player position (2 blocks tall)
- `->` — movement direction

---

## Situation 1: Foundation exists

The starting platform already has 2-block height (floor + foundation).
Jump bridge can work — we have something to build FROM.

### 1.1 Starting position

```
000x
000x
1111 ->
0001
```

- Foundation: YES (2 blocks at the junction — floor + foundation below)
- Sprint runway: NO (player is at the edge)
- Platform for runway: YES (3 blocks behind)

### 1.2 Back up for sprint

```
00x0
00x0
1111 ->
0001
```

Player walks backward to create sprint distance.

### 1.3 Sprint-jump

```
0000x
0000x
00000
1111 ->
0001
```

Player sprinted forward and jumped. Now airborne, rotating backward.

### 1.4 Place foundation + floor (first column)

```
0000x
0000x
00000
11111 ->
00011
```

Mid-air: placed 2 blocks at the first gap position.
Order: foundation (lower) first, then floor (upper).
Both placed by clicking the +dir face of the existing 2-block column.

### 1.5 Place foundation + floor (second column)

```
00000x
00000x
000000
111111 ->
000111
```

Still airborne: placed 2 more blocks at the next position.
The previously placed column serves as the new target for clicking.

### 1.6 Landing — ready for next iteration

```
00000x
00000x
111111 ->
000111
```

Player landed on the bridge. Sneak to stop.
The bridge is 2 blocks tall — ready for the next sprint-jump cycle.
Player backs up on the placed blocks, sprints, jumps again.

---

## Situation 2: No foundation

The starting platform is only 1 block tall (floor only, no foundation below).

```
00x0
00x0
1111 ->
0000
```

Jump bridge cannot activate — no 2-block column to click against.

### 2.1 Build foundation first

Player places blocks UNDER the existing floor to create foundation.
(Pillar down / place below self)

```
00x0
00x0
1111
1111 ->
0000
```

Foundation built. Now the platform is 2 blocks tall.
Proceed to Situation 1.1.

---

## State machine summary

```
[Start]
   |
   v
Has foundation? ──NO──> Build foundation (slow/pillar down)
   |                          |
  YES                         v
   |                     Has foundation now
   |<─────────────────────────┘
   v
Has 3-block runway? ──NO──> Slow bridge (place 1 floor block)
   |                              |
  YES                             v
   |                         Re-check on next tick
   |<─────────────────────────────┘
   v
Is sprinting? ──NO──> Back up for sprint (walk backward on platform)
   |                        |
  YES                       v
   |                   Sprint forward
   |<───────────────────────┘
   v
Sprint-jump at edge
   |
   v
[AIRBORNE]
   |
   v
Place foundation + floor columns (2 blocks per position)
   |
   v
[LAND]
   |
   v
Sneak to stop, back up, sprint, jump again
   |
   v
[Repeat from "Has 3-block runway?"]
```

---

## Current implementation (problems)

What actually happens now in Situation 1:

### Problem: no sprint runway

Player starts FJ_SPRINT at the edge (just came from slow bridge).
Sprint not built up — jump is walking-speed (~2.5 blocks instead of ~4.5).

### What gets placed

Only partial columns — not enough airtime to place all blocks.
The upper-right block doesn't get placed, but player still lands:

```
00000x
00000x
111110 ->
000111
```

Player falls onto the last floor block:

```
00000x
11111x ->
000111
```

(Landed successfully but missing 1 floor block at the far end)

### What happens next

Player is on the edge again. No sprint built up.
Slow bridge takes over (1 block at a time, no foundation).
Jump bridge runway check sees no foundation on slow-built blocks → stays in slow.
Loop: slow bridge forever, jump bridge never reactivates.

### Root cause

1. No sprint runway — jump from standstill
2. Slow bridge doesn't place foundation → jump bridge can't reactivate
3. No backup phase to create sprint distance

---

## Landing variants: Safe vs Continuous

After placing blocks mid-air and landing, two strategies for the next jump:

### Safe landing

Slow, reliable. Full stop between jumps.

```
Cycle: jump → place → land → SNEAK STOP → turn forward → back up → sprint → jump

Timeline per cycle:
  [AIRBORNE: place blocks]
  [LAND: sneak 3 ticks, full stop]
  [TURN FORWARD: rotate 180° back to +dir]
  [BACK UP: walk backward ~3 blocks on placed bridge]
  [SPRINT: run forward 3 blocks]
  [JUMP: at edge]
  ... repeat
```

Pros:
- No risk of falling off after landing
- Full sprint speed guaranteed (3+ blocks of runway)
- Clean state — each jump is independent

Cons:
- Slow — full stop + 180° rotation + backup + sprint = ~40-50 ticks per cycle
- Two 180° rotations per cycle (forward for sprint, backward for placement)

### Continuous landing

Fast, risky. No stop between jumps.

```
Cycle: jump → place → land → IMMEDIATELY sprint → jump → place

Timeline per cycle:
  [AIRBORNE: place blocks, looking backward]
  [LAND: rotate 180° to forward WHILE running]
  [SPRINT: already moving forward from landing momentum]
  [JUMP: at edge of placed blocks]
  [AIRBORNE: rotate 180° to backward, place blocks]
  ... repeat
```

Pros:
- Fast — no stop, no backup needed (landing momentum = sprint start)
- ~20 ticks per cycle (vs ~50 for safe)

Cons:
- Two snap rotations per cycle (180° each) — looks bot-like, anticheat risk
- WindMouse can't do 180° fast enough → must use blockInteract snap
- If rotation doesn't settle before jump, placement fails
- Risk of sliding off placed blocks (no sneak stop)
- Sprint might not reactivate in time (1-2 tick delay)

### Recommendation

Start with **safe landing**. Add continuous as a separate sub-mode later
(`bridgingMode = "jump_continuous"` or a setting).

---

## Key constraints

1. **Foundation required for jump bridge**: the 2-block column gives double the face area for clicking. Without it, raycast from above hits the top face instead of the side face.

2. **Sprint runway required**: minimum 3 blocks of floor behind the edge. Without sprint speed, the jump is too short (~2.5 blocks instead of ~4.5).

3. **Placement order in air**: foundation (Y-1) first, then floor (Y). The foundation block's +dir face is easier to hit from above (steeper ray angle clearly misses the top face).

4. **No movement keys during airborne**: pure inertia from sprint-jump keeps trajectory straight. Pressing keys while rotated backward would cause sideways drift.

5. **Always exit on landing**: pathfinder re-evaluates on next tick. No multi-phase cycling inside jump bridge (prevents infinite loops).
