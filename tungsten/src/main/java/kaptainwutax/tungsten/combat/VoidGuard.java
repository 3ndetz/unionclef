package kaptainwutax.tungsten.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Shared void-safety movement clamp — the final word on the movement keys so no
 * subsystem walks or launches the bot off a ledge. Used by the combat aura
 * (SafetySystem) and by the flee task (RunAwayTask, applied after the pathfinder
 * executor sets keys). Leverages vanilla sneak edge-protection.
 *
 * The rule: near a rim never sprint; when the heading (pressed keys OR raw
 * momentum) points at a serious drop, never jump toward it and plant with sneak.
 * The drive is only cancelled when the bot is actively STEERING off (its own
 * keys point at the drop) — when only momentum points there (knockback / a brake
 * pushing back toward the island) the toward-island input is kept and sneak is
 * merely added, so recovery isn't stranded at the edge.
 */
public final class VoidGuard {

    private VoidGuard() {}

    /**
     * @param pos feet position to test from (tick pos in combat, entity pos elsewhere)
     * @param vel current velocity (for the momentum/overshoot check)
     */
    public static void protect(ClientPlayerEntity player, Vec3d pos, Vec3d vel, WorldView world) {
        MinecraftClient mc = MinecraftClient.getInstance();
        double horizSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        boolean nearVoid = VoidDetector.voidWithin(pos, world, 3, 3);
        // speed-scaled lookahead so a sprinting bot sees the rim with room to stop
        double look = Math.max(1.4, horizSpeed * 10.0);
        double[] keyHeading = pressedHeading(player, mc);
        boolean edgeByKey = keyHeading != null
                && VoidDetector.edgeAhead(pos, keyHeading[0], keyHeading[1], world, 3, look);
        boolean edgeByVel = horizSpeed > 0.04
                && VoidDetector.edgeAhead(pos, vel.x, vel.z, world, 3, look);

        if (nearVoid) {
            mc.options.sprintKey.setPressed(false);
        }
        // Jump suppression uses a LONGER lookahead: a jump carries the bot ~4
        // blocks, so a rim that's still 2-3 blocks ahead (invisible to the walk
        // lookahead above) is exactly where a crit- or brake-jump launches us off.
        double jumpLook = Math.max(3.2, horizSpeed * 16.0);
        boolean jumpTowardEdge =
                (keyHeading != null && VoidDetector.edgeAhead(pos, keyHeading[0], keyHeading[1], world, 3, jumpLook))
                || (horizSpeed > 0.04 && VoidDetector.edgeAhead(pos, vel.x, vel.z, world, 3, jumpLook));
        if (jumpTowardEdge) {
            mc.options.jumpKey.setPressed(false);
        }
        if (edgeByKey || edgeByVel) {
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(true);
            if (edgeByKey) {
                mc.options.forwardKey.setPressed(false);
                mc.options.backKey.setPressed(false);
                mc.options.leftKey.setPressed(false);
                mc.options.rightKey.setPressed(false);
                mc.options.sprintKey.setPressed(false);
            }
        }
    }

    /** Convenience overload using the player's current entity pos/velocity. */
    public static void protect(ClientPlayerEntity player, WorldView world) {
        protect(player, player.getEntityPos(), player.getVelocity(), world);
    }

    /** World-space horizontal heading (dx,dz) implied by the pressed movement
     *  keys relative to yaw, or null if none. MC convention: sideways +1 = LEFT. */
    public static double[] pressedHeading(ClientPlayerEntity player, MinecraftClient mc) {
        double fwd = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
        double strafe = (mc.options.leftKey.isPressed() ? 1 : 0) - (mc.options.rightKey.isPressed() ? 1 : 0);
        if (fwd == 0 && strafe == 0) return null;
        double yawRad = Math.toRadians(player.getYaw());
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        double dx = strafe * cos - fwd * sin;
        double dz = fwd * cos + strafe * sin;
        return new double[]{dx, dz};
    }
}
