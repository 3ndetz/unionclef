package kaptainwutax.tungsten.path;

import kaptainwutax.tungsten.agent.Agent;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldView;

import java.util.List;

/** Recheck recorded landings from a detached snapshot of the body that will replay them. */
public final class ReplayFeasibility {
    private ReplayFeasibility() {}

    /**
     * Baritone pathing/path/PathExecutor.java:197-218 rechecks movement feasibility
     * before starting, while cancellation is safe. A physics replay must recalculate
     * the trajectory too: residual velocity can decay during the worker's search,
     * leaving a nominally reachable landing beyond the actual body's flight.
     *
     * Returns the first recorded grounded state the replay cannot reach, or -1.
     * The root has no input; its idle tick still advances the real body. Simulate
     * those current keys as well, rather than treating the search snapshot as a tick.
     * Neither the supplied root nor the planned nodes nor the real player is mutated.
     */
    public static int firstMissedLanding(WorldView world, Agent root, List<Node> path,
                                         PathInput currentKeys, boolean nativeRotation,
                                         double sensitivity) {
        Agent simulated = root.copy();
        for (int i = 0; i < path.size(); i++) {
            Node planned = path.get(i);
            PathInput input = planned.input == null ? currentKeys : planned.input;
            float yaw = input.yaw, pitch = input.pitch;
            if (nativeRotation && planned.input != null) {
                yaw = nativeAngle(simulated.yaw, yaw, sensitivity);
                pitch = MathHelper.clamp(nativeAngle(simulated.pitch, pitch, sensitivity), -90.0F, 90.0F);
            }
            PathInput replay = new PathInput(input.forward, input.back, input.right, input.left,
                    input.jump, input.sneak, input.sprint, pitch, yaw);
            simulated = Agent.of(simulated, replay).tick(world);
            currentKeys = replay; // A later input-free splice also retains the last pressed keys.
            if (planned.agent.onGround && !simulated.onGround) return i;
        }
        return -1;
    }

    /** Same integer mouse pixels and float additions as changeLookDirection. */
    static float nativeAngle(float current, float target, double sensitivity) {
        double scale = nativeScale(sensitivity);
        long pixels = Math.round(((double) target - current) / (scale * 0.15));
        // Entity.changeLookDirection converts the mouse delta to float BEFORE
        // multiplying by 0.15F, then adds it to the current float rotation.
        return current + (float) (pixels * scale) * 0.15F;
    }

    static double nativeScale(double sensitivity) {
        double f = sensitivity * 0.6 + 0.2;
        return f * f * f * 8.0;
    }
}
