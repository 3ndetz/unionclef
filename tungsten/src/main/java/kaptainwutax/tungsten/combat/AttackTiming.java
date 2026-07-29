package kaptainwutax.tungsten.combat;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * Attack timing logic: cooldown, reach, crit detection.
 * Vanilla 1.21 crit conditions: !onGround && velY < 0 && !climbing && !water && !blind.
 */
public final class AttackTiming {

    private static final double REACH = 3.0;
    private static final float MIN_COOLDOWN = 0.9f;

    private AttackTiming() {}

    /** True if player can attack target right now (in reach + cooldown ready). */
    public static boolean canAttack(ClientPlayerEntity player, Entity target) {
        if (target == null || !target.isAlive()) return false;
        double dist = player.squaredDistanceTo(target);
        if (dist > REACH * REACH) return false;
        return player.getAttackCooldownProgress(0.5f) >= MIN_COOLDOWN;
    }


    /**
     * Compute yaw from player to target entity.
     * Returns degrees, same coordinate system as Minecraft yaw.
     */
    /**
     * Would a swing RIGHT NOW be a critical hit? Vanilla's rule, nothing invented: falling,
     * off the ground, not in water, not on a ladder, not slowed in the air, not riding.
     * A crit is +50% damage and it is free — the bot is already jumping around the target.
     */
    public static boolean isCrit(ClientPlayerEntity player) {
        return player.getVelocity().y < 0.0
                && !player.isOnGround()
                && !player.isClimbing()
                && !player.isTouchingWater()
                && !player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS)
                && !player.hasVehicle();
    }

    public static float yawTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) (Math.toDegrees(-Math.atan2(dx, dz)));
    }

    /**
     * Compute pitch from player eyes to target center.
     */
    public static float pitchTo(Vec3d eyePos, Vec3d targetCenter) {
        double dx = targetCenter.x - eyePos.x;
        double dy = targetCenter.y - eyePos.y;
        double dz = targetCenter.z - eyePos.z;
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        return (float) (Math.toDegrees(-Math.atan2(dy, horizDist)));
    }
}
