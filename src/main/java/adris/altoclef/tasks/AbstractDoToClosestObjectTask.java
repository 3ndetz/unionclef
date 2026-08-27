package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Optional;

/**
 * Use this whenever you want to travel to a target position that may change.
 * <p>
 * https://www.notion.so/Closest-threshold-ing-system-utility-c3816b880402494ba9209c9f9b62b8bf
 */
public abstract class AbstractDoToClosestObjectTask<T> extends Task {

    /**
     * WHICH BRANCH OF THE PURSUIT DECISION ACTUALLY RUNS. Read as dcNew/dcRetry/dcHold/dcWander.
     *
     * <p>Two fixes with provably correct mechanisms -- a climb priced as a stair that is not there,
     * and a comparison that ran on infinity -- moved nothing measurable on the wood rung, which
     * means the dominant blocker is elsewhere and guessing again would be a third guess. A run
     * beginning in 184 log blocks within forty reaches nothing in five minutes while ciCollect
     * spins 4399 times and the drive is entered 94 times; whether that is this task changing its
     * mind every tick or never getting one is exactly what these four numbers say, and nothing
     * currently said it.
     *
     * <p>FIRST READING, and it refutes the thrash: dc=493/14/4/0/0 over a five-minute run. This
     * task ticked 493 times out of roughly 6000, changed its mind 14 times and retried an old
     * target 4 times -- that is a bot pursuing steadily, not one dithering. The time is going
     * somewhere ABOVE here: CraftInInventoryTask ticked 4192 times, 2336 of them in its
     * collect-materials branch, and only 493 of those reached the mining task at all. That gap,
     * not the choice of block, is where the wood rung is being lost.
     */
    public static volatile int dcNewPursuit, dcRetryOld, dcHold, dcWander, dcTick;

    private final HashMap<T, CachedHeuristic> heuristicMap = new HashMap<>();
    private T currentlyPursuing = null;
    private boolean wasWandering;
    private Task goalTask = null;

    protected abstract Vec3d getPos(AltoClef mod, T obj);

    protected abstract Optional<T> getClosestTo(AltoClef mod, Vec3d pos);

    protected abstract Vec3d getOriginPos(AltoClef mod);

    protected abstract Task getGoalTask(T obj);

    protected abstract boolean isValid(AltoClef mod, T obj);

    // Virtual
    protected Task getWanderTask(AltoClef mod) {
        return new TimeoutWanderTask(true);
    }

    public void resetSearch() {
        currentlyPursuing = null;
        heuristicMap.clear();
        goalTask = null;
    }

    public boolean wasWandering() {
        return wasWandering;
    }

    /**
     * How expensive the thing being pursued still looks, in ticks.
     *
     * <h2>This asked the wrong engine, and the answer was always infinity</h2>
     *
     * It used to be {@code getPathingBehavior().ticksRemainingInSegment()} — BARITONE's estimate of
     * the path it is walking. Tungsten drives now, so baritone is never pathing, the Optional is
     * always empty, and this returned POSITIVE_INFINITY on every call of every tick.
     *
     * <p>That is not a cosmetic staleness. The whole switch-target decision below is a comparison
     * of this number between the thing being pursued and a candidate, and infinity compares equal
     * to infinity — so the only branch that could ever fire was "this candidate has never been
     * tried, take it". In a dark oak forest the scanner's nearest log changes as the bot walks, so
     * the bot took a new target, built a new goal task for it, walked a step, took another, and
     * felled nothing. Measured with the start point finally recorded: a run beginning in 170 log
     * blocks within forty reached NOTHING in five minutes, with pdEnter=94 ticks of navigation goal
     * in the whole run and ciCollect spinning 4399 times.
     *
     * <p>The estimate does not need a pathfinder: it is the same cost model the block scanner uses
     * to rank candidates in the first place, measured from where the bot is now to where the thing
     * is. It is in ticks, it is comparable between candidates, and it exists whichever engine is
     * driving — which is also one less call into baritone (G-0).
     */
    private double getCurrentCalculatedHeuristic(AltoClef mod) {
        if (currentlyPursuing == null || mod.getPlayer() == null) {
            return Double.POSITIVE_INFINITY;
        }
        return BaritoneHelper.calculateGenericHeuristic(
                mod.getPlayer().getPos(), getPos(mod, currentlyPursuing));
    }

    @Override
    protected Task onTick() {
        wasWandering = false;
        dcTick++;
        AltoClef mod = AltoClef.getInstance();

        // Reset our pursuit if our pursuing object no longer is pursuable.
        if (currentlyPursuing != null && !isValid(mod, currentlyPursuing)) {
            // This is probably a good idea, no?
            heuristicMap.remove(currentlyPursuing);
            currentlyPursuing = null;
        }

        // Get closest object
        Optional<T> checkNewClosest = getClosestTo(mod, getOriginPos(mod));
        // 2848 ticks against 3 pursuits, and every counted branch at zero: the loop spends
        // its life on the UNCOUNTED path. Two states share it and they mean opposite things --
        // nothing found at all, or the closest thing IS the one already being chased. The
        // second is a bot pursuing one drop for the whole run (dropPick showed a single
        // cobblestone two blocks down, deepPicks=722). Split them before touching anything.
        if (checkNewClosest.isEmpty()) dcNone++;
        else if (checkNewClosest.get().equals(currentlyPursuing)) dcSame++;
        // IS THE CHASE PRODUCTIVE? dc owns 76% of the main chain's ticks and dcSame owns
        // almost all of dc, so 'pursuing the same target' IS the run. Dead time sits at a
        // median near a third, and the question is whether those ticks are a bot travelling
        // toward its target or a bot standing next to it. Split them by the only honest
        // signal: did the BODY move since the previous tick.
        {
            net.minecraft.util.math.Vec3d me = mod.getPlayer().getPos();
            if (dcLastPos != null) {
                if (me.squaredDistanceTo(dcLastPos) > 0.0025D) dcMoving++; else dcStill++;
            }
            dcLastPos = me;
        }

        // A PURSUIT THAT NEVER CLOSES MUST END. dcSame=12070 of 12145 ticks: the bot chased
        // ONE target for a whole ten-minute run -- a single cobblestone two blocks down, the
        // only candidate -- and this chooser has no give-up at all. The budget that fixed the
        // opening lives in PickupDroppedItemTask and does not cover this path (dropBudget=0
        // while the bot stood here). Same principle as there: the clock belongs to the TARGET.
        if (kaptainwutax.tungsten.TungstenConfig.get().closestPursuitHasBudget
                && currentlyPursuing != null) {
            double dSq = getPos(mod, currentlyPursuing).squaredDistanceTo(mod.getPlayer().getPos());
            long now = System.currentTimeMillis();
            // SAME PURSUIT, NOT THE SAME OBJECT. Comparing by identity restarts the clock
            // every time the tracker hands back a fresh object for the same physical drop,
            // so the budget never accrues: measured same9011/gave0 -- nine thousand ticks on
            // one target and not a single give-up. The pickup budget had exactly this bug
            // and was fixed the same way. Judge by WHERE the target is, not which object
            // it is.
            net.minecraft.util.math.Vec3d hereNow = getPos(mod, currentlyPursuing);
            boolean samePursuit = budgetTargetPos != null
                    && hereNow.squaredDistanceTo(budgetTargetPos) <= SAME_PURSUIT_SQ;
            if (!samePursuit) {
                // WHY DOES THE CLOCK KEEP RESTARTING? gave reads 3 in one sweep and 0 in
                // the next while same~10000 in both, so the ceiling is being reset rather
                // than reached. Count the resets and how far the target appeared to jump,
                // because 'the target moved' and 'a different target' want different fixes.
                if (budgetTargetPos != null) {
                    dcClockReset++;
                    dcResetJumpCm = (int) Math.min(Math.sqrt(
                            hereNow.squaredDistanceTo(budgetTargetPos)) * 100.0, 2_000_000_000.0);
                }
                budgetTargetPos = hereNow;
                budgetStartMs = now;
                budgetHardStartMs = now;
                budgetBestSq = dSq;
            // STANDING STILL IS NOT A SLOW CHASE, IT IS A STUCK ONE.
            // Measured: of 10445 chooser ticks the body moved on 248 and stood on 10196 --
            // 98% -- while dc owns 76% of the whole run. The three-minute ceiling does fire
            // (gave=4) but only after three minutes are already spent standing.
            // Standing IS legitimate while mining the target, so exclude that with the same
            // break clock that separated digging from stranded for UnstuckChain.
            boolean bodyMoved = dcLastPos == null
                    || mod.getPlayer().getPos().squaredDistanceTo(dcLastPos) > 0.0025D;
            boolean breaking = adris.altoclef.control.PlayerExtraController.lastBreakProgressMs > 0
                    && now - adris.altoclef.control.PlayerExtraController.lastBreakProgressMs < 2000L;
            // WITHIN REACH IS NOT IDLE. Approach, aim, swing is a legitimate stationary
            // sequence, and the first attempt at this rule snatched targets away mid-swing:
            // craft fell 22/22 -> 20/22 with mine_stone ending on 3 and 7 cobblestone of 8.
            // A bot standing FAR from its target is the stuck case; standing AT it is work.
            boolean withinReach = dSq <= REACH_SQ;
            if (bodyMoved || breaking || withinReach) {
                budgetIdleSinceMs = now;
            } else if (CLOSEST_PURSUIT_IDLE_MS > 0
                    && now - budgetIdleSinceMs > CLOSEST_PURSUIT_IDLE_MS
                    && budgetIdleSinceMs > 0) {
                dcGaveUpIdle++;
                heuristicMap.remove(currentlyPursuing);
                markUnreachable(mod, currentlyPursuing);
                currentlyPursuing = null;
                budgetTargetPos = null;
                budgetIdleSinceMs = 0L;
                return null;
            }
            } else if (dSq < budgetBestSq - 0.25) {
                // real progress toward it -- the clock earns a restart
                budgetBestSq = dSq;
                budgetStartMs = now;
            }
            // A HARD CEILING THE PROGRESS RULE CANNOT RESET. Letting 'got closer than ever'
            // restart the clock is too generous: a bot circling a drop keeps setting new
            // bests, so the budget never accrued -- same9101/gave0 even with the clock made
            // static. Three minutes on ONE target is enough for any drop worth having.
            if (now - budgetHardStartMs > CLOSEST_PURSUIT_HARD_MS
                    || now - budgetStartMs > CLOSEST_PURSUIT_BUDGET_MS) {
                dcGaveUp++;
                heuristicMap.remove(currentlyPursuing);
                markUnreachable(mod, currentlyPursuing);
                currentlyPursuing = null;
                budgetTargetPos = null;
                return null;
            }
        }

        // Receive closest object and position
        if (checkNewClosest.isPresent() && !checkNewClosest.get().equals(currentlyPursuing)) {
            T newClosest = checkNewClosest.get();
            // Different closest object
            if (currentlyPursuing == null) {
                // We don't have a closest object
                currentlyPursuing = newClosest;
            } else {
                if (goalTask != null /*isMovingToClosestPos(mod)*/) {
                    setDebugState("Moving towards closest...");
                    double currentHeuristic = getCurrentCalculatedHeuristic(mod);
                    double closestDistanceSqr = getPos(mod, currentlyPursuing).squaredDistanceTo(mod.getPlayer().getPos());
                    int lastTick = WorldHelper.getTicks();

                    if (!heuristicMap.containsKey(currentlyPursuing)) {
                        heuristicMap.put(currentlyPursuing, new CachedHeuristic());
                    }
                    CachedHeuristic h = heuristicMap.get(currentlyPursuing);
                    h.updateHeuristic(currentHeuristic);
                    h.updateDistance(closestDistanceSqr);
                    h.setTickAttempted(lastTick);
                    if (heuristicMap.containsKey(newClosest)) {
                        // Our new object has a past potential heuristic calculated, if it's better try it out.
                        CachedHeuristic maybeReAttempt = heuristicMap.get(newClosest);
                        double maybeClosestDistance = getPos(mod, newClosest).squaredDistanceTo(mod.getPlayer().getPos());
                        // Get considerably closer (divide distance by 2)
                        if (maybeReAttempt.getHeuristicValue() < h.getHeuristicValue() || maybeClosestDistance < maybeReAttempt.getClosestDistanceSqr() / 4) {
                            setDebugState("Retrying old heuristic!");
                            dcRetryOld++;
                            // The currently closest previously calculated heuristic is better, move towards it!
                            currentlyPursuing = newClosest;
                            // In theory, this next line shouldn't need to be run,
                            // but it's CRITICAL to making this work for some reason
                            maybeReAttempt.updateDistance(maybeClosestDistance);
                        }
                    } else {
                        setDebugState("Trying out NEW pursuit");
                        dcNewPursuit++;
                        // Our new object does not have a heuristic, TRY IT OUT!
                        currentlyPursuing = newClosest;
                    }
                } else {
                    setDebugState("Waiting for move task to kick in...");
                    dcHold++;
                    // We should keep moving towards our object until we get some new info.
                }
            }
        }

        if (currentlyPursuing != null) {
            goalTask = getGoalTask(currentlyPursuing);
            return goalTask;
        } else {
            goalTask = null;
        }


        if (checkNewClosest.isEmpty()) {
            setDebugState("Waiting for calculations I think (wandering)");
            dcWander++;
            wasWandering = true;
            return getWanderTask(mod);
        }

        setDebugState("Waiting for calculations I think (NOT wandering)");
        return null;
    }

    private static class CachedHeuristic {

        private double _closestDistanceSqr;
        private int _tickAttempted;
        private double _heuristicValue;

        public CachedHeuristic() {
            _closestDistanceSqr = Double.POSITIVE_INFINITY;
            _heuristicValue = Double.POSITIVE_INFINITY;
        }

        public CachedHeuristic(double closestDistanceSqr, int tickAttempted, double heuristicValue) {
            _closestDistanceSqr = closestDistanceSqr;
            _tickAttempted = tickAttempted;
            _heuristicValue = heuristicValue;
        }

        public double getHeuristicValue() {
            return _heuristicValue;
        }

        public void updateHeuristic(double heuristicValue) {
            _heuristicValue = Math.min(_heuristicValue, heuristicValue);
        }

        public double getClosestDistanceSqr() {
            return _closestDistanceSqr;
        }

        public void updateDistance(double closestDistanceSqr) {
            _closestDistanceSqr = Math.min(_closestDistanceSqr, closestDistanceSqr);
        }

        public int getTickAttempted() {
            return _tickAttempted;
        }

        public void setTickAttempted(int tickAttempted) {
            _tickAttempted = tickAttempted;
        }
    }

    /** The two uncounted states of the loop: nothing found, or the same target again. */
    public static volatile int dcNone, dcSame;

    /** Target the give-up clock is running for, when it started, and the closest it has been. */
    // THE CLOCK OUTLIVES THE TASK -- THIS IS THE THIRD TIME THIS TRAP HAS BEEN SPRUNG.
    // These were instance fields, and this task is rebuilt constantly, so every rebuild
    // reset the budget and it never accrued: measured same9874/gave0 -- nine thousand
    // ticks on one target and not one give-up. PickupDroppedItemTask had exactly this bug
    // (its clock now lives on the target and fixed the opening, 44.2 s -> 21.8 s median),
    // and so did the pursuit identity below. Static, keyed by WHERE the target is.
    private static net.minecraft.util.math.Vec3d budgetTargetPos = null;
    private static long budgetStartMs = 0L;
    /** Set only when the TARGET changes -- progress cannot push this one back. */
    private static long budgetHardStartMs = 0L;
    /** When the body last moved or a break progressed while pursuing. */
    private static long budgetIdleSinceMs = 0L;
    private static double budgetBestSq = Double.MAX_VALUE;
    /** Two minutes without closing on the target -- generous, and still finite. */
    private static final long CLOSEST_PURSUIT_BUDGET_MS = 120_000L;
    /** Total time allowed on one target, progress or not. */
    private static final long CLOSEST_PURSUIT_HARD_MS = 180_000L;
    /** Standing still and not breaking anything for this long means the target is unreachable. */
    /**
     * OFF (0) -- MEASURED, AND IT COSTS MINING.
     *
     * <p>The diagnosis is solid: of 10445 chooser ticks the body moved on 248 and stood on
     * 10196 (98%), while the chooser owns 76% of a run. Standing still IS where the dead
     * time lives, and the three-minute ceiling only rescues it after three minutes are
     * already spent.
     *
     * <p>But giving up after twenty idle seconds took craft from 22/22 to 20/22:
     * mine_stone finished with cobblestone=3 and 7 against the eight it needs. The reason
     * is the gap this rule cannot see: a bot that has ARRIVED at a block and is aiming at
     * it stands still and has not started breaking yet, so the break clock is empty and
     * the rule snatches its target away.
     *
     * <p>Set a value again only together with an exclusion for 'the target is within
     * mining reach' -- approach, aim and swing is a legitimate stationary sequence and
     * twenty seconds is shorter than it. Kept as a named constant so the next attempt
     * starts from the measurement rather than the idea.
     */
    private static final long CLOSEST_PURSUIT_IDLE_MS = 30_000L;
    /** Mining reach, squared -- inside it, standing still is working, not stalling. */
    private static final double REACH_SQ = 4.5D * 4.5D;
    /**
     * How far a target may move and still be the SAME pursuit.
     *
     * <p>Half a block was too tight: a dropped item DRIFTS. Measured at a real stall,
     * rst114@82cm -- the clock was reset 114 times and the last jump that did it was 82 cm,
     * so neither give-up ever accrued (pursuit=15/0s, dropBudget=0) while the bot chased a
     * crafting table one block above it. Two blocks still separates 'this drop' from 'a
     * different drop' -- a grid step to the next block reads 100 cm and rightly resets.
     */
    private static final double SAME_PURSUIT_SQ = 4.0D;
    /** Pursuits abandoned because they never closed. */
    public static volatile int dcGaveUp;
    /** Clock restarts, and how far the target appeared to move on the last one. */
    public static volatile int dcClockReset, dcResetJumpCm;
    /** Chooser ticks where the body moved, and where it did not. */
    public static volatile int dcMoving, dcStill;
    /** Pursuits abandoned because the body stood still and broke nothing. */
    public static volatile int dcGaveUpIdle;
    private static net.minecraft.util.math.Vec3d dcLastPos = null;

    /** Tell the trackers this target is not worth chasing; overridden where a tracker exists. */
    protected void markUnreachable(AltoClef mod, T obj) { }
}
