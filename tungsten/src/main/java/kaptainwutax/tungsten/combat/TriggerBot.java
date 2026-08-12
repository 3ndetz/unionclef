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
    /**
     * Ticks where the swing was READY, split by whether the target was in reach.
     *
     * <p>The gates above are evaluated independently, so a tick lands in `cd` and `reach` at once
     * and neither of them can say how often a matured swing was thrown away for distance. On
     * mob_skeleton that is the quantity that matters: arrows landed is a function of FIGHT LENGTH
     * (2-3 shots, one per second of bow draw, against a kill needing 3-4 landed swings at a
     * 0.625 s cooldown), and damage is already surplus. A swing that matures out of range costs a
     * whole cooldown — about a second, about one arrow.
     *
     * <p>⛔ MEASURED 2026-08-12, and it is the strongest number this course has produced:
     * <pre>
     *   ready = far/near   33/3   31/3   44/4   28/9   9/5   38/4   39/4
     * </pre>
     * ABOUT NINE TICKS IN TEN WITH A MATURED SWING ARE SPENT OUT OF REACH. After each cooldown the
     * bot waits roughly a second and a half to be inside 3.0 again, and only three or four ticks of
     * that window are usable. Against a kill needing 3-4 landed swings that is where the fight's
     * length comes from — and the fight's length is what decides arrows landed, since the skeleton
     * fires once per second of draw and does not shoot at the approach at all.
     *
     * <p>So the target is no longer "dodge" or "close faster": it is DO NOT LET A READY SWING
     * MATURE OUT OF RANGE. combatReachControl already tries to start closing before the swing is
     * ready, by exactly the travel time — this 10:1 says it is not achieving it.
     *
     * <p>WHY, read out of the code rather than guessed. The arming rule is
     * {@code ticksToClose = (dist - STRIKE_DISTANCE) / CLOSE_SPEED_PER_TICK} — a closing time
     * computed from OUR speed alone. Against a retreating shooter that is the one assumption that
     * does not hold: the skeleton backs away at about a walking bot's speed, and inside REACH the
     * sprint is deliberately cut so the blow lands unsprinted. Walk against walk closes nothing,
     * so the last half-block is never crossed and the swing matures at 3.4-3.6 against a 3.0 gate.
     *
     * <p>AND THAT REOPENS A CLOSED HYPOTHESIS ON BETTER TERMS. combatDodgeOnDraw's sibling,
     * combatHoldContactOnShooter, aimed at exactly this and was refuted ON ARROWS LANDED: -0.50
     * arrows at 1.77 sigma, SE 0.51. But arrows is a coarse outcome — a handful of integers per
     * run — while ready=far/near is a per-tick ratio reading 10:1, with orders of magnitude more
     * signal. The honest next experiment is to restore that flag and judge it HERE, on the
     * mechanism it actually targets, instead of on the outcome three steps downstream. If the
     * ratio does not move, the idea is dead for good; if it does and arrows still do not, then
     * fight length is not what sets arrows and the whole chain above needs revisiting.
     */
    public static volatile int gReadyFar, gReadyNear, gReadyFarDodging, gReadyFarWalking, gReadyFarSprintHeld, gReadyFarFwdHeld;
    /** Charge carried by the swings that PASSED, and how many took the early crit-window
     *  threshold. Divide the sum by gPassed for the mean -- these two are the difference between
     *  "we swing undercharged" as a theory and as a number. Note the means above (angleMean,
     *  reachMean) are conditional on the gate REFUSING, so they are always past their threshold
     *  by construction and say nothing about typical behaviour; this pair is unconditional. */
    public static volatile double gSwingChargeSum = 0;
    /** Mean melee score of what was actually held at the swing, and swings made with nothing
     *  worth swinging. A full-charge iron_sword is 6 damage; if our hits average 3.50 while the
     *  charge is 1.000, the weapon is the remaining suspect. */
    public static volatile double gSwingWeaponSum = 0;
    public static volatile int gSwingNoWeapon = 0;
    /** Swings declined because a better weapon was one slot away and being drawn. */
    public static volatile int gSwingDeferred = 0;
    public static volatile int gSwingCritWindow = 0;
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
        // ⛔ EVERY SUM BELOW ACCUMULATES INSIDE ITS OWN FAILURE BRANCH.
        // gReachDistSum only grows when the reach gate REFUSED, gAngleSum only when the angle gate
        // refused. So "reachMean" and "angleMean" are means over the REJECTED swings, never over
        // the attempts -- reachMean 4.06 does not say the bot fights at 4.06, it says that when it
        // was too far, it was too far by that much. On the same run 31 of 58 evaluations failed
        // reach, so the other 27 were inside it and contribute to no mean at all.
        //
        // Misread three separate times in one session (angleMean as "the aim is 90 degrees off",
        // reachMean as "the bot holds at 4 blocks"), each time turning a conditional statistic into
        // a claim about the fight. The counts (gReach, gAngle) are the honest half; the means only
        // describe the misses.
        gTotal++;
        if (gateClick) gClick++;
        if (gateCooldown) gCooldown++;
        if (gateReach) { gReach++; double d = Math.sqrt(distSq); gReachDistSum += d; if (d > gReachDistMax) gReachDistMax = d; }
        // ⛔ THE ONE JOINT THE OTHER COUNTERS CANNOT SHOW. Every gate above is evaluated
        // INDEPENDENTLY, so a tick lands in `cd` and `reach` at once and neither says how often a
        // READY swing was thrown away for distance. That is the quantity the next fix turns on:
        // arrows landed on this course is a function of FIGHT LENGTH (2-3 shots, one per second of
        // draw, against a kill that needs 3-4 landed swings at a 0.625 s cooldown), and damage is
        // already surplus (landed=4 crits=3 is ~33 against 20 HP). So the bot is limited by the
        // RATE it lands hits, and a swing that matures out of range costs a full cooldown — about
        // a second, about one arrow.
        if (!gateCooldown) {
            if (gateReach) {
                gReadyFar++;
                // ...AND WHO OWNS THE LEGS WHILE THAT SWING GOES TO WASTE. Holding sprint to the
                // swing did not move the far/near ratio at all (0.165 -> 0.161), so the swing is
                // not being lost to closing SPEED — which leaves "the bot is not closing at all".
                // ProjectileDodge takes the legs whenever it drives, and its budget is the same
                // order as this counter (dodgeDrive 8-96 a fight against gReadyFar 20-58). A dodge
                // that cannot beat a two-tick arrow, spending itself exactly when a swing is ready,
                // would explain the whole thirty-tick gap. This says whether it does.
                //
                // ⛔ ANSWERED, 2026-08-12: IT DOES NOT. far/near/dodging over six runs —
                //     39/5/9   19/4/0   30/5/7   31/15/11   15/6/0   32/5/4
                // The dodge holds the legs for about 16% of the wasted ticks (and none at all in
                // two runs). It is a real contributor and NOT the explanation: in five ticks out
                // of six the swing is ready, the target is out of reach, and the dodge is not
                // driving. Where the approach goes in those ticks is still unknown, and the next
                // pass should find out rather than assume — this candidate looked obvious from
                // the matching magnitudes of dodgeDrive and gReadyFar, and magnitudes agreeing is
                // not a mechanism.
                if (kaptainwutax.tungsten.task.ProjectileDodge.isActive()) gReadyFarDodging++;
                if (CombatController.lastForwardPressed) gReadyFarWalking++;
                // ...AND WHAT THE GAME ACTUALLY HOLDS, not what the controller resolved. The two
                // differ exactly when a later writer strips the key -- pitfall P1, which has
                // already invalidated four dodge experiments here by wiping presses before the
                // game read them. Asked-vs-held is the whole question now, so read the option.
                try {
                    net.minecraft.client.MinecraftClient mc0 = MinecraftClient.getInstance();
                    if (mc0 != null && mc0.options != null) {
                        if (mc0.options.sprintKey.isPressed()) gReadyFarSprintHeld++;
                        if (mc0.options.forwardKey.isPressed()) gReadyFarFwdHeld++;
                    }
                } catch (Exception ignored) {
                    // an instrument must never be the thing that breaks a fight
                }
                // ⛔ ANSWERED, 2026-08-12, and it clears every consumer of suspicion:
                //     far/near/dodging/WALKING
                //     20/5/5/17   21/4/0/21   39/4/0/37   13/7/0/13   40/9/8/40   31/5/5/29
                // In 95-100% of the wasted ticks the bot IS asking to go forward. Nothing is
                // stealing the legs — the approach itself is failing.
                //
                // AND THAT EXPOSES A MIS-AIMED EXPERIMENT OF MINE. combatHoldContactOnShooter
                // altered sprint only where `dist <= REACH`; the waste happens OUTSIDE reach,
                // where the baseline rule already sprints. The flag could not touch the quantity
                // it was measured against, which is exactly what the unmoved ratio said. Its
                // refutation therefore says nothing about the walk-against-walk idea — only that
                // I pointed it at the wrong side of the threshold.
                //
                // OPEN, and the next thing to measure: the bot is far, asking forward, and by the
                // baseline rule sprinting — yet it does not close on a mob that walks. Either the
                // sprint is not reaching the keys (a writer downstream strips it, the shape of
                // pitfall P1) or the distance is not being lost to travel at all. Read what the
                // keys actually carry on those ticks before proposing anything.
            } else {
                gReadyNear++;
            }
        }
        if (gateAngle) { gAngle++; gAngleSum += angle; if (angle > gAngleMax) gAngleMax = angle; }
        if (gateLos) gLos++;
        if (gateClick || gateCooldown || gateReach || gateAngle || gateLos) return;
        // DO NOT SPEND THE SWING ON THE WRONG ITEM.
        // Forcing a weapon re-check when combat starts took bow swings from 21% of all swings to
        // 9%, not to zero, because a slot switch is not instantaneous: equipBestMelee sets the
        // selected slot and the attack can still go out the same tick holding the old item. No
        // re-check cadence can close that; only the attack declining can. One skipped tick costs
        // nothing -- the cooldown is already full, so the swing lands on the next one -- while a
        // bow swing spends the whole cooldown on about one damage where the sword is six.
        // Guarded on "something strictly better exists", so bare fists still swing.
        if (kaptainwutax.tungsten.combat.WeaponSelector.hasBetterThanHeld(player)) {
            kaptainwutax.tungsten.combat.WeaponSelector.forceRecheck();
            kaptainwutax.tungsten.combat.WeaponSelector.equipBestMelee(player);
            gSwingDeferred++;
            return;
        }
        gPassed++;

        // Count the swing BEFORE it lands — the state that decides a crit is the one we are in
        // as we click. These increments were lost in a revert during an A/B and nothing put
        // them back, so the counter read zero through an entire investigation while the bot
        // was swinging 24 times a fight. The gate counters above are what exposed that.
        // WHAT CHARGE DOES THE SWING ACTUALLY CARRY? Measured symmetrically over one course,
        // both fighters' counters zeroed together: the opponent's hits land 5.17 damage and ours
        // 3.50, on the same iron_sword. Vanilla scales a swing by 0.2 + charge^2 * 0.8, so 5.17
        // of 6 is ~0.95 charge and 3.50 of 6 is ~0.70 -- we appear to be swinging undercharged.
        // The only path in this method that permits it is the crit window buying an early swing
        // at COOLDOWN_CRIT instead of COOLDOWN_FULL, which collects the charge penalty and only
        // pays for itself if the swing IS a crit. So count all three together: the charge we
        // actually swing at, how often the early threshold was the one in force, and how many of
        // those swings vanilla scored as crits. If chargeMean sits near 0.95 the theory is wrong
        // and the damage gap is somewhere else entirely.
        gSwingChargeSum += cooldown;
        if (critWindow) gSwingCritWindow++;
        // WHAT IS ACTUALLY IN THE HAND WHEN WE SWING?
        // chargeMean came back 1.000, which kills the "we swing undercharged" theory outright --
        // the swings are fully charged. Yet the symmetric measurement says our hits deal 3.50 and
        // theirs 5.17, and a fully charged iron_sword is 6. Half damage from a full swing means
        // the sword is not what is being swung: allround puts a BOW in slot 0 for the ranged phase
        // and only restores the sword when the driver sees dist <= 12, while WeaponSelector
        // re-checks just once every RECHECK_TICKS = 20 ticks. The bot dies 17 times a course and
        // every life starts holding the bow, so there is a window each life where it fights with
        // it -- exactly the failure PunkPlayerTask's own comment warns about ("kept fighting with
        // the bow (2 dmg/hit) while a sword sat in the hotbar and it lost the fight").
        // Mixing 6-damage sword hits with ~1-damage bow hits averages 3.5 at a 50/50 split.
        // So count it rather than believe it: the mean weapon score across swings, and how many
        // swings went out with nothing that deserves the name.
        double ws = kaptainwutax.tungsten.combat.WeaponSelector.meleeScore(player.getMainHandStack());
        gSwingWeaponSum += ws;
        if (ws <= 1.0) gSwingNoWeapon++;
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
