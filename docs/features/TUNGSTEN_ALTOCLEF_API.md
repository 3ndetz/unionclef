# Tungsten ↔ AltoClef: role split and API

Status: design (2026-07-20). Implementation — incremental, each stage with an
autotest on the bench (`deploy/`).

⛔ CHECKED 2026-09-02: Stages 2 and 3 (§"Interface 2") below are marked as future work — this
is outdated. Both GOALS were reached, but NOT through the `NeedFulfiller` interface/
`reserveScaffoldBlocks` envisioned here (these names appear nowhere in the code, grep is
empty) — they were implemented a DIFFERENT way, by tungsten itself, with no request to
altoclef:
- **Stage 2 (inventory limit) — exists, but simpler than planned.** `FastPlanner.placeBudget`/
  `countPlaceable` (`FastPlanner.java:314,346`) counts available blocks and trims the plan
  (`placedDepth >= placeBudget`) — but WITHOUT altoclef's throwaway-block classification, which
  this section assumed would be reused. `selectThrowaway` (`MovementHelperB.java:1010`) by its
  OWN javadoc is "deliberately minimal" — it takes ANY placeable block, without asking altoclef
  about protected/throwaway. So Stage 2 answers the "how many blocks" question, but not "which
  blocks don't matter" — that part of the plan (`docs/BARITONE-PORT.md`, block-placement
  section, the "throwaway-block accounting" finding) is still open separately.
- **Stage 3 (block placement as a move) — done**, also a different way: not "in PathExecutor
  following the mining pattern" as written here, but through the newer `MovementQueue`/
  `MovementTraverse`/`MovementHelperB` system (`docs/BARITONE-PORT-SPEC.md`, Units 1-3), which
  did not exist when this document was written. `FastPlanner.placeAcross`/`pillarUp` are
  exactly "block-space children of 'place a block and stand on it'" with a real cost; aiming at
  the face + right-click is `RealPlacement`/`MovementHelperB.attemptToPlaceABlock`. Verified in
  this same session (`docs/BARITONE-PORT.md`, block-placement section).

The "Order of work" at the end of the file is also outdated in light of this — item 4 is
mostly done, just not the way it was planned.

## Principle

The same split for combat and for working with the world:

- **tungsten** — execution and physics: hitting, aiming, trajectories, movement,
  the mining primitive, (future) block placement. Knows nothing about the inventory,
  item value, or strategy.
- **altoclef** — the brain and the inventory: what to equip, what to spend, when it has
  a golden apple, which block is junk. Listens to tungsten's "needs" and
  satisfies them from the inventory.

The dependency direction is already correct: altoclef depends on tungsten,
so the channel is **callbacks/interfaces that tungsten declares and altoclef
registers at init time** (`TungstenModDataContainer`-style, static
slots). No back-imports.

## Interface 1: combat primitives (TODO 2.4-2.5)

tungsten exports (partially already there, bring up to an API):

Facade: `kaptainwutax.tungsten.combat.CombatPrimitives` (2026-07-21).

| Primitive | Status |
|---|---|
| `canHit(player, target, angle)` — gate (reach/COLLIDER LOS/angle) | EXISTS (facade; TriggerBot uses the same logic + cooldown) |
| `attack(player, target)` — direct delivery (attackEntity + swing) | EXISTS |
| `aimAt(Vec3d/Entity)` — WindMouse aim | exists (SafetySystem/WindMouseRotation), not exposed in the facade |
| `shieldHold(ticks)/shieldRelease/isShieldBlocking` | EXISTS (ShieldBlocker; yields the use key to the bow). Test: 0/3 damage from arrows with 2/2 control. ⛔ CLARIFICATION 2026-09-01: the primitive exists and is callable (`ShieldBlocker.java`, `CombatController.java:454-456`), but the combat engine only raises the shield ON ITS OWN when `combatShieldEnabled = true`, and that is NOT the default (`TungstenConfig`) — see `TODOS.md` C6.5/C6.11 for the current picture (shield measured neutral on `mob_trio`, unmeasured on the duel set, which has no shield in its kit at all). The table here is about the PRIMITIVE's existence, not whether it is on by default in combat — don't confuse the two. |
| `solveArrow(player, target)` — lead-computing ballistics | EXISTS (TrajectorySolver; 3/5 stationary, 2/5 against a running target at 18 blocks) |
| `shootArrow(target)` — shot (aim→charge→track→release) | EXISTS (BowShooter) |
| `throwProjectile` (trident/snowball/pearl), mace strike from height | no — next primitives |
| Telemetry: own/enemy HP, cooldown, distance, danger scores | exists internally, not exposed outward |

py4j wiring: shootArrowAt, solveArrowAim, shieldBlock (for external tests).

altoclef builds the brain on top of this: weapon choice (sword/axe/bow/mace/trident/
crossbow — the bow implementation in altoclef is already excellent and stays there),
consumables (pearls, golden apples by HP), snowballs for the first knockback, shield
against an axe. tungsten hands back the **trajectory solution** for the bow (physics
engine: gravity 0.05, drag 0.99, lead computed by simulating the target's movement).

## Interface 2: mining and building (TODO 3.6)

Flow of "needs" from tungsten to altoclef:

```java
// tungsten declares (static slots in TungstenModDataContainer):
interface NeedFulfiller {
    // "About to break pos/state — equip the best tool" (called before mining
    // and once every N ticks during). Returns false = break with what's in hand.
    boolean equipToolFor(BlockPos pos, BlockState state);

    // "I want to place N scaffold blocks — got any?" Blocks go into the hand, the
    // answer is how many are actually available (junk classification lives on
    // altoclef's side).
    int reserveScaffoldBlocks(int wanted);
}
```

- **Stage 1 — tools — DONE (2026-07-20)**: hook
  `TungstenModDataContainer.equipToolHook` (BiConsumer<BlockPos, BlockState>),
  tungsten calls it from `PathExecutor.tickBreaking` (client thread, try-catch —
  the hook cannot break mining); altoclef registers it in `onInitializeLoad`:
  `StorageHelper.getBestToolSlot` → `SlotHandler.forceEquipItem`, respecting
  eating (isTryingToEat). Autotest `E_tool` PASS: a deepslate door (bare hands
  15s/block — doesn't fit the budget), an iron pickaxe in `container.9`
  (outside the hotbar) — the course passed within the limit.
  Next step: cost in block-space from the BEST available tool
  (a second hook-supplier `bestBreakTicks(BlockState)`), not from the current hand.
- **Stage 2 — quantity**: `reserveScaffoldBlocks` — block-space, when planning
  bridges/placement, asks for the limit BEFORE building the plan: you cannot promise a
  20-block bridge with 10 in the inventory. Junk classification (dirt/cobblestone — spend,
  diamond blocks — don't spend) lives in altoclef, which already has the
  protected/throwaway items concept.
- **Stage 3 — block placement** (the big one): a place primitive in tungsten
  (aim at the face + right click, as jump-bridge in shredder already does),
  block-space children of "place a block and stand up" with cost = f(availability from
  reserveScaffoldBlocks), executed in PathExecutor following the mining pattern.
  Preference for cheap blocks — sorting inside the altoclef implementation.

## Order of work

1. Stage 1 (tools) — small, with a test right away.
2. Combat API wiring (formalize what exists + the shield).
3. Bow trajectories (#11) — a standalone module with a unit check on the bench
   (bow + target, hit percentage).
4. Stage 2 (quantity) → Stage 3 (placement) — one at a time, with courses.

There are a lot of variables (hardness × tool × enchantments, junk/value, reserves,
spending order) — which is why it's incremental: each step is verified on the bench
before the next.
