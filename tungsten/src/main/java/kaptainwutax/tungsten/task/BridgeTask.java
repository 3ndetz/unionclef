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

        BlockPos foot = BlockPos.ofFloored(player.getX(), player.getY() - 0.1, player.getZ());
        BlockPos support = foot.down();            // block we stand on
        BlockPos targetCell = support.offset(dir); // floating block goes here, becomes the next floor

        // sneak is ALWAYS held so we never walk off the edge into the void
        opts.sneakKey.setPressed(true);
        opts.sprintKey.setPressed(false);

        // face along the bridge and look down at the support's outward side face
        Vec3d faceCenter = Vec3d.ofCenter(support).add(Vec3d.of(dir.getVector()).multiply(0.5));
        Vec3d eye = player.getEyePos();
        Vec3d dv = faceCenter.subtract(eye);
        player.setYaw((float) Math.toDegrees(-Math.atan2(dv.x, dv.z)));
        player.setPitch((float) Math.toDegrees(-Math.atan2(dv.y, Math.sqrt(dv.x * dv.x + dv.z * dv.z))));

        boolean targetSolid = !world.getBlockState(targetCell).getCollisionShape(world, targetCell).isEmpty();

        if (!stepping) {
            // PLACE phase: stand still (no forward), place the block ahead
            opts.forwardKey.setPressed(false);
            if (targetSolid) {
                stepping = true; // already there — go step onto it
            } else if (placeCd <= 0) {
                BlockHitResult hit = new BlockHitResult(faceCenter, dir, support, false);
                mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
                player.swingHand(Hand.MAIN_HAND);
                placeCd = 4;
                if (!world.getBlockState(targetCell).getCollisionShape(world, targetCell).isEmpty()) {
                    placed++;
                    Debug.logMessage("Bridge placed " + placed + " (" + targetCell.toShortString() + ")");
                    stepping = true;
                }
            } else {
                placeCd--;
            }
        } else {
            // STEP phase: creep forward (sneaking) until standing on the new block
            opts.forwardKey.setPressed(true);
            BlockPos newFoot = BlockPos.ofFloored(player.getX(), player.getY() - 0.1, player.getZ());
            if (!newFoot.equals(foot) && newFoot.getSquaredDistance(support.offset(dir)) < 0.6) {
                // moved onto the freshly placed floor cell — next block
                stepping = false;
                blocksLeft--;
                lastProgress = dir.getAxis() == Direction.Axis.X ? player.getX() : player.getZ();
            }
        }

        // stuck detection
        double progress = dir.getAxis() == Direction.Axis.X ? player.getX() : player.getZ();
        if (Math.abs(progress - lastProgress) < 0.02) {
            if (++stuckTicks > 80) { Debug.logMessage("Bridge stuck"); stop(); return; }
        } else {
            stuckTicks = 0;
            lastProgress = progress;
        }
    }
}
