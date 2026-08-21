package kaptainwutax.tungsten.path.movements;

import java.util.ArrayList;
import java.util.List;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.util.WindMouseRotation;
import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldView;

/**
 * What the planner hands over: an ORDERED chain of {@link Movement}s, of which exactly one is
 * ticked per client tick. Port of the node-advance half of
 * {@code baritone/src/main/java/baritone/behavior/PathingBehavior.java} +
 * {@code baritone/src/main/java/baritone/pathing/path/PathExecutor.java} (onTick at :106-232),
 * reduced to what a chain of {@link MovementTraverse} needs — BARITONE-PORT-SPEC.md unit 2,
 * {@code movements/MovementQueue.java}.
 *
 * <p><b>Why this class is the point of the whole port.</b> Tungsten's bridge used to be two
 * components: {@code BlockPathWalker} moved the body, {@code PathExecutor.tickPlacing} owned the aim
 * and the click, and they negotiated through a shared flag. The seam measured
 * {@code called=11041 inRange=11040 clicked=0} (spec pitfall P1) — eleven thousand ticks in range,
 * zero clicks, because the tick in which the aim was right was never the tick in which the body was.
 * Here ONE object owns the whole step: {@link Movement#update()} is the only thing that writes keys
 * or the camera target, and it decides walk-vs-break-vs-side-place-vs-sneak-backplace itself, every
 * tick, from world state. Whatever ticks this queue MUST therefore suppress every other per-tick
 * input writer for that tick — that is the caller's half of the contract (MixinClientPlayerEntity
 * skips {@code BlockPathWalker.tick} and {@code EXECUTOR.tick} while {@link #isRunning()}).
 *
 * <h2>Ported behaviours, and why each one is load-bearing</h2>
 * <ul>
 *   <li><b>SUCCESS advances and re-enters in the SAME tick</b> (PathExecutor.java:214-219, which
 *       recurses into {@code onTick()}). Without it every step boundary costs a tick in which no key
 *       is pressed — at the stand's measured 10 fps that is a visible stutter per block, and on a
 *       sneak-bridge a released SNEAK for one tick is a fall.</li>
 *   <li><b>The snap loops</b> (PathExecutor.java:126-146): if the feet are not in the current
 *       movement's valid positions, look for a LATER movement that contains them (overshoot: the bot
 *       was carried two cells) and then for an EARLIER one (knockback, lag teleport). Upstream scans
 *       backwards first; both directions are copied because either one alone deadlocks the chain.</li>
 *   <li><b>The timeout is upstream's, not a watchdog</b> (PathExecutor.java:159-166): a movement is
 *       cancelled once it has run longer than its own cost estimate plus
 *       {@code movementTimeoutTicks = 100} (Settings.java:340). It is a per-movement budget derived
 *       from the price the planner paid, which is why it is not a reactive hack.</li>
 * </ul>
 *
 * <h2>Divergences, recorded not faked</h2>
 * <ul>
 *   <li>Upstream re-uses the path's own cost estimate. Here the estimate comes from
 *       {@link Movement#getCost(net.minecraft.world.WorldView, ClientPlayerEntity)} at start time,
 *       and it is CLAMPED to {@link #MAX_COST_ESTIMATE}: tungsten's planner, not
 *       {@code MovementTraverse.cost}, decided this edge was possible, so the movement's own price
 *       may be {@code COST_INF} for a cell whose floor the route is about to lay. An unclamped
 *       {@code COST_INF + 100} is a movement that never times out.</li>
 *   <li>No {@code PathingBehavior} means no recalculation from inside the queue. On
 *       {@code UNREACHABLE}/{@code FAILED}/timeout the queue simply stops; {@code FastNavigator}
 *       sees it go idle and replans from the bot's real position, which is the same closed loop one
 *       level up.</li>
 *   <li>Only {@link MovementTraverse} edges are accepted ({@link #traversePrefix}). Every other edge
 *       kind — parkour, ladder, slime, diagonal, pillar — keeps its current path, which is what
 *       makes this port incapable of regressing those courses by construction (spec unit 2).</li>
 * </ul>
 */
public final class MovementQueue {

    /**
     * {@code Baritone.settings().movementTimeoutTicks} (Settings.java:340) — default 100.
     */
    private static final int MOVEMENT_TIMEOUT_TICKS = 100;

    /**
     * Ceiling on the cost estimate the timeout is built from. See the divergence note above: a
     * bridge step is priced against a floor that does not exist yet, so its own
     * {@code MovementTraverse.cost} can be {@code COST_INF}. 60 ticks is three seconds at 20 tps,
     * comfortably above the ~35-tick price of a real backplace.
     */
    private static final double MAX_COST_ESTIMATE = 60.0;

    /**
     * How many times {@link #tick} may advance within one client tick. Upstream recurses into
     * {@code onTick()} and is bounded by the path length; this is the same bound made explicit so a
     * movement that reports SUCCESS without the feet ever moving cannot spin.
     */
    private static final int MAX_ADVANCES_PER_TICK = 8;

    // ---------------------------------------------------------------------------------------
    // Telemetry. Read over py4j as `placeStats` — the counters that told us the split path was
    // broken (clicked=0) have to keep telling us the truth about the replacement.
    // ---------------------------------------------------------------------------------------
    public static volatile int qStarted, qSteps, qSuccess, qUnreachable, qTimeout, qTicks;
    /** Steps completed while the body has not left the cell it started the chain in. A route that
     *  is being consumed rather than walked shows up here and nowhere else. */
    public static volatile int qBurnedInPlace;
    /**
     * WHY the queue handed the body back, split three ways. {@code qUnreachable} lumped them
     * together and the lump was unreadable: a chase measured 481 starts for 53 steps, and "454
     * hand-backs" cannot tell you whether the chain was aimed at the wrong cell to begin with,
     * whether a movement declared itself impossible, or whether the body drifted off the route.
     * Those have three different fixes, so they get three counters.
     */
    public static volatile int qLost, qStatusFail, qRefused;
    /** Edges dropped because no movement class matches their shape (a running jump, today). */
    public static volatile int qNoClass;

    /** Zero-length route edges stepped over; reads 0 with queueSkipsNullEdges off. */
    public static volatile int qNullEdge;

    /** Signed dx,dy,dz of every edge truncated for want of a movement class, tallied by shape. */
    private static final java.util.Map<String, Integer> noClassShapes =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    /** The truncating shapes this run, commonest first -- the queue's own to-do list. */
    public static String noClassShapes() {
        synchronized (noClassShapes) {
            return noClassShapes.entrySet().stream()
                    .sorted((x, y) -> y.getValue() - x.getValue())
                    .limit(8)
                    .map(e -> e.getKey() + "x" + e.getValue())
                    .reduce((x, y) -> x + " " + y).orElse("(none)");
        }
    }

    public static void clearNoClassShapes() {
        synchronized (noClassShapes) {
            noClassShapes.clear();
        }
    }

    /** Parkour edges DISPATCHED as a running jump. Read as the 4th of mq's parkour triple. */
    public static volatile int qParkour;

    /** Edges DISPATCHED that admission would have refused. Any non-zero value is a contradiction. */
    public static volatile int qAdmitMismatch;

    /** Ticks the head movement spent PREPPING (pressing nothing) against RUNNING. Read as qPrep. */
    public static volatile int qPrepTicks, qRunTicks;
    /** {@link #qRefused} split by cause: the route was shorter than two cells, or the vetting
     *  left nothing executable. Same reasoning as the split above — one number, two fixes. */
    public static volatile int qShort, qVetoed;

    /** {@code MAX_DIST_FROM_PATH} / {@code MAX_MAX_DIST_FROM_PATH} / {@code MAX_TICKS_AWAY}
     *  (PathExecutor.java:51-61). 200 ticks is upstream's ten seconds. */
    private static final double MAX_DIST_FROM_PATH = 2;
    private static final double MAX_MAX_DIST_FROM_PATH = 3;
    private static final int MAX_TICKS_AWAY = 200;

    private static final List<Movement> movements = new ArrayList<>();
    private static volatile boolean running = false;
    private static int index = 0;
    /** Where the body stood when the current chain began; the yardstick for qBurnedInPlace. */
    private static BetterBlockPos chainStartFeet = null;
    /** Feet cell at the end of the previous tick, for spotting a jump the walk cannot explain. */
    private static BetterBlockPos lastTickFeet = null;
    /**
     * Ticks the queue has run with the body in the SAME cell.
     *
     * <p>This lives on the queue, not on a Movement, and that is the entire point. The per-step
     * timeout ({@code ticksOnCurrent}) is charged to the current movement and {@code reset()} on
     * every rewind -- so a step that can never complete never times out, because the drift check
     * keeps rewinding and wiping its clock. Measured in a stall capture: mqBack=87 rewinds over 91
     * chains, mvSteered=4810 and moveTicks=5352 (the keys ARE being pressed), and mqTimeout=0
     * across ninety seconds during which the bot did not move a single block.
     */
    private static int ticksNotMoving = 0;
    /** Chains abandoned because the body would not leave its cell. Read as qNoMove. */
    public static volatile int qStuckNoMove;
    /**
     * How long the body may fail to change cell while the queue is actively steering.
     *
     * <p>Six seconds. A legitimate step is about ten ticks; a break-and-step or a place can take a
     * few seconds; ninety seconds is what the bench calls a stall. This sits well above the
     * slowest honest movement and far below the point where a run is lost.
     */
    private static final int MAX_TICKS_NOT_MOVING = 120;
    /**
     * WHAT IS THE BODY STANDING IN WHEN IT WILL NOT MOVE? The counter alone cannot say.
     *
     * <p>⛔ qNoMove is the sharpest split found on the playthrough: pooled over 124 runs, the ones
     * that reach ZERO rungs read 44 of these and the ones that score read ZERO. Same start-site
     * wood (133 vs 153 logs), same frame rate (28.0 vs 27.0), same code. The failing bot plans
     * three times as often, advances a quarter as far per chain, never leaves a seven-block patch
     * at ONE altitude, and finishes with an empty inventory.
     *
     * <p>Everything measured so far says the body is held; nothing says by WHAT. The keys are being
     * pressed (mvSteered 90) and the driver knows it is stuck (pdStuck 17 against 1), so the next
     * question is the scene at the moment the chain is dropped -- what is at the feet, what is in
     * front, and which way the step wanted to go. Recorded as a small rolling note rather than a
     * count, because the shape of the obstruction is the answer and a tally cannot carry it.
     */
    private static final java.util.Deque<String> stuckScenes = new java.util.ArrayDeque<>();

    /** Key state latched AFTER the movement applied its inputs, i.e. what the body ticked with. */
    private static volatile String lastTickKeys = "?";

    /** Drop the recorded scenes; called by the per-run counter reset so they cannot outlive a run. */
    public static void clearStuckScenes() {
        stuckScenes.clear();
        lastTickKeys = "?";
    }

    private static void recordStuckScene(BetterBlockPos feet) {
        try {
            var world = kaptainwutax.tungsten.TungstenModDataContainer.world;
            if (world == null || feet == null) return;
            java.util.function.Function<BlockPos, String> name = bp -> {
                String id = net.minecraft.registry.Registries.BLOCK
                        .getId(world.getBlockState(bp).getBlock()).getPath();
                return id.length() > 14 ? id.substring(0, 14) : id;
            };
            // ⛔ THE DIRECTION MUST BE THE EDGE, NOT THE BODY'S OFFSET FROM IT. The first version
            // measured dest MINUS FEET, so a body that had drifted one cell reported a shape the
            // route never contained -- it read "EN-", a diagonal descend, for what may well have
            // been a plain descend walked from one step to the side. The class name comes from the
            // movement itself and is trustworthy; this label was not, and a contract violation was
            // nearly concluded from it. src -> dest is the edge as planned.
            Movement cur = index < movements.size() ? movements.get(index) : null;
            BlockPos want = cur != null ? cur.dest : null;
            BlockPos from = cur != null ? cur.src : null;
            String dir = "-";
            if (want != null && from != null) {
                int dx = want.getX() - from.getX(), dz = want.getZ() - from.getZ();
                int dy = want.getY() - from.getY();
                dir = (dx != 0 ? (dx > 0 ? "E" : "W") : "") + (dz != 0 ? (dz > 0 ? "S" : "N") : "")
                        + (dy != 0 ? (dy > 0 ? "+" : "-") : "=");
                if (dir.isEmpty()) dir = "same";
            }
            // ⛔ AND THE KEYS, because the scene so far says nothing is in the way. Twelve stuck
            // scenes out of twelve wanted a step DOWNWARD, standing on solid ground with air at
            // the feet and air at the head -- nothing blocking, and the body still would not go.
            // Vanilla has exactly one mechanism that holds a body at the lip of a drop while
            // forward is held: SNEAK. This file already records the leak that would cause it --
            // "a task can setPressed(true) and end without releasing, leaving SHIFT stuck" -- and
            // the failing runs never change altitude at all (vertical extent 1 block against 13).
            // ⛔ AND THE KEYS MUST BE READ WHERE THEY ARE PRESSED, NOT WHERE THEY ARE CLEARED.
            // The first version sampled them right here, and here is line 772 -- the movement
            // applies its inputs in movement.update() a hundred lines LATER, and Movement.tick
            // clears the map at the end of every tick. So the sample landed in the gap and read
            // fwd:n on every single scene, which would have "proved" that no movement key is ever
            // pressed. That is the artefact, not the finding. The keys are latched AFTER
            // update() instead, so what is reported is the state the body actually ticked with.
            String keys = lastTickKeys;
            // ⛔ AND WHICH MOVEMENT, IN WHICH STATE. The corrected key sample still reads fwd:n --
            // taken AFTER movement.update() this time, so the keys really are not pressed. Movement
            // .tick only applies inputs when the status is NOT complete, so a movement that reports
            // SUCCESS or FAILED and is not retired by the queue would press nothing and hold the
            // body exactly like this. Naming the class and the status separates that from a
            // movement that is running and simply declines to press.
            String who = "-";
            try {
                Movement m = cur;
                if (m != null) {
                    String cn = m.getClass().getSimpleName();
                    who = cn.replace("Movement", "") + "/" + m.statusForDiagnostics()
                            + "/idx" + index + "of" + movements.size();
                }
            } catch (Exception ignored) {
                // an instrument never breaks the tick it rides on
            }
            // ⛔ FORWARD GOES WHERE YOU LOOK, NOT WHERE THE EDGE POINTS. The scene already says
            // the step wants ES and that fwd is held with nothing in the way -- and the body does
            // not move. The one thing it never said is whether the CAMERA agrees with the edge.
            // The same question, asked of the close walk, is what exonerated its aim (480 of 481)
            // and sent that investigation somewhere useful; here it has never been asked at all.
            String look = "?";
            try {
                var pl = net.minecraft.client.MinecraftClient.getInstance().player;
                if (pl != null && cur != null) {
                    double ddx = (cur.dest.x + 0.5) - pl.getX();
                    double ddz = (cur.dest.z + 0.5) - pl.getZ();
                    float wantYawDeg = (float) Math.toDegrees(-Math.atan2(ddx, ddz));
                    // ⛔ AND THE TWO FIELDS THAT SEPARATE "BLOCKED" FROM "PRESSED AND NOT
                    // MOVING". The scene now shows a perfectly aimed diagonal (off:+0) with
                    // forward and sprint held, air at the feet and head, an ASCENDING step, and
                    // jump:n -- and MovementDiagonal only jumps when player.horizontalCollision
                    // is true. So either the body is against something the block names do not
                    // show, or it is against nothing and simply is not moving. Those want
                    // opposite fixes and the scene cannot currently tell them apart.
                    look = String.format(java.util.Locale.ROOT, "%+.0f coll:%s v:%.2f",
                            net.minecraft.util.math.MathHelper.wrapDegrees(
                                    wantYawDeg - pl.getYaw()),
                            pl.horizontalCollision ? "Y" : "n",
                            Math.hypot(pl.getVelocity().x, pl.getVelocity().z));
                }
            } catch (Exception ignored) {
                // an instrument never breaks the tick it rides on
            }
            stuckScenes.addLast("on:" + name.apply(feet.down()) + " in:" + name.apply(feet)
                    + " head:" + name.apply(feet.above()) + " go:" + dir + " off:" + look
                    + " " + keys + " " + who);
            while (stuckScenes.size() > 4) stuckScenes.removeFirst();
        } catch (Exception ignored) {
            // an instrument never breaks the tick it rides on
        }
    }

    /** The last four stuck scenes, or "-" if the body has never refused to move. */
    public static String stuckScenes() {
        return stuckScenes.isEmpty() ? "-" : String.join(";", stuckScenes);
    }

    /** Chains abandoned because the body was MOVED rather than walked. Read as qTeleport. */
    public static volatile int qTeleported;
    /**
     * A body cannot walk this far in one tick, so a jump this large means something moved it:
     * a death and respawn, a teleport, a portal. Vanilla sprint-jump covers well under a block
     * per tick, so eight is far above anything legitimate and far below a respawn across an arena.
     */
    private static final double TELEPORT_JUMP = 8.0;
    private static int ticksOnCurrent = 0;
    private static int ticksAway = 0;
    /** {@code costEstimateIndex} (PathExecutor.java:68): -1 = "no estimate read yet". */
    private static int costEstimateIndex = -1;
    private static double currentCostEstimate = MAX_COST_ESTIMATE;

    private MovementQueue() {}

    /**
     * Chains refused for ending on the cell they started from. Read as qNull.
     *
     * <p>Non-zero means the search really does hand back routes to nowhere -- which was invisible
     * before, because accepting one looked exactly like healthy pathing from the outside.
     */
    public static volatile int qNullRoute;

    public static boolean isRunning() {
        return running;
    }

    /** Which step of the chain is being executed, and how long it is. Diagnostics only. */
    public static int getIndex() {
        return index;
    }

    public static int size() {
        return movements.size();
    }

    /**
     * How many of {@code cells} a chain of {@link MovementTraverse} can cover, counting from the
     * front: 0 if the list is too short, otherwise 1 + the number of leading edges that are a
     * same-Y cardinal step. The planner calls this BEFORE committing a leg to the queue, because a
     * chain that stops short of the cell that needs a block placed would hand the bridge back to
     * the split path anyway — and a chain of length 1 would replan the same dead end forever.
     */
    /** Edge-shape histogram over every route offered to the queue. Which class buys the most is a
     *  question with an answer, and 4% coverage said the prefix RULE was the ceiling — this says
     *  which shapes the routes are actually made of, so the next class is chosen by count and not
     *  by guess. Telemetry only. */
    public static volatile int edgeTraverse, edgeAscend, edgeDescend, edgeDiagonal, edgeOther;

    /** Count the shapes of every edge in a route. Called from the planners' telemetry, not here. */
    public static void histogram(List<BlockPos> cells) {
        if (cells == null || cells.size() < 2) return;
        for (int i = 1; i < cells.size(); i++) {
            BlockPos a = cells.get(i - 1), b = cells.get(i);
            if (isTraverseEdge(a, b)) edgeTraverse++;
            else if (isAscendEdge(a, b)) edgeAscend++;
            else if (isDescendEdge(a, b)) edgeDescend++;
            else if (isDiagonalEdge(a, b)) edgeDiagonal++;
            else edgeOther++;
        }
    }

    public static int traversePrefix(List<BlockPos> cells) {
        if (cells == null || cells.size() < 2) {
            return 0;
        }
        int covered = 1;
        for (int i = 1; i < cells.size(); i++) {
            if (!isSupportedEdge(cells.get(i - 1), cells.get(i))) {
                break;
            }
            covered++;
        }
        return covered;
    }

    /**
     * A {@link MovementTraverse} is exactly one one-block CARDINAL step at the SAME Y (upstream
     * geometry: {@code positionsToBreak = {dest.above(), dest}}, {@code positionToPlace =
     * dest.below()}). Anything else — a climb, a drop, a diagonal, a parkour gap — is a different
     * movement class that this port does not include yet, and feeding one to a traverse would make
     * it aim at a cell it was never designed for.
     */
    private static boolean isTraverseEdge(BlockPos a, BlockPos b) {
        if (a.getY() != b.getY()) {
            return false;
        }
        return Math.abs(b.getX() - a.getX()) + Math.abs(b.getZ() - a.getZ()) == 1;
    }

    /**
     * One cardinal step UP — {@link MovementAscend}. The queue used to take traverses only, and
     * that was not a small gap: measured on chase_terrain, of 193 route cells across four chase
     * routes it could take TEN. A route over terrain is climbs and drops, so every one of them fell
     * back to the hand-rolled walker.
     */
    /**
     * A PILLAR is straight up in place: same column, one Y higher. Measured on chase_terrain this
     * is what the chase route opens with and it was the single biggest source of hand-backs — 52
     * of them in one run, all {@code MovementFallback (0,88,-283)->(0,89,-283)}, a steer being
     * asked to climb a block it has to BUILD. {@link MovementPillar} was ported long ago; nothing
     * ever dispatched to it, so the step fell through to the fallback and failed every time.
     */
    /**
     * A FALL is a cardinal step down by TWO or THREE. Deliberately disjoint from
     * {@link #isDescendEdge} (exactly -1) so the first-match dispatch chain cannot shadow either,
     * and cardinal only, as upstream — {@code MovementFall} is only ever constructed off
     * {@code MovementDescend.cost}. FastPlanner plans drops to MAX_FALL=3 and until now the
     * executor's deepest drop was one block, so everything deeper met a dumb steer with no fall
     * model at all.
     */
    private static final int MAX_FALL = 3;

    private static boolean isFallEdge(BlockPos a, BlockPos b) {
        int dy = b.getY() - a.getY();
        if (dy > -2 || dy < -MAX_FALL) {
            return false;
        }
        return Math.abs(b.getX() - a.getX()) + Math.abs(b.getZ() - a.getZ()) == 1;
    }

    private static boolean isPillarEdge(BlockPos a, BlockPos b) {
        return b.getY() - a.getY() == 1 && a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    private static boolean isAscendEdge(BlockPos a, BlockPos b) {
        if (b.getY() - a.getY() != 1) {
            return false;
        }
        return Math.abs(b.getX() - a.getX()) + Math.abs(b.getZ() - a.getZ()) == 1;
    }

    /**
     * Any edge this queue has a ported movement class for.
     *
     * ⛔ ASCEND IS PORTED BUT NOT ACCEPTED HERE — MEASURED WORSE, REVERTED. Adding it looked
     * obviously right and the numbers said no, twice over:
     *   * the traversable prefix did NOT grow (64 route cells, 4 taken — the same ~6%), because
     *     traversePrefix counts a CONTIGUOUS run from the start and a terrain route hits its first
     *     DESCEND almost immediately. Ascend alone cannot lengthen that prefix.
     *   * chase_terrain went from 12 freezes to 22.
     * So MovementAscend stays a completed, compiling port unit and is wired in only when
     * MovementDescend and MovementDiagonal join it and a prefix can actually form. Wiring one
     * class of four buys nothing and cost twice the stalls.
     */
    /**
     * One cardinal step DOWN — {@link MovementDescend}. Ascend alone could not lengthen the
     * contiguous prefix at all (64 route cells, 4 taken), because a terrain route meets its first
     * drop almost immediately and the count stops there; climbs and drops only pay off together.
     */
    private static boolean isDescendEdge(BlockPos a, BlockPos b) {
        if (b.getY() - a.getY() != -1) {
            return false;
        }
        return Math.abs(b.getX() - a.getX()) + Math.abs(b.getZ() - a.getZ()) == 1;
    }

    /**
     * One corner step — {@link MovementDiagonal}. Same Y or one up or down, one cell in each of two
     * axes. With traverse, ascend and descend wired the contiguous prefix now breaks here, and open
     * ground is mostly diagonals.
     */
    /**
     * A RUNNING JUMP — {@link MovementParkour}. Straight along one cardinal, two to four cells,
     * level or one up, with the cells in between not walkable.
     *
     * <p>This is the shape {@code mqNoClass} was counting. Measured on a stalled playthrough run:
     * 27 of 28 chains truncated, 25 steps advanced in 160 seconds, the route replanned identically
     * each time. The queue's own comment already named the case from a live run --
     * {@code {90,134,-36} -> {86,135,-34}}, four across and one up -- and there was no class for it.
     *
     * <p>THE GAP IS CHECKED, not assumed. A straight two-to-four cell edge over SOLID ground is not
     * a jump, it is a route the planner shortened, and handing that to a jump would launch the bot
     * over ground it could have walked. So every intermediate cell must be non-walkable for this to
     * claim the edge.
     */
    private static boolean isParkourShape(BlockPos a, BlockPos b) {
        int dy = b.getY() - a.getY();
        if (dy != 0 && dy != 1) {
            return false;
        }
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        if (dx != 0 && dz != 0) {
            return false;                       // straight cardinals only
        }
        int d = Math.abs(dx) + Math.abs(dz);
        return d >= 2 && d <= MovementParkour.MAX_DIST;
    }

    /**
     * The shape AND the gap. Admission ({@link #isSupportedEdge}) has no world and asks only the
     * shape; dispatch asks this, because a straight two-to-four cell edge over SOLID ground is not
     * a jump -- it is a route the planner shortened, and launching the bot over ground it could
     * have walked is how a fix becomes a regression.
     */
    private static boolean isParkourEdge(WorldView world, BlockPos a, BlockPos b) {
        if (!isParkourShape(a, b)) {
            return false;
        }
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        int d = Math.abs(dx) + Math.abs(dz);
        Direction dir = dx != 0
                ? (dx > 0 ? Direction.EAST : Direction.WEST)
                : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        for (int i = 1; i < d; i++) {
            BlockPos mid = a.offset(dir, i);
            if (MovementHelperB.canWalkOn(world, mid.down(), world.getBlockState(mid.down()))) {
                return false;                   // walkable ground in between: not a gap
            }
        }
        return true;
    }

    private static boolean isDiagonalEdge(BlockPos a, BlockPos b) {
        int dy = b.getY() - a.getY();
        if (dy < -1 || dy > 1) {
            return false;
        }
        return Math.abs(b.getX() - a.getX()) == 1 && Math.abs(b.getZ() - a.getZ()) == 1;
    }

    private static boolean isSupportedEdge(BlockPos a, BlockPos b) {
        // ⛔ THE NUMBERS THAT USED TO BE QUOTED HERE ARE RETRACTED (register C5.19): every A/B of
        // that session ran against a lever that was never wired, so they compared the build with
        // itself. What was a hypothesis in them has since been CONFIRMED by reading the source:
        // playerInValidPosition() had dropped upstream's `|| contains(pathStart())`, and
        // MovementDiagonal is its only live caller and turns a false into an immediate
        // UNREACHABLE that kills the whole chain. That half is restored; diagonals stay behind
        // the flag only until a genuinely-armed A/B says where they belong.
        var cfg = kaptainwutax.tungsten.TungstenConfig.get();
        // WHOLE-ROUTE MODE: every edge is ours, untyped ones walked by MovementFallback. The
        // contiguous-prefix rule is what capped coverage at 4%, and the tail it gave back went to
        // a walker that measurably cannot cross terrain. See C5.18.
        if (cfg.queueWholeRoute) return true;
        if (isTraverseEdge(a, b)) return true;
        // A PILLAR IS AN ACCEPTED EDGE, NOT JUST A DISPATCHABLE ONE. The dispatch switch below
        // has known isPillarEdge since the pillar fix, but admission is what decides how much of
        // a route becomes a chain at all — so a straight-up edge still ENDED the prefix, and if
        // it came first the chain was refused outright. MovementPillar was reachable only with
        // queueWholeRoute on. That is also why ladders were unclimbable through the queue:
        // MovementPillar owns ladders and vines too.
        if (isPillarEdge(a, b)) return true;
        // A PARKOUR EDGE MUST BE ADMITTED, NOT ONLY DISPATCHABLE -- the same lesson as the pillar
        // note directly above. mqNoClass=27 of 28 chains truncated at this shape; wiring only the
        // dispatch would leave the prefix ending in exactly the same place.
        if (kaptainwutax.tungsten.TungstenConfig.get().queueParkour && isParkourShape(a, b)) return true;
        if (cfg.queueClimbs && (isAscendEdge(a, b) || isDescendEdge(a, b))) return true;
        // FALLS ARE ADMITTED AGAIN. They were pulled for one build after 25 of 26 chains died on
        // the timeout with the feet never leaving the lip — but MovementFall was not the culprit:
        // VoidGuard ran after the queue on every punk tick, un-pressed the MOVE_FORWARD the
        // movement had just declared and forced SNEAK, because a planned 3-block drop reads as
        // fallHeight 4 against its hardcoded limit of 3. Fixed at that call site
        // (MixinClientPlayerEntity), where the same exemption already covered every other driver.
        if (cfg.queueClimbs && isFallEdge(a, b)) return true;
        // ⛔ (previous retraction, kept for the record)
        // MEASURED 2026-08-02, MovementFall DOES NOT MOVE.
        // Armed for one chase: 25 of 26 chains died on the queue's timeout and EVERY ONE was a
        // MovementFall that never left the lip — "step 0 has taken too long (161 ticks) ...
        // (-248,105,285)->(-247,102,285), feet (-248,105,285)". freezes 20 with falls admitted
        // against 3 for the plain walker in the same batch. The class stays in the tree, the
        // dispatch below stays wired, but nothing routes to it until the reason it never starts
        // is found — a class that stands still for eight seconds is worse than no class.
        // if (cfg.queueClimbs && isFallEdge(a, b)) return true;
        return cfg.queueDiagonals && isDiagonalEdge(a, b);
    }

    /**
     * Take over the given cell chain. Returns the number of movements actually queued (0 = refused,
     * and the caller must keep its existing route). The chain is truncated at the first non-traverse
     * edge rather than skipped over: the queue owns a contiguous run or nothing.
     */
    public static synchronized int start(List<BlockPos> cells) {
        return start(cells, kaptainwutax.tungsten.TungstenConfig.get().queueWholeRoute);
    }

    /**
     * As {@link #start(List)}, but the caller states whether it wants the WHOLE route.
     *
     * <p>The two callers want different things and measuring proved it. The chase is better off
     * with every edge in the chain (4 interleaved pairs: 7/15, 13/12, 22/22, 0/22 — and the
     * 0-freeze run was the longest chase of the batch, the runner covering 529 blocks against
     * ~107). The NAVIGATOR is not: turning the same mode on globally took nav 12/12 to 11/12 with
     * nav_bridge red, because it also changes what a build leg accepts. So it stops being one
     * global switch and becomes the caller's choice.
     */
    public static synchronized int start(List<BlockPos> cells, boolean wholeRoute) {
        int covered = wholeRoute ? (cells == null ? 0 : cells.size()) : traversePrefix(cells);
        if (covered < 2) {
            qRefused++;
            qShort++;
            return 0;
        }
        stop();
        net.minecraft.world.WorldView world =
                net.minecraft.client.MinecraftClient.getInstance().world;
        if (world == null) {
            qRefused++;
            return 0;
        }
        for (int i = 1; i < covered; i++) {
            BetterBlockPos from = new BetterBlockPos(cells.get(i - 1));
            BetterBlockPos to = new BetterBlockPos(cells.get(i));
            // ONE MOVEMENT CLASS PER EDGE SHAPE, which is upstream's whole model: the step decides
            // for itself how to be walked, and the queue only decides whose turn it is.
            // ONE MOVEMENT CLASS PER EDGE SHAPE, which is upstream's model: the step decides for
            // itself how to be walked, and the queue only decides whose turn it is.
            // WATER FIRST, BEFORE ANY LAND PREDICATE CAN CLAIM THE SHAPE. FastPlanner expands all
            // six directions inside water and prices them as strokes, so a route through a lake
            // carries vertical edges — and a vertical edge matches isPillarEdge, which means the
            // bot was told to build a tower under itself while floating. Measured: 34 hand-backs
            // in one run, every one of them MovementPillar (-177,62,290)->(-177,63,290), with
            // rcon confirming the source is water and the destination is the air above it.
            // ⛔ DOES DISPATCH AGREE WITH ADMISSION? A stuck scene names Diagonal/RUNNING/idx0of2
            // -- a MovementDiagonal as the FIRST movement of a chain -- while queueDiagonals reads
            // false on the live stand (checked, not assumed, along with queueWholeRoute=false, so
            // no pinned experiment leaked into the baseline). Admission should have refused that
            // edge and left the chain empty. Dispatch built it anyway, or admission accepted it by
            // a clause that does not match what it advertises. Those are different bugs; count the
            // disagreement instead of arguing from either side.
            if (!wholeRoute && !isSupportedEdge(from, to)) {
                qAdmitMismatch++;
            }
            if (MovementHelperB.isLiquid(world, from) || MovementHelperB.isLiquid(world, to)) {
                movements.add(new MovementSwim(from, to));
            } else if (isPillarEdge(from, to)) {
                movements.add(new MovementPillar(from, to));
            } else if (isDiagonalEdge(from, to)) {
                movements.add(new MovementDiagonal(from, to));
            } else if (isAscendEdge(from, to)) {
                movements.add(new MovementAscend(from, to));
            } else if (isDescendEdge(from, to)) {
                movements.add(new MovementDescend(from, to));
            } else if (isFallEdge(from, to)) {
                movements.add(new MovementFall(from, to));
            } else if (isTraverseEdge(from, to)) {
                movements.add(new MovementTraverse(from, to));
            } else if (kaptainwutax.tungsten.TungstenConfig.get().queueParkour
                    && isParkourEdge(world, from, to)) {
                qParkour++;
                movements.add(new MovementParkour(from, to));
            } else {
                // AN EDGE WITH NO CLASS IS NOT THIS QUEUE'S WORK -- KEEP THE HEAD, HAND BACK THE REST.
                // The route comes from CombatPathfinder with parkour enabled, so it contains running
                // jumps: measured on @gamer, {90,134,-36}->{86,135,-34}, four blocks across and one
                // up. There is no MovementParkour here, so the shape fell through to a dumb steer
                // that walks at the gap, fails its own no-progress check a second and a half later,
                // and gets planned again identically -- the bot oscillates on the lip and the drive
                // eventually reports "goal unreachable - no progress in 14s".
                // Truncating instead is honest about capability: the queue runs the part it has
                // movements for, and a route whose FIRST edge is a jump is refused outright, which
                // sends it to the walker -- the thing that sprint-jumps toward a waypoint and can
                // actually clear the gap.
                // A ROUTE EDGE THAT GOES NOWHERE IS NOT A MISSING MOVEMENT CLASS.
                // See TungstenConfig.queueSkipsNullEdges: 601 of the truncating shapes on the
                // reproduced stall were 0,0,0. Truncating on one throws away every step after it.
                if (kaptainwutax.tungsten.TungstenConfig.get().queueSkipsNullEdges
                        && from.equals(to)) {
                    qNullEdge++;
                    continue;
                }
                qNoClass++;
                // ⛔ COUNT IS NOT A CORPUS. qNoClass says 27 of 28 chains were truncated; it does
                // NOT say what shape did it, so "the missing class is parkour" rests on one edge
                // read by hand out of one stalled run ({90,134,-36}->{86,135,-34}). If a second
                // shape is also falling through, that single sample cannot show it, and a whole
                // pass could be spent implementing the wrong movement.
                //
                // So tally the SHAPES, by their signed offsets. Whatever tops this table is the
                // movement worth writing next, and the table costs nothing to keep.
                synchronized (noClassShapes) {
                    if (noClassShapes.size() < 64) {
                        String key = (to.getX() - from.getX()) + "," + (to.getY() - from.getY())
                                + "," + (to.getZ() - from.getZ());
                        noClassShapes.merge(key, 1, Integer::sum);
                    }
                }
                break;
            }
        }
        // A STEP WE CANNOT PREPARE IS NOT A STEP WE CAN TAKE.
        // Movement.updateState returns PREPPING while prepared() is false, and every ported
        // subclass returns immediately on a non-RUNNING status — BEFORE its own arrival check.
        // So a movement that can never be prepared does not fail: it sits there pressing nothing
        // until the queue's cost+100 timeout, and measured on chase_terrain that is where the
        // freezes come from. Five of six timeouts in one run were MovementAscend, one of them
        // with the feet ALREADY STANDING ON THE DESTINATION (src 228,50,177 -> dest 229,51,177,
        // feet 229,51,177) burning 161 ticks, because an ascend also wants src.above().above()
        // and dest.above() clear and nothing in a chase breaks them.
        //
        // Upstream does not need this check: its cost model prices the breaking, so a step that
        // would have to break something either carries that price or is never planned. FastPlanner
        // does not model it, so the chain gets vetted here instead — truncate at the first step
        // whose preparation is impossible right now, and keep the executable head.
        // "NEEDS A BLOCK BROKEN" IS NOT THE SAME AS "CANNOT BE DONE". Breaking is what
        // prepared() is FOR — it aims, swaps tool and holds attack (Movement.java:378-418), and
        // upstream simply prices the mining in cost(). Cutting on any non-empty toBreak refused
        // 25 route hand-offs for every 2 it accepted, because a terrain route meets a breakable
        // cell almost immediately. Cut only where the break is genuinely impossible: a block we
        // are forbidden to break, or one with no finite mining time.
        if (movements.isEmpty()) {
            // Every edge was a shape we have no class for -- most often a route whose very first
            // step is a jump. Refusing sends the drive to the walker instead of starting an empty
            // queue that would report "chain complete" and look like an arrival.
            qRefused++;
            return 0;
        }
        net.minecraft.client.network.ClientPlayerEntity self =
                net.minecraft.client.MinecraftClient.getInstance().player;
        int executable = movements.size();
        outer:
        for (int i = 0; i < movements.size(); i++) {
            Movement m = movements.get(i);
            if (!m.needsClearBreaks()) {
                continue;
            }
            for (BlockPos cell : m.toBreak(world)) {
                BlockState st = world.getBlockState(cell);
                // A FLUID IS NOT A BREAK. Flowing water fails canWalkThrough, so it lands in
                // toBreak; BreakRules then refuses it because you cannot mine a fluid — and this
                // loop read that refusal as "this step is impossible" and threw away the ENTIRE
                // route. Measured on chase_terrain: every single cut was minecraft:water, 68 of
                // them in one run, at step 0 of routes 40 to 58 cells long. Upstream never breaks
                // water either; it PRICES swimming, and the movements carry the liquid handling
                // themselves. So a fluid cell is simply not this check's business.
                if (!world.getFluidState(cell).isEmpty()) {
                    continue;
                }
                double ticksToMine = self == null ? Double.POSITIVE_INFINITY
                        : MovementHelperB.getMiningDurationTicks(world, self, cell.getX(),
                                cell.getY(), cell.getZ(), st, true);
                boolean forbidden = MovementHelperB.avoidBreaking(
                        world, cell.getX(), cell.getY(), cell.getZ(), st);
                if (forbidden || !Double.isFinite(ticksToMine)
                        || ticksToMine >= kaptainwutax.tungsten.path.calculators.ActionCosts.COST_INF) {
                    // NAME THE CELL AND THE REASON. "chain cut to 0/40" says a 40-cell route was
                    // thrown away whole and nothing about why, which is not a diagnosis.
                    Debug.logMessage("MovementQueue: cut at " + i + " — "
                            + m.getClass().getSimpleName() + " needs " + cell + " ("
                            + net.minecraft.registry.Registries.BLOCK.getId(st.getBlock())
                            + ") " + (forbidden ? "FORBIDDEN" : "mine=" + ticksToMine));
                    executable = i;
                    break outer;
                }
            }
        }
        if (executable < movements.size()) {
            Debug.logMessage("MovementQueue: chain cut to " + executable + "/" + movements.size()
                    + " — step " + executable + " needs blocks broken first");
            while (movements.size() > executable) {
                movements.remove(movements.size() - 1);
            }
        }
        if (movements.isEmpty()) {
            qRefused++;
            qVetoed++;
            return 0;
        }
        // A CHAIN THAT ENDS WHERE IT BEGAN IS NOT A ROUTE, AND ACCEPTING ONE COSTS ALL THE WOOD.
        //
        // Measured on chop_canopy, a course built for exactly this: a log three blocks away and
        // seven up, with an ordinary trunk twelve blocks off. The bot walks to the column beneath
        // the unreachable log and then, ten times over:
        //
        //   MovementQueue: 2 movement(s) 3,-60,0 -> 3,-60,0
        //   MovementQueue: chain complete
        //
        // Same cell in and out. The chain is accepted, running goes true, and that is enough to
        // ruin the recovery: MineOrCollectTask resets its progress checker on every tick where
        // Nav.isPathing() is true, so a queue that perpetually "runs" a route to nowhere means the
        // checker CANNOT trip, the log is never blacklisted, and the fallback to the reachable
        // trunk never happens. Result: logs=0 in 150 seconds, against 7.8 seconds to the first log
        // when the same tree stands alone.
        //
        // Refusing it is the honest answer to "can you take me there": we are already there, and
        // whatever the caller wants next, it is not walking. That lets isRunning() go false, the
        // progress checker do its job, and the blacklist-and-move-on path finally run.
        if (cells.get(0).equals(cells.get(movements.size()))) {
            qRefused++;
            qNullRoute++;
            movements.clear();
            return 0;
        }
        index = 0;
        ticksOnCurrent = 0;
        ticksAway = 0;
        costEstimateIndex = -1;
        currentCostEstimate = MAX_COST_ESTIMATE;
        running = true;
        qStarted++;
        // Latch where the body is as this chain begins; qBurnedInPlace is measured against it.
        try {
            chainStartFeet = movements.get(0).ctx.playerFeet();
        } catch (Throwable t) {
            chainStartFeet = null;
        }
        // ⛔ NAME THE CALLER. Six mechanisms have now been proposed for the cobblestone tower that
        // loses mine_stone -- the pillar trigger, the zombie route, the shaft, the break ban, the
        // progress check, the flee goal -- and five are refuted by their own gates. Every one was a
        // guess about WHO asks for a route that climbs eight blocks into empty air. This prints it
        // instead: the goal the navigator is serving and the altoclef task holding the chain, at the
        // instant the chain starts, and only for chains that actually climb.
        //
        // The technique is not new here; it is the one that worked three times when reasoning did
        // not (lastDisableBy, lastSkip, lastBrokenBlockPos). It costs one string on a rare path.
        int rise = cells.get(movements.size()).getY() - cells.get(0).getY();
        Debug.logMessage("MovementQueue: " + movements.size() + " movement(s) "
                + cells.get(0).getX() + "," + cells.get(0).getY() + "," + cells.get(0).getZ()
                // the chain may have been truncated, so report where it will ACTUALLY end
                + " -> " + cells.get(movements.size()).getX() + ","
                + cells.get(movements.size()).getY() + ","
                + cells.get(movements.size()).getZ()
                + (rise >= 3 ? " CLIMB+" + rise + " for goal=" + kaptainwutax.tungsten.task.FastNavigator.goalDescription()
                        + " nowServing=" + kaptainwutax.tungsten.combat.CombatTrace.hostGoal
                        + " routeArmedFor=" + kaptainwutax.tungsten.task.FastNavigator.startedFor() : ""));
        return movements.size();
    }

    /**
     * Hand the body back. Releases every key this queue could be holding — a movement that stops
     * mid-sneak must not leave SHIFT latched over whoever runs next (and over the human player) —
     * and drops the aim target so {@code WindMouseRotation} stops steering.
     */
    public static synchronized void stop() {
        movements.clear();
        index = 0;
        ticksOnCurrent = 0;
        ticksAway = 0;
        costEstimateIndex = -1;
        if (running) {
            running = false;
            Movement.clearAllKeys();
            Movement.clearMotionFrame();
            WindMouseRotation.INSTANCE.clearTarget();
        }
    }

    /**
     * Ticked from the client mixin, BEFORE the walker and instead of it. This is
     * {@code PathExecutor.onTick} (PathExecutor.java:93-232) with the pieces tungsten has no
     * counterpart for removed — the break/place plan recalculation (:147-180, a renderer feed), the
     * loaded-chunk pause (:184-190), the cost re-verification (:192-215, which needs a live
     * {@code CalculationContext}) and {@code shouldPause} (:216-219, which needs a search in
     * progress). What is left is the part that decides WHICH movement owns the tick, and it is
     * copied, not paraphrased: the two snap loops with their resets, the same-tick advance on
     * SUCCESS, the off-path cancel, and the cost-estimate timeout.
     */
    public static void tick(ClientPlayerEntity player) {
        if (!running) {
            return;
        }
        if (player == null) {
            stop();
            return;
        }
        qTicks++;

        // PathExecutor.java:148-181 recomputes toBreak/toPlace/toWalkInto EVERY tick. Movement
        // caches them on first use and nothing here ever dropped that cache, so a chain kept
        // acting on the world as it was when start() ran — including after it had broken the very
        // block it was waiting on.
        for (Movement m : movements) {
            m.resetBlockCache();
        }
        // A ROUTE IS ABOUT WHERE WE WERE. IF SOMETHING MOVED US, IT IS ABOUT NOWHERE.
        // Measured on nav_bridge: the bot walks off a lip, falls into the void, dies, and
        // respawns at 0.5,-60,0.5 -- and then stands there for the remaining forty seconds of the
        // run while the chain it is holding describes a walk across an island it is no longer on.
        // The off-path check below cannot save this: it rewinds or replans against cells that are
        // now hundreds of blocks away, and its own thresholds (2.0 and 3.0) were written for
        // drift, not for teleportation.
        // A death is the common case, but the same is true of any teleport or portal.
        try {
            BetterBlockPos feet = movements.isEmpty() ? null : movements.get(0).ctx.playerFeet();
            if (feet != null && lastTickFeet != null
                    && Math.sqrt(feet.distanceSq(lastTickFeet)) > TELEPORT_JUMP) {
                Debug.logMessage("MovementQueue: body MOVED " + lastTickFeet + " -> " + feet
                        + " (not walked) — dropping the chain and replanning");
                qTeleported++;
                lastTickFeet = feet;
                stop();
                kaptainwutax.tungsten.task.FastNavigator.replanFromHere();
                return;
            }
            // AND THE CLOCK A REWIND CANNOT ERASE.
            // Same cell as last tick while we are steering means no progress, whatever the step
            // counter says: rewinds replay steps and reset their timers, so the only honest
            // yardstick is the body itself.
            if (feet != null && feet.equals(lastTickFeet)) {
                if (++ticksNotMoving > MAX_TICKS_NOT_MOVING) {
                    Debug.logMessage("MovementQueue: body has not left " + feet + " for "
                            + ticksNotMoving + " ticks while steering — dropping the chain"
                            + " (step " + index + "/" + movements.size() + ")");
                    qStuckNoMove++;
                    recordStuckScene(feet);
                    ticksNotMoving = 0;
                    lastTickFeet = feet;
                    stop();
                    kaptainwutax.tungsten.task.FastNavigator.replanFromHere();
                    return;
                }
            } else {
                ticksNotMoving = 0;
            }
            lastTickFeet = feet;
        } catch (Throwable t) {
            // A diagnostic must never be the thing that stops the bot walking.
            lastTickFeet = null;
            ticksNotMoving = 0;
        }

        for (int advances = 0; advances < MAX_ADVANCES_PER_TICK; advances++) {
            // PathExecutor.java:93-99. `path.length()` counts POSITIONS, so upstream's
            // `pathPosition >= path.length()` is `index >= movements.size()` here.
            if (index >= movements.size()) {
                Debug.logMessage("MovementQueue: chain complete");
                stop();
                return;
            }
            Movement movement = movements.get(index);

            // PathExecutor.java:101-127 — the two snap loops. Upstream recurses into onTick() after
            // either one fires; here `snap` reports that it moved the index and the outer loop
            // re-enters, which is the same control flow made explicit.
            if (!movement.getValidPositions().contains(movement.ctx.playerFeet())) {
                if (snap(movement.ctx.playerFeet())) {
                    continue;
                }
            }

            // PathExecutor.java:129-145. Being off the path is a DISTANCE question, not a
            // set-membership one: mid-step, mid-sneak or mid-fall the feet cell can legitimately
            // belong to no movement in the chain, and cancelling on that alone would abort a
            // backplace every time the body hangs over the hole. Only three blocks away from every
            // cell in the chain is genuinely lost.
            // DRIFT IS A RE-PLAN, NOT A FAILURE — this is the closed loop upstream has and we did
            // not. PathingBehavior re-searches on the SAME TICK a segment goes wrong; our queue
            // rewound twice and then abandoned the crossing, so a recoverable drift killed it.
            //
            // And it is exactly what makes the port fps-dependent. MAX_DIST_FROM_PATH (2.0) and
            // MAX_MAX (3.0) assume ticks arrive on time; at this stand's ~10 fps each tick moves
            // the body further, the same walk overshoots, and the run dies on a threshold that was
            // never meant to be an fps-dependent quantity. Measured on a failing sweep run:
            //   MovementQueue: too far from path (3.4) / (3.3), rewound 7 -> 5, rewound 13 -> 11
            // The manoeuvre was fine; only the recovery was missing.
            double closest = closestPathPos(player);
            boolean far = possiblyOffPath(player, movement, closest, MAX_DIST_FROM_PATH);
            boolean lost = possiblyOffPath(player, movement, closest, MAX_MAX_DIST_FROM_PATH)
                    || (far && ++ticksAway > MAX_TICKS_AWAY);
            if (!far) ticksAway = 0;
            if (lost) {
                // NAME THE ROUTE, NOT JUST THE DISTANCE.
                // A run of @gamer produced mqStarted=226 with mqLost=226 and step=0/1 every time:
                // the queue was handed a one-movement route, called itself lost 6-7 blocks from it
                // on the FIRST tick, stopped, and got the same route again. A route that far from
                // the body cannot have been rooted where the body is -- but "off path (6.4)" does
                // not say where the route WAS, so the loop cannot be diagnosed from it. The first
                // movement's own endpoints answer that in one line.
                Movement head = movements.isEmpty() ? null : movements.get(0);
                Debug.logMessage(String.format("MovementQueue: lost route head %s->%s (%s), feet %s",
                        head == null ? "-" : head.getSrc(), head == null ? "-" : head.getDest(),
                        head == null ? "-" : head.getClass().getSimpleName(),
                        head == null ? "-" : head.ctx.playerFeet()));
                Debug.logMessage(String.format(
                        "MovementQueue: off path (%.1f) at %.2f,%.2f,%.2f ground=%b fall=%.1f"
                                + " step=%d/%d — replanning from here",
                        closest, player.getEntityPos().x, player.getEntityPos().y,
                        player.getEntityPos().z, player.isOnGround(), player.fallDistance,
                        index, movements.size()));
                qUnreachable++;
                qLost++;
                stop();
                // Re-plan from where the bot ACTUALLY is. The navigator owns planning, so ask it
                // for a fresh leg rather than trying to repair a chain built from a stale start.
                kaptainwutax.tungsten.task.FastNavigator.replanFromHere();
                return;
            }

            // PathExecutor.java:193-197: read the estimate ONCE, when the movement becomes current,
            // "and deliberately get the cost as cached when this path was calculated, not the cost as
            // it is right now" — as the blocks get broken/placed the remaining cost falls, and a
            // per-tick estimate would make the timeout unreachable.
            if (costEstimateIndex != index) {
                costEstimateIndex = index;
                double cost;
                try {
                    cost = movement.getCost(player.getEntityWorld(), player);
                } catch (RuntimeException e) {
                    cost = MAX_COST_ESTIMATE;
                }
                currentCostEstimate = Math.min(Math.max(cost, 0.0), MAX_COST_ESTIMATE);
            }

            MovementStatus status;
            try {
                // ⛔ HOW MUCH OF A RUN IS SPENT NOT MOVING BY DESIGN? A movement in PREPPING
                // presses NOTHING: every updateState begins with
                //     super.updateState(state); if (status != RUNNING) return state;
                // and Movement.tick then applies an empty input map. So a movement that never
                // leaves PREPPING holds the body without a single key, which is exactly the state
                // one captured stuck scene shows -- Descend/PREPPING/idx0of4, fwd:n, on solid
                // ground with air at feet and head. One scene is a sample, not a rate, so count
                // the ticks instead: this fires on every run, failing or not.
                status = movement.update();
                if (status == MovementStatus.PREPPING) qPrepTicks++;
                else if (status == MovementStatus.RUNNING) qRunTicks++;
                try {
                    var o = net.minecraft.client.MinecraftClient.getInstance().options;
                    // ⛔ THE MISSING KEY WAS THE ONE THE PATHOLOGY USES. This latched sneak,
                    // forward and sprint only, so a movement sitting in PREPPING while it MINES
                    // an obstruction recorded as "sneak:n fwd:n spr:n" -- and read as "pressing
                    // nothing at all". Movement.prepared() holds CLICK_LEFT at whatever blocks
                    // the step and returns false, which is exactly the livelock this scene was
                    // built to identify; the scene could not tell it from an idle movement.
                    //
                    // Captured live on the playthrough: Descend/PREPPING/idx0of3 with
                    // "in:air head:air", which does NOT rule the obstruction out either --
                    // positionsToBreak is not the body and head cells.
                    lastTickKeys = "sneak:" + (o.sneakKey.isPressed() ? "Y" : "n")
                            + " fwd:" + (o.forwardKey.isPressed() ? "Y" : "n")
                            + " spr:" + (o.sprintKey.isPressed() ? "Y" : "n")
                            + " mine:" + (o.attackKey.isPressed() ? "Y" : "n")
                            + " jump:" + (o.jumpKey.isPressed() ? "Y" : "n");
                } catch (Exception ignored) {
                    // an instrument never breaks the tick it rides on
                }
            } catch (RuntimeException e) {
                Debug.logWarning("MovementQueue: " + movement.getClass().getSimpleName() + " threw: " + e);
                qUnreachable++;
                stop();
                return;
            }

            if (status == MovementStatus.UNREACHABLE || status == MovementStatus.FAILED) {
                Debug.logMessage("MovementQueue: movement returns status " + status
                        + " at step " + index + "/" + movements.size()
                        + " (" + movement.getClass().getSimpleName() + " " + movement.src
                        + "->" + movement.dest + ", feet " + movement.ctx.playerFeet() + ")");
                qUnreachable++;
                qStatusFail++;
                stop();
                return;
            }
            if (status == MovementStatus.SUCCESS) {
                qSuccess++;
                qSteps++;
                // A STEP THAT MOVES NOBODY IS NOT PROGRESS, AND IT SHOULD BE VISIBLE AS SUCH.
                // Compared against where the body was when this chain started: a genuine route
                // leaves that cell within a step or two, while a consumed one never does.
                BetterBlockPos feetNow = movement.ctx.playerFeet();
                if (chainStartFeet != null && feetNow != null
                        && feetNow.equals(chainStartFeet)) {
                    qBurnedInPlace++;
                }
                index++;
                onChangeInPathPosition();
                // Upstream recurses into onTick() here (:229-236) so the NEXT movement presses its
                // keys in this same tick. Loop instead of recurse; same effect, explicit bound.
                continue;
            }

            // :242-250. ticksOnCurrent is only charged on a tick the movement actually ran.
            // PathExecutor.java:238-241, AND its other half. The teardown alone took nav_gaps
            // from 12/12 to 11/12 when it landed without a policy to decide sprint — only
            // traverse and diagonal ever ask for it themselves, so the release fired nearly every
            // tick on the one course made of sprint jumps. SprintPolicy is that missing half: a
            // lookahead over the next one to three movements, ported from shouldSprintNextTick.
            // ⛔ WIRED, MEASURED, TAKEN BACK — 2026-08-02, SECOND ATTEMPT AT THIS MECHANISM.
            // The class is a faithful port of shouldSprintNextTick and the wiring follows
            // upstream's order, but on chase_terrain it PINNED the gap: two runs, min=25.0 and
            // 25.0, last=25.0 and 25.1, kills=0 — against min 4.4 / 5.1 and a kill on the build
            // immediately before. Freezes stayed at 0, so it does not stall the bot; it stops it
            // closing. The suspects are the skip branch (advances(), which moves `index` without
            // the movement ever running) and applySteer, which clears the keys — but that is a
            // hypothesis and this is a measurement, so the wiring comes out until the hypothesis
            // is one too. SprintPolicy.java stays in the tree; nothing calls it.
            //
            //   SprintPolicy.Decision d = SprintPolicy.shouldSprintNextTick(movements, index, player);
            //   ... latch, applySprint / applyJump / applySteer, and the advances() re-entry.
            ticksOnCurrent++;
            if (ticksOnCurrent > currentCostEstimate + MOVEMENT_TIMEOUT_TICKS) {
                // WHICH step, not just that one. 13 of 15 chains in a measured chase died here,
                // each burning cost+100 ticks — five to six seconds of standing still per stuck
                // step, which is the freeze the bench counts. The class and the two cells say
                // whether the planner is emitting steps the ported movements cannot execute.
                Debug.logMessage("MovementQueue: step " + index + " has taken too long ("
                        + ticksOnCurrent + " ticks, expected " + (int) currentCostEstimate + ") "
                        + movement.getClass().getSimpleName() + " " + movement.src + "->"
                        + movement.dest + ", feet " + movement.ctx.playerFeet());
                qTimeout++;
                stop();
                return;
            }
            return;   // the movement is still running; it owns this tick
        }
    }

    /**
     * PathExecutor.java:103-126, both directions, in upstream's order. Returns true if the index
     * moved (the caller must then re-enter with the new current movement).
     *
     * <p>BACKWARDS first (:103-114) — "this happens for example when you lag out and get teleported
     * back a couple blocks". <b>The reset loop at :107-109 is the load-bearing part.</b> A Movement
     * keeps its status, and a SUCCESS status is sticky: {@code MovementTraverse.updateState} returns
     * immediately when the status is neither RUNNING nor PREPPING, so replaying an already-succeeded
     * movement reports SUCCESS again without touching the world. Leaving the reset out produced
     * precisely that, measured: {@code rewound 13 -> 11} two to three times per tick for 2758 ticks,
     * {@code mqSteps=14365} on a 14-step chain, the bot frozen at the lip of the gap for the whole
     * course. {@code reset()} also clears {@code MovementTraverse.wasTheBridgeBlockAlwaysThere},
     * which is the sprint guard (pitfall P3), so a rewind that skipped it would sprint off a lip.
     *
     * <p>FORWARDS (:115-126) starts at {@code index + 3}, deliberately: "dont check pathPosition+1.
     * the movement tells us when it's done (e.g. sneak placing)" — a sneak-placing movement legally
     * has the feet in its DEST while it is still working, and skipping to it would cut the place
     * short. Landing on {@code i - 1} is upstream's too.
     */
    private static boolean snap(BetterBlockPos whereAmI) {
        for (int i = 0; i < index && i < movements.size(); i++) {
            if (movements.get(i).getValidPositions().contains(whereAmI)) {
                int previousPos = index;
                index = i;
                for (int j = index; j <= previousPos && j < movements.size(); j++) {
                    movements.get(j).reset();
                }
                if (TungstenConfig.get().verboseDebugLogging) {
                    Debug.logMessage("MovementQueue: rewound " + previousPos + " -> " + index);
                }
                onChangeInPathPosition();
                return true;
            }
        }
        for (int i = index + 3; i < movements.size(); i++) {
            if (movements.get(i).getValidPositions().contains(whereAmI)) {
                if (i - index > 2) {
                    Debug.logMessage("MovementQueue: skipping forward " + (i - index) + " steps, to " + i);
                }
                index = i - 1;
                // RESET WHAT WE LAND ON -- THE SAME REASON THE REWIND ABOVE DOES.
                // A SUCCESS status is sticky: updateState returns immediately when the status is
                // neither RUNNING nor PREPPING, so replaying an already-succeeded movement reports
                // SUCCESS again without touching the world. The backwards branch has reset for
                // exactly that reason and this one did not, which leaves the identical failure
                // reachable from the other side -- land on a movement that has already succeeded,
                // it completes instantly, index++, land again, and the chain is consumed WITHOUT
                // THE BOT MOVING.
                //
                // That is the stall this file's own history describes (mqSteps=14365 on a
                // 14-step chain) and it is what a capture on the playthrough shows: 114 steps
                // advanced in a single poll, one chain started, sixteen rewinds in the whole run,
                // and the position not changing by a block. Sixteen rewinds cannot burn 114
                // steps; a forward landing with no reset can.
                //
                // Reset the landing movement AND the one after it: the loop re-enters at `index`
                // and the very next advance runs `index + 1`, which the same stickiness would
                // otherwise wave through.
                for (int j = index; j <= i && j < movements.size(); j++) {
                    movements.get(j).reset();
                }
                onChangeInPathPosition();
                return true;
            }
        }
        return false;
    }

    /**
     * {@code closestPathPos} (PathExecutor.java:256-269) reduced to the distance, which is all
     * {@code possiblyOffPath} uses. {@code VecUtils.entityDistanceToCenter} is the distance from the
     * entity position to the block CENTRE (VecUtils.java:91-96, :107-109) — note the {@code +0.5} on
     * Y as well, which is upstream's and is not a bug to fix here.
     */
    /**
     * PathExecutor.java:304-317. Mid-air in a fall the body is LEGITIMATELY far from the route, so
     * upstream measures the FLAT (Y-ignored) distance to the fall's destination instead of the 3D
     * distance to the nearest route cell — and it applies that to BOTH leniencies, not just the
     * hard one, or the ticksAway accumulator charges through every fall.
     *
     * <p>Without this a fall is the likeliest edge in the game to trip {@code MAX_MAX} (3.0): it
     * is the move with the most un-braked drift, the body leaves the lip at full momentum with
     * three blocks of air before anything slows it, and tripping that threshold means an immediate
     * stop + replan MID-AIR — from a position with no support to route from.
     */
    private static boolean possiblyOffPath(ClientPlayerEntity player, Movement current,
                                           double closest, double leniency) {
        if (closest <= leniency) {
            return false;
        }
        if (current instanceof MovementFall) {
            BetterBlockPos fallDest = current.getDest();
            double dx = player.getEntityPos().x - (fallDest.getX() + 0.5);
            double dz = player.getEntityPos().z - (fallDest.getZ() + 0.5);
            return Math.sqrt(dx * dx + dz * dz) >= leniency;   // >=, as upstream
        }
        return true;
    }

    private static double closestPathPos(ClientPlayerEntity player) {
        double best = -1;
        for (Movement movement : movements) {
            for (BetterBlockPos pos : movement.getValidPositions()) {
                double xdiff = pos.getX() + 0.5 - player.getEntityPos().x;
                double ydiff = pos.getY() + 0.5 - player.getEntityPos().y;
                double zdiff = pos.getZ() + 0.5 - player.getEntityPos().z;
                double dist = Math.sqrt(xdiff * xdiff + ydiff * ydiff + zdiff * zdiff);
                if (dist < best || best == -1) {
                    best = dist;
                }
            }
        }
        return best == -1 ? 0 : best;
    }

    /**
     * Does the rest of the queued route need blocks broken or placed?
     *
     * <p>Asked by altoclef's pre-equip chain, which wants to know whether it is safe to put a sword
     * in hand: a route that has to mine or bridge needs the tool, a route that is just walking does
     * not. It used to ask BARITONE's path, which is empty whenever tungsten is driving -- so the
     * whole chain returned on its first line and the feature had been silently dead since the swap.
     *
     * @return true if any remaining step wants a block broken or placed, or if we cannot tell
     */
    public static synchronized boolean remainingNeedsBlockWork(net.minecraft.world.WorldView world) {
        if (world == null || movements.isEmpty()) {
            return false;
        }
        for (int i = Math.max(0, index); i < movements.size(); i++) {
            Movement m = movements.get(i);
            try {
                if (!m.toBreak(world).isEmpty() || !m.toPlace(world).isEmpty()) {
                    return true;
                }
            } catch (RuntimeException e) {
                return true;   // cannot tell: assume it does, which is the cautious answer
            }
        }
        return false;
    }

    /** {@code onChangeInPathPosition} (PathExecutor.java:583-586): release the keys, reset the clock. */
    private static void onChangeInPathPosition() {
        Movement.clearAllKeys();
        ticksOnCurrent = 0;
    }
}
