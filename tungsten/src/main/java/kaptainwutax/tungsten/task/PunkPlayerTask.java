package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.combat.CombatController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.WorldView;

/**
 * PvP task: hunt a player by name.
 *
 * Modes:
 *   APPROACH — far away: use tungsten A* (FollowEntityTask) to close distance
 *   COMBAT   — close range + LOS: hand off to CombatController
 *
 * Called every game tick from MixinClientPlayerEntity.
 */
public class PunkPlayerTask {

    // ENTER COMBAT AT THE DISTANCE COMBAT CAN ACTUALLY WORK AT, not a block and a half outside it.
    //
    // This was 4.5 ("enter combat early"), while MC entity reach is 3.0. Between 3.0 and 4.5 the
    // bot is in COMBAT mode, which drives with RAW FORWARD KEYS — no pathing — straight at an
    // opponent that is circle-strafing. Measured over 404 combat ticks: inReach=95 (23%),
    // lastDist=5.05 mid-fight while the controller's own target spacing said 2.9, forward pressed
    // 177 times against 74 wanted, and dirBlockedFwd=0. It wants to close, nothing blocks it, and
    // walking in a straight line at a moving target does not close the gap.
    //
    // APPROACH mode paths (PATHFINDER.find) and can cut the corner. Keeping it until 3.4 means the
    // pathfinder owns the chase and the controller only owns the last stride. APPROACH_RESUME
    // stays at 6.0, so the hysteresis band widens rather than flipping mode every tick.
    private static final double COMBAT_RANGE   = 3.4; // MC entity reach = 3.0
    private static final double APPROACH_RESUME = 6.0;

    private enum Mode { APPROACH, COMBAT }

    // ── state ────────────────────────────────────────────────────────────────
    private static String  targetName   = null;   // fixed single-target name (or null)
    private static Entity  targetEntity = null;
    private static boolean active       = false;
    private static Mode    mode         = Mode.APPROACH;
    private static boolean anyMode      = false;   // hunt nearest ALLOWED target

    // multi-target policy (the brain decides WHO; tungsten executes). Lowercased.
    private static final java.util.Set<String> allowTargets = new java.util.HashSet<>(); // empty = any player
    private static final java.util.Set<String> avoidTargets = new java.util.HashSet<>();

    private static final CombatController combat = new CombatController();


    // ── public API ───────────────────────────────────────────────────────────

    /** Hunt one specific player by name (explicit target overrides allow/avoid). */
    /**
     * ZERO THE INSTRUMENTS AT THE START OF A RUN. Every counter here and in the walker is a
     * plain static that nothing reset, so it accumulated over the CONTAINER's lifetime — dozens
     * of runs — while being read as if it described one chase. That produced two withdrawn
     * conclusions in a single session ("the walker is off 80% of a pursuit", "punk is inactive
     * 89% of a chase"), both arithmetic on hours of idle time between runs.
     *
     * <p>A counter is only a measurement if you know its zero. A chase begins here, so this is
     * the zero.
     */
    public static void resetCounters() {
        pCalled = pInactive = pNoTarget = pLastKnown = pArmedEarly = 0;
        pRestartKept = 0;
        kaptainwutax.tungsten.task.BlockPathWalker.tickOff = 0;
        kaptainwutax.tungsten.task.BlockPathWalker.tickBfs = 0;
        kaptainwutax.tungsten.task.BlockPathWalker.tickDir = 0;
        FollowEntityTask.followTicks = FollowEntityTask.steerTicks = 0;
        FollowEntityTask.leapTicks = FollowEntityTask.cooldownTicks = FollowEntityTask.losBlocked = 0;
        FollowEntityTask.tickCalled = FollowEntityTask.tickInactive = FollowEntityTask.tickActive = 0;
    }

    /** Re-issues of punk that were answered by keeping the fight running; 0 with the flag off. */
    public static volatile int pRestartKept = 0;

    /**
     * Start punking, or -- if we are already punking exactly this target -- carry on.
     *
     * <p>⛔ WHY THE EARLY RETURN EXISTS. start() calls stop(), zeroes the counters and puts the
     * mode back to APPROACH, so re-issuing punk on a target we are already fighting throws away
     * the combat state and re-enters the approach mid-trade. A driver that re-issues on every
     * melee entry therefore pays that a dozen times a fight.
     *
     * <p>That is the last ASYMMETRY left on allround, and it matters because everything the mod
     * does symmetrically cancels there: the opponent runs this same engine, so a change that
     * helps both fighters cannot move the margin. Three combat-policy changes measured neutral
     * for exactly that reason. The opponent is given punk ONCE and keeps it for the whole run;
     * the bot's driver re-issues it on every melee entry.
     */
    public static void start(String name) {
        if (kaptainwutax.tungsten.TungstenConfig.get().punkRestartKeepsFight
                && active && !anyMode && name != null && name.equals(targetName)) {
            pRestartKept++;
            return;
        }
        stop();
        RunAwayTask.stop();   // can't hunt and flee at once
        targetName = name;
        resetCounters();
        active = true;
        mode = Mode.APPROACH;
        Debug.logMessage("Punking player: " + name);
    }

    /** Hunt the NEAREST acceptable player: allow = candidate names (empty = any),
     *  avoid = never-hit names. The agent's multi-target / avoid-target lever —
     *  it decides the sets, the mod picks the closest valid one and re-targets
     *  automatically as the fight evolves. */
    public static void startAny(java.util.List<String> allow, java.util.List<String> avoid) {
        stop();
        RunAwayTask.stop();   // can't hunt and flee at once
        setTargets(allow);
        setAvoid(avoid);
        anyMode = true;
        resetCounters();
        active = true;
        mode = Mode.APPROACH;
        Debug.logMessage("Punk ANY allow=" + allowTargets + " avoid=" + avoidTargets);
    }

    public static void setTargets(java.util.List<String> allow) {
        allowTargets.clear();
        if (allow != null) for (String s : allow) if (s != null) allowTargets.add(s.toLowerCase());
    }

    public static void setAvoid(java.util.List<String> avoid) {
        avoidTargets.clear();
        if (avoid != null) for (String s : avoid) if (s != null) avoidTargets.add(s.toLowerCase());
    }

    public static void stop() {
        // WHO STOPPED THE CHASE? It stops itself about a third of the way through
        // chase_terrain — 1183 active ticks of a 180 s course — and six passes were spent
        // measuring the consequences (walker idle, tickBFS silent, planning interval) before
        // asking this. `active` is cleared only here, so the caller IS the answer.
        if (active && kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging) {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder who = new StringBuilder("PUNKSTOP by");
            for (int i = 2; i < Math.min(st.length, 6); i++) {
                who.append(' ').append(st[i].getClassName()
                        .substring(st[i].getClassName().lastIndexOf('.') + 1))
                   .append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
            }
            kaptainwutax.tungsten.Debug.logMessage(who.toString());
        }
        if (active) {
            combat.releaseKeys();
            FollowEntityTask.stop();
        }
        active       = false;
        anyMode      = false;
        targetName   = null;
        targetEntity = null;
        mode         = Mode.APPROACH;
        allowTargets.clear();
        avoidTargets.clear();
    }

    public static boolean isActive()      { return active; }
    public static String  getTargetName() { return targetName; }

    /** Name of the player currently being fought (null if none acquired). */
    public static String getCurrentTarget() {
        if (targetEntity instanceof PlayerEntity && targetEntity.isAlive() && !targetEntity.isRemoved())
            return ((PlayerEntity) targetEntity).getName().getString();
        return null;
    }

    /** May we engage this entity under the current allow/avoid policy? */
    private static boolean isAcceptable(Entity e) {
        if (!(e instanceof PlayerEntity) || !e.isAlive() || e.isRemoved()) return false;
        String n = ((PlayerEntity) e).getName().getString().toLowerCase();
        if (avoidTargets.contains(n)) return false;
        if (!allowTargets.isEmpty() && !allowTargets.contains(n)) return false;
        return true;
    }

    // ── tick ─────────────────────────────────────────────────────────────────

    // Branch counters, read over py4j. Two earlier measurements disagreed about where the
    // bot spends its time — the chase says it is fighting, combat says it is not — and only
    // counting each branch can settle it.
    public static volatile int pLastKnown = 0;
    public static volatile int pCalled = 0, pInactive = 0, pNoTarget = 0,
            pVoidHold = 0, pCombat = 0, pApproach = 0;
    /** Edge guard: ticks it held sneak, and ticks we were airborne over a bottomless column. */
    public static volatile int pEdgeSneak = 0, pEdgeAir = 0;
    /** Ticks the executor was driving — where the guard used to switch itself off entirely. */
    public static volatile int pEdgeSkipExec = 0;

    /** Weapon switches made on the APPROACH rather than on contact; 0 with the flag off. */
    public static volatile int pArmedEarly = 0;

    /** Close enough that the fight is about to start, so the sword should already be out. */
    private static final double APPROACH_ARM_RANGE_SQ = 8.0 * 8.0;

    public static void tick(WorldView world, ClientPlayerEntity player) {
        pCalled++;
        if (!active) { pInactive++; return; }

        // ── Universal edge-protection while punking ───────────────────────
        // If we're on the ground and our HORIZONTAL VELOCITY points at a serious
        // drop, hold sneak. Vanilla sneak won't step off a ledge, so no combat
        // maneuver, post-kill coast, or knockback carry can slide us into the
        // void — this covers the seams between combat/approach/disengage where
        // per-frame key logic isn't running. Skipped while the void-aware
        // pathfinder executor drives (it may descend on purpose).
        // WHICH OF THE TWO BLIND SPOTS ACTUALLY DROPS US — COUNT, DO NOT GUESS.
        // allround loses its only non-frame-noise gate to `fell out of the world`, thirteen times a
        // run, and the OPPONENT falls too (server log, 2026-08-09). This guard covers exactly one
        // case: on the ground, already moving at a drop. It cannot cover two others, and both are
        // live in a duel — (a) AIRBORNE, because the crit hop leaves the ground on purpose and sneak
        // holds nothing mid-air (that run: crits=11), and (b) KNOCKBACK, which sneak never resisted.
        // pEdgeSneak counts the guard firing, pEdgeAir counts being over/near an edge with no ground
        // under us. If the falls track pEdgeAir the fix is the hop's take-off test; if they track
        // neither, it is knockback and the fix is not fighting at the rim at all.
        //
        // ⛔ MEASURED, AND IT REFUTED BOTH OF THOSE — THE GUARD BARELY RUNS AT ALL.
        // Two runs on the deployed jar, counters now per-run (see resetRunCounters):
        //     deaths=11  edgeSneak=1  edgeAir=2   called=296  inactive=143  combat=415
        //     deaths=13  edgeSneak=1  edgeAir=4   called=133  inactive=122  combat=633
        // Neither counter tracks the deaths, so it is neither the crit hop nor a mis-firing guard.
        //
        // ⛔ AND THE FIRST EXPLANATION I DREW FROM THEM WAS WRONG. I read `called - inactive` at
        // judge time as "the guard got eleven opportunities against thirteen deaths" and called the
        // tick suppressed to ~1.1/s. Wrong the ordinary way — I did not know the counter's zero.
        // Sampling `called` live DURING a fight, four reads ten seconds apart:
        //     271 -> 157 -> 368 -> 561        (inactive 122 -> 0 -> 0 -> 130)
        // The drop is the suite starting its retry attempt ("running it once more before believing
        // it"), which resets at run_suite.py:216. Between resets: 157 -> 561 in 20 s = 20.2 calls/s.
        // THE TICK IS HEALTHY AT FULL CLIENT RATE while fighting. So the guard is NOT starved, and
        // any conclusion drawn from `called - inactive` read at judge time — including the eleven —
        // rests on a counter whose window I had not established. edgeSneak/edgeAir read at judge
        // time are from that same unreliable window and must be re-measured LIVE, mid-fight, before
        // anyone reasons from them.
        //
        // THE LEAD THAT IS LEFT: edgeAhead NEVER FIRES ON THIS ARENA.
        // Read live mid-fight, both counters ZERO at both ends of a 25 s window in which `called`
        // climbed into the hundreds and the bot falls roughly every nine seconds:
        //     edgeSneak 0 -> 0     edgeAir 0 -> 0     voidHold 0 -> 0
        // (Caveat, stated because it bit me twice today: a reset landed inside that window —
        // `inactive` went 158 -> 0 — so the DELTAS there are worthless. What survives is that both
        // counters read zero AFTER hundreds of active fighting ticks, which no reset can manufacture.)
        // So the guard is not losing; it is never triggered.
        //
        // AND PART OF THAT ZERO IS MY OWN INSTRUMENT, SAID PLAINLY. VoidDetector.edgeAhead opens
        // with `if (fallHeight(pos, world) > maxSafeFall) return false;` (VoidDetector.java:93) —
        // "already over a drop, this guard does not apply". The moment the bot is past the rim that
        // is exactly true of it, so edgeAhead returns false for the whole fall. edgeAir, which I
        // added as `!onGround && nearEdge`, therefore CANNOT count a real fall by construction: the
        // one state it was meant to catch is the one the detector opts out of. Its zero is evidence
        // about my counter, not only about the bot. Fix the counter before reading it again — sample
        // fallHeight directly rather than going through edgeAhead.
        //
        // The ground case is still open and still worth the pass: standing ON the platform,
        // fallHeight reads 1 and a cell beyond the rim reads MAX_SCAN_DEPTH, so edgeAhead should
        // fire while the bot walks at the rim. It read zero across hundreds of fighting ticks. Check
        // the velocity gate (`(vx*vx+vz*vz) > 0.0016` plus the isOnGround requirement) against a bot
        // that is being knocked around rather than driving itself — knockback moves you without your
        // own velocity pointing anywhere useful.
        //
        // CHECKED AND UNSUPPORTED: an exception upstream in the same @Inject. DamageWatch.tick,
        // FollowEntityTask.tick and FollowPlayerTask.tick all run ahead of this one
        // (MixinClientPlayerEntity:70-72) and a throw in any of them would eat the rest of the
        // injection — the right shape, since all three are busy exactly when a fight is on. But the
        // client log across the whole run carries no tick exception at all, only startup noise
        // (audio device, realms auth, options.txt permissions). Not proven impossible, but nothing
        // supports it; do not spend the next pass here without new evidence.
        //
        // STILL OPEN, and it is the question worth taking: what drops this tick from 20/s to ~1.1/s
        // for the duration of a fight. Note the two runs differed 2.2x (called=296 vs 133) on the
        // same course, so whatever it is, it is not a constant.
        // Do NOT re-open the aim for this; the aim numbers on this course are frame-rate noise below
        // the 14 fps floor (see the course file).
        // ── THE FIX: the guard used to switch OFF entirely whenever the executor drove ──────
        // `if (!isExecutorRunning())` was the whole condition, on the grounds that the executor is
        // void-aware and "may descend on purpose". In a duel the executor is what CHASES, so the
        // protection was disabled for most of every fight — which is when the bot is being pushed
        // around near a rim and least able to look after itself.
        //
        // Removing the exclusion outright would be wrong: descending IS legitimate on real terrain
        // (chase_terrain, slab_hole, the nav courses all path downhill deliberately). So keep the
        // exclusion for ordinary drops and override it only for a BOTTOMLESS one, using this file's
        // own established discriminator — the same "a genuine void is bottomless; 20 blocks of
        // nothing is a safe discriminator" that the target-hold below is built on. A legitimate
        // descent is a few blocks; nothing legitimate walks into twenty blocks of air.
        // ⛔ AND THE FIX BELOW WAS THEN MEASURED AND DOES NOT WORK. Like-for-like re-run of
        // allround (--no-early-stop, as the baseline was): deaths=10 at 7.0 fps, against 11 and 13
        // before it. Inside the noise, on a different frame rate, gate still FAIL. Do not record
        // this change as an improvement to anything; it stays only because it is correct in its own
        // right. The suite score did NOT move.
        //
        // ⛔ MEASURED AFTERWARDS: edgeSkipExec = 0. THE PREMISE ABOVE WAS WRONG.
        // The counter was added precisely to check it, and it says the executor was NEVER driving on
        // a punk-active tick, so the guard was not being disabled that way at all. The override
        // below is harmless and principled, but it does NOT explain the falls. Do not credit it.
        //
        // WHAT THE SAME SAMPLING DID SHOW, tracking both guards every 15 s through a run:
        //     t+0..60   punkActive=0    vgCalls=0     <- SEVENTY-FIVE SECONDS WITH NO GUARD AT ALL
        //     t+75      punkActive=173  vgCalls=185
        //     t+90..120 punkActive=272  vgCalls=272   sneak=1  vgEdge=1
        // Two things, both bigger than this branch. (1) For the whole ranged phase neither guard
        // runs: this one returns at `if (!active)` above, and VoidGuard's call site requires
        // RunAwayTask or PunkPlayerTask active (MixinClientPlayerEntity:171). That is the phase the
        // bot spends kiting and backing up. (2) Once punk IS active both guards run every tick and
        // between them they see the rim ONCE in 272 ticks — two independent implementations
        // agreeing, which puts the fault in VoidDetector.edgeAhead rather than in either call site.
        boolean execDriving = TungstenModDataContainer.isExecutorRunning();
        if (execDriving) pEdgeSkipExec++;   // counts how often the old code switched itself off
        {
            double vx = player.getVelocity().x, vz = player.getVelocity().z;
            double look = Math.max(1.4, Math.sqrt(vx * vx + vz * vz) * 10.0);
            // While the executor drives, only a bottomless column overrides it.
            int guardFall = execDriving ? 20 : 3;
            boolean nearEdge = kaptainwutax.tungsten.combat.VoidDetector.edgeAhead(
                    player.getEntityPos(), vx, vz, world, guardFall, look);
            if (player.isOnGround() && (vx * vx + vz * vz) > 0.0016 && nearEdge) {
                pEdgeSneak++;
                MinecraftClient.getInstance().options.sneakKey.setPressed(true);
            }
            // pEdgeAir used to be read through edgeAhead, which opens with "already over a drop ->
            // not my problem" (VoidDetector.java:93) — so it could never see the fall it was added
            // to catch. Ask the world directly instead.
            if (!player.isOnGround()
                    && kaptainwutax.tungsten.combat.VoidDetector.fallHeight(
                            player.getEntityPos(), world) > 20) {
                pEdgeAir++;
            }
        }

        tryRediscover();
        if (targetEntity == null || targetEntity.isRemoved() || !targetEntity.isAlive()) {
            pNoTarget++;
            // A LOST TARGET IS NOT A REASON TO STAND STILL — AND THE ANSWER ALREADY EXISTS.
            // A target only lives while the SERVER sends it: tryRediscover reads the client's
            // entity list, and this server tracks entities for view-distance=8, i.e. 128 blocks,
            // while the chase bench drives the runner 140 blocks out. So it drops out of view by
            // design. Measured: noTarget for 966 ticks, about 27% of chase_terrain.
            //
            // FollowEntityTask already remembers where it last saw the target and falls back to
            // that position (FollowEntityTask.java:173, `targetPos = lastKnownPos`). Killing the
            // drive keys and returning here is what prevented that from ever running. So: while
            // the follow task is still active, let it keep going to the remembered spot —
            // exactly what a person does — and only release the keys when there is nothing to
            // go to. Reuse, not a second implementation of the same idea.
            if (FollowEntityTask.isActive() && !anyMode) {
                pLastKnown++;
                return;
            }
            // No valid target — most often the instant we KILLED it. The combat
            // render frame stops refreshing keys (its target is gone), so a stale
            // forward-toward-the-enemy press would coast us off the rim before we
            // re-acquire. Release the drive keys now; the tick guard above keeps
            // sneak on if we're still sliding toward a drop.
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.options.forwardKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            return;
        }

        // ── Target fell off the map (knocked into the void / over a big drop) ──
        // Do NOT chase it down: following an enemy over the edge is exactly how
        // the bot ends up in the void too, and A* toward a void cell just spams
        // "Ran out of nodes". Hold position — immediate-respawn (and most maps)
        // put the target back up top within a second, and we re-engage then.
        double targetDrop = player.getY() - targetEntity.getEntityPos().y;
        int targetFall = kaptainwutax.tungsten.combat.VoidDetector.fallHeight(
                targetEntity.getEntityPos(), world);
        // NB the fall bound must mean "there is no ground down there at all", not
        // merely "it is a way down". On generated terrain a fleeing target running
        // downhill is routinely 3+ blocks below with a 6-block drop under it, and
        // the bedwars-era numbers made the bot sneak-freeze on a hillside and let
        // the prey escape (stand-measured: 111 s of standing still). A genuine void
        // is bottomless; 20 blocks of nothing is a safe discriminator.
        if (targetDrop > 3.0 && targetFall > 20) {
            pVoidHold++;
            if (mode == Mode.COMBAT) combat.releaseKeys();
            if (FollowEntityTask.isActive()) FollowEntityTask.stop();
            // Hold with SNEAK: vanilla sneak refuses to walk off a block edge, so
            // our residual momentum from the knockback kill can't coast us into
            // the void while we wait for the target to respawn. releaseKeys()
            // just cleared sneak — re-assert it (and kill any forward carry).
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(true);
            mode = Mode.APPROACH;
            return;
        }

        double dist = player.getEntityPos().distanceTo(targetEntity.getEntityPos());
        // raycast to body center — the feet point clips terrain right at the
        // target's feet and spuriously blocked combat entry on flat ground
        boolean hasLOS = FollowEntityTask.hasLineOfSight(player,
                targetEntity.getEntityPos().add(0, targetEntity.getHeight() * 0.5, 0));

        // ── mode switching ───────────────────────────────────────────────
        if (mode == Mode.APPROACH && dist < COMBAT_RANGE && hasLOS) {
            enterCombat();
        // NO HITS YET IS NOT THE SAME AS STUCK. The no-progress rule exists to break out of a
        // chase that has gone nowhere, but it fired on the far more common case of closing the
        // last stride: COMBAT_RANGE is 4.5 and the swing needs 3.0, so the bot enters combat
        // still out of reach and, five seconds later, is dropped back into APPROACH before it
        // can land anything. Measured on melee_basic: six mode flips in one run, "Following"
        // and "Follow stopped" six times each, zero swings, and the distance drifting OUT from
        // 5.14 to 6.21. Only re-approach on no progress when we are genuinely not in striking
        // range; inside it the answer is to keep fighting, not to restart the chase.
        } else if (mode == Mode.COMBAT && (dist > APPROACH_RESUME
                || (dist > kaptainwutax.tungsten.combat.TriggerBot.REACH
                    && CombatController.triggerBot.hasNoProgress(100)
                    && CombatController.safety.getStage() != kaptainwutax.tungsten.combat.CombatStage.ESCAPE))) {
            // too far OR no hits for 5 sec → re-approach with A* pathfinding
            enterApproach();
        }

        // ⛔ DRAW THE SWORD ON THE WAY IN, NOT ON ARRIVAL. equipBestMelee is called only in
        // COMBAT below -- the same tick the bot starts swinging -- so the switch and the first
        // blow land together and the SERVER, which is a packet behind, resolves that blow with
        // whatever it still holds. Measured over rcon during a fight: `data get entity <bot>
        // SelectedItem` says BOW on 2-4 of every 7 samples, and a bow swung as a club deals
        // exactly the 1.0 that ~20 hits a run land for.
        //
        // Gating the SWING on the switch (holdSwingTicksAfterSwitch) removes those 1.0s
        // completely -- 20.3 to 1.0 -- and buys nothing: they come back as partials while flat
        // blows fall, damage 250.2 against 252.2. Delay is not the answer; being armed before
        // contact is, because then no swing is ever near a switch.
        if (kaptainwutax.tungsten.TungstenConfig.get().equipMeleeOnApproach
                && mode != Mode.COMBAT && targetEntity != null
                && player.squaredDistanceTo(targetEntity) <= APPROACH_ARM_RANGE_SQ) {
            if (kaptainwutax.tungsten.combat.WeaponSelector.equipBestMelee(player)) pArmedEarly++;
        }

        // ── execute ──────────────────────────────────────────────────────
        if (mode == Mode.COMBAT) {
            pCombat++;
            // Never punch with the wrong item: the engine swings whatever is HELD, so a
            // bot that had just used a bow kept "fighting" with it (2 dmg/hit) while a
            // sword sat in the hotbar and it lost the fight (user 2026-07-24).
            kaptainwutax.tungsten.combat.WeaponSelector.equipBestMelee(player);
            combat.tick(player, targetEntity, world);
        } else if (!FollowEntityTask.isActive()) {
            pApproach++;
            // APPROACH but follow isn't running (e.g. we just resumed after a
            // void-wait) — (re)start it so the bot actually walks to the target.
            FollowEntityTask.start(targetEntity, 1.0);
        }
        // APPROACH is otherwise driven by FollowEntityTask (ticked in the mixin)
    }

    // ── mode transitions ─────────────────────────────────────────────────────

    private static void enterCombat() {
        mode = Mode.COMBAT;
        // Combat is starting: look at the hand NOW rather than up to a second from now. The
        // selector's 20-tick cadence is deliberate (a slot switch resets the attack cooldown), but
        // carrying a stale count into the one moment the hand is most likely to be wrong is how
        // about one swing in five went out with a bow -- measured weaponMean 59.21 against the
        // iron_sword's 75.0 on allround, where every respawn puts the bow back in hand.
        kaptainwutax.tungsten.combat.WeaponSelector.forceRecheck();
        TungstenModDataContainer.PATHFINDER.stop.set(true);
        if (TungstenModDataContainer.EXECUTOR != null) TungstenModDataContainer.EXECUTOR.stop = true;
        FollowEntityTask.stop();
        Debug.logMessage("PUNK: combat mode");
    }

    private static void enterApproach() {
        mode = Mode.APPROACH;
        combat.releaseKeys();
        FollowEntityTask.start(targetEntity, 1.0);
        Debug.logMessage("PUNK: approach mode (A*)");
    }

    // ── target discovery ─────────────────────────────────────────────────────

    private static void tryRediscover() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;

        // keep the current target if it's still valid under the policy
        if (targetEntity != null && !targetEntity.isRemoved()
                && mc.world.getEntityById(targetEntity.getId()) == targetEntity
                && (anyMode ? isAcceptable(targetEntity) : targetEntity.isAlive())) {
            return;
        }

        if (anyMode) {
            // pick the NEAREST acceptable player (allow/avoid policy)
            PlayerEntity self = mc.player;
            PlayerEntity best = null;
            double bestD = Double.MAX_VALUE;
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == self || !isAcceptable(p)) continue;
                double d = self.getEntityPos().distanceTo(p.getEntityPos());
                if (d < bestD) { bestD = d; best = p; }
            }
            targetEntity = best;
            if (targetEntity != null && mode == Mode.APPROACH) FollowEntityTask.start(targetEntity, 1.0);
            return;
        }

        // fixed single-name mode
        if (targetName == null) { targetEntity = null; return; }
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.getName().getString().equalsIgnoreCase(targetName)) {
                targetEntity = p;
                if (mode == Mode.APPROACH) FollowEntityTask.start(targetEntity, 1.0);
                return;
            }
        }
        targetEntity = null;
    }
}
