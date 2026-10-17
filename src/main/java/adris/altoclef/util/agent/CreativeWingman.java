package adris.altoclef.util.agent;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps beside a player who flies in creative (no elytra: they hover, stop, turn on the spot,
 * which an elytra cannot follow). The bot creative-flies too and holds a slot a few blocks to the
 * leader's right and a little behind; when the leader stands on the ground for a second, or is
 * lost from sight, it comes down and lands. Steering by hand (WASD) lets go at once.
 *
 * Also flies to a point the same way (hoverTo): straight up out of a pit, across a few blocks
 * over the ground, straight down onto the top surface there. No take-off run and no open sky
 * needed, as an elytra wants: the way out of a hole the bot cannot walk out of.
 */
public final class CreativeWingman {
    private enum Phase { IDLE, FLYING, LANDING, DONE, FAILED }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile String reason = "";
    private static volatile String leader;
    private static int lostTicks, groundTicks, ticks;
    private static volatile double gap;
    private static Vec3d lastLead; // the leader's position a tick ago: their motion
    private static volatile double[] point; // hoverTo: the block x, z to land on; null following
    private static double bestDist, bestY;  // progress to the point, for the watchdog
    private static int bestTick;

    private static final int TIMEOUT_TICKS = 20 * 300;
    private static final double MAX_SPEED = 1.0; // blocks a tick, about creative sprint-flying
    private static final double SIDE = 3, BACK = 1.5, UP = 1;
    private static final double CLEARANCE = 3;  // hoverTo: blocks over the highest ground ahead
    private static final int LOOK_AHEAD = 12;    // blocks of the way ahead that set the height
    private static final int STALL_TICKS = 60;   // no progress for 3 s: a roof or a wall, give up

    private CreativeWingman() {}

    /** Start keeping beside nick; call on the client thread. */
    public static synchronized Map<String, Object> start(String nick) {
        MinecraftClient c = MinecraftClient.getInstance();
        ClientPlayerEntity p = c.player;
        if (p == null || c.world == null) return fail("no world");
        if (!p.getAbilities().allowFlying) return fail("not creative");
        if (find(c, nick) == null) return fail("not in sight");
        leader = nick;
        point = null;
        phase = Phase.FLYING;
        reason = "";
        lostTicks = groundTicks = ticks = 0;
        lastLead = null;
        return status();
    }

    /** Creative-fly to block x, z and land on the top surface there; call on the client thread. */
    public static synchronized Map<String, Object> startTo(int x, int z) {
        MinecraftClient c = MinecraftClient.getInstance();
        ClientPlayerEntity p = c.player;
        if (p == null || c.world == null) return fail("no world");
        if (!p.getAbilities().allowFlying) return fail("not creative");
        leader = null;
        point = new double[] {x + 0.5, z + 0.5};
        phase = Phase.FLYING;
        reason = "";
        ticks = bestTick = 0;
        bestDist = Double.MAX_VALUE;
        bestY = p.getY();
        return status();
    }

    /** Stop where it is (the agent's @stop, a hand on the keys): stays in the air if it was. */
    public static synchronized void stop(String why) {
        if (phase == Phase.FLYING || phase == Phase.LANDING) {
            phase = Phase.FAILED;
            reason = why;
        }
    }

    public static boolean active() { return phase == Phase.FLYING || phase == Phase.LANDING; }

    public static Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("phase", phase.name().toLowerCase(Locale.ROOT));
        m.put("reason", reason);
        if (leader != null) m.put("leader", leader);
        double[] pt = point;
        if (pt != null) m.put("target", (int) Math.floor(pt[0]) + "," + (int) Math.floor(pt[1]));
        m.put("gap", Math.round(gap * 10) / 10.0);
        return m;
    }

    private static Map<String, Object> fail(String why) {
        phase = Phase.FAILED;
        reason = why;
        return status();
    }

    private static PlayerEntity find(MinecraftClient c, String nick) {
        if (c.world == null || nick == null) return null;
        for (var pl : c.world.getPlayers()) {
            if (pl != c.player && pl.getName().getString().equalsIgnoreCase(nick)) return pl;
        }
        return null;
    }

    private static double ground(MinecraftClient c, double x, double z) {
        return c.world.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
    }

    /** True while a player is up in the air in creative: off the ground, not in water, not gliding. */
    public static boolean hovering(MinecraftClient c, PlayerEntity pl) {
        return !pl.isOnGround() && !pl.isTouchingWater()
                && !adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(pl)
                && pl.getY() - ground(c, pl.getX(), pl.getZ()) > 1.5;
    }

    static void tick(MinecraftClient c) {
        if (!active()) return;
        ClientPlayerEntity p = c.player;
        if (p == null || c.world == null) {
            fail("no world");
            return;
        }
        if (ElytraFlight.manualInput(c)) {
            fail("by hand");
            return;
        }
        if (++ticks > TIMEOUT_TICKS) phase = Phase.LANDING;
        if (!p.getAbilities().flying) {
            p.getAbilities().flying = true;
            p.sendAbilitiesUpdate();
        }
        if (phase == Phase.FLYING && point != null) {
            toPoint(c, p, point);
            return;
        }
        if (phase == Phase.FLYING) {
            PlayerEntity lead = find(c, leader);
            if (lead == null) {
                if (++lostTicks > 40) phase = Phase.LANDING; // out of sight for two seconds
                p.setVelocity(Vec3d.ZERO);
                return;
            }
            lostTicks = 0;
            groundTicks = lead.isOnGround() || lead.isTouchingWater() ? groundTicks + 1 : 0;
            if (groundTicks > 20) { // stood a second: come down beside them
                phase = Phase.LANDING;
                return;
            }
            double yaw = Math.toRadians(lead.getYaw());
            double fx = -Math.sin(yaw), fz = Math.cos(yaw); // the way the leader faces
            double rx = -fz, rz = fx;                          // their right
            Vec3d slot = new Vec3d(lead.getX() + rx * SIDE - fx * BACK, lead.getY() + UP, lead.getZ() + rz * SIDE - fz * BACK);
            Vec3d d = slot.subtract(p.getX(), p.getY(), p.getZ());
            gap = d.length();
            // Leader's own motion plus a pull towards the slot, gentle near it so it settles.
            Vec3d here = new Vec3d(lead.getX(), lead.getY(), lead.getZ());
            Vec3d lv = lastLead == null ? Vec3d.ZERO : here.subtract(lastLead);
            if (lv.length() > 3) lv = Vec3d.ZERO; // a teleport, not motion
            lastLead = here;
            Vec3d v = lv.add(d.multiply(0.25));
            if (v.length() > MAX_SPEED) v = v.normalize().multiply(MAX_SPEED);
            if (gap < 0.3 && lv.length() < 0.05) v = Vec3d.ZERO;
            p.setVelocity(v);
            double lx = lead.getX() - p.getX(), lz = lead.getZ() - p.getZ();
            if (lx * lx + lz * lz > 1) { // look at the leader, as one flying together would
                p.setYaw((float) Math.toDegrees(Math.atan2(-lx, lz)));
                p.setPitch((float) Math.toDegrees(-Math.atan2(lead.getEyeY() - p.getEyeY(), Math.hypot(lx, lz))));
            }
            return;
        }
        // Landing: straight down to the ground under it, then walk on it again.
        double above = p.getY() - ground(c, p.getX(), p.getZ());
        if (p.isOnGround() || above < 0.2) {
            p.getAbilities().flying = false;
            p.sendAbilitiesUpdate();
            p.setVelocity(Vec3d.ZERO);
            phase = Phase.DONE;
            reason = "landed";
            return;
        }
        p.setVelocity(new Vec3d(0, -Math.min(0.8, Math.max(0.15, above * 0.2)), 0));
    }

    /** One tick of hoverTo: up to a few blocks over the highest ground of the way ahead first
     *  (straight up when well below it: out of a pit), then across; over the point, land. */
    private static void toPoint(MinecraftClient c, ClientPlayerEntity p, double[] pt) {
        double px = p.getX(), py = p.getY(), pz = p.getZ();
        double dx = pt[0] - px, dz = pt[1] - pz, hd = Math.hypot(dx, dz);
        gap = hd;
        if (hd < 0.7) {
            phase = Phase.LANDING;
            return;
        }
        double ux = dx / hd, uz = dz / hd;
        double cruise = ground(c, px, pz);
        for (int i = 1; i <= LOOK_AHEAD; i++) {
            double s = Math.min(i, hd);
            cruise = Math.max(cruise, ground(c, px + ux * s, pz + uz * s));
        }
        cruise += CLEARANCE;
        Vec3d v;
        if (py < cruise - 1.5) {
            v = new Vec3d(0, Math.min(0.6, 0.2 + (cruise - py) * 0.2), 0); // straight up
        } else {
            double speed = Math.min(MAX_SPEED * 0.8, 0.15 + hd * 0.1); // slows down over the point
            v = new Vec3d(ux * speed, Math.max(-0.4, Math.min(0.4, (cruise - py) * 0.2)), uz * speed);
        }
        p.setVelocity(v);
        p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setPitch(0);
        // A block over the head or a wall in the way: no progress either way. Say so rather than
        // push against it for five minutes.
        if (hd < bestDist - 0.5 || py > bestY + 0.5) {
            bestDist = Math.min(bestDist, hd);
            bestY = Math.max(bestY, py);
            bestTick = ticks;
        } else if (ticks - bestTick > STALL_TICKS) {
            phase = Phase.FAILED;
            reason = "blocked";
        }
    }
}
