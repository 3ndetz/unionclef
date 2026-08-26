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

        // A PURSUIT THAT NEVER CLOSES MUST END. dcSame=12070 of 12145 ticks: the bot chased
        // ONE target for a whole ten-minute run -- a single cobblestone two blocks down, the
        // only candidate -- and this chooser has no give-up at all. The budget that fixed the
        // opening lives in PickupDroppedItemTask and does not cover this path (dropBudget=0
        // while the bot stood here). Same principle as there: the clock belongs to the TARGET.
        if (kaptainwutax.tungsten.TungstenConfig.get().closestPursuitHasBudget
                && currentlyPursuing != null) {
            double dSq = getPos(mod, currentlyPursuing).squaredDistanceTo(mod.getPlayer().getPos());
            long now = System.currentTimeMillis();
            if (!currentlyPursuing.equals(budgetTarget)) {
                budgetTarget = currentlyPursuing;
                budgetStartMs = now;
                budgetBestSq = dSq;
            } else if (dSq < budgetBestSq - 0.25) {
                // real progress toward it -- the clock earns a restart
                budgetBestSq = dSq;
                budgetStartMs = now;
            } else if (now - budgetStartMs > CLOSEST_PURSUIT_BUDGET_MS) {
                dcGaveUp++;
                heuristicMap.remove(currentlyPursuing);
                markUnreachable(mod, currentlyPursuing);
                currentlyPursuing = null;
                budgetTarget = null;
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
    private T budgetTarget = null;
    private long budgetStartMs = 0L;
    private double budgetBestSq = Double.MAX_VALUE;
    /** Two minutes without closing on the target -- generous, and still finite. */
    private static final long CLOSEST_PURSUIT_BUDGET_MS = 120_000L;
    /** Pursuits abandoned because they never closed. */
    public static volatile int dcGaveUp;

    /** Tell the trackers this target is not worth chasing; overridden where a tracker exists. */
    protected void markUnreachable(AltoClef mod, T obj) { }
}
