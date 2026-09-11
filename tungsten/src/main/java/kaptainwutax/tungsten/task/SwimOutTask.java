package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Climb OUT of water onto a raised bank — the water counterpart to {@link PillarTask}.
 *
 * <h2>Why this exists</h2>
 *
 * FastNavigator meets a bank above the bot and, being unable to jump-step it, starts
 * {@link PillarTask} (place a block under yourself and rise). Pillaring needs solid footing to jump
 * and place from; in water the bot only bobs and places nothing — measured on a lake bench, 40s+
 * stuck at a +1 bank, and skipping to the physics engine did not swim it up either.
 *
 * <p>The right move from water is the one a player uses: at the surface, hold JUMP (which is "swim
 * up" in liquid) and hold FORWARD aimed at the ledge, and buoyancy plus forward carry you up and
 * onto it. That is all this task does, driven tick-by-tick from the client mixin exactly like
 * PillarTask, until the body is standing on the ledge and out of the water (or it stops making
 * progress and hands back).
 */
public class SwimOutTask {

    private static boolean active = false;
    private static BlockPos ledge;      // the stand cell to climb onto
    private static int stuckTicks;
    private static double bestDistSq;

    public static synchronized boolean startTo(BlockPos target) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null || target == null) return false;
        ledge = target;
        stuckTicks = 0;
        bestDistSq = Double.MAX_VALUE;
        active = true;
        Debug.logMessage("Swimming out onto a bank at y=" + target.getY());
        return true;
    }

    public static boolean isActive() { return active; }

    public static void stop() {
        active = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null) {
            mc.options.jumpKey.setPressed(false);
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
        if (mc.options == null || ledge == null) { stop(); return; }
        var opts = mc.options;

        // Done: standing on solid ground at/above the ledge and no longer in the water.
        if (player.isOnGround() && player.getY() >= ledge.getY() - 0.05 && !player.isTouchingWater()) {
            Debug.logMessage("Swam out onto the bank at y=" + String.format("%.1f", player.getY()));
            stop();
            return;
        }
        // Left the water without reaching the ledge (drifted onto a lower shore): hand back and let
        // navigation re-plan from solid ground rather than swim-driving on land.
        if (!player.isTouchingWater() && player.isOnGround()) {
            stop();
            return;
        }

        // Aim at the ledge centre (up-and-forward) — in water the body swims where it looks, so
        // this points the swim up onto the bank — and hold JUMP (rise) + FORWARD (toward it).
        Vec3d c = Vec3d.ofCenter(ledge);
        Vec3d dv = c.subtract(player.getEyePos());
        float wantYaw = (float) Math.toDegrees(-Math.atan2(dv.x, dv.z));
        float wantPitch = (float) Math.toDegrees(-Math.atan2(dv.y, Math.sqrt(dv.x * dv.x + dv.z * dv.z)));
        WindMouseRotation.INSTANCE.setTarget(wantYaw, wantPitch);
        opts.forwardKey.setPressed(true);
        opts.jumpKey.setPressed(true);      // JUMP in liquid = swim up
        opts.sprintKey.setPressed(false);
        opts.sneakKey.setPressed(false);

        // Progress on distance to the ledge; best-distance is monotonic so surface bobbing does not
        // trip it. ~3s of no approach and we hand back.
        double dx = player.getX() - (ledge.getX() + 0.5);
        double dy = player.getY() - (ledge.getY() + 0.5);
        double dz = player.getZ() - (ledge.getZ() + 0.5);
        double dsq = dx * dx + dy * dy + dz * dz;
        if (dsq < bestDistSq - 0.02) {
            bestDistSq = dsq;
            stuckTicks = 0;
        } else if (++stuckTicks > 60) {
            Debug.logMessage("Swim-out made no progress — handing back");
            stop();
        }
    }
}
