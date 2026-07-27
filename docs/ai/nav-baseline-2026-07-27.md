# nav suite — baseline before the block-space rework (2026-07-27)

Stand: local docker (`deploy/compose.test.yml`), flat world, jar `0.62.0` **plus** the combat fixes
and **without** the physics-A* chunk fix. Command:

```
python3 deploy/runner/run_suite.py nav
```

This is the reference the search rework (#6 unified move generation, #7 g-cost, #8 dedup) is
measured against. Re-run it after every search change.

## Result: 3 / 10 gate courses pass

| course | capability under test | result | time / final dist | freezes |
|---|---|---|---|---|
| `nav_flat` | plain walk, 30 blocks | **PASS** | 6.8 s | 0 |
| `nav_staircase` | 8× +1 step-up, 3 wide | **PASS** | 6.8 s | 0 |
| `nav_descend` | drops of 1, 2, 3 | **PASS** | 4.5 s | 0 |
| `nav_steep` | +1 up every 2 across (parkour-ascend) | FAIL | never, 19.1 | 12 |
| `nav_gaps` | 2 / 3 / 4-block gaps over void | FAIL | never, 21.4 | 12 |
| `nav_water` | swim a 6×3 pool and climb out | FAIL | never, 14.3 | 16 |
| `nav_ladder` | climb a 4-high ladder | FAIL | never, 7.9 | 11 |
| `nav_slime` | drop → slime bounce → ledge | FAIL | never, 16.3 | 12 |
| `nav_break` | mine through a 1-thick wall (no way around) | FAIL | never, 10.3 | 16 |
| `nav_wall2` | 2-block wall, cobble in hand, `planPlaceMoves` ON | FAIL | never, 6.7 | 16 |

`no self-fall` passes on **every** course, including the failures. The bot does not fall off or
mis-execute — it **stops moving**. The failure mode is uniformly "the search produced nothing usable
and the bot stood still", which matches the engine chat: `Partial path (goal unreachable via
move-gen) — advancing 2 nodes toward goal`, repeated.

Client FPS was ~10 on every course (software-GL container). That is the PERF-1 "before" number; it is
a trend line for comparison, not a pass/fail bar.

## What this means

Tungsten's block-space search today reliably handles **flat walking, simple step-up staircases and
safe descents** — and nothing else. Every capability that needs a non-trivial move type is red.

That is the concrete answer to "can tungsten replace baritone": not yet, and the gap is the move
generator, exactly as the audit predicted (`docs/ai/audit-2026-07-27-tungsten-full.md`, C2.1):

- `smartMoves = false` (default) → the blind radius-8 scan generates ~1086 children per expansion and
  filters them through a pile of `instanceof` special cases. It *has* break/place hooks but is so
  expensive it exhausts its budget on anything non-trivial.
- `smartMoves = true` → ≤8 pre-validated children, but `getChildren` returns early and never reaches
  `shouldRemoveNode`, so it has **no break, no place, no ladders, no water, no slime, no diagonals**.

Neither branch can pass this suite. The rework has to produce ONE generator that emits typed,
pre-validated moves *including* break/place/ladder/water/slime.

## Two landmines found while reading the cost model (blockers for #7)

1. `ActionCosts.COST_INF = -1000000` — the "infinity" sentinel is **negative**, so every
   `cost < COST_INF` comparison is inverted. Real A* relaxation cannot be written against it as-is.
2. Unit mismatch: costs are in **ticks** (`WALK_ONE_BLOCK_COST = 20/4.317 = 4.633` per block) while
   `Goal.heuristic` returns raw **blocks**. The moment g accumulates honestly, the heuristic is
   ~4.6× under-weighted and the search degenerates toward Dijkstra. The heuristic must be scaled into
   tick units in the same change.

Consequence for sequencing: **#7 cannot be done in isolation on the legacy branch** — that branch is
slated for deletion, and fixing its cost model would be throwaway work. Costs get built correctly
inside the unified generator (#6 + #7 as one change).

---

## Итерация 1 — паркурные ямы (2026-07-27, вечер)

**Счёт: 3/10 -> 4/10.** `nav_gaps` из «не проходил никогда» -> стабильный PASS
(6 из 6 подряд, 7.8–9.6 с, самопадений 0). Регрессии нет.

Пять корней, все в ядре, все доказаны логом:

1. `FastNavigator.nextLegNeedsPhysics` — флаг писался и **не читался ни разу**.
   Передачи прыжка физике не существовало.
2. `ARM_TOLERANCE = 2.0` против `driftThreshold = 0.8` — путь с корнем между ними
   не взводился, стартовал и умирал на первом тике (`drift 1.723 at tick 1`).
3. `findBlockPath` брал маршрут только ПОЛНЫМ — а при передаче прыжка он неполон
   именно потому, что прыжок делегирован физике. Уходило в legacy -> `Ran out of nodes`.
4. Шагатель стартовал новый отрезок ВО ВРЕМЯ прыжка (мой баг) — два владельца клавиш.
5. `FastPlanner.MAX_JUMP_GAP = 3` делал 4-блочные ямы непланируемыми; ветка «тупик»
   печатала «physics owns this» и звала `stop()`, обнуляя цель; `setPath()` затирал
   callback повтора `;goto`; безусловный `sleep(500)` на каждый запрос.
6. `find()` был `void` и **молча выбрасывал** запрос при занятом поиске; `thread` не
   volatile и чистится ПОСЛЕ `active` -> окно, где корректный запрос пропадал.
   Это и был источник флака (~30%).

**Оценка дороги:** правильная. Все правки — в ядре, ни одной заплатки, ни одного
хардкода. Мёртвый флаг оказался корнем ЧЕТЫРЕ раза подряд — это системная болезнь
кодовой базы, а не совпадение.

**Осталось красным:** `nav_steep`, `nav_water`, `nav_ladder`, `nav_slime`, `nav_break`,
`nav_wall2`. Из них 4 последних — отсутствующие ходы в `FastPlanner`
(лестница, плавание, отскок, слом, постановка).
