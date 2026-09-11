package adris.altoclef.tasks.movement;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.InputControls;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.goals.AltoGoal;
import adris.altoclef.util.helpers.TungstenHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.*;
import net.minecraft.util.math.BlockPos;

/**
 * Turns a baritone goal into a task.
 */
public abstract class CustomBaritoneGoalTask extends Task implements ITaskRequiresGrounded {

    /** Entry and early-exit tallies for the tungsten branch; read over py4j in placeStats(). */
    public static volatile int pdEnter, pdNotPrimary, pdPillar, pdBridge, pdStuckGiveUp,
            pdWalking, pdNear, pdNoGoal, pdFinished, pdNoVec, pdStallWalker, pdStallReset, pdNearBusy, pdNearFind, pdPlanning, pdPlanGiveUp;
    /** Planning ticks that did NOT reset the stall watchdog. Proof the fix fired. */
    public static volatile int pdPlanNoReset;

    /** Routes handed back to the walker because the queue could only admit a stub; 0 at minSteps=1. */
    public static volatile int pdQueueTooShort;
    /** When the "no route" line last printed; the state repeats every tick otherwise. */
    private long twLastNoRouteLogMs = 0L;
    /** When the near-goal branch last issued a search; see the rate gate at its site. */
    private long twLastNearFindMs = 0L;
    /** When the drive started planning without the body moving or a chain running; 0 = not in
     *  that state. The yardstick for PLAN_GIVE_UP_MS. */
    private long twPlanSinceMs = 0L;
    /** Where the body was when that clock started, so ANY real movement restarts it. */
    private net.minecraft.util.math.BlockPos twPlanFeet = null;
    /** How long the drive may claim the tick while producing no route and no movement. Eight
     *  seconds is far longer than a healthy plan (which becomes a chain within a tick or two)
     *  and far shorter than the ninety seconds of standing still the bench calls a stall. */
    private static final long PLAN_GIVE_UP_MS = 8000L;
    /** Ticks the LEGACY engine was handed the goal because tungsten declined. Read as pdLegacy;
     *  the whole of "can baritone go" is whether this stays at zero on a real run. */
    public static volatile int pdLegacyPath;
    /** Legacy hand-offs re-routed to tungsten, and ones tungsten would not take either. */
    public static volatile int pdLegacyToTungsten, pdLegacyDeclined;
    /** Simple name of the last goal type goalToVec could not translate; read over py4j. */
    /** Goal snaps: asked, moved to solid ground, and left unstandable. */
    public static volatile int snapAsked, snapMoved, snapFailed;
    /** Snaps that landed on the bot's own cell -- a request to walk nowhere. */
    public static volatile int snapToSelf;
    /** Tasks that finished because the bot stood where the SNAP put the goal, not where the task
     *  asked. The mechanism counter for
     *  {@link kaptainwutax.tungsten.TungstenConfig#arrivalAgreesWithTheSnap}: 0 in a control arm,
     *  non-zero in a fix arm, or the pair measured nothing (CHECKLIST rule 4a1). */
    public static volatile int arrivedAtSnap;
    /** ...and WHICH tasks asked, by count. Bounded: a tally that can grow without limit is a
     *  leak, and eight names is already more than a verdict line can carry. */
    private static final java.util.Map<String, Integer> SNAP_TO_SELF_WHO =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    /** Record one snap-to-self against the task that asked for it. */
    public static void noteSnapToSelf(String who) {
        synchronized (SNAP_TO_SELF_WHO) {
            if (SNAP_TO_SELF_WHO.size() < 16 || SNAP_TO_SELF_WHO.containsKey(who)) {
                SNAP_TO_SELF_WHO.merge(who, 1, Integer::sum);
            }
        }
    }

    /** The tally, most frequent first, as "Task xN Task xN". */
    public static String snapToSelfDump() {
        synchronized (SNAP_TO_SELF_WHO) {
            return SNAP_TO_SELF_WHO.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(6)
                    .map(e -> e.getKey() + "x" + e.getValue())
                    .reduce((a, b) -> a + " " + b).orElse("-");
        }
    }

    /** Cleared with the other per-run counters. */
    public static void clearSnapToSelfWho() {
        synchronized (SNAP_TO_SELF_WHO) { SNAP_TO_SELF_WHO.clear(); }
    }

    public static volatile String pdLastUnknownGoal = "-";

    private final Task wanderTask = new TimeoutWanderTask(5, true);
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final boolean wander;
    protected MovementProgressChecker checker = new MovementProgressChecker();
    /** The same goal in altoclef's own terms — what the drive and isFinished actually steer by. */
    protected AltoGoal cachedAlto = null;
    /**
     * The cell the snap moved this task's goal to, or null when the goal stood on its own.
     *
     * <p>Set every tick the drive runs, because the snap depends on the world and on where the bot
     * is standing; caching it across ticks would be a stale answer to a question that moves.
     */
    private net.minecraft.util.math.BlockPos snappedGoalCell = null;
    // Anti-permanent-stuck (tungsten-primary): if the bot hasn't moved for a while,
    // the tungsten nav is trapped (unreachable sub-goal / stale-rooted reject loop) —
    // reset its state so it re-plans fresh, then yield to wander if it stays stuck.
    private net.minecraft.util.math.Vec3d twStuckPos = null;
    private long twStuckSinceMs = 0L;
    private int twStuckResets = 0;
    // The walker can't parkour (gap jumps / wall climbs). When it stalls we hand the
    // segment to the physics executor (which can) for a window, then re-try the walker.
    private long twPreferExecutorUntilMs = 0L;

    /**
     * Until when the QUEUE is not to be offered a route, after it turned one down as too short.
     *
     * <p>⛔ WITHOUT THIS THE DECLINE IS WORSE THAN THE DISEASE, and it was measured that way on a
     * live playthrough: pdEnter+448, mqStart+446, mqSteps+0 in twenty-two seconds. The queue was
     * started EVERY TICK and took no step at all -- stopping it leaves isRunning() false, the
     * walker starts, the walker stops itself the moment the executor runs, and the next tick
     * offers the queue the same route again. 446 starts, zero steps, the body never moved: worse
     * than the 28 starts and 25 steps the untouched code manages.
     *
     * <p>A decline has to buy the walker a WINDOW, not a tick. Same shape as
     * twPreferExecutorUntilMs above, which exists for the mirror-image problem.
     */
    private long twPreferQueueAfterMs = 0L;

    /** How long the walker owns the route after the queue turns it down. */
    private static final long QUEUE_DECLINE_COOLDOWN_MS = 6000L;
    // Net-progress-toward-goal tracking, to give up on genuinely UNREACHABLE goals
    // (e.g. a tree top needing place/break we don't plan yet) instead of searching
    // forever. Keyed on distance to goal, not raw movement — a bot wandering in place
    // near an unreachable goal makes no NET progress even though it "moves". #27.
    private double twBestDistToGoal = -1;
    private long twBestImproveMs = 0L;
    // Build-engine escalation: when the grid BFS cannot reach the goal (up a cliff, across a
    // gap, walled in) we hand the leg to FastNavigator — the ;goto engine that plans
    // pillar/bridge/break/staircase via FastPlanner and executes through the physics executor.
    private long twNoRouteSinceMs = 0L;      // ms since grid BFS first returned no usable route (0 = has one)
    private long twFnCooldownUntilMs = 0L;   // don't (re)start the build engine before this ms
    private net.minecraft.util.math.BlockPos twFnGoal = null;  // the goal cell FastNavigator was handed
    public static int pdFnBuild = 0;         // times the drive escalated to FastNavigator to build
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
        Nav.cancel();
        TungstenHelper.reset();
        checker.reset();
        stuckCheck.reset();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        InputControls controls = mod.getInputControls();
        
        if (Nav.isPathing()) {
            checker.reset();
        }
        if (WorldHelper.isInNetherPortal()) {
            // TODOS.md, the same "a search is not progress" substitution already proven for the
            // drowning guard (WorldSurvivalChain.handleDrowning): Nav.isPathing() is true while a
            // background search merely computes, driving nothing. A stalled search could suppress
            // this manual walk-out-of-the-portal escape indefinitely, and prolonged portal contact
            // eventually teleports the bot to the other dimension via plain vanilla mechanics.
            // Nav.isExecutingRoute() asks the narrower, correct question: a genuinely executing
            // route through the portal is untouched. Particularly relevant here: this is the
            // primary altoclef movement driver, so it runs on nearly every task.
            if (!Nav.isExecutingRoute()) {
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
            if (Nav.isPathing()) {
                controls.release(Input.SNEAK);
                controls.release(Input.MOVE_BACK);
                controls.release(Input.MOVE_FORWARD);
            }
        }
        if (unstuckTask != null && unstuckTask.isActive() && !unstuckTask.isFinished() && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            Nav.clearGoal();
            Nav.stopExploring();
            return unstuckTask;
        }
        if (!checker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                unstuckTask = getFenceUnstuckTask();
                return unstuckTask;
            }
            // Not in annoying block — force baritone to recompute, so wander fallback can fire
            Nav.cancel();
            stuckCheck.reset();
        }
        goal(mod);

        // ── Tungsten-PRIMARY (drop-in swap, TODO 13) ──
        if (driveTungstenPrimary(mod)) return null;

        // ── Tungsten lock: exclusive 30s control, Baritone stays off ──
        if (TungstenHelper.isLocked()) {
            TungstenHelper.tickLock();
            Nav.cancel();
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
                    if (cachedAlto != null) {
                        var player = mod.getPlayer();
                        var goalPos = new net.minecraft.util.math.Vec3d(
                                player.getX(), player.getY(), player.getZ());
                        // G-0: AltoGoal.target() IS the point, so the instanceof ladder over legacy
                        // goal classes that used to recover it is gone -- which is the whole reason
                        // AltoGoal was introduced.
                        var t = cachedAlto.target();
                        if (t != null) goalPos = t;
                        if (TungstenHelper.tryPathTo(goalPos)) {
                            Nav.cancel();
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
        if (!isFinished()
                && !TungstenHelper.isActive()
                && !Nav.hasGoal()
                && Nav.isSafeToCancel()) {
            // THE LAST PLACE THE LEGACY ENGINE STILL MOVES THE BOT.
            // Everything above this line is tungsten; reaching here means the tungsten drive
            // declined the tick and shredder is being asked to walk instead. Count it, because
            // "can baritone be deleted" is exactly the question of whether this number is zero on
            // a real run -- and a guess about that is worth nothing.
            //
            // ⛔ A FINISHED TASK MUST NOT COMMAND AN ENGINE, and until now it did. Measured on
            // craft_iron_pickaxe, which passes: pdLegacy=62 of pdEnter=151, with the declines
            // reading pdFinished=122 pdWalking=23 pdNear=5. So the dominant reason tungsten
            // stepped aside was that the goal was ALREADY REACHED (isFinished), and this line
            // then handed that reached goal to shredder and asked it to path there.
            //
            // That is not a safety net catching a tungsten failure — it is spurious work, and on
            // that course it was 40% of every drive entry. It is also why the whole "baritone is
            // nearly dead" reading was wrong: the number was large because finished tasks kept
            // poking it on their way out, not because tungsten kept failing.
            //
            // The other two declines are already handled by the guard below: pdWalking and
            // pdNear both leave TungstenHelper.isActive() true, so those ticks never reach here.
            pdLegacyPath++;
            // ⛔ THE USER WATCHED A RUN AND SAW THIS: "постоянно активируется баритон и ломает
            // маршрут". They are right, and a counter I had all along says so -- pdLegacy=9 on a
            // twenty-minute playthrough. The comment above this line reasoned that the number was
            // large only because finished tasks poked it on the way out; that was true and it was
            // not the whole story. Nine entries remain where tungsten genuinely declined the tick
            // and the goal was handed to shredder, which then walks the bot on its own route --
            // visibly, in the middle of a tungsten run.
            //
            // Removing baritone is the entire point of this project, so the answer here is not a
            // better fallback, it is no fallback: ask TUNGSTEN for the same goal, exactly as the
            // stuck-recovery path thirty lines above already does. If tungsten will not take it,
            // do nothing and let the task ask again next tick -- a tick spent waiting for the
            // engine we are keeping beats a tick spent moving on the engine we are deleting.
            if (kaptainwutax.tungsten.TungstenConfig.get().neverHandOffToLegacy) {
                net.minecraft.util.math.Vec3d legacyPos = cachedAlto == null ? null : cachedAlto.target();
                if (legacyPos != null && TungstenHelper.tryPathTo(legacyPos)) {
                    pdLegacyToTungsten++;
                } else {
                    pdLegacyDeclined++;
                }
            } else {
                // G-0: the legacy hand-off is gone. If tungsten declines, wait a tick and ask again --
                // there is no other engine to fall back to, and that is the point.
            }
        }
        setDebugState("Completing goal.");
        return null;
    }

    @Override
    public boolean isFinished() {
        AltoGoal g = goal(AltoClef.getInstance());
        // SAY WHERE WE WERE WHEN WE CALLED IT DONE.
        // nav_bridge ends with the bot standing at the lip of the gap, 11.6 blocks short, and the
        // chain reading "No tasks" -- with no "interrupted" and no "finished in N seconds" in the
        // log, which leaves this method returning true as the only way out. If that is what
        // happens, the goal and the position at that moment name the bug; if it is not, this line
        // never prints and the search moves elsewhere.
        if (AltoClef.getInstance() == null || AltoClef.getInstance().getPlayer() == null) {
            return false;
        }
        net.minecraft.util.math.BlockPos at = AltoClef.getInstance().getPlayer().getBlockPos();
        boolean done = g != null && g.reached(at);
        // ⛔ THE DRIVE AND THE ARRIVAL TEST MUST NOT STEER BY DIFFERENT CELLS.
        //
        // AltoGoal.Block.reached asks whether the bot OCCUPIES the requested cell. The drive
        // steers at snapGoalToStandable(that cell) -- and the snap only moves a goal that CANNOT
        // BE STOOD IN. So whenever it moves one, this test is unsatisfiable by construction: the
        // drive parks the bot beside the log, the task reports "not there", and asks again next
        // tick, forever. Measured on the playthrough:
        //
        //   snap=200/197/3/self134[GetToBlockTaskx134]   goal moved on 197 of 200 asks,
        //                                                and onto the BOT 134 times
        //   atGoal=10(ex10,ytol0)@GetToBlockTask@block(-311,125,-257)x10
        //
        // -- ten plans in a row asking for a route into the cell the bot already stood in, all
        // from one task and one block, every match an exact cell. snapToSelf counts only ticks
        // where this method has ALREADY returned false (its guard sits above the snap), so those
        // 134 are 134 unsatisfiable arrival tests, not near-misses.
        //
        // Accepting the snapped cell can only ADD completions that were otherwise impossible: if
        // the requested cell is unstandable the bot can never occupy it, so the exact test could
        // never have passed. A goal that stood on its own keeps exact semantics -- snappedGoalCell
        // is null then.
        if (!done && snappedGoalCell != null && snappedGoalCell.equals(at)
                && kaptainwutax.tungsten.TungstenConfig.get().arrivalAgreesWithTheSnap) {
            arrivedAtSnap++;
            done = true;
        }
        if (done) {
            kaptainwutax.tungsten.Debug.logMessage("[nav] goal task reports FINISHED at "
                    + at.getX() + "," + at.getY() + "," + at.getZ() + " goal=" + g);
        }
        return done;
    }

    @Override
    protected void onStop(Task interruptTask) {
        Nav.cancel();
        TungstenHelper.stop();
        // ⛔ THE ROUTE DIES WITH ITS DRIVE (G52, 2026-09-11). TungstenHelper.stop() ends the
        // PHYSICS search and its executor -- the navigator, the walker, the queue and the building
        // manoeuvres kept running under whatever task came next. On the 17:22 recording the
        // cobblestone approach escalated to the navigator ("Path needs mining: 1 block(s)"), the
        // task tree switched to the crafting table three blocks overhead, the click leaf aimed UP
        // at the table while the orphaned navigator handed off to a tower that aimed DOWN:
        // pitch 25, no jump in eighty ticks, "Pillar stuck air=0", and the stone beside the bot
        // "failed to break" three times as the crosshair swung between the two owners. G40 only
        // caught an orphan when the NEXT drive started; a leaf that does not drive never did.
        // A drive that is replaced by another drive leaves the route for adoption (G40); an armed
        // escape and a builder's exact cell belong to someone else and are left alone.
        if (kaptainwutax.tungsten.TungstenConfig.get().routeDiesWithItsDrive
                && !(interruptTask instanceof CustomBaritoneGoalTask)
                && PlannedEscape.armedFrom() == null
                && !kaptainwutax.tungsten.task.FastNavigator.hasExactCell()) {
            boolean live = kaptainwutax.tungsten.task.FastNavigator.isActive()
                    || kaptainwutax.tungsten.task.BlockPathWalker.isRunning()
                    || kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()
                    || kaptainwutax.tungsten.task.PillarTask.isActive()
                    || kaptainwutax.tungsten.task.BridgeTask.isActive()
                    || kaptainwutax.tungsten.task.SwimOutTask.isActive();
            if (live) {
                pdRouteStopped++;
                kaptainwutax.tungsten.TungstenMod.stopNavigation();
            }
        }
    }

    /**
     * THE GOAL, IN ALTOCLEF'S TERMS — whichever vocabulary the task chose to express it in.
     *
     * <p>A task states its goal by overriding EITHER {@link #newAltoGoal} (the way forward) or
     * {@link #newGoal} (baritone's types, the way out). Both are resolved here into one
     * {@link AltoGoal}, so everything downstream — the tungsten drive, isFinished — knows exactly
     * one type and the files can be moved over one at a time without a flag day.
     */
    protected AltoGoal goal(AltoClef mod) {
        if (cachedAlto != null) return cachedAlto;
        cachedAlto = newAltoGoal(mod);
        // G-0: the legacy twin is gone, and with it toBaritone(), BaritoneGoalView and newGoal().
        // The comment that stood here promised the import would go when the fallback did. It has.
        return cachedAlto;
    }

    /**
     * Where this task is going, in altoclef's own goal type. Null means "I still speak baritone" —
     * see {@link #newGoal}. Overriding this is what removes a file from the baritone count.
     */
    protected AltoGoal newAltoGoal(AltoClef mod) {
        return null;
    }

    /**
     * Where this task is going, in baritone's goal type.
     *
     * <p>The legacy way. Tasks whose goal is a place (a block, a radius, a column, a height) should
     * override {@link #newAltoGoal} instead; this stays for the goals that are really custom
     * HEURISTICS rather than places — flee goals, the lava escape, the direction goal — which carry
     * baritone's cost model inside them and need porting rather than translating.
     */
    // removed with the legacy goal type (G-0)

    // removed with the legacy goal type (G-0)

    // (the two goal adapters documented here were deleted with the legacy type)
    // removed with the legacy goal type (G-0)

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

    /** The bot is trapped in a pit/shaft below a goal that is UP-AND-OFFSET: a wall in the
     *  goal's horizontal direction is ≥2 blocks tall (can neither step nor jump over it), and
     *  there is headroom to start a pillar. Returns the Y to pillar to — the top of that wall,
     *  where the horizontal route reopens, capped at the goal's own Y and a sane maximum so a
     *  freak goal far overhead can never build an endless tower. Returns -1 when NOT trapped
     *  (open ground, a plain cliff face the goal is not behind, or a ceiling overhead), so the
     *  normal give-up path runs instead of pillaring pointlessly. */
    private int pillarEscapeY(AltoClef mod, net.minecraft.util.math.Vec3d gp) {
        var p = mod.getPlayer();
        var world = mod.getWorld();
        net.minecraft.util.math.BlockPos feet = p.getBlockPos();
        // Need clear space above the head, or a pillar cannot even start.
        net.minecraft.util.math.BlockPos over = feet.up(2);
        if (!world.getBlockState(over).getCollisionShape(world, over).isEmpty()) return -1;
        double dx = gp.x - p.getX(), dz = gp.z - p.getZ();
        net.minecraft.util.math.Direction dir = Math.abs(dx) >= Math.abs(dz)
                ? (dx >= 0 ? net.minecraft.util.math.Direction.EAST : net.minecraft.util.math.Direction.WEST)
                : (dz >= 0 ? net.minecraft.util.math.Direction.SOUTH : net.minecraft.util.math.Direction.NORTH);
        net.minecraft.util.math.BlockPos ahead = feet.offset(dir);
        // A wall the bot cannot step (feet-level solid) OR jump (head-level solid too) over.
        boolean solidFeet = !world.getBlockState(ahead).getCollisionShape(world, ahead).isEmpty();
        boolean solidHead = !world.getBlockState(ahead.up()).getCollisionShape(world, ahead.up()).isEmpty();
        if (!(solidFeet && solidHead)) return -1;   // not walled in toward the goal -> not our case
        int goalY = (int) Math.ceil(gp.y);
        int cap = Math.min(goalY, feet.getY() + 24);
        // Climb only to where the wall in the goal's direction first opens up (a 2-tall gap we
        // could step through), so we escape the pit without towering past it.
        for (int y = feet.getY() + 1; y <= cap; y++) {
            net.minecraft.util.math.BlockPos a = new net.minecraft.util.math.BlockPos(ahead.getX(), y, ahead.getZ());
            net.minecraft.util.math.BlockPos a2 = a.up();
            if (world.getBlockState(a).getCollisionShape(world, a).isEmpty()
                    && world.getBlockState(a2).getCollisionShape(world, a2).isEmpty()) {
                return y;
            }
        }
        return cap;   // walled the whole way up: pillar to the goal's level
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
        AltoGoal goal = goal(mod);
        if (goal == null) { pdNoGoal++; return false; }
        if (isFinished()) { pdFinished++; return false; }
        net.minecraft.util.math.Vec3d gp = goal.target();
        if (gp != null) {
            // An XZ goal has no height and a Y-level goal has no column; both say so with NaN and
            // borrow the missing half from where the bot is standing.
            if (Double.isNaN(gp.x) || Double.isNaN(gp.z)) {
                gp = new net.minecraft.util.math.Vec3d(Double.isNaN(gp.x) ? mod.getPlayer().getX() : gp.x,
                        gp.y, Double.isNaN(gp.z) ? mod.getPlayer().getZ() : gp.z);
            }
            if (Double.isNaN(gp.y)) {
                gp = new net.minecraft.util.math.Vec3d(gp.x, mod.getPlayer().getY(), gp.z);
            }
            // Publish the live goal for the planned escape (PlannedEscape): a stuck bot's best
            // escape target is the thing it was trying to reach.
            lastGoalVec = gp;
            lastGoalAtMs = System.currentTimeMillis();
            lastGoalReachBlock = goal instanceof adris.altoclef.util.goals.AltoGoal.Adjacent a0 ? a0.pos() : null;
            // ── A BLOCK TO BE BROKEN IS APPROACHED, NEVER STOOD IN (G25, 2026-09-11) ──
            // Everything below this line steers at a CELL: it snaps an unstandable goal onto
            // standable ground, walks the grid BFS there, and escalates to the build engine only
            // when walking fails. For a mining target that is the wrong question from the first
            // line -- the snap turned "the stone 5 blocks under my feet" into "the surface I am
            // standing on", and no engine was ever asked to dig. A reach goal skips all of it and
            // goes straight to FastNavigator with the block itself.
            if (goal instanceof adris.altoclef.util.goals.AltoGoal.Adjacent adj
                    && kaptainwutax.tungsten.TungstenConfig.get().mineGoalIsAdjacent) {
                return driveReach(mod, adj, gp);
            }
            // ⛔ A SOLID BLOCK GOAL IS DUG INTO, NOT STOOD ON (G55, 2026-09-11). Baritone's
            // GoalBlock is the cell itself; when the cell is rock, the route ends with the rock
            // mined and the feet where it was. Here the snap below moved such a goal beside or on
            // top of the block, the planner's height tolerance called "on top" arrived, and
            // AltoGoal.Block.reached -- exact, as it should be -- never agreed. On the 17:56
            // recording: the loot action asked for the sand cell above a buried chest while the
            // bot stood on that sand: snapSelfRefused=1830, atGoal=52(ytol52), "arrived (1.0)"
            // every twelve seconds, "Failed! No block path" every two, nine minutes on one spot.
            // A breakable solid cell goes to the one engine that digs, as an EXACT cell; what
            // cannot be broken (bedrock, a container, a protected block) keeps the snap.
            if (goal instanceof adris.altoclef.util.goals.AltoGoal.Block bg
                    && kaptainwutax.tungsten.TungstenConfig.get().blockGoalDigsIntoSolid
                    && diggableGoalCell(mod, bg.pos())) {
                return driveDig(mod, bg.pos());
            }
            // MEASURE THE SNAP. The 1219 stall runs at a goal whose floor is AIR
            // (tgt[1205.5,104.0,-839.5], floor=air), so this must either move it to solid ground
            // or admit it cannot. Nine million expanded nodes says nobody found out which.
            net.minecraft.util.math.Vec3d gpBefore = gp;
            gp = snapGoalToStandable(gp, mod);
            snapAsked++;
            // ⛔ A SNAP THAT LANDS ON THE BOT'S OWN FEET IS NOT A GOAL, IT IS A DEAD END (G40,
            // 2026-09-11). The snap walks the goal's column up to five cells looking for somewhere
            // to stand, and once the bot has dug to within five blocks of a goal inside rock the
            // first standable cell in that column IS the bottom of its own shaft. Measured on the
            // dig_down regression: six blocks mined by FastNavigator, then at y=-57 (goal -62) the
            // snapped goal became the bot's cell, the "goal moved" guard below killed the navigator
            // silently (25 > 16), and the physics final approach searched a route to its own feet
            // every 600 ms for 140 s -- "Time taken to find path: 2 ms" / "Finished!" -- looking
            // at the block it should have been mining. A goal that cannot be stood in is reached by
            // the engine that digs; the snap exists for walkers. Keep the real goal: the grid BFS
            // finds no route into rock and the escalation hands it to FastNavigator, which does.
            if (kaptainwutax.tungsten.TungstenConfig.get().snapNeverLandsOnSelf
                    && gp != null && gpBefore != null && !gp.equals(gpBefore)) {
                net.minecraft.util.math.BlockPos meS = mod.getPlayer().getBlockPos();
                if (meS.getX() == (int) Math.floor(gp.x) && meS.getY() == (int) Math.floor(gp.y)
                        && meS.getZ() == (int) Math.floor(gp.z)) {
                    snapRefusedSelf++;
                    gp = gpBefore;
                }
            }
            // DID THE SNAP LAND ON US? A goal that cannot be stood in gets pulled to the
            // nearest standable cell, and for an unreachable target the nearest such cell can
            // be the one the BOT IS STANDING IN. Then the planner is asked to route to where
            // it already is: measured as atGoal=50(ex50,ytol0) -- every match an EXACT cell,
            // so the two layers agree and the request itself is the no-op. The real target
            // stays unreached while the task asks again every tick.
            if (gp != null) {
                net.minecraft.util.math.BlockPos me = mod.getPlayer().getBlockPos();
                if (me.getX() == (int) Math.floor(gp.x) && me.getY() == (int) Math.floor(gp.y)
                        && me.getZ() == (int) Math.floor(gp.z)) {
                    snapToSelf++;
                    // WHO, BY COUNT. snapToSelf read 747 of 944 in one run and 4260 of 5554 in
                    // another, and a single number cannot say whether that is one task asking
                    // four thousand times or every task asking once. A last-wins string cannot
                    // either -- that is what planAtGoalWho was, and it named a cell.
                    noteSnapToSelf(getClass().getSimpleName());
                }
            }
            // REMEMBER WHERE THE DRIVE IS ACTUALLY TAKING US. isFinished() asks AltoGoal.Block
            // whether the bot OCCUPIES the requested cell, while the drive steers at the snapped
            // one -- and the snap exists precisely because the requested cell CANNOT be stood in.
            // So for every goal the snap has to move, the arrival test is unsatisfiable by
            // construction: the drive parks the bot beside the block, the task says "not there",
            // and asks again every tick. That is what snapToSelf counts, and it counts only ticks
            // where isFinished() already returned false (the guard sits above this block).
            if (gp != null && gpBefore != null && !gp.equals(gpBefore)) {
                snapMoved++;
                snappedGoalCell = net.minecraft.util.math.BlockPos.ofFloored(gp.x, gp.y, gp.z);
            } else {
                // The goal stood on its own, so exact arrival still means exact.
                snappedGoalCell = null;
                if (gp != null && !standable(mod.getWorld(), (int) Math.floor(gp.x),
                        (int) Math.floor(gp.y), (int) Math.floor(gp.z))) snapFailed++;
            }
            // Publish WHOSE goal this is, so a climbing route can name its caller (CombatTrace).
            kaptainwutax.tungsten.combat.CombatTrace.hostGoal = String.valueOf(goal);
            // ...AND WHICH TASK IS HOLDING IT. The goal string names a cell, not an orderer, and
            // the open question at FastPlanner.planStartIsGoal is which task keeps asking for a
            // route into the cell the bot already occupies. This class is abstract; the simple
            // name is the concrete subclass -- DestroyBlockTask, GetToBlockTask, GetToXZTask...
            kaptainwutax.tungsten.combat.CombatTrace.hostOwner = getClass().getSimpleName();
        }
        if (gp == null) {
            // NAME THE TYPE, DO NOT GUESS IT. Extending the translator from two goal types to six
            // took pdNoVec to 0 on short runs, but a fifteen-minute run put it back at 1039 of
            // 3815 entries -- 27% -- so a further type turns up once the bot gets past its first
            // job. Record which, because that is the whole of the next fix.
            pdLastUnknownGoal = goal.toString();
            pdNoVec++;
            return false;
        }
        // WIRED 2026-09-10: the walker/queue still cannot build — but when they cannot REACH the
        // goal (grid BFS returns no route: a cliff, a gap, a wall, a pit) the escalation below
        // hands the leg to FastNavigator, which plans pillar/bridge/break/staircase via
        // FastPlanner and executes through the physics executor. planPlaceMoves/allowBreak default
        // ON (TungstenConfig), so that engine builds by default. The old note here said the
        // hand-off "isn't wired yet" and @goto only bridged reactively after a 14s give-up; that
        // is what this change fixes. hasBuildBlock() now gates the escalation's place case.

        // ── Anti-permanent-stuck safety net ──────────────────────────────
        long nowMs = System.currentTimeMillis();
        net.minecraft.util.math.Vec3d plNow = new net.minecraft.util.math.Vec3d(
                mod.getPlayer().getX(), mod.getPlayer().getY(), mod.getPlayer().getZ());

        // ⛔ A NAVIGATOR NOBODY IN THIS TASK ARMED IS EITHER OURS OR IN THE WAY (G40, 2026-09-11).
        // twFnGoal lives in the task INSTANCE, and the pickup rebuilds its approach task on every
        // target flip -- so a route the previous instance armed was still running while this
        // instance, seeing twFnGoal == null, walked the whole ladder underneath it: the yield
        // below never fired, the near-goal escalation was refused ("navigator active"), and the
        // physics approach spun. TungstenHelper.stop() does not touch the navigator, so nothing
        // ever ended the orphan. A running route that serves OUR goal is adopted; one that serves
        // a stale goal is stopped; an escape or a builder's exact positioning is left alone.
        if (kaptainwutax.tungsten.task.FastNavigator.isActive() && twFnGoal == null
                && kaptainwutax.tungsten.TungstenConfig.get().nearGoalEscalatesToBuild) {
            if (PlannedEscape.armedFrom() != null
                    || kaptainwutax.tungsten.task.FastNavigator.hasExactCell()) {
                checker.reset();
                setDebugState("Tungsten: yielding to a running navigator leg (escape / builder)...");
                return true;
            }
            net.minecraft.util.math.Vec3d ng = kaptainwutax.tungsten.task.FastNavigator.currentGoal();
            if (ng != null && ng.squaredDistanceTo(gp) <= 4.0) {
                twFnGoal = net.minecraft.util.math.BlockPos.ofFloored(gp);
                pdFnAdopted++;
            } else {
                kaptainwutax.tungsten.task.FastNavigator.stop();
                pdFnStale++;
            }
        }

        // ── Build-engine in charge: let FastNavigator drive ──────────────
        // FastNavigator (the ;goto engine) is the ONLY route driver that can pillar up a cliff,
        // bridge a gap, or dig a staircase — it plans with FastPlanner and executes through the
        // physics executor + PillarTask/BridgeTask. When the escalation below has handed it a leg,
        // it self-ticks from the client tick; the walker/queue must NOT run alongside it or they
        // fight for the movement keys. So while it is active, this task just yields the tick to it
        // and keeps the progress checker fed (the shimmy is already suppressed because
        // Nav.isPathing()/Pillar/Bridge are true — see UnstuckChain).
        if (kaptainwutax.tungsten.task.FastNavigator.isActive() && twFnGoal != null) {
            net.minecraft.util.math.BlockPos gpCell = net.minecraft.util.math.BlockPos.ofFloored(gp);
            // Goal moved far from what it is building toward -> stop and let the normal drive
            // (or a fresh escalation) re-plan on the new goal next tick.
            if (twFnGoal.getSquaredDistance(gpCell) > 16.0) {
                kaptainwutax.tungsten.task.FastNavigator.stop();
                twFnGoal = null;
                twFnCooldownUntilMs = nowMs + 1500;
            } else {
                checker.reset();
                setDebugState("Tungsten building route (pillar/bridge/break) via FastPlanner...");
                return true;
            }
        }

        // ── Unreachable-goal give-up (net progress toward the goal) ────────
        // If the closest we've gotten to the goal hasn't improved for a while, the goal
        // is unreachable under the current move set (we can't place/pillar/bridge yet).
        // Give up: stop tungsten and yield WITHOUT resetting the parent progress checker,
        // so the task can fail cleanly instead of the pathfinder spinning forever. #27.
        double distToGoalNow = plNow.distanceTo(gp);
        if (twBestDistToGoal < 0 || distToGoalNow < twBestDistToGoal - 0.5) {
            twBestDistToGoal = distToGoalNow;
            twBestImproveMs = nowMs;
        } else if (twBestImproveMs > 0 && nowMs - twBestImproveMs > 4000 && distToGoalNow > 2.0
                && nowMs >= twFnCooldownUntilMs
                && !kaptainwutax.tungsten.task.FastNavigator.isActive()
                && (kaptainwutax.tungsten.TungstenConfig.get().allowBreak
                    || (kaptainwutax.tungsten.TungstenConfig.get().planPlaceMoves && hasBuildBlock(mod)))) {
            // ── G5: PROGRESS-BASED ESCALATION TO THE BUILD ENGINE ──
            // The grid BFS returns a route (>=2 cells) that walks toward the goal but never reaches
            // it — a wandering partial, or a 2-cell stub toward a goal that is up a cliff / down
            // through stone / across water. bfs.size() is not <2, so the no-route escalation below
            // never fires, and the bot walks the partial and stalls into the 14s give-up (this was
            // the "dug 9 blocks down then stopped 3 short" case, 2026-09-10). So: after 4s of no
            // NET progress toward the goal, hand the leg to FastNavigator, which plans
            // pillar/bridge/break/dig-down and executes it. Cheaper than waiting the full 14s.
            kaptainwutax.tungsten.task.BlockPathWalker.stop();
            kaptainwutax.tungsten.path.movements.MovementQueue.stop();
            var exG = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            if (exG != null) exG.stop = false;
            kaptainwutax.tungsten.task.FastNavigator.start(gp);
            twFnGoal = net.minecraft.util.math.BlockPos.ofFloored(gp);
            twFnCooldownUntilMs = nowMs + 12000;
            twBestDistToGoal = -1; twBestImproveMs = 0L;
            pdFnBuild++;
            checker.reset();
            setDebugState("Tungsten: no progress — building a route (dig/pillar/bridge) via FastPlanner...");
            return true;
        } else if (twBestImproveMs > 0 && nowMs - twBestImproveMs > 14000 && distToGoalNow > 2.0) {
            kaptainwutax.tungsten.task.BlockPathWalker.stop();
            var pfU = kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER;
            var exU = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            // Do not destroy a search that has never handed back a route -- it is unfinished,
            // not bad, and killing it is what keeps the bot standing still. See
            // stallResetSparesAVirginSearch (measured here: 21+7 kills, tryEmit=0).
            if (kaptainwutax.tungsten.TungstenConfig.get().stallResetSparesAVirginSearch
                    && pfU != null && pfU.active.get()
                    && !kaptainwutax.tungsten.path.PathFinder.searchHasEmitted) {
                kaptainwutax.tungsten.path.PathFinder.stallSpared++;
            } else {
                kaptainwutax.tungsten.path.PathFinder.noteStop("CustomBaritoneGoalTask@521");
                if (pfU != null) pfU.stop.set(true);
                if (exU != null) exU.stop = true;
            }
            // #46 place-as-a-move: if the goal is directly above us and we have a block,
            // PILLAR up to it instead of abandoning — the real fix for raised place-only
            // goals (tree top / ledge) that walking or jumping can't reach.
            // Only a CLEAR vertical reach (goal well above + nearly overhead) — not a
            // transient stall near the top of a staircase where the goal is ~1 up.
            double horizToGoal = Math.hypot(plNow.x - gp.x, plNow.z - gp.z);
            // Two pillar cases, both only when the goal is well ABOVE us and we have a block:
            //   overhead — goal nearly straight up (tree top / ledge right above): pillar to it.
            //   trapped  — goal is up AND offset, and a wall in the goal's direction boxes us in
            //              (a pit/shaft the bot dug or fell into while mining). Walking can't get
            //              out; pillar up to the top of the trapping wall so the horizontal route
            //              reopens. This is the survival-path gap that left the bot standing in a
            //              hole holding dirt it could have climbed with (found live 2026-09-10).
            if (gp.y > mod.getPlayer().getY() + 2.0) {
                int pillarTargetY = -1;
                if (horizToGoal < 1.5) {
                    pillarTargetY = (int) Math.ceil(gp.y);
                } else if (kaptainwutax.tungsten.TungstenConfig.get().pillarEscapePit) {
                    pillarTargetY = pillarEscapeY(mod, gp);
                    if (pillarTargetY > mod.getPlayer().getY()) {
                        kaptainwutax.tungsten.Debug.logMessage(
                                "[nav] trapped below an offset goal — pit-escape pillar to y=" + pillarTargetY);
                    }
                }
                if (pillarTargetY > mod.getPlayer().getY() && equipBuildBlock(mod)) {
                    kaptainwutax.tungsten.task.PillarTask.startTo(pillarTargetY);
                    twBestDistToGoal = -1; twBestImproveMs = 0L;
                    checker.reset();
                    setDebugState("Tungsten pillaring up to goal (#46)...");
                    return true;
                }
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
            // Do not destroy a search that has never handed back a route -- it is unfinished,
            // not bad, and killing it is what keeps the bot standing still. See
            // stallResetSparesAVirginSearch (measured here: 21+7 kills, tryEmit=0).
            if (kaptainwutax.tungsten.TungstenConfig.get().stallResetSparesAVirginSearch
                    && pfR != null && pfR.active.get()
                    && !kaptainwutax.tungsten.path.PathFinder.searchHasEmitted) {
                kaptainwutax.tungsten.path.PathFinder.stallSpared++;
            } else {
                kaptainwutax.tungsten.path.PathFinder.noteStop("CustomBaritoneGoalTask@609");
                if (pfR != null) { pfR.stop.set(true); pfR.overrideStartPos = null; }
                if (exR != null) exR.stop = true;
            }
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
                Nav.cancel();
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
                // ROOT THE ROUTE WHERE THE MOVEMENTS THINK THE FEET ARE.
                // getBlockPos() is a plain floor of y, and a player standing on solid ground sits
                // at y = 132.99999... about as often as at exactly 133 -- so the route was rooted
                // one cell BELOW the feet, inside the ground. Everything downstream then makes
                // sense and still cannot work: the first edge is an ASCEND from that buried cell
                // to the real one, MovementAscend waits to be standing at a source the player will
                // never occupy, and it holds forward against the block face until the queue times
                // out and re-plans the same thing.
                // Measured on the @gamer sweep, shipped defaults: 3 distinct positions in five
                // minutes, 0 items, the same "MV {109,132,-40}->{108,133,-40} st=RUNNING
                // feet={109,133,-40} keys=F ground=true" repeating -- src and feet one apart in Y,
                // which is precisely the difference between these two functions.
                // playerFeet is baritone's own answer (IPlayerContext.java:62-81, the +0.1251 and
                // the slab correction) and it is what every ported Movement tests itself against,
                // so the search and the executor now agree on where the bot is standing.
                net.minecraft.util.math.BlockPos startB =
                        kaptainwutax.tungsten.path.movements.RotationHelper.playerFeet(mod.getPlayer());
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
                // A ONE-CELL ROUTE IS THE SEARCH SAYING "NOTHING I CAN REACH IS ANY CLOSER".
                // Measured on a failing @gamer run: 1029 of them, every one at exactly d30.0 dy0.0
                // -- the same distance to the same goal, so the bot never moved an inch, and the
                // BFS was expanding (the no-expansion diagnosis in CombatPathfinder never fired).
                // Everything the fix needs is WHERE: which cell the bot is in and which cell it is
                // being sent to. Those two positions name the situation; the distance alone does
                // not. Rate-limited, because the state repeats every tick.
                if (bfs.size() < 2 && nowMs - twLastNoRouteLogMs > 2000) {
                    twLastNoRouteLogMs = nowMs;
                    net.minecraft.util.math.BlockPos me = startB;
                    Debug.logMessage(String.format(
                            "primDrive NO ROUTE: at %d,%d,%d -> goal %d,%d,%d (d%.1f) goalTask=%s",
                            me.getX(), me.getY(), me.getZ(), goalB.getX(), goalB.getY(), goalB.getZ(),
                            distToGoal, goal));
                }
                // ── ESCALATE TO THE BUILD ENGINE WHEN THE WALKER CANNOT REACH ──
                // A grid BFS that returns <2 waypoints (or a degenerate far stub) is the walker
                // saying "nothing I can reach is any closer" — a cliff, a gap, a wall, a pit. The
                // walker/queue have no vertical build move, so no amount of retrying gets up. Hand
                // the leg to FastNavigator, which plans pillar/bridge/break/staircase via
                // FastPlanner and executes them through the physics executor (the proven ;goto
                // path that clears nav_wall2 and nav_bridge). This is the fix for the playthrough
                // stalling at any terrain that needs building (user 2026-09-10). It only fires
                // when normal walking has already failed, so reachable terrain is untouched.
                boolean noWalkRoute = bfs.size() < 2 || degenerateStub;
                if (noWalkRoute) {
                    if (twNoRouteSinceMs == 0L) twNoRouteSinceMs = nowMs;
                    boolean canBuild = kaptainwutax.tungsten.TungstenConfig.get().allowBreak
                            || (kaptainwutax.tungsten.TungstenConfig.get().planPlaceMoves && hasBuildBlock(mod));
                    if (canBuild && nowMs - twNoRouteSinceMs > 2500 && nowMs >= twFnCooldownUntilMs
                            && !kaptainwutax.tungsten.task.FastNavigator.isActive()) {
                        // Clear the walker/queue so FastNavigator owns the keys; let the executor run.
                        kaptainwutax.tungsten.task.BlockPathWalker.stop();
                        kaptainwutax.tungsten.path.movements.MovementQueue.stop();
                        if (ex != null) ex.stop = false;
                        kaptainwutax.tungsten.task.FastNavigator.start(gp);
                        twFnGoal = net.minecraft.util.math.BlockPos.ofFloored(gp);
                        twFnCooldownUntilMs = nowMs + 12000;   // give it room to build before re-deciding
                        twNoRouteSinceMs = 0L;
                        pdFnBuild++;
                        Nav.cancel();
                        checker.reset();
                        setDebugState("Tungsten: no walk route — building via FastPlanner (pillar/bridge/break)...");
                        return true;
                    }
                } else {
                    twNoRouteSinceMs = 0L;
                }
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
                            && !kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()
                            && nowMs >= twPreferQueueAfterMs) {
                        int admitted = kaptainwutax.tungsten.path.movements.MovementQueue
                                .start(bfs, true);
                        // A ROUTE THE QUEUE CAN BARELY START IS A ROUTE IT SHOULD NOT KEEP.
                        // See TungstenConfig.navQueueMinSteps: accepting a prefix of two steps out
                        // of ten denies the whole route to the walker, which sprint-jumps and could
                        // have crossed the edge that stopped the queue.
                        int minSteps = kaptainwutax.tungsten.TungstenConfig.get().navQueueMinSteps;
                        if (admitted > 0 && admitted < minSteps) {
                            kaptainwutax.tungsten.path.movements.MovementQueue.stop();
                            pdQueueTooShort++;
                            // Buy the walker a WINDOW: without it the queue is re-offered the
                            // same route next tick and the two churn at 20 restarts a second.
                            twPreferQueueAfterMs = nowMs + QUEUE_DECLINE_COOLDOWN_MS;
                            admitted = 0;
                        }
                        queuedRoute = admitted > 0;
                    }
                    if (!queuedRoute
                            && !kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()) {
                        kaptainwutax.tungsten.task.BlockPathWalker.startBFS(bfs);
                    }
                    Nav.cancel();
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
                    for (kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode n : bp.get()) {
                        net.minecraft.util.math.BlockPos bpos = n.getBlockPos();
                        // ⛔ DO NOT HAND ON A ROUTE THAT REPEATS A CELL. The movement queue types
                        // every edge and an edge from a cell to itself has no type, so it
                        // truncated the chain there and gave back every step after it. Measured on
                        // a 60-second reproduction of the navigation stall: the truncating shape
                        // was 0,0,0 on 601 of them, and EVERY ONE sat at index 1 -- the first edge
                        // of the chain, i.e. this list starting with the same block twice.
                        //
                        // Fixed here, at the assembly, rather than only guarded in the queue:
                        // a repeated cell is malformed data, and the consumer-side skip
                        // (queueSkipsNullEdges) exists to prove it and to cover any other producer.
                        if (kaptainwutax.tungsten.TungstenConfig.get().queueSkipsNullEdges
                                && !wps.isEmpty() && wps.get(wps.size() - 1).equals(bpos)) {
                            continue;
                        }
                        wps.add(bpos);
                    }
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
                            && !kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()
                            && nowMs >= twPreferQueueAfterMs) {
                        queuedRoute = kaptainwutax.tungsten.path.movements.MovementQueue
                                .start(wps, true) > 0;
                    }
                    if (!queuedRoute
                            && !kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()) {
                        kaptainwutax.tungsten.task.BlockPathWalker.startBFS(wps);
                    }
                    Nav.cancel();
                    checker.reset();
                    setDebugState("Tungsten (primary) walking (robust path)...");
                    return true;
                }
                // (3) no block path yet — kick the async search to compute one.
                boolean busy = (pf != null && pf.active.get()) || (ex != null && ex.isRunning());
                if (kaptainwutax.tungsten.task.BlockPathWalker.DEBUG)
                    Debug.logMessage("primDrive asyncKick busy" + busy);
                if (!busy && pf != null) { if (ex != null) ex.stop = false; pf.find(mod.getWorld(), gp, mod.getPlayer()); }
                Nav.cancel();
                // ⛔ DO NOT PET THE WATCHDOG FROM THE BRANCH THAT IS FAILING.
                //
                // checker.reset() stood here, and this branch by definition produced no movement --
                // it only kicked a search. The checker IS the stall detector every recovery in this
                // task hangs off, so resetting it told them all that a motionless bot was fine.
                //
                // pdPlan reads 8171/34: eight thousand planning ticks against thirty-four give-ups,
                // about seven minutes of a run. With the legacy engine gone there is no second
                // engine to pick the goal up, and the same spot now reproduces exactly -- ten
                // minutes at 1219.5,104.1,-843.5 planning a flat fourteen-block route.
                //
                // Ordinary planning is unaffected: it costs a second or two and the checker's
                // window is six.
                if (!kaptainwutax.tungsten.TungstenConfig.get().planningIsNotProgress) {
                    checker.reset();
                } else {
                    pdPlanNoReset++;
                }
                setDebugState("Tungsten (primary) planning...");
                return true;
            }
            // ⛔ THE LAST FOUR BLOCKS ARE NOT ALWAYS A WALK (G40, 2026-09-11). Inside this radius
            // the physics executor is the only driver, and it can neither dig nor climb: a goal
            // five blocks straight down through stone, or a drop 2.4 blocks away across a ledge
            // top the body is hanging off the edge of, both measured as a search every 600 ms,
            // a "Finished!" every 600 ms and a body that never moves -- for the whole window.
            // The build engine plans the same short legs the walker does AND the dig / pillar
            // when the leg needs one, so it is the engine for a near goal the physics approach
            // is not closing: at once when the goal cell cannot be stood in (it must be dug to),
            // and after 2.5 s of the body not moving otherwise.
            if (kaptainwutax.tungsten.TungstenConfig.get().nearGoalEscalatesToBuild
                    && distToGoal <= 4.0
                    && !kaptainwutax.tungsten.task.FastNavigator.isActive()
                    && nowMs >= twFnCooldownUntilMs
                    && kaptainwutax.tungsten.TungstenConfig.get().allowBreak) {
                net.minecraft.util.math.BlockPos gCell = net.minecraft.util.math.BlockPos.ofFloored(gp);
                boolean goalUnwalkable = !standable(mod.getWorld(), gCell.getX(), gCell.getY(), gCell.getZ());
                if (twNearStillPos == null || plNow.distanceTo(twNearStillPos) > 0.3) {
                    twNearStillPos = plNow;
                    twNearStillSinceMs = nowMs;
                }
                boolean stillTooLong = nowMs - twNearStillSinceMs > 2500;
                if (goalUnwalkable || stillTooLong) {
                    kaptainwutax.tungsten.task.BlockPathWalker.stop();
                    kaptainwutax.tungsten.path.movements.MovementQueue.stop();
                    if (ex != null) ex.stop = false;
                    kaptainwutax.tungsten.task.FastNavigator.start(gp);
                    twFnGoal = gCell;
                    twFnCooldownUntilMs = nowMs + 12000;
                    twNearStillPos = null;
                    pdNearBuild++;
                    pdFnBuild++;
                    Nav.cancel();
                    checker.reset();
                    setDebugState(goalUnwalkable
                            ? "Tungsten: goal cell is not standable — digging/building to it via FastPlanner..."
                            : "Tungsten: near goal not closing — FastPlanner takes the last steps...");
                    return true;
                }
            } else {
                twNearStillPos = null;
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
        Nav.cancel();
        checker.reset();
        setDebugState("Tungsten (primary) pathfinding...");
        // PLANNING THAT NEVER BECOMES A ROUTE IS NOT DRIVING, AND MUST NOT HOLD THE TICK.
        // Returning true here claims ownership of movement. A stall capture shows what that costs
        // when the claim is empty: pdEnter=7478 with pdWalking=0, pdNear=26, dbTick=7521 and
        // rayMiss=7072 -- the bot standing 8.5 blocks from the block it wants, asking to move 29
        // times, and never moving, because this branch reported "I am driving" on every one of
        // those ticks while the movement queue never started.
        // So bound it: if we have been planning this long with no chain ever running and the body
        // not moving, hand the tick back and let something else try.
        pdPlanning++;
        boolean queueRunning = kaptainwutax.tungsten.path.movements.MovementQueue.isRunning();
        net.minecraft.util.math.BlockPos hereNow = mod.getPlayer().getBlockPos();
        if (queueRunning || !hereNow.equals(twPlanFeet)) {
            twPlanFeet = hereNow;
            twPlanSinceMs = nowMs;
        } else if (twPlanSinceMs != 0L && nowMs - twPlanSinceMs > PLAN_GIVE_UP_MS) {
            pdPlanGiveUp++;
            twPlanSinceMs = 0L;
            twPlanFeet = null;
            return false;   // not driving; let the caller fall back
        } else if (twPlanSinceMs == 0L) {
            twPlanSinceMs = nowMs;
            twPlanFeet = hereNow;
        }
        return true;
    }

    // goalToVec REMOVED (G-0): it was the instanceof ladder over six legacy goal classes
    // that existed purely to recover a point. AltoGoal.target() is that point.


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
            if (isSolidAt(w, gx, gy, gz)) {
                // A BLOCK TO BE MINED IS REACHED FROM BESIDE IT, NOT FROM ON TOP OF IT.
                // Going up the column first is right for "stand on this surface" and wrong for
                // every mining target: a log's first standable cell above it is the top of the
                // TREE, so the drive was sent to an air cell in the canopy that nothing can route
                // to. Measured on a failing @gamer run, printed by the drive itself:
                //   NO ROUTE: at 90,135,-36 -> goal 84,140,-39  goalTask=block(84,136,-39)
                //   NO ROUTE: at -3308,150,-3239 -> goal -3290,101,-3230 goalTask=block(-3290,99,-3230)
                // -- the task asked for a block and the drive aimed four (and two) blocks above it,
                // then reported a one-cell route 1029 times without the bot moving an inch.
                // Standing beside the block is both reachable and the position mining needs, so the
                // neighbours come first; the column search stays as the fallback for a goal that
                // really is a surface to stand on.
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    for (int dy = 0; dy >= -1; dy--) {
                        if (standable(w, gx + d[0], gy + dy, gz + d[1])) {
                            return new net.minecraft.util.math.Vec3d(
                                    gx + d[0] + 0.5, gy + dy, gz + d[1] + 0.5);
                        }
                    }
                }
                for (int y = gy + 1; y <= gy + 5; y++)               // no neighbour: stand on top
                    if (standable(w, gx, y, gz)) return new net.minecraft.util.math.Vec3d(gx + 0.5, y, gz + 0.5);
            }
            for (int y = gy; y >= gy - 6; y--)                       // floating goal → drop to the ground
                if (standable(w, gx, y, gz)) return new net.minecraft.util.math.Vec3d(gx + 0.5, y, gz + 0.5);
        } catch (Throwable ignored) { }
        return gp;
    }

    /**
     * Reach-route anatomy: ticks the drive armed FastNavigator for a block, and ticks it held off
     * re-arming because the navigator had just given the route up. Read as pdReach=armed/held.
     */
    public static volatile int pdReachArmed, pdReachHeld;
    private long twReachRearmAtMs = 0L;

    /** G40: snaps refused because they landed on the bot's own cell, and near-goal ticks handed
     *  to the build engine because the physics approach was not closing. Read as
     *  snapSelfRefused / pdNearBuild. */
    public static volatile int snapRefusedSelf, pdNearBuild;
    /** G40: navigator routes armed by an earlier task instance that this one adopted (same goal)
     *  or stopped (stale goal). Read as pdFnOrphan=adopted/stale. */
    public static volatile int pdFnAdopted, pdFnStale;
    /** G52: routes (navigator / walker / queue / tower / bridge / swim-out) still running when the
     *  drive that owned them stopped and no other drive took over -- stopped with it. */
    public static volatile int pdRouteStopped;
    private net.minecraft.util.math.Vec3d twNearStillPos = null;
    private long twNearStillSinceMs = 0L;

    /** The goal the drive last steered at, and when -- read by PlannedEscape. */
    public static volatile net.minecraft.util.math.Vec3d lastGoalVec = null;
    public static volatile long lastGoalAtMs = 0L;
    /** The block a REACH goal was for (null for a position goal), so an escape re-armed at the
     *  live goal keeps the reach semantics instead of asking to stand inside the ore. */
    public static volatile net.minecraft.util.math.BlockPos lastGoalReachBlock = null;

    /**
     * Drive a REACH goal: get the FEET next to a block (baritone's GoalGetToBlock), digging if
     * that is what it takes. The one engine that can dig is FastNavigator's planner, so the block
     * goes straight there -- no snap, no grid BFS, no escalation ladder. Arrival is the goal's own
     * adjacency test, or the miner's: the block can be struck from here.
     *
     * <p>Returns true while the navigator owns the tick, false when there is nothing left to walk.
     */
    private boolean driveReach(AltoClef mod, adris.altoclef.util.goals.AltoGoal.Adjacent adj,
                               net.minecraft.util.math.Vec3d gp) {
        net.minecraft.util.math.BlockPos block = adj.pos();
        net.minecraft.util.math.BlockPos feet =
                kaptainwutax.tungsten.path.movements.RotationHelper.playerFeet(mod.getPlayer());
        boolean armedForThis = kaptainwutax.tungsten.task.FastNavigator.isActive()
                && block.equals(kaptainwutax.tungsten.task.FastNavigator.reachBlock());
        if (adj.reached(feet) || adris.altoclef.util.helpers.LookHelper.getReach(block).isPresent()) {
            if (armedForThis) kaptainwutax.tungsten.task.FastNavigator.stop();
            pdFinished++;
            return false;
        }
        long nowMs = System.currentTimeMillis();
        if (!armedForThis) {
            // The navigator gives a route up on its own watchdog ("no progress, handing over");
            // re-arming on the very next tick would spin that watchdog at 20 Hz. A short hold
            // lets the world settle (a block just mined, a fall just landed) before the next plan.
            if (nowMs < twReachRearmAtMs) {
                pdReachHeld++;
                checker.reset();
                setDebugState("Tungsten: reach route gave up — re-planning shortly");
                return true;
            }
            kaptainwutax.tungsten.task.BlockPathWalker.stop();
            kaptainwutax.tungsten.path.movements.MovementQueue.stop();
            var exR = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            if (exR != null) exR.stop = false;
            kaptainwutax.tungsten.task.FastNavigator.start(gp, block);
            twFnGoal = net.minecraft.util.math.BlockPos.ofFloored(gp);
            twReachRearmAtMs = nowMs + 2500;
            pdReachArmed++;
            pdFnBuild++;
        }
        checker.reset();
        setDebugState("Tungsten: reaching " + block.toShortString()
                + " via FastPlanner (dig allowed)...");
        return true;
    }

    /** G55: block goals whose solid cell was handed to the navigator as an exact cell to dig into,
     *  and ticks the re-arm was held after the navigator gave such a route up. */
    public static volatile int pdDigArmed, pdDigHeld;

    /** G55: a block goal that must be DUG INTO -- solid, breakable, not a container or another
     *  block entity (those are interacted with, never mined on the way to them). */
    private static boolean diggableGoalCell(AltoClef mod, net.minecraft.util.math.BlockPos cell) {
        try {
            net.minecraft.world.World w = mod.getWorld();
            net.minecraft.block.BlockState st = w.getBlockState(cell);
            if (st.getCollisionShape(w, cell).isEmpty()) return false;     // standable or air: walk
            if (st.hasBlockEntity()) return false;                          // chest, table, furnace
            if (st.getHardness(w, cell) < 0) return false;                  // bedrock and kin
            return kaptainwutax.tungsten.path.BreakRules.canBreak(w, cell, st);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Drive a BLOCK goal whose cell is solid: get the FEET into that cell by digging (baritone's
     * GoalBlock on rock). Same shape as {@link #driveReach}: straight to FastNavigator as an exact
     * cell -- no snap, no grid BFS -- and arrival is the exact cell, the same test isFinished uses.
     */
    private boolean driveDig(AltoClef mod, net.minecraft.util.math.BlockPos cell) {
        net.minecraft.util.math.BlockPos feet =
                kaptainwutax.tungsten.path.movements.RotationHelper.playerFeet(mod.getPlayer());
        boolean armedForThis = kaptainwutax.tungsten.task.FastNavigator.isActive()
                && cell.equals(kaptainwutax.tungsten.task.FastNavigator.driveExactCell());
        if (feet.equals(cell)) {
            if (armedForThis) kaptainwutax.tungsten.task.FastNavigator.stop();
            pdFinished++;
            return false;
        }
        long nowMs = System.currentTimeMillis();
        if (!armedForThis) {
            if (nowMs < twReachRearmAtMs) {
                pdDigHeld++;
                checker.reset();
                setDebugState("Tungsten: dig route gave up — re-planning shortly");
                return true;
            }
            kaptainwutax.tungsten.task.BlockPathWalker.stop();
            kaptainwutax.tungsten.path.movements.MovementQueue.stop();
            var exR = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            if (exR != null) exR.stop = false;
            kaptainwutax.tungsten.task.FastNavigator.startExactForDrive(cell);
            twFnGoal = cell;
            twReachRearmAtMs = nowMs + 2500;
            pdDigArmed++;
            pdFnBuild++;
        }
        checker.reset();
        setDebugState("Tungsten: digging into " + cell.toShortString()
                + " (a solid block goal) via FastPlanner...");
        return true;
    }

    /** Shared with TimeoutWanderTask, which needs the same question about its wander target. */
    public static boolean standable(net.minecraft.world.World w, int x, int y, int z) {
        return isSolidAt(w, x, y - 1, z) && !isSolidAt(w, x, y, z) && !isSolidAt(w, x, y + 1, z);
    }

    private static boolean isSolidAt(net.minecraft.world.World w, int x, int y, int z) {
        net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(x, y, z);
        return !w.getBlockState(p).getCollisionShape(w, p).isEmpty();
    }
}
