package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.combat.CombatController;
import kaptainwutax.tungsten.combat.VoidDetector;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * PvP combat task: hunt and fight a player using tungsten A* (far) + MPC (close).
 *
 * Modes:
 *   APPROACH (> 6 blocks): tungsten A* pathfinding via FollowEntityTask
 *   COMBAT   (< 6 blocks + LOS + solid ground): MPC combat controller
 *   EMERGENCY: MPC override if knocked toward void (regardless of distance)
 */
public class PunkPlayerTask {

    private static final double COMBAT_RANGE = 6.0;
    private static final double APPROACH_RESUME = 8.0; // hysteresis: go back to A* at 8+

    private enum Mode { APPROACH, COMBAT }

    // ── state ─────────────────────────────────────────────────────────────────
    private static String  targetName   = null;
    private static Entity  targetEntity = null;
    private static boolean active       = false;
    private static Mode    mode         = Mode.APPROACH;

    private static final CombatController combat = new CombatController();

    // ── public API ────────────────────────────────────────────────────────────

    public static void start(String name) {
        stop(); // clean previous state
        targetName = name;
        active = true;
        mode = Mode.APPROACH;
        Debug.logMessage("Punking player: " + name);
    }

    public static void stop() {
        if (active) {
            combat.releaseKeys();
            // stop any A* that was running for approach
            FollowEntityTask.stop();
        }
        active       = false;
        targetName   = null;
        targetEntity = null;
        mode         = Mode.APPROACH;
    }

    public static boolean isActive()      { return active; }
    public static String  getTargetName() { return targetName; }

    // ── tick (called from mixin every game tick) ──────────────────────────────

    public static void tick(WorldView world, ClientPlayerEntity player) {
        if (!active) return;

        // find/re-find target
        tryRediscover();
        if (targetEntity == null || targetEntity.isRemoved() || !targetEntity.isAlive()) {
            return; // wait for target to appear
        }

        Vec3d playerPos = player.getPos();
        Vec3d targetPos = targetEntity.getPos();
        double dist = playerPos.distanceTo(targetPos);
        boolean hasLOS = FollowEntityTask.hasLineOfSight(player, targetPos);

        // ── emergency: knocked toward void → MPC override ────────────────────
        boolean emergency = !VoidDetector.isSafe(
            playerPos.add(player.getVelocity().multiply(5)), world);

        // ── mode switching ────────────────────────────────────────────────────
        if (mode == Mode.APPROACH) {
            if (dist < COMBAT_RANGE && hasLOS && !emergency) {
                enterCombat(player);
            }
        } else { // COMBAT
            if (dist > APPROACH_RESUME && !emergency) {
                enterApproach(player);
            }
        }

        // ── emergency always uses MPC ─────────────────────────────────────────
        if (emergency && mode == Mode.APPROACH) {
            // don't switch mode permanently, just run MPC this tick
            combat.tick(player, targetEntity, world);
            return;
        }

        // ── execute current mode ──────────────────────────────────────────────
        if (mode == Mode.COMBAT) {
            combat.tick(player, targetEntity, world);
        }
        // APPROACH mode is handled by FollowEntityTask (ticked separately in mixin)
    }

    // ── mode transitions ──────────────────────────────────────────────────────

    private static void enterCombat(ClientPlayerEntity player) {
        mode = Mode.COMBAT;
        // stop A* — we're taking over movement
        TungstenModDataContainer.PATHFINDER.stop.set(true);
        TungstenModDataContainer.EXECUTOR.stop = true;
        FollowEntityTask.stop();
        Debug.logMessage("PUNK: entering combat mode");
    }

    private static void enterApproach(ClientPlayerEntity player) {
        mode = Mode.APPROACH;
        combat.releaseKeys();
        // re-start A* follow
        FollowEntityTask.start(targetEntity, 1.0);
        Debug.logMessage("PUNK: entering approach mode (A*)");
    }

    // ── target discovery ──────────────────────────────────────────────────────

    private static void tryRediscover() {
        if (targetName == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;

        if (targetEntity != null && !targetEntity.isRemoved()
                && mc.world.getEntityById(targetEntity.getId()) == targetEntity) {
            return; // still valid
        }

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.getName().getString().equalsIgnoreCase(targetName)) {
                targetEntity = p;
                // if we just found them and we're in approach, start follow
                if (mode == Mode.APPROACH) {
                    FollowEntityTask.start(targetEntity, 1.0);
                }
                return;
            }
        }
        targetEntity = null;
    }
}
