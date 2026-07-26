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

    private static final double COMBAT_RANGE   = 4.5; // MC entity reach = 3.0; enter combat early
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

    /** Jam detection: ticks of zero horizontal progress before we route around. */
    private static final int JAM_TICKS = 30;   // 1.5s
    private static net.minecraft.util.math.Vec3d jamAnchor = null;
    private static int jamTicks = 0;
    /** Where we last jammed + how many times in a row nearby (widens the ban). */
    private static net.minecraft.util.math.BlockPos lastJamPos = null;
    private static int jamRepeats = 0;

    // ── public API ───────────────────────────────────────────────────────────

    /** Hunt one specific player by name (explicit target overrides allow/avoid). */
    public static void start(String name) {
        stop();
        RunAwayTask.stop();   // can't hunt and flee at once
        targetName = name;
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

    public static void tick(WorldView world, ClientPlayerEntity player) {
        if (!active) return;

        // ── Universal edge-protection while punking ───────────────────────
        // If we're on the ground and our HORIZONTAL VELOCITY points at a serious
        // drop, hold sneak. Vanilla sneak won't step off a ledge, so no combat
        // maneuver, post-kill coast, or knockback carry can slide us into the
        // void — this covers the seams between combat/approach/disengage where
        // per-frame key logic isn't running. Skipped while the void-aware
        // pathfinder executor drives (it may descend on purpose).
        if (!TungstenModDataContainer.isExecutorRunning()) {
            double vx = player.getVelocity().x, vz = player.getVelocity().z;
            double look = Math.max(1.4, Math.sqrt(vx * vx + vz * vz) * 10.0);
            if (player.isOnGround() && (vx * vx + vz * vz) > 0.0016
                    && kaptainwutax.tungsten.combat.VoidDetector.edgeAhead(
                            player.getEntityPos(), vx, vz, world, 3, look)) {
                MinecraftClient.getInstance().options.sneakKey.setPressed(true);
            }
        }

        tryRediscover();
        if (targetEntity == null || targetEntity.isRemoved() || !targetEntity.isAlive()) {
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

        // JAM WATCHDOG (punk owns the chase, so it must live here — a copy in
        // FollowEntityTask never fired because in COMBAT/APPROACH this task drives
        // and that tick path is not reached). Judge by the only thing that
        // matters: horizontal displacement. Bouncing on a ledge with X/Z frozen is
        // a jam no matter which component claims to be running.
        net.minecraft.util.math.Vec3d posNow = player.getEntityPos();
        if (jamAnchor == null
                || Math.hypot(posNow.x - jamAnchor.x, posNow.z - jamAnchor.z) > 0.5) {
            jamAnchor = posNow;
            jamTicks = 0;
        } else if (++jamTicks >= JAM_TICKS) {
            jamTicks = 0;
            jamAnchor = null;
            // Blacklist the cell we are FAILING TO REACH, never the one we stand
            // in: banning our own footing removes the search root, every plan
            // becomes impossible and all engines go quiet (measured — the bot
            // stood with pf/blk/exec/walk all false right after the ban).
            net.minecraft.util.math.BlockPos badTarget = BlockPathWalker.getCurrentWaypoint();
            if (badTarget != null && !badTarget.equals(player.getBlockPos())) {
                kaptainwutax.tungsten.path.fast.FastPlanner.blockCell(badTarget);
            }
            // Getting stuck AGAIN near the same spot means the whole pocket is a
            // dead end, not one bad cell — widen the ban so the plan has to leave
            // it instead of offering the next-door cell and circling.
            net.minecraft.util.math.BlockPos me = player.getBlockPos();
            if (lastJamPos != null && me.getSquaredDistance(lastJamPos) < 36) {
                jamRepeats++;
                kaptainwutax.tungsten.path.fast.FastPlanner.blockArea(
                        me, Math.min(1 + jamRepeats, 4));
            } else {
                jamRepeats = 0;
            }
            lastJamPos = me;
            Debug.logMessage("Punk jammed at " + player.getBlockPos().toShortString()
                    + " -> avoid " + (badTarget == null ? "?" : badTarget.toShortString()));
            BlockPathWalker.stop();
            TungstenModDataContainer.PATHFINDER.overrideStartPos = null;
            TungstenModDataContainer.PATHFINDER.stop.set(true);
            FollowEntityTask.stop();
            mode = Mode.APPROACH;
            // re-approach immediately: a ban with no re-plan is just a quieter stall
            FollowEntityTask.start(targetEntity, 1.0);
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
        } else if (mode == Mode.COMBAT && (dist > APPROACH_RESUME
                || (CombatController.triggerBot.hasNoProgress(100)
                    && CombatController.safety.getStage() != kaptainwutax.tungsten.combat.CombatStage.ESCAPE))) {
            // too far OR no hits for 5 sec → re-approach with A* pathfinding
            enterApproach();
        }

        // ── execute ──────────────────────────────────────────────────────
        if (mode == Mode.COMBAT) {
            // Never punch with the wrong item: the engine swings whatever is HELD, so a
            // bot that had just used a bow kept "fighting" with it (2 dmg/hit) while a
            // sword sat in the hotbar and it lost the fight (user 2026-07-24).
            kaptainwutax.tungsten.combat.WeaponSelector.equipBestMelee(player);
            combat.tick(player, targetEntity, world);
        } else if (!FollowEntityTask.isActive()) {
            // APPROACH but follow isn't running (e.g. we just resumed after a
            // void-wait) — (re)start it so the bot actually walks to the target.
            FollowEntityTask.start(targetEntity, 1.0);
        }
        // APPROACH is otherwise driven by FollowEntityTask (ticked in the mixin)
    }

    // ── mode transitions ─────────────────────────────────────────────────────

    private static void enterCombat() {
        mode = Mode.COMBAT;
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
