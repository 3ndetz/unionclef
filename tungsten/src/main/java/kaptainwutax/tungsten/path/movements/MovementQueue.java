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
    /**
     * WHY the queue handed the body back, split three ways. {@code qUnreachable} lumped them
     * together and the lump was unreadable: a chase measured 481 starts for 53 steps, and "454
     * hand-backs" cannot tell you whether the chain was aimed at the wrong cell to begin with,
     * whether a movement declared itself impossible, or whether the body drifted off the route.
     * Those have three different fixes, so they get three counters.
     */
    public static volatile int qLost, qStatusFail, qRefused;
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
    private static int ticksOnCurrent = 0;
    private static int ticksAway = 0;
    /** {@code costEstimateIndex} (PathExecutor.java:68): -1 = "no estimate read yet". */
    private static int costEstimateIndex = -1;
    private static double currentCostEstimate = MAX_COST_ESTIMATE;

    private MovementQueue() {}

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
            if (isPillarEdge(from, to)) {
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
            } else {
                // No class for this shape — walk it rather than hand the tail back.
                movements.add(new MovementFallback(from, to));
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
        index = 0;
        ticksOnCurrent = 0;
        ticksAway = 0;
        costEstimateIndex = -1;
        currentCostEstimate = MAX_COST_ESTIMATE;
        running = true;
        qStarted++;
        Debug.logMessage("MovementQueue: " + movements.size() + " movement(s) "
                + cells.get(0).getX() + "," + cells.get(0).getY() + "," + cells.get(0).getZ()
                // the chain may have been truncated, so report where it will ACTUALLY end
                + " -> " + cells.get(movements.size()).getX() + ","
                + cells.get(movements.size()).getY() + ","
                + cells.get(movements.size()).getZ());
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
                status = movement.update();
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
                index++;
                onChangeInPathPosition();
                // Upstream recurses into onTick() here (:229-236) so the NEXT movement presses its
                // keys in this same tick. Loop instead of recurse; same effect, explicit bound.
                continue;
            }

            // :242-250. ticksOnCurrent is only charged on a tick the movement actually ran.
            // ⛔ MEASURED AND TAKEN BACK, 2026-08-02. PathExecutor.java:237-242 does clear the
            // sprint STATE when the next movement does not ask for it, and releasing the KEY is
            // genuinely not the same thing — but upstream's teardown is one half of a mechanism
            // whose other half is shouldSprintNextTick() (PathExecutor.java:345-475), a lookahead
            // over the next one to three movements that DECIDES sprint. We have no such policy:
            // only traverse and diagonal ever request SPRINT, so the teardown fired on nearly
            // every tick and nav_gaps — which is jumps across gaps, i.e. the one course that
            // needs sprint distance — went red. Porting half a mechanism is the exact defect
            // class this file keeps fixing. It goes back in WITH shouldSprintNextTick, not before.
            //
            // if (!movement.sprintRequested()) {
            //     player.setSprinting(false);
            // }
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

    /** {@code onChangeInPathPosition} (PathExecutor.java:583-586): release the keys, reset the clock. */
    private static void onChangeInPathPosition() {
        Movement.clearAllKeys();
        ticksOnCurrent = 0;
    }
}
