package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
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

        if (player.getVelocity().y < -0.5) { // falling — paving fell behind
            Debug.logMessage("Bridge aborted (falling) after " + placed);
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

        // need a block in hand — the caller equips it (selectHotbar); tungsten
        // must not depend on altoclef's inventory layer (dependency direction)
        if (!(player.getMainHandStack().getItem() instanceof BlockItem)) {
            Debug.logMessage("Bridge: no block in hand — equip one first");
            stop();
            return;
        }

        // ofFloored(y-0.1) IS the block the player stands on (the support).
        BlockPos support = BlockPos.ofFloored(player.getX(), player.getY() - 0.1, player.getZ());
        // Continuous godbridge model: NO sneak, walk forward, and PAVE the floor
        // ahead every tick so a flat block always exists before our feet reach
        // the edge — targetCell is at the SAME level as support, so it's flat
        // ground: nothing to fall off. Pace vs place-rate keeps us supported.
        BlockPos targetCell = support.offset(dir);
        Vec3d eye = player.getEyePos();

        // SPRINT forward along the bridge — real godbridge speed. No sneak
        // (that's the slow/broken way). Force sprint at the entity level so it
        // actually engages (the key alone doesn't always re-trigger it).
        opts.sneakKey.setPressed(false);
        opts.forwardKey.setPressed(true);
        opts.sprintKey.setPressed(true);
        player.setSprinting(true);

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
            kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.setTarget(wantYaw, wantPitch);
            BlockHitResult hit =
                    kaptainwutax.tungsten.helpers.RealPlacement.readyToPlace(mc, toPlace);
            if (hit != null) {
                mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
                player.swingHand(Hand.MAIN_HAND);
                // remember this bridge block as scaffolding so a cleanup can mine it back out
                kaptainwutax.tungsten.util.ScaffoldRegistry.record(toPlace);
            }
        }

        // stuck detection along the bridge axis
        double progress = dir.getAxis() == Direction.Axis.X ? player.getX() : player.getZ();
        if (Math.abs(progress - lastProgress) < 0.02) {
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
