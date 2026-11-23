package kaptainwutax.tungsten.elytra;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A landing the physics can fly: from where the glider is to a touchdown on the ground at the spot,
 * soft enough to take no damage. A move holds one look for {@link #HOLD} ticks, turned no faster
 * than the bot turns, flown through {@link ElytraPhysics}, no new rockets (a landing sheds energy;
 * one still pulling, the take-off's on a short hop, is flown out). The
 * search ends on a move that touches the ground, and only one that touches it softly, then slides to
 * within {@link Options#reach} of the spot.
 *
 * <p>Soft: while a glide sinks slower than 0.5 blocks a tick the game keeps its fall distance at 1;
 * faster, the fall adds up, and over 3 blocks it hurts. The search keeps the same count, from a
 * sink of {@link #SOFT} for a margin, and asks for at most {@link Options#maxFall} at the touch. A move that meets a block on its way across (a
 * wall, a tree) is out: gliding into a wall is the other way elytra hurt.
 *
 * <p>The heuristic is a lower bound of the ticks left: the way at top speed, and the energy (height +
 * v^2/0.16) above a touchdown's at the fastest a glide sheds it, a steep turning dive (measured on
 * the physics: 1.34 a tick at 2.2 blocks a tick). Without the energy term every start high over the
 * spot ran out of budget circling it; with it, on flat ground, seven starts from 20 to 80 blocks
 * out and 3 to 30 up all found a landing, 1.5-3.5 blocks off, in 5 to 1 845 states.
 */
public final class LandingPlan {

    public static final int HOLD = 4;
    private static final float[] YAWS = {-60, -30, -12, 0, 12, 30, 60};
    private static final float[] PITCHES = {-20, -5, 10, 25, 40, 60};
    static final double TOP_SPEED = 1.8, SHED = 1.35, E_TOUCH = 9;
    /** After the touch: a tick more at the touch speed (the wings close a tick later), then ground
     *  friction, 0.6 * 0.91 a tick. Without the extra tick the bot stopped 1.5 blocks past where the
     *  plan said, on all three legs of the first bench run (08.11). */
    static final double SLIDE = 1 + 0.546 / (1 - 0.546);
    /** The game keeps the fall at 1 while the glide sinks slower than 0.5 a tick; the search counts it
     *  from 0.4. A plan touching at -0.45 had the bot sink 0.59 a tick its last 8 ticks, a tenth off
     *  the plan, and lose 2 health (08.11). */
    static final double SOFT = 0.4;
    /** Room kept round the body, sideways, from anything across the way. Planned with none, a plan
     *  grazed the posts round the spot, the bot flying it a block off was into them at once, and half
     *  the plans on the walled course were unsafe the tick they came (08.11). */
    static final double ROOM = 0.5;

    /** Where it touches, how fast it sinks and moves then, the fall distance, where the slide ends. */
    public record Touchdown(Vec3d at, Vec3d vel, double fall, Vec3d stop) {
    }

    /** The landing tick by tick from the start (index 0); ok with the touchdown, else the state the
     *  search came nearest from. */
    public record Plan(boolean ok, List<AirPlan.Tick> ticks, Touchdown touch, int expanded, String why) {
    }

    public static final class Options {
        public double reach = 4;      // the slide ends within this of the spot
        public double maxFall = 2;    // fall distance at the touch, blocks (3 hurts)
        public double weight = 3;     // weighted A*
        public int budget = 8000;     // states expanded at most
    }

    private record Act(float yaw, float pitch, int ticks) {
    }

    private static final class Node {
        final Vec3d pos, vel;
        final float yaw, pitch;
        final int rocketLeft;
        final double fall, g;
        double f;
        final Node parent;
        final Act act;

        Node(Vec3d pos, Vec3d vel, float yaw, float pitch, int rocketLeft, double fall, double g, Node parent, Act act) {
            this.pos = pos;
            this.vel = vel;
            this.yaw = yaw;
            this.pitch = pitch;
            this.rocketLeft = rocketLeft;
            this.fall = fall;
            this.g = g;
            this.parent = parent;
            this.act = act;
        }
    }

    private LandingPlan() {
    }

    /**
     * @param fall the player's fall distance now (1 while gliding slowly)
     * @param spot where to land: the ground's top at the target (feet height)
     */
    public static Plan plan(BlockView world, AirPlan.State start, double fall, Vec3d spot, Options o) {
        Solid solid = new Solid(world);
        PriorityQueue<Node> q = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> seen = new HashMap<>();
        Node s0 = new Node(start.pos(), start.vel(), start.yaw(), start.pitch(), start.rocketLeft(), fall, 0, null, null);
        s0.f = o.weight * h(s0.pos, s0.vel, spot, o);
        q.add(s0);
        Node best = s0;
        int expanded = 0, walls = 0, hard = 0, wide = 0;
        while (!q.isEmpty() && expanded < o.budget) {
            Node s = q.poll();
            long k = key(s);
            if (seen.getOrDefault(k, Double.MAX_VALUE) <= s.g) continue;
            seen.put(k, s.g);
            expanded++;
            if (s.f - s.g < best.f - best.g) best = s;
            float hd = AirPlan.heading(s.vel, s.yaw);
            for (float dy : YAWS) {
                for (float tp : PITCHES) {
                    float ty = hd + dy;
                    Vec3d p = s.pos, v = s.vel;
                    float yaw = s.yaw, pitch = s.pitch;
                    double fd = s.fall;
                    int t = 0, rl = s.rocketLeft;
                    boolean ok = true;
                    Touchdown touch = null;
                    for (; t < HOLD; t++) {
                        yaw += MathHelper.clamp(MathHelper.wrapDegrees(ty - yaw), -25, 25);
                        pitch += MathHelper.clamp(tp - pitch, -15, 15);
                        var st = ElytraPhysics.tick(v, yaw, pitch, rl > 0);
                        v = st.motion();
                        Vec3d m = st.move();
                        if (rl > 0) rl--;
                        fd = v.y > -SOFT ? 1 : fd - m.y;
                        Vec3d n = p.add(m);
                        if (solid.hits(p, n)) {
                            // Only the way down is blocked: the ground. Anything across is a wall, and
                            // so is a top higher than the spot's (a wall's, a post's): the bot slides off it.
                            if (m.y >= 0 || p.y > spot.y + 1 || solid.hits(p, new Vec3d(n.x, p.y, n.z))) {
                                ok = false;
                                walls++;
                                break;
                            }
                            touch = touchdown(solid, p, n, v, fd);
                            t++;
                            break;
                        }
                        if (solid.hits(p, n, ROOM)) {
                            ok = false;
                            walls++;
                            break;
                        }
                        p = n;
                    }
                    if (!ok) continue;
                    if (touch != null) {
                        if (touch.fall() > o.maxFall) {
                            hard++;
                            continue;
                        }
                        if (Math.hypot(touch.stop().x - spot.x, touch.stop().z - spot.z) > o.reach) {
                            wide++;
                            continue;
                        }
                        Node c = new Node(touch.at(), v, yaw, pitch, rl, touch.fall(), s.g + t, s, new Act(ty, tp, t));
                        return new Plan(true, unroll(start, c), touch, expanded, "");
                    }
                    double hh = h(p, v, spot, o);
                    if (hh == Double.MAX_VALUE) continue;
                    Node c = new Node(p, v, yaw, pitch, rl, fd, s.g + t, s, new Act(ty, tp, t));
                    c.f = c.g + o.weight * hh;
                    if (seen.getOrDefault(key(c), Double.MAX_VALUE) <= c.g) continue;
                    q.add(c);
                }
            }
        }
        String why = expanded + " states; moves into walls " + walls + ", hard touches " + hard
                + ", touches off the spot " + wide;
        return new Plan(false, unroll(start, best), null, expanded, why);
    }

    /** Where the move's way down meets the ground: the feet lowered in 1/16 steps till the box sits on
     *  it; the slide on from there at the touch speed, stopped short by a block in its way. */
    private static Touchdown touchdown(Solid solid, Vec3d from, Vec3d to, Vec3d v, double fall) {
        double y = from.y;
        while (y - 0.0625 > to.y - 1 && !solid.hits(new Vec3d(to.x, y - 0.0625, to.z))) y -= 0.0625;
        Vec3d at = new Vec3d(to.x, y, to.z);
        Vec3d stop = at, end = at.add(v.x * SLIDE, 0, v.z * SLIDE);
        if (!solid.hits(at.add(0, 0.01, 0), end.add(0, 0.01, 0))) stop = end;
        return new Touchdown(at, v, fall + Math.max(0, from.y - y), stop);
    }

    private static double h(Vec3d p, Vec3d v, Vec3d spot, Options o) {
        double d = Math.hypot(spot.x - p.x, spot.z - p.z);
        double e = p.y - spot.y + v.lengthSquared() / 0.16;
        if (e < AirPlan.LOSS_PER_BLOCK * (d - o.reach) - 1) return Double.MAX_VALUE; // cannot glide that far
        return Math.max(Math.max(0, d - o.reach) / TOP_SPEED, (e - E_TOUCH) / SHED);
    }

    private static List<AirPlan.Tick> unroll(AirPlan.State start, Node end) {
        List<Act> acts = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) acts.add(0, n.act);
        List<AirPlan.Tick> out = new ArrayList<>();
        out.add(new AirPlan.Tick(start.pos(), start.vel(), start.yaw(), start.pitch(), false));
        Vec3d p = start.pos(), v = start.vel();
        float yaw = start.yaw(), pitch = start.pitch();
        int rl = start.rocketLeft();
        for (Act a : acts) {
            for (int t = 0; t < a.ticks(); t++) {
                yaw += MathHelper.clamp(MathHelper.wrapDegrees(a.yaw() - yaw), -25, 25);
                pitch += MathHelper.clamp(a.pitch() - pitch, -15, 15);
                var st = ElytraPhysics.tick(v, yaw, pitch, rl > 0);
                v = st.motion();
                if (rl > 0) rl--;
                p = p.add(st.move());
                out.add(new AirPlan.Tick(p, v, MathHelper.wrapDegrees(yaw), pitch, false));
            }
        }
        return out;
    }

    /** One-block cells, heading in 15 degree steps, speed in 0.2 steps, a rocket pulling or not. */
    private static long key(Node s) {
        long x = MathHelper.floor(s.pos.x) & 0xFFFF, y = MathHelper.floor(s.pos.y) & 0x3FF,
                z = MathHelper.floor(s.pos.z) & 0xFFFF;
        long hd = Math.floorMod(Math.round(AirPlan.heading(s.vel, s.yaw) / 15f), 24) & 0x1F;
        long sp = Math.min(15, (int) (s.vel.length() / 0.2));
        return x << 48 | z << 32 | y << 22 | hd << 17 | sp << 13 | (s.rocketLeft > 0 ? 1 : 0);
    }

    /** The world's collision boxes, each block looked up once: a search asks for the same few
     *  hundred blocks hundreds of thousands of times. */
    private static final class Solid {
        private final BlockView world;
        private final Map<Long, Box> boxes = new HashMap<>();
        private static final Box NONE = new Box(0, 0, 0, 0, 0, 0);

        Solid(BlockView world) {
            this.world = world;
        }

        boolean hits(Vec3d feet) {
            return hits(feet, feet);
        }

        /** The gliding box anywhere between two ticks' feet touches a block (as ElytraPhysics). */
        boolean hits(Vec3d from, Vec3d to) {
            return hits(from, to, 0);
        }

        /** As hits, the box wider by room on every side but the bottom: the ground under it is no wall.
         *  Room under the feet as well, over a wall, was worse: 4 hurt landings in 10 against 1 in 14,
         *  the plans kept high over the wall and came down hard behind it (08.11). */
        boolean hits(Vec3d from, Vec3d to, double room) {
            double w = 0.3 + room;
            Box b = new Box(Math.min(from.x, to.x) - w, Math.min(from.y, to.y), Math.min(from.z, to.z) - w,
                    Math.max(from.x, to.x) + w, Math.max(from.y, to.y) + 0.6 + room, Math.max(from.z, to.z) + w);
            for (int x = MathHelper.floor(b.minX); x <= MathHelper.floor(b.maxX); x++) {
                for (int y = MathHelper.floor(b.minY); y <= MathHelper.floor(b.maxY); y++) {
                    for (int z = MathHelper.floor(b.minZ); z <= MathHelper.floor(b.maxZ); z++) {
                        Box c = box(x, y, z);
                        if (c != NONE && c.intersects(b)) return true;
                    }
                }
            }
            return false;
        }

        private Box box(int x, int y, int z) {
            return boxes.computeIfAbsent(BlockPos.asLong(x, y, z), k -> {
                var at = new BlockPos(x, y, z);
                var shape = world.getBlockState(at).getCollisionShape(world, at);
                return shape.isEmpty() ? NONE : shape.getBoundingBox().offset(at);
            });
        }
    }
}
