package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import kaptainwutax.tungsten.render.Line;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-computes a combat movement+attack timeline using Agent physics simulation.
 *
 * Simulates N ticks ahead: sprint-jump toward target, turn to aim, identify
 * attack windows where crosshair would sweep through target hitbox.
 *
 * Currently visualization only — renders planned arc + attack windows.
 * When combatExecutorEnabled = true, will execute the planned inputs.
 */
public class CombatExecutor {

    private static final int SIM_TICKS = 30; // simulate ~1.5 sec ahead
    private static final int RECOMPUTE_INTERVAL = 10; // ticks between replans
    private static final double ATTACK_REACH = 3.0;

    private static final Color COL_PLAN_ARC    = new Color(100, 255, 100); // green arc
    private static final Color COL_ATTACK_WIN  = new Color(255, 50, 50);   // red = can hit
    private static final Color COL_TURN_POINT  = new Color(255, 200, 50);  // yellow = turning

    // planned trajectory
    private final List<Vec3d> plannedPositions = new ArrayList<>();
    private final List<Boolean> attackWindows = new ArrayList<>();
    private int recomputeTimer = 0;

    /**
     * Recompute plan every N ticks. Called from SafetySystem.tick().
     */
    public void tick(ClientPlayerEntity player, Entity target, WorldView world) {
        recomputeTimer++;
        if (recomputeTimer < RECOMPUTE_INTERVAL) return;
        recomputeTimer = 0;

        plannedPositions.clear();
        attackWindows.clear();

        // create simulated agent from current player state
        Agent sim = Agent.of(player);
        Vec3d targetPos = target.getEntityPos();
        double targetHalfHeight = target.getHeight() * 0.5;

        // simulate forward
        for (int t = 0; t < SIM_TICKS; t++) {
            Vec3d simPos = sim.getPos();
            plannedPositions.add(simPos);

            // compute yaw toward target (predicted position with simple extrapolation)
            Vec3d toTarget = targetPos.subtract(simPos);
            double horizDist = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
            float aimYaw = (float) Math.toDegrees(-Math.atan2(toTarget.x, toTarget.z));

            // check if we could hit from this position
            boolean canHit = horizDist < ATTACK_REACH && Math.abs(simPos.y - targetPos.y) < 2.0;

            // plan inputs: sprint-jump when on ground, aim toward target
            if (canHit) {
                // in attack range: face target, no jump (stable footing)
                sim.yaw = aimYaw;
                sim.keyForward = true;
                sim.keySprint = false;
                sim.keyJump = false;
            } else {
                // approach: sprint-jump toward target
                sim.yaw = aimYaw;
                sim.keyForward = true;
                sim.keySprint = true;
                sim.keyJump = sim.onGround;
            }
            sim.keyBack = false;
            sim.keyLeft = false;
            sim.keyRight = false;
            sim.keySneak = false;

            attackWindows.add(canHit);

            // advance simulation
            sim.tick(world);
        }
    }

    /**
     * Render planned trajectory. Called from SafetySystem.renderUpdate().
     */
    public void renderUpdate() {
        if (plannedPositions.size() < 2) return;

        for (int i = 0; i < plannedPositions.size() - 1; i++) {
            Vec3d from = plannedPositions.get(i).add(0, 0.1, 0);
            Vec3d to = plannedPositions.get(i + 1).add(0, 0.1, 0);

            boolean canHit = i < attackWindows.size() && attackWindows.get(i);
            Color col = canHit ? COL_ATTACK_WIN : COL_PLAN_ARC;

            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(from, to, col));

            // mark attack windows with cubes
            if (canHit) {
                TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                        from.subtract(0.15, 0.05, 0.15), new Vec3d(0.3, 0.1, 0.3), COL_ATTACK_WIN));
            }
        }

        // mark start and end
        if (!plannedPositions.isEmpty()) {
            Vec3d start = plannedPositions.get(0);
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    start.subtract(0.1, 0, 0.1), new Vec3d(0.2, 0.2, 0.2), COL_TURN_POINT));
        }
    }

    public void reset() {
        plannedPositions.clear();
        attackWindows.clear();
        recomputeTimer = 0;
    }
}
