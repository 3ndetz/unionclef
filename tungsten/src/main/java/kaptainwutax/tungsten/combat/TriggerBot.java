package kaptainwutax.tungsten.combat;

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
 * Trigger bot — swings when the target is genuinely hittable.
 *
 * Decides with its own checks (reach to the hitbox, COLLIDER line of sight,
 * rough look angle, attack cooldown) and delivers the attack directly via
 * interactionManager.attackEntity.
 *
 * The old version gated on vanilla mc.targetedEntity — an OUTLINE crosshair
 * pick. Tall grass has an outline but no collision, so it blocked the pick
 * while the COLLIDER-based aimer kept the lock: the bot froze in grass,
 * aiming forever and never firing. Aim lead also pushed the crosshair off
 * the CURRENT hitbox, suppressing swings on clear targets.
 */
public class TriggerBot {

    private static final float COOLDOWN_FULL = 0.95f;
    private static final float COOLDOWN_CRIT = 0.85f; // falling crit is worth an early swing
    private static final double REACH = 3.0;
    private static final double MAX_LOOK_ANGLE_DEG = 40.0;

    // prevent double-clicking on same cooldown cycle
    private boolean clickedThisCycle = false;
    // hit tracking for progress detection
    private int totalHits = 0;
    private int ticksSinceLastHit = 0;

    private int traceCooldown = 0;

    public void tick(ClientPlayerEntity player, Entity target) {
        MinecraftClient mc = MinecraftClient.getInstance();

        float cooldown = player.getAttackCooldownProgress(0f);
        if (cooldown < 0.3f) {
            clickedThisCycle = false;
        }

        ticksSinceLastHit++;

        boolean critWindow = !player.isOnGround() && player.fallDistance > 0.1f
                && player.getVelocity().y < 0;
        float threshold = critWindow ? COOLDOWN_CRIT : COOLDOWN_FULL;

        Vec3d eye = player.getEyePos();
        Box box = target.getBoundingBox();

        // vanilla reach model: eye to the closest point of the hitbox
        Vec3d closest = new Vec3d(
                MathHelper.clamp(eye.x, box.minX, box.maxX),
                MathHelper.clamp(eye.y, box.minY, box.maxY),
                MathHelper.clamp(eye.z, box.minZ, box.maxZ));
        double distSq = eye.squaredDistanceTo(closest);

        // rough look-angle sanity: the aimer keeps us close anyway, this only
        // rejects wild misalignment — no exact crosshair-on-hitbox pick needed
        Vec3d toTarget = box.getCenter().subtract(eye).normalize();
        Vec3d look = player.getRotationVec(1.0f);
        double angle = Math.toDegrees(Math.acos(
                MathHelper.clamp(look.dotProduct(toTarget), -1.0, 1.0)));

        // line of sight through COLLIDERS only — tall grass has no collision
        // shape and must not block the swing
        HitResult hit = player.getEntityWorld().raycast(new RaycastContext(
                eye, box.getCenter(), RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));

        boolean gateClick = clickedThisCycle;
        boolean gateCooldown = cooldown < threshold;
        boolean gateReach = distSq > REACH * REACH;
        boolean gateAngle = angle > MAX_LOOK_ANGLE_DEG;
        boolean gateLos = hit.getType() != HitResult.Type.MISS;

        if (kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging
                && (gateClick || gateCooldown || gateReach || gateAngle || gateLos)
                && ++traceCooldown >= 20) {
            traceCooldown = 0;
            kaptainwutax.tungsten.Debug.logMessage(String.format(
                "trigger gate: click=%b cd=%.2f reach2=%.2f angle=%.0f los=%s",
                gateClick, cooldown, distSq, angle, hit.getType()));
        }

        if (gateClick || gateCooldown || gateReach || gateAngle || gateLos) return;

        mc.interactionManager.attackEntity(player, target);
        player.swingHand(Hand.MAIN_HAND);
        clickedThisCycle = true;
        totalHits++;
        ticksSinceLastHit = 0;
    }

    /** True if no hits landed in the last N ticks. */
    public boolean hasNoProgress(int tickThreshold) {
        return ticksSinceLastHit > tickThreshold;
    }

    public int getTotalHits() { return totalHits; }
    public int getTicksSinceLastHit() { return ticksSinceLastHit; }

    public void reset() {
        clickedThisCycle = false;
        totalHits = 0;
        ticksSinceLastHit = 0;
    }
}
