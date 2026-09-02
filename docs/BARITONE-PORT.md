# Porting baritone into tungsten — the audit, and the work list

Produced 2026-07-30 by a fan-out audit (8 concerns, one agent each, every
`re-derived` / `missing` claim then re-opened by an adversarial verifier that defaulted to
refuting). The question it answers is the user's: **is tungsten re-walking baritone's path of
mistakes instead of copying logic that already works?**

The answer is yes, almost everywhere. Of 104 findings: **58 re-derived, 40 missing, 4
divergent on purpose, 2 actually ported.** `baritone/` is not compiled (at the time this was
written, shredder occupied the `baritone.*` package and was itself the live target for this
work; since the "G-0" migration, 2026-08-24, shredder is ALSO not compiled — tungsten is the
only pathfinder, and the audit below is unaffected: it was always about copying LOGIC into
tungsten, never about calling shredder), so porting means copying the LOGIC into tungsten —
never calling it.

Read this before building any movement mechanism. See also the rule this produced in
[CHECKLIST.md](CHECKLIST.md) section 1b.


## Block breaking: does tungsten re-derive baritone's mining model (getMiningDurationTicks / canWalkThrough / avoidBreaking / ToolSet) in its break-through generator, BreakRules, tickBreaking and truncateAtBreaks?  (13 findings)

### [high] re-derived — Mining cost is priced with whatever item is CURRENTLY in the main hand, but execution equips the best tool from the whole inventory — so the planner's break cost is systematically wrong (up to ~25x too expensive: hand-mining stone is 150 ticks vs 6 with a diamond pick).

- baritone: `baritone/src/main/java/baritone/utils/ToolSet.java:180 (getBestDestructionTime -> getBestSlot(b,false,true)), consumed at baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:666 (context.toolSet.getStrVsBlock)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:843 and tungsten/src/main/java/kaptainwutax/tungsten/path/blockSpaceSearchAssist/BlockNode.java:727 (st.calcBlockBreakingDelta(player,...) = held item) vs tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:390 (equipToolHook -> best tool, impl at src/main/java/adris/altoclef/AltoClef.java:531 -> StorageHelper.`
- copy: Port ToolSet.calculateSpeedVsBlock (ToolSet.java:197) + getBestSlot(Block, preferSilkTouch, pathingCalculation) (ToolSet.java:121-172) into a tungsten class (e.g. kaptainwutax.tungsten.path.MiningCost), and replace the calcBlockBreakingDelta(player,...) calls at FastPlanner.java:843 / BlockNode.java:727 with a best-slot-based tick estimate that mirrors what equipToolHook will actually equip. Keep baritone's ToolSet.getBestSlot:131 short-circuit (blast resistance 0 -> use held slot) and the pathingCalculation branch at ToolSet.java:133 so the cost follows the held item only when auto-tool is off.

### [high] missing — No veto on breaking a block whose NEIGHBOURS are liquid or unsupported falling blocks — tungsten only checks the fluid at the block itself, so it will happily open a wall with lava/water behind it and flood its own tunnel.

- baritone: `baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:96 (avoidBreaking) and :117-144 (avoidAdjacentBreaking: FluidBlock up/N/S/E/W, source-level check, FallingBlock.canFallThrough), invoked from getMiningDurationTicks at MovementHelper.java:663`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/BreakRules.java:29 (only world.getFluidState(pos) at the block itself)`
- copy: Copy avoidBreaking + avoidAdjacentBreaking (MovementHelper.java:96-144) into BreakRules.canBreak: the five neighbour probes (x,y+1,z with directlyAbove=true; x±1/z±1 with directlyAbove=false), the FluidBlock.LEVEL==0 source-block rule, the 'prefers flowing down' fallback at MovementHelper.java:141, the Blocks.ICE deny at MovementHelper.java:108, and the FallingBlock-with-nothing-under-it rule at MovementHelper.java:123-129 (baritone gates that one on avoidUpdatingFallingBlocks, Settings.java:318).

### [high] missing — Break-as-a-move only exists for horizontal CARDINAL steps at the same Y, so tungsten can never plan a dig-down, a dug staircase, mining a ceiling to pillar up, or a diagonal break — only a flat tunnel at one Y.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementAscend.java:145,149,153 (y+2 self, dest y+1, dest y+2 with includeFalling); MovementDescend.java:82,86,90 (three cells); MovementPillar.java:113 (y+2, includeFalling); MovementDownward.java:70 (y-1); MovementDiagonal.java:202-220 (both corner options)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:810 (`if (dx != 0 && dz != 0) return;`) with the cell loop pinned to from.y+1..from.y at FastPlanner.java:826; tungsten/src/main/java/kaptainwutax/tungsten/path/blockSpaceSearchAssist/BlockNode.java:641 (`if (dy != 0 || Math.abs(dx)+Math.abs(dz) != 1) return false;`)`
- copy: Add three more break generators next to FastPlanner.breakThrough: (a) break-down — price the floor cell (x,y-1,z) like MovementDownward.cost:70 (FALL_N_BLOCKS_COST[1] + mining ticks); (b) break-up/pillar-ceiling — price (x,y+2,z) like MovementPillar.java:113 and copy the refusal at MovementPillar.java:121-129 (COST_INF if a FallingBlock sits on top of the block you break above your head); (c) ascend-with-break — the three-cell pricing of MovementAscend.java:145-153. Diagonal breaks can stay out (baritone's MovementDiagonal.java:202-220 is the reference if wanted).

### [high] re-derived — "Is this cell in the way" is decided purely by collision shape, so every no-collision obstruction (cobweb, fire, tripwire, sweet berry bush) is invisible to the planner: it is never counted as a wall, never enters the break plan, and the bot walks in and gets stuck.

- baritone: `baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:182-230 (canWalkThroughBlockState: NO for AbstractFireBlock, TRIPWIRE, COBWEB, SWEET_BERRY_BUSH, POWDER_SNOW, BIG_DRIPLEAF, cauldrons, non-full fluids), which is what makes getMiningDurationTicks charge for them at MovementHelper.java:655`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:840 (`getCollisionShape(...).isEmpty() -> continue`), tungsten/src/main/java/kaptainwutax/tungsten/path/blockSpaceSearchAssist/BlockNode.java:650 (`getShapeVolume(pos, world) == 0 -> continue`), backed by tungsten/src/main/java/kaptainwutax/tungsten/helpers/BlockShapeChecker.java:56-82 and tungsten/src/main/java/kaptainwutax/t`
- copy: Port canWalkThroughBlockState + canWalkThroughPosition (MovementHelper.java:182-272) into tungsten/helpers/BlockStateChecker (finishing the dead fullyPassableBlockState stub, which also dropped baritone's SnowBlock clause and its `state.canPathfindThrough(NavigationType.LAND)` fallback at MovementHelper.java:299-303 — as written it returns YES for solid stone). Then gate the occupancy tests at FastPlanner.java:840 and BlockNode.java:650 on that Ternary instead of collision volume, so no-collision blockers become break candidates. Also carry over the user hook Settings.blocksToAvoid (Settings.java:219, consulted at MovementHelper.java:196) — tungsten only has the inverse (breakDenyBlocks, Tun

### [high] missing — A closed door or fence gate is treated as a breakable wall and mined, because nothing in tungsten can open one.

- baritone: `baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:199-205 (DoorBlock/FenceGateBlock -> YES, only IRON_DOOR is NO) plus isDoorPassable/isGatePassable at MovementHelper.java:374-398, and the right-click execution at baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java:225-238`
- tungsten: `MISSING`
- copy: Two parts. (1) In BreakRules.canBreak (tungsten/path/BreakRules.java:24) deny DoorBlock/FenceGateBlock (except Blocks.IRON_DOOR, which must stay a wall) so the planner stops pricing them as breaks — today they pass every check at BreakRules.java:28-47. (2) Port isDoorPassable/isGatePassable/isHorizontalBlockPassable (MovementHelper.java:374-418) plus a use-key branch in PathExecutor modelled on MovementTraverse.java:225-238, and treat an openable door as passable in the occupancy tests at FastPlanner.java:840 / BlockNode.java:650.

### [high] re-derived — Mining fires on a raw ±12 degree angular tolerance to the block CENTRE with no verification that the crosshair actually hits the intended block, so an occluded target means mining whatever is in front instead (then the 300-tick abort fires).

- baritone: `baritone/src/main/java/baritone/api/utils/RotationUtils.java:233-248 (reachableOffset raytraces and requires ((BlockHitResult)result).getBlockPos().equals(pos)) and :199-218 (centre first, then 8 shape-derived side offsets), used by baritone/src/main/java/baritone/pathing/movement/Movement.java:165-173 with an explicit "break the incorrect block" fallback only when nothing is reachable (Movement.j`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:401-411 (`aimed = Math.abs(dYaw) < 12f && Math.abs(dPitch) < 12f; options.attackKey.setPressed(aimed)`)`
- copy: Port RotationUtils.reachableOffset/reachable (RotationUtils.java:199-248) — the raytrace-verified rotation search over block centre + BLOCK_SIDE_MULTIPLIERS offsets — and in tickBreaking replace the 12-degree gate with "press attack only when mc.crosshairTarget is a BlockHitResult whose getBlockPos() equals target", keeping the WindMouse aim target as the thing being converged. Baritone's fallback ordering (reachable face first, centre-anyway second) is the branch to copy.

### [medium] re-derived — Any block with a block entity is a hard deny, so one chest/sign/banner/bed in the wall column aborts the whole break plan instead of merely being expensive.

- baritone: `baritone/src/main/java/baritone/api/Settings.java:226 (blocksToDisallowBreaking, empty by default = hard deny) vs Settings.java:233-243 (blocksToAvoidBreaking = CRAFTING_TABLE, FURNACE, CHEST, TRAPPED_CHEST with avoidBreakingMultiplier .1), applied as a speed multiplier in baritone/src/main/java/baritone/utils/ToolSet.java:185-187`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/BreakRules.java:31 (`if (state.hasBlockEntity()) return false;`), which makes FastPlanner.java:842 and BlockNode.java:726 bail out of the entire plan`
- copy: Split BreakRules into the same two tiers baritone has: keep the hard deny for the genuinely-protected set (cfg.breakDenyBlocks, deny zones, canBreakHook), and replace the blanket hasBlockEntity() deny with a soft cost multiplier — port ToolSet.avoidanceMultiplier (ToolSet.java:185-187) with baritone's default list (Settings.java:233-238) and 0.1 speed factor, applied at FastPlanner.java:845 / BlockNode.java:729 where ticks are computed.

### [medium] re-derived — The primary (fast) planner charges nothing for the sand/gravel column that will collapse into the passage it opens, and nothing anywhere pauses mining while a falling-block entity is in flight through a target cell.

- baritone: `baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:676-681 (includeFalling recurses up the FallingBlock stack), used with includeFalling=true on the top broken block at baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java:107; the in-flight pause is baritone/src/main/java/baritone/pathing/movement/Movement.java:159 (FallingBlockEntity box test, gated o`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:826-847 (two cells only, no FallingBlock scan — while the legacy path DOES have it at tungsten/src/main/java/kaptainwutax/tungsten/path/blockSpaceSearchAssist/BlockNode.java:660-668); tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:339 substitutes a blind 12-tick settle wait`
- copy: In FastPlanner.breakThrough, after the head cell, add BlockNode.java:660-668's loop (walk up while the state is a FallingBlock, add breakTicks each, bail if unbreakable) — or better, port MovementHelper.getMiningDurationTicks's recursive includeFalling (MovementHelper.java:676-681) once and call it from both planners. In PathExecutor.tickBreaking, add Movement.java:159's guard: if world.getNonSpectatingEntities(FallingBlockEntity.class, new Box(0,0,0,1,1.1,1).offset(target)) is non-empty, release attackKey and wait instead of relying on the 12-tick settle at PathExecutor.java:339.

### [medium] re-derived — A hardcoded 300-tick abort for the WHOLE break plan silently caps what the bot can mine — two hand-mined stone blocks (300 ticks) or obsidian with a stone pick (~375 ticks) can never finish, and the plan is dropped and re-searched forever.

- baritone: `baritone/src/main/java/baritone/pathing/movement/Movement.java:158-185 (prepared() keeps mining every tick until MovementHelper.canWalkThrough(ctx, blockPos) is true — no time cap; only UNREACHABLE when no rotation reaches the block, Movement.java:186-191)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:367 (`if (breakingTicks++ > 300 || eye.squaredDistanceTo(center) > 4.5*4.5)`), with breakingTicks reset only on completion/abort, not per block`
- copy: Drop the fixed 300 and derive the budget from the block being mined: reuse the same tick estimate the planner computed (FastPlanner.java:845) times a slack factor, reset the counter each time the target cell changes, and keep only the progress/reach failure conditions. For the reach half, replace the hardcoded 4.5*4.5 with the player's real interaction range the way baritone does (ctx.playerController().getBlockReachDistance(), passed into RotationUtils.reachable at Movement.java:165).

### [medium] re-derived — The bot stands completely still to mine (all movement keys released) where baritone walks and sprints into the wall while breaking it, at a 26-degree pitch chosen for the efficient break angle.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java:178-216 (walkWhileBreaking gate, avoidWalkingInto guards at :186-194, the dist<0.83 guard at :196, pitchToBreak=26 at :209-212, then MOVE_FORWARD+SPRINT at :215-216); setting default true at baritone/src/main/java/baritone/api/Settings.java:884`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:387 (releaseMovementKeys), with the physics leg deliberately cut one cell short at tungsten/src/main/java/kaptainwutax/tungsten/path/PathFinder.java:900-925`
- copy: The truncation itself is stated as deliberate (PathFinder.java:897-899: the live world can't be simulated through missing blocks) — keep it. But replace releaseMovementKeys at PathExecutor.java:387 with MovementTraverse.java:186-216's guarded forward press: hold forwardKey (and sprint) toward the target cell only while horizontal distance to the destination centre is >= 0.83 and neither broken cell is an avoidWalkingInto block — port avoidWalkingInto itself from MovementHelper.java:420-431, which tungsten has no equivalent of.

### [medium] re-derived — In the legacy block-space search the break penalty is not in the same unit as movement cost (that A* adds a flat 1 per step), so the tunnel-vs-detour decision rests on a hand-tuned 0.15 fudge rather than real ticks, and the per-block approach overhead baritone charges is missing entirely.

- baritone: `baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:673-675 (`result = 1/strVsBlock; result += context.breakBlockAdditionalCost; result *= mult`), with breakBlockAdditionalCost wired at baritone/src/main/java/baritone/pathing/movement/CalculationContext.java:156 from Settings.java:117 (default 2D), and the COST_INF gate breakCostMultiplierAt at CalculationContext.java:202-213`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/blockSpaceSearchAssist/BlockNode.java:671-675 (`child.cost += ticks * 0.15 * breakCostMultiplier`, with the comment admitting the A* does not accumulate cost) against tungsten/src/main/java/kaptainwutax/tungsten/path/blockSpaceSearchAssist/BlockSpacePathFinder.java:347 (`tentativeCost = child.cost + 1`); FastPlanner.java:850 is tick-consistent but `
- copy: Add baritone's additive per-block penalty (Settings.java:117 blockBreakAdditionalPenalty = 2 ticks, applied per broken block as at MovementHelper.java:674) to both FastPlanner.java:850 and BlockNode.java:675, and drop the 0.15 scale by making the legacy A* accumulate real g-cost in ticks (BlockSpacePathFinder.java:347 should be parent cost + a tick-priced edge, mirroring tungsten's own ActionCosts.WALK_ONE_BLOCK_COST=4.633 which is already baritone's unit).

### [low] re-derived — Break-strength is recomputed per candidate cell inside the time-budgeted A* inner loop instead of being cached per Block, so a wall-heavy search burns its millisecond budget on repeated hardness/enchantment/status-effect lookups.

- baritone: `baritone/src/main/java/baritone/utils/ToolSet.java:52 (breakStrengthCache) and :80-82 (getStrVsBlock via computeIfAbsent), constructed once per search at baritone/src/main/java/baritone/pathing/movement/CalculationContext.java:105`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:843 (per-cell calcBlockBreakingDelta, under the wall-clock budget check at FastPlanner.java:333) and tungsten/src/main/java/kaptainwutax/tungsten/path/blockSpaceSearchAssist/BlockNode.java:727`
- copy: When porting the cost function (finding 1), also port ToolSet's Map<Block,Double> breakStrengthCache + computeIfAbsent pattern (ToolSet.java:52,80-82) and build it once per search invocation next to FastPlanner's existing STATE_CACHE (FastPlanner.java:700-710), rather than calling calcBlockBreakingDelta per expanded node.

### [low] divergent-on-purpose — Tungsten has no tool-selection code of its own at all (no useSwordToMine / itemSaver / preferSilkTouch / material-cost tiebreak) — it hands the decision to altoclef over a hook.

- baritone: `baritone/src/main/java/baritone/utils/ToolSet.java:121-172 (getBestSlot: useSwordToMine skip at :144, itemSaver+itemSaverThreshold skip at :147, silk-touch and material-cost tiebreak at :155-169) plus baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:698-713 (switchToBestToolFor, gated on autoTool/assumeExternalAutoTool)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/TungstenModDataContainer.java:23-29 ("Tungsten itself never touches the inventory") -> tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:390-395`
- copy: nothing — the delegation is stated in code and the altoclef side (src/main/java/adris/altoclef/util/helpers/StorageHelper.java:159 getBestToolSlot) already scans the full inventory and honours a save-tool rule, which is broader than baritone's 9-slot hotbar scan. Only the COST side needs fixing (finding 1); leave the equip side on the hook.


## Block placement: tungsten re-derived most of baritone's placement layer from scratch and the re-derivation is materially weaker on both halves. PLANNING: FastPlanner.placeAcross only ever generates a BACKPLACE (against the cell under its own feet), so baritone's 5-direction side-place scan and the SNEAK_ONE_BLOCK_COST premium that distinguishes the two never happen; none of baritone's backplace-impossibility rejections (soul sand, half slab, not standing on a block, lily pad/carpet over fluid) exist; and placeAcross/pillarUp never consult PlaceRules at all even though the sibling breakThrough does consult BreakRules three lines up and the other planner (BlockNode.tryPlanPlaceThrough) does consult PlaceRules — so the two planners disagree on protection policy. EXECUTION: PathExecutor.tickPlacing picks its against-face by "collision shape is non-empty" over all six Directions (baritone requires a FULL CUBE face and deliberately excludes UP), never raytraces to verify the face is actually hittable, has no PlaceResult tri-state and therefore no "something is in the way, break it" branch, computes the aim from the standing eye while holding SNEAK, and has no MOVE_BACK — the file's own comment at PathExecutor.java:508-511 records that releasing keys does not cancel momentum and the bot fell off the lip twice on nav_slime, which is exactly the failure baritone's MOVE_BACK branches exist to prevent. PillarTask forces sneak OFF and places with no isLookingAt / height gate, relying on an 80-tick stuck timeout instead of baritone's re-centering input. Tungsten already contains a copy of isBlockNormalCube (BlockShapeChecker.java:110) but no placement code path uses it.  (16 findings)

### [high] re-derived — canPlaceAgainst must require a FULL CUBE face (plus glass); tungsten accepts any non-empty collision shape, so fences, slabs, panes, walls, cactus, bamboo, dripstone all count as place-against faces

- baritone: `baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:637`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:496`
- copy: Replace the `BlockShapeChecker.getShapeVolume(n, world) > 0` test at PathExecutor.java:496 and the two `getCollisionShape(...).isEmpty()` tests at FastPlanner.java:882 and 895 with a new PlaceRules.canPlaceAgainst(world,pos) that is the body of baritone MovementHelper.java:637-647: `isBlockNormalCube(state) || block == Blocks.GLASS || block instanceof StainedGlassBlock`. Tungsten already has isBlockNormalCube at BlockShapeChecker.java:110 — but its copy DROPPED BambooBlock and PistonExtensionBlock from baritone's exclusion list (MovementHelper.java:790-792); add both back.

### [high] re-derived — attemptToPlaceABlock verifies the candidate face by actually raytracing the peeked rotation and checking the hit is `against` AND `against.offset(hitSide) == placeAt`; tungsten synthesizes a BlockHitResult and clicks without any raytrace, occlusion or reach verification

- baritone: `baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:827`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:539`
- copy: Port the verification loop of MovementHelper.attemptToPlaceABlock (lines 814-839) into tickPlacing: for each candidate `against`, compute the face rotation, then `world.raycast(new RaycastContext(eye, eye+dir*reach, ShapeType.OUTLINE, ...))` (baritone RayTraceUtils.java:49-63) and accept the candidate ONLY if `hit.getBlockPos().equals(against) && hit.getBlockPos().offset(hit.getSide()).equals(target)`. Tungsten already calls world.raycast this way in combat/CombatPrimitives.java:43, so the primitive exists.

### [high] missing — PlaceResult tri-state (READY_TO_PLACE / ATTEMPTING / NO_OPTION) and the ATTEMPTING branch that presses CLICK_LEFT to break whatever is blocking the placement; tungsten has no tri-state and explicitly forces attackKey off during placing

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java:321`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:503`
- copy: Return baritone's three-way PlaceResult (MovementHelper.java:862-864) from the against-selection, and add MovementTraverse.java:313-325's ATTEMPTING branch: when the aim has converged but nothing was placed, press attackKey to mine the obstruction instead of PathExecutor.java:503's unconditional `options.attackKey.setPressed(false)` followed by the 200-tick timeout at line 472.

### [high] re-derived — MovementTraverse.cost scans 5 directions for a SIDE place (skipping the backplace direction) and only falls back to a backplace priced at SNEAK_ONE_BLOCK_COST/WALK_ONE_BLOCK_COST (3.32x); tungsten hardwires the backplace as the only generated place move and prices every place identically with no sneak factor

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java:142`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:894`
- copy: In FastPlanner.placeAcross, replace the single hardwired `against = (from.x, from.y-1, from.z)` (line 894) with baritone's loop over HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP (Movement.java:36) around the floor cell, `continue`-ing the direction that would be a backplace (MovementTraverse.java:146-148); when a side place is found charge WALK + place, and only in the backplace fallback multiply the walk component by SNEAK_ONE_BLOCK_COST/WALK_ONE_BLOCK_COST (baritone ActionCosts.java:30 = 15.385/4.633). Add SNEAK_ONE_BLOCK_COST to tungsten/path/calculators/ActionCosts.java (it has no such constant). Same change in BlockNode.tryPlanPlaceThrough:710.

### [high] missing — Backplace impossibility rejections: baritone returns COST_INF for a sneak-backplace off soul sand or a non-double slab, when not standing on a block at all, or off a lily pad / carpet floating on fluid; tungsten's placeAcross accepts any of these as a valid `against`

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java:154`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:895`
- copy: Add MovementTraverse.java:154-163's three rejections to placeAcross before the relax() call: (a) `srcDownBlock == Blocks.SOUL_SAND || (srcDownBlock instanceof SlabBlock && srcDown.get(SlabBlock.TYPE) != SlabType.DOUBLE)` -> reject; (b) not standing on a solid block (tungsten can test `Double.isNaN(PlayerFit.supportTop(...))`) -> reject; (c) `(blockSrc == Blocks.LILY_PAD || blockSrc instanceof CarpetBlock) && !srcDown.getFluidState().isEmpty()` -> reject. Note PlayerFit.supportTop:180-184 deliberately returns a half-slab top as a valid floor, so (a) cannot be reached incidentally.

### [high] re-derived — MOVE_BACK to kill forward momentum at the edge while placing (soul sand / slab case, and the true sneak-backplace once the feet are already over the gap); tungsten only un-presses keys, which does not cancel velocity

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java:292`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:583`
- copy: Port both MOVE_BACK branches into tickPlacing. (1) MovementTraverse.java:292-300: standing on soul sand or a SlabBlock and `max(|dx|,|dz|) < 0.85` -> forwardKey false + backKey true. (2) MovementTraverse.java:330-356: when the feet are already in the destination cell over air, aim with the REVERSED yaw (`calcRotationFromVec3d(blockCenter(dest), playerHead)`) and press backKey once `dist2 < 0.29`. PathExecutor.releaseMovementKeys:583-591 has no backKey press at all, and the comment at PathExecutor.java:508-511 already identifies un-pressing-is-not-braking as the nav_slime void-fall cause.

### [high] missing — Placement protection policy is baked into the cost function (costOfPlacingAt returns COST_INF), so a protected/denied cell is never planned; FastPlanner's place generators skip PlaceRules entirely even though the sibling break generator checks BreakRules

- baritone: `baritone/src/main/java/baritone/pathing/movement/CalculationContext.java:196`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:898`
- copy: Add `if (!PlaceRules.canPlace(world, floor)) return;` in FastPlanner.placeAcross before the cost at line 898, and `if (!PlaceRules.canPlace(world, feet)) return;` in pillarUp before line 928 — mirroring FastPlanner.java:842 which already does exactly this for breaking (`BreakRules.canBreak`), and matching BlockNode.java:714 which already does it in the other planner. Without it the plan is only rejected at PathExecutor.java:483 after the bot has walked to the spot.

### [high] re-derived — MovementPillar's execution gates: SNEAK held while above dest (ncp 1-tick placement delay), CLICK_RIGHT only when isInSneakingPose AND isLookingAt(src)/(src.down()) AND y > dest.y+0.1, MOVE_FORWARD re-centering whenever horizontal dist > 0.17, JUMP only while y < dest.y, and a break-the-source branch with JUMP forced off; PillarTask has none of them

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementPillar.java:232`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/PillarTask.java:89`
- copy: In PillarTask.tick replace line 89's unconditional `opts.sneakKey.setPressed(false)` with MovementPillar.java:232's `sneak = player.getY() > targetY || player.getY() < srcY + 0.2`; replace line 87's unconditional `forwardKey false` with MovementPillar.java:239-247's `dist > 0.17 -> MOVE_FORWARD` re-centering (that is what removes the need for the 80-tick stuck bail at PillarTask.java:127); gate the interactBlock at line 114 on MovementPillar.java:265's `isInSneakingPose() && isLookingAt(against) && y > targetY + 0.1` instead of the `velocity.y > -0.15` proxy at line 98; gate jump on `player.getY() < targetY` (MovementPillar.java:250) rather than line 93's `isOnGround()`; and add MovementPill

### [medium] re-derived — The target cell must satisfy isReplaceable (air / 1-layer snow / tall grass / state.isReplaceable), not merely have an empty collision shape; and a bridge through water must be rejected outright

- baritone: `baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:340`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:882`
- copy: Replace FastPlanner.java:882's `!getCollisionShape(...).isEmpty()` gate with baritone MovementHelper.isReplaceable(x,y,z,state,bsi) (lines 340-367: AirBlock, SnowBlock with LAYERS==1, LARGE_FERN/TALL_GRASS, else state.isReplaceable()), and add MovementTraverse.java:127-131's rejection when the floor cell is water and the traverse itself is through water. PlaceRules.java:28 already has the isReplaceable test — the planner just never calls it (see the PlaceRules finding).

### [medium] re-derived — UP is deliberately excluded from the against-face search (HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP); tungsten iterates Direction.values(), which puts UP second, ahead of every horizontal

- baritone: `baritone/src/main/java/baritone/pathing/movement/Movement.java:36`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:494`
- copy: Change the `for (Direction dir : Direction.values())` loop at PathExecutor.java:494 to iterate {NORTH, SOUTH, EAST, WEST, DOWN} exactly as baritone MovementHelper.java:815 does. Also port the `preferDown` flag (MovementHelper.java:832-836) so a placement can ask for the LAST matching option instead of the first, the way MovementParkour.java:282 does.

### [medium] missing — The placement aim is computed from the SNEAKING eye position when the placement is a sneak-place; tungsten computes it from the standing eye while it holds sneak, so the ray it intends leaves from ~0.35 blocks above where the real ray will

- baritone: `baritone/src/main/java/baritone/api/utils/RayTraceUtils.java:66`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:455`
- copy: When `sneakToPlace` is true (PathExecutor.java:516), replace `player.getEyePos()` at line 455 with baritone's inferSneakingEyePosition — `new Vec3d(player.getX(), player.getY() + 1.27, player.getZ())`, the sneaking value from IPlayerContext.eyeHeight(true) (IPlayerContext.java:101-103) — and use that vector for both the wantYaw/wantPitch computation at lines 519-521 and the reach check at line 460, exactly as MovementHelper.java:825 does.

### [medium] missing — Pillar cost rejections: cannot pillar up from a bottom slab, cannot pillar while standing in liquid without a placeable face below, cannot pillar off a lily pad / carpet over fluid; FastPlanner.pillarUp checks none of these

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementPillar.java:73`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:918`
- copy: Add to pillarUp before the cost at line 928: MovementPillar.java:73-75's `fromDown instanceof SlabBlock && get(TYPE) == SlabType.BOTTOM -> reject` (needed because PlayerFit.supportTop:180-184 happily returns a half-slab top as a valid floor, so the guard at FastPlanner.java:925 cannot catch it); MovementPillar.java:103-108's liquid rejection; and MovementPillar.java:109-112's lily-pad/carpet-over-fluid rejection.

### [medium] re-derived — Throwaway-block accounting: baritone only counts/selects items on the acceptableThrowawayItems list, skips protected items, and only searches hotbar slots 0-8; tungsten's placeBudget counts every BlockItem in the whole inventory, including ones the equip hook will never equip

- baritone: `baritone/src/main/java/baritone/behavior/InventoryBehavior.java:181`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:282`
- copy: Make countPlaceable (FastPlanner.java:282-291) count only items the equip hook can actually equip. AltoClef.java:554-559 hardcodes exactly eight (COBBLESTONE, DIRT, STONE, NETHERRACK, COBBLED_DEEPSLATE, OAK_PLANKS, DEEPSLATE, ANDESITE) — expose that list to tungsten and filter against it, plus honour isItemProtected the way InventoryBehavior.java:193 does. Today a pocket of shulker boxes and beds inflates placeBudget, the search promises a causeway it cannot build, and the run dies at PathExecutor.java:447's "no block in hand" abort after walking there.

### [medium] re-derived — Reach for placement comes from getBlockReachDistance (4.5, or 5.0 creative); tungsten hardcodes 5.5 as its in-range gate — and 4.5 for breaking three functions earlier in the same file

- baritone: `baritone/src/main/java/baritone/api/utils/IPlayerController.java:58`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:467`
- copy: Change the `placeDist > 5.5` defer threshold at PathExecutor.java:467 to baritone's blockReachDistance (Settings.java:385 = 4.5f, 5.0 in creative per IPlayerController.java:58), matching the 4.5 already used for breaking at PathExecutor.java:367. At 5.5 the executor enters placing mode, sends interactBlock at a distance the server rejects, and spins to the 200-tick timeout at line 472 instead of walking one block closer.

### [low] missing — World-border rejection for placements (checked both in canPlaceAgainst and in costOfPlacingAt); PlaceRules has no border test

- baritone: `baritone/src/main/java/baritone/pathing/movement/CalculationContext.java:193`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PlaceRules.java:24`
- copy: Add a `world.getWorldBorder().contains(pos)` test to PlaceRules.canPlace (after the allowPlace check at PlaceRules.java:26), mirroring baritone's `bsi.worldBorder.canPlaceAt(x, z)` in both MovementHelper.java:640 and CalculationContext.java:193.

### [low] divergent-on-purpose — BridgeTask sprints forward with sneak explicitly off (godbridge) rather than baritone's sneak-and-step, and paves the cell at the SAME level as the support so there is no edge to fall from

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java:303`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/BridgeTask.java:140`
- copy: nothing


## COST MODELS — baritone/pathing/movement (ActionCosts, CalculationContext, the eight Movement* cost functions, AStarPathFinder/AbstractNodeCostSearch scoring) vs tungsten (path/calculators/ActionCosts.java + every cost expression in path/fast/FastPlanner.java, with a side look at the still-live legacy BlockSpacePathFinder/BlockNode costs).  (15 findings)

### [high] re-derived — Fall cost is a flat 1.0 tick per block instead of baritone's gravity-integrated FALL_N_BLOCKS_COST table plus the walk-off-edge / centre-after-fall split, so drops are priced ~3x too cheap and the planner prefers plunging over routing.

- baritone: `baritone/src/main/java/baritone/api/pathing/movement/ActionCosts.java:48 (FALL_N_BLOCKS_COST = generateFallNBlocksCost), :36-40 (WALK_OFF_BLOCK_COST, CENTER_AFTER_FALL_COST), :83-97 (distanceToTicks); baritone/src/main/java/baritone/pathing/movement/movements/MovementDescend.java:124-129`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/calculators/ActionCosts.java:18 (FALL_ONE_BLOCK_COST = 1.0); tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:785-788`
- copy: Copy velocity(int), distanceToTicks(double) and generateFallNBlocksCost() out of baritone ActionCosts.java:67-97 into tungsten ActionCosts, plus the WALK_OFF_BLOCK_COST / CENTER_AFTER_FALL_COST constants (ActionCosts.java:36-40). In FastPlanner.step() (line 785-788) replace `baseCost + FALL_ONE_BLOCK_COST * -rise` for descending moves with MovementDescend's form: WALK_OFF_BLOCK_COST (soul-sand-scaled) + Math.max(FALL_N_BLOCKS_COST[drop], CENTER_AFTER_FALL_COST). Delete FALL_ONE_BLOCK_COST and its two other uses (FastPlanner.java:615, :681).

### [high] re-derived — The heuristic multiplies the vertical difference by the same 3.563 coefficient as the horizontal one, which exceeds tungsten's own per-block descend cost (1.0) — the heuristic is inadmissible downward, so A* is biased to dive and its "closest node" partial plans are scored by vertical progress.

- baritone: `baritone/src/main/java/baritone/api/pathing/goals/GoalYLevel.java:49-58 (descend = FALL_N_BLOCKS_COST[2]/2 per block, ascend = JUMP_ONE_BLOCK_COST per block, NOT scaled by costHeuristic); baritone/src/main/java/baritone/api/pathing/goals/GoalBlock.java:104-113; baritone/src/main/java/baritone/api/pathing/goals/GoalXZ.java:115 (only XZ gets costHeuristic)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:1066-1074 (octile adds raw dy) and :1014 (whole thing multiplied by HEURISTIC)`
- copy: Split FastPlanner.octile(): return the XZ part (diagonal*SQRT2 + straight) and multiply only that by HEURISTIC at line 1014, then add the Y term unscaled exactly as GoalYLevel.calculate does — FALL_N_BLOCKS_COST[2]/2 per block that must be descended, JUMP_ONE_BLOCK_COST per block that must be ascended. Never multiply dy by 3.563.

### [high] re-derived — Parkour reach exceeds vanilla: tungsten lands 5 cells away (4 air cells) where baritone's hard maximum is a 4-cell displacement (3 air), and it ignores the sprint/hunger and soul-sand takeoff caps — so it emits gap jumps nothing can execute and hands them to the physics engine.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementParkour.java:104-113 (maxJump: soul sand 2, canSprint 4, else 3), :208-219 (costFromJumpDistance: 2->WALK*2, 3->WALK*3, 4->SPRINT*4, anything else throws)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:59 (MAX_JUMP_GAP = 4 air cells), :967-984 (landing at from + dir*(gap+1)), tungsten/src/main/java/kaptainwutax/tungsten/path/calculators/ActionCosts.java:15`
- copy: In FastPlanner.parkour() cap the DISPLACEMENT at 4 (i.e. MAX_JUMP_GAP = 3 air cells), drop to 3 when the player cannot sprint (baritone's CalculationContext.java:108 test, foodLevel > 6) and to 2 when the takeoff block is soul sand (MovementParkour.java:105-106); also refuse takeoff from stairs/bottom slab/ladder/vine as MovementParkour.java:94 does. Replace the linear `PARKOUR_ONE_BLOCK_COST * (gap+1)` at line 980 with baritone's costFromJumpDistance switch + jumpPenalty.

### [high] missing — Placement moves ignore the protection/ownership policy that tungsten already has (PlaceRules), where baritone folds exactly those checks into costOfPlacingAt as COST_INF — so the planner routes bridges and towers through deny zones and the executor then refuses them.

- baritone: `baritone/src/main/java/baritone/pathing/movement/CalculationContext.java:186-200 (costOfPlacingAt: !hasThrowaway, isPossiblyProtected, worldBorder.canPlaceAt, shouldAvoidPlacingAt -> COST_INF)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:867-903 (placeAcross) and :913-933 (pillarUp) — neither calls tungsten/src/main/java/kaptainwutax/tungsten/path/PlaceRules.java:25 (canPlace), unlike breakThrough which does consult BreakRules at FastPlanner.java:842`
- copy: Gate placeAcross on `PlaceRules.canPlace(world, floor)` before line 898 and pillarUp on `PlaceRules.canPlace(world, feet)` before line 928, returning without emitting (the COST_INF equivalent). Also port the world-border test (CalculationContext.java:193) and replace countPlaceable's "any BlockItem" scan (FastPlanner.java:282-291) with a throwaway whitelist like baritone's acceptableThrowawayItems (Settings.java:209-214) so it does not plan to brick shulkers into a causeway.

### [high] re-derived — Mining cost is computed from the item currently in hand (calcBlockBreakingDelta) with no tool selection, no break tiebreaker and no falling-block-above term, where baritone prices with the best tool on the hotbar — so a bot holding a sword prices a dirt tunnel at hundreds of ticks and never chooses to dig.

- baritone: `baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:653-682 (getMiningDurationTicks: toolSet.getStrVsBlock, breakBlockAdditionalCost, breakCostMultiplierAt, FallingBlock recursion at :676-681); baritone/src/main/java/baritone/utils/ToolSet.java:74-82 and :121-135 (best hotbar slot)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:843-846 (calcBlockBreakingDelta with the held stack) and :850-851 (ticks * breakCostMultiplier)`
- copy: Port ToolSet (the getBestSlot hotbar scan of ToolSet.java:121-168 and getStrVsBlock) into tungsten, build it once per search on the client thread, and in breakThrough use `1 / strVsBlock + blockBreakAdditionalPenalty(2)` per cell instead of calcBlockBreakingDelta of the held item; add the FallingBlock-above recursion from MovementHelper.java:676-681 so sand/gravel collapse is priced. Make the executor switch to that slot (MovementHelper.switchToBestToolFor, MovementHelper.java:709-713).

### [medium] re-derived — JUMP_PENALTY is 6.5 against upstream's jumpPenalty of 2, and JUMP_ONE_BLOCK_COST is redefined as WALK+6.5 (11.13) instead of max(FALL_1_25 - FALL_0_25, WALK) (~4.63) — every ascend, pillar and parkour edge is priced 1.7-3x upstream, so the planner walks the long way round hills.

- baritone: `baritone/src/main/java/baritone/api/Settings.java:119-122 (jumpPenalty = 2D, "additional penalty for hitting the space bar ... because it uses hunger"); baritone/src/main/java/baritone/api/pathing/movement/ActionCosts.java:50-64 (JUMP_ONE_BLOCK_COST); baritone/src/main/java/baritone/pathing/movement/movements/MovementAscend.java:127-139; MovementPillar.java:143`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/calculators/ActionCosts.java:11-12 (JUMP_PENALTY = 6.5, JUMP_ONE_BLOCK_COST = WALK + 6.5); tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:786`
- copy: Set JUMP_PENALTY = 2 and JUMP_ONE_BLOCK_COST = Math.max(FALL_1_25_BLOCKS_COST - FALL_0_25_BLOCKS_COST, WALK_ONE_BLOCK_COST) (upstream ActionCosts.java:50-64 + MovementAscend.java:137-139). The stated reason for 6.5 (ActionCosts.java:7-11: keep parity with BlockNode's convention) is parity with a search whose g-cost never accumulated (see FastPlanner.java:20-24), so it was never tuned against the tick model.

### [medium] missing — No deep-fall model: MAX_FALL is a hard 3 regardless of what is at the bottom, so a survivable plunge into water or a water-bucket fall is unplannable, and a mid-fall ladder/vine grab is never priced.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementDescend.java:136-222 (dynamicFallCost: fall into still water at any depth :159-179, vine/ladder speed reset :188-195, maxFallHeightNoWater :205, hasWaterBucket + maxFallHeightBucket + placeBucketCost :213-218); baritone/src/main/java/baritone/pathing/movement/CalculationContext.java:215-217 (placeBucketCost)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:50 (MAX_FALL = 3), :745 (loop floor at -MAX_FALL)`
- copy: Port dynamicFallCost's descending column scan into FastPlanner.step(): keep MAX_FALL=3 for dry landings (it matches maxFallHeightNoWater, Settings.java:536), but accept a deeper landing when the column ends in non-flowing water with a floor under it (cost = WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[height]), reset the accumulated fall at a vine/ladder cell (MovementDescend.java:188-195), and allow up to maxFallHeightBucket (20) + placeBucketCost when a water bucket is in the inventory.

### [medium] re-derived — Bridging is priced at WALK*2.5 (11.58) where upstream charges 20 ticks per placed block deliberately to conserve blocks — combined with JUMP_PENALTY=6.5 a bridge costs the same as a jump, so the planner reaches for blocks where upstream would walk around.

- baritone: `baritone/src/main/java/baritone/api/Settings.java:104-110 (blockPlacementPenalty = 20D, "this cost is so high because we want to generally conserve blocks"); baritone/src/main/java/baritone/pathing/movement/CalculationContext.java:109 and :199 (placeBlockCost); baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java:132,150`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/calculators/ActionCosts.java:25-29 (PLACE_ONE_BLOCK_COST = WALK * 2.5); tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:898-899, :928-929`
- copy: Set PLACE_ONE_BLOCK_COST = 20 (upstream blockPlacementPenalty) and keep TungstenConfig.placeCostMultiplier as the knob. The comment's stated intent at ActionCosts.java:25-29 ("priced above a jump") only holds because the jump is inflated to 11.13; with jumpPenalty fixed to 2 the place cost must rise to 20 or bridging becomes the default move.

### [medium] missing — Terrain-material walk modifiers do not exist: every step costs a flat WALK_ONE_BLOCK_COST, so soul sand (2x), walking on a water surface, and depth-strider/water-efficiency water speed are all priced as ordinary ground, and sneak-bridging is priced as walking.

- baritone: `baritone/src/main/java/baritone/api/pathing/movement/ActionCosts.java:26-30 (WALK_ONE_IN_WATER_COST, WALK_ONE_OVER_SOUL_SAND_COST, SNEAK_ONE_BLOCK_COST); baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java:86-101 (half the soul-sand penalty at source, half at destination; walkOnWaterOnePenalty) and :164 (sneak-backplace ratio); baritone/src/main/java/baritone/pathing/m`
- tungsten: `MISSING (tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:404 and :410-411 pass a bare WALK_ONE_BLOCK_COST; tungsten/src/main/java/kaptainwutax/tungsten/path/calculators/ActionCosts.java:21 is a fixed SWIM cost)`
- copy: Add WALK_ONE_OVER_SOUL_SAND_COST and walkOnWaterOnePenalty (3, Settings.java:124-127) to tungsten ActionCosts, and in FastPlanner.step() charge (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST)/2 for a soul-sand source cell and the same again for a soul-sand destination cell exactly as MovementTraverse.java:92-101 does, plus walkOnWaterOnePenalty when the surface stood on is water. Compute SWIM cost per-search from the water-movement-efficiency attribute as CalculationContext.java:138-155 does instead of hardcoding WALK*2.

### [medium] missing — No backtrack-favouring multiplier on edges belonging to the previous plan, so a replan is free to pick a different equal-cost route and the bot oscillates between them; upstream halves the cost of staying on the old path.

- baritone: `baritone/src/main/java/baritone/pathing/calc/AStarPathFinder.java:138-141 (actionCost *= favoring.calculate(hashCode), "see issue #18"); baritone/src/main/java/baritone/api/Settings.java:422-427 (backtrackCostFavoringCoefficient = 0.5); baritone/src/main/java/baritone/pathing/movement/CalculationContext.java:157`
- tungsten: `MISSING (tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:1007-1021 — relax() applies no per-destination multiplier)`
- copy: Pass the previous Result's cell hashes into plan() as a long set and, in relax(), multiply edgeCost by 0.5 when the destination hash is in it (baritone's Favoring.calculate, applied at AStarPathFinder.java:140). Cheapest possible port and it directly attacks replan flapping.

### [medium] missing — Partial plans (the common case, since the search is time-sliced) are chosen as the node with the lowest raw heuristic, so a long expensive wander that got one block closer beats a short sane path; upstream scores candidates with seven cost coefficients and refuses partials shorter than 5 blocks.

- baritone: `baritone/src/main/java/baritone/pathing/calc/AbstractNodeCostSearch.java:62-73 (COEFFICIENTS = {1.5,2,2.5,3,4,5,10}, MIN_DIST_PATH = 5) and :188-222 (bestSoFar); baritone/src/main/java/baritone/pathing/calc/AStarPathFinder.java:153-162`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:319-321 and :344-347 (best = lowest heuristic only), :382 (tail = best)`
- copy: Port the bestSoFar[] machinery: keep one candidate per COEFFICIENTS entry scored `heuristic + cost / COEFFICIENTS[i]` (AStarPathFinder.java:153-157) and at line 382 return the first candidate whose squared distance from start exceeds MIN_DIST_PATH^2 (AbstractNodeCostSearch.java:193-212). Note tungsten already ported this into the legacy search (blockSpaceSearchAssist/BlockSpacePathFinder.java:194, :254-263) and skipped it in FastPlanner.

### [medium] re-derived — The still-live legacy block-space cost model is broken in a way upstream's is not: COST_INF is negative, the start node is initialised to -1,000,000, and g is not accumulated (each freshly allocated child starts at 0 and gets +1), so the coefficient scoring runs on garbage g-values.

- baritone: `baritone/src/main/java/baritone/api/pathing/movement/ActionCosts.java:42-46 (COST_INF = +1000000, with the reason it must not be MAX_VALUE); baritone/src/main/java/baritone/pathing/calc/PathNode.java:69 (cost initialised to COST_INF); baritone/src/main/java/baritone/pathing/calc/AStarPathFinder.java:143-147`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/calculators/ActionCosts.java:4 (COST_INF = -1000000); tungsten/src/main/java/kaptainwutax/tungsten/path/blockSpaceSearchAssist/BlockNode.java:132,148,168; tungsten/src/main/java/kaptainwutax/tungsten/path/blockSpaceSearchAssist/BlockSpacePathFinder.java:345-363 (updateNode: tentativeCost = child.cost + 1, unconditional overwrite) — still reachable `
- copy: Flip COST_INF to +1000000 (ActionCosts.java:4) and make updateNode accumulate from the PARENT with an improvement test, i.e. baritone's `tentativeCost = currentNode.cost + actionCost; if (neighbor.cost - tentativeCost > MIN_IMPROVEMENT)` (AStarPathFinder.java:143-147), with BlockNode.cost initialised to COST_INF as PathNode.java:69 does — or delete the legacy search path in PathFinder.java:882-886 so only FastPlanner prices routes.

### [medium] re-derived — Diagonal steps are run through the same generator as cardinals, so diagonal ascends and 3-block diagonal drops are emitted unconditionally and priced as WALK*SQRT2 plus the jump/fall penalties; upstream gates both behind settings that default OFF (corner contact with unchecked blocks) and only ever allows a one-block diagonal ascend/descend.

- baritone: `baritone/src/main/java/baritone/api/Settings.java:183-197 (allowDiagonalDescend = false, allowDiagonalAscend = false, with the safety reason); baritone/src/main/java/baritone/pathing/movement/CalculationContext.java:132-133; baritone/src/main/java/baritone/pathing/movement/movements/MovementDiagonal.java:196 (ascend = multiplier*SQRT_2 + JUMP_ONE_BLOCK_COST)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:406-412 (DIAGONALS call step(), which sweeps dy from CLIMB_MAX down to -MAX_FALL at :745)`
- copy: In FastPlanner.expand() give diagonals their own restricted call: same-level only by default, and dy = +1 / -1 only when config flags equivalent to allowDiagonalAscend / allowDiagonalDescend are on, priced as MovementDiagonal.java:196 (multiplier*SQRT2 + JUMP_ONE_BLOCK_COST) and MovementDiagonal.java:246 (+ max(FALL_N_BLOCKS_COST[1], CENTER_AFTER_FALL_COST)). Keep the existing sideClear corner guard.

### [low] re-derived — Ladder movement has a single symmetric cost used for climbing up, climbing down AND the horizontal step onto/off a ladder; upstream prices up and down differently and prices the step onto a ladder as an ordinary traverse.

- baritone: `baritone/src/main/java/baritone/api/pathing/movement/ActionCosts.java:28-29 (LADDER_UP_ONE_COST = 8.511, LADDER_DOWN_ONE_COST = 6.667); baritone/src/main/java/baritone/pathing/movement/movements/MovementPillar.java:141 (ladder ascend = LADDER_UP_ONE_COST + hardness*5); baritone/src/main/java/baritone/pathing/movement/movements/MovementDescend.java:192 (LADDER_DOWN_ONE_COST)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/calculators/ActionCosts.java:23 (LADDER_ONE_BLOCK_COST = WALK * 1.6 = 7.41); tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:462-463 (up and down), :478-479 (step off), :487-488 (step onto)`
- copy: Replace LADDER_ONE_BLOCK_COST with upstream's two constants (LADDER_UP_ONE_COST = 20/2.35, LADDER_DOWN_ONE_COST = 20/3.0) and use up/down accordingly at FastPlanner.java:462-463; charge WALK_ONE_BLOCK_COST (not a ladder cost) for the horizontal step-onto/step-off edges at :478-479 and :487-488.

### [low] missing — Relaxation accepts any improvement, however tiny, so the float noise from mixing WALK and WALK*SQRT2 over flat ground causes needless re-parenting and decrease-key work inside a hard millisecond budget; upstream requires a 0.01-tick improvement for exactly this reason.

- baritone: `baritone/src/main/java/baritone/pathing/calc/AbstractNodeCostSearch.java:75-82 (MIN_IMPROVEMENT = 0.01 with the traverse/diagonal float-error rationale); baritone/src/main/java/baritone/pathing/calc/AStarPathFinder.java:80, :144`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:1012 (`if (tentative >= next.cost) return;`)`
- copy: Change FastPlanner.java:1012 to `if (next.cost - tentative <= 0.01) return;` (baritone's MIN_IMPROVEMENT gate at AStarPathFinder.java:144).


## GOALS AND ARRIVAL — tungsten has no goal abstraction at all. Baritone's `Goal` interface pairs `isInGoal` with a matching `heuristic` in one object (`baritone/src/main/java/baritone/api/pathing/goals/Goal.java:38,48,68`) and that single object is used by the search to terminate (`AStarPathFinder.java:97`), by the result classifier (`AbstractNodeCostSearch.java:124`), and by the behaviour to declare arrival (`PathingBehavior.java:156`) — one definition, twelve implementations (block / near / XZ / get-to-block / two-blocks / composite / inverted / run-away). Tungsten instead passes a bare `BlockPos` into `FastPlanner.plan` and hardcodes the in-goal test inline (`FastPlanner.java:338`), hardcodes a *different* heuristic in `octile()` (`FastPlanner.java:1066`), and then re-invents "arrived" five more times with five different radii and dimensionalities across FastNavigator, BlockPathWalker, GotoCommand and PathExecutor. Two consequences are behavioural, not cosmetic: (a) the heuristic charges 3.563 ticks per block of |dy| while the descend/fall/slime edges are priced at 1.0 per block, so h is inadmissible downhill and A* silently returns non-optimal plans that prefer cliffs over stairs; (b) there is no way to say "within N blocks" or "adjacent to this block" to the planner, so entity-follow and get-to-block goals demand the exact target cell and produce the never-completing plan → hand-off loop that RW-1/RW-9 describe. The partial-plan chooser is also greedy-by-h, where baritone's COEFFICIENTS family exists precisely because greedy-by-h picks the end of an expensive detour.  (13 findings)

### [high] re-derived — No Goal abstraction: the goal is a bare BlockPos and the in-goal test is hardcoded inside the search loop, separate from the heuristic that estimates it

- baritone: `baritone/src/main/java/baritone/api/pathing/goals/Goal.java:38`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:338`
- copy: Port `baritone.api.pathing.goals.Goal` (the interface: `isInGoal(int,int,int)`, `heuristic(int,int,int)`, default `heuristic()`) into tungsten as e.g. kaptainwutax.tungsten.path.Goal, change `FastPlanner.plan(world, start, BlockPos goal, budget)` to take that interface, replace the inline test at FastPlanner.java:338-343 with `goal.isInGoal(current.x, current.y, current.z)` and `NodeMap.get`'s `octile(x,y,z,goal)` (FastPlanner.java:1043) with `goal.heuristic(x,y,z)`. Keep a GoalBlock-with-|dy|<=1 implementation to preserve today's default behaviour.

### [high] re-derived — Heuristic is inadmissible on descent: octile counts |dy| at 1.0/block and the whole estimate is multiplied by 3.563, while a downhill edge is priced at WALK + 1.0/block of drop

- baritone: `baritone/src/main/java/baritone/api/pathing/goals/GoalYLevel.java:49`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:1073`
- copy: Copy `GoalBlock.calculate` (GoalBlock.java:108-118): apply the 3.563 factor ONLY to the horizontal term (as `GoalXZ.calculate`, GoalXZ.java:97-116 does) and replace the `+ dy` in FastPlanner.octile with `GoalYLevel.calculate(goalY, y)` — i.e. `FALL_N_BLOCKS_COST[2]/2 * (y - goalY)` when descending and `JUMP_ONE_BLOCK_COST * (goalY - y)` when ascending. That also means porting baritone's `ActionCosts.distanceToTicks`/`FALL_N_BLOCKS_COST` table (ActionCosts.java:48,83) instead of tungsten's flat `FALL_ONE_BLOCK_COST = 1.0` (path/calculators/ActionCosts.java:18), so fall edges and the fall heuristic are on the same scale.

### [high] re-derived — Downhill and slime edges cost less than the heuristic credits, so f decreases along them — A* loses optimality and treats losing altitude as free progress

- baritone: `baritone/src/main/java/baritone/api/pathing/goals/GoalBlock.java:108`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:788`
- copy: Price the edges to at least the heuristic's per-block rate: the 3-block-drop branch (FastPlanner.java:745-789) charges WALK 4.633 + 3*1.0 = 7.63 for a move that cuts h by 3.563*(1+3) = 14.25, and the slime-bounce branch (FastPlanner.java:679-682) charges JUMP + (rise+lr)*1.0 for up to 8 blocks of horizontal travel that cuts h by 3.563*8 = 28.5. Use baritone's `WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[n] + CENTER_AFTER_FALL_COST` decomposition (ActionCosts.java:36,40,48) for drops and charge horizontal air travel at `SPRINT_ONE_BLOCK_COST` per block.

### [high] re-derived — Best-partial-node is chosen greedily by h alone, with no cost term and no minimum-length filter

- baritone: `baritone/src/main/java/baritone/pathing/calc/AStarPathFinder.java:153`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:344`
- copy: Copy `COEFFICIENTS = {1.5,2,2.5,3,4,5,10}` and `MIN_DIST_PATH = 5` (AbstractNodeCostSearch.java:68,73), keep a `bestHeuristicSoFar[i]` array scored as `node.heuristic + node.cost / COEFFICIENTS[i]` (AStarPathFinder.java:153-162), and pick the tail in FastPlanner.plan:382 via baritone's `bestSoFar()` rule (AbstractNodeCostSearch.java:188-213) — first coefficient whose node is further than MIN_DIST_PATH from the start. This is what FastNavigator's ad-hoc `MIN_PARTIAL_PROGRESS = 4.0` (FastNavigator.java:50) is patching from outside the search.

### [high] missing — No way to express "within N blocks" to the planner: the follow radius is only a hold-position gate, while the search still demands the target's exact x/z cell

- baritone: `baritone/src/main/java/baritone/api/pathing/goals/GoalNear.java:42`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/FollowEntityTask.java:184`
- copy: Port `GoalNear` (isInGoal = `dx*dx+dy*dy+dz*dz <= rangeSq`, GoalNear.java:42-47, plus its `heuristic()` no-arg at GoalNear.java:58-83) and have FollowEntityTask pass `new GoalNear(targetCell, closeEnough)` into the planner instead of only gating at FollowEntityTask.java:212-214. Today a target standing on a 1-block pillar or in a cell the bot cannot occupy makes `FastPlanner.java:338` unsatisfiable forever, so every plan is partial and FastNavigator.java:332-354 loops the physics hand-off.

### [high] re-derived — The physics search's heuristic is in blocks while its g is in ticks, so h is roughly half the true cost — the search degenerates and times out

- baritone: `baritone/src/main/java/baritone/api/pathing/goals/GoalXZ.java:115`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathFinder.java:681`
- copy: g is `child.cost + 1` per simulated tick (PathFinder.java:692) but h is `0.8 * euclid(next guide cell) + euclid(realTarget)` in BLOCKS (PathFinder.java:681-685), i.e. ~1.8 ticks-equivalent per block against a true ~3.56 — so f is dominated by g and A* spreads like Dijkstra until the budget dies ("Partial path (goal unreachable)"). Copy baritone's scaling: multiply the distance terms by `SPRINT_ONE_BLOCK_COST` (3.564, ActionCosts.java:31) / the `costHeuristic` 3.563 default (Settings.java:413) the way GoalXZ.calculate does at GoalXZ.java:115, and stop summing two independent distances (guide + realTarget) into one estimate.

### [medium] re-derived — Arrival is defined five times with different radii and dimensionality, none of them the planner's own in-goal test

- baritone: `baritone/src/main/java/baritone/behavior/PathingBehavior.java:156`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/FastNavigator.java:111`
- copy: Route every one of these through a single `goal.isInGoal(player.getBlockPos())` the way PathingBehavior.java:156 and AbstractNodeCostSearch.java:124 share one predicate: FastNavigator.java:111 (3D sphere ARRIVE_DIST 2.0), FastNavigator.java:193 (`horiz < 1.5 && |rise| < 1.0`), GotoCommand.java:19/119 (ARRIVAL_DIST_SQ 2*2, 3D), PathExecutor.java:555 (`distanceTo(goal) < 2.0`), BlockPathWalker.java:407 (horizontal 1.5). The 3D spheres are the behavioural bug: standing 2 blocks directly above the goal on a ledge satisfies FastNavigator.java:111 and the bot reports "arrived" at a cell the planner would never have accepted.

### [medium] re-derived — The planner's |dy|<=1 goal tolerance is invisible to the heuristic, so h > 0 at an in-goal cell and there is no `heuristic()` no-arg for that case

- baritone: `baritone/src/main/java/baritone/api/pathing/goals/GoalTwoBlocks.java:64`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:339`
- copy: Copy GoalTwoBlocks' paired definition: `isInGoal` = `y == this.y || y == this.y - 1` (GoalTwoBlocks.java:59-61) with `heuristic` = `GoalBlock.calculate(xDiff, yDiff < 0 ? yDiff + 1 : yDiff, zDiff)` (GoalTwoBlocks.java:64-69), so the tolerated cell scores h = 0. As written, a cell one below the goal is accepted by FastPlanner.java:339 but still carries h = 3.563, so the search will pay up to ~3.5 ticks of extra route to level with the goal cell before it will pop the tolerated one; and Goal.heuristic() (Goal.java:58-70), which exists exactly for goals whose in-goal heuristic is non-zero, has no tungsten counterpart.

### [medium] re-derived — GoalSnap moves the target to a standable cell instead of relaxing the goal test, and only searches the target's own column

- baritone: `baritone/src/main/java/baritone/api/pathing/goals/GoalGetToBlock.java:49`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/GoalSnap.java:36`
- copy: Port `GoalGetToBlock.isInGoal` (`|dx| + |yDiff<0 ? yDiff+1 : yDiff| + |dz| <= 1`, GoalGetToBlock.java:49-54) and its heuristic (GoalGetToBlock.java:57-62) and use it for "get to this block" targets instead of GoalSnap.snap. GoalSnap only scans the goal's own column (+5 up at GoalSnap.java:41-43, -8 down at 44-45); a chest or furnace with a solid block on top has no standable cell in that column, so snap returns the original (GoalSnap.java:46), the goal cell is solid, FastPlanner.java:338 can never be satisfied, and GotoCommand burns its 10 retries (GotoCommand.java:18,66).

### [medium] re-derived — The same goal is rounded two different ways two lines apart — int cast vs floor — so the guide's heuristic aims one block off in negative coordinates

- baritone: `baritone/src/main/java/baritone/api/pathing/goals/GoalXZ.java:122`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathFinder.java:851`
- copy: PathFinder.java:849-851 builds the `blockSpaceSearchAssist.Goal` with `(int) target.x/y/z` while PathFinder.java:854 builds the planner's goal with `BlockPos.ofFloored(target)`. For target.x = -5.5 those are -5 and -6. Every BlockNode's `estimatedCostToGoal` (BlockNode.java:133,149,169) is then measured to a cell one block from the one being searched for. Use `MathHelper.floor` / `BlockPos.ofFloored` everywhere, as baritone does when constructing goals (GoalXZ.fromDirection, GoalXZ.java:118-123).

### [medium] re-derived — tungsten's block-space Goal is a copy of GoalBlock with the tick-scaled heuristic replaced by plain 3D Euclidean distance

- baritone: `baritone/src/main/java/baritone/api/pathing/goals/GoalBlock.java:63`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/blockSpaceSearchAssist/Goal.java:61`
- copy: Replace the body of `Goal.calculate` (blockSpaceSearchAssist/Goal.java:61-64, `sqrt(dx^2+dy^2+dz^2)`) with GoalBlock.calculate's `GoalYLevel.calculate(0, yDiff) + GoalXZ.calculate(xDiff, zDiff)` (GoalBlock.java:108-118). As written it returns ~1 per block while the edges it is compared against are `WALK_ONE_BLOCK_COST = 4.633` ticks (path/calculators/ActionCosts.java:5), so h is ~4.6x too small and the legacy block search (still the fallback at PathFinder.java:882) is effectively Dijkstra with a 2000-candidate branching factor.

### [low] re-derived — Node relaxation has no minimum-improvement epsilon, so float noise from traverse+diagonal combinations triggers pointless decrease-key work inside a hard time budget

- baritone: `baritone/src/main/java/baritone/pathing/calc/AStarPathFinder.java:144`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:1012`
- copy: Copy `MIN_IMPROVEMENT = 0.01` (AbstractNodeCostSearch.java:82, with its comment about 1e-16 improvements over flat ground) and change FastPlanner.java:1012 from `if (tentative >= next.cost) return;` to `if (next.cost - tentative <= MIN_IMPROVEMENT) return;`. Matters because plan() is wall-clock-sliced at fastPlanBudgetMs (FastPlanner.java:333), so wasted sift-ups cost reachable nodes.

### [low] re-derived — A partial plan's speculative tail is committed to in full; there is no cutoff of the least-reliable end

- baritone: `baritone/src/main/java/baritone/utils/pathing/PathBase.java:46`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/FastNavigator.java:412`
- copy: Copy `staticCutoff` (PathBase.java:46-57): when `!goal.isInGoal(getDest())`, trim to `(length - min) * 0.9 + min - 1` with pathCutoffMinimumLength 30 / pathCutoffFactor 0.9 (Settings.java:507,512). FastNavigator only caps at `LEG_LENGTH = 32` (FastNavigator.java:412-414) and applies it identically to complete and partial plans, so the walker commits to the far end of a plan produced by an inadmissible h and a greedy best-node, then the STALL_TICKS 60 watchdog (FastNavigator.java:38,132) fires instead.


## Move-set audit: baritone's Moves enum + movements/ package vs tungsten FastPlanner's generators (step / diagonal / climb / drop / parkour / placeAcross / pillarUp / special / breakThrough).

Shape of the result: tungsten's generator set covers baritone's TRAVERSE, ASCEND, DESCEND, DIAGONAL, PARKOUR and PILLAR (place-only), and adds three moves baritone has no analogue for (swim, ladder, slime-bounce in special()). What is systematically absent is not whole directions but the PRECONDITION layer baritone accumulated: (a) there is no hazard predicate anywhere in FastPlanner — nothing corresponding to MovementHelper.avoidWalkingInto / canWalkThroughBlockState, so lava, fire, cobweb, berry bushes and powder snow are invisible to the planner because PlayerFit only asks collision shapes; (b) every "you cannot take off from / land on this block" rule (bottom slab, stairs, ladder/vine, soul sand, water, lilypad/carpet-on-water, FallingBlock columns) is missing; (c) the two dynamic moves are the weakest — parkour reaches ONE BLOCK FARTHER than vanilla allows and cannot ascend or place, and the drop is a flat 3-block cap with no fall-into-water, no water-bucket MLG and no vine/ladder catch, which is baritone's whole cliff-descent strategy. One move is absent outright: Moves.DOWNWARD (dig down / climb down a ladder you are standing on top of) has no generator at all, so the bot cannot descend a shaft it makes itself.

⛔ PARTIALLY CLOSED, CHECKED 2026-09-02 — this is a mixed section, not uniformly open or fixed;
five of the fifteen findings spot-checked directly against current `FastPlanner.java`, not
sampled to make a point either way:

- **(a), the hazard predicate — FIXED.** `hazardAt()` (`FastPlanner.java:1376-1389`) exists now,
  explicitly cites this exact gap in its own javadoc ("tungsten even had the pieces already...
  and this class called neither" — past tense) and calls `BlockStateChecker.isAnyLava` — the
  same class this session separately found was almost wrongly deleted as dead code (see C5.22)
  precisely because it was an unfinished landing spot rather than duplicate cruft. This is what
  it was waiting to be finished for.
- **Moves.DOWNWARD — STILL MISSING.** No `downward()` generator, no reference to digging down or
  climbing down onto a ladder from above, anywhere in the file.
- **Parkour take-off gating (vine/ladder/stairs/slab/soul-sand/water refusal,
  `checkOvershootSafety`) — STILL MISSING.** No matching guard found.
- **Diagonal cutting-over hazard check / the one-corner-open "edge around" rule — STILL
  MISSING.** No `cuttingOver`/`optionA`/`optionB` equivalent found.
- **Vine climbing — STILL NOT WIRED.** `isLadder()` (`:928-930`) is still `instanceof
  LadderBlock` only; a `Blocks.VINE` reference does exist elsewhere in the file (`:1255`) but for
  a different check, not this one — vines still are not climbable via the ladder path.

The other ten findings in this section (parkour distance/ascend/place, drop depth modeling,
pillar mining-through, the bridge 5-direction scan, sprint-speed pricing) were not re-checked
this pass — treat them as unverified, not as confirmed-still-open just because the five sampled
here mostly were.

None of this needs calling baritone (which is not compiled); every item is a self-contained predicate or constant to copy into FastPlanner.  (15 findings)

### [high] re-derived — Parkour reaches one block farther than vanilla: tungsten lands at distance gap+1 with gap up to MAX_JUMP_GAP=4, i.e. a 5-block jump over 4 air cells; baritone's loop puts the DESTINATION at distance i, i<=4, i.e. 3 air cells max.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementParkour.java:104-119`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:59 (MAX_JUMP_GAP=4), landing computed at from + dx*(gap+1) at FastPlanner.java:967-973`
- copy: Reinterpret MAX_JUMP_GAP as baritone's `maxJump` (a DESTINATION distance, not an air-cell count): make the parkour loop put the landing cell at most 4 blocks away (gap <= 3), and 3 away when sprinting is unavailable — baritone's `if (context.canSprint) maxJump = 4; else maxJump = 3;` (MovementParkour.java:108-112). The comment at FastPlanner.java:52-58 that justifies 4 ("a vanilla sprint-jump clears 4 air blocks") is the error: the standard 4-block jump LANDS 4 away, clearing 3.

### [high] missing — No hazard predicate in the planner at all: step/diagonal/parkour/placeAcross judge cells only by collision shape via PlayerFit, so lava, fire, cobweb, sweet berry, bubble column and powder snow (all with empty or near-empty collision shapes) are treated as free air, and magma is treated as an ordinary floor.

- baritone: `baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:420-431 (avoidWalkingInto) and MovementHelper.java:187-195 (canWalkThroughBlockState returns NO for fire/tripwire/cobweb/sweet berry/powder snow/dripstone)`
- tungsten: `MISSING`
- copy: Port avoidWalkingInto(BlockState) verbatim (MovementHelper.java:420-431) plus the NO-list of canWalkThroughBlockState (MovementHelper.java:187-195) into a tungsten predicate, and gate every relax() destination on it: step() at FastPlanner.java:759-789, the diagonal sideClear at FastPlanner.java:989-993, the parkour landing at FastPlanner.java:976-982, the bridge destination at FastPlanner.java:876-878. tungsten/helpers/BlockStateChecker.java already has isAnyLava (line 246) and a fullyPassableBlockState NO-list (lines 40-62) that FastPlanner never consults.

### [high] missing — Moves.DOWNWARD is absent: no generator descends the cell you stand in by mining the floor block, and none climbs DOWN a ladder/vine you are standing on top of (tungsten's ladder branch only fires when the CURRENT cell is already a ladder).

- baritone: `baritone/src/main/java/baritone/pathing/movement/Moves.java:31-41 and baritone/src/main/java/baritone/pathing/movement/movements/MovementDownward.java:57-72`
- tungsten: `MISSING (expand() emits only step/diagonal/placeAcross/pillarUp/special — FastPlanner.java:403-417; breakThrough is cardinal-only, FastPlanner.java:810)`
- copy: Add a downward() generator called from expand(): copy MovementDownward.cost — require canWalkOn at (x, y-2, z) (MovementDownward.java:61-63), return LADDER_DOWN_ONE_COST when the block below is a ladder/vine (lines 66-67), otherwise price FALL_N_BLOCKS_COST[1] plus the mining ticks of the floor block and emit it as a toBreak waypoint through the existing relax(..., toBreak) overload (FastPlanner.java:1001-1005).

### [high] missing — Drop moves are a flat MAX_FALL=3 cap. Baritone's dynamicFallCost scans the column down and accepts a fall of ANY depth into still water, a fall onto a ladder/vine that resets fall speed, and a deeper fall paid for with a water bucket (MLG); it also refuses to land on a bottom slab.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementDescend.java:136-222 (water landing 159-179, ladder/vine catch 188-195, bottom-slab refusal 202-204, maxFallHeightNoWater and hasWaterBucket 205-218)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:50 (MAX_FALL=3) applied in the step() dy loop at FastPlanner.java:745`
- copy: Port dynamicFallCost as a fall() generator: the descending scan with `unprotectedFallHeight`, the isWater landing branch (non-flowing, and canWalkOn one below — MovementDescend.java:159-172), the vine/ladder reset (`costSoFar += LADDER_DOWN_ONE_COST; effectiveStartHeight = newY`, lines 188-195), the isBottomSlab refusal (lines 202-204) and the hasWaterBucket branch (lines 213-218). Also replace the linear FALL_ONE_BLOCK_COST=1.0 (tungsten/path/calculators/ActionCosts.java:18) with baritone's tick-accurate FALL_N_BLOCKS_COST table (baritone/api/pathing/movement/ActionCosts.java:48,67-97) so deep drops are priced by real airtime.

### [medium] missing — Parkour cannot ascend: tungsten only accepts a landing at the same level or one down and explicitly rejects a higher landing. Baritone jumps a gap ONTO a block one higher for distances up to 3 when sprinting.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementParkour.java:130-138`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:974-978 (`for (int dy = 0; dy >= -1; dy--)` and `if (top - support > PlayerFit.STEP_HEIGHT) continue;`)`
- copy: In parkour(), add baritone's ascend branch: when the destination cell at feet level is NOT passable but is walkable-on and the distance is <= 3, emit the landing at y+1 with cost `i * SPRINT_ONE_BLOCK_COST + jumpPenalty` guarded by checkOvershootSafety (MovementParkour.java:132-137).

### [medium] missing — No parkour-place: baritone, when a gap is too wide to clear, walks the verified jump distances largest-to-smallest and places a block at the landing spot mid-flight. tungsten's only place-move is a walking-level bridge one cell ahead.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementParkour.java:166-200`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:867-903 (placeAcross — one cardinal cell, floor at from.y-1 only)`
- copy: Add the `verifiedMaxJump` bookkeeping to tungsten's parkour loop (MovementParkour.java:116,163) and then the descending place loop: isReplaceable at (destX, y-1, destZ), the 5-direction canPlaceAgainst scan over HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP with the "we can't turn around that fast" exclusion (MovementParkour.java:185-191), cost = costFromJumpDistance(i) + placeCost + jumpPenalty. Emit via the existing relax(..., toPlace) overload (FastPlanner.java:1007-1021), which already feeds the executor's place queue.

### [medium] re-derived — Parkour take-off has almost no block-type gating. Baritone refuses to jump from vine, ladder, stairs or a bottom slab, refuses to jump out of water, and drops maxJump to 2 when standing on soul sand.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementParkour.java:93-113`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:962-965 (only a cardinal test and a head-clearance passableAt at support+1.0)`
- copy: Add the `standingOn` guard block at the top of parkour(): read the state at (from.x, from.y-1, from.z) and return on VINE / LADDER / StairsBlock / isBottomSlab (MovementParkour.java:93-96); return when the feet cell has a non-empty fluid state ("can't jump out of water", lines 101-103); set maxJump=2 on SOUL_SAND (lines 105-106). Also copy checkOvershootSafety (MovementParkour.java:203-206) so a landing whose next two cells are lava/fire/magma is rejected.

### [medium] re-derived — Parkour is only generated when the adjacent cell has no floor at ANY level from +3 to -3, because step() returns on the first level it can reach. A gap whose near side has a ledge one to three blocks down is therefore always descended and the jump is never offered to A* as an alternative; baritone expands DESCEND and PARKOUR independently and suppresses parkour only when a plain TRAVERSE exists.

- baritone: `baritone/src/main/java/baritone/pathing/movement/Moves.java:151-167 (DESCEND_*) and Moves.java:279-289 (PARKOUR_*) as separate enum entries; the only suppression is MovementParkour.java:76-80`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:789-794 (`relax(...); return;` then parkour() only after the dy loop finds nothing)`
- copy: Call parkour() unconditionally from step() (or from expand()) instead of only on the no-floor fall-through, and reproduce baritone's narrower guard inside parkour(): return only when the cell at (from.x+dx, from.y-1, from.z+dz) is walkable-on, i.e. when a plain traverse exists (MovementParkour.java:76-80). Let A* choose between the descend edge and the jump edge on cost.

### [medium] re-derived — Diagonals require BOTH orthogonal corner cells to be passable, so baritone's "edge around" diagonal — legal when exactly one corner is open, priced at (SQRT_2 - 0.001) with sprinting disabled — is never generated; conversely tungsten never checks what it cuts OVER, so a diagonal across a lava or magma corner is allowed.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementDiagonal.java:202-243 (optionA/optionB one-of-two rule, sprint only when neither corner is obstructed) and MovementDiagonal.java:157-164 (cuttingOver1/cuttingOver2 magma/lava refusal)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:406-412 (both sideClear() calls must pass), sideClear at FastPlanner.java:989-993`
- copy: Replace the two-way AND with baritone's optionA/optionB logic: allow the diagonal when at least ONE corner column (feet+head) is clear, charge WALK*(SQRT_2 - 0.001) in that case, and refuse it when the start cell is a ladder/vine (MovementDiagonal.java:229-234); additionally test the two cut-over floor cells (x, y-1, destZ) and (destX, y-1, z) for MAGMA_BLOCK / lava and return (MovementDiagonal.java:157-164).

### [medium] missing — Vines are not climbable: isLadder tests `instanceof LadderBlock` only, so the whole special() ladder path (climb up, climb down, step off) never fires on a vine wall and vines are seen as ordinary obstruction. Baritone treats VINE and LADDER together in pillar, downward and descend.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementPillar.java:67 (`ladder = from == LADDER || from == VINE`, priced LADDER_UP_ONE_COST at line 141) and MovementDownward.java:66-67`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:712-714`
- copy: Widen isLadder() to `block instanceof LadderBlock || block == Blocks.VINE` (matching MovementPillar.java:67), and for vines require baritone's attachment test hasAgainst(context, x, y, z) — an adjacent normal cube on one of the four sides (MovementPillar.java:77-79,147-152) — before emitting the climb, since an unattached vine cannot be climbed.

### [medium] re-derived — pillarUp cannot mine its way up and has no take-off gating: it requires the two cells above to be empty (bodyFits), whereas baritone's PILLAR prices breaking the block at y+2 as part of the move; baritone also refuses to pillar from a bottom slab, from inside water with no face to place against, off a lilypad/carpet floating on water, and under a FallingBlock column.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementPillar.java:113-139 (mining y+2, FallingBlock suffocation guard 121-130), MovementPillar.java:73-75 (bottom slab), MovementPillar.java:103-112 (water / lilypad / carpet)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:913-933 (bodyFits at upY line 918, feet cell must be empty line 920, support-or-branchPlaced line 925)`
- copy: In pillarUp(): when bodyFits fails only because of the cell at (x, y+2, z), price its break with BreakRules.canBreak + calcBlockBreakingDelta (the same code breakThrough already uses at FastPlanner.java:842-845) and emit it as toBreak alongside the toPlace. Add the guards: return if (x, y+3, z) is a FallingBlock unless the stack is already cleared (MovementPillar.java:121-130); return if the block below is a bottom slab (lines 73-75); return if the feet cell is liquid and (x, y-1, z) offers no placeable face (lines 103-108).

### [medium] re-derived — The bridge move can only BACKPLACE: the sole accepted click face is the block under the bot's own feet. Baritone tries five faces around the target floor cell first (excluding the one behind it) and treats a backplace as a distinct, more expensive and more restricted case (sneak speed; forbidden from soul sand, half slabs, lilypad/carpet over water, and while swimming).

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java:142-165`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:894-896 (single `against` = from.x, from.y-1, from.z)`
- copy: Copy the 5-direction scan from MovementTraverse.java:142-152 (HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP around (destX, y-1, destZ), skipping the cell equal to src) with a canPlaceAgainst test; when only the backplace remains, apply baritone's refusals and cost — COST_INF on SOUL_SAND or a non-double SlabBlock below, COST_INF when not standing on a block, COST_INF on LILY_PAD/CarpetBlock over fluid, otherwise multiply the walk cost by SNEAK_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST (MovementTraverse.java:154-165).

### [medium] missing — There is no place-to-ascend move: tungsten can build straight up (pillarUp) or straight ahead at its own level (placeAcross), but cannot place the block it then steps UP onto, which is how baritone climbs a stepped wall without towering.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementAscend.java:67-94`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:867-903 (placeAcross places only at from.y-1); step()'s climb branch needs an EXISTING support at the destination, FastPlanner.java:745-789`
- copy: Add an ascend-place branch to placeAcross parameterised by dy=+1: destination (nx, from.y+1, nz) must have room for the body, the block goes at (nx, from.y, nz) which must be isReplaceable, and one of the five non-up faces around it must be placeable-against, excluding the src column (MovementAscend.java:75-93). Also copy MovementAscend.java:119-123 — an ascend out of a bottom slab onto a non-slab is impossible.

### [medium] re-derived — Unobstructed traverse/diagonal are priced at full walking speed, never sprint speed, while the heuristic weight is baritone's sprint-tuned 3.563 — so h is only ~77% of the true per-block edge cost and the search expands far wider than baritone would for the same time budget, which a 250 ms slice cannot afford.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java:108-115 (`WC *= SPRINT_MULTIPLIER` when nothing needs breaking and it is not water) and MovementDiagonal.java:236-242; SPRINT_MULTIPLIER = 0.769 at baritone/src/main/java/baritone/api/pathing/movement/ActionCosts.java:32`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:404-411 (flat WALK_ONE_BLOCK_COST and WALK*SQRT2) against HEURISTIC = 3.563 at FastPlanner.java:46`
- copy: Add SPRINT_MULTIPLIER = SPRINT_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST to tungsten/path/calculators/ActionCosts.java and apply it in expand() to the straight and diagonal edges when the move needs no breaking/placing and the cell is not water — the same condition as MovementTraverse.java:109. That makes the 3.563 heuristic tight again and restores baritone's preference for open running over tunnelling.

### [low] divergent-on-purpose — A single climb edge covers rises up to CLIMB_MAX=3 flagged needsPhysics, where baritone decomposes the same terrain into repeated one-block PILLAR moves. tungsten states the reason (its physics engine executes the step, and refusing the edge outright made the chase stop at cliffs) and gates the edge on planPlaceMoves so it is only offered when pillaring is actually possible.

- baritone: `baritone/src/main/java/baritone/pathing/movement/Moves.java:43-53 (PILLAR, one block per move)`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:96-102 (CLIMB_MAX rationale) and FastPlanner.java:772-789 (the climb edge and its planPlaceMoves gate)`
- copy: nothing


## Off-thread world access: baritone builds a client-thread chunk SNAPSHOT (BlockStateInterface + createThreadSafeCopy) and funnels every search read through one accessor (get0) plus a BlockView wrapper for shape math; tungsten's searches read the LIVE ClientWorld from raw background threads (FastNavigator.planAhead, PathFinder's thread/executor) and try to buy back the cost with two per-search value memos (FastPlanner.STATE_CACHE, PlayerFit.ClassifyCache). A memo is not a snapshot: it only makes the SECOND read of a cell consistent, it caches ANSWERS that the client thread can invalidate mid-search, and half the search's reads bypass it entirely. Tungsten's own comment at FastPlanner.java:695 concedes this ("the cheap half of the off-thread snapshot this planner really wants"), and the snapshot shell it once started (VoxelWorld/MixinWorldChunk) is dead code.  (7 findings)

### [high] missing — Per-search thread-safe copy of the loaded-chunk reference array, built on the client thread, so the search never touches the live ClientChunkManager.

- baritone: `baritone/src/main/java/baritone/utils/BlockStateInterface.java:67`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/FastNavigator.java:303`
- copy: Port MixinClientChunkProvider.createThreadSafeCopy() (baritone/src/main/java/baritone/launch/mixins/MixinClientChunkProvider.java:39-46) plus MixinChunkArray.copyFrom() (MixinChunkArray.java:78-96, the AtomicReferenceArray<WorldChunk> copy that also pins centerChunkX/centerChunkZ and viewDistance). Build it on the client thread — CalculationContext.java:104 does `new BlockStateInterface(ctx, forUseOnAnotherThread)` and BlockStateInterface.java:72-74 throws IllegalStateException if not `ctx.minecraft().isOnThread()`. Then FastNavigator.planAhead (which already reads the inventory on the client thread at FastNavigator.java:301) must capture the snapshot there and pass it as the WorldView into 

### [high] re-derived — A single read funnel (get0) for the whole search, plus a BlockView wrapper so collision-shape queries never touch the live world or block entities.

- baritone: `baritone/src/main/java/baritone/utils/BlockStateInterface.java:76`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/helpers/PlayerFit.java:44`
- copy: Copy BlockStateInterfaceAccessWrapper (baritone/src/main/java/baritone/utils/BlockStateInterfaceAccessWrapper.java:49 routes getBlockState to bsi.get0; :42 deliberately returns null from getBlockEntity) and pass THAT as the WorldView to every shape call, i.e. PlayerFit.shapeAt:44, classifyUncached:131, supportTop:181 and :189, BlockShapeChecker.getBlockHeight:33 / getShapeVolume:57 / getBlockHeight:91, GoalSnap.solid:24. Today `state.getCollisionShape(world, pos)` hands the LIVE ClientWorld to blocks whose shape reads a block entity (shulker box, MOVING_PISTON — the very blocks MovementHelper.java:788-796 lists as exclusions), i.e. an off-thread ClientWorld.getBlockEntity. Also fold the read

### [high] re-derived — Baritone's per-search cache memoises the chunk REFERENCE, never the answer, so it cannot go stale; tungsten memoises the classification and then trusts it without re-reading.

- baritone: `baritone/src/main/java/baritone/utils/BlockStateInterface.java:105`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:328`
- copy: Replace the value memo with baritone's reference memo: the `prev` WorldChunk single-slot cache at BlockStateInterface.java:105-119 plus static getFromChunk (BlockStateInterface.java:168-174). Every read still resolves against the real chunk data, so a block the client thread just mined is seen as mined. PlayerFit's ClassifyCache (PlayerFit.java:70-97) pins 0/1/2 per position for the whole search, and PlayerFit.supportTop:186-188 takes `under == 1` as a floor at below.getY()+1.0 with NO read at all — so once a cell is memoised as a full cube, a block broken mid-search stays solid for the rest of the plan (and vice versa, air stays air). PlayerFit.java:66-69 states the precondition ("must neve

### [medium] missing — World border and world-height bounds checked on every expansion, for both movement and block placement.

- baritone: `baritone/src/main/java/baritone/pathing/calc/AStarPathFinder.java:111`
- tungsten: `MISSING`
- copy: Copy BetterWorldBorder (baritone/src/main/java/baritone/utils/pathing/BetterWorldBorder.java:40 entirelyContains, :44 canPlaceAt — note canPlaceAt insets by one block because vanilla refuses a right-click against a block outside the border), construct it once per plan from world.getWorldBorder() the way BlockStateInterface.java:64 does, and check entirelyContains at FastPlanner's relax sites (step:789, parkour, special) and canPlaceAt in placeAcross (FastPlanner.java:881, the `floor` cell) and pillarUp (FastPlanner.java:919, the `feet` cell). Also copy the y bound at AStarPathFinder.java:114 (`> height || < minY`, from world.getDimension().minY()/height() at AStarPathFinder.java:52-53) — tun

### [medium] missing — "Unloaded" is a third answer, distinct from "air": the search refuses to expand into unloaded chunks, the path is cut at the load boundary, and an unloaded goal loses its Y coordinate.

- baritone: `baritone/src/main/java/baritone/utils/BlockStateInterface.java:142`
- tungsten: `MISSING`
- copy: Copy worldContainsLoadedChunk (BlockStateInterface.java:79-81, `provider.isChunkLoaded(blockX >> 4, blockZ >> 4)`) and use it exactly where baritone does: (a) the expansion guard at AStarPathFinder.java:104, which only pays for the check when the destination crosses a chunk boundary (`newX >> 4 != currentNode.x >> 4`) — wire it into FastPlanner.step before relax:789; (b) PathingBehavior.java:558-562's simplifyUnloadedYCoord transform (goal in an unloaded chunk → drop to XZ-only) in the goal setup, because FastPlanner's completion test at FastPlanner.java:338-339 demands `Math.abs(current.y - goal.getY()) <= 1`, so any goal past render distance has an unknowable Y and EVERY long-range plan co

### [medium] re-derived — Allocation-free hot lookup path: one-slot chunk memo plus an empty-section early-out, instead of a boxed hash map per read.

- baritone: `baritone/src/main/java/baritone/utils/BlockStateInterface.java:168`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:704`
- copy: Copy get0's structure: the `prev` WorldChunk / `prevCached` CachedRegion single-slot memos (BlockStateInterface.java:104-134, chunk hit is a >>4 compare) and static getFromChunk (BlockStateInterface.java:168-174) whose `section.isEmpty()` branch returns AIR without touching a palette. FastPlanner's STATE_CACHE is a HashMap<Long,BlockState> (FastPlanner.java:696-697), so `cache.get(key)` at :704 and `cache.put(key, st)` at :708 autobox a Long on every read of every cell — an allocation per lookup inside the loop the memo was added to speed up. This is behavioural, not cosmetic, by tungsten's own accounting: nodes-per-budget decides plan completeness (FastPlanner.java:60-64 records 164 nodes i

### [medium] re-derived — Block classification cached per BLOCK STATE ID for the whole session, with a MAYBE bit for the few genuinely position-dependent blocks — not per position, per search.

- baritone: `baritone/src/main/java/baritone/pathing/precompute/PrecomputedData.java:30`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/helpers/PlayerFit.java:70`
- copy: Copy PrecomputedData's table: `int[] data = new int[Block.STATE_IDS.size()]` indexed by Block.STATE_IDS.getRawId(state) (PrecomputedData.java:30, :73-84), filled lazily by fillData (:40-70) with a COMPLETED_MASK plus paired VALUE/SPECIAL bits (:32-37) where SPECIAL means "must ask the position". PlayerFit.classify's 0/1/2 (PlayerFit.java:128-138) is a pure function of the BlockState for everything except the pos/block-entity-dependent shapes, so it belongs in that table with a SPECIAL bit for the same exclusion list baritone keeps at MovementHelper.java:789-796 (Bamboo, PistonExtension, Scaffolding, ShulkerBox, PointedDripstone, AmethystCluster — tungsten's list at BlockShapeChecker.java:112


## Tungsten's execution layer has one failure detector — a wall-clock "distance to the final goal stopped shrinking" watchdog — where baritone has five independent, cheap, per-movement ones (cost-proportional per-move timeout, graduated off-path distance, live cost re-verification, position resync, chunk-edge pause), each of which converts a specific failure into a re-plan rather than into a stop. The result is that tungsten fails in the two worst ways: it kills navigation on routes that are succeeding but detouring, and it keeps walking on routes that have already gone wrong. On top of that, baritone's failure path is closed-loop (PathingBehavior re-searches on the same tick a segment fails); tungsten's watchdog "hands over" to a caller that does not exist, and does not even release the walker it leaves running. Most of the graduated-tolerance and per-move-budget logic is a direct copy job — the FastPlanner already carries per-node ActionCosts, so a per-waypoint budget needs no new information.  (10 findings)

⛔ CHECKED 2026-09-02, WITH DELIBERATE CAUTION — this file (`FastNavigator.java`) has visibly
been through many more iterative fixes since this section was written than most others in this
audit (its own comments narrate several: "BUILDING IS PROGRESS", the physics-hand-off exemption,
"RUNNING is not PROGRESSING"), so a shallow read risks re-flagging something already handled a
different way. Two things confirmed with reasonable confidence, one flagged as uncertain rather
than asserted:

- **The flat global timeout is still exactly as described.** `STALL_TICKS = 60` (`:42`) is a
  single wall-clock constant gating `tick()`'s distance-to-goal check (`:262`), not a per-move
  budget derived from `ActionCosts` — the finding's literal claim holds.
- **`stop()` (`:142-155`) still does not call `BlockPathWalker.stop()`** — it clears the
  navigator's own state and stops `MovementQueue` (with a comment explaining exactly why THAT
  needs stopping explicitly: "a queue left running past the navigator would keep pressing keys
  with nobody steering"), but the equivalent line for the walker is absent from this general
  `stop()`. `BlockPathWalker.stop()` is only called from the separate "arrived" branch (`:182`).
  Since the stall watchdog at `:262-266` calls this same general `stop()`, a stall firing while
  `BlockPathWalker` (not `MovementQueue`) owns movement would, on this reading, leave the walker
  running with the navigator no longer supervising it — the literal shape of the audit's claim.
- **NOT asserting this as a live, currently-reproducing bug.** `BlockPathWalker.java` has its own
  separate stall/bail logic (`LIVE_STUCK_LIMIT`, `NO_PROGRESS_LIMIT`, around `:65-332`) for its
  OWN direct-vs-BFS mode switching, and I have not traced whether that independently catches the
  case the navigator's `stop()` misses, or whether the two mechanisms interact in a way a static
  read of one file cannot show. Given how many prior fixes in this exact function were driven by
  a live trace catching something a comment-level read would have missed, this is flagged for
  someone with stand access to check with `NAVSTATE`/`verboseDebugLogging` on a reproduced stall,
  not asserted as confirmed the way the two points above are.

### [high] re-derived — Movement timeout is a flat 60-tick global watchdog on distance-to-GOAL, instead of a per-move budget proportional to that move's own cached cost estimate and reset on every move boundary.

- baritone: `baritone/src/main/java/baritone/pathing/path/PathExecutor.java:242`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/FastNavigator.java:132`
- copy: Copy the ticksOnCurrent mechanism: cache the move's cost once when it starts (PathExecutor.java:195-198, deliberately NOT recalculated each tick), compare ticksOnCurrent > cachedCost + movementTimeoutTicks (=100, Settings.java:570) at PathExecutor.java:243, and reset it in onChangeInPathPosition() (PathExecutor.java:583-586). In tungsten this means: expose the per-step cost on FastPlanner.Waypoint (the g-cost delta is already there, FastPlanner.java:251 Node.cost, in ActionCosts units), give BlockPathWalker a ticksOnCurrentWp counter zeroed where waypointIdx++ happens (BlockPathWalker.java:409), and delete the goal-distance test at FastNavigator.java:129-136. Note the extra defect in the cur

### [high] missing — The BFS walker has no notion of being off its path at all — no distance-to-path measurement, no graduated tolerance, no cancel-and-replan.

- baritone: `baritone/src/main/java/baritone/pathing/path/PathExecutor.java:129`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/BlockPathWalker.java:334`
- copy: Port closestPathPos() (PathExecutor.java:256-269) and possiblyOffPath() (PathExecutor.java:304-317) plus the two-tier reaction at PathExecutor.java:130-145: MAX_DIST_FROM_PATH=2 tolerated for up to MAX_TICKS_AWAY=200 ticks (constants at PathExecutor.java:51-61 — the comment at :54-61 records that below 110 ticks it misfires), and MAX_MAX_DIST_FROM_PATH=3 as an immediate cancel. Add it to tickBFS before the waypoint-advance block at BlockPathWalker.java:406, measuring against every cell of `path`, and make the cancel mean 'drop this leg and re-plan' (FastNavigator.planAhead) rather than 'stop'. The MovementFall exemption at PathExecutor.java:308-310 (ignore Y, use flat distance to the fall de

### [high] missing — Nothing re-validates the queued waypoints against the live world once a leg starts, so a block placed, a door closed, or lava spreading across the leg is only discovered by walking into it.

- baritone: `baritone/src/main/java/baritone/pathing/path/PathExecutor.java:207`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/BlockPathWalker.java:380`
- copy: Port the three per-tick checks from PathExecutor.java:199-219: (a) the lookahead loop at :199-205 that calls calculateCost on the next costVerificationLookahead=5 movements and cancels if any hit COST_INF (Settings.java:502, with the stated reason 'if lava has spread across the path, don't walk right up to it then recalculate'); (b) recalculateCost on the current movement >= COST_INF at :207-212; (c) the maxCostIncrease=10 delta test at :213-219 (Settings.java:495). In tungsten, run FastPlanner's own per-move validity predicates (PlayerFit.supportTop / the step generator's passability test) over path[waypointIdx .. waypointIdx+5] each tick in tickBFS and re-plan on failure. Note the asymmetr

### [high] re-derived — When the stall watchdog fires, FastNavigator stops itself but neither stops the walker nor triggers any re-plan — so the bot keeps pressing movement keys toward the failed leg with nobody steering, and no search is started.

- baritone: `baritone/src/main/java/baritone/behavior/PathingBehavior.java:154`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/FastNavigator.java:134`
- copy: Two concrete pieces. (1) Centralised cancel: copy PathExecutor.cancel() (PathExecutor.java:593-598) — clearAllKeys + stopBreakingBlock + mark failed — and have FastNavigator's watchdog path call BlockPathWalker.stop() the way the arrival path already does at FastNavigator.java:113-114 (FastNavigator.stop() at :95-104 touches none of the walker's keys, and BlockPathWalker.tick is invoked independently from MixinClientPlayerEntity.java:66, so the walker survives its navigator). (2) Closed-loop recovery: copy the failure branch of PathingBehavior.tickPath (PathingBehavior.java:154-195) — on failed()/finished() it checks the goal, discards `next` if it does not contain the current position (:165

### [high] missing — Plans are always seeded from the raw player block position, with no fallback when that cell has no floor (standing off a lip, or airborne), which the planner then rejects outright.

- baritone: `baritone/src/main/java/baritone/behavior/PathingBehavior.java:423`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/FastNavigator.java:285`
- copy: Port pathStart() wholesale (PathingBehavior.java:423-461) and use it at every FastNavigator.planAhead call site (FastNavigator.java:92, :173, :285) in place of player.getBlockPos(): when the feet cell has no walkable support, sort the 8 neighbours by horizontal distance, reject any with both xDist>0.8 and zDist>0.8 (:440), and return the first that is standable and passable (:444-447); when !onGround, return feet.down() if that has support (:454-457). Without it the airborne/edge case dead-ends in FastPlanner.plan at FastPlanner.java:358-374 — a floorless start node is `continue`d, the open set empties, plan() returns a 1-node stump, FastNavigator.java:310 discards it for size<2, and FastNav

### [medium] missing — waypointIdx only ever increments on proximity, so a teleport, lag-back, or knockback that moves the bot past or behind the current waypoint is never resynced — the bot turns around and walks back to a waypoint it has already passed.

- baritone: `baritone/src/main/java/baritone/pathing/path/PathExecutor.java:102`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/BlockPathWalker.java:409`
- copy: Port both resync loops from PathExecutor.java:102-128, keyed on 'the bot is not in the current step's valid positions'. Backward: scan i in [0, pathPosition) for a movement containing playerFeet, jump back and reset the intervening movements (:103-113, comment: 'this happens for example when you lag out and get teleported back a couple blocks'). Forward: scan i from pathPosition+3 to length-1 and skip ahead to i-1 (:115-127) — the +3 offset is deliberate, so the current step is still allowed to declare its own completion. In tungsten this is a scan over `path` for a cell within ~1 block of the player, setting waypointIdx accordingly, placed at the top of tickBFS.

### [medium] missing — No safe-to-cancel concept anywhere: every stop path releases all movement keys immediately, regardless of whether the bot is mid-jump, mid-fall or sneak-placing over a drop.

- baritone: `baritone/src/main/java/baritone/pathing/path/PathExecutor.java:194`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/BlockPathWalker.java:133`
- copy: Copy the canCancel gate: baritone computes it once per tick from movement.safeToCancel() (PathExecutor.java:194) and every discretionary cancel is conditioned on it (:200, :208, :213), while onTick's return value propagates it to PathingBehavior.isSafeToCancel() (PathingBehavior.java:311-316) which gates pause, splice and cancelEverything (:119, :197, :322-338). In tungsten, add a safeToCancel() predicate — false while !onGround, while a bounce/parkour manoeuvre owns the keys, and while placeQueue/breakQueue is mid-block — and check it in BlockPathWalker.stop() (BlockPathWalker.java:133-146, whose releaseKeys() at :566 cuts momentum mid-arc), in the FastNavigator watchdog (FastNavigator.java

### [medium] re-derived — Sprint is a two-line policy (always sprint unless stepping up) rather than a decision about what the next move is, so the walker sprints into descents and drops and overshoots the landing.

- baritone: `baritone/src/main/java/baritone/pathing/path/PathExecutor.java:345`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/BlockPathWalker.java:516`
- copy: Port the two lookahead predicates and the release: canSprintFromDescendInto() (PathExecutor.java:570-581 — only sprint out of a descend into a colinear descend/traverse, never into a turn) including the two-descends-ahead veto at :420-426, and sprintableAscend() (PathExecutor.java:530-568 — requires colinear direction across three moves, walkable floors, nothing to break, and head clearance at src.up(2)/up(3)). Also copy the explicit setSprinting(false) at PathExecutor.java:239-241 with its comment 'letting go of control doesn't make you stop sprinting actually'. These replace tungsten's measured-but-blunt substitutes: the `droppingTo = wp.y < feet.y - 2` heuristic (BlockPathWalker.java:540)

### [medium] re-derived — A physics path rooted ahead of the bot is accepted and held 'armed' for up to 15 s waiting for the bot to arrive, instead of being rejected as an orphan segment or parked as a proper next-segment.

- baritone: `baritone/src/main/java/baritone/behavior/PathingBehavior.java:509`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java:96`
- copy: Adopt baritone's two-slot model instead of arming. On calc completion it validates the segment against reality — executor.getPath().positions().contains(expectedSegmentStart) or it logs 'discarding orphan path segment with incorrect start' (PathingBehavior.java:511-517), and for a planned-ahead segment it requires getSrc().equals(current.getPath().getDest()) (:527-531) — then keeps it in `next` and only promotes it when current finishes (:176-183) or when the feet are already on it via snipsnapifpossible() (PathExecutor.java:324-343, whose :325-335 guard refuses to splice while falling or moving downward through water). Concretely: replace armTolerance()/armed (tungsten PathExecutor.java:78-

### [medium] missing — No pause when the route runs into unloaded terrain — the leg is walked or declared a dead end instead of waiting for chunks.

- baritone: `baritone/src/main/java/baritone/pathing/path/PathExecutor.java:186`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/FastNavigator.java:332`
- copy: Copy the chunk-edge pause at PathExecutor.java:186-193: if the NEXT step's destination is not in a loaded chunk, clearKeys() and return 'paused' — explicitly a pause, not a cancel, so the executor resumes when the chunk arrives. Tungsten already has VoxelWorld.isChunkLoaded (world/VoxelWorld.java:41) but neither FastPlanner (no chunk check anywhere in path/fast/FastPlanner.java) nor the walker consults it; beyond render distance every cell reads as floorless air, so plan() returns !complete, and the dead-end branch at FastNavigator.java:332-355 hands the whole goal to a physics search that cannot see the terrain either. Also worth copying: the simplifyUnloadedYCoord rewrite of a goal in an u


## Tungsten's live block planner (FastPlanner) re-derives baritone's special-terrain rules from collision shapes alone, and the derivation lost the parts that matter: a fall is capped by a bare constant (MAX_FALL=3) instead of budgeted, the one damage-aware guard tungsten does own is short-circuited by `ignoreFallDamage = true` by default, the water / water-bucket / ladder-catch exceptions that let baritone drop 20 blocks safely do not exist, and hazards (lava, fire, cobweb, magma, powder snow) are invisible to routing because PlayerFit judges a cell by its collision shape only. Direct answer to the question asked: tungsten DOES price fall DEPTH (a flat 1.0 per block, FastPlanner:801 / SmartMoves:104) but it never prices or checks fall DAMAGE in the planner that drives the bot — the damage code lives in the physics layer and is off by default. MovementHelper/MovementDescend/MovementDownward already solve every one of these, and the copies are small and local.  (15 findings)

> **STATUS, checked 2026-09-02 against current source (this section had NOT been reread since
> writing, unlike the three sections above it — checked with the same care regardless).** This
> section is a MIX, same as the move-set audit: some findings are stale because real work
> landed after the audit was written, most are not. Spot-checked, not exhaustive re-verification
> of all 15:
> - **STILL OPEN, exactly as described**: fall-damage budgeting (`MAX_FALL=3` at
>   `FastPlanner.java:50`, `ignoreFallDamage=true` at `TungstenModDataContainer.java:47` — both
>   confirmed present, unchanged). Fall-into-water-at-any-depth is also still open: the main
>   drop scan (`FastPlanner.java:961`, `for (int dy = CLIMB_MAX; dy >= -MAX_FALL; dy--)`) has no
>   water branch that lifts the `-MAX_FALL` floor, so a deep drop onto water is capped the same
>   as a deep drop onto land. Flowing water is also still merged with source water everywhere
>   (`isWater()` at `FastPlanner.java:932` calls `BlockStateChecker.isAnyWater`, which is
>   `isWater() || isFlowingWater()` — no call site in the planner asks which). Fall cost curve is
>   still the flat `FALL_ONE_BLOCK_COST = 1.0` (`ActionCosts.java:63`) — the sibling jump-cost
>   port (`JUMP_ONE_BLOCK_COST` via `distanceToTicks`/`velocity`, same file) already landed, the
>   fall-cost half of that same porting pass did not.
> - **CONFIRMED FIXED, and not a small thing to have missed**: water-bucket MLG exists.
>   `tungsten/.../path/movements/MovementFall.java` (456 lines) carries
>   `STACK_BUCKET_WATER`/`STACK_BUCKET_EMPTY`, `MAX_FALL_HEIGHT_BUCKET=20`, and the exact
>   select-water-bucket / aim / click / swap-back execution sequence this finding's own "copy"
>   column asks for — the audit's literal check ("grep for BUCKET in tungsten/ returns nothing")
>   no longer holds. `TODOS.md` already documents this move's live behaviour in detail (the MLG
>   task-starvation finding around line 5542) — this audit section just hadn't been told the move
>   exists at all, a bigger gap than the usual "already fixed, undocumented here."
> - **Hazard predicate**: already recorded fixed in the move-set-audit section above
>   (`hazardAt()` exists, called at the parkour/step-landing checks, `FastPlanner.java:1410`
>   and `:1422-1423`) — same finding, not rechecked twice.
> - **FIXED 2026-09-02** (commit `a359fd38`), was open when this banner was first written a few
>   minutes earlier in the same pass: the ladder branch's bogus climb into open air.
>   `FastPlanner.java:641-654` used to offer a climb step whenever
>   `isLadder(...) || PlayerFit.bodyFits(...)` — the `bodyFits` fallback had no
>   still-a-ladder-or-real-floor precondition, exactly the shape this finding names. Now
>   continuing along the column requires the next cell to still be a ladder; landing at the
>   bottom (dy=-1) additionally accepts a real floor there. Getting OFF at the top already had
>   its own fix, a genuinely new piece of code right below (:655-663+, "GETTING OFF THE
>   LADDER" — a cardinal step-off exit) that closed the *other* half of the baritone gap this
>   finding also names (a ladder used to be a one-way trip). Like the rest of this document's
>   fixes marked here, **read-and-reasoned, not stand-verified** — no stand access in this room
>   right now (C8.1).
> - **Not rechecked this pass**: the remaining ~8 findings in this section (parkour/step landing
>   hazard coverage beyond what's cited above, vine-as-ladder conflation, and others not spot-
>   checked). Do not read the four bullets above as a verdict on the whole 15.



### [high] re-derived — Fall-damage budgeting: tungsten's only damage-aware guard is a hard reject at >2.75 blocks that is short-circuited by ignoreFallDamage=true (TungstenModDataContainer:20), so the physics search will route an arbitrarily deep drop; the block planner substitutes the bare constant MAX_FALL=3 (FastPlanner:50).

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementDescend.java:205`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/PathFinder.java:178`
- copy: Port dynamicFallCost's per-candidate budget `reachedMinimum && unprotectedFallHeight <= context.maxFallHeightNoWater + 1` into FastPlanner.step's drop branch (FastPlanner:758) with maxFallHeightNoWater as a TungstenConfig setting instead of the private MAX_FALL constant, and flip TungstenModDataContainer.ignoreFallDamage to false so PathFinder.checkForFallDamage actually runs.

### [high] missing — A fall into water is unplannable at any depth: FastPlanner's only land->water entry looks at from.y and from.y-1, and step()'s drop scan needs PlayerFit.supportTop != NaN, which water never gives (empty collision shape).

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementDescend.java:159`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:546`
- copy: Copy dynamicFallCost's water branch verbatim into FastPlanner.step's dy scan: if the scanned cell isWater and !isFlowing and canWalkOn(newY-1) then accept the drop at ANY depth (no MAX_FALL cap) — baritone deliberately has no height limit on that branch.

### [high] re-derived — Hazard/trap terrain is invisible to the live planner: PlayerFit.classifyUncached judges a cell by getCollisionShape only, so lava, fire, cobweb, sweet-berry and powder snow classify as air and magma classifies as a good floor; tungsten's own hazard table is consulted only by the combat pathfinder (CombatPathfinder:354/367) and by BlockPathWalker's DIRECT mode at the bot's own feet (BlockPathWalker:258).

- baritone: `baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:420`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/helpers/PlayerFit.java:128`
- copy: Gate FastPlanner.relax (and the step/parkour landing tests) on baritone's avoidWalkingInto set — fluids, MAGMA_BLOCK, CACTUS, SWEET_BERRY_BUSH, AbstractFireBlock, COBWEB, BUBBLE_COLUMN — plus canWalkThroughBlockState's POWDER_SNOW=NO (MovementHelper.java:193) and canWalkOnBlockState's MAGMA/BUBBLE_COLUMN/HONEY_BLOCK exclusion (MovementHelper.java:458). Cheapest route: call the existing CombatPathfinder.isHazardOrSlow from FastPlanner.step.

### [medium] re-derived — Fall cost curve: tungsten charges a flat FALL_ONE_BLOCK_COST=1.0 per block of drop (linear, ~3 ticks for a 3-block fall against a real ~8.5), so the search treats dropping as nearly free versus walking (4.633/block) and prefers ledges over ground routes; it also charges a full WALK for the step off the edge.

- baritone: `baritone/src/main/java/baritone/api/pathing/movement/ActionCosts.java:48`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/calculators/ActionCosts.java:18`
- copy: Copy generateFallNBlocksCost / distanceToTicks / velocity (ActionCosts.java:48-97) into tungsten's ActionCosts and replace `FALL_ONE_BLOCK_COST * -rise` at FastPlanner:801 and `drop * FALL_ONE_BLOCK_COST` at SmartMoves:104 with FALL_N_BLOCKS_COST[drop]; add MovementDescend.java:129's `WALK_OFF_BLOCK_COST + Math.max(FALL_N_BLOCKS_COST[1], CENTER_AFTER_FALL_COST)` terms (ActionCosts.java:36 and :40) for the walk-off-and-recentre part.

### [medium] missing — No water-bucket MLG anywhere in tungsten (grep for BUCKET in tungsten/ returns nothing), so a drop between 4 and 20 blocks is simply refused where baritone plans it, prices it and executes the clutch.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementFall.java:99`
- tungsten: `MISSING`
- copy: CalculationContext.hasWaterBucket (CalculationContext.java:107) + placeBucketCost (CalculationContext.java:215), the dynamicFallCost branch `hasWaterBucket && unprotectedFallHeight <= maxFallHeightBucket + 1` (MovementDescend.java:213), and MovementFall.updateState's execution sequence: select the water bucket, aim pitch 90 at dest, CLICK_RIGHT when isLookingAt(dest), then swap to the empty bucket and pick the water back up while velocity.y >= 0.

### [medium] missing — The ladder branch emits a climb up or down whenever the target cell merely FITS the body, so it plans a rung-step into open air above the top rung / below the bottom rung; that bogus node can also become the `best` node whose chain is returned as the partial plan, leaving a waypoint in mid-air.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementDownward.java:61`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:461`
- copy: MovementDownward.cost's precondition `if (!MovementHelper.canWalkOn(context, x, y - 2, z)) return COST_INF` — in FastPlanner's `for (int dy : {1,-1})` loop require the target to still be a ladder cell, or (for dy=-1) that supportTop of the cell below is a real surface; the bare bodyFits fallback is what admits air.

### [medium] missing — Flowing water is merged with source water everywhere in the planner (isAnyWater), so a river's current is invisible to routing: a flowing cell is offered as a swim cell and (once fall-into-water exists) as a landing, where baritone refuses both.

- baritone: `baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:774`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/helpers/BlockStateChecker.java:225`
- copy: Port isFlowing(x,y,z,state,bsi) and possiblyFlowing (MovementHelper.java:768) — including the neighbour scan that makes a level-8 source next to a lower-level neighbour count as flowing — and use it in FastPlanner.isWater's consumers (FastPlanner:500 swim branch, :546 entry branch) to refuse flowing cells as landings and price them above still water.

### [medium] missing — No descend-in-place / dig-down move: breakThrough is cardinal-only (`if (dx != 0 && dz != 0) return`, no dy variant) and step() only visits horizontal neighbours, so a goal below the bot behind its own floor is unreachable even with a pickaxe.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementDownward.java:70`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:823`
- copy: Add a downward twin of breakThrough that plans toBreak=[(x,y-1,z)] and relaxes to (x,y-1,z), priced as MovementDownward.cost does: FALL_N_BLOCKS_COST[1] + the block's break ticks, with the ladder/vine short-circuit to LADDER_DOWN_ONE_COST (MovementDownward.java:67) and the canWalkOn(x,y-2,z) guard.

### [medium] missing — Nothing brakes an overshooting fall: baritone sneaks while airborne and off-centre to land in the destination cell, while tungsten's physics search prices any sneak at +2000 so the manoeuvre can never be generated — the walker fights the same overshoot with a throttle cut instead (BlockPathWalker:498).

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementFall.java:139`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/Node.java:354`
- copy: MovementFall.updateState's air-brake: when off-centre by >0.1 in x or z and |velocity.y| > 0.4 and !onGround, hold SNEAK (plus the 0.125*avoid offset aim at MovementFall.java:156). Requires exempting airborne nodes from Node.calculateNodeCost's flat +2000 sneak penalty, otherwise the move stays unreachable.

### [medium] re-derived — Tungsten already contains a partial copy of baritone's canWalkOnBlockState table (ladder/vine YES, magma/bubble/honey excluded) but NOTHING calls it — grep finds only the declaration — so the live planner re-derives standability from raw collision shapes, which is the root cause of the hazard and slab findings above.

- baritone: `baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:458`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/helpers/MovementHelper.java:29`
- copy: Finish the port — add the water/lava MAYBE arms and canWalkOnPosition (MovementHelper.java:507, incl. the lily-pad/carpet-above and `isWater(upState) ^ assumeWalkOnWater` cases) — and then actually consult it from FastPlanner.step and PlayerFit.standable instead of PlayerFit.supportTop alone.

### [low] missing — A ladder or vine column does not arrest a fall: the drop scan stops at the first standable surface within MAX_FALL and never treats a climbable as a mid-fall reset, so a ladder shaft deeper than 3 blocks is not a route down.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementDescend.java:188`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/fast/FastPlanner.java:758`
- copy: dynamicFallCost's catch clause: while scanning down, if unprotectedFallHeight <= 11 and the cell is LADDER/VINE, add FALL_N_BLOCKS_COST[h-1] + LADDER_DOWN_ONE_COST to costSoFar, set effectiveStartHeight = newY and CONTINUE the scan — that single bookkeeping trick is what makes deep shafts plannable, and it generalises to slime pads too.

### [low] missing — A drop can be aimed onto a bottom slab: PlayerFit.supportTop reports a bottom slab as a perfectly good floor at its own top, and nothing rejects it as a landing, where baritone refuses it because the landing is glitchy and takes more damage than the height predicts.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementDescend.java:202`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/helpers/PlayerFit.java:180`
- copy: `if (MovementHelper.isBottomSlab(ontoBlock)) return false;` — reject drops of more than one block whose landing surface is a bottom slab, in FastPlanner.step's dy scan; the predicate already exists as BlockStateChecker.isBottomSlab (BlockStateChecker.java:205).

### [low] ported — Ladder execution presses INTO the block the ladder hangs on (FACING.getOpposite) and advances on vertical arrival — the same mechanism baritone uses, correctly ported, including never jumping to descend.

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementPillar.java:205`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/task/BlockPathWalker.java:436`
- copy: nothing

### [low] ported — Swim cost: SWIM_ONE_BLOCK_COST = WALK*2.0 = 9.27 matches baritone's measured WALK_ONE_IN_WATER_COST = 20/2.2 = 9.091, so the block planner's water pricing is effectively the upstream number.

- baritone: `baritone/src/main/java/baritone/api/pathing/movement/ActionCosts.java:26`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/calculators/ActionCosts.java:21`
- copy: nothing

### [low] divergent-on-purpose — Fall EXECUTION is deliberately different: baritone hand-steers the fall in MovementFall.updateState because it has no simulator, whereas tungsten simulates the descent tick-by-tick in the airborne node loop with the real Agent physics — the code states this split (FastPlanner javadoc lines 32-40, 'hand exactly those segments to the physics engine').

- baritone: `baritone/src/main/java/baritone/pathing/movement/movements/MovementFall.java:87`
- tungsten: `tungsten/src/main/java/kaptainwutax/tungsten/path/Node.java:391`
- copy: nothing

## CRITICAL, found by the user on 2026-07-30: tungsten was placing blocks by FORGING the interaction

Three sites — `PathExecutor.tickPlacing`, `BridgeTask`, `PillarTask` — built their own
`BlockHitResult` from a face centre and handed it to `interactionManager.interactBlock`. That
is not a placement, it is a forged packet: it claims the player clicked a face the player was
never looking at. Blocks appeared through block edges with the camera pointing elsewhere.
`PillarTask` did not even aim first. One of the sites carried a comment of mine calling the
camera "cosmetic here" — it is not cosmetic, it IS the interaction.

Fixed: `helpers/RealPlacement.java` ports baritone's gate — aim at the face, then accept only
when the player's own crosshair lands somewhere that would produce the wanted block
(`hit.getBlockPos().offset(hit.getSide()).equals(placeAt)`), and place with THAT hit result.
Also ports `canPlaceAgainst` (normal cubes and glass, not "any non-empty collision shape").

### What this exposed, and what still has to be ported

With the forgery gone the suite got WORSE, and that is the honest state: `nav_bridge` fails at
11.6 blocks, twice, because **the bridging MANOEUVRE was never ported — only the click was.**

Two things were tried and measured before understanding this:

1. **Sole aim ownership.** The walker re-aimed at its waypoint every tick underneath the
   placer. Baritone has exactly one thing steering. Ported — and it did NOT fix it (still
   11.6). Worth keeping regardless (it was previously judged "measured neutral", a worthless
   verdict taken while placement ignored the camera entirely).
2. **Aiming at the target's face from on top of the block.** Geometrically impossible: from
   ON TOP of block B, a ray towards B's side face hits B's TOP face first. No amount of aiming
   fixes it.

The actual manoeuvre, from `MovementTraverse.updateState`
(baritone/.../movements/MovementTraverse.java:336-350):

```java
BlockPos goalLook = src.down();   // the block we were JUST STANDING ON
Rotation backToFace = calcRotationFromVec3d(playerHead(), faceCentre(src.down(), dest.down()));
double dist2 = max(|player.x - faceX|, |player.z - faceZ|);
if (dist2 < 0.29) { ...MOVE_BACK... }        // too close to see the face — back off first
if (ctx.isLookingAt(goalLook)) CLICK_RIGHT;  // only then
```

So it is: **sneak forward INTO the empty cell** (sneaking is what stops the fall), turn round,
look down and BACK at the face of the block you came from, press MOVE_BACK if you are too
close to see it, and click only when the crosshair is genuinely on that block. The new block
appears beneath you. Tungsten instead stands ON the block and looks forward-down at the
destination, which cannot work.

Next pass ports that manoeuvre — sneak-into-the-cell, look back, MOVE_BACK separation — not
another variation on aiming from where the bot already stands.

⛔ THAT NEXT PASS HAPPENED, CONFIRMED 2026-09-02 — this section is otherwise accurate but stops
one step short of the current state. `MovementTraverse.java` (`tungsten/path/movements/`, not
`PathExecutor`/`BridgeTask`/`PillarTask` — see `docs/BARITONE-PORT-SPEC.md`'s Units 1-3) carries
exactly the manoeuvre described above: `wasTheBridgeBlockAlwaysThere` gates the sprint (`:153,
390,410`), `MOVE_BACK` is set in the backplace branch (`:419,476`), and the swapped-argument
`calcRotationFromVec3d(dest, playerHead, ...)` — facing back up the bridge, not forward down at
it — appears exactly where it must (`:474`), confirmed against every other call site in the same
file correctly using the opposite order. Checked line-by-line this session, not assumed from the
port having looked planned. `RealPlacement`/`canPlaceAgainst` from earlier in this section are
also both live and used by all three original forgery sites plus `Py4jEntryPoint`'s build
surface (`grep` finds zero remaining `new BlockHitResult` at any of the three). See `TODOS.md`
C5.8, closed 2026-07-31, which already carries this in full detail — this section just hadn't
been told. Kept as historical record of what the forgery bug was and why the manoeuvre matters,
not as an open task.

### Telemetry after porting the manoeuvre — still 0 clicks, and the reason is a vanilla rule

`placeStats` mid-run on nav_bridge: **`called=11041 deferred=1 inRange=11040 clicked=0`**. The
placer is in range for eleven thousand ticks and the crosshair test never passes ONCE. So the
bot never reaches a position from which the face is visible.

The blocker is a vanilla mechanic working against the port: **sneaking prevents you from
walking off a ledge.** The manoeuvre as written sneaks and presses MOVE_BACK to carry the body
over the empty cell — and sneaking is exactly what stops that from happening. Baritone does not
have this problem because the BOT IS ALREADY IN `dest` when `updateState` runs the placement:
the traverse movement owns both the step and the place, and the step happens first. In tungsten
the walker owns the step and refuses to walk into a floorless cell, while the executor owns the
place — so nobody ever puts the body where the face can be seen.

That is the seam again, in its sharpest form yet. The fix is structural: whatever performs a
bridge step must own BOTH the step into the empty cell and the placement, the way
MovementTraverse does. Splitting them across the walker and the executor cannot work, no matter
how faithfully each half is ported.

Current honest state: placement no longer forges interactions (correct), and nav_bridge is red
because of it (11.6 blocks, 3 of 3). nav_wall2 still passes 2/2 — pillaring reaches its face
from where it already stands, so it never needed the manoeuvre.

### Three attempts, none moved the number — where the next session must start

All measured on nav_bridge, all still `clicked=0`:

1. sole ownership of the AIM while the placer is in range — 11.6, unchanged;
2. the full backplace manoeuvre (face back, look down at the face, MOVE_BACK, sneak) — 11.6;
3. sole ownership of the BODY too, and then not wiping the placer's keys on yield — still
   `called=336 inRange=336 clicked=0`.

So the crosshair never lands on the face, and it is not key contention and not aim contention.
The remaining candidates, in the order worth testing:

- **Is the body actually creeping past the lip?** Sneaking permits the centre to reach
  `edge + 0.3` (the box's rear 0.3 keeps support), and that is the ONLY position from which a
  block's side face is visible — from anywhere further back the ray crosses the block's top
  plane while still inside its footprint. Log the player's x/z against the lip, per tick. If it
  never passes the edge, the manoeuvre is being blocked by something else pressing keys.
- **Is the pitch reaching ~-79 degrees?** That is roughly what the geometry needs. WindMouse
  smooths, so log the achieved pitch, not the requested one.
- **What IS the crosshair hitting?** Log `mc.crosshairTarget`'s block and side each tick while
  in range. If it is the TOP face of the block below, the body has not passed the lip. This is
  one line and it settles the question — it should have been the first thing logged.

The structural conclusion stands regardless: MovementTraverse owns the step AND the place, and
tungsten splits them. Until one component owns both, each faithful half-port will keep failing
for a different reason.

### The crosshair question, answered — and the next one, also answered

Logged what the crosshair was actually hitting, which is what should have happened first:

```
PLACEAIM want=13,-61 against=12,-61 side=east pos=(11.43,0.62) pitch=53/53
         hit=12,-61 side=up -> would fill 12,-60
```

The bot was standing at **x=11.43** — a block and a half short of the lip block (x=12), aiming
at its TOP face — because the placer took the BODY as soon as it was within 5.5 blocks and
froze it there. Upstream never has this because walking is a separate movement that finishes
first; the placement runs when the bot is already in the cell it places from.

Fixed: the placer now takes the body only when the feet cell is directly above the block it
will click. And that immediately answered the next question too:

```
PLACEWAIT want=13,-61 against=12,-61 feet=11,-60 pos=(11.44,0.50) onGround=true
```

**The walker leaves the bot 0.56 blocks short of the lip block and stops.** The placer is no
longer interfering — `placingNow` is false in this state, so the walker is free — and the bot
still does not step onto (12,-60). That is where the next session starts, and it is a walker /
navigator question, not a placement one:

- is the walker even ticking here, or did its leg end and the navigator not start another
  (its leg-start needs `!BlockPathWalker.isRunning()`, and the stall watchdog counts a live
  place queue as progress, so it will neither stop nor advance);
- does the leg contain (12,-60) at all, or did the waypoint advance already consume it and
  leave the walker steering at (13,-60), the floorless cell;
- and the geometry to aim for once it does arrive: sneaking permits the centre to reach
  ~13.29 (the box is 0.6 wide, so support holds while `x - 0.3 < 13`), and from there the
  face IS visible at a pitch of about 82 degrees. From the block's centre it is not, at any
  pitch — the ray crosses the top plane while still inside the footprint. Verified by
  construction, not by guess.

### And one level deeper: the leg is cut and handed to physics on every single leg

Counted over one nav_bridge run:

| signal | count |
|---|---|
| `Walker: BFS` (legs started) | 12 |
| `HANDOFF` (leg ended at a flagged waypoint) | 12 |
| `walking dead-ends` | 1 |
| `WALKSTOP` (on the ground with movement not pressed) | **0** |

`WALKSTOP = 0` is the informative one: the walker is never standing still with movement
released. It simply is not running — every leg it starts ends at a flagged waypoint, the
navigator hands the rest to the physics engine, physics cannot solve it, and the cycle repeats
twelve times. That is why the bot is left 0.56 blocks short of the lip: nobody is walking it.

So the chain, all measured, none of it guessed:

1. placements were forged → fixed, and that exposed
2. the placer seizing the body 5.5 blocks early → fixed, and that exposed
3. the leg being cut and handed to physics on every leg → **open**.

Note that (3) is the same family as an earlier recorded dead end: skipping `toPlace` waypoints
in `firstPhysicsIndex` regressed nav_wall2 and nav_bridge, because the ledge courses pass
BECAUSE the cut routes their climb to PillarTask. So the fix is not to change the cut again —
it is the structural one this file has been pointing at from the start: **one component must
own a bridge step's walk AND its placement**, the way MovementTraverse does. Every attempt to
keep them split has now failed at a different seam, which is about as clear a signal as a
codebase can give.

### The root cause, and why the obvious owner still did not work

`placeAcross` emits its planks **flagged `viaJump`**. So the leg is cut at the first plank and
everything after it is handed to the physics search — the engine with **no place move at all**.
That is the whole reason the bridge died the moment placement became honest: previously the
place plan reached the executor THROUGH that physics path and the forged hit result did not
care where the body was.

Wiring the run to `BridgeTask` instead — the one component that owns the step AND the
placement, exactly as `PillarTask` owns a tower — was the right idea and it MEASURED WORSE:
BridgeTask did start (twice a run), and nav_bridge went from 11.6 standing still to **22.5,
three of three, the void-fall signature**. Sneaking when there is no floor ahead (kept inside
BridgeTask, since the godbridge model is broken without it now) did not save it.

The reason is timing, and it is the last unknown turned into a known: **by the time the
hand-off happens the walker has already delivered the bot off the lip.** The owner has to take
the manoeuvre from BEFORE the lip, not at it. That means changing WHERE the leg is cut — which
this file already records as a dead end for a different reason (the ledge courses pass because
the cut routes their climb to PillarTask). Both cannot hold, so the next session's job is
narrow and clear: **the cut has to become per-move-kind rather than one flag.** A waypoint that
places is not "physics"; it needs its own owner, taken over BEFORE the bot reaches the lip,
while the climb keeps going to PillarTask exactly as it does now.

Reverted rather than kept, because falling is a strictly worse failure than standing.
nav_bridge is back to 11.6 and nav_wall2 verified still PASS (0.6).
