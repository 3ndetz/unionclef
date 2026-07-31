package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.path.PlaceRules;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Pillar up: reach a raised goal (a ledge / tree top / tower) by placing blocks
 * under yourself — the vertical counterpart to {@link BridgeTask}. Classic
 * Minecraft pillaring, driven tick-by-tick: stay centred, jump, and while airborne
 * place a block into the air cell under your feet (against the block below it), so
 * you land one higher. Repeat to the target Y.
 *
 * A directed execution primitive (like BridgeTask). The pathfinder integration
 * (place-as-a-move) drives it; it's also exposed via py4j (pillarTo) and is the
 * reach mechanism for goals #27's give-up currently abandons.
 */
public class PillarTask {

    private static boolean active = false;
    private static int targetY;
    private static int placed;
    private static int stuckTicks;
    private static double lastY;

    public static synchronized boolean startTo(int ty) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return false;
        targetY = ty;
        placed = 0;
        stuckTicks = 0;
        lastY = p.getY();
        active = true;
        Debug.logMessage("Pillaring up to y=" + ty);
        return true;
    }

    public static boolean isActive() { return active; }
    public static int getPlaced() { return placed; }

    public static void stop() {
        active = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null) {
            mc.options.jumpKey.setPressed(false);
            mc.options.useKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
        }
        WindMouseRotation.INSTANCE.clearTarget();
    }

    /** Called every game tick from MixinClientPlayerEntity. */
    public static void tick(ClientPlayerEntity player) {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        WorldView world = player.getEntityWorld();
        var opts = mc.options;

        // reached target height (standing on / at the target level)
        if (player.getY() >= targetY - 0.05 && player.isOnGround()) {
            Debug.logMessage("Pillar done at y=" + String.format("%.1f", player.getY()) + " (placed " + placed + ")");
            stop();
            return;
        }

        // need a block in hand — the caller equips it (dependency direction: tungsten
        // must not reach into altoclef's inventory layer).
        if (!(player.getMainHandStack().getItem() instanceof BlockItem)) {
            Debug.logMessage("Pillar: no block in hand — equip one first");
            stop();
            return;
        }

        // Stay centred over the column (no horizontal drift) and aim straight down.
        opts.forwardKey.setPressed(false);
        opts.sprintKey.setPressed(false);
        opts.sneakKey.setPressed(false);
        WindMouseRotation.INSTANCE.setTarget(player.getYaw(), 89f); // pitch +89 = down

        // Jump off the ground; release jump while airborne (single hop per block).
        opts.jumpKey.setPressed(player.isOnGround());

        // Find the air cell directly under the player that has a solid block below it
        // (within 2 down) — that's where the pillar block goes. Only place while
        // airborne and rising, so we don't fight our own footing.
        if (!player.isOnGround() && player.getVelocity().y > -0.15) {
            double px = player.getX(), pz = player.getZ();
            BlockPos placeAt = null, against = null;
            for (int dy = 0; dy <= 2; dy++) {
                BlockPos c = BlockPos.ofFloored(px, player.getY() - dy, pz);
                BlockPos b = c.down();
                if (isAir(world, c) && !isAir(world, b)) { placeAt = c; against = b; break; }
            }
            if (placeAt != null) {
                if (!PlaceRules.canPlace(world, placeAt)) {
                    Debug.logMessage("Pillar stopped: protected/denied at " + placeAt.toShortString());
                    stop();
                    return;
                }
                // THIS DID NOT EVEN AIM. It forged a hit on the top face of the block below
                // and clicked, so a tower went up with the camera pointing anywhere at all —
                // a placement through geometry, not a placement. Now: look DOWN at that face
                // through the mouse pipeline, and click only when the player's own crosshair
                // agrees it would fill the cell (RealPlacement, ported from baritone's
                // MovementHelper.attemptToPlaceABlock).
                Vec3d faceCenter = Vec3d.ofCenter(against).add(0, 0.5, 0); // top face of the block below
                Vec3d dv = faceCenter.subtract(player.getEyePos());
                float wantYaw = (float) Math.toDegrees(-Math.atan2(dv.x, dv.z));
                float wantPitch = (float) Math.toDegrees(
                        -Math.atan2(dv.y, Math.sqrt(dv.x * dv.x + dv.z * dv.z)));
                kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.setTarget(wantYaw, wantPitch);
                BlockHitResult hit =
                        kaptainwutax.tungsten.helpers.RealPlacement.readyToPlace(mc, placeAt);
                // Same shared rate gate as every other placement (helpers/BlockPlaceHelper).
                if (hit != null && kaptainwutax.tungsten.helpers.BlockPlaceHelper.tryPlace(hit)) {
                    // remember this pillar block as scaffolding so a cleanup can mine it back out
                    kaptainwutax.tungsten.util.ScaffoldRegistry.record(placeAt);
                }
            }
        }

        // Progress / stuck detection on Y.
        if (player.getY() - lastY > 0.5) {
            placed++;
            lastY = player.getY();
            stuckTicks = 0;
        } else if (Math.abs(player.getY() - lastY) < 0.02) {
            if (++stuckTicks > 80) { // ~4s no vertical progress
                Debug.logMessage("Pillar stuck at y=" + String.format("%.1f", player.getY()));
                stop();
            }
        }
    }

    private static boolean isAir(WorldView w, BlockPos p) {
        return w.getBlockState(p).getCollisionShape(w, p).isEmpty();
    }
}
