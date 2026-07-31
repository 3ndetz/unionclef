package kaptainwutax.tungsten.helpers;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * HOW FAST A PLAYER CAN PLACE BLOCKS — one number, in one place, obeyed by everyone.
 *
 * <p>Port of {@code baritone/utils/BlockPlaceHelper.java}. Upstream every right click in the
 * whole bot funnels through one instance of that class, held by the input handler, and it
 * enforces {@code rightClickSpeed = 4} ticks between placements (Settings.java:375, minus
 * {@code BASE_PLACE_DELAY = 1}). Holding use in vanilla is subject to the same law, which is
 * why a baritone bridge looks like a person building a bridge.
 *
 * <p>Tungsten had ported the gate, but into a private method of {@code Movement} — so it
 * governed the ported movements and NOTHING else. Everything else placed as fast as its own
 * loop allowed:
 *
 * <ul>
 *   <li>{@code PathExecutor.tickPlacing}, {@code BridgeTask}, {@code PillarTask} run once per
 *       client tick, so up to 20 blocks per second — four times what holding the use key can
 *       do.</li>
 *   <li>{@code Py4jEntryPoint.fillCells} placed up to 96 blocks inside ONE client tick. That is
 *       the clip where six panes of glass appeared simultaneously: not a fast builder, a wall
 *       materialising out of nothing.</li>
 * </ul>
 *
 * <p>So the gate moves here, where every placement can reach it, and the countdown has exactly
 * one owner: {@link #tickCooldown()} runs once per client tick (TungstenMod's START_CLIENT_TICK),
 * standing in for upstream's input handler.
 *
 * <p>The hit result is always the caller's REAL one — see {@link RealPlacement}. This class
 * limits the RATE; it does not, and must not, manufacture an interaction.
 */
public final class BlockPlaceHelper {

    private BlockPlaceHelper() {}

    /** {@code rightClickSpeed}, Settings.java:375. */
    private static final int RIGHT_CLICK_SPEED = 4;
    /** {@code BASE_PLACE_DELAY}, BlockPlaceHelper.java:29 — the tick the place itself costs. */
    private static final int BASE_PLACE_DELAY = 1;

    /** BlockPlaceHelper.java:32, per player rather than per movement — a chain of one-block
     *  bridge steps must not reset the cooldown by handing over to the next movement. */
    private static int rightClickTimer;

    /** Telemetry, read over py4j as {@code placeStats}: how many placements the rate gate
     *  swallowed. A queue that never drains and a queue that is merely slow look identical
     *  from outside without this. */
    public static volatile int gatedByCooldown, gatedThrough;

    /**
     * Ticked exactly once per client tick. The single owner of the countdown.
     *
     * <p>The {@code return} matters and was missing. Upstream's tick is
     * {@code if (timer > 0) { timer--; return; }} (BlockPlaceHelper.java:39-42) — a tick spent
     * decrementing is a tick in which NOTHING places. Splitting it into "decrement here, test
     * {@code > 0} over there" let the same tick decrement 1 to 0 and then place, which is a
     * period of three ticks, not four: a bot placing 6.7 blocks a second where a player manages
     * 5. Charging one tick per tick, and only then draining, restores upstream's arithmetic.
     */
    public static void tickCooldown() {
        if (rightClickTimer > 0) {
            rightClickTimer--;
            return;
        }
        drainQueue();
    }

    /** True while the rate gate is closed — nothing may place this tick. */
    public static boolean onCooldown() {
        return rightClickTimer > 0;
    }

    /**
     * Place using the caller's REAL hit result, if the rate allows it.
     *
     * <p>BlockPlaceHelper.java:47-56 verbatim in shape: charge the cooldown first (upstream
     * charges it even when the interaction fails, so a refused place cannot be retried twenty
     * times a second), then try main hand and off hand.
     *
     * @return true when the interaction was actually sent.
     */
    public static boolean tryPlace(BlockHitResult hit) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null || hit == null) return false;
        if (rightClickTimer > 0) {
            gatedByCooldown++;
            return false;
        }
        rightClickTimer = RIGHT_CLICK_SPEED - BASE_PLACE_DELAY;
        for (Hand hand : Hand.values()) {
            if (mc.interactionManager.interactBlock(mc.player, hand, hit) == ActionResult.SUCCESS) {
                mc.player.swingHand(hand);
                gatedThrough++;
                return true;
            }
            if (!mc.player.getStackInHand(hand).isEmpty()
                    && mc.interactionManager.interactItem(mc.player, hand) == ActionResult.SUCCESS) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------
    // The build queue — what a rate limit forces bulk building to become.
    // ------------------------------------------------------------------------------------

    /**
     * A cell waiting to be built, and the block wanted in it.
     *
     * <p>Bulk building used to be a {@code for} loop inside one py4j call, which only worked
     * because placement was instantaneous and free. Once a placement costs four ticks, "fill
     * this region" cannot be answered inside a single call by anyone — so it stops pretending
     * to be, and becomes what it always was: work handed over, drained on the tick, polled by
     * the agent. That is the shape {@code bridgeForward} / {@code bridgePlaced} already uses.
     */
    private record Cell(BlockPos pos, String blockName) {}

    private static final Deque<Cell> QUEUE = new ArrayDeque<>();
    /** Cells the drain gave up on — out of reach, or nothing to place against yet. The agent
     *  repositions and enqueues them again; it is not the executor's job to decide where to
     *  stand. */
    private static final List<BlockPos> DEFERRED = new ArrayList<>();

    private static int placedFromQueue, alreadyFilled;
    /** WHY cells got deferred, because "the queue dropped three of them" is not a diagnosis.
     *  {@code noFace} = no neighbour was both placeable and visible from where the player stood;
     *  {@code protected} = a claim or protection rule refused it. The 2x2 //set that placed
     *  1 of 4 was read straight off these. */
    public static volatile int deferNoFace, deferTimeout, deferProtected;
    /** The block cannot survive in that cell (a torch with no wall, a sapling on stone) —
     *  {@code BlockState.canPlaceAt}, BuilderProcess.possibleToPlace:506. */
    public static volatile int deferNoSupport;
    /** The named block is not in the hotbar. Upstream's NO_OPTION: not a placement problem. */
    public static volatile int deferNoMaterial;
    /** Consecutive ticks on which NOTHING in the scan window was buildable from where the
     *  player stands. Reset by any progress, including merely finding a face to aim at. */
    private static int idleTicks;
    /** The cell the builder is currently walking to a placing position for, so it asks the
     *  navigator once instead of restarting the search every tick. */
    private static BlockPos walkingFor;
    /** Whether the builder may walk at all. An agent that wants to own movement itself turns
     *  this off and repositions on its own using {@code buildQueue().deferred}. */
    private static volatile boolean walkToBuild = true;
    /** Ticks walked for the current cell; past this it is deferred and the queue moves on, so
     *  one unreachable cell cannot hold a whole schematic hostage. */
    private static int walkTicks;
    /** The cell the last walk was planned for, and how many walks it has cost. A walk that ends
     *  without making the cell placeable is worth retrying from the new position — but not
     *  forever. */
    private static BlockPos lastWalkCell;
    private static int walkAttempts;
    public static volatile int walkStarted;
    /** The cell being walked for and the cell being walked TO. Reported by buildQueue(), because
     *  "it deferred after two walks" does not say whether it picked a bad destination or picked a
     *  good one and never arrived — and those need opposite fixes. */
    public static volatile String walkDebug = "";
    /** Cells skipped because the block would have been placed inside an entity — almost always
     *  the player itself. Non-zero here means "the builder is standing in its own way", which is
     *  a walking problem, not an aiming problem. */
    public static volatile int blockedByOwnBody;
    private static String equipped;

    /** How long to keep looking before handing the rest back. Generous: the aim is humanized
     *  (WindMouse), so convergence is tens of ticks at worst, and the caller may be walking. */
    private static final int IDLE_TIMEOUT_TICKS = 60;
    /** How far down the queue one tick looks for something buildable. Bounded because this runs
     *  on the client tick and the queue can hold a whole sphere. */
    private static final int SCAN_LIMIT = 64;
    /** Idle ticks before the builder decides the answer is "stand somewhere else" and walks. */
    private static final int WALK_AFTER_TICKS = 10;
    /** How many walks one cell is worth before it goes back to the agent. */
    private static final int MAX_WALK_ATTEMPTS = 4;
    /** How long to spend walking for one cell before giving up on it. 200 ticks (10 s) was too
     *  tight and deferred cells the navigator was still walking towards; a build walk is a
     *  pathfind plus the walk, and the queue is not in a hurry. */
    private static final int WALK_TIMEOUT_TICKS = 600;

    /** Hand a batch of cells to the tick drain. Order is the caller's — the callers sort
     *  bottom-up so each cell has support by the time it is reached. */
    public static synchronized void enqueue(List<BlockPos> cells, String blockName) {
        for (BlockPos p : cells) QUEUE.add(new Cell(p, blockName));
    }

    /**
     * Start a NEW bulk operation: whatever the previous one still owed is dropped.
     *
     * <p>There is one queue and one player, so "//set stone" cannot mean "after you finish the
     * sphere you abandoned". Measured: a //sphere that ran out of reachable cells left 37 of them
     * queued, and the next test's four-block structure went in behind them and never got built —
     * the call even reported {@code queued=4, queueTotal=37}, which is the queue saying so out
     * loud. A bulk op replaces the previous bulk op; {@code enqueue} on its own still appends,
     * for callers that are adding to the batch they just started.
     */
    public static synchronized void beginBatch(List<BlockPos> cells, String blockName) {
        clearQueue();
        resetCounters();
        enqueue(cells, blockName);
    }

    public static synchronized int queued() { return QUEUE.size(); }

    public static synchronized int placedFromQueue() { return placedFromQueue; }

    public static synchronized int alreadyFilled() { return alreadyFilled; }

    /** Cells the drain could not reach; the agent repositions and re-enqueues them. */
    public static synchronized List<BlockPos> deferred() { return new ArrayList<>(DEFERRED); }

    public static synchronized void resetCounters() {
        placedFromQueue = 0;
        alreadyFilled = 0;
        deferNoFace = 0;
        deferTimeout = 0;
        deferProtected = 0;
        deferNoSupport = 0;
        deferNoMaterial = 0;
        walkStarted = 0;
        blockedByOwnBody = 0;
        walkDebug = "";
        DEFERRED.clear();
    }

    public static synchronized void clearQueue() {
        QUEUE.clear();
        idleTicks = 0;
        walkingFor = null;
        walkTicks = 0;
        equipped = null;
    }

    /** Let the agent own movement instead: with this off the queue places only what it can see
     *  from where it stands and hands the rest back through {@code deferred}. */
    public static void setWalkToBuild(boolean on) { walkToBuild = on; }

    public static boolean walkToBuild() { return walkToBuild; }

    /**
     * One placement's worth of work per tick, at most.
     *
     * <p>SCANS the queue rather than blocking on its head. That distinction is the whole
     * difference between a builder and a queue: measured on the stand, a strict head-of-line
     * drain placed the base of a column and then abandoned the three cells above and beside it,
     * because it kept staring at one cell it could not see a face for while cells it COULD build
     * waited behind it. Baritone's builder has the same shape - BuilderProcess scans the whole
     * remaining set for something placeable from where it stands.
     *
     * <p>What it still cannot do is MOVE. A cell whose every neighbour is hidden behind the block
     * just placed is genuinely unbuildable from here, and it is now reported as such
     * ({@code deferNoFace}) instead of being placed through the obstruction with a forged hit.
     * Walking to where the cell is placeable is BuilderProcess's other half and is not ported yet.
     */
    private static synchronized void drainQueue() {
        if (QUEUE.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return;

        // A pillar owns the body, the keys and the camera for its whole run, exactly as a
        // MovementQueue leg does. Hands off until it finishes; the cells it fills come back to
        // the scan as "already built" and drop out of the queue on their own.
        if (kaptainwutax.tungsten.task.PillarTask.isActive()) return;

        Vec3d eye = kaptainwutax.tungsten.path.movements.RotationHelper.playerHead(player);
        double reach = kaptainwutax.tungsten.path.movements.RotationHelper.blockReachDistance(player);
        kaptainwutax.tungsten.path.movements.Rotation current =
                kaptainwutax.tungsten.path.movements.RotationHelper.playerRotations(player);

        int scanned = 0;
        for (java.util.Iterator<Cell> it = QUEUE.iterator(); it.hasNext() && scanned < SCAN_LIMIT; scanned++) {
            Cell cell = it.next();
            BlockPos target = cell.pos();

            // Someone else built it, or it was never air: nothing owed here.
            if (!mc.world.getBlockState(target).isReplaceable()) {
                it.remove();
                alreadyFilled++;
                idleTicks = 0;
                return;
            }
            if (!kaptainwutax.tungsten.path.PlaceRules.canPlace(mc.world, target)) {
                it.remove();
                deferProtected++;
                DEFERRED.add(target);
                idleTicks = 0;
                return;
            }
            // AM I STANDING WHERE THE BLOCK GOES? Then no face on earth will work from here, and
            // hammering the use key at it is what the operator filmed. Skip the cell this tick
            // and let the idle branch below walk us out of it — moving IS the fix, not retrying.
            BlockState wanted = wantedState(player, cell.blockName());
            // WILL THE BLOCK SURVIVE THERE — BuilderProcess.possibleToPlace:506, dropped entirely.
            // A torch needs a wall, a door needs two cells, a sapling needs dirt: without this the
            // queue burns its whole budget aiming at cells the game will refuse on arrival.
            if (!wanted.canPlaceAt(mc.world, target)) {
                it.remove();
                DEFERRED.add(target);
                deferNoSupport++;
                idleTicks = 0;
                return;
            }
            if (!placementPlausible(mc.world, target, wanted)) {
                blockedByOwnBody++;
                continue;
            }

            // WHICH FACE - and this is the part I first got wrong by adapting instead of porting.
            // My version picked the NEAREST placeable neighbour and aimed at it. Nearest is not
            // the question: a face can be perfectly close and completely occluded, and then the
            // aim never converges and the cell is abandoned. Measured: a 2x2 //set placed one
            // block and deferred the other three, because the block just placed stood between the
            // eye and the floor face the drain had chosen.
            //
            // Upstream asks the only question that matters, per candidate:
            // MovementHelper.attemptToPlaceABlock:815-833 walks
            // HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP, ray traces TOWARDS the
            // rotation that face would need, and accepts the first candidate whose trace actually
            // lands on it and would fill the target. Occlusion answers itself.
            // ALL SIX directions. This loop is the analogue of BuilderProcess.possibleToPlace
            // (:499), which iterates Direction.values() — not of attemptToPlaceABlock, whose
            // EXCEPT_UP list exists because a MOVEMENT places under itself and must never aim up.
            // A builder placing a ceiling has only the block above to click, and this loop could
            // not see it.
            for (Direction dir : Direction.values()) {
                BlockPos against = target.offset(dir);
                if (!RealPlacement.canPlaceAgainst(mc.world, against)) continue;
                // The face point, MovementHelper.attemptToPlaceABlock:822-824 verbatim - including
                // the asymmetric Y term, which is what puts the aim on the TOP face of a block
                // below.
                double faceX = (target.getX() + against.getX() + 1.0D) * 0.5D;
                double faceY = (target.getY() + against.getY() + 0.5D) * 0.5D;
                double faceZ = (target.getZ() + against.getZ() + 1.0D) * 0.5D;
                kaptainwutax.tungsten.path.movements.Rotation place = kaptainwutax.tungsten.path.movements
                        .RotationHelper.calcRotationFromVec3d(eye, new Vec3d(faceX, faceY, faceZ), current);
                HitResult res = kaptainwutax.tungsten.path.movements.RotationHelper
                        .rayTraceTowards(player, place, reach);
                if (!(res instanceof BlockHitResult bhr) || res.getType() != HitResult.Type.BLOCK) continue;
                if (!bhr.getBlockPos().equals(against)
                        || !bhr.getBlockPos().offset(bhr.getSide()).equals(target)) {
                    continue;   // that face is blocked from here - ask the next one
                }
                // This face works from where the player stands. Equip, turn the camera to it
                // through the mouse pipeline, and place the moment the crosshair agrees.
                if (!equipBlock(player, cell.blockName())) {
                    // Upstream's NO_OPTION: no material, so this is not a placement problem and
                    // no amount of walking or aiming fixes it. Hand it back instead of spinning.
                    it.remove();
                    DEFERRED.add(target);
                    deferNoMaterial++;
                    idleTicks = 0;
                    return;
                }
                // ONE OWNER OF THE BODY AT A TIME. This line used to set the camera every tick a
                // face happened to trace — including the ticks the navigator was mid-walk, which
                // made the builder and the navigator fight over the same camera: the walk was
                // steering towards one cell while the aim was dragged onto another, and the walk
                // never finished. Measured: the last cell of diag_build deferred after two walks
                // that never arrived.
                //
                // So arriving is a MODE CHANGE. Reaching this line means the cell is placeable
                // from where we already are, which is the whole reason we were walking; end the
                // walk here, then aim.
                stopWalking();
                kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE
                        .setTarget(place.getYaw(), place.getPitch());
                BlockHitResult hit = RealPlacement.readyToPlace(mc, target);
                // "A face exists" is NOT progress, and counting it as progress disabled the very
                // escape hatch below: an aim that never converges kept resetting idleTicks, so the
                // queue neither walked nor gave up. Only a placement, or the rate gate holding us
                // back from one, counts.
                if (hit == null) return;         // aim still on its way; hold this cell
                idleTicks = 0;
                if (!tryPlace(hit)) return;      // rate gate closed this tick
                it.remove();
                placedFromQueue++;
                walkDone();
                return;
            }
        }

        // Nothing in the scan window can be built from where the player is standing. THE ANSWER
        // IS TO STAND SOMEWHERE ELSE — that is the half of baritone's BuilderProcess that turns
        // a list of cells into a builder. Upstream never places from wherever it happens to be:
        // BuilderProcess.placementGoal (BuilderProcess.java:1050-1063) turns each cell into a
        // GOAL and the pathfinder walks there.
        idleTicks++;
        if (!walkToBuild) {
            if (idleTicks > IDLE_TIMEOUT_TICKS) deferRest();
            return;
        }
        Cell headCell = QUEUE.peek();
        BlockPos head = headCell.pos();

        // VERTICAL RUNS ARE NOT WALKED, THEY ARE JUMPED. If the cell can be STOOD IN — air, with
        // air above it and something solid below — then the reason no neighbouring face works is
        // that the cell is a step of a column, and a column is built the way a player builds one:
        // stand in it, jump, and place the block into the space your feet just left.
        //
        // This is the whole of C5.11, and it is the sixth time the answer was already in the
        // repo: PillarTask does exactly that manoeuvre and already performs it for the executor's
        // planned climbs (MovementPillar upstream). The build queue simply never asked it.
        //
        // Measured before this: a 3-tall column left its top two cells unbuildable from every
        // position on the ground, because to place the third block you must stand on the second,
        // and the second is a cell you are yourself filling. NOSTAND(5,-59,0) NOSTAND(5,-58,0).
        if (standable(mc.world, head)) {
            if (player.getBlockPos().equals(head)) {
                if (!equipBlock(player, headCell.blockName())) {
                    QUEUE.poll();
                    DEFERRED.add(head);
                    deferNoMaterial++;
                    idleTicks = 0;
                    return;
                }
                stopWalking();
                if (walkDebug.length() < 700) {
                    walkDebug += "PILLAR(" + head.toShortString() + ") ";
                }
                // One step: the block lands in this cell and we come to rest on top of it, which
                // is where the next cell of the run wants us anyway.
                kaptainwutax.tungsten.task.PillarTask.startTo(head.getY() + 1);
                idleTicks = 0;
                return;
            }
            // Not there yet. Walk INTO the cell — standing in it is the point, not a mistake.
            if (walkingFor == null || !walkingFor.equals(head)) {
                // The same attempt cap as every other walk. Without it this branch spun: one run
                // logged FIFTEEN walks to the same pillar base from two blocks away, because the
                // exhaustion check further down is never reached on this path.
                if (lastWalkCell != null && lastWalkCell.equals(head)) {
                    if (walkAttempts >= MAX_WALK_ATTEMPTS) {
                        if (walkDebug.length() < 700) {
                            walkDebug += "PILLARUNREACHED(" + head.toShortString() + ")@"
                                    + player.getBlockPos().toShortString() + " ";
                        }
                        stopWalking();
                        QUEUE.poll();
                        DEFERRED.add(head);
                        deferNoFace++;
                        walkAttempts = 0;
                        lastWalkCell = null;
                        idleTicks = 0;
                        return;
                    }
                } else {
                    walkAttempts = 0;
                }
                walkingFor = head;
                walkTicks = 0;
                walkStarted++;
                lastWalkCell = head;
                walkAttempts++;
                if (walkDebug.length() < 600) {
                    walkDebug += "[" + walkStarted + " pillarbase=" + head.toShortString()
                            + " from=" + player.getBlockPos().toShortString() + "] ";
                }
                // EXACT: the whole point is to be standing in this cell, and "within two blocks
                // of it" is where the previous version stopped — from (4,-60,0) it was already
                // 2.55 from (6,-60,0), so it walked half a block, hit the 2.0 radius, declared
                // arrival and quit. Fifteen times in one run.
                kaptainwutax.tungsten.task.FastNavigator.startExact(head);
                return;
            }
        }

        if (walkingFor != null && walkingFor.equals(head)) {
            walkTicks++;
            // THE NAVIGATOR STOPPING IS AN EVENT, AND THE BUILDER WAS DEAF TO IT. Measured on the
            // stand: the bot walked one block, from (3,-60,0) to (4,-60,-1), and the navigator
            // went inactive — arrived by its own reckoning, or gave up — while the builder sat
            // there for the full thirty-second timeout waiting for an arrival that had already
            // happened or was never coming. Both failing runs ended "nav=false", and both passing
            // ones simply never hit this.
            //
            // So when the navigator finishes, the walk is finished: take the body back and let
            // the scan re-decide from where we ACTUALLY are next tick. That is upstream's shape
            // too — BuilderProcess re-issues its goal every tick from the current position rather
            // than committing to one walk.
            if (!kaptainwutax.tungsten.task.FastNavigator.isActive()) {
                stopWalking();
                return;
            }
            if (walkTicks > WALK_TIMEOUT_TICKS) {
                if (walkDebug.length() < 700) {
                    walkDebug += "TIMEOUT(" + head.toShortString() + ")@"
                            + player.getBlockPos().toShortString() + " ";
                }
                stopWalking();
                QUEUE.poll();
                DEFERRED.add(head);
                deferNoFace++;
            }
            return;
        }
        // A walk that ends without making the cell placeable must not be retried forever: the
        // stand is recomputed from the new position each time, but if that keeps producing a
        // place we cannot reach, the honest answer is to hand the cell back to the agent.
        boolean sameCellAsLastAttempt = lastWalkCell != null && lastWalkCell.equals(head);
        if (!sameCellAsLastAttempt) walkAttempts = 0;
        if (sameCellAsLastAttempt && walkAttempts >= MAX_WALK_ATTEMPTS) {
            if (walkDebug.length() < 700) {
                walkDebug += "EXHAUSTED(" + head.toShortString() + ")@"
                        + player.getBlockPos().toShortString() + " ";
            }
            QUEUE.poll();
            DEFERRED.add(head);
            deferNoFace++;
            walkAttempts = 0;
            lastWalkCell = null;
            idleTicks = 0;
            return;
        }
        if (idleTicks <= WALK_AFTER_TICKS) return;   // the aim may still be arriving
        BlockPos stand = placementStand(mc.world, head, wantedState(player, headCell.blockName()));
        if (stand == null) {
            // Nowhere to stand that we can reach. That is this CELL's problem, not the batch's:
            // hand it back and carry on with the rest, which is what deferRest() used to prevent
            // by throwing the whole remaining queue away over one awkward cell.
            if (walkDebug.length() < 700) {
                walkDebug += "NOSTAND(" + head.toShortString() + ") ";
            }
            QUEUE.poll();
            DEFERRED.add(head);
            deferNoFace++;
            idleTicks = 0;
            return;
        }
        walkingFor = head;
        walkTicks = 0;
        walkStarted++;
        lastWalkCell = head;
        walkAttempts++;
        // APPEND, do not overwrite: the failing runs differ from the passing ones only in HOW
        // MANY walks happened, so the sequence is the measurement and the last entry is not.
        if (walkDebug.length() < 600) {
            walkDebug += "[" + walkStarted + " for=" + head.toShortString()
                    + " stand=" + stand.toShortString()
                    + " from=" + player.getBlockPos().toShortString() + "] ";
        }
        // Exact too: a stand chosen because THAT cell can see the face is not interchangeable
        // with any cell within two blocks of it.
        kaptainwutax.tungsten.task.FastNavigator.startExact(stand);
    }

    /** End a walk, if one is running: the navigator stops steering and the builder takes the
     *  body back. Idempotent, so the placing path can call it unconditionally. */
    private static void stopWalking() {
        if (walkingFor == null) return;
        kaptainwutax.tungsten.task.FastNavigator.stop();
        walkingFor = null;
        walkTicks = 0;
    }

    /** A placement clears the walk bookkeeping for that cell. */
    private static void walkDone() {
        stopWalking();
        lastWalkCell = null;
        walkAttempts = 0;
    }

    /** Hand the whole remaining queue back to the caller. */
    private static void deferRest() {
        for (Cell c : QUEUE) {
            DEFERRED.add(c.pos());
            deferNoFace++;
        }
        QUEUE.clear();
        idleTicks = 0;
        walkingFor = null;
        walkTicks = 0;
    }

    /**
     * WOULD THE BLOCK FIT, OR AM I STANDING IN IT — port of
     * {@code BuilderProcess.placementPlausible} (BuilderProcess.java:492-496).
     *
     * <p>THE ONE I DROPPED, and the operator found it on video: the bot walked flush against a
     * wall and tried to place a block inside its own hitbox, forever. Vanilla refuses that
     * placement, so every attempt failed, charged the four-tick cooldown and started again — a
     * bot standing still, shoving at a wall.
     *
     * <p>Upstream never gets there, because both places that decide "can this cell be built"
     * ask this question: {@code placementGoal} (:1058) requires
     * {@code canPlaceAgainst(neighbour) && placementPlausible(pos, state)}, and
     * {@code possibleToPlace} (:508) refuses a face outright when it fails. I ported the first
     * half of that {@code &&} and left the second, which is precisely the half that knows a
     * player is a solid object.
     *
     * <p>The question is asked of the block THAT WILL EXIST: take the collision shape the new
     * block would have at that cell and test it against every entity. An empty shape (a torch,
     * a sapling) always passes.
     */
    private static boolean placementPlausible(net.minecraft.world.World world, BlockPos pos, BlockState state) {
        if (state == null) return false;
        net.minecraft.util.shape.VoxelShape shape = state.getCollisionShape(world, pos);
        return shape.isEmpty()
                || world.doesNotIntersectEntities(null, shape.offset(pos.getX(), pos.getY(), pos.getZ()));
    }

    /**
     * The state that WILL EXIST in the cell — the thing {@link #placementPlausible} has to
     * measure. Upstream reads it from the schematic ({@code toPlace} at BuilderProcess.java:508,
     * {@code bcc.getSchematic(...)} at :1058); the queue's own {@code blockName} IS our schematic.
     *
     * <p>It first measured the HELD item instead, which is a different question and answered it
     * wrongly in both directions: a batch of torches queued with a pickaxe in hand fell back to
     * STONE, whose full cube "intersects" the player, so every cell was refused — though vanilla
     * places a torch inside your own hitbox happily. And the check runs before the equip, so with
     * the previous batch's torch still in hand a stone batch measured a shapeless torch and the
     * guard passed vacuously on the one tick it mattered.
     */
    private static BlockState wantedState(ClientPlayerEntity player, String blockName) {
        if (blockName != null && !blockName.isEmpty()) {
            String want = blockName.contains(":") ? blockName : "minecraft:" + blockName;
            net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(want);
            if (id != null && Registries.BLOCK.containsId(id)) {
                return Registries.BLOCK.get(id).getDefaultState();
            }
        }
        // Unnamed batch: fall back to the hand, and to a full cube if even that says nothing —
        // the conservative assumption, since a full cube is the shape most likely to hit us.
        if (player.getMainHandStack().getItem() instanceof BlockItem bi) {
            return bi.getBlock().getDefaultState();
        }
        return net.minecraft.block.Blocks.STONE.getDefaultState();
    }

    /**
     * WHERE TO STAND to place a block at {@code target} — port of
     * {@code BuilderProcess.placementGoal} (BuilderProcess.java:1050-1063).
     *
     * <p>Upstream returns a Goal (a predicate over positions) and lets the pathfinder pick any
     * position satisfying it. Tungsten's navigator takes a concrete destination, so the goal's
     * predicate is evaluated here and one cell is chosen — preferring lower y, which is what
     * {@code GoalAdjacent.heuristic} (:1109-1112) does with its {@code y * 100} term.
     *
     * <ul>
     *   <li>A placeable neighbour exists -&gt; {@code GoalAdjacent(target, against, allowSameLevel)}:
     *       stand NEXT TO the cell, never in it and never in the block being placed against, never
     *       below {@code target.y - 1}, and at the same level only when {@code target.up()} is
     *       solid (:1092-1106). That last rule is why the builder does not try to place a block
     *       into the space its own head occupies.</li>
     *   <li>Otherwise -&gt; {@code GoalPlace(target)} = {@code GoalBlock(target.up())} (:1147):
     *       stand ON TOP of where the block goes and place downwards. This is the case that
     *       reaches the top of a column, which nothing on the ground can see: you cannot look at
     *       the top face of a block whose top is above your eye.</li>
     * </ul>
     */
    private static BlockPos placementStand(net.minecraft.world.WorldView world, BlockPos target,
                                           BlockState state) {
        boolean allowSameLevel = !world.getBlockState(target.up()).isAir();
        for (Direction facing : kaptainwutax.tungsten.path.movements.Movement
                .HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
            BlockPos against = target.offset(facing);
            // BOTH halves of upstream's condition (BuilderProcess.java:1058). The second half is
            // the one that says "and the block would actually fit there".
            if (!RealPlacement.canPlaceAgainst(world, against)) continue;
            if (world instanceof net.minecraft.world.World w
                    && !placementPlausible(w, target, state)) continue;
            BlockPos stand = adjacentStand(world, target, against, allowSameLevel);
            if (stand != null) return stand;
        }
        // GoalPlace — stand on top of the cell and place downwards (BuilderProcess.java:1147).
        // ONLY IF WE CAN ACTUALLY STAND THERE. Upstream can hand this goal to a pathfinder that
        // pillars up to reach it; ours walks, so a goal floating in mid-air is not a destination,
        // it is a lie, and the walk burns its whole timeout failing to satisfy it.
        //
        // Measured, in the sharpest possible form. While the navigator's arrival test was a plain
        // 3D sphere it "arrived" at such a goal instantly without moving, which hid this — and the
        // moment that test was fixed, diag_build fell from 4 passes in 5 to 1, because four walks
        // of thirty seconds each now genuinely tried and genuinely failed. The arrival fix was
        // right (nav stayed 12/12); the goal was what was wrong.
        //
        // So: no reachable stand, no walk. Say so, and let the caller hand the cell back to the
        // agent, which can put a block under itself or come at it from a scaffold — decisions
        // that belong to whoever owns the build, not to the queue draining it.
        BlockPos above = target.up();
        return standable(world, above) ? above : null;
    }

    /** The {@code GoalAdjacent.isInGoal} predicate (BuilderProcess.java:1092-1106), evaluated
     *  over the cells around the target, lowest first. */
    private static BlockPos adjacentStand(net.minecraft.world.WorldView world, BlockPos target,
                                          BlockPos against, boolean allowSameLevel) {
        int[] levels = allowSameLevel ? new int[]{-1, 0, 1} : new int[]{0, 1};
        for (int dy : levels) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos stand = target.offset(d).up(dy);
                if (stand.equals(target) || stand.equals(against)) continue;
                if (stand.getY() < target.getY() - 1) continue;
                // A player is two blocks tall: standing with FEET or HEAD in the cell we are
                // trying to fill is the very situation placementPlausible refuses. GoalAdjacent
                // excludes the target cell itself (:1093-1095); the head is the same rule one
                // level up, and skipping it is how the bot ends up shoving at its own eye level.
                if (stand.up().equals(target)) continue;
                if (!standable(world, stand)) continue;
                return stand;
            }
        }
        return null;
    }


    /** Feet space, head space, and something to stand on — asked with the ported predicates so
     *  the builder and the pathfinder agree on what a standing position is. */
    private static boolean standable(net.minecraft.world.WorldView world, BlockPos feet) {
        return kaptainwutax.tungsten.path.movements.MovementHelperB.canWalkThrough(world, feet)
                && kaptainwutax.tungsten.path.movements.MovementHelperB.canWalkThrough(world, feet.up())
                && kaptainwutax.tungsten.path.movements.MovementHelperB.canWalkOn(world, feet.down());
    }

    /** Hold {@code blockName}. Returns false when it is not in the hotbar at all — the caller
     *  must then NOT place, because placing something else is worse than placing nothing.
     *  Skips the lookup when the wanted type is already equipped. */
    private static boolean equipBlock(ClientPlayerEntity player, String blockName) {
        if (blockName != null && blockName.equals(equipped)
                && player.getMainHandStack().getItem() instanceof BlockItem) {
            return true;
        }
        if (blockName != null && !blockName.isEmpty()) {
            String want = blockName.contains(":") ? blockName : "minecraft:" + blockName;
            for (int i = 0; i < 9; i++) {
                ItemStack st = player.getInventory().getStack(i);
                if (st.isEmpty()) continue;
                if (Registries.ITEM.getId(st.getItem()).toString().equals(want)) {
                    player.getInventory().setSelectedSlot(i);
                    equipped = blockName;
                    return true;
                }
            }
        }
        if (player.getMainHandStack().getItem() instanceof BlockItem) return true;
        // NO "ANY BLOCK WILL DO" FALLBACK. It used to grab the first BlockItem in the hotbar when
        // the named one was absent, so a //set cobblestone with no cobblestone quietly built the
        // wall out of whatever was lying in slot 1 — and a schematic came out the wrong colour
        // with every cell reported as placed. Upstream refuses instead:
        // selectThrowawayForLocation failing makes attemptToPlaceABlock return NO_OPTION and set
        // the movement UNREACHABLE (MovementHelper.java:819-823). Same answer here: say so, and
        // let the caller see it as a deferral rather than a silent substitution.
        return false;
    }
}
