package adris.altoclef.util.agent;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

/**
 * The no-break policy (setBuildPolicy allowBreak=false), held where every break starts: the
 * client's attackBlock and updateBlockBreakingProgress. The pathfinders honour the policy, but a
 * break can start in a dozen other places, and in creative one hit takes the block at once -- the
 * bot took blocks out of other players' builds on a shared server with the policy on.
 *
 * <p>The person at the keyboard still breaks by hand: a left mouse button held for real lets the
 * hit through. Each refusal is logged once per block with the code that asked, to find it.
 */
public final class NoBreakGuard {
    private static volatile BlockPos lastLogged;
    public static volatile int refused;

    private NoBreakGuard() {}

    /** True when this break must not happen. */
    public static boolean refuse(BlockPos pos) {
        if (kaptainwutax.tungsten.TungstenConfig.get().allowBreak) return false;
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.currentScreen == null && GLFW.glfwGetMouseButton(c.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) {
            return false; // by hand
        }
        refused++;
        if (pos != null && !pos.equals(lastLogged)) {
            lastLogged = pos.toImmutable();
            StringBuilder who = new StringBuilder();
            int shown = 0;
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                String cl = e.getClassName();
                if (cl.startsWith("java.") || cl.contains("NoBreakGuard") || cl.contains("mixin")
                        || cl.startsWith("net.minecraft.") || cl.startsWith("org.spongepowered")) continue;
                who.append(shown == 0 ? "" : " <- ").append(cl.substring(cl.lastIndexOf('.') + 1)).append('.').append(e.getMethodName());
                if (++shown == 6) break;
            }
            System.out.println("NoBreakGuard: refused a break at " + pos.toShortString() + " (" + who + ")");
        }
        return true;
    }
}
