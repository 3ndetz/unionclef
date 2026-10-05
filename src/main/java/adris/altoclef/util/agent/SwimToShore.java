package adris.altoclef.util.agent;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.Map;

/**
 * Gets the bot out of water: picks the nearest dry shore column and swims there on the surface
 * (forward + jump, the way a player does), climbing out onto the bank.
 *
 * <p>The pathfinder handles water poorly and the bot used to stay in a lake for minutes; elytra
 * cannot take off from water either. This needs no path: open water is flat, so a straight line
 * to the nearest dry block is the route, and jump keeps the head up and steps onto the bank.
 */
public final class SwimToShore {

    public enum Phase { IDLE, SWIMMING, DONE, FAILED }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile String reason = "";
    private static double tx, ty, tz;
    private static int ticks, dryTicks, stuckTicks;
    private static double lastX, lastZ;
    private static boolean registered;

    private static final int TIMEOUT_TICKS = 20 * 45;

    private SwimToShore() {}

    /** Start swimming to the nearest shore within radius; call on the client thread. */
    public static synchronized Map<String, Object> start(int radius) {
        if (!registered) {
            ClientTickEvents.END_CLIENT_TICK.register(SwimToShore::tick);
            registered = true;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null) {
            phase = Phase.FAILED;
            reason = "not in game";
            return status();
        }
        if (!inWater(p)) {
            phase = Phase.DONE;
            reason = "not in water";
            return status();
        }
        BlockPos shore = nearestShore(client, p, Math.max(4, Math.min(radius, 48)));
        if (shore == null) {
            phase = Phase.FAILED;
            reason = "no shore in range";
            return status();
        }
        tx = shore.getX() + 0.5;
        ty = shore.getY();
        tz = shore.getZ() + 0.5;
        ticks = dryTicks = stuckTicks = 0;
        lastX = p.getX();
        lastZ = p.getZ();
        reason = "";
        phase = Phase.SWIMMING;
        return status();
    }

    public static void stop() {
        if (phase == Phase.SWIMMING) {
            phase = Phase.IDLE;
            reason = "stopped";
            MinecraftClient.getInstance().execute(SwimToShore::release);
        }
    }

    public static Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        m.put("reason", reason);
        m.put("target", String.format(java.util.Locale.ROOT, "%.0f,%.0f,%.0f", tx, ty, tz));
        var p = MinecraftClient.getInstance().player;
        if (p != null) {
            m.put("inWater", inWater(p));
            m.put("distance", Math.round(Math.hypot(tx - p.getX(), tz - p.getZ())));
        }
        return m;
    }

    public static boolean inWater(ClientPlayerEntity p) {
        return p.isTouchingWater() || p.isSubmergedInWater();
    }

    /** Nearest column whose top block is dry ground, in rings outward; null when all water. */
    private static BlockPos nearestShore(MinecraftClient client, ClientPlayerEntity p, int radius) {
        int px = p.getBlockX(), pz = p.getBlockZ();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int r = 1; r <= radius && best == null; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring only
                    int x = px + dx, z = pz + dz;
                    if (!client.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
                    int top = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                    BlockPos ground = new BlockPos(x, top - 1, z);
                    var st = client.world.getBlockState(ground);
                    if (!st.getFluidState().isEmpty() || st.isAir()) continue;
                    // A bank more than a block above the water cannot be climbed by swimming.
                    if (top - p.getY() > 2.2) continue;
                    double d = dx * dx + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = new BlockPos(x, top, z);
                    }
                }
            }
        }
        return best;
    }

    private static void tick(MinecraftClient client) {
        if (phase != Phase.SWIMMING) return;
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null) {
            end(Phase.FAILED, "not in game");
            return;
        }
        if (++ticks > TIMEOUT_TICKS) {
            end(Phase.FAILED, "timeout");
            return;
        }
        // Out once standing on dry ground for a few ticks.
        if (!inWater(p) && p.isOnGround()) {
            if (++dryTicks >= 4) end(Phase.DONE, "ashore");
            return;
        }
        dryTicks = 0;
        // No progress for 3 s (a wall of a bank too high): try the next shore over.
        if (ticks % 60 == 0) {
            if (Math.hypot(p.getX() - lastX, p.getZ() - lastZ) < 1.0 && ++stuckTicks >= 1) {
                BlockPos other = nearestShore(client, p, 48);
                if (other == null) {
                    end(Phase.FAILED, "stuck");
                    return;
                }
                tx = other.getX() + 0.5;
                tz = other.getZ() + 0.5;
                stuckTicks = 0;
            }
            lastX = p.getX();
            lastZ = p.getZ();
        }
        double dx = tx - p.getX(), dz = tz - p.getZ();
        p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setPitch(-10); // a little up: stay at the surface
        client.options.forwardKey.setPressed(true);
        client.options.jumpKey.setPressed(true); // swim up; at the bank, step onto it
        client.options.sprintKey.setPressed(false); // sprint-swimming dives under
    }

    private static void end(Phase ph, String why) {
        phase = ph;
        reason = why;
        release();
    }

    private static void release() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.options.forwardKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }
}
