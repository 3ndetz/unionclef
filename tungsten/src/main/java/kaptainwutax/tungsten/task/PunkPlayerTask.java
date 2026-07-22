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

    // ── public API ───────────────────────────────────────────────────────────

    /** Hunt one specific player by name (explicit target overrides allow/avoid). */
    public static void start(String name) {
        stop();
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

        tryRediscover();
        if (targetEntity == null || targetEntity.isRemoved() || !targetEntity.isAlive()) {
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
        if (targetDrop > 3.0 && targetFall > 6) {
            if (mode == Mode.COMBAT) combat.releaseKeys();
            if (FollowEntityTask.isActive()) FollowEntityTask.stop();
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
        } else if (mode == Mode.COMBAT && (dist > APPROACH_RESUME
                || (CombatController.triggerBot.hasNoProgress(100)
                    && CombatController.safety.getStage() != kaptainwutax.tungsten.combat.CombatStage.ESCAPE))) {
            // too far OR no hits for 5 sec → re-approach with A* pathfinding
            enterApproach();
        }

        // ── execute ──────────────────────────────────────────────────────
        if (mode == Mode.COMBAT) {
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
