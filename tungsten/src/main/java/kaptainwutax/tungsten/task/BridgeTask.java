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
    private static int blocksLeft;
    private static int placed;
    private static int stuckTicks;
    private static double lastProgress;
    private static boolean stepping;   // false = PLACE phase, true = STEP phase
    private static int placeCd;        // ticks to wait between place attempts

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
        blocksLeft = Math.max(1, blocks);
        placed = 0;
        stuckTicks = 0;
        lastProgress = 0;
        stepping = false;
        placeCd = 0;
        active = true;
        Debug.logMessage("Bridging " + dir + " x" + blocksLeft);
        return true;
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
        }
    }

    /** Called every game tick from MixinClientPlayerEntity. */
    public static void tick(ClientPlayerEntity player) {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        var world = player.getEntityWorld();
        var opts = mc.options;

        if (blocksLeft <= 0 || player.getVelocity().y < -0.5) { // done or falling
            if (player.getVelocity().y < -0.5) Debug.logMessage("Bridge aborted (falling)");
            else Debug.logMessage("Bridge done: " + placed + " blocks");
            stop();
            return;
        }

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

        // walk forward along the bridge; no sneak (that's the slow/broken way)
        opts.sneakKey.setPressed(false);
        opts.sprintKey.setPressed(false);
        opts.forwardKey.setPressed(true);

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

        if (toPlace != null) {
            Vec3d faceCenter = Vec3d.ofCenter(against).add(Vec3d.of(dir.getVector()).multiply(0.5));
            Vec3d dv = faceCenter.subtract(eye);
            player.setYaw((float) Math.toDegrees(-Math.atan2(dv.x, dv.z)));
            player.setPitch((float) Math.toDegrees(-Math.atan2(dv.y, Math.sqrt(dv.x * dv.x + dv.z * dv.z))));
            BlockHitResult hit = new BlockHitResult(faceCenter, side, against, false);
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            player.swingHand(Hand.MAIN_HAND);
            if (!isAir(world, toPlace) && toPlace.equals(targetCell)) {
                // count a new floor cell laid directly ahead of us
                placed++;
                if (placed % 3 == 0) Debug.logMessage("Bridge paved " + placed);
                if (--blocksLeft <= 0) { Debug.logMessage("Bridge done: " + placed); stop(); return; }
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
