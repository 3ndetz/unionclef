package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Epic sneak-bridge across a gap (TODO 7.2, first directed form). Extends a
 * one-wide floor of placed blocks in a cardinal direction: stand at the edge,
 * sneak so you don't fall, aim at the side face of the block under your feet,
 * right-click to place a block floating into the gap, then step onto it — the
 * classic Minecraft bridge, driven tick-by-tick.
 *
 * A directed behavior, not (yet) an A* move — the physics-integration into the
 * pathfinder comes on top. Triggered via py4j (bridgeForward), ticked from
 * MixinClientPlayerEntity on the client thread.
 */
public class BridgeTask {

    private static boolean active = false;
    private static Direction dir;
    private static int blocksRequested;
    private static int placed;
    private static int stuckTicks;
    private static double lastProgress;
    /** {@link #placed} as of the previous tick — a placement between ticks is progress. */
    private static int placedAtLastCheck;
    /** True on ticks where a cell was wanted and the aim was being driven at it: the bot is
     *  standing still ON PURPOSE, which the stuck watchdog must not read as a fault. */
    private static boolean aimingToPlace;
    /** Consecutive ticks spent aiming without a placement landing. */
    private static int aimTicks;
    /** How long the aim is allowed to fail to converge before the bridge says so and stops.
     *  Generous — WindMouse is humanised and the rate gate is four ticks — but finite. */
    private static final int AIM_PATIENCE = 100;
    private static double startAlong = Double.NaN; // position along bridge axis at start

    public static synchronized boolean start(String direction, int blocks) {
        Direction d = switch (direction == null ? "" : direction.toLowerCase()) {
            case "north", "-z" -> Direction.NORTH;
            case "south", "+z" -> Direction.SOUTH;
            case "west", "-x" -> Direction.WEST;
            case "east", "+x" -> Direction.EAST;
            default -> null;
        };
        if (d == null) {
            // infer from the player's facing (nearest horizontal cardinal)
            ClientPlayerEntity p = MinecraftClient.getInstance().player;
            if (p == null) return false;
            float yaw = MathHelper.wrapDegrees(p.getYaw());
            if (yaw >= -45 && yaw < 45) d = Direction.SOUTH;      // +z
            else if (yaw >= 45 && yaw < 135) d = Direction.WEST;  // -x
            else if (yaw >= -135 && yaw < -45) d = Direction.EAST; // +x
            else d = Direction.NORTH;                              // -z
        }
        dir = d;
        blocksRequested = Math.max(1, blocks);
        placed = 0;
        stuckTicks = 0;
        placedAtLastCheck = 0;
        aimingToPlace = false;
        aimTicks = 0;
        lastProgress = 0;
        startAlong = Double.NaN;
        active = true;
        // fast-but-human WindMouse convergence for the fixed bridge aim
        kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.setParams(4.5, 0.8, 9.0, 20.0, 0.5, 3.0);
        Debug.logMessage("Bridging " + dir + " x" + blocksRequested);
        return true;
    }

    /** Bridge TOWARD a target position — picks the dominant horizontal cardinal
     *  and bridges until the along-axis position reaches the target (for
     *  bedwars: bridge to the enemy island/bed). */
    public static boolean startTo(int tx, int ty, int tz) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return false;
        double dx = tx + 0.5 - p.getX(), dz = tz + 0.5 - p.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return start(dx >= 0 ? "east" : "west", (int) Math.ceil(Math.abs(dx)));
        }
        return start(dz >= 0 ? "south" : "north", (int) Math.ceil(Math.abs(dz)));
    }

    public static boolean isActive() { return active; }
    public static int getPlaced() { return placed; }

    public static void stop() {
        active = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null) {
            mc.options.sneakKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
            mc.options.useKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
        }
        kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
        kaptainwutax.tungsten.TungstenModRenderContainer.TEST.clear();
    }

    /** Called every game tick from MixinClientPlayerEntity. */
    public static void tick(ClientPlayerEntity player) {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        var world = player.getEntityWorld();
        var opts = mc.options;

        // PER-TICK TRACE, because three hypotheses about this abort have now been wrong. It prints
        // the only things the fall test is made of — where the body is, what it is standing on,
        // and how fast it is going down — so the next reading is evidence rather than a theory.
        if (kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging) {
            System.out.println(String.format(
                    "BRIDGETRACE pos=(%.2f,%.2f,%.2f) vel=%.3f ground=%b sneakPose=%b placed=%d",
                    player.getX(), player.getY(), player.getZ(),
                    player.getVelocity().y, player.isOnGround(), player.isInSneakingPose(), placed));
        }
        if (player.getVelocity().y < -0.5) { // falling — paving fell behind
            Debug.logMessage("Bridge aborted (falling) after " + placed
                    + String.format(" at (%.2f,%.2f,%.2f) vel=%.3f ground=%b sneak=%b",
                    player.getX(), player.getY(), player.getZ(),
                    player.getVelocity().y, player.isOnGround(), player.isInSneakingPose()));
            stop();
            return;
        }
        // stop once we've advanced the requested number of blocks
        double along = dir.getAxis() == Direction.Axis.X ? player.getX() : player.getZ();
        if (Double.isNaN(startAlong)) startAlong = along;
        double advanced = dir.getDirection() == Direction.AxisDirection.POSITIVE
                ? along - startAlong : startAlong - along;
        if (advanced >= blocksRequested) {
            Debug.logMessage("Bridge done: " + (int) advanced + " blocks");
            stop();
            return;
        }
        placed = (int) Math.max(0, advanced);

        // RE-EQUIP RATHER THAN GIVE UP. This used to abort the moment the held stack ran out —
        // which mid-bridge means stopping on a one-block ledge over the gap you were crossing.
        // Upstream re-selects a throwaway on every placement attempt and only reports NO_OPTION
        // when the inventory has nothing at all (MovementHelper.java:819-823).
        if (!kaptainwutax.tungsten.helpers.BlockPlaceHelper.equipThrowaway(player)) {
            Debug.logMessage("Bridge: out of blocks — nothing placeable in the hotbar");
            stop();
            return;
        }

        // THE SUPPORT MUST BE A BLOCK THAT EXISTS. ofFloored takes the cell under the player's
        // CENTRE — and at the sneak limit the centre is already PAST the edge, hanging over air,
        // held up by the block behind. So the task aimed at a support that was not there and
        // targeted the cell beyond it. The crosshair diagnostic caught it exactly:
        //   BRIDGEAIM want=2,-61,0 against=1,-61,0 wantYaw=90.0/90.0 wantPitch=68.0/68.0
        //             hit=0,-61,0 side=up -> fills 0,-60,0
        // The aim had converged perfectly (asked 90/68, camera at 90/68) onto a face of a block
        // that does not exist, while the real block under the bot was one cell back — and the ray
        // landed on ITS top face, which would have built a step on the bot's own head. Not an aim
        // problem at all; the geometry was being computed from a cell of air.
        //
        // Walk the support back along the bridge until it is solid, which is what "the block the
        // player stands on" means when the player is sneaking over a ledge.
        BlockPos support = BlockPos.ofFloored(player.getX(), player.getY() - 0.1, player.getZ());
        for (int back = 0; back < 2 && isAir(world, support); back++) {
            support = support.offset(dir.getOpposite());
        }
        // Continuous godbridge model: NO sneak, walk forward, and PAVE the floor
        // ahead every tick so a flat block always exists before our feet reach
        // the edge — targetCell is at the SAME level as support, so it's flat
        // ground: nothing to fall off. Pace vs place-rate keeps us supported.
        BlockPos targetCell = support.offset(dir);
        Vec3d eye = player.getEyePos();

        // DO NOT OUTRUN THE FLOOR. The godbridge model above — sprint and pave on the move,
        // never sneak — only holds while the placement is INSTANT, and it was: the old code
        // forged its BlockHitResult and the block appeared the tick it was asked for. Now that
        // placement goes through the game's own ray trace it takes real ticks, and sprinting
        // walked the bot straight off the lip: 22.5 blocks short, the void-fall signature.
        //
        // So sneak exactly when there is nothing to walk onto. Sneaking cannot fall off a
        // ledge, and it still lets the body creep to the sneak limit (centre = edge + 0.3,
        // since the 0.6-wide box keeps support while x - 0.3 is over the block) — which is the
        // ONLY position from which the block's side face is visible at all. Forward stays
        // pressed: that creep is what the manoeuvre needs. Sprint only on real ground.
        // PACE AGAINST THE PLACE RATE, TWO CELLS AHEAD. Looking only ONE cell ahead is what
        // dropped the bot into the void: the cell ahead of "one short of the lip" IS the lip, so
        // floorAhead was true, the bot sprinted — and at this stand's 8-12 fps a single
        // sprinting tick carries it clean past the lip before the next tick can sneak. Measured
        // 22.5 blocks short, 3 of 3, twice over. Sprint only when there is floor for the tick
        // AFTER this one as well, and sneak the moment the lip is within one cell: sneaking
        // cannot walk off an edge, and it still lets the body creep to the sneak limit
        // (centre = edge + 0.3, the 0.6-wide box keeping support), which is the only place from
        // which the block's side face can be seen at all.
        boolean floorAhead = !isAir(world, targetCell);
        boolean floorTwoAhead = !isAir(world, targetCell.offset(dir));
        boolean lipNear = !floorAhead || !floorTwoAhead;
        // WALK ALONG THE BRIDGE, NOT ALONG THE CAMERA. forwardKey moves the body wherever the
        // camera happens to point, and this task points the camera at the FACE it is placing
        // against — so while WindMouse converges, "forward" is whatever heading the aim is
        // passing through. Measured: a bridge asked to run due east ended at z=1.5 having
        // started at z=0.5, off the side of its own lip, with zero blocks placed and
        // "Bridge aborted (falling) after 0" in chat.
        //
        // The fix is not new: Movement.motionYaw plus MixinEntityMotionYaw exist precisely to
        // resolve a tick's inputs in a DECLARED facing while the camera is still travelling, and
        // that mechanism's own javadoc cites a fall into the void as the trace it was built from.
        // BridgeTask never declared a frame, so it fell into the void the mechanism was written
        // for. The frame is cleared globally at the end of ClientPlayerEntity.tick, so declaring
        // it here owns this tick only.
        kaptainwutax.tungsten.path.movements.Movement.motionYaw =
                dir.getAxis() == Direction.Axis.X
                        ? (dir.getOffsetX() > 0 ? -90.0f : 90.0f)
                        : (dir.getOffsetZ() > 0 ? 0.0f : 180.0f);
        kaptainwutax.tungsten.path.movements.Movement.motionPitch = player.getPitch();
        // AT THE LIP, TURN ROUND — the backplace, which this task never had. Walking forward and
        // LOOKING forward cannot work at the edge, and the trace said so exactly: parked at the
        // sneak limit, "101 ticks at (1.29,-60.00,0.50), the face never came into view". The eye
        // was at x=1.29 and the face it had to click at x=1.0, i.e. BEHIND it. You cannot look at
        // the east face of the block you are standing on from past its east edge.
        //
        // Upstream's answer is MovementTraverse's backplace, and it is already ported and working
        // in PathExecutor.tickPlacing: face BACK up the bridge, look down at the face of the block
        // you are standing on, press MOVE_BACK — which carries the body FORWARD in the world while
        // the camera keeps looking at the face — and sneak so stepping over the empty cell is not
        // a fall. The new block appears beneath you. That is why a bot bridges walking backwards.
        opts.sneakKey.setPressed(lipNear);
        opts.forwardKey.setPressed(!lipNear);
        opts.backKey.setPressed(lipNear);
        opts.sprintKey.setPressed(!lipNear);
        player.setSprinting(!lipNear);

        // aim: prefer paving the cell 2 ahead as well so a fast walk never
        // outruns the floor. Place the nearest air cell in {targetCell, +2}.
        BlockPos toPlace = null;
        BlockPos against = null;
        Direction side = dir;
        if (isAir(world, targetCell)) {
            toPlace = targetCell; against = support;           // side face of the block we're on
        } else if (isAir(world, targetCell.offset(dir))) {
            toPlace = targetCell.offset(dir); against = targetCell; // extend one more ahead
        }

        // visualize the next cell we're about to place (green) — the PLACE_PLAN
        // container, gated by renderPlacePlan in MixinDebugRenderer
        kaptainwutax.tungsten.TungstenModRenderContainer.PLACE_PLAN.clear();
        kaptainwutax.tungsten.TungstenModRenderContainer.PLACE_PLAN.add(new kaptainwutax.tungsten.render.Cuboid(
                new Vec3d(targetCell.getX() + 0.1, targetCell.getY() + 0.1, targetCell.getZ() + 0.1),
                new Vec3d(0.8, 0.3, 0.8), new kaptainwutax.tungsten.render.Color(60, 220, 120)));

        aimingToPlace = false;
        // honour protected areas / claims (same policy as the pathfinder)
        if (toPlace != null && !kaptainwutax.tungsten.path.PlaceRules.canPlace(world, toPlace)) {
            Debug.logMessage("Bridge stopped: protected area at " + toPlace.toShortString());
            stop();
            return;
        }

        if (toPlace != null) {
            Vec3d faceCenter = Vec3d.ofCenter(against).add(Vec3d.of(dir.getVector()).multiply(0.5));
            Vec3d dv = faceCenter.subtract(eye);
            // Humanized aim: never setYaw/setPitch (anti-cheat flags it instantly)
            // — feed the target to WindMouse, which rotates via the vanilla mouse
            // pipeline so the server sees physical-mouse steps. The placement then WAITS for
            // that aim: it used to forge a BlockHitResult from the face centre and place
            // "independent of camera lag", i.e. through the block's edge with the camera
            // pointing elsewhere. See RealPlacement, ported from baritone.
            float wantYaw = (float) Math.toDegrees(-Math.atan2(dv.x, dv.z));
            float wantPitch = (float) Math.toDegrees(-Math.atan2(dv.y, Math.sqrt(dv.x * dv.x + dv.z * dv.z)));
            if (lipNear) {
                // PathExecutor.tickPlacing:650-656 verbatim in intent: the yaw runs FROM the cell
                // being paved TOWARDS the head, which is "facing back up the bridge". The pitch
                // stays on the face — it is the same face, seen from the other side.
                Vec3d back = eye.subtract(Vec3d.ofCenter(toPlace));
                wantYaw = (float) Math.toDegrees(-Math.atan2(back.x, back.z));
            }
            kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.setTarget(wantYaw, wantPitch);
            // The body must follow the BRIDGE, not this camera: with MOVE_BACK held, the declared
            // motion frame is the reverse of the bridge axis so "back" resolves to forward along it.
            if (lipNear) {
                kaptainwutax.tungsten.path.movements.Movement.motionYaw = wantYaw;
                kaptainwutax.tungsten.path.movements.Movement.motionPitch = player.getPitch();
            }
            aimingToPlace = true;   // standing still to aim is work, not a stall
            BlockHitResult hit =
                    kaptainwutax.tungsten.helpers.RealPlacement.readyToPlace(mc, toPlace);
            // WHAT IS THE CROSSHAIR ACTUALLY ON? The one question that separates "the aim has not
            // converged yet" from "this face cannot be hit from here", and guessing between those
            // two has now cost three passes.
            if (hit == null && kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging) {
                var live = kaptainwutax.tungsten.path.movements.RotationHelper.liveHit(player);
                String what = "null";
                if (live instanceof BlockHitResult lb
                        && live.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
                    what = lb.getBlockPos().toShortString() + " side=" + lb.getSide()
                            + " -> fills " + lb.getBlockPos().offset(lb.getSide()).toShortString();
                } else if (live != null) {
                    what = String.valueOf(live.getType());
                }
                System.out.println(String.format(
                        "BRIDGEAIM want=%s against=%s wantYaw=%.1f/%.1f wantPitch=%.1f/%.1f hit=%s",
                        toPlace.toShortString(), against.toShortString(),
                        wantYaw, player.getYaw(), wantPitch, player.getPitch(), what));
            }
            // Rate through the shared gate (helpers/BlockPlaceHelper): this ticks once per client
            // tick, and a bridge is the exact case where placing every tick looks inhuman.
            if (hit != null && kaptainwutax.tungsten.helpers.BlockPlaceHelper.tryPlace(hit)) {
                // remember this bridge block as scaffolding so a cleanup can mine it back out
                kaptainwutax.tungsten.util.ScaffoldRegistry.record(toPlace);
            }
        }

        // STUCK DETECTION, AND PLACING IS NOT STUCK. This measured movement along the bridge
        // axis and gave up after sixty motionless ticks — three seconds — which was fine while a
        // placement was instantaneous, because the bot only ever stood still when something was
        // wrong. It is not fine now: a placement goes through the game's own ray trace and the
        // shared four-tick rate gate, so standing still while the aim converges is the bot doing
        // its job. Measured after that change: bridgeForward ran for exactly three seconds and
        // stopped having placed ZERO blocks, every time.
        //
        // FastNavigator already carries this exact lesson in its own watchdog ("BUILDING IS
        // PROGRESS, even though the distance does not move"); BridgeTask was simply never given
        // it. Aiming at a cell we intend to fill counts, and so does having just filled one.
        // AIMING COUNTS AS PROGRESS, BUT NOT FOREVER. Letting it count without a bound was my
        // own mistake and the trace caught it: the bot parks at the sneak limit
        //   pos=(1.29,-60.00,0.50) vel=-0.078 ground=true sneakPose=true placed=0
        // aiming at a face it can never see — x=1.29 is PAST the face at x=1.0, so the ray trace
        // will never agree — and because aiming reset the watchdog every tick, a three-second
        // loud failure became an unbounded silent one. A wait that cannot end is not a wait.
        //
        // (The "Bridge aborted (falling)" lines that sent me chasing a fall were from EARLIER
        // runs still in the chat ring. vel=-0.078 is resting gravity; nothing was ever falling.)
        double progress = dir.getAxis() == Direction.Axis.X ? player.getX() : player.getZ();
        if (placed != placedAtLastCheck) aimTicks = 0;
        else if (aimingToPlace && ++aimTicks > AIM_PATIENCE) {
            Debug.logMessage("Bridge gave up aiming after " + placed
                    + String.format(" — %d ticks at (%.2f,%.2f,%.2f), the face never came into view",
                    aimTicks, player.getX(), player.getY(), player.getZ()));
            stop();
            return;
        }
        boolean working = placed != placedAtLastCheck || aimingToPlace;
        placedAtLastCheck = placed;
        if (working) {
            stuckTicks = 0;
            lastProgress = progress;
        } else if (Math.abs(progress - lastProgress) < 0.02) {
            if (++stuckTicks > 60) { Debug.logMessage("Bridge stuck at " + placed); stop(); return; }
        } else {
            stuckTicks = 0;
            lastProgress = progress;
        }
    }

    private static boolean isAir(net.minecraft.world.WorldView w, BlockPos p) {
        return w.getBlockState(p).getCollisionShape(w, p).isEmpty();
    }
}
