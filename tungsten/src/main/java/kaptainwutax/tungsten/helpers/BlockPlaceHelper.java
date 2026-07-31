package kaptainwutax.tungsten.helpers;

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

    /** Ticked exactly once per client tick. The single owner of the countdown. */
    public static void tickCooldown() {
        if (rightClickTimer > 0) rightClickTimer--;
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
    /** Consecutive ticks on which NOTHING in the scan window was buildable from where the
     *  player stands. Reset by any progress, including merely finding a face to aim at. */
    private static int idleTicks;
    private static String equipped;

    /** How long to keep looking before handing the rest back. Generous: the aim is humanized
     *  (WindMouse), so convergence is tens of ticks at worst, and the caller may be walking. */
    private static final int IDLE_TIMEOUT_TICKS = 60;
    /** How far down the queue one tick looks for something buildable. Bounded because this runs
     *  on the client tick and the queue can hold a whole sphere. */
    private static final int SCAN_LIMIT = 64;

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
        DEFERRED.clear();
    }

    public static synchronized void clearQueue() {
        QUEUE.clear();
        idleTicks = 0;
        equipped = null;
    }

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
            for (Direction dir : kaptainwutax.tungsten.path.movements.Movement
                    .HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
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
                equipBlock(player, cell.blockName());
                kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE
                        .setTarget(place.getYaw(), place.getPitch());
                idleTicks = 0;
                BlockHitResult hit = RealPlacement.readyToPlace(mc, target);
                if (hit == null) return;         // aim still on its way; hold this cell
                if (!tryPlace(hit)) return;      // rate gate closed this tick
                it.remove();
                placedFromQueue++;
                return;
            }
        }

        // Nothing in the scan window can be built from where the player is standing. Give the
        // caller time to walk (it may be pathing right now), then hand the rest back rather than
        // spinning forever.
        if (++idleTicks > IDLE_TIMEOUT_TICKS) {
            for (Cell c : QUEUE) {
                DEFERRED.add(c.pos());
                deferNoFace++;
            }
            QUEUE.clear();
            idleTicks = 0;
        }
    }

    /** Select the hotbar slot holding {@code blockName}; if unnamed or absent, any block item
     *  will do. Skips the lookup when the wanted type is already equipped. */
    private static void equipBlock(ClientPlayerEntity player, String blockName) {
        if (blockName != null && blockName.equals(equipped)
                && player.getMainHandStack().getItem() instanceof BlockItem) {
            return;
        }
        if (blockName != null && !blockName.isEmpty()) {
            String want = blockName.contains(":") ? blockName : "minecraft:" + blockName;
            for (int i = 0; i < 9; i++) {
                ItemStack st = player.getInventory().getStack(i);
                if (st.isEmpty()) continue;
                if (Registries.ITEM.getId(st.getItem()).toString().equals(want)) {
                    player.getInventory().setSelectedSlot(i);
                    equipped = blockName;
                    return;
                }
            }
        }
        if (player.getMainHandStack().getItem() instanceof BlockItem) return;
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).getItem() instanceof BlockItem) {
                player.getInventory().setSelectedSlot(i);
                equipped = null;
                return;
            }
        }
    }
}
