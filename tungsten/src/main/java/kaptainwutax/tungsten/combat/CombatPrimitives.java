package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.task.BowShooter;
import kaptainwutax.tungsten.task.ShieldBlocker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * The combat-primitives facade (docs/features/TUNGSTEN_ALTOCLEF_API.md):
 * tungsten's execution surface for the altoclef battle brain. Everything
 * here is stateless or delegates to a ticked primitive; strategy (weapon
 * choice, WHEN to strike, consumables, HP logic) lives on the altoclef side.
 */
public final class CombatPrimitives {

    public static final double MELEE_REACH = 3.0;

    private CombatPrimitives() {}

    /** Reach + COLLIDER line of sight + look angle — the melee gate without
     *  the cooldown part (the brain may check cooldown separately). */
    public static boolean canHit(ClientPlayerEntity player, Entity target, double maxLookAngleDeg) {
        Vec3d eye = player.getEyePos();
        Box box = target.getBoundingBox();
        Vec3d closest = new Vec3d(
                MathHelper.clamp(eye.x, box.minX, box.maxX),
                MathHelper.clamp(eye.y, box.minY, box.maxY),
                MathHelper.clamp(eye.z, box.minZ, box.maxZ));
        if (eye.squaredDistanceTo(closest) > MELEE_REACH * MELEE_REACH) return false;

        Vec3d toTarget = box.getCenter().subtract(eye).normalize();
        double angle = Math.toDegrees(Math.acos(MathHelper.clamp(
                player.getRotationVec(1.0f).dotProduct(toTarget), -1.0, 1.0)));
        if (angle > maxLookAngleDeg) return false;

        HitResult hit = player.getEntityWorld().raycast(new RaycastContext(
                eye, box.getCenter(), RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** Deliver a melee attack right now (no gate — combine with canHit). */
    public static void attack(ClientPlayerEntity player, Entity target) {
        MinecraftClient.getInstance().interactionManager.attackEntity(player, target);
        player.swingHand(Hand.MAIN_HAND);
    }

    /** Raise the shield for N ticks (shield in hand is the brain's job). */
    public static void shieldHold(int ticks) {
        ShieldBlocker.hold(ticks);
    }

    public static void shieldRelease() {
        ShieldBlocker.release();
    }

    public static boolean isShieldBlocking() {
        return ShieldBlocker.isBlocking();
    }

    /** Ballistic solution for a full-draw arrow at a moving entity. */
    public static TrajectorySolver.Solution solveArrow(ClientPlayerEntity player, Entity target) {
        return TrajectorySolver.solve(player, target, 1.0);
    }

    /** Fire an aimed, lead-predicted arrow (bow already in hand). */
    public static boolean shootArrow(Entity target) {
        return BowShooter.shootAt(target);
    }
}
