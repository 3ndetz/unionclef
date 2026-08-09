package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.world.WorldView;

/**
 * PvP combat controller.
 *
 * Subsystems:
 *   SAFETY  — render-freq: stage machine, viz, braking, aim prediction
 *   MOUSE   — render-freq via WindMouse: rotation toward predicted aim point
 *   TRIGGER — tick-freq: auto-click when crosshair lands on target
 */
public class CombatController {

    public static final TriggerBot triggerBot = new TriggerBot();
    public static final SafetySystem safety = new SafetySystem();

    /**
     * Distance (eye -> nearest hitbox point) the bot tries to hold in a melee fight.
     * Derived from {@link TriggerBot#REACH} so the mover can never again settle at a
     * distance the attack gate refuses to fire at: we sit a comfortable margin INSIDE
     * reach, so normal jitter/knockback still leaves the swing legal.
     */
    public static final double STRIKE_DISTANCE = TriggerBot.REACH - 0.6;   // 2.4
    /** Below this we are inside the opponent's swing and lose angle — drift back out. */
    public static final double TOO_CLOSE_DISTANCE = TriggerBot.REACH - 1.4; // 1.6

    /**
     * Distance held against a MOB while the swing is ready.
     *
     * <p>Right at the edge of our own reach and outside a zombie's. Ours is
     * {@link TriggerBot#REACH} = 3.0 eye-to-hitbox; a zombie's is about 2.0 by the same measure,
     * so anywhere near 2.9 hits without being hit. The margin below 3.0 is what absorbs a tick of
     * jitter or knockback -- go closer and the free swings come back, go further and our own gate
     * refuses.
     */
    public static final double MOB_STRIKE_DISTANCE = TriggerBot.REACH - 0.1;  // 2.9
    /** Pressing distance against a mob: well inside our reach, so no tick is spent closing. */
    public static final double MOB_PRESS_DISTANCE = 1.8;
    /** And essentially never retreat -- retreating is what the measurements punish. */
    public static final double MOB_PRESS_BACK_OFF = 0.9;
    /** Below this against a mob we are inside its arm: step back out immediately. */
    public static final double MOB_BACK_OFF_DISTANCE = TriggerBot.REACH - 0.35; // 2.65
    /**
     * How far a mob can hit us, eye-to-hitbox, and therefore the radius to stay out of.
     *
     * <p>A vanilla mob attacks at roughly its own width plus two blocks centre-to-centre, which
     * measured the way {@link TriggerBot#eyeToHitbox} measures comes out near 2.0. The extra
     * quarter block is the margin for a mob that is walking toward us as it swings.
     */
    public static final double MOB_ARM_REACH = 2.25;
    /** Centre-to-centre floor against a mob: the worst hit ever observed (2.00) plus a margin. */
    public static final double MOB_MIN_CENTRE_GAP = 2.30;
    /** Within this, a second hostile turns the fight from a duel into a crowd. */
    public static final double CROWD_RADIUS = 7.0;
    /** Walking speed of a hostile mob, blocks per tick — a zombie's, which is the slow case. */
    private static final double MOB_SPEED_PER_TICK = 0.115;
    /** How far ahead the plan looks. One second: long enough to matter, short enough to be true. */
    private static final double PLAN_HORIZON_TICKS = 20.0;
    /** Ticks between decisions, and therefore how far a candidate step reaches. */
    private static final int PLAN_REPLAN_TICKS = 6;
    /** What being able to hit is worth against one enemy arriving within the horizon. */
    private static final double PLAN_OPPORTUNITY_WEIGHT = 0.8;
    /** Ticks the crowd plan chose the step. Read as crowdPlan. */
    public static volatile int crowdPlanTicks;
    /** A vanilla mob owes about a second between melee attacks; this is that gap, less a margin
     *  for the tick the animation arrives on. */
    private static final long MOB_SWING_COOLDOWN_MS = 850L;
    /** Last observed swing per entity id -- the clock the safe window is measured from. */
    private static final java.util.Map<Integer, Long> lastSwingMs = new java.util.HashMap<>();
    /** Ticks spent holding off because a loaded arm was in range. Read as armHold. */
    public static volatile int armLoadedTicks;
    /** Ticks the fight was treated as a CROWD (kite) rather than a duel. Read as crowdEsc. */
    public static volatile int crowdEscapeTicks;

    /** Ground walking speed the combat mover actually produces, blocks per tick. Vanilla walk is
     *  4.317 b/s = 0.216 b/t; this is the yardstick for "how long is the road back in". */
    public static final double CLOSE_SPEED_PER_TICK = 0.216;

    /** Half a bar. Below it a melee exchange is a losing trade and the bot breaks contact. */
    public static final double LOW_HP = 10.0;
    /** Distance a hurt bot holds — clear of every melee reach, and the range the bench's bow
     *  phase resumes at, so backing off is what puts an arrow back in hand. */
    public static final double KITE_DISTANCE = 14.0;
    /** Telemetry: ticks spent kiting because of health. Counted so "does it disengage" is a
     *  number rather than an impression. */
    public static volatile int lowHpTicks;
    /** Ticks spent inside the ~10-tick window after taking a hit, split by what the bot was doing. */
    public static volatile int hurtTicks, hurtAdvancing, hurtBackingOff;
    /** True on ticks where the close-quarters controller actually ran — lets DamageWatch report
     *  what share of the hurt window this branch even sees. */
    public static volatile boolean controlledThisTick;
    /** THE CONTROL FOR "controlled=0 during 166 hurt ticks". Without a total, that zero cannot tell
     *  "the controller runs but never while hit" from "the controller barely runs at all" — two very
     *  different diagnoses.
     *
     *  ⚠ THIS COUNTS COMPLETIONS, NOT ENTRIES. It sits at the BOTTOM of closeQuarters, below the
     *  no-LOS return and the low-hp kite return, so it misses every tick that leaves early. The
     *  comment here used to claim it counted "every time the method executes", and that wrong
     *  sentence cost a whole experiment: the in-reach arbitration edit controls the ENTRY, so ctl
     *  was read as its verdict while answering a different question. Use cqEntry for the entry. */
    public static volatile int controlTicks;
    /** Entries into closeQuarters, and the two ways out that never reach controlTicks. The budget
     *  reconciles exactly: cqEntry = cqNoLos + lowHpTicks + controlTicks. Anything that claims the
     *  branch is starved has to show it HERE, because this is the number the stage arbitration in
     *  {@code tick()} actually moves. */
    public static volatile int cqEntry, cqNoLos;

    // ── dynamic combat movement state (circle-strafe + range + crit-jumps) ──────
    private int strafeDir = 1;              // +1 = left, -1 = right
    /** How far a blow can throw us: knockback is ~0.4 blocks/tick decaying, so three blocks of
     *  clearance behind is the margin that matters. */
    private static final double KNOCKBACK_REACH = 3.0;
    /** Ticks spent with the rim in the knockback line -- the exposure this fix is meant to remove. */
    public static volatile int rimAtBackTicks;
    private long lastStrafeSwitch = 0;
    private long strafeInterval = 800;
    private long lastJump = 0;
    private long jumpInterval = 900;

    /** The resolved request actually written to the keys this tick. */
    private final CombatMoveIntent resolved = new CombatMoveIntent();

    /**
     * WHICH BRANCH OWNS THE CAMERA. The aim is put on the ENEMY only in the hasLOS branch; the
     * other three point it at a brake yaw or at the path direction. Measured on allround, the
     * crosshair sits ~85 deg off the target when TriggerBot refuses (angleMean 77.6 and 91.2
     * against a threshold of 40), while TriggerBot's OWN los gate refuses zero times — so
     * something here is steering the head away from a target it can plainly see.
     *
     * <p>Counting the branches is the only way to say which. Three guesses at this aim have
     * already been built and reverted; this is the measurement that should have come first.
     */
    public static volatile int aimBrake=0, aimReposition=0, aimEnemy=0, aimPath=0, aimNone=0;

    public static void resetAimCounters() {
        aimBrake = 0; aimReposition = 0; aimEnemy = 0; aimPath = 0; aimNone = 0;
    }

    public boolean tick(ClientPlayerEntity player, Entity target, WorldView world) {
        if (target == null || target.isRemoved() || !target.isAlive()) return false;

        TungstenConfig cfg = TungstenConfig.get();

        // safety: velocity tracking always, braking/viz only if enabled
        safety.tick(player, target, world);

        if (cfg.combatRotatesEnabled) {
            if (cfg.combatSaverEnabled && safety.isBraking()) {
                // DANGER_IMMINENT: face opposite velocity
                WindMouseRotation.INSTANCE.setParams(
                        cfg.combatWindMouseGravity * 2,
                        cfg.combatWindMouseWind * 0.3,
                        cfg.combatWindMouseMaxStep * 2.5,
                        cfg.combatWindMouseWindDist,
                        cfg.combatWindMouseDoneThreshold,
                        cfg.combatWindMouseFlickScale
                );
                aimBrake++;
                WindMouseRotation.INSTANCE.setTarget(safety.getBrakeYaw(), 0);
            } else if (cfg.combatSaverEnabled && safety.isRepositioning()) {
                // DANGER_BATTLE: face retreat waypoint (faster turn, still fighting)
                WindMouseRotation.INSTANCE.setParams(
                        cfg.combatWindMouseGravity * 1.5,
                        cfg.combatWindMouseWind * 0.5,
                        cfg.combatWindMouseMaxStep * 1.5,
                        cfg.combatWindMouseWindDist,
                        cfg.combatWindMouseDoneThreshold,
                        cfg.combatWindMouseFlickScale
                );
                aimReposition++;
// TRIED: aiming at the ENEMY here instead of the retreat waypoint, on the grounds that
                // this branch owns 63-80% of combat ticks (enemy=45 brake=5 reposition=77 / enemy=35
                // brake=0 reposition=144) and the crosshair sits ~85 deg off target. MEASURED FLAT:
                // angleMean 77.6/91.2 -> 79.0/79.2, landed 4,5 -> 5,3. Reverted, because it also
                // gives up facing the retreat for nothing.
                //
                // So the branch that SETS the aim is not the problem: even when the target is the
                // enemy, the crosshair does not arrive. Everything downstream of it has now been
                // checked and cleared — combatWindMouseMaxStep is 25 deg/frame and IS rate-scaled
                // on the large-angle path (g and ms both multiply by rate), so at 6 fps the cap is
                // ~83 deg/frame and one frame should cover 79. combatAimSmoothing is 0.5 with a
                // 55 deg snap. None of it explains the residual.
                //
                // WHAT IS LEFT UNCHECKED, for the next pass: whether accumulatePixels actually
                // becomes rotation on this client. It converts degrees to raw mouse pixels and the
                // stand runs UNFOCUSED, where MixinInGameHud has to apply the deltas by hand
                // (UnfocusedMouseHelper). If that path drops or rounds them away, every layer above
                // is correct and the head still never turns — which is exactly what the numbers say.
                //
                // ⛔ THAT "MEASURED FLAT" VERDICT IS VOID, AND SO IS THE FRAME-RATE STORY ABOVE IT.
                // Every number in this comment block was taken at 4-8 fps against the bench's own
                // 14.0 validity floor, on runs the guard was failing to void (fixed 2026-08-09:
                // five gates across four courses now declare load_sensitive). The stand now runs at
                // 20.6-22.7 fps after dropping the clients from 1920x1080 to 854x480, and the first
                // valid verdict says the opposite of what was concluded here:
                //     angleMean 93.3 (thr 40)   reachMean 3.97 (thr 3.0)   passed 48 of 714
                //     aim: enemy=252 brake=8 reposition=453     kills=9 deaths=14
                // The residual did not shrink with the frame rate — it GREW, from 68-84 to 93.3. So
                // it was never starvation, and "aiming at the enemy here changes nothing" rests on a
                // measurement that no longer counts.
                //
                // AND THE MECHANISM IS PLAIN ONCE THE BRANCH SPLIT IS READ AS A SHARE OF THE FIGHT:
                // this branch owns 453 of 714 ticks — 63% — and it deliberately points the head at
                // the retreat waypoint. The bot therefore spends two thirds of a duel looking away
                // from the opponent, which is exactly when the angle gate refuses and the swing does
                // not happen. It is not failing to aim; it is aiming somewhere else on purpose, and
                // losing 9-14 while it does.
                //
                // So: keep the retreat MOVEMENT, drop the retreat GAZE. Same fast turn parameters
                // above, enemy as the target. If this measures flat again on a VALID stand, the
                // next suspect is how often isRepositioning() is true at all, not where it looks.
                WindMouseRotation.INSTANCE.setTarget(safety.getAimYaw(), safety.getAimPitch());
            } else if (safety.hasLOS()) {
                // LOS to target: aim at predicted target position for hits
                WindMouseRotation.INSTANCE.setParams(
                        cfg.combatWindMouseGravity,
                        cfg.combatWindMouseWind,
                        cfg.combatWindMouseMaxStep,
                        cfg.combatWindMouseWindDist,
                        cfg.combatWindMouseDoneThreshold,
                        cfg.combatWindMouseFlickScale
                );
                // AIM MODE TRIED AND REVERTED — setTargetFast changed NOTHING here, and the
                // measurement is worth more than the idea. TriggerBot's gate counters on allround:
                //     slow glide   angle 83/113 (73%)  76/137 (55%)   landed 4, 5
                //     fast aim     angle 190/238 (80%) 123/203 (61%)  landed 5, 3
                // The precedent looked strong — BowShooter:139 and BlockPathWalker both switched to
                // setTargetFast for exactly this symptom — and it still did not transfer. So the
                // crosshair is not merely arriving slowly.
                //
                // What the counters DO say, for whoever takes this next: click=0 and los=0 never
                // refuse, so it is never "already swung" or "no line of sight". angle and REACH
                // share the blame (reach refused 48% and 70% after the change), which reads as the
                // opponent circling out of range rather than an aim that cannot keep up. Instrument
                // whether the yaw actually reaches the requested value before touching aim again.
                aimEnemy++;
                WindMouseRotation.INSTANCE.setTarget(safety.getAimYaw(), safety.getAimPitch());
            } else if (safety.isMovementActive()) {
                // no LOS: face BFS path direction to navigate around walls
                WindMouseRotation.INSTANCE.setParams(
                        cfg.combatWindMouseGravity,
                        cfg.combatWindMouseWind,
                        cfg.combatWindMouseMaxStep,
                        cfg.combatWindMouseWindDist,
                        cfg.combatWindMouseDoneThreshold,
                        cfg.combatWindMouseFlickScale
                );
                aimPath++;
                WindMouseRotation.INSTANCE.setTarget(safety.getMovementYaw(), 0);
            } else {
                aimNone++;
            }
        }

        if (cfg.combatTriggerBotEnabled) {
            triggerBot.tick(player, target);
        }

        // SHIELD UP IN THE GAP BETWEEN SWINGS. The engine never raised it at all: the
        // primitive existed but only ever ran when an agent drove it by hand over py4j, so
        // the bot fought with a shield in its off hand and never used it. Blocking and
        // attacking are mutually exclusive in vanilla, which decides the policy for us —
        // raise it while the attack cooldown is recharging, when we could not swing anyway,
        // and drop it just before the swing lands. Free mitigation, no lost damage.
        var offHand = player.getOffHandStack();
        if (cfg.combatShieldEnabled
                && offHand.getItem() instanceof net.minecraft.item.ShieldItem) {
            double d2 = player.squaredDistanceTo(target);
            float cd = player.getAttackCooldownProgress(0.5f);
            boolean threatClose = d2 < (TriggerBot.REACH + 2.0) * (TriggerBot.REACH + 2.0);
            if (threatClose && cd < 0.55f) {
                kaptainwutax.tungsten.task.ShieldBlocker.hold(3);
            } else if (kaptainwutax.tungsten.task.ShieldBlocker.isBlocking()) {
                kaptainwutax.tungsten.task.ShieldBlocker.release();
            }
        }

        // LEGS: resolve ONE movement request and write it ONCE.
        //
        // Previously two subsystems pressed the keys independently: the SafetySystem stage
        // machine (per RENDER FRAME) and combatMove (per CLIENT TICK). Whichever ran last
        // before vanilla sampled the keyboard won, so movement was framerate-dependent —
        // which is why stand results never matched live behaviour — and the per-tick writer
        // bypassed the VoidGuard clamp completely. Now: safety has priority (it handles
        // falling, bridges and retreat), close-quarters combat fills in when safety does not
        // want the legs, VoidGuard vetoes the result, and the keys are written exactly once.
        if (cfg.combatMovementsEnabled) {
            resolved.clear();

            CombatMoveIntent safetyIntent = safety.getIntent();
            boolean safetyFresh = safety.consumeIntentFresh();
            boolean safetyClaims = cfg.combatSaverEnabled && safetyFresh && safetyIntent.active;
            // A SNEAK-ONLY CLAIM IS A LAYER, NOT A VETO. The edge-sneak arms whenever the bot
            // is moving and sits within 0.3 of a block boundary — which on open ground is most
            // of the time — and its own comment calls it "additive: claim the legs (sneak-only)
            // if nothing else has". It was not additive: setting intent.active made the whole
            // safety intent win arbitration and REPLACE the approach, so instead of stepping in
            // the bot crept. Measured on melee_basic: closeQuarters ran 130 of 453 combat ticks,
            // the last of them at 4.37 blocks — permanently outside the 3.0 reach, which is why
            // the trigger never swung once. Steering claims still win outright; a bare sneak is
            // now layered over the approach.
            boolean safetyWantsLegs = safetyIntent.forward || safetyIntent.back
                    || safetyIntent.left || safetyIntent.right || safetyIntent.jump;
            // ⛔ IN REACH, A DANGER CLAIM IS A LAYER TOO — SAME LESSON AS THE SNEAK CLAIM ABOVE.
            // Measured tonight: closeQuarters executes 107 times in a ~2400-tick course because this
            // branch hands the legs to the safety stage first, so the reach control, the
            // advance/back-off decision and any reaction to being hit run on about 4% of a fight.
            // With narrow fixed (324 -> 45) the claim now comes overwhelmingly from DANGER_BATTLE
            // (danger=107), whose trigger is a knockback estimate — and that estimate is inflated on
            // flat ground, yet load-bearing: removing it was measured harmful twice (16 -> 23 and
            // 15 -> 19 deaths). So do not remove the caution and do not obey it blindly either.
            // INSIDE STRIKE RANGE the bot is already in the trade; retreating there is what produces
            // an even exchange, and an even exchange still loses when only OUR deaths are counted.
            // Let the stage keep its claim at distance, and let close combat own the legs in reach.
            double claimDist = TriggerBot.eyeToHitbox(player, target);
            boolean stageOverridesInReach = claimDist > TriggerBot.REACH + 1.0;
            if (safetyClaims && safetyWantsLegs && stageOverridesInReach) {
                resolved.copyFrom(safetyIntent);
            } else {
                closeQuarters(player, target, world, resolved);
                if (safetyClaims && safetyIntent.sneak) resolved.sneak = true;
            }

            if (cfg.combatSaverEnabled) {
                VoidGuard.apply(resolved, player, player.getEntityPos(),
                        player.getVelocity(), world);
            }
            // Did the request to close survive arbitration? The safety intent can win, and
            // the void guard can veto after that — this counts what actually reaches the keys.
            if (resolved.forward) fwdPressed++;
            resolved.writeKeys(MinecraftClient.getInstance());
        }

        return true;
    }

    /**
     * How many hostile mobs OTHER than the target are close enough to matter.
     *
     * <p>"Close enough" is generous on purpose -- a zombie six blocks away will be on us within
     * the time one exchange takes, so it counts toward the decision to kite rather than duel.
     */
    private static int countThreats(ClientPlayerEntity player, Entity target) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.world == null) {
            return 0;
        }
        int n = 0;
        for (Entity e : mc.world.getEntities()) {
            if (e == player || e == target || !e.isAlive()
                    || !(e instanceof net.minecraft.entity.mob.HostileEntity)) {
                continue;
            }
            if (e.squaredDistanceTo(player) <= CROWD_RADIUS * CROWD_RADIUS) {
                n++;
            }
        }
        return n;
    }

    /**
     * Close-quarters movement: circle-strafe around the target while holding strike
     * distance, crit-hop on a randomised cadence. Only runs when the safety stage machine
     * does not want the legs (i.e. PURSUE / DELICATE_BATTLE — no fall, no bridge, no retreat).
     *
     * <p>THE BUG THIS REPLACES. The old version pressed forward only at {@code dist > 3.4}
     * and back only at {@code dist < 2.0}, both measured CENTRE-TO-CENTRE. Between those two
     * numbers it pressed no forward and no back at all — and 2.0-3.4 centre-to-centre is
     * exactly melee range. The only motion left was the circle-strafe, which was itself
     * suppressed near any drop by setting BOTH strafe keys false, so on a ledge or a 1-wide
     * bridge the bot pressed literally nothing and stood there. That is the "стоит и смотрит,
     * почти не двигается когда цель рядом" the user kept reporting. Worse, 3.4
     * centre-to-centre is ~3.1 eye-to-hitbox, i.e. OUTSIDE {@link TriggerBot#REACH}, so the
     * bot's chosen hold distance was one at which it could never land a hit.
     *
     * <p>Now: distance uses the same eye-to-hitbox metric as the attack gate, the band is
     * derived from {@link TriggerBot#REACH}, and there is no state in which the bot presses
     * nothing — if a strafe side is unsafe it takes the other side, and if both are unsafe it
     * still micro-adjusts range instead of freezing.
     */
    private void closeQuarters(ClientPlayerEntity player, Entity target, WorldView world,
                               CombatMoveIntent out) {
        // No line of sight: the target is occluded (a wall, or another entity in the way).
        // Don't just spin and click — walk toward the route so we flank around it. The aim
        // branch already points us at getMovementYaw().
        cqEntry++;
        if (!safety.hasLOS()) {
            cqNoLos++;
            if (safety.isMovementActive()) {
                out.set(true, false, false, false, true, false, false);
            }
            return;
        }

        long now = System.currentTimeMillis();
        double dist = TriggerBot.eyeToHitbox(player, target);






        // Range control (we face the target, so forward = toward it).
        //
        // EVERY direction is edge-tested individually before it is pressed. VoidGuard is
        // still the final veto, but it cancels ALL steering at once, so relying on it alone
        // turns "one unsafe component" into "no movement at all" — which is how the bot ends
        // up frozen on a ledge. Backing off is the dangerous one: the target stands on solid
        // ground, so forward is nearly always safe, while backwards is what walks the bot off
        // a 5x5 platform or a 1-wide bridge.
        // REACH CONTROL: THE BAND FOLLOWS THE COOLDOWN.
        // With a fixed band the bot parks in [1.6, 2.4] for the WHOLE fight — permanently inside
        // the opponent's 3.0 reach, including its own ~12.5-tick recharge, which is a window it
        // cannot attack in at all. Every swing the opponent offers in that window is free. This
        // is the one change that removes incoming damage without costing a single point of
        // outgoing damage: close when the swing is ready, hold just OUTSIDE the opponent's reach
        // while it is not.
        //
        // Derived from getAttackCooldownProgress — the same signal TriggerBot gates the swing on
        // (TriggerBot.java:75), so the mover and the attack gate stay one source of truth, which
        // is the reason STRIKE_DISTANCE was tied to TriggerBot.REACH in the first place.
        // RE-TESTED WITH AN INSTRUMENT THAT WORKS.
        // This was tried once and called "no effect", but that verdict came from min_hp sampled
        // two or three times across a four-second fight -- and for part of the series the stats
        // line was throwing and returning nothing at all. Damage is now counted per tick in the
        // mod, so a change of three health points against a 3.0 baseline is visible.
        // The reasoning is unchanged and still sound: STRIKE_DISTANCE (2.4) is derived for a duel
        // where the opponent reaches as far as we do. A zombie's arm is about 2.0 eye-to-hitbox
        // against our 3.0, so there is a band that hits without being hit -- and 2.4 sits inside
        // its arm, conceding a free swing on every exchange.
        // DISTANCE TUNING IS EXHAUSTED, AND THE DEFAULT WINS.
        // Three settings measured with a per-tick damage counter, three runs each, against three
        // zombies:
        //     2.9 (outside their arm)  -> 12 / 20 / 6 damage, 3.9-6.9s
        //     2.4 (this default)       ->  3 /  3 / 9 damage, 3.6-4.4s
        //     1.8 (pressing in)        -> 14 / 15 / 14 damage, 7.5-7.9s
        // Backing off lengthens the fight, as the law predicts. Pressing in ALSO lengthens it,
        // which the law did not predict and the physics explains: inside 2.0 the bot is in the
        // knockback, the mobs are shoved away, and it spends its time re-closing. The tuned
        // default sits at the minimum of both, so this line stays as it is.
        double strikeAt = STRIKE_DISTANCE;
        double backOffAt = TOO_CLOSE_DISTANCE;
        if (kaptainwutax.tungsten.TungstenConfig.get().combatReachControl) {
            float cd = player.getAttackCooldownProgress(0f);
            // START CLOSING BEFORE THE SWING IS READY, BY EXACTLY THE TRAVEL TIME.
            // Standing off until `armed` and only THEN walking back in spends the first ticks of
            // every ready swing on the road: the stand-off sits a full block outside
            // STRIKE_DISTANCE, which is about five ticks of walking, while the last stretch of
            // cooldown is two. Measured against the baseline engine that showed up as a trade
            // rather than a win — 4:4, 4:6, 4:4, 3:4, margin -0.75: fewer free hits taken, and
            // fewer landed, because the bot arrived late to its own swing.
            //
            // So the decision is not "is the swing ready" but "is it ready by the time I get
            // there". Both sides in ticks, from what the game already tells us: the cooldown's
            // full period from the attack-speed attribute, and the distance to cover at the
            // walk speed this controller actually produces.
            double cdTicks = 20.0 / Math.max(0.1,
                    player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.ATTACK_SPEED));
            double ticksToReady = Math.max(0.0, (TriggerBot.COOLDOWN_CRIT - cd) * cdTicks);
            double ticksToClose = Math.max(0.0, (dist - STRIKE_DISTANCE) / CLOSE_SPEED_PER_TICK);
            boolean armed = cd >= TriggerBot.COOLDOWN_CRIT || ticksToClose >= ticksToReady;
            // HURT ⇒ STOP TRADING. This is the whole of tungsten's health awareness, and until
            // now there was none: getHealth() was read NOWHERE in the module, and SafetySystem's
            // ESCAPE stage is declared, handled and unreachable ("TODO: DELICATE_BATTLE — low HP
            // careful play", SafetySystem.java:483). A fight is damage_dealt / damage_taken and
            // the bot only ever optimised the numerator, which is why it wins the damage race
            // and loses the match.
            //
            // Breaking contact is the correct answer here rather than a heal or a shield: the
            // bench disables natural regeneration and the kit carries no food, so a wounded bot
            // cannot recover — but it CAN stop offering free exchanges, and out at this range the
            // bow becomes the weapon. That is not a special case for one course; it is what a
            // hurt fighter with a ranged option should do anywhere.
            double hp = player.getHealth() + player.getAbsorptionAmount();
            // DISENGAGE ONLY WHERE DISENGAGING CAN WORK — NOT FROM INSIDE THE TRADE.
            //
            // With natural regeneration off on the bench (arena.py sets
            // natural_health_regeneration=false) and no food in the kit, health within one life
            // is monotonically non-increasing. So `hp <= LOW_HP` is not a threshold, it is a
            // ONE-WAY LATCH: the first two or three hits put the bot under half health and every
            // remaining tick of that life returned here, before reach control, before the crit
            // hop and before any reaction to being hit. The bot surrendered the second half of
            // every life and the opponent simply followed it.
            //
            // Measured, and the budget reconciles exactly (cqEntry = cqNoLos + lowHp + ctl):
            //     385 = 1 + 180 + 204
            // i.e. 47% of every close-quarters tick went to kiting, while the no-LOS return —
            // the other suspect — took 1 tick in 385.
            //
            // Backing off from INSIDE the opponent's reach is strictly dominated: the same blows
            // land on us and none land on them, which converts a trade into a beating. A wounded
            // fighter already in the trade has to fight. Out past reach the original intent still
            // holds and the bow is the weapon, so the caution is kept exactly where it was designed
            // to work.
            if (hp <= LOW_HP && dist > TriggerBot.REACH) {
                lowHpTicks++;
                kite(out, player, world, dist);
                return;
            }

            if (!armed) {
                // Recharging: stand off just past the opponent's reach. Not further — the swing
                // has to be one step away when the cooldown lands, or the stand-off costs tempo.
                //
                // TRIED HOLDING INSIDE REACH INSTEAD (REACH-0.1 / REACH-0.4), on the measurement
                // that the bot can only hit on 23% of combat ticks (closeStats inReach=95 of 404)
                // and that nothing was blocking it (dirBlockedFwd=0, forward pressed 177 vs wanted
                // 74). It changed nothing:
                //     inReach share  23% -> 20%
                //     landed         4-6 -> 4, 4
                //     kills/deaths   1/2 -> 1/2, 1/1
                // and lastDist read 5.05 mid-fight, i.e. the bot was out of range for reasons that
                // have nothing to do with its target spacing. The limiter is the CHASE, not this
                // constant. Reverted.
                strikeAt = TriggerBot.REACH + 0.4;
                backOffAt = TriggerBot.REACH + 0.2;
            }
        }
        // WHAT DOES IT DO WHILE IT IS BEING HIT? Combat is open-loop on incoming damage — a sweep
        // of combat/ and task/ finds no hurtTime, no lastDamage, nothing: behaviour is driven purely
        // by the hp <= LOW_HP threshold, which measurement showed fires ~11 ticks before each death.
        // hurtTime is non-zero for about ten ticks after every hit, so these three say plainly
        // whether the bot spends those ticks walking INTO the fight, holding at range, or backing
        // off. Read them before deciding what a hit should change — bolting on a reaction blind is
        // how three fixes died today.
        controlledThisTick = true;
        controlTicks++;
        if (player.hurtTime > 0) {
            hurtTicks++;
            if (dist > strikeAt) hurtAdvancing++;
            else if (dist < backOffAt) hurtBackingOff++;
        }

        boolean tooFar = dist > strikeAt;
        boolean tooClose = dist < backOffAt;
        out.active = true;
        out.forward = tooFar && dirSafe(player, world, 1, 0);
        // Did we ASK to close, and did the ask survive? Combat runs 416 ticks a fight and
        // lands zero swings, with the trigger's gate reporting "ready, but out of reach" —
        // so either forward is never requested, or something downstream overrides it.
        if (tooFar) fwdWanted++;
        // How often is the bot genuinely inside the swing's reach? "lastDist" is one sample —
        // the final tick — and a distribution is what decides whether the trigger ever gets a
        // chance at all.
        if (dist <= TriggerBot.REACH) inReachTicks++;
        if (dist <= TriggerBot.REACH + 0.5) nearReachTicks++;
        if (out.forward) fwdAsked++;
        lastDist = dist;
        // WHILE WE ARE BEING HIT THE STAND-OFF HAS ALREADY FAILED — TRADE, DO NOT CEDE GROUND.
        //
        // backOffAt holds at REACH+0.2 during the attack cooldown, which is right against an
        // opponent that respects reach. The bench opponent does not: punk charges and swings
        // without pause, so retreating inside its arc hands it ground and answers nothing.
        //
        // Measured with the low-hp fix already in: hurt = 142/41/101 — of 142 ticks inside the
        // post-hit window the bot spent 101 backing off. Both fighters carry an iron sword, no
        // scenario in the pvp suite issues armour, and 206 damage over 40 hits means four blows
        // kill. In that duel whoever lands more wins, and retreating lowers OUR landing rate
        // without lowering theirs, because they are the one advancing.
        //
        // AND THE DEATHS REALLY ARE LOST FIGHTS, from the server log rather than a mod counter:
        // tester1 slain 25 / fell out of the world 4, tester2 slain 14 / fell 4. The comment on
        // AllRound.build claiming these deaths are void falls was written from 4-8 fps runs and
        // no longer holds at 29 fps — worth correcting there before it misdirects another pass.
        boolean beingHit = player.hurtTime > 0;
        out.back = tooClose && !beingHit && dirSafe(player, world, -1, 0);
        out.sprint = out.forward && dist > strikeAt + 1.0; // sprint only for a real approach

        // Circle-strafe: orbit the target, flipping direction on a randomised cadence
        // (unpredictable, keeps flanking). If the chosen side is a drop, take the OTHER
        // side rather than standing still; only when BOTH sides are unsafe do we skip the
        // strafe, and even then the range control above keeps the bot moving.
        // AGAINST A CROWD, COMMIT TO ONE DIRECTION.
        // Flipping every half second is right in a duel -- it is unpredictable, and one opponent
        // cannot exploit it. Against three it is the opposite: each flip walks the bot back
        // through the arc it just cleared, and they re-surround it. A steady orbit strings them
        // into a line instead, because they all path to the same moving point, and a line can be
        // fought one at a time.
        // The safety flip below still applies -- an unsafe side is always abandoned -- so this
        // only removes the RANDOM flip, not the one that keeps the bot on the platform.
        boolean crowded = countThreats(player, target) >= 1;
        if (!crowded && now - lastStrafeSwitch > strafeInterval) {
            strafeDir = -strafeDir;
            lastStrafeSwitch = now;
            strafeInterval = 500 + (long) (Math.random() * 700);
        }
        if (!strafeSideSafe(player, world, strafeDir)) {
            strafeDir = -strafeDir;               // try the other side immediately
            lastStrafeSwitch = now;
        }
        boolean canStrafe = strafeSideSafe(player, world, strafeDir);
        out.left = canStrafe && strafeDir > 0;
        out.right = canStrafe && strafeDir < 0;
        // ⛔ NEVER FIGHT WITH THE RIM AT YOUR BACK.
        //
        // MEASURED on edge_duel, five falls out of five: vgFall = onset 5 / hurt 5 / sprint 0 /
        // afterEdge 5. Every single fall STARTED on a tick where hurtTime > 0, none while
        // sprinting, and in all five the void guard had already seen the edge the tick before and
        // went over regardless. The bot does not walk off this platform -- it is HIT off it.
        //
        // Which is why no amount of work in VoidGuard can fix it: knockback is a velocity the
        // server applies, and the guard's whole hold is releasing keys and pressing sneak. Neither
        // cancels momentum nobody asked for. The only move that survives a blow is not to be
        // standing where the blow sends you over.
        //
        // Knockback pushes AWAY from the attacker, so the dangerous edge is the one directly
        // behind us, and the answer is to close instead: the opponent is standing on floor by
        // construction, so forward is the one direction guaranteed to have ground. This does not
        // fight the range control -- it only overrides the hold, and only when the rim is in the
        // line a hit would throw us along.
        net.minecraft.util.math.Vec3d awayFromTarget =
                player.getEntityPos().subtract(target.getEntityPos());
        if (awayFromTarget.horizontalLengthSquared() > 1e-6) {
            boolean rimAtBack = VoidDetector.edgeAhead(
                    player.getEntityPos(), awayFromTarget.x, awayFromTarget.z, world, 3, KNOCKBACK_REACH);
            if (rimAtBack) {
                rimAtBackTicks++;
                out.back = false;                    // never retreat into it
                if (!canStrafe && dirSafe(player, world, 1, 0)) {
                    out.forward = true;              // close, so a blow cannot launch us clear
                }
            }
        }
        // Neither side strafeable (a 1-wide bridge, a tiny platform) and already at strike
        // distance: keep some motion so we are not a static target, but ONLY into space we
        // have tested. Forward-pulse against the opponent is safe by construction — they are
        // standing on floor — whereas the backwards half of a naive jitter is exactly what
        // walked the bot off the edge. If forward is not safe either, we hold position: on a
        // one-block ledge standing still beats falling, and the aim/trigger still fight.
        if (!canStrafe && !tooFar && !tooClose) {
            boolean pulse = ((now / 300) % 2) == 0;
            out.forward = pulse && dirSafe(player, world, 1, 0);
            out.back = false;
        }

        // BUNNY-HOP: jump on a fast cadence so the bot is ALWAYS moving/juking around the
        // target, for crits + a harder-to-hit profile. Only from the ground and only when the
        // landing isn't a drop (a jump can never launch us into the void).
        // JUMP ON THE ATTACK COOLDOWN, NOT ON A DICE ROLL. A hit lands as a CRIT — half again
        // the damage — only while the player is falling. The bot was already hopping around
        // the target constantly, but on a 280-600 ms random cadence, so whether a swing
        // happened to coincide with a descent was pure chance. Taking off while the cooldown
        // is nearly recharged puts the swing on the way DOWN, which turns an accident into
        // the normal case. The random interval survives as a floor so the bot still juks
        // when it is not about to swing.
        var jcfg = kaptainwutax.tungsten.TungstenConfig.get();
        float cd = player.getAttackCooldownProgress(0.5f);
        // THE TAKE-OFF HAS TO BE EARLIER THAN THIS, OR THE SWING LANDS ON THE WAY UP.
        // A crit counts only while FALLING. A jump climbs for about six ticks, and the attack
        // cooldown at the default attack speed runs 12.5 ticks end to end -- so leaving the ground
        // at cd=0.55 leaves barely five ticks before the swing is ready, and the bot is still
        // ASCENDING when it hits. Measured on the trio course: 13 hits, 1 crit -- 7.7%, where the
        // whole point of hopping is to make crits the normal case.
        // Solving (1 - cd) * 12.5 > 6 gives cd < 0.52: take off then, and the swing arrives on the
        // way down. The lower bound keeps the bot from hopping the instant it has swung, when the
        // next swing is still most of a second away.
        boolean windingUp = cd > 0.25f && cd < 0.50f;
        long interval = windingUp ? jcfg.combatBunnyHopMinMs : jumpInterval;
        // DO NOT HOP ON A LEDGE. A crit is worth half a hit; falling off is worth the whole
        // fight. isJumpLandingSafe projects the CURRENT velocity, which is not enough on a
        // one-wide walkway where a strafe or a knockback nudge arrives mid-air — measured on
        // narrow_bridge_duel, self-falls 1/0/3 across three runs, and 1/0/0 with the hop
        // suppressed entirely. The same edge score the safety machine already computes is the
        // right gate: hop on open ground, keep both feet down on a bridge.
        double edgeScore = kaptainwutax.tungsten.combat.VoidDetector
                .edgeScoreWithFallThreshold(player.getEntityPos(), world, 5);
        if (player.isOnGround() && edgeScore < 0.4 && now - lastJump > interval
                && SafetySystem.isJumpLandingSafe(player.getEntityPos(), player.getVelocity(), world)) {
            out.jump = true;
            lastJump = now;
            jumpInterval = jcfg.combatBunnyHopMinMs + (long) (Math.random() * jcfg.combatBunnyHopRandMs);
        }
    }

    /** Is stepping sideways in {@code dir} (+1 left / -1 right) clear of a drop? */
    private boolean strafeSideSafe(ClientPlayerEntity player, WorldView world, int dir) {
        return dirSafe(player, world, 0, dir);
    }

    /**
     * Is moving with this input combination clear of a serious drop?
     *
     * @param fwd    +1 forward, -1 back, 0 none
     * @param strafe +1 left, -1 right, 0 none (MC convention: sideways +1 = LEFT)
     */
    // Counters, read over py4j. The chat is not usable for this — it floods and drops — and
    // the question "is the edge guard the thing stopping the approach" cannot be answered by
    // reading the code, only by counting how often it says no.
    public static volatile int dirAsked = 0, dirBlockedFwd = 0;
    public static volatile int fwdWanted = 0, fwdAsked = 0, fwdPressed = 0;
    public static volatile int inReachTicks = 0, nearReachTicks = 0;
    public static volatile double lastDist = -1;

    private boolean dirSafe(ClientPlayerEntity player, WorldView world, int fwd, int strafe) {
        if (fwd == 0 && strafe == 0) return true;
        double yawRad = Math.toRadians(player.getYaw());
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        double dx = strafe * cos - fwd * sin;
        double dz = fwd * cos + strafe * sin;
        // Lookahead scales with speed: a sprinting bot must see the rim with room to stop.
        double speed = Math.sqrt(player.getVelocity().x * player.getVelocity().x
                + player.getVelocity().z * player.getVelocity().z);
        double look = Math.max(1.5, speed * 8.0);
        boolean safe = !VoidDetector.edgeAhead(player.getEntityPos(), dx, dz, world, 3, look);
        if (fwd > 0) { dirAsked++; if (!safe) dirBlockedFwd++; }
        return safe;
    }

    /**
     * Back away from the target, keeping it in front. Not a retreat path — the terrain safety
     * system owns those — just a straight-line disengage with the same per-direction edge test
     * every other movement here uses, so backing off can never walk off a ledge.
     */
    private void kite(CombatMoveIntent out, ClientPlayerEntity player,
                      net.minecraft.world.WorldView world, double dist) {
        out.active = true;
        out.forward = false;
        // A WALL AT YOUR BACK IS NOT A RETREAT. dirSafe answers "is there floor that way", which
        // is the right question for a ledge and the wrong one for a wall: pressed against one the
        // bot keeps holding BACK, goes nowhere, and becomes a stationary target at exactly the
        // moment it can least afford to be one. These arenas are walled, so this is not a corner
        // case here — and neither is a cave, a ravine wall or a doorway anywhere else.
        boolean penned = player.horizontalCollision;
        out.back = dist < KITE_DISTANCE && !penned && dirSafe(player, world, -1, 0);
        out.sprint = out.back;
        // Blocked or unsafe behind: run ALONG the obstacle instead of into it. Standing still is
        // the one option that is certainly wrong.
        if (!out.back && dist < KITE_DISTANCE) {
            out.left = dirSafe(player, world, 0, -1);
            out.right = !out.left && dirSafe(player, world, 0, 1);
            out.sprint = out.left || out.right;
        }
    }

    public void releaseKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        resolved.clear();
        safety.getIntent().clear();
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        triggerBot.reset();
        safety.reset();
        WindMouseRotation.INSTANCE.clearTarget();
    }
}
