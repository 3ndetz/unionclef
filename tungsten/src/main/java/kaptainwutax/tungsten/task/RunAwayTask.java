package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.combat.VoidDetector;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Flee a named player, keeping at least {@code keepDistance} blocks. The mirror
 * of {@link FollowEntityTask}: instead of pathing TO the threat it paths to the
 * safest reachable point directly AWAY from it, re-planning as the threat moves.
 *
 * Void-aware: a flee waypoint is only accepted on solid ground that isn't over a
 * serious drop, so keeping distance never means backing off the island. When the
 * straight-away direction is blocked by a wall or the void it samples angled
 * fallbacks and takes whichever safe point sits furthest from the threat.
 *
 * Primitive, not policy — the agent decides WHEN to flee and from WHOM; the mod
 * just executes the keep-distance retreat. Ticked every game tick from
 * MixinClientPlayerEntity.
 */
public class RunAwayTask {

    private static final int    RECALC = 10;   // re-plan cadence (ticks)
    private static final double STEP   = 8.0;  // how far ahead to aim the flee point

    private static boolean active       = false;
    private static String  threatName   = null;
    private static double  keepDistance = 8.0;
    /** Previous tick's distance to the threat, so the hold can tell closing from holding. */
    private static double  lastThreatDist = -1;

    /**
     * Ticks the threat spent inside melee reach, and inside a stride of it.
     *
     * <p>A COUNT, DELIBERATELY, NOT A RATE. bow_flee's death total ranges 4 to 10 on unchanged
     * code (n=8 at healthy fps), so no tightening of the flee objective can be demonstrated by
     * counting deaths -- the effect would have to exceed the spread. Ticks-in-reach is a count
     * over 1200 ticks of course, it moves with the behaviour rather than with luck, and a
     * monotonic counter cannot be missed by the bench's 1 Hz poll.
     *
     * <p>The poll is exactly why this has to live here. The victim on this course carries only a
     * sword (scenarios_pvp: victim_kit = KIT_SWORD), so EVERY death is a catch -- and yet the
     * sampler reported 0 of 14 samples inside reach on a run with six of them. The gap collapses
     * and recovers between samples. Only per-tick counting sees it.
     */
    public static volatile int reachTicks;
    public static volatile int nearTicks;
    /** Of those ticks, how many had the bow drawn -- i.e. sprint unavailable. */
    public static volatile int reachDrawingTicks;
    public static volatile int nearDrawingTicks;
    /** Of the in-reach ticks: how many were spent sprinting, and how many moving away. */
    public static volatile int reachSprintTicks;
    public static volatile int reachAwayTicks;
    private static PlayerEntity threat  = null;
    private static int     tickCounter  = 0;

    /**
     * What the flee actually spent its time doing. Read over py4j as
     * {@code flee=held/searching/ran/plans}.
     *
     * <ul>
     *   <li>{@code fleeHeld} — standing still on purpose, already far enough
     *   <li>{@code fleeSearch} — a path SEARCH in flight: also standing still, but not on purpose
     *   <li>{@code fleeRan} — the executor actually replaying a path
     *   <li>{@code fleePlans} — how many routes were requested
     * </ul>
     *
     * <p>ANSWERED, and the answer needed the split. The question this block used to pose was
     * "held vs ran": if holding dominated, the stop-start reading was right; if running dominated,
     * the bot was simply losing a footrace. Running dominated ~5:1 — so it looked like a footrace,
     * and it was not one.
     *
     * <p>The reason is that {@code fleeRan} was counting SEARCHES as running.
     * {@code PathFinder.active} is raised at the top of {@code find()} and cleared when the worker
     * finishes, so it means a search is in flight and the bot is stationary. One run read
     * {@code flee=244/894/103} while {@code execTicks} — the executor's own count of replayed
     * ticks — was 618. The missing 276 ticks are nearly FOURTEEN SECONDS of a sixty-second course
     * spent looking for a path instead of following one, and no combination of the old three
     * numbers could show it.
     *
     * <p>That is also what {@code RECALC} was buying: the old tick replanned every 10 ticks whether
     * or not a good path was running, tearing it down with {@code stop.set(true)} and starting a
     * 400ms search on a stand that renders at ~9 fps. Flee paths are ~8 blocks, about two seconds
     * of travel, so they exhaust on their own; replanning now waits for that.
     */
    public static volatile int fleeHeld, fleeRan, fleePlans;
    /** Ticks spent with a SEARCH in flight — standing still, not fleeing. Split out of fleeRan,
     *  which had been counting them as running and hiding ~14s of a 60s course. */
    public static volatile int fleeSearch;
    /** Ticks the break-contact fallback actually drove the keys. Exists to prove the
     *  fallback RUNS: its first version never did, and read as a refuted hypothesis. */
    public static volatile int fleeDriveTicks;
    /** Ticks the fallback DECLINED to drive because the next step had no floor. On a
     *  1-wide bridge this should dominate fleeDriveTicks; on flat ground it should be 0. */
    public static volatile int fleeDriveBlocked;

    /** Zeroed by resetRunCounters so a bench run measures itself, not the stand's history. */
    public static void resetCounters() {
        fleeHeld = 0;
        fleeSearch = 0;
        fleeDriveTicks = 0;
        fleeDriveBlocked = 0;
        fleeRan = 0;
        fleePlans = 0;
    }

    /** Start fleeing {@code name}, keeping at least {@code dist} blocks (min 3). */
    public static void start(String name, double dist) {
        stop();
        PunkPlayerTask.stop();            // can't hunt and flee at once
        threatName   = name;
        keepDistance = Math.max(3.0, dist);
        lastThreatDist = -1;
        active       = true;
        Debug.logMessage("Run away from " + name + " (keep " + (int) keepDistance + ")");
    }

    public static void stop() {
        if (active) {
            TungstenModDataContainer.PATHFINDER.stop.set(true);
            if (TungstenModDataContainer.EXECUTOR != null) TungstenModDataContainer.EXECUTOR.stop = true;
            releaseKeys();
        }
        active = false; threatName = null; threat = null; tickCounter = 0;
    }

    public static boolean isActive()      { return active; }
    public static String  getThreatName() { return threatName; }

    /** The gap this flee order was told to hold. Other primitives need it to avoid working
     *  against the order — see {@code BowShooter.shootAt}. */
    public static double getKeepDistance() { return keepDistance; }

    /**
     * Count exposure for THIS tick. Called from the client tick, unconditionally.
     *
     * <p>IT USED TO LIVE INSIDE THE TASK BODY and that made it lie. The task has early returns --
     * the hold branch, the search path -- so the counter only ran on ticks that reached it, and
     * the denominator moved between runs: 25, 14, 12, then 0 on a run with closest=2.72 and four
     * deaths, which is arithmetically impossible for a counter that sees every tick inside three
     * blocks. It was measuring exposure DURING A FLEE and being read as exposure.
     *
     * <p>Here it runs while the flee ORDER stands ({@code threat} survives until stop()), which is
     * the span the question is actually about.
     */
    /**
     * Keys as they stand AFTER VoidGuard has had its say -- the state the player actually ticks with.
     *
     * <p>countExposure runs between the drive and the guard, so its "keys down" reading proves only
     * that the drive pressed them. VoidGuard zeroes movement keys on an edge, and every stall sits
     * at radius 18.6 against the arena boundary, which is where an edge test would fire. Six fixes
     * were reverted for changing which keys the drive presses; if the guard clears them afterwards,
     * all six were editing a value nobody reads.
     */
    public static void countKeysAfterGuard(ClientPlayerEntity player) {
        if (player == null || threat == null || !threat.isAlive() || threat.isRemoved()) return;
        if (player.getEntityPos().distanceTo(threat.getEntityPos()) > 5.0) return;
        Vec3d hv = player.getVelocity();
        if (Math.sqrt(hv.x * hv.x + hv.z * hv.z) >= 0.02) return;
        var o = MinecraftClient.getInstance().options;
        if (o.forwardKey.isPressed() || o.backKey.isPressed()
                || o.leftKey.isPressed() || o.rightKey.isPressed()) {
            keysDownAfterGuardTicks++;
        }
        stalledSeenAfterGuard++;
    }

    public static void countExposure(ClientPlayerEntity player) {
        if (player == null || threat == null || !threat.isAlive() || threat.isRemoved()) return;
        double d = player.getEntityPos().distanceTo(threat.getEntityPos());

        // CALIBRATE THE REACH BY THE BLOWS, not by arguing about hitbox widths. 3.0 was wrong, the
        // 3.6 I "corrected" it to is wrong too -- 19 blows landed in a run where only 5 ticks were
        // counted inside it. Record the distance AT the moment a blow arrives and let the
        // distribution state the reach. hurtTime goes positive on the tick damage lands, so its
        // rising edge is the event.
        boolean hurtNow = player.hurtTime > 0;
        if (hurtNow && !wasHurtForReach) {
            int dd = (int) Math.round(d * 100.0);
            if (dd > hitDistMax) hitDistMax = dd;
            hitDistSum += dd;
            hitDistN++;
        }
        wasHurtForReach = hurtNow;
        // STANDING, MEASURED DIRECTLY. Not "the flee did not steer this tick" and not "a search was
        // in flight" -- both are claims about which subsystem owns the tick, and I concluded "the
        // bot is standing" from them twice tonight and withdrew it twice, because the path executor
        // moves the bot without the flee steering. Horizontal speed cannot be argued with: under
        // 0.02 blocks/tick nothing is going anywhere, whoever is nominally driving.
        if (d <= 5.0) {
            Vec3d hv = player.getVelocity();
            if (Math.sqrt(hv.x * hv.x + hv.z * hv.z) < 0.02) {
                stillNearThreatTicks++;
                // WHICH STATE IS HOLDING IT. Three candidates were listed and none guessed at:
                // the executor between legs, a search in flight, or neither (nobody driving).
                // Recorded at the motionless tick itself, so the attribution needs no inference.
                if (TungstenModDataContainer.isExecutorRunning()) stillExecutorTicks++;
                else if (TungstenModDataContainer.PATHFINDER.active.get()) stillSearchTicks++;
                else {
                    stillNobodyTicks++;
                    // SPLIT THE "NOBODY" BUCKET. driveAwayRaw is also gated on !movementOwnsTick,
                    // which is exactly MovementQueue.isRunning() (MixinClientPlayerEntity:92). If a
                    // movement claimed the tick and did not move the bot, that is the gap; if not,
                    // the decline is somewhere else again and the search continues one level down.
                    if (kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()) stillMoveQueueTicks++;
                    // ONE BOOLEAN DECIDES IT. Every gate driveAwayRaw has is open on these ticks
                    // (moveQueue 0, executor 0, search 0), so the keys ought to be down. If they
                    // ARE down and the bot still is not moving, this is collision -- walking into
                    // the victim or geometry. If they are NOT, something clears them after the
                    // drive presses them, and VoidGuard runs immediately after and zeroes movement
                    // keys when it sees an edge. Recorded, not assumed.
                    if (MinecraftClient.getInstance().options.forwardKey.isPressed()
                            || MinecraftClient.getInstance().options.backKey.isPressed()
                            || MinecraftClient.getInstance().options.leftKey.isPressed()
                            || MinecraftClient.getInstance().options.rightKey.isPressed()) {
                        stillKeysDownTicks++;
                        // IS THE BLOCKER THE CHASER? I have been asserting it from "flat arena,
                        // what else is there", which is an inference, and inferences have cost me
                        // five reverts tonight. Two player boxes are 0.6 wide each, so contact is
                        // under ~1.2 blocks centre to centre; 1.5 leaves margin. If this stays
                        // near zero the obstacle is something else entirely and an anti-body-block
                        // remedy would be aimed at nothing.
                        if (d < 1.5) stillTouchingThreatTicks++;
                        // WHERE does it stall? The arena is centred on the origin, so the larger
                        // of |x| and |z| is how far out the bot is. G-1.66 already recorded that
                        // this objective "seeks corners"; if the stalls sit at a large radius the
                        // bot is pressing into the boundary and the subject is the OBJECTIVE, not
                        // the drive. Reported in tenths of a block to keep it an int.
                        int radius = (int) Math.round(Math.max(Math.abs(player.getX()),
                                                               Math.abs(player.getZ())) * 10.0);
                        if (radius > stillMaxRadius) stillMaxRadius = radius;
                        stillRadiusSum += radius;
                    }
                }
            }
            nearThreatTicks++;
        }

        // 3.6, NOT 3.0. Vanilla melee reaches the target's HITBOX, not its centre, and a player
        // box is 0.6 wide -- so a sword lands at a centre-to-centre distance of roughly 3.3-3.6.
        // Measured the error directly: a run reported reachTicks=0 with closest=3.44 and five
        // deaths, which is only possible if the killing band sits above the threshold being used.
        if (d <= 3.6) {
            Vec3d v = player.getVelocity();
            Vec3d away = player.getEntityPos().subtract(threat.getEntityPos());
            if (player.isSprinting()) reachSprintTicks++;
            if ((v.x * away.x + v.z * away.z) > 0) reachAwayTicks++;
            if (BowShooter.isDrawing()) reachDrawingTicks++;
            reachTicks++;
        // 5.0 = the reach band plus roughly one stride: a sprinting player covers ~0.28 blocks a
        // tick, about 1.4 in the half-second it takes to notice and react, and 3.6 + 1.4 is 5.0.
        // The near band means "close enough that one commitment by the chaser puts it in range",
        // so it has to track the reach band; left at 4.5 it would still be calibrated against the
        // 3.0 band it used to sit above. Written down because I changed it in the same edit as the
        // reach fix and gave no reason -- an unexplained constant is what this session kept
        // tripping over, and I have no standing to leave one behind.
        } else if (d <= 5.0) {
            nearTicks++;
            if (BowShooter.isDrawing()) nearDrawingTicks++;
        }
    }

    /** Current gap to the threat, or -1 when there is no live threat to measure against. */
    public static double gapTo(ClientPlayerEntity player) {
        return (active && threat != null && threat.isAlive() && !threat.isRemoved())
                ? player.getEntityPos().distanceTo(threat.getEntityPos()) : -1;
    }

    /** Name of the threat currently tracked (null if not visible). */
    public static String getCurrentThreat() {
        return (threat != null && threat.isAlive() && !threat.isRemoved())
                ? threat.getName().getString() : null;
    }

    // ── tick ─────────────────────────────────────────────────────────────────

    /** Ticks the flee declined at each of its two entry gates -- the missing time, itemised. */
    public static volatile int fleeIdleInactive, fleeIdleNoThreat;

    /**
     * THE DENOMINATOR. Client ticks seen in the same window as every counter beside it.
     *
     * <p>Exists because I compared 538 counted ticks against an ASSUMED 1200 (60 seconds at 20
     * tps) and called the difference a deficit, then built a conclusion about the caller on it.
     * The call site turned out to be unconditional. The player entity does not tick while dead
     * and awaiting respawn, and this course kills the bot four to ten times a run, so the real
     * denominator is not the wall clock -- it has to be counted, not assumed.
     */
    public static volatile int clientTicks;

    /** Ticks with a threat within 5 blocks, and how many of those the bot spent motionless. */
    public static volatile int nearThreatTicks, stillNearThreatTicks;
    /** Of the motionless ticks: executor between legs, search in flight, or nobody driving. */
    public static volatile int stillExecutorTicks, stillSearchTicks, stillNobodyTicks;
    /** Of the "nobody" ticks: how many had a MovementQueue claiming the tick without moving. */
    public static volatile int stillMoveQueueTicks;
    /** Of the nobody-ticks: how many had a movement key actually held down. */
    public static volatile int stillKeysDownTicks;
    /** Of the stalled keys-down ticks: how many had the threat within body contact. */
    public static volatile int stillTouchingThreatTicks;
    /** Distance from arena centre at stalled ticks, in tenths of a block. */
    public static volatile int stillMaxRadius, stillRadiusSum;
    /** Stalled ticks seen after the guard, and how many still had a key held then. */
    public static volatile int stalledSeenAfterGuard, keysDownAfterGuardTicks;
    /** Distance to the threat at the moment each blow landed, in hundredths of a block. */
    public static volatile int hitDistMax, hitDistSum, hitDistN;
    /** Of the flee's driving ticks, how many were spent sprinting. */
    public static volatile int fleeSprintTicks;
    private static boolean wasHurtForReach = false;

    public static void tick(WorldView world, ClientPlayerEntity player) {
        // WHERE THE OTHER TWO THIRDS GO. The flee drives 345 ticks and holds 72 of a 1200-tick
        // course; four fixes to how it DRIVES all left the exposure profile unchanged, because
        // they could only touch 29% of the run. These two counters itemise the rest.
        //
        // The second gate is the one to watch: "threat out of view" idles the flee, and fleeing
        // means turning away from the threat -- which would be self-reinforcing, the bot turning
        // its back, losing sight, stopping, and being caught. Counted rather than assumed.
        clientTicks++;
        if (!active) { fleeIdleInactive++; return; }

        threat = resolve(player);
        if (threat == null) { fleeIdleNoThreat++; return; } // gone / out of view — idle

        double dist = player.getEntityPos().distanceTo(threat.getEntityPos());
        // DO NOT HOLD WHILE THE THREAT IS CLOSING.
        //
        // The hold exists to save effort once the gap is safe, and it stops the bot dead AND
        // cancels the search. Against a pursuer that sprints, "safe" lasts under two seconds: a
        // sprinting player covers ~5.6 blocks a second, so the 9.5 this triggers at is gone before
        // the bot has any reason to move again -- and it restarts from a standstill against
        // something already at full speed.
        //
        // MEASURED on bow_flee: the course's own criterion reported a mean separation of 9.32,
        // which is the signature of parking exactly on this threshold, and it PASSED. Meanwhile 36
        // of the 38 hits taken landed with something inside 4.5 blocks (dw rangedHits=2), and the
        // bot died 10 times in 60s. A good average and a collapsing distance are the same fact
        // here: it holds at the line, the gap is eaten, it is caught flat-footed.
        //
        // So the gap alone is the wrong test. Hold only while the threat is NOT gaining ground.
        // HEADING OR SPEED -- the two ways a gap is lost with sprint available, and they are not
        // the same defect. If the bot is moving AWAY and still caught, it is out-run and the
        // objective needs a bigger margin. If it is caught while moving sideways or toward the
        // threat, the flee DIRECTION is wrong at that instant and no margin will save it.
        // Counted per tick, because the exposure is ~1% of the run and a 1 Hz poll sees none of it.
        boolean closing = lastThreatDist >= 0 && dist < lastThreatDist - 0.01;
        lastThreatDist = dist;
        if (!closing && dist >= keepDistance + 1.5) {
            // far enough — stop pathing, hold position
            fleeHeld++;
            if (TungstenModDataContainer.PATHFINDER.active.get()) {
                TungstenModDataContainer.PATHFINDER.stop.set(true);
            }
            return;
        }

        tickCounter++;
        // SEARCHING IS NOT RUNNING. PathFinder.active is set at the top of find() and cleared when
        // the worker finishes, so it means "a search is in flight" — the bot is STANDING. Counting
        // it as fleeRan hid the cost: one run reported flee=244/894/103 while the executor replayed
        // only 618 ticks, so 276 of those "running" ticks — nearly 14 seconds of a 60-second course
        // — were spent looking for a path rather than following one.
        boolean searching = TungstenModDataContainer.PATHFINDER.active.get();
        boolean executing = TungstenModDataContainer.isExecutorRunning();
        if (searching) fleeSearch++;
        if (executing) fleeRan++;

        // THE CLOCK STAYS, AND IT WAS MEASURED BACK IN. Replanning every RECALC ticks looks
        // wasteful — it tears down a live path twice a second and pays for a fresh search — so it
        // was changed to fire only once nothing was pathing. That made things WORSE, and by the
        // margin the bench can actually see:
        //     clock (this code)    avg_dist 7.32 / 7.10 / 9.43   3 of 3 above the gate
        //     replan-on-need       avg_dist 6.11 / 4.84 / 8.39   1 of 3
        // and it did not even buy what it was for: search ticks stayed at 250-330 per run against
        // 276 before, while plans halved from ~110 to ~45. So the searches simply got longer
        // instead of fewer, and the flee lost the thing the cadence was really providing — a
        // direction that stays fresh while the threat keeps moving. A stale plan followed
        // perfectly is worse than a fresh plan followed in bursts.
        boolean pathing = searching || executing;
        if (!pathing || tickCounter >= RECALC) {
            tickCounter = 0;
            Vec3d flee = safeFleePoint(world, player);
            if (flee != null) {
                fleePlans++;
                TungstenModDataContainer.PATHFINDER.stop.set(true);
                TungstenConfig.get().searchTimeoutMs = 400L;
                TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 1;
                TungstenModDataContainer.PATHFINDER.minDistPath = 0.3;
                TungstenModDataContainer.PATHFINDER.find(world, flee, player);
            }
        }
    }

    /**
     * Best solid, void-safe standable point away from the threat. Scans several
     * away-biased directions AND several distances (so on a bounded island the
     * furthest reachable cell — e.g. the far corner — is picked instead of a
     * fixed point 8 blocks out in the void), then keeps the safe candidate that
     * sits furthest from the threat. Null if nothing safe (cornered — hold, don't
     * flee off the edge).
     */
    private static Vec3d safeFleePoint(WorldView world, ClientPlayerEntity player) {
        Vec3d p = player.getEntityPos();
        Vec3d away = p.subtract(threat.getEntityPos());
        away = new Vec3d(away.x, 0, away.z);
        if (away.lengthSquared() < 1e-4) away = new Vec3d(1, 0, 0);
        away = away.normalize();

        // TWO TIERS, NOT ONE SCORE. "Furthest from the threat" is a corner-seeking objective in any
        // bounded space: the corner IS the furthest point from something approaching the middle, and
        // once standing in it every candidate scores worse, so the flee has nowhere left to go.
        //
        // bow_flee's traces show exactly that, every run. The bot spawns centre, sprints out, and
        // the gap collapses at the boundary of the 40x40 field:
        //     t= 6.5 bot=[-17.7,-17.1]   t=17.5 bot=[5.5, 19.4]   t=28.4 bot=[-18.7,-7.9]
        //     t=40.4 bot=[-5.3, 19.7]    t=56.7 bot=[-8.3, 19.5]
        // min distance ~3 blocks in EVERY run regardless of the mean — it dies at a wall, respawns
        // in the middle, and repeats. Raising the average gap did nothing for that, because the
        // average was never the thing killing it.
        //
        // So a candidate you cannot flee ONWARD from is a dead end, and is taken only when nothing
        // else exists. Expressed as a tier rather than a weighted penalty on purpose: a weight
        // would be a number invented to trade blocks against escape room, and there is no honest
        // exchange rate between them.
        Vec3d best = null, bestDeadEnd = null;
        double bestScore = -1, bestDeadEndScore = -1;
        // THE CANDIDATE SET COULD NOT EXPRESS "RUN ALONG THE RIM". It stopped at +-80 degrees from
        // straight-away, so at a boundary every candidate points outward, every one is a dead end,
        // and the fallback takes the rim itself -- which is exactly where the bot was measured
        // standing: every stall at radius 18.57-18.7 on a platform whose edge is 20, keys held,
        // waiting for the sword.
        //
        // The tiering above was already right; it simply had nothing tangential to choose. +-115
        // and +-145 give it the two moves a cornered runner actually has -- slide along the edge,
        // or cut back past the chaser through open ground -- and both are scored by the same
        // hasRoomBeyond test, so a direction is still only taken if the flee can continue from it.
        double[] angles = {0, 25, -25, 50, -50, 80, -80, 115, -115, 145, -145};
        double[] dists  = {STEP, STEP * 0.66, STEP * 0.4, 2.0};
        for (double a : angles) {
            double rad = Math.toRadians(a);
            double cos = Math.cos(rad), sin = Math.sin(rad);
            double dx = away.x * cos - away.z * sin;
            double dz = away.x * sin + away.z * cos;
            for (double step : dists) {
                Vec3d cand = new Vec3d(p.x + dx * step, p.y, p.z + dz * step);
                Vec3d ground = snapGround(world, cand);
                if (ground == null) continue;
                double score = ground.distanceTo(threat.getEntityPos());
                if (hasRoomBeyond(world, ground, dx, dz)) {
                    if (score > bestScore) { bestScore = score; best = ground; }
                } else if (score > bestDeadEndScore) {
                    bestDeadEndScore = score; bestDeadEnd = ground;
                }
                break; // furthest reachable in this direction wins; stop shrinking
            }
        }
        return best != null ? best : bestDeadEnd;
    }

    /**
     * Walk directly away from the threat on the movement KEYS, without touching the view.
     *
     * <p>Called from the mixin as a FINAL-WORD writer — after every tick owner, under the same
     * {@code !movementOwnsTick} exemption the walker and executor use, and before VoidGuard so the
     * guard can still veto a step off a rim. It must not be called from {@link #tick}: that runs
     * before MovementQueue and BlockPathWalker, which release every key and press their own, so a
     * writer there is silently overwritten (pitfall P1). The first version of this WAS there, read
     * as "no effect" across three runs, and was reverted as a refuted idea when it had never once
     * executed.
     *
     * <p>{@code fleeDriveTicks} exists so that cannot happen twice: a zero there means the fallback
     * is not running, which is a different fact from "the fallback does not help".
     *
     * <p>Directions are expressed in the player's own frame, so this composes with a bow shot that
     * has claimed the view and never produces the instant rotation the aim pipeline avoids. Sprint
     * is requested only when the motion is mostly FORWARD, because vanilla refuses it otherwise —
     * {@code canStartSprinting()} requires {@code input.hasForwardMovement()}.
     */
    public static void driveAwayRaw(WorldView world, ClientPlayerEntity player) {
        if (!active || threat == null || !threat.isAlive() || threat.isRemoved()) return;
        Vec3d away = player.getEntityPos().subtract(threat.getEntityPos());
        away = new Vec3d(away.x, 0, away.z);
        if (away.lengthSquared() < 1e-6) return;
        away = away.normalize();

        // ONLY WHERE THERE IS GROUND TO RUN ONTO. Blind key-driving cost a self-fall on
        // narrow_bridge_duel the first time this shipped — the course whose whole floor is a
        // one-wide walkway over the void. VoidGuard runs after this and did not save it, so the
        // veto is not enough on its own; the honest rule is that standing still beats falling.
        // Same standability test the flee planner already applies to its waypoints, so "somewhere
        // to run" means exactly what it means everywhere else in this file.
        // NO GROUND PROBE HERE, AND THAT WAS MEASURED THREE WAYS. Blind key-driving over a void
        // is an obvious hazard, so this checked standability before pressing anything. Both
        // versions cost more than they protected, blocking ~260-290 ticks per run on FLAT ground
        // where nothing was at risk:
        //     no probe            hits 17   avg_dist 9.02 / 10.75 / 7.02
        //     3 probes (diagonal) hits 22   avg_dist 9.51 / 8.77
        //     1 probe (ahead)     hits 26   avg_dist 6.47 / 6.55
        // And the regression that prompted them was misattributed: narrow_bridge_duel's self-falls
        // were blamed on this fallback before reading flee=0/0/0/0/0/0 — that course is a duel and
        // never runs a flee at all, so this code cannot execute there. The void courses that DO
        // pass through here, bridge_assault and slab_hole, were green without any probe.
        //
        // VoidGuard still runs after this and keeps its veto, which is where void safety belongs.
        fleeDriveTicks++;
        // SPRINT SHARE OVER THE WHOLE FLEE, not just inside the killing band. The in-reach sample
        // is a few dozen ticks and swung 0-14% between runs, too noisy to settle anything. This
        // denominator is every tick the flee actually drives, which is hundreds -- and the question
        // it answers is why a 12-block order collapses to blows landing at 4.25: a sprinting player
        // covers 5.6 blocks a second, so a flee that cannot sprint loses the gap no matter where it
        // points.
        if (player.isSprinting()) fleeSprintTicks++;

        double yaw = Math.toRadians(player.getYaw());
        Vec3d facing = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3d right  = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw));
        double fwd = away.dotProduct(facing);
        double str = away.dotProduct(right);

        var options = MinecraftClient.getInstance().options;
        options.forwardKey.setPressed(fwd > 0.35);
        options.backKey.setPressed(fwd < -0.35);
        options.rightKey.setPressed(str > 0.35);
        options.leftKey.setPressed(str < -0.35);
        options.sprintKey.setPressed(fwd > 0.6);
    }


    /** Can the flee CONTINUE past {@code from} in the same direction? One more standable step is
     *  enough to tell a corner from open ground, and it costs one snapGround call. */
    private static boolean hasRoomBeyond(WorldView world, Vec3d from, double dx, double dz) {
        return snapGround(world, new Vec3d(from.x + dx * STEP, from.y, from.z + dz * STEP)) != null;
    }

    /** Nearest standable INTERIOR cell around {@code pos} (scan a few blocks
     *  up/down); null if none. Interior = solid, 2 air above, and no serious drop
     *  within 2 blocks — fleeing to the very rim let the executor sprint-overshoot
     *  off the edge, so a flee target must keep a safety margin from the void. */
    private static Vec3d snapGround(WorldView world, Vec3d pos) {
        int x = (int) Math.floor(pos.x), z = (int) Math.floor(pos.z), y0 = (int) Math.floor(pos.y);
        for (int dy = 2; dy >= -4; dy--) {
            BlockPos bp = new BlockPos(x, y0 + dy, z);
            boolean solid = !world.getBlockState(bp).getCollisionShape(world, bp).isEmpty();
            boolean airAbove = world.getBlockState(bp.up()).getCollisionShape(world, bp.up()).isEmpty()
                    && world.getBlockState(bp.up(2)).getCollisionShape(world, bp.up(2)).isEmpty();
            if (solid && airAbove) {
                Vec3d stand = new Vec3d(x + 0.5, bp.getY() + 1.0, z + 0.5);
                boolean safe = VoidDetector.fallHeight(stand, world) <= 3
                        && !VoidDetector.voidWithin(stand, world, 2, 3);
                return safe ? stand : null;
            }
        }
        return null;
    }

    private static PlayerEntity resolve(ClientPlayerEntity player) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || threatName == null) return null;
        if (threat != null && !threat.isRemoved() && threat.isAlive()
                && mc.world.getEntityById(threat.getId()) == threat) {
            return threat;
        }
        for (PlayerEntity pl : mc.world.getPlayers()) {
            if (pl != player && pl.getName().getString().equalsIgnoreCase(threatName)) return pl;
        }
        return null;
    }

    private static void releaseKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }
}
