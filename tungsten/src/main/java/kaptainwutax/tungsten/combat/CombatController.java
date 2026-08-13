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

    // ⛔⛔ EVERYTHING FROM HERE TO PLAN_OPPORTUNITY_WEIGHT IS DECLARED AND NEVER READ.
    //
    // Eleven constants -- MOB_STRIKE_DISTANCE, MOB_PRESS_DISTANCE, MOB_PRESS_BACK_OFF,
    // MOB_BACK_OFF_DISTANCE, MOB_ARM_REACH, MOB_MIN_CENTRE_GAP, MOB_SPEED_PER_TICK,
    // MOB_SWING_COOLDOWN_MS, PLAN_HORIZON_TICKS, PLAN_REPLAN_TICKS, PLAN_OPPORTUNITY_WEIGHT --
    // each appears exactly ONCE in this repository: on its own declaration. Nothing reads them,
    // here or anywhere else. The mob combat policy they describe DOES NOT EXIST; a zombie is
    // fought with the player duelling code and nothing else.
    //
    // That is the answer to mob_trio, which gates on zero damage and has never passed, and to the
    // eight hypotheses its header records as dying there: there was no mob policy to tune. The
    // javadoc below still reads as a specification -- "a band that hits without being hit", a
    // horizon, a replan interval, an opportunity weight -- and it is a specification of something
    // unimplemented. Left in place, and labelled, because it is a good specification: whoever
    // implements it should start from these numbers rather than invent new ones.
    //
    // Fourth instance of this pattern found on 2026-08-11 alone: lastSwingMs (declared, no writer,
    // no reader), armHold/crowdEsc/crowdPlan (declared, never incremented, and I briefly cited
    // their zeros as evidence), and these. Assume a counter or constant is dead until grep says
    // otherwise -- `grep -c` returning 1 means the declaration and nothing more.

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
    /**
     * Centre-to-centre floor against a mob: the worst hit ever observed (2.00) plus a margin.
     *
     * <p>⛔ WIRED AS A FORWARD-PRESS VETO AND REVERTED, 2026-08-11. The band above holds a distance
     * from the ONE entity being fought; mob_trio spawns three and the other two close while it
     * does. So: when another hostile is already inside this floor, suppress the press rather than
     * walk into a free swing. Six runs against the band-only twelve:
     *     band only        3 9 3 3 6 12 6 3 17 6 0 3   mean 5.92
     *     + press veto     3 9 12 20 3 6               mean 8.83
     * No improvement, and all three zombies still died, so it was not trading damage for kills.
     *
     * <p>The reason is a lesson this repo has already written down once, in the void-guard work:
     * SUPPRESSING A DIRECTION IS NOT REPOSITIONING. Refusing to press leaves the bot standing among
     * three zombies instead of moving through them, and this course's own established law is that
     * damage tracks TIME IN CONTACT. A veto lengthens contact by construction.
     *
     * <p>So this constant belongs to the crowd PLAN — candidate steps scored over a horizon,
     * which is what PLAN_HORIZON_TICKS, PLAN_REPLAN_TICKS and PLAN_OPPORTUNITY_WEIGHT describe —
     * not to a key that gets withheld.
     */
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
    /** A vanilla mob owes about a second between melee attacks; this is that gap, less a margin
     *  for the tick the animation arrives on. */
    private static final long MOB_SWING_COOLDOWN_MS = 850L;

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
    /**
     * Ticks where the wounded retreat WOULD have fired and was declined for having nothing to
     * shoot with.
     *
     * <p>lowHpTicks going to zero only says the branch stopped firing; it cannot say how much was
     * removed, and the "47% of close-quarters ticks" figure it is tempting to quote was measured
     * in another context, not on the courses this changed. This counter is the missing half: it is
     * the SAME course, the SAME jar, and it reports exactly the ticks the predicate took away.
     * lowHpTicks + lowHpDeclined is what lowHpTicks alone used to be.
     */
    public static volatile int lowHpDeclined;
    /**
     * Reach-control ticks on which the bot could not have walked backwards.
     *
     * <p>The same shape as {@link #lowHpDeclined} and for the same reason: without it, "the fix is
     * in" is an impression. It says how often the arena is the kind where the stand-off degenerated
     * into standing still, so a course that shows no change can be told apart from a course where
     * the branch never fired. Expect it near zero on an open field and large on a platform — 285
     * in a 60 s edge_duel run, against 0 for {@code lowHpTicks} on the same run.
     *
     * <p>It counts {@code !armed && !canWithdraw} — exactly the ticks on which the stand-off would
     * have fired and was refused — so the name and the code agree. The first version counted every
     * reach-control tick with {@code !canWithdraw}, armed ones included, which was an upper bound
     * rather than a count; the numbers 57-307 quoted in the release notes for 0.77.0 are from THAT
     * version and read high. Five counters in this file's history were misread by trusting the name
     * over the code, which is why this one was corrected before it was quoted again.
     */
    public static volatile int standOffDeclined;
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
    /**
     * The crit take-off, counted gate by gate, over the windingUp window ONLY.
     *
     * <p>hopWind is the opportunity count; the four after it are the refusals; hopFired is the
     * take-off. They reconcile as hopWind = hopFired + (refusals, which may overlap on one tick).
     * Read over py4j as hop=wind/air/edge/interval/unsafe/fired.
     */
    public static volatile int hopWind, hopAir, hopEdge, hopInterval, hopUnsafe, hopFired;
    /** Crit windows where the hop stood down because an arrow was inbound. Read as hopDodge. */
    public static volatile int hopDodge;
    /** Crit windows where a hop would have carried the swing out of reach. Read as hopFar. */
    public static volatile int hopFar;
    /** How high a vanilla jump lifts the eye, blocks. Used to keep the hop inside our own reach. */
    private static final double JUMP_APEX = 1.25;
    /** Entries into closeQuarters, and the two ways out that never reach controlTicks. The budget
     *  reconciles exactly: cqEntry = cqNoLos + lowHpTicks + controlTicks. Anything that claims the
     *  branch is starved has to show it HERE, because this is the number the stage arbitration in
     *  {@code tick()} actually moves. */
    public static volatile int cqEntry, cqNoLos;

    // ── dynamic combat movement state (circle-strafe + range + crit-jumps) ──────
    private int strafeDir = 1;              // +1 = left, -1 = right
    /**
     * How far a blow actually throws us, MEASURED rather than assumed.
     *
     * <p>This was 3.0, a number I wrote from intuition, and it guarded a line more than twice as
     * long as the real thing. Measured on edge_duel over 16 hits: mean throw 1.45 blocks. A
     * threshold at double the mean makes the guard fire far more often than the danger warrants,
     * which manufactured much of the exposure it then reported -- and explains why two separate
     * "close instead" fixes changed nothing: both were treating the symptom of an inflated
     * constant rather than a real geometric bind.
     *
     * <p>2.0 is the mean plus a margin of roughly a third, and it is also the platform radius on
     * edge_duel (half=2), which is the distance that decides whether a centred fighter survives a
     * hit at all. The measurement says it does: 1.45 < 2.0, so the course's stated intent -- "both
     * keep footing 1 block from drop" -- is consistent with the physics and this gate is reachable.
     *
     * <p>The measured MAX was 5.32, but the ten-tick window also catches the horizontal drift of a
     * fall already in progress, so that figure is part cause and part consequence and is not what
     * this constant should be set from.
     */
    /** Whether the last resolved combat tick asked the legs to go forward. */
    public static volatile boolean lastForwardPressed;

    /** Strafe ticks pressed while the target is OUT of reach -- the approach, where a diagonal costs speed. */
    public static volatile int strafeFarTicks;
    /** Strafe ticks pressed inside reach -- the duel, where orbiting is a defence worth paying for. */
    public static volatile int strafeNearTicks;
    private static final double KNOCKBACK_REACH = 2.0;
    /** Sprint hits carry further: max impulse measured 0.854 blocks/tick, ~2.1 blocks of carry. */
    private static final double KNOCKBACK_REACH_SPRINT = 2.2;
    /** One stride, for probing where an orbit side would leave us. */
    private static final double STRAFE_PROBE = 1.5;
    /** Ticks with the rim on the knockback line -- the exposure, and the measure this must lower. */
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
    /** Ticks this controller handed the camera to a bow on the critical stretch of its aim.
     *  Zero on a sword-only course by construction; on allround it is the measure of how much
     *  overlap the arbiter actually bought, and it must be non-zero for any claim about it. */
    public static volatile int aimYieldedToBow=0;

    public static void resetAimCounters() {
        aimBrake = 0; aimReposition = 0; aimEnemy = 0; aimPath = 0; aimNone = 0; aimYieldedToBow = 0;
        hopWind = 0; hopAir = 0; hopEdge = 0; hopInterval = 0; hopUnsafe = 0; hopFired = 0; hopDodge = 0; hopFar = 0;
    }

    public boolean tick(ClientPlayerEntity player, Entity target, WorldView world) {
        if (target == null || target.isRemoved() || !target.isAlive()) return false;

        TungstenConfig cfg = TungstenConfig.get();

        // safety: velocity tracking always, braking/viz only if enabled
        safety.tick(player, target, world);

        // ⛔ A BOW ON THE FINAL STRETCH OF ITS AIM OWNS THE CAMERA. MOVEMENT DOES NOT STOP.
        //
        // PathExecutor has honoured BowShooter.isAimCritical() for a while; this controller never
        // did, and that omission is what forces the agent to choose between closing and shooting.
        // Two writers on WindMouseRotation in one tick means last-writer-wins, so a shot taken
        // while punk is live never converges and the arrow is either loosed wild or times out --
        // which is why AllRound's drive calls punkStop before every arrow, and why the bot's combat
        // engine is ticked three to seven times less often than its opponent's:
        //
        //     punk called   bot 201-396   victim 1375-1404      (allround, n=3, healthy fps)
        //     combat ticks  bot 221-287   victim  417-475
        //     swings passed bot  24- 29   victim   39- 43
        //
        // The bot is not out-fought, it is out-TICKED: it spends the run standing still to shoot
        // while an opponent that never stops walks in with the initiative.
        //
        // Yielding the aim is enough because the two want nearly the SAME yaw whenever the shot
        // and the chase share a target -- walking at someone and aiming at them are the same
        // direction, and only the ballistic pitch differs. So the keys below keep running: the
        // bot closes, strafes and swings on schedule, and the bow gets an uncontested crosshair
        // for the ~22 ticks it actually needs.
        //
        // Movement keys are DELIBERATELY not gated on this. Skipping them too would reproduce the
        // exact defect this removes, one layer down.
        boolean bowOwnsCamera = kaptainwutax.tungsten.task.BowShooter.isAimCritical();
        if (bowOwnsCamera) aimYieldedToBow++;
        if (cfg.combatRotatesEnabled && !bowOwnsCamera) {
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
            // Published for TriggerBot's wasted-swing split: at a tick where the swing is ready and
            // the target is out of reach, is the bot even ASKING to walk forward? If it is, the
            // approach is failing on speed or geometry; if it is not, some other consumer owns the
            // legs and should be named rather than guessed at. The dodge was the obvious guess and
            // accounted for only a sixth of them.
            lastForwardPressed = resolved.forward;
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
        // ⛔ GROUND-DISTANCE POSITIONING: TRIED, MEASURED AT n=40 AN ARM, REVERTED.
        //
        // The argument was sound and is still sound: eyeToHitbox is 3D from the EYE, so a hop of
        // about 1.25 blocks turns a 3.0 ground gap into 3.25, and the hop counters say the bot is
        // airborne for 78-100% of the ticks that matter -- so the range control was reacting to its
        // own altitude. Positioning on ground distance, with TriggerBot keeping the exact 3D test,
        // removes that. It measured NOTHING:
        //
        //     first pair   ON 1.10  OFF 1.43   n=20 an arm   (0.33, 1.33 sigma -- looked real)
        //     pooled       ON 1.22  OFF 1.33   n=40 an arm   (0.11, 0.53 sigma -- nothing)
        //
        // Both arms same session, same clients, differing only by a pin, per checklist 4j. The
        // first pair is the whole lesson: 1.33 sigma at n=20 with a good mechanism behind it is
        // exactly what this work has shipped on three times and retracted three times. Doubling n
        // halved the effect.
        //
        // So the eye-vs-ground asymmetry is REAL and does not matter at this bench's resolution.
        // Do not re-open it without a course where the bot's altitude actually decides reach.
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
        // ⛔ "THE DEFAULT WINS" WAS n=3 AND IS OVERTURNED AT n=12. The table below reads 2.9 as
        // WORSE than 2.4 against three zombies (12/20/6 against 3/3/9). Re-run properly on
        // 2026-08-11 -- twelve runs an arm, same stand, same day, interleaved in blocks, exact
        // dmgTaken rather than min_hp -- with 2.9 wired for MOBS ONLY through the band below:
        //     2.4 (this table's winner)   3 9 6 20 17 9 9 9 6 3 17 15   mean 10.25  median 9.0
        //     2.9 (the mob band)          3 9 3 3 6 12 6 3 17 6 0 3     mean  5.92  median 4.5
        // Damage nearly halves, 2.0 sigma, and the 0 is mob_trio's FIRST EVER PASS. Three runs an
        // arm could not have separated these: this course's spread on one build is 3 to 20.
        //
        // The old table is kept because its 1.8 row still stands and its reasoning about knockback
        // is sound -- only its verdict on 2.9 was underpowered.
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
        // ⛔ A MOB IS NOT A PLAYER, AND UNTIL NOW IT WAS FOUGHT AS ONE.
        //
        // STRIKE_DISTANCE is 2.4 and TOO_CLOSE_DISTANCE is 1.6, both derived for a duel where the
        // opponent reaches as far as we do (3.0). A zombie's arm is MOB_ARM_REACH = 2.25 by the
        // same eye-to-hitbox measure, so those two numbers park the bot INSIDE the arm and do not
        // drift out until 1.6 -- deeper in. Every exchange concedes a free swing by construction.
        //
        // The mob band was derived for exactly this and then never read: MOB_STRIKE_DISTANCE 2.9
        // sits outside the 2.25 arm, MOB_BACK_OFF_DISTANCE 2.65 is the floor to step back out at.
        // #78 found that constant and nine others appearing exactly once in the repository, on
        // their own declarations. This is the first of them to be wired up.
        //
        // Deliberately ONE change: the base band only. The reach-control block below still owns
        // the cooldown stand-off, and the press/crowd-plan constants stay unwired until they can
        // be measured on their own.
        //
        // PREDICTED BEFORE THE RUN: mob_trio's exact dmgTaken should fall. Its spread on one build
        // is 3 to 18, so this is judged at n=6, not at the n=2 that made my last two attempts
        // unreadable. mob_melee and mob_weapon_swap must not move.
        boolean vsPlayer = target instanceof net.minecraft.entity.player.PlayerEntity;
        // ⛔ THE MOB BAND EXISTS TO STAY OUTSIDE AN ARM. A SHOOTER HAS NO ARM.
        //
        // MOB_STRIKE_DISTANCE is 2.9 for one stated reason, written above: a zombie's arm is
        // MOB_ARM_REACH = 2.25, so 2.9 hits without being hit. Against a skeleton that trade does
        // not exist -- its threat is the bow and it reaches us at 2.9 exactly as well as at 1.9 --
        // so the band buys nothing and costs the thing this course is actually priced by, which is
        // TIME IN CONTACT. Held at the outer edge of our own 3.0 reach, one knockback puts the
        // target outside it and the bot spends the next swing re-closing instead of hitting.
        //
        // RangedAttackMob is vanilla's own marker, the same property the flee reflex now asks, so
        // this names no mob. Zombies keep the arm band exactly as measured (mob_trio, mob_melee are
        // untouched by construction -- a zombie is not a RangedAttackMob).
        // TRIED AND REVERTED, 2026-08-11: giving a RangedAttackMob the tighter PLAYER band
        // (2.4/1.6) on the argument above. mob_skeleton min_hp, six runs an arm:
        //     mob band 2.9/2.65   12 3 15 16 16 16 16    median 16
        //     player band 2.4/1.6 20 11 13 12 16 16      median 14.5
        // Not an improvement, and the argument was sound enough that the reason matters: the swing
        // gates say REACH is what refuses, 35-56 refusals of 49-83 evaluations with only 1-4 swings
        // passing a whole fight. Both bands sit inside our 3.0 reach, so neither is what holds the
        // bot out -- something else is, and moving the band cannot fix it. Read the reach counter
        // before touching these numbers again.
        double strikeAt = vsPlayer ? STRIKE_DISTANCE : MOB_STRIKE_DISTANCE;
        double backOffAt = vsPlayer ? TOO_CLOSE_DISTANCE : MOB_BACK_OFF_DISTANCE;
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
            // ...AND ONLY WHERE THERE IS SOMETHING TO DISENGAGE *WITH*.
            // The paragraph above argues the retreat from the bow: out past reach the bow becomes
            // the weapon. It never asked whether the bot HAS one. On a sword-only kit the answer
            // is no, and then this branch buys nothing and spends everything -- the bot walks out
            // of range, deals zero, cannot heal, and gives the opponent the initiative for the
            // rest of a life it has already latched into.
            //
            // That is the exact shape of the two red courses. melee_basic and narrow_bridge are
            // both KIT_SWORD (an iron sword and nothing else) and both fail ONE criterion, the
            // same one -- won the exchange -- while swings, crits, damage, freezes, standstill
            // and fps all pass: 5:6, 5:6 and 12:15, 11:17. edge_duel carries the same kit, fires
            // this branch far less because a 5x5 platform has nowhere to retreat TO, and wins 4/4
            // on the same jar. A wounded fighter with no ranged option has to fight.
            if (hp <= LOW_HP && dist > TriggerBot.REACH) {
                if (!WeaponSelector.hasRangedOption(player)) {
                    // ⛔ THE OLD BRANCH DID TWO THINGS, AND ONLY ONE OF THEM WAS WRONG.
                    // Removing it whole cost edge_duel: n=8 on a healthy stand came back
                    // +1,-1,+1,-3,-4,+1,-2,-5 -- median -1.5, 3 passes in 8 -- on a course that
                    // closed 4/4 with the branch in place. The trigger for this change was
                    // declared before that measurement ran.
                    //
                    // What kite() actually did: out.forward = false, and THEN out.back only when
                    // dirSafe(back). On a 5x5 platform over void dirSafe(back) is false almost
                    // everywhere near the rim, so there was never a retreat there -- only a bot
                    // that stopped walking into a fight it was losing. That half is sound. The
                    // half that is not is stepping OUT to a range where a bowless bot can do
                    // nothing, which is what the predicate above still refuses.
                    //
                    // So: hold, do not withdraw. And do it with a flag rather than kite()'s early
                    // return, because that return also skips the circle-strafe, the crit hop and
                    // the trigger -- and melee_basic's gain rests on those still running.
                    lowHpDeclined++;          // the ticks this predicate gave back to the fight
                } else {
                    lowHpTicks++;
                    kite(out, player, world, dist);
                    return;
                }
            }

            // ⛔ A STAND-OFF YOU CANNOT WALK BACKWARDS FROM IS NOT A STAND-OFF. IT IS STANDING.
            //
            // The block below raises strikeAt to REACH+0.4 and backOffAt to REACH+0.2 while the
            // swing recharges. Look at what the two keys then do, thirty lines further down:
            //     out.forward = dist > strikeAt && dirSafe(fwd)
            //     out.back    = dist < backOffAt && !beingHit && dirSafe(back)
            // On open ground the bot backs off, gains the block, and closes when the swing is
            // ready -- the behaviour as designed. Where `back` is unsafe, `back` is never pressed;
            // and because strikeAt was raised to 3.4, `forward` is false at every distance under
            // that too. The bot presses NEITHER KEY. It stands still, inside the reach of an
            // opponent that has no such rule and simply walks in and swings.
            //
            // MEASURED, n=11 an arm on edge_duel (a 5x5 platform over void, where dirSafe(back)
            // is false almost everywhere near the rim), the victim on the baseline engine:
            //     with this setting     margins -7 -5 -2 0 -10 +2 -4 -2 -4 -6 -4   median -4
            //     both sides without it  margins  0 +2 -5 +1  +1 -1 +1 +4  0 -3 +1  median +1
            // 3.2 sigma, and the mirror arm's mean is +0.09 -- exactly the zero a duel between two
            // copies of one build must produce, which is what makes the other column readable.
            // The bot took 1.93x the blows it landed with the setting and 1.12x without.
            //
            // And it is the ARENA, not the setting: on melee_basic, whose spread is 0.75 and which
            // would show a shift this size at more than five standard errors, the same comparison
            // reads median 0 over n=7. So the cure is not to delete the stand-off -- it works
            // where retreating works -- it is to stop applying it where retreating cannot happen.
            //
            // dirSafe(back) is checked with the SAME arguments the key press uses, so the two
            // cannot drift apart: if the retreat would be refused down there, the band is not
            // raised up here, and the bot keeps its ordinary strike distance and fights.
            // ⛔ KEYING THIS TO THE OPPONENT'S SWING CLOCK WAS TRIED TWICE AND IS WORSE. DO NOT.
            //
            // The reasoning is good enough that it will occur to the next person too: `armed` is
            // OUR cooldown, and standing off while OUR swing recharges only avoids a blow when the
            // two cooldowns happen to be in phase -- which nothing keeps them in. The blow that
            // lands is THEIRS, so key off theirs. A vanilla swing animation is broadcast to every
            // client, so their period IS observable, and lastSwingMs (declared here with no writer
            // and no reader) even looks like it was meant for that.
            //
            // Measured on edge_duel against this version's -0.27 at n=11:
            //     stand off while their swing is imminent      -4, 0, -4, -2   mean -2.5
            //     ...and treat a stale clock as NOT imminent    0, -8, -5, +1   mean -3.0
            // Pooled n=8 that is about 2.2 sigma BELOW the shipped behaviour, and the counters say
            // why in both directions: the first cut read "imminent" permanently, because a player
            // walking toward us has not swung recently by definition (theirSwing=240/236), so the
            // band stayed raised and the bot never closed. Guarding the staleness inverted it --
            // 42/344, i.e. almost always pressing in -- and that was no better. The window this
            // reasoning wants does not survive contact with an opponent who is sometimes walking.
            //
            // What the two runs DID confirm is the premise underneath: this flag is the only
            // asymmetry these gates can see, so it is the only place a fix can come from
            // (docs/features/PVP_SUITE.md). The next idea for it needs to beat -0.27, and the
            // measurement is edge_duel A/B at n=11 an arm -- nothing smaller separates anything.
            boolean canWithdraw = dirSafe(player, world, -1, 0);
            if (!armed && !canWithdraw) standOffDeclined++;   // the ticks this guard actually took
            if (!armed && canWithdraw) {
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
                // ⛔ BUT NOT AGAINST SOMETHING THAT REACHES US ANYWHERE.
                //
                // Withdrawing while the swing recharges is sound in a DUEL: it leaves the
                // opponent's reach for the ticks we cannot hit back. A shooter has no such reach to
                // leave -- it hits us at 3.4 exactly as well as at 2.9 -- so the withdrawal buys
                // nothing and costs the time to close again, and TIME IN CONTACT is what this
                // course's arrows are priced by. Every extra second beside a skeleton is half
                // another shot at us.
                //
                // Same property test as the flee reflex and the dodge, so no mob is named, and
                // duels are untouched by construction (a player is not a RangedAttackMob).
                // ⛔ AND IT SHOWS NO MEASURED EFFECT AT n=40. The arm read 0.77 arrows at n=13,
                // 0.90 at n=26 and 1.18 at n=40 -- identical to the 1.18 baseline. Both earlier
                // readings were noise, and this was shipped on the 1.4 sigma the n=26 figure gave.
                //
                // KEPT ON THE MECHANISM ALONE, which is a plain logical correction: withdrawing
                // while the swing recharges leaves a DUEL opponent's reach, and a shooter has no
                // reach to leave, so the withdrawal can only add re-closing time. It is inert for
                // players and zombies by the type test, so it costs nothing to hold. It is NOT an
                // improvement and must not be quoted as one.
                //
                // THE REAL LESSON, and it is the fourth time in this work: on this course n=13 and
                // n=26 BOTH misled. Nothing here is believable under n=40, and preferably against a
                // baseline measured in the same session.
                if (!(target instanceof net.minecraft.entity.ai.RangedAttackMob)) {
                    strikeAt = TriggerBot.REACH + 0.4;
                    backOffAt = TriggerBot.REACH + 0.2;
                }
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
        // ⛔ THESE TWO COUNT POSITION, NOT KEYS -- THE NAMES OVERSTATE THEM.
        // hurtBackingOff is "hurt AND closer than backOffAt", not "hurt AND pressed back". Since
        // out.back gained its !beingHit guard a few lines below, the bot does not retreat while it
        // is being hit at all, so a high hurtBackingOff now says the OPPOSITE of what it reads
        // like: the bot is standing INSIDE the trade. Misread once already, from these very
        // numbers -- 115/45/65 and 133/54/70 -- as "it retreats more than it advances", which
        // would have sent a fix at a mechanism that was already fixed. They also do not partition:
        // the band between backOffAt and strikeAt increments neither, so the two never have to sum
        // to hurtTicks.

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
        // ⛔ THE SPRINT CUT-OFF LEFT A DEAD BAND THE BOT COULD NOT CROSS.
        //
        // This was `dist > strikeAt + 1.0`, which against a mob is 2.9 + 1.0 = 3.9. Our own swing
        // needs eye-to-hitbox <= TriggerBot.REACH = 3.0. So between 3.0 and 3.9 the bot WALKED:
        // too close to be allowed to sprint, too far to be allowed to hit. A skeleton retreats as
        // you approach, at about a walking bot's speed, so that band is not crossed by walking --
        // the gap simply holds.
        //
        // Measured on mob_skeleton, eight runs, over the swings the reach gate REFUSED:
        //     reachMean 3.52 3.52 3.48 3.64 3.57 3.56 3.71 3.50   (threshold 3.0)
        //     reachMax  4.06 4.15 4.13 4.11 4.18 4.02 4.10 4.08
        // The bot sits half a block outside its own reach, every run, and only 1-4 swings pass a
        // whole fight while 35-56 are refused for reach.
        //
        // AND WHY THE OBVIOUS VERSION OF THIS WAS REFUTED BEFORE. "Hold SPRINT while approaching"
        // was tried and measured WORSE -- closest_gap 5.73 -> 7.78 (checklist rule five). That is
        // what sprint KNOCKBACK does: a blow landed while sprinting throws the target further away
        // and the chase restarts, so sprinting all the way through the swing enlarges the very gap
        // it was meant to close. Hence the cut-off is our REACH and not the strike band: sprint
        // across the dead zone, arrive walking, and let the hit land without the extra shove.
        // ⭐⭐ AND THE FOURTH ATTEMPT IS UNNECESSARY: THE REACH TICKS DO NOT COST ARROWS.
        // Pooled over 33 valid runs from two series, correlation against arrows landed:
        //     reach ticks  +0.17   (nothing; n=33 puts that well inside noise)
        //     band  ticks  +0.43   (real at this n)
        // So the quantity three attempts fought over does not predict the outcome, while TOTAL time
        // in the band does -- and the bulk of that is the ~91 ticks where the controller is not
        // ticking at all, not the 34.8 where it ticks out of reach.
        // One correlation would have retired this branch before any of the three code changes. That
        // is the cheaper instrument and it existed the whole time.
        //
        // ⛔ READ THIS BLOCK BEFORE ATTEMPTING THE REACH REFUSALS A FOURTH TIME (2026-08-13).
        // The 34.8 ticks a fight of "controller running, target unreachable" are real and still
        // unsolved, but THREE attempts on them are now recorded and each failed differently:
        //   1. hold sprint while approaching        -> WORSE, closest_gap 5.73 -> 7.78 (knockback)
        //   2. combatHoldContactOnShooter           -> refuted; and note it cannot even touch the
        //      out-of-reach ticks, since swingImminent is false there and line 923 already sprints
        //   3. combatCloseToReach (in altoclef)     -> tripled the quantity it targeted, because
        //      gating onEntityInteract at 3.0 takes THIS controller out of the closing zone
        // The common shape: every remedy that pushes harder into contact pays for it in knockback,
        // and every remedy that delays the handover pays for it in the controller not running.
        // A fourth attempt needs a mechanism that does neither -- or evidence that the 34.8 ticks
        // are not costing arrows at all, which is entirely possible: two of the three attempts moved
        // their mechanism counters cleanly and moved arrows not at all.
        out.sprint = out.forward && dist > TriggerBot.REACH;
        // RESTORED FOR A PROPER MEASUREMENT (default off): drop sprint at the SWING rather than at
        // REACH, against a RangedAttackMob only. The blow still lands unsprinted — that was the
        // whole point of the cut-off, and it is what keeps crits possible — while the bot is
        // allowed to keep pace with a target that retreats at walking speed. Judged on
        // TriggerBot.ready=far/near, the counter for the mechanism, not on arrows landed.
        if (kaptainwutax.tungsten.TungstenConfig.get().combatHoldContactOnShooter
                && target instanceof net.minecraft.entity.ai.RangedAttackMob) {
            boolean swingImminent = dist <= TriggerBot.REACH
                    && player.getAttackCooldownProgress(0f) >= 0.85f;
            out.sprint = out.forward && !swingImminent;
        }
        // TRIED AND REFUTED, 2026-08-12: holding sprint against a RETREATING SHOOTER until the
        // swing instead of dropping it at REACH. The argument was sound — a skeleton retreats at
        // about a walking bot's speed, so the tick the bot crosses 3.0 and slows it falls back
        // into the 3-6 band where the arrows actually land (gapMean 3.79-5.03, gapMax 6.30, past
        // what any dodge can beat) — and the blow would still land unsprinted, keeping crits.
        //
        // Measured as a PINNED SAME-SESSION PAIR on mob_skeleton, twelve runs an arm, judged on
        // arrows landed = (20 - min_hp)/4 with the rule written before the data existed:
        //
        //     flag off   n=12  mean 1.19 arrows  sd 0.37
        //     flag on    n=12  mean 1.69 arrows  sd 0.91
        //     difference -0.50 arrows, SE 0.28 -> 1.77 sigma
        //
        // Below the 2-sigma bar, so "no effect at this resolution" — and the sign is the wrong
        // way round, so there is certainly no case for shipping it. The branch is gone; this note
        // is what remains, so the next pass does not re-derive it.
        //
        // Worth keeping from the exercise: within ONE session the baseline arm's spread is
        // sd 0.37, against sd 1.20 pooled across series. That gap IS the between-series noise
        // rule 4j warns about, measured.

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
        // ⛔ TRIED AND REVERTED: GIVING A SHOOTER THE STEADY ORBIT TOO.
        //
        // The argument was lateral SPEED -- a flip is a moment of zero sideways velocity, the flip
        // runs every 500-1200 ms while a skeleton fires about every 2000 ms, so the overlap is
        // likely rather than rare, and inside contact an arrow arrives in under a tick so reacting
        // to it is too late by construction. It should have been the orbit's job, not the dodge's.
        //
        // Measured on arrows landed, which is this course's real ruler:
        //     flip kept      mean 1.10 arrows   sd 0.75   n=27
        //     steady orbit   mean 1.60 arrows   sd 1.02   n=13
        // Worse, and the reasoning was backwards. What the flip buys is not speed but
        // UNPREDICTABILITY: a constant orbit is a constant velocity, which is exactly what a mob's
        // aim leads correctly. Randomising the side is what makes it miss. Do not remove it.
        //
        // Note the cost of finding this out: ONE n=12 run, because the ruler was characterised
        // first. The same question judged on pass counts would have taken days and still not
        // answered.
        if (!crowded && now - lastStrafeSwitch > strafeInterval) {
            strafeDir = -strafeDir;
            lastStrafeSwitch = now;
            strafeInterval = 500 + (long) (Math.random() * 700);
        }
        if (!strafeSideSafe(player, world, strafeDir)) {
            strafeDir = -strafeDir;               // try the other side immediately
            lastStrafeSwitch = now;
        }
        // ⛔ CIRCLE THE SIDE THAT PUTS FLOOR AT YOUR BACK, NOT THE VOID.
        //
        // MEASURED on edge_duel across three runs: vgFall onset 5/9, hurt 5/8, sprint 0/0 -- every
        // fall begins on a tick where hurtTime > 0 and none while sprinting. The bot is HIT off the
        // platform, not walked off, and rimBack read 327 ticks of standing with the rim in the line
        // a blow throws it along. Nothing inside VoidGuard can answer that: knockback is a velocity
        // the server applies and the guard only releases keys and presses sneak, which is inert in
        // the air anyway.
        //
        // A FIRST ATTEMPT AT THIS WAS REVERTED, and its failure is the reason this one is shaped
        // differently. It refused to retreat and closed instead -- but the closing half was gated
        // on !canStrafe, and on a 5x5 platform a strafe is nearly always available, so in practice
        // it only suppressed `back` and never moved the bot off the dangerous line. Onsets went
        // 5 -> 9. Suppressing a direction is not repositioning.
        //
        // So choose the ORBIT DIRECTION by where it leaves us: step each candidate out by a stride,
        // and ask whether the rim would then lie behind us on the knockback line. Circling the
        // target rotates that line, so one side genuinely fixes what the other does not.
        net.minecraft.util.math.Vec3d selfPos = player.getEntityPos();
        net.minecraft.util.math.Vec3d toTgt = target.getEntityPos().subtract(selfPos);
        if (toTgt.horizontalLengthSquared() > 1e-6) {
            net.minecraft.util.math.Vec3d fwdN = new net.minecraft.util.math.Vec3d(
                    toTgt.x, 0, toTgt.z).normalize();
            // MC convention: sideways +1 is LEFT, which is (-fwd.z, +fwd.x) rotated
            net.minecraft.util.math.Vec3d leftN =
                    new net.minecraft.util.math.Vec3d(-fwdN.z, 0, fwdN.x);
            // A SPRINTING ATTACKER IS A DIFFERENT THREAT, and the impulse says so plainly:
            // mean 0.439 blocks/tick carries ~1.1 blocks, but the max seen is 0.854 -- roughly
            // 2.1 blocks, past this platform's radius. That gap is the sprint hit. An ordinary
            // blow cannot throw a centred fighter off a 5x5 board; a sprint blow can.
            //
            // So the danger is not "a rim within two blocks", it is "a rim behind me while
            // someone runs at me", which is a far narrower condition and one the bot can see.
            // Guarding the wide case all the time is what the 3.0 constant did, and it cost
            // exposure without buying safety.
            double reach = (target instanceof net.minecraft.entity.LivingEntity le && le.isSprinting())
                    ? KNOCKBACK_REACH_SPRINT : KNOCKBACK_REACH;
            boolean rimBehindNow = VoidDetector.edgeAhead(
                    selfPos, -fwdN.x, -fwdN.z, world, 3, reach);
            if (rimBehindNow) {
                rimAtBackTicks++;
                for (int cand : new int[]{strafeDir, -strafeDir}) {
                    net.minecraft.util.math.Vec3d step = leftN.multiply(cand * STRAFE_PROBE);
                    net.minecraft.util.math.Vec3d after = selfPos.add(step);
                    net.minecraft.util.math.Vec3d awayAfter = after.subtract(target.getEntityPos());
                    if (awayAfter.horizontalLengthSquared() < 1e-6) continue;
                    boolean stillRim = VoidDetector.edgeAhead(
                            after, awayAfter.x, awayAfter.z, world, 3, KNOCKBACK_REACH);
                    if (!stillRim && strafeSideSafe(player, world, cand)) {
                        strafeDir = cand;         // this orbit side gets the void off our back
                        lastStrafeSwitch = now;
                        break;
                    }
                }
            }
        }
        boolean canStrafe = strafeSideSafe(player, world, strafeDir);
        // ⛔ A READY SWING OUTRANKS THE ORBIT (behind a pin, default off).
        //
        // MEASURED, and it is the end of a chain four other counters could not close. On
        // mob_skeleton a strafe key is held in ~55% of the ticks where the swing is READY and the
        // target is OUT of reach — at the same time as forward and sprint. The bot is running
        // DIAGONALLY, and a 45-degree diagonal leaves only ~70% of the speed pointing at the
        // target: a 5.6 blocks/s sprint closes at ~3.9 while a skeleton retreats at about 5. The
        // bot loses ground while honestly holding every key it should.
        //
        // Arrows landed on that course is a function of FIGHT LENGTH — the skeleton fires once per
        // second of draw and does not shoot at the approach at all — so an orbit that adds seconds
        // is paid for in arrows. Orbiting is a defence worth having; it is not worth having while a
        // matured swing goes stale out of range.
        //
        // Narrow on purpose: only when the swing is ready AND the target is beyond reach. Inside
        // reach the orbit still runs, so flanking and the rim-safe side choice are untouched, and
        // nothing changes at all for an opponent the bot is already hitting.
        boolean readySwingOutOfReach =
                kaptainwutax.tungsten.TungstenConfig.get().combatCloseOverOrbit
                && dist > TriggerBot.REACH
                && player.getAttackCooldownProgress(0f) >= TriggerBot.COOLDOWN_CRIT;
        // THE PHASE DISTINCTION THE COUNTERS ARGUE FOR: an approach is not a duel. Orbiting is a
        // defence against someone already swinging at you; while the target is out of reach it buys
        // nothing and costs ~30% of closing speed to the 45-degree line. Wider than
        // combatCloseOverOrbit on purpose -- that one waited for a MATURE SWING, which is a minority
        // of ticks, and so never touched the 69% of orbit ticks that happen out of reach.
        boolean approachPhase =
                kaptainwutax.tungsten.TungstenConfig.get().combatApproachNoOrbit
                && dist > TriggerBot.REACH;
        out.left = canStrafe && !readySwingOutOfReach && !approachPhase && strafeDir > 0;
        out.right = canStrafe && !readySwingOutOfReach && !approachPhase && strafeDir < 0;
        // ⭐ ANSWERED, AND IT IS THIS CONTROLLER, NOT THE PATH (2026-08-13). Four runs:
        //     strafeFar/strafeNear = 54/15, 18/12, 25/17, 41/19  -> 138 far against 63 near.
        // Sixty-nine percent of the orbit happens while the target is OUT of reach, i.e. during the
        // approach, where a 45-degree line costs ~30% of the speed and therefore about one whole
        // skeleton shot. My own guess -- that the diagonal belonged to KillEntitiesTask's 7->4.5 leg
        // -- was wrong, and the counter said so in four runs.
        //
        // IT ALSO CLEARS combatCloseOverOrbit's NEGATIVE RESULT. That flag suppressed this strafe
        // only when a swing was ALREADY MATURE, which is a minority of ticks; it never touched the
        // bulk of these 138 and so could not have moved the arrow count either way. Its measured
        // "made arrows worse" was never evidence against the diagonal -- it measured something else.
        //
        // THE FIX IS NOT MERELY SUPPRESSING THE ORBIT: 'closeOverOrbit' already tried a version of
        // that and lost. What the numbers argue for is a PHASE distinction -- the approach is not a
        // duel and should not be fought like one. Orbit is a defence against a melee opponent who is
        // already swinging; against a bowman at 6 blocks it is a 30% speed tax paid for nothing.
        //
        // ⛔ WHERE DOES THE DIAGONAL COME FROM? The next hypothesis worth a series is that the
        // approach travels at 45 degrees and so pays ~30% of its speed (3.9 b/s against 5.6), which
        // costs about one whole skeleton shot -- the right size against the ~1.9-arrow gap, unlike
        // the engage band, which measured under one arrow at full power.
        // But it is NOT established that this controller is what bends it: the 7->4.5 leg belongs
        // to KillEntitiesTask, and combatCloseOverOrbit (suppressing this very strafe out of reach)
        // was measured and made arrows WORSE. So split the strafe by distance before touching
        // anything: if strafeFar is near zero the diagonal is the PATH's, not the orbit's, and the
        // work belongs in the approach rather than here.
        if (out.left || out.right) {
            if (dist > TriggerBot.REACH) strafeFarTicks++;
            else strafeNearTicks++;
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
        // ⛔ SUPPRESSING THE RANDOM JUKE TO "PROTECT" THE CRIT HOP IS REFUTED. TRIED AND REVERTED.
        //
        // The reasoning looked sound: a swing cycle is 12.5 ticks, a jump holds the bot off the
        // ground for about 600 ms of it, and the crit take-off window (cd 0.25-0.50) is ticks 3-6 --
        // so a juke launched just after a swing leaves the bot airborne exactly when it needs to
        // take off, and it lands flat-footed. Making the crit window the ONLY hop should have made
        // crits reliable.
        //
        // It did the opposite, n=8 an arm on mob_skeleton:
        //     random juke kept    critWindowSwings 2 0 0 0 0 2 2 0   crits mean 1.0   1/8 PASS
        //     juke suppressed     critWindowSwings 1 0 1 2 0 0 0 0   crits mean 0.5   0/8 PASS
        //
        // So the juke was not stealing the take-off, it was PROVIDING it: with only 2-4 swings in a
        // fight, the crit window is missed for reasons of its own (not grounded, edge score, landing
        // check) and the random hop was the thing that happened to put the bot in a descent often
        // enough to matter. Remove it and the accidental crits go with it.
        //
        // The lesson for whoever times this next: crits here are not a cadence problem. Find out
        // WHY the windingUp branch fails to take off -- count the three gates below separately --
        // before touching the interval again.
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
        // WHICH OF THE FOUR GATES EATS THE CRIT TAKE-OFF? Counted only over the windingUp window,
        // because that is the only window in which a hop can produce a crit -- a count over all
        // ticks would be a statistic about juking, not about crits (checklist rule 4c).
        //
        // Two plausible fixes have now been refuted here in a row -- the player band, and
        // suppressing the juke -- so this is rule one applied literally: stop patching the thing
        // and measure the input. critWindowSwings reads 0 in about half of all runs and nothing in
        // the repository can currently say why.
        // ⛔ TRIED AND REVERTED: STANDING THE HOP DOWN WHILE DODGING, FOR GROUND TRACTION.
        //
        // Minecraft air control is a fraction of ground control: a lateral key pressed mid-air
        // barely accelerates the body, which keeps whatever momentum it left the ground with. So a
        // bot that is airborne 78-100% of the time -- which the hopAir counter says this one is --
        // presses its dodge keys into the air and does not actually leave the arrow's line.
        //
        // That is the same shape as the defect that made the dodge worth fixing at all: it now
        // REACHES the keys (dodgeDrive is non-zero) and the hop then denies them traction. Two
        // systems, one body, and the hop wins by being airborne first.
        //
        // MEASURED FLAT, n=12: 2/12 PASS against 2/12 for the build without it, min_hp median
        // 12-13 either way. And the counter says why it could not have worked: hopDodge -- crit
        // windows where the hop stood down for an inbound arrow -- reads 4 0 0 2 0 3 0 0 2 0 2 0 0
        // against 3-9 windows a fight. The overlap between "an arrow is inbound" and "the hop wants
        // to take off" is a couple of ticks a fight, far too small a slice to move anything.
        //
        // The air-control argument itself is untested by this, not refuted: the bot is still
        // airborne 78-100% of the ticks that matter, and lateral keys still do little up there.
        // What is refuted is that the DODGE WINDOW is where that costs the fight. If this is worth
        // another attempt it has to reduce the airborne fraction across the WHOLE fight, and the
        // outright version of that is already measured and reverted (crits 1.0 -> 0.5, 1/8 -> 0/8).
        boolean gDodging = kaptainwutax.tungsten.task.ProjectileDodge.isActive();
        // ⛔⛔ THE BUNNY HOP IS LOAD-BEARING. THREE SEPARATE ATTEMPTS TO REDUCE IT ALL MEASURED WORSE.
        //
        // This one gated it on a DERIVED radius: eyeToHitbox is 3D from the eye and vanilla reach
        // is too, so a 1.25-block hop turns a ground gap d into sqrt(d^2 + 1.25^2), and the bot
        // must be inside sqrt(REACH^2 - 1.25^2) = 2.73 for leaving the ground to be free. Beyond
        // that the hop takes the swing out of range -- which is why REACH stays the dominant
        // refusal (15-65 a fight against 2-4 swings passed). Sound arithmetic; wrong answer.
        //
        // Measured on arrows landed, the ruler for this course:
        //     hop as-is                mean 1.10   sd 0.75   n=27
        //     hop gated by distance    mean 1.63   sd 0.87   n=13
        // Worse, and `passed` swings did not move either (3, range 2-4) -- so it did not even buy
        // the swings it was designed to buy.
        //
        // THE FAMILY, so nobody re-opens it one branch at a time:
        //     suppress the juke outright     crits 1.0 -> 0.5, course 1/8 -> 0/8
        //     stand it down while dodging    2/12 vs 2/12, and hopDodge showed why (a couple of
        //                                    ticks of overlap a fight)
        //     gate it by distance            arrows 1.10 -> 1.63
        // Whatever the hop costs in reach, it buys more back -- crits, and an unpredictable
        // profile that a mob's aim cannot lead (see the strafe-flip refutation above, same lesson).
        // Do not reduce the hop. If reach refusals are to be attacked, attack them somewhere else.
        boolean gAir = !player.isOnGround();
        boolean gEdge = edgeScore >= 0.4;
        boolean gInterval = now - lastJump <= interval;
        boolean gLand = !SafetySystem.isJumpLandingSafe(
                player.getEntityPos(), player.getVelocity(), world);
        if (windingUp) {
            hopWind++;
            if (gAir) hopAir++;
            if (gEdge) hopEdge++;
            if (gInterval) hopInterval++;
            if (gLand) hopUnsafe++;
        }
        if (windingUp && gDodging) hopDodge++;
        if (!gAir && !gEdge && !gInterval && !gLand) {
            out.jump = true;
            lastJump = now;
            jumpInterval = jcfg.combatBunnyHopMinMs + (long) (Math.random() * jcfg.combatBunnyHopRandMs);
            if (windingUp) hopFired++;
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
