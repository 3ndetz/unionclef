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
    // Package-visible: CombatController's reach control holds its distance against the same
    // threshold this gate fires at, so the mover and the swing cannot disagree about "armed".
    static final float COOLDOWN_CRIT = 0.85f; // falling crit is worth an early swing
    /** Charge accepted when a mob is close enough to hit us: enough to knock it back. */
    private static final float COOLDOWN_INTERRUPT = 0.55f;
    /** Centre-to-centre band inside which a mob's swing can land -- measured, worst case 2.00. */
    private static final double DANGER_BAND = 2.10;

    /**
     * Vanilla melee entity reach, measured EYE -> CLOSEST POINT OF THE HITBOX.
     *
     * <p>Public and the single source of truth on purpose: the combat mover used to keep its
     * own, looser idea of "close enough" (3.4 blocks CENTRE-TO-CENTRE), which at a player
     * hitbox works out to ~3.1 eye-to-hitbox — i.e. it parked the bot permanently OUTSIDE
     * the distance this gate will fire at, so the bot neither closed in nor ever swung.
     * Anything that decides how close to get must derive from this constant.
     */
    public static final double REACH = 3.0;

    private static final double MAX_LOOK_ANGLE_DEG = 40.0;

    /** Distance from the player's eyes to the nearest point of the target hitbox —
     *  the metric vanilla actually uses to decide whether an attack lands. */
    public static double eyeToHitbox(ClientPlayerEntity player, Entity target) {
        Vec3d eye = player.getEyePos();
        Box box = target.getBoundingBox();
        Vec3d closest = new Vec3d(
                MathHelper.clamp(eye.x, box.minX, box.maxX),
                MathHelper.clamp(eye.y, box.minY, box.maxY),
                MathHelper.clamp(eye.z, box.minZ, box.maxZ));
        return eye.distanceTo(closest);
    }

    // prevent double-clicking on same cooldown cycle
    private boolean clickedThisCycle = false;
    // hit tracking for progress detection
    private int totalHits = 0;
    /** Swings that landed while falling — i.e. CRITS, half again the damage. */
    private int critHits = 0;
    // LIFETIME counters, deliberately never reset. The per-fight ones above go to zero in
    // reset() when combat ends, so reading them after a scenario always gave 0 — the
    // instrument reported "no swings" for a run that had just dealt 12 damage.
    public static volatile int lifetimeHits = 0;
    public static volatile int gTotal=0, gClick=0, gCooldown=0, gReach=0, gAngle=0, gLos=0, gPassed=0;
    /**
     * HOW FAR OFF the crosshair is when the angle gate refuses, and how far the target is when
     * reach refuses. Counts alone cannot separate "nearly aimed, threshold too tight" from "not
     * tracking at all", and THREE aim changes were built and reverted without that distinction:
     * setTargetFast for melee, a narrower bow release window, and stepping the aim on the tick.
     * All three changed HOW the aim moves; none measured WHERE it ended up.
     *
     * <p>Sum plus max, so the mean is recoverable. The threshold is {@link #MAX_LOOK_ANGLE_DEG}
     * (40 deg) and reach is {@link #REACH} (3.0), so a mean just above either says the gate is
     * marginal, and a mean far above says the bot is simply not on target or not in range.
     */
    public static volatile double gAngleSum=0, gAngleMax=0, gReachDistSum=0, gReachDistMax=0;
    public static volatile int lifetimeCrits = 0;
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

        // WHICH GATE SAYS NO. The bot stands inside reach for 71-156 ticks a fight and swings
        // zero times, and the sampled log line only prints on failure, so it cannot show the
        // distribution. Count each gate: exactly one of these is the answer.
        gTotal++;
        if (gateClick) gClick++;
        if (gateCooldown) gCooldown++;
        if (gateReach) { gReach++; double d = Math.sqrt(distSq); gReachDistSum += d; if (d > gReachDistMax) gReachDistMax = d; }
        if (gateAngle) { gAngle++; gAngleSum += angle; if (angle > gAngleMax) gAngleMax = angle; }
        if (gateLos) gLos++;
        if (gateClick || gateCooldown || gateReach || gateAngle || gateLos) return;
        gPassed++;

        // Count the swing BEFORE it lands — the state that decides a crit is the one we are in
        // as we click. These increments were lost in a revert during an A/B and nothing put
        // them back, so the counter read zero through an entire investigation while the bot
        // was swinging 24 times a fight. The gate counters above are what exposed that.
        if (kaptainwutax.tungsten.combat.AttackTiming.isCrit(player)) { critHits++; lifetimeCrits++; }
        lifetimeHits++;
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

    public int getCritHits() { return critHits; }
    public int getTicksSinceLastHit() { return ticksSinceLastHit; }

    public void reset() {
        clickedThisCycle = false;
        totalHits = 0;
        critHits = 0;
        ticksSinceLastHit = 0;
    }
}
