package adris.altoclef.util.agent;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The last two minutes of gliding, tick by tick: position, velocity, look and whether a rocket is
 * pulling. Read over MCP (flightLog) to check tungsten's ElytraPhysics against what the game did:
 * the same look fed to the model must give the next tick's velocity.
 *
 * <p>Logged at the end of the client tick, after the pilot has set the look the next tick will fly.
 */
public final class FlightLog {

    private static final int MAX = 20 * 120;
    private static final double[][] rows = new double[MAX][];
    private static int head, size;
    private static long tick;

    private FlightLog() {
    }

    static synchronized void tick(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        tick++;
        if (p == null || c.world == null || !adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p)) return;
        var v = p.getVelocity();
        rows[head] = new double[]{tick, p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, p.getYaw(), p.getPitch(),
                rocketOn(c, p) ? 1 : 0, p.horizontalCollision || p.verticalCollision ? 1 : 0};
        head = (head + 1) % MAX;
        if (size < MAX) size++;
    }

    /** A rocket fired by the player rides along with it while it pulls. */
    static boolean rocketOn(MinecraftClient c, ClientPlayerEntity p) {
        for (var e : c.world.getEntities()) {
            if (e instanceof FireworkRocketEntity r && r.getOwner() == p && r.squaredDistanceTo(p) < 4) return true;
        }
        return false;
    }

    /** The last n ticks, oldest first: tick, x, y, z, vx, vy, vz, yaw, pitch, rocket, collided. */
    public static synchronized Map<String, Object> last(int n) {
        n = Math.max(1, Math.min(n, size));
        List<double[]> out = new ArrayList<>(n);
        for (int i = size - n; i < size; i++) out.add(rows[(head - size + i + MAX) % MAX]);
        Map<String, Object> m = new HashMap<>();
        m.put("columns", "tick,x,y,z,vx,vy,vz,yaw,pitch,rocket,collided");
        m.put("rows", out);
        return m;
    }

    public static synchronized void clear() {
        head = size = 0;
    }
}
