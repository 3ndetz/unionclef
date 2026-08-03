package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.InputControls;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.TungstenHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.*;
import net.minecraft.util.math.BlockPos;

/**
 * Turns a baritone goal into a task.
 */
public abstract class CustomBaritoneGoalTask extends Task implements ITaskRequiresGrounded {

    /** Entry and early-exit tallies for the tungsten branch; read over py4j in placeStats(). */
    public static volatile int pdEnter, pdNotPrimary, pdPillar, pdBridge, pdStuckGiveUp,
            pdWalking, pdNear, pdNoGoal, pdFinished, pdNoVec, pdStallWalker, pdStallReset, pdNearBusy, pdNearFind;
    /** When the near-goal branch last issued a search; see the rate gate at its site. */
    private long twLastNearFindMs = 0L;

    private final Task wanderTask = new TimeoutWanderTask(5, true);
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final boolean wander;
    protected MovementProgressChecker checker = new MovementProgressChecker();
    protected Goal cachedGoal = null;
    // Anti-permanent-stuck (tungsten-primary): if the bot hasn't moved for a while,
    // the tungsten nav is trapped (unreachable sub-goal / stale-rooted reject loop) —
    // reset its state so it re-plans fresh, then yield to wander if it stays stuck.
    private net.minecraft.util.math.Vec3d twStuckPos = null;
    private long twStuckSinceMs = 0L;
    private int twStuckResets = 0;
    // The walker can't parkour (gap jumps / wall climbs). When it stalls we hand the
    // segment to the physics executor (which can) for a window, then re-try the walker.
    private long twPreferExecutorUntilMs = 0L;
    // Net-progress-toward-goal tracking, to give up on genuinely UNREACHABLE goals
    // (e.g. a tree top needing place/break we don't plan yet) instead of searching
    // forever. Keyed on distance to goal, not raw movement — a bot wandering in place
    // near an unreachable goal makes no NET progress even though it "moves". #27.
    private double twBestDistToGoal = -1;
    private long twBestImproveMs = 0L;
    Block[] annoyingBlocks = new Block[]{
            Blocks.VINE,
            Blocks.NETHER_SPROUTS,
            Blocks.CAVE_VINES,
            Blocks.CAVE_VINES_PLANT,
            Blocks.TWISTING_VINES,
            Blocks.TWISTING_VINES_PLANT,
            Blocks.WEEPING_VINES_PLANT,
            Blocks.LADDER,
            Blocks.BIG_DRIPLEAF,
            Blocks.BIG_DRIPLEAF_STEM,
            Blocks.SMALL_DRIPLEAF,
            Blocks.TALL_GRASS,
            Blocks.SHORT_GRASS,
            Blocks.SWEET_BERRY_BUSH
    };
    private Task unstuckTask = null;

    // This happens all the time in mineshafts and swamps/jungles

    public CustomBaritoneGoalTask(boolean wander) {
        this.wander = wander;
    }

    public CustomBaritoneGoalTask() {
        this(true);
    }

    private static BlockPos[] generateSides(BlockPos pos) {
        return new BlockPos[]{
                pos.add(1,0,0),
                pos.add(-1,0,0),
                pos.add(0,0,1),
                pos.add(0,0,-1),
                pos.add(1,0,-1),
                pos.add(1,0,1),
                pos.add(-1,0,-1),
                pos.add(-1,0,1)
        };
    }

    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        for (Block annoyingBlock : annoyingBlocks) {
            if (block == annoyingBlock) return true;
        }
        return block instanceof DoorBlock ||
                block instanceof FenceBlock ||
                block instanceof FenceGateBlock ||
                block instanceof FlowerBlock;
    }

    private BlockPos stuckInBlock(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        if (isAnnoying(mod, p)) return p;
        if (isAnnoying(mod, p.up())) return p.up();
        BlockPos[] toCheck = generateSides(p);
        for (BlockPos check : toCheck) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        BlockPos[] toCheckHigh = generateSides(p.up());
        for (BlockPos check : toCheckHigh) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        return null;
    }

    private Task getFenceUnstuckTask() {
        return new SafeRandomShimmyTask();
    }

    @Override
    protected void onStart() {
        AltoClef.getInstance().getClientBaritone().getPathingBehavior().forceCancel();
        TungstenHelper.reset();
        checker.reset();
        stuckCheck.reset();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        InputControls controls = mod.getInputControls();
        
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            checker.reset();
        }
        if (WorldHelper.isInNetherPortal()) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                setDebugState("Getting out from nether portal");
                controls.hold(Input.SNEAK);
                controls.hold(Input.MOVE_FORWARD);
                return null;
            } else {
                controls.release(Input.SNEAK);
                controls.release(Input.MOVE_BACK);
                controls.release(Input.MOVE_FORWARD);
            }
        } else {
            if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
                controls.release(Input.SNEAK);
                controls.release(Input.MOVE_BACK);
                controls.release(Input.MOVE_FORWARD);
            }
        }
        if (unstuckTask != null && unstuckTask.isActive() && !unstuckTask.isFinished() && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return unstuckTask;
        }
        if (!checker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                unstuckTask = getFenceUnstuckTask();
                return unstuckTask;
            }
            // Not in annoying block — force baritone to recompute, so wander fallback can fire
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            stuckCheck.reset();
        }
        if (cachedGoal == null) {
            cachedGoal = newGoal(mod);
        }

        // ── Tungsten-PRIMARY (drop-in swap, TODO 13) ──
        if (driveTungstenPrimary(mod)) return null;

        // ── Tungsten lock: exclusive 30s control, Baritone stays off ──
        if (TungstenHelper.isLocked()) {
            TungstenHelper.tickLock();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            checker.reset();
            long remaining = Math.max(0, (TungstenHelper.lockUntilMs() - System.currentTimeMillis()) / 1000);
            setDebugState("Tungsten pathfinding (" + remaining + "s left)");
            return null;
        }

        // If Tungsten is actively pathfinding (outside lock), let it finish
        if (TungstenHelper.isActive()) {
            checker.reset();
            setDebugState("Tungsten fallback pathfinding...");
            return null;
        }

        if (wander) {
            if (isFinished()) {
                // Don't wander if we've reached our goal.
                checker.reset();
                TungstenHelper.stop();
            } else {
                if (wanderTask.isActive() && !wanderTask.isFinished()) {
                    setDebugState("Wandering...");
                    checker.reset();
                    return wanderTask;
                }
                if (!checker.check(mod)) {
                    // Baritone failed — try Tungsten before wandering
                    if (cachedGoal != null) {
                        var player = mod.getPlayer();
                        var goalPos = new net.minecraft.util.math.Vec3d(
                                player.getX(), player.getY(), player.getZ());
                        // Try to extract goal position from cachedGoal for Tungsten
                        if (cachedGoal instanceof baritone.api.pathing.goals.GoalBlock gb) {
                            goalPos = new net.minecraft.util.math.Vec3d(gb.x, gb.y, gb.z);
                        } else if (cachedGoal instanceof baritone.api.pathing.goals.GoalGetToBlock gg) {
                            goalPos = new net.minecraft.util.math.Vec3d(gg.x, gg.y, gg.z);
                        }
                        if (TungstenHelper.tryPathTo(goalPos)) {
                            mod.getClientBaritone().getPathingBehavior().forceCancel();
                            setDebugState("Baritone stuck, trying Tungsten...");
                            return null;
                        }
                    }
                    Debug.logMessage("Failed to make progress on goal, wandering.");
                    onWander(mod);
                    return wanderTask;
                }
            }
        }
        if (!TungstenHelper.isActive()
                && !mod.getClientBaritone().getCustomGoalProcess().isActive()
                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(cachedGoal);
        }
        setDebugState("Completing goal.");
        return null;
    }

    @Override
    public boolean isFinished() {
        if (cachedGoal == null) {
            cachedGoal = newGoal(AltoClef.getInstance());
        }
        return cachedGoal != null && cachedGoal.isInGoal(AltoClef.getInstance().getPlayer().getBlockPos());
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef.getInstance().getClientBaritone().getPathingBehavior().forceCancel();
        TungstenHelper.stop();
    }

    protected abstract Goal newGoal(AltoClef mod);

    protected void onWander(AltoClef mod) {
    }

    /** Equip a throwaway building block for pillaring (#46). True if a BlockItem is
     *  (now) in hand. Tries common cheap blocks the bot carries. Agent-provided
     *  blocks in hand already count — this is the mod's autonomous fallback. */
    private static final net.minecraft.item.Item[] BUILD_BLOCKS = {
        net.minecraft.item.Items.COBBLESTONE, net.minecraft.item.Items.DIRT,
        net.minecraft.item.Items.STONE, net.minecraft.item.Items.NETHERRACK,
        net.minecraft.item.Items.COBBLED_DEEPSLATE, net.minecraft.item.Items.OAK_PLANKS,
        net.minecraft.item.Items.DEEPSLATE, net.minecraft.item.Items.ANDESITE
    };

    private boolean equipBuildBlock(AltoClef mod) {
        if (mod.getPlayer().getMainHandStack().getItem() instanceof net.minecraft.item.BlockItem) return true;
        for (net.minecraft.item.Item b : BUILD_BLOCKS) {
            if (mod.getItemStorage().hasItemInventoryOnly(b)) {
                mod.getSlotHandler().forceEquipItem(b);
                return true;
            }
        }
        return false;
    }

    /** The bot has a placeable block (does NOT equip). Gates the pathfinder's plan-bridging
     *  so parkour/walk routing without blocks is unaffected. */
    private boolean hasBuildBlock(AltoClef mod) {
        if (mod.getPlayer().getMainHandStack().getItem() instanceof net.minecraft.item.BlockItem) return true;
        for (net.minecraft.item.Item b : BUILD_BLOCKS)
            if (mod.getItemStorage().hasItemInventoryOnly(b)) return true;
        return false;
    }

    /** True when the bot is stuck at the edge of a GAP (a real drop) in the goal's
     *  horizontal direction — a "bridge here" signal, distinct from a wall (cell
     *  ahead solid) or a step-down. Cell ahead toward the goal must be clear (not a
     *  wall) with no floor for 2+ blocks below (a genuine gap a jump can't close). */
    private boolean gapTowardGoal(AltoClef mod, net.minecraft.util.math.Vec3d gp) {
        var p = mod.getPlayer();
        var world = mod.getWorld();
        double dx = gp.x - p.getX(), dz = gp.z - p.getZ();
        net.minecraft.util.math.Direction dir = Math.abs(dx) >= Math.abs(dz)
                ? (dx >= 0 ? net.minecraft.util.math.Direction.EAST : net.minecraft.util.math.Direction.WEST)
                : (dz >= 0 ? net.minecraft.util.math.Direction.SOUTH : net.minecraft.util.math.Direction.NORTH);
        net.minecraft.util.math.BlockPos ahead = p.getBlockPos().offset(dir);
        boolean aheadClear = world.getBlockState(ahead).getCollisionShape(world, ahead).isEmpty();
        boolean noFloor1 = world.getBlockState(ahead.down()).getCollisionShape(world, ahead.down()).isEmpty();
        boolean noFloor2 = world.getBlockState(ahead.down(2)).getCollisionShape(world, ahead.down(2)).isEmpty();
        return aheadClear && noFloor1 && noFloor2;
    }


    /** Drop-in swap (TODO 13): when tungsten is PRIMARY, drive movement via
     *  tungsten directly (the same call ;goto uses — baritone movement doesn't
     *  execute on headless clients). Async: PATHFINDER.find kicks a background
     *  search, so this never blocks. Returns true if it took control (caller
     *  should return null to keep baritone off). Subclasses that override
     *  onTick (e.g. GetToBlockTask's wander) MUST call this BEFORE their own
     *  stuck/wander logic, or the wander loop starves the swap. */
    protected boolean driveTungstenPrimary(AltoClef mod) {
        // WHERE DOES THIS METHOD ACTUALLY LEAVE? Three passes guessed at the reason the bot
        // stands still and all three were refuted; every place a counter already existed, the
        // answer came on the first run. So count the entry and each early exit.
        pdEnter++;
        if (!TungstenHelper.isPrimary()) { pdNotPrimary++; return false; }
        if (cachedGoal == null) cachedGoal = newGoal(mod);
        if (cachedGoal == null) { pdNoGoal++; return false; }
        if (isFinished()) { pdFinished++; return false; }
        net.minecraft.util.math.Vec3d gp = goalToVec(cachedGoal, mod);
        if (gp == null) { pdNoVec++; return false; }
        // NOTE: @goto keeps planPlaceMoves at its default (off) — the proactive @goto bridge
        // needs a walker↔executor hand-off that isn't wired yet (the walker takes a gap stub
        // and the executor's bridge never runs). @goto still bridges REACTIVELY (v0.41 give-up).
        // The core place-as-a-move bridge is exposed as an agent primitive via ;goto +
        // setTungstenPlanPlaceMoves (agent decides when to bridge). hasBuildBlock() stays for
        // when that hand-off lands.

        // ── Anti-permanent-stuck safety net ──────────────────────────────
        long nowMs = System.currentTimeMillis();
        net.minecraft.util.math.Vec3d plNow = new net.minecraft.util.math.Vec3d(
                mod.getPlayer().getX(), mod.getPlayer().getY(), mod.getPlayer().getZ());

        // ── Unreachable-goal give-up (net progress toward the goal) ────────
        // If the closest we've gotten to the goal hasn't improved for a while, the goal
        // is unreachable under the current move set (we can't place/pillar/bridge yet).
        // Give up: stop tungsten and yield WITHOUT resetting the parent progress checker,
        // so the task can fail cleanly instead of the pathfinder spinning forever. #27.
        double distToGoalNow = plNow.distanceTo(gp);
        if (twBestDistToGoal < 0 || distToGoalNow < twBestDistToGoal - 0.5) {
            twBestDistToGoal = distToGoalNow;
            twBestImproveMs = nowMs;
        } else if (twBestImproveMs > 0 && nowMs - twBestImproveMs > 14000 && distToGoalNow > 2.0) {
            kaptainwutax.tungsten.task.BlockPathWalker.stop();
            var pfU = kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER;
            var exU = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            if (pfU != null) pfU.stop.set(true);
            if (exU != null) exU.stop = true;
            // #46 place-as-a-move: if the goal is directly above us and we have a block,
            // PILLAR up to it instead of abandoning — the real fix for raised place-only
            // goals (tree top / ledge) that walking or jumping can't reach.
            // Only a CLEAR vertical reach (goal well above + nearly overhead) — not a
            // transient stall near the top of a staircase where the goal is ~1 up.
            double horizToGoal = Math.hypot(plNow.x - gp.x, plNow.z - gp.z);
            if (gp.y > mod.getPlayer().getY() + 2.0 && horizToGoal < 1.5 && equipBuildBlock(mod)) {
                kaptainwutax.tungsten.task.PillarTask.startTo((int) Math.ceil(gp.y));
                twBestDistToGoal = -1; twBestImproveMs = 0L;
                checker.reset();
                setDebugState("Tungsten pillaring up to goal (#46)...");
                return true;
            }
            // #46 bridge-as-a-move: stuck at the edge of a GAP with the goal across it
            // (roughly level, not overhead) — pave a bridge toward the goal instead of
            // abandoning. Parkour (v0.40.0) already clears gaps <=4; this handles wider
            // ones a running jump can't. Mutually exclusive with the pillar case above.
            if (Math.abs(gp.y - mod.getPlayer().getY()) <= 2.0 && horizToGoal > 2.0
                    && gapTowardGoal(mod, gp) && equipBuildBlock(mod)) {
                kaptainwutax.tungsten.task.BridgeTask.startTo(
                        (int) Math.floor(gp.x), (int) Math.floor(gp.y), (int) Math.floor(gp.z));
                twBestDistToGoal = -1; twBestImproveMs = 0L;
                checker.reset();
                setDebugState("Tungsten bridging across a gap to goal (#46)...");
                return true;
            }
            twBestDistToGoal = -1; twBestImproveMs = 0L;   // re-measure on re-entry
            kaptainwutax.tungsten.Debug.logMessage(
                    "[nav] goal unreachable — no progress in 14s (dist " + String.format("%.1f", distToGoalNow) + "), yielding");
            return false;   // NOTE: no checker.reset() here — let the task fail
        }

        // Mid-pillar (#46) — let the pillar finish before any other nav runs.
        if (kaptainwutax.tungsten.task.PillarTask.isActive()) {
            pdPillar++;
            checker.reset();
            setDebugState("Tungsten pillaring up to goal (#46)...");
            return true;
        }
        // Mid-bridge (#46) — let the bridge finish crossing before any other nav runs.
        if (kaptainwutax.tungsten.task.BridgeTask.isActive()) {
            pdBridge++;
            checker.reset();
            setDebugState("Tungsten bridging across a gap (#46)...");
            return true;
        }

        // A BOT IN WATER IS NOT STUCK, IT IS FLOATING.
        // A swimmer bobs inside one block — measured on the playthrough course, ten minutes at
        // (-177,62,290) with the body oscillating between y 62.2 and 63.0, which is well inside
        // the 0.75 this detector calls "has not moved". The escalation then fires every five
        // seconds and its recovery is to KILL the pathfinder, the executor and the walker; after
        // three rounds primDrive returns false and hands movement back to the legacy driver
        // entirely. That is why every tungsten counter read zero on that course — mqStarted=0 and
        // called=0 — while the search kept finding paths: control never reached the block-route
        // branch below, which is the one that can hand a liquid edge to MovementSwim.
        // Refreshing the timer here lets that branch run. It is not a licence to float forever:
        // the branch below either produces a route or falls through as before.
        if (mod.getPlayer().isTouchingWater()) {
            twStuckPos = plNow;
            twStuckSinceMs = nowMs;
            twStuckResets = 0;
        }
        if (twStuckPos == null || plNow.distanceTo(twStuckPos) > 0.75) {
            twStuckPos = plNow; twStuckSinceMs = nowMs; twStuckResets = 0;
            // REFUTED, AND THE COUNTER IS WHY. Adding MovementQueue.isRunning() to this rung
            // changed nothing at all: pdStallWalk stayed 0 across three more runs and the sweep
            // went 2/3 to 0/3. So when the stall is detected NEITHER owner is running — the rung
            // below, which resets the nav, is not a missing hand-off but the correct branch for
            // "nobody is driving". Whatever leaves both drivers idle is the real question, and it
            // is upstream of this ladder. Reverted rather than left in as a no-op.
        } else if (kaptainwutax.tungsten.task.BlockPathWalker.isRunning() && nowMs - twStuckSinceMs > 2500) {
            // The WALKER stalled — most likely a parkour move it can't do (gap jump /
            // wall climb). Hand this segment to the physics executor (which parkours)
            // for a window, then re-try the walker.
            pdStallWalker++;
            kaptainwutax.tungsten.task.BlockPathWalker.stop();
            twPreferExecutorUntilMs = nowMs + 8000;
            twStuckSinceMs = nowMs;
        } else if (nowMs - twStuckSinceMs > 5000) {
            // Even the executor is stuck — trapped (stale-rooted reject loop /
            // unreachable sub-goal). Reset the nav to re-plan from the ACTUAL position;
            // after a few fruitless resets, yield to the wander so we walk out of a
            // local trap instead of freezing forever.
            var pfR = kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER;
            var exR = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            if (pfR != null) { pfR.stop.set(true); pfR.overrideStartPos = null; }
            if (exR != null) exR.stop = true;
            kaptainwutax.tungsten.task.BlockPathWalker.stop();
            pdStallReset++;
            twStuckSinceMs = nowMs;
            if (++twStuckResets >= 3) { pdStuckGiveUp++; twStuckResets = 0; twStuckPos = null; return false; }
        }

        try {
            var pf = kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER;
            var ex = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            boolean walking = kaptainwutax.tungsten.task.BlockPathWalker.isRunning();

            // DRIFT-IMMUNE terrain nav gets PRIORITY (user's directive: @gamer must be
            // extremely stable, never stuck). The physics executor replays a simulated
            // trajectory that DRIFTS on steps/slopes; at drift>threshold it hard-stops
            // AND the search rejects its own path ("root far from player") — so the
            // pathfinder is perpetually busy, never yielding, and the bot stalls. The
            // BlockPathWalker instead sprints from the bot's REAL position toward each
            // block-path waypoint (CombatPathfinder's grid BFS already does step-up/down),
            // so drift can't accumulate. When a walkable block path exists we FORCE the
            // drift-prone pathfinder/executor off and let the walker own movement; the
            // path is re-planned per ~25-block segment (rolling horizon). Water/parkour,
            // where the block BFS returns nothing, fall through to the physics executor.
            double dgx = mod.getPlayer().getX() - gp.x, dgy = mod.getPlayer().getY() - gp.y,
                    dgz = mod.getPlayer().getZ() - gp.z;
            double distToGoal = Math.sqrt(dgx * dgx + dgy * dgy + dgz * dgz);
            // Walker owns the LONG haul (drift-immune); the physics executor does the
            // final ~4-block precise approach (short range = negligible drift), which
            // closes the last steps a short "within 1.5 of goal" BFS path stalls on.
            // Close to the goal — stop the walker; the executor does the final <=4-block
            // precise approach (short range = negligible drift).
            if (walking && distToGoal <= 4.0) { kaptainwutax.tungsten.task.BlockPathWalker.stop(); walking = false; }
            if (walking) {
                pdWalking++;
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                checker.reset();
                setDebugState("Tungsten (primary) walking terrain...");
                return true;
            }
            // WATER NO LONGER EXCLUDES THE BLOCK ROUTE.
            // This gate was written when the only consumer of a block route was BlockPathWalker,
            // which cannot swim, so a bot in water had to fall through to the physics executor.
            // The queue now types liquid edges as MovementSwim and dispatches them BEFORE any
            // land predicate, so with navUsesQueue on there is something here that can cross a
            // pond. Measured on the playthrough course: the bot sat in the pond at (-177,62,290)
            // for ten minutes with mqStarted=0 and called=0 — this branch was never entered at
            // all, so nothing downstream could have helped.
            boolean inWater = mod.getPlayer().isTouchingWater();
            if (distToGoal <= 4.0) pdNear++;
            if (distToGoal > 4.0
                    && (!inWater || kaptainwutax.tungsten.TungstenConfig.get().navUsesQueue)
                    && nowMs >= twPreferExecutorUntilMs) {
                // (1) cheap grid BFS — instant, good for near/clean terrain.
                net.minecraft.util.math.BlockPos startB = mod.getPlayer().getBlockPos();
                net.minecraft.util.math.BlockPos goalB = net.minecraft.util.math.BlockPos.ofFloored(gp);
                java.util.List<net.minecraft.util.math.BlockPos> bfs =
                        kaptainwutax.tungsten.combat.CombatPathfinder.findPath(startB, goalB, mod.getWorld());
                boolean smart = kaptainwutax.tungsten.TungstenConfig.get().smartMoves;
                // A degenerate 2-wp stub to a far goal = CombatPathfinder couldn't route the
                // terrain (gapped/steep). With smartMoves the async SmartMoves search CAN
                // route it, so skip the stub and fall through to the robust path (2)/(3).
                boolean degenerateStub = smart && bfs.size() == 2 && distToGoal > 6.0
                        && Math.sqrt(bfs.get(1).getSquaredDistance(goalB)) > distToGoal - 3.0;
                if (kaptainwutax.tungsten.task.BlockPathWalker.DEBUG)
                    Debug.logMessage(String.format("primDrive gridBFS sz%d degen%b d%.1f dy%.1f",
                            bfs.size(), degenerateStub, distToGoal, gp.y - mod.getPlayer().getY()));
                if (bfs.size() >= 2 && !degenerateStub) {
                    // STOP THE DRIVER, NOT THE SEARCH.
                    // Handing movement to the walker is an OWNERSHIP decision and the executor
                    // must indeed stand down — it is the thing that would fight for the keys.
                    // The SEARCH is not fighting anyone: it is computing, on its own thread, a
                    // route this task will want in a moment. Killing it here threw that work
                    // away on every hand-off, which is the "[Tungsten] stopped!" that fires
                    // every ~5 seconds all run long, and it takes the armed paths with it
                    // ("the walker that was to reach its root has stopped").
                    if (ex != null) ex.stop = true;
                    // THE PORTED MOVEMENTS GET FIRST REFUSAL ON THE ROUTE.
                    // MovementQueue.start() had two callers, neither of them on this path: the
                    // navigator's build legs and the chase. So ordinary navigation — every step of
                    // the @gamer playthrough — went to the hand-rolled walker, and traverse /
                    // ascend / descend / diagonal / swim / fall were never asked for anything.
                    // Measured on the playthrough course: mqStarted=0 across a whole run, every
                    // refusal counter also 0, while the search kept finding paths. The walker
                    // stays as the fallback for a route the queue declines, exactly as in
                    // FollowEntityTask.
                    boolean queuedRoute = false;
                    if (kaptainwutax.tungsten.TungstenConfig.get().navUsesQueue
                            && !kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()) {
                        queuedRoute = kaptainwutax.tungsten.path.movements.MovementQueue
                                .start(bfs, true) > 0;
                    }
                    if (!queuedRoute
                            && !kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()) {
                        kaptainwutax.tungsten.task.BlockPathWalker.startBFS(bfs);
                    }
                    mod.getClientBaritone().getPathingBehavior().forceCancel();
                    checker.reset();
                    setDebugState("Tungsten (primary) walking terrain...");
                    return true;
                }
                // (2) cheap BFS can't route this (natural terrain, >25 blocks) — follow the
                // ROBUST elevation-aware block path the async search computes, drift-immune,
                // instead of the drift-prone physics executor (user's directive).
                java.util.Optional<java.util.List<kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode>> bp =
                        kaptainwutax.tungsten.path.PathFinder.getComputedBlockPath();
                // Staleness guard (smartMoves): getComputedBlockPath is the LAST async
                // result — may be for a previous goal. Only accept a path whose endpoint
                // reaches near the current goal; else recompute. (Off by default so the
                // legacy path selection is untouched.)
                boolean fresh = !smart || (bp.isPresent() && !bp.get().isEmpty()
                        && bp.get().get(bp.get().size() - 1).getBlockPos().getSquaredDistance(goalB) <= 36.0);
                if (kaptainwutax.tungsten.task.BlockPathWalker.DEBUG)
                    Debug.logMessage(String.format("primDrive robustPath present%b sz%d fresh%b",
                            bp.isPresent(), bp.map(java.util.List::size).orElse(0), fresh));
                if (bp.isPresent() && bp.get().size() >= 2 && fresh) {
                    java.util.List<net.minecraft.util.math.BlockPos> wps = new java.util.ArrayList<>();
                    for (kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode n : bp.get()) wps.add(n.getBlockPos());
                    if (ex != null) ex.stop = true;   // don't let the executor drift-replay
                    // THE PORTED MOVEMENTS GET FIRST REFUSAL ON THE ROUTE.
                    // MovementQueue.start() had two callers, neither of them on this path: the
                    // navigator's build legs and the chase. So ordinary navigation — every step of
                    // the @gamer playthrough — went to the hand-rolled walker, and traverse /
                    // ascend / descend / diagonal / swim / fall were never asked for anything.
                    // Measured on the playthrough course: mqStarted=0 across a whole run, every
                    // refusal counter also 0, while the search kept finding paths. The walker
                    // stays as the fallback for a route the queue declines, exactly as in
                    // FollowEntityTask.
                    boolean queuedRoute = false;
                    if (kaptainwutax.tungsten.TungstenConfig.get().navUsesQueue
                            && !kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()) {
                        queuedRoute = kaptainwutax.tungsten.path.movements.MovementQueue
                                .start(wps, true) > 0;
                    }
                    if (!queuedRoute
                            && !kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()) {
                        kaptainwutax.tungsten.task.BlockPathWalker.startBFS(wps);
                    }
                    mod.getClientBaritone().getPathingBehavior().forceCancel();
                    checker.reset();
                    setDebugState("Tungsten (primary) walking (robust path)...");
                    return true;
                }
                // (3) no block path yet — kick the async search to compute one.
                boolean busy = (pf != null && pf.active.get()) || (ex != null && ex.isRunning());
                if (kaptainwutax.tungsten.task.BlockPathWalker.DEBUG)
                    Debug.logMessage("primDrive asyncKick busy" + busy);
                if (!busy && pf != null) { if (ex != null) ex.stop = false; pf.find(mod.getWorld(), gp, mod.getPlayer()); }
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                checker.reset();
                setDebugState("Tungsten (primary) planning...");
                return true;
            }
            // Final approach (<=4 blocks) or water → physics executor.
            // THIS IS WHERE THE BOT SPENDS ITS LIFE, so it gets counted like everything else:
            // pdNear is ~5000 of ~5100 entries, i.e. the goal is within 4 blocks about 98% of the
            // time, and inside that radius the block route does not run at all — the physics
            // executor is the only driver. Whether it is working or merely "busy" is the
            // difference between arriving and the 5-second reset firing twenty times a run, and
            // nothing here could tell those apart.
            boolean busy = (pf != null && pf.active.get()) || (ex != null && ex.isRunning());
            if (busy) pdNearBusy++;
            // A SEARCH PER TWO TICKS IS NOT PLANNING, IT IS THRASHING.
            // Measured: pdNearFind 2460 and 2707 in a four-minute run — about 4800 ticks — for a
            // goal FOUR BLOCKS away. Every tick the search was not already busy, this issued a
            // fresh one, so no search ever survived long enough for its path to be walked, and
            // the bot stood in place until the 5-second reset fired (pdStallReset 14 and 17).
            // The same rate gate the placements got: give a search time to become a path.
            if (!busy && pf != null && nowMs - twLastNearFindMs > 600) {
                twLastNearFindMs = nowMs;
                pdNearFind++;
                if (ex != null) ex.stop = false;   // a prior ;stop leaves it stuck true
                pf.find(mod.getWorld(), gp, mod.getPlayer());
            }
        } catch (Throwable t) {
            Debug.logInternal("[swap] tungsten primary drive failed: " + t);
        }
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        checker.reset();
        setDebugState("Tungsten (primary) pathfinding...");
        return true;
    }

    /** Extract a target position from a baritone goal for tungsten (GoalBlock /
     *  GoalGetToBlock / GoalNear carry x,y,z). Null if the goal has no point. */
    private static net.minecraft.util.math.Vec3d goalToVec(Goal goal, AltoClef mod) {
        net.minecraft.util.math.Vec3d raw = null;
        if (goal instanceof baritone.api.pathing.goals.GoalBlock gb) {
            raw = new net.minecraft.util.math.Vec3d(gb.x, gb.y, gb.z);
        } else if (goal instanceof baritone.api.pathing.goals.GoalGetToBlock gg) {
            raw = new net.minecraft.util.math.Vec3d(gg.x, gg.y, gg.z);
        } else if (goal instanceof baritone.api.pathing.goals.GoalNear gn) {
            net.minecraft.util.math.BlockPos p = gn.getGoalPos();
            raw = new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ());
        } else if (goal instanceof baritone.api.pathing.goals.GoalTwoBlocks gt) {
            net.minecraft.util.math.BlockPos p = gt.getGoalPos();
            raw = new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ());
        } else if (goal instanceof baritone.api.pathing.goals.GoalXZ gxz) {
            // No y in the goal at all — an XZ goal means "get to this column", so aim at our own
            // height and let the search find the surface.
            raw = new net.minecraft.util.math.Vec3d(gxz.getX(), mod.getPlayer().getY(), gxz.getZ());
        } else if (goal instanceof baritone.api.pathing.goals.GoalComposite gc) {
            // Nearest member wins: a composite is "any of these will do".
            double best = Double.MAX_VALUE;
            for (Goal sub : gc.goals()) {
                net.minecraft.util.math.Vec3d v = goalToVec(sub, mod);
                if (v == null) continue;
                double d = v.squaredDistanceTo(mod.getPlayer().getX(),
                        mod.getPlayer().getY(), mod.getPlayer().getZ());
                if (d < best) { best = d; raw = v; }
            }
            return raw;   // already snapped by the recursive call
        }
        return raw == null ? null : snapGoalToStandable(raw, mod);
    }

    /** A goal cell that isn't standable (inside a solid block, or floating in air
     *  above the ground — e.g. a click on a grass block reports the cell ABOVE the
     *  surface) can never be reached exactly, so the tungsten search stalls at it.
     *  Snap it to the nearest standable cell (surface on top of a block / ground
     *  below the air) so the bot actually approaches. Valid standable goals are
     *  returned unchanged — normal navigation is untouched. */
    private static net.minecraft.util.math.Vec3d snapGoalToStandable(net.minecraft.util.math.Vec3d gp, AltoClef mod) {
        try {
            net.minecraft.world.World w = mod.getWorld();
            int gx = (int) Math.floor(gp.x), gy = (int) Math.floor(gp.y), gz = (int) Math.floor(gp.z);
            if (standable(w, gx, gy, gz)) return gp;                 // already fine
            if (isSolidAt(w, gx, gy, gz)) {                          // goal inside a block → stand on top
                for (int y = gy + 1; y <= gy + 5; y++)
                    if (standable(w, gx, y, gz)) return new net.minecraft.util.math.Vec3d(gx + 0.5, y, gz + 0.5);
            }
            for (int y = gy; y >= gy - 6; y--)                       // floating goal → drop to the ground
                if (standable(w, gx, y, gz)) return new net.minecraft.util.math.Vec3d(gx + 0.5, y, gz + 0.5);
        } catch (Throwable ignored) { }
        return gp;
    }

    private static boolean standable(net.minecraft.world.World w, int x, int y, int z) {
        return isSolidAt(w, x, y - 1, z) && !isSolidAt(w, x, y, z) && !isSolidAt(w, x, y + 1, z);
    }

    private static boolean isSolidAt(net.minecraft.world.World w, int x, int y, int z) {
        net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(x, y, z);
        return !w.getBlockState(p).getCollisionShape(w, p).isEmpty();
    }
}
