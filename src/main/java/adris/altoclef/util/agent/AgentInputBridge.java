package adris.altoclef.util.agent;

import adris.altoclef.util.helpers.MouseMoveHelper;
import adris.altoclef.util.time.TimerReal;

public class AgentInputBridge {
    public static boolean isAgentInputActive = false;
    public static final TimerReal agentInputTimer = new TimerReal(2);
    private static double accumulatedDx = 0.0;
    private static double accumulatedDy = 0.0;

    public static void addDelta(double dx, double dy) {
        accumulatedDx += dx;
        accumulatedDy += dy;
    }

    public static double[] consumeDeltas() {
        double dx = accumulatedDx;
        double dy = accumulatedDy;
        accumulatedDx = 0;
        accumulatedDy = 0;
        return new double[]{dx, dy};
    }

    public static void afterTick() {
        if (agentInputTimer.elapsed()) {
            AgentInputBridge.isAgentInputActive = false;
            MouseMoveHelper.RotationEnabled = true;
        } else {
            MouseMoveHelper.RotationEnabled = false;
            AgentInputBridge.agentInputTimer.reset();
        }
    }
}
