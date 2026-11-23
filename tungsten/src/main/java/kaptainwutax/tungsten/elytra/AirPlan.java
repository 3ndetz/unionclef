package kaptainwutax.tungsten.elytra;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The second layer of the way through the air: a flight that the physics can actually fly, searched
 * inside the corridor the cell search ({@link AirRoute}) found. A state is where the glider is, how
 * fast, where it looks and how long its rocket still pulls; a move holds one look for
 * {@link #HOLD} ticks, with or without a fresh rocket, turned no faster than the bot turns, and is
 * flown through {@link ElytraPhysics}. A move that touches a block (with {@code margin} of room) or
 * leaves the corridor is out. The cost is ticks plus a price for each rocket.
 *
 * <p>Why: the cells know where the gaps are, not whether a glider gets from a low one up to a high
 * one. A climb needs speed taken in before it, so a rocket has to burn where the climb is still
 * seconds away -- further than the solver's two seconds can see (01.11: two passes of the maze in
 * fifteen, the rest stalled under the high gap). Here the rockets fall where the flight needs them.
 *
 * <p>The heuristic is a lower bound of what is left: the corridor distance to go at top speed, and
 * the rockets the energy cannot do without. A glide loses at least {@link #LOSS_PER_BLOCK} of energy
 * (height + v^2/0.16) a block; one rocket adds at most {@link #ROCKET_ENERGY}.
 */
public final class AirPlan {

    public static final int HOLD = 8;                  // ticks one move holds its look
    private static final float[] YAWS = {-50, -25, -10, 0, 10, 25, 50};
    private static final float[] PITCHES = {-50, -30, -15, 0, 15, 35};
    static final double LOSS_PER_BLOCK = 0.099, ROCKET_ENERGY = 45, TOP_SPEED = 1.8;

    /** Where the glider is: feet, motion, look, and the ticks its rocket still pulls. */
    public record State(Vec3d pos, Vec3d vel, float yaw, float pitch, int rocketLeft) {
    }

    /** One tick of the planned flight: the state after it, the look flown, a rocket fired on it. */
    public record Tick(Vec3d pos, Vec3d vel, float yaw, float pitch, boolean fire) {
    }

    /** The flight, tick by tick from the start state (index 0 is the start); ok when it reaches
     *  the goal, else the furthest it got. */
    public record Plan(boolean ok, List<Tick> ticks, int rockets, int expanded, String why) {
    }

    public static final class Options {
        public double rocketTicks = 10;  // a rocket's price, in ticks of flight
        public double margin = 0.8;     // room kept around the body, blocks
        public int pull = 20;            // ticks a fresh rocket is planned to pull (the least it does)
        public double reach = 6;         // the goal is reached within this many blocks
        public double weight = 2.0;      // weighted A*: faster, a little longer than the best
        public int budget = 20_000;      // states expanded at most
    }

    private record Act(float yaw, float pitch, boolean fire, int ticks) {
    }

    private static final class Node {
        final Vec3d pos, vel;
        final float yaw, pitch;
        final int rocketLeft, rockets;
        final double g;
        double f;
        final Node parent;
        final Act act;

        Node(Vec3d pos, Vec3d vel, float yaw, float pitch, int rocketLeft, int rockets, double g, Node parent, Act act) {
            this.pos = pos;
            this.vel = vel;
            this.yaw = yaw;
            this.pitch = pitch;
            this.rocketLeft = rocketLeft;
            this.rockets = rockets;
            this.g = g;
            this.parent = parent;
            this.act = act;
        }
    }

    private AirPlan() {
    }

    public static Plan plan(State start, Vec3d goal, AirRoute.Corridor cor, Options o) {
        PriorityQueue<Node> q = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> seen = new HashMap<>();
        Node s0 = new Node(start.pos(), start.vel(), start.yaw(), start.pitch(), start.rocketLeft(), 0, 0, null, null);
        s0.f = o.weight * h(s0.pos, s0.vel, s0.rocketLeft, goal, cor, o);
        q.add(s0);
        // A start already closer to a block than the margin (low after a take-off, off the plan in a
        // gap) plans its first move without it: the way out of a tight spot is allowed.
        boolean tightStart = cor.boxHits(start.pos(), o.margin);
        Node bestH = s0;
        double bestHv = s0.f;
        int expanded = 0, firstHit = 0, firstOff = 0;
        while (!q.isEmpty() && expanded < o.budget) {
            Node s = q.poll();
            long k = key(s);
            if (seen.getOrDefault(k, Double.MAX_VALUE) <= s.g) continue;
            seen.put(k, s.g);
            expanded++;
            if (s.f - s.g < bestHv) {
                bestHv = s.f - s.g;
                bestH = s;
            }
            float hd = heading(s.vel, s.yaw);
            int fires = s.rocketLeft == 0 ? 1 : 0;
            for (int fire = 0; fire <= fires; fire++) {
                for (float dy : YAWS) {
                    for (float tp : PITCHES) {
                        float ty = hd + dy;
                        Vec3d p = s.pos, v = s.vel;
                        float yaw = s.yaw, pitch = s.pitch;
                        int rl = fire == 1 ? o.pull : s.rocketLeft, t = 0;
                        boolean ok = true, done = false;
                        for (; t < HOLD; t++) {
                            yaw += MathHelper.clamp(MathHelper.wrapDegrees(ty - yaw), -25, 25);
                            pitch += MathHelper.clamp(tp - pitch, -15, 15);
                            var st = ElytraPhysics.tick(v, yaw, pitch, rl > 0);
                            v = st.motion();
                            if (rl > 0) rl--;
                            Vec3d n = p.add(st.move());
                            boolean off = cor.toGo(n) < 0;
                            if (off || cor.boxHits(p, n, tightStart && s == s0 ? 0 : o.margin)) {
                                if (s == s0) {
                                    if (off) firstOff++;
                                    else firstHit++;
                                }
                                ok = false;
                                break;
                            }
                            p = n;
                            if (p.distanceTo(goal) < o.reach) {
                                done = true;
                                t++;
                                break;
                            }
                        }
                        if (!ok) continue;
                        Node c = new Node(p, v, yaw, pitch, rl, s.rockets + fire, s.g + t + (fire == 1 ? o.rocketTicks : 0),
                                s, new Act(ty, tp, fire == 1, t));
                        if (done) return new Plan(true, unroll(start, c, o.pull), c.rockets, expanded, "");
                        c.f = c.g + o.weight * h(p, v, rl, goal, cor, o);
                        if (seen.getOrDefault(key(c), Double.MAX_VALUE) <= c.g) continue;
                        q.add(c);
                    }
                }
            }
        }
        String why = "first moves: " + firstHit + " hit, " + firstOff + " off the corridor"
                + (tightStart ? ", tight start" : "") + ", start toGo " + Math.round(cor.toGo(start.pos()));
        return new Plan(false, unroll(start, bestH, o.pull), bestH.rockets, expanded, why);
    }

    private static double h(Vec3d p, Vec3d v, int rl, Vec3d goal, AirRoute.Corridor cor, Options o) {
        double d0 = cor.toGo(p);
        if (d0 < 0) return Double.MAX_VALUE / 4;
        double d = Math.max(d0, p.distanceTo(goal));
        double e = p.y + v.lengthSquared() / 0.16 + rl * (ROCKET_ENERGY / 20);
        double shortOf = goal.y + LOSS_PER_BLOCK * d - e;
        return d / TOP_SPEED + (shortOf > 0 ? Math.ceil(shortOf / ROCKET_ENERGY) * o.rocketTicks : 0);
    }

    /** The moves as a flight, tick by tick, flown again from the start. */
    private static List<Tick> unroll(State start, Node end, int pull) {
        List<Act> acts = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) acts.add(0, n.act);
        List<Tick> out = new ArrayList<>();
        out.add(new Tick(start.pos(), start.vel(), start.yaw(), start.pitch(), false));
        Vec3d p = start.pos(), v = start.vel();
        float yaw = start.yaw(), pitch = start.pitch();
        int rl = start.rocketLeft();
        for (Act a : acts) {
            if (a.fire()) rl = pull;
            for (int t = 0; t < a.ticks(); t++) {
                yaw += MathHelper.clamp(MathHelper.wrapDegrees(a.yaw() - yaw), -25, 25);
                pitch += MathHelper.clamp(a.pitch() - pitch, -15, 15);
                var st = ElytraPhysics.tick(v, yaw, pitch, rl > 0);
                v = st.motion();
                if (rl > 0) rl--;
                p = p.add(st.move());
                out.add(new Tick(p, v, MathHelper.wrapDegrees(yaw), pitch, a.fire() && t == 0));
            }
        }
        return out;
    }

    static float heading(Vec3d v, float fallback) {
        return Math.hypot(v.x, v.z) > 0.05 ? (float) Math.toDegrees(Math.atan2(-v.x, v.z)) : fallback;
    }

    /** Two states with the same key are the same for the search: 2-block cells, heading in 20 degree
     *  steps, speed in 0.3 steps, a rocket pulling or not. */
    private static long key(Node s) {
        long x = MathHelper.floor(s.pos.x / 2) & 0xFFFF, y = MathHelper.floor(s.pos.y / 2) & 0x3FF,
                z = MathHelper.floor(s.pos.z / 2) & 0xFFFF;
        long hd = Math.round(heading(s.vel, s.yaw) / 20f) & 0x1F;
        long sp = Math.min(15, (int) (s.vel.length() / 0.3));
        return x << 48 | z << 32 | y << 22 | hd << 17 | sp << 13 | (s.rocketLeft > 0 ? 1 : 0);
    }
}
