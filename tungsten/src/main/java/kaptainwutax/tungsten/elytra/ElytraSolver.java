package kaptainwutax.tungsten.elytra;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the look (and whether to fire a rocket) for the next ticks of an elytra flight by trying
 * them: every candidate yaw and pitch, with and without a fresh rocket, is flown two seconds ahead
 * through {@link ElytraPhysics}. A candidate that hits a block is out; of the rest the best one makes
 * the most of the way to the goal, keeps its height and speed, keeps clear of blocks and spends the
 * fewest rockets. Called again every couple of ticks, so holding one look for the two seconds is only
 * how far it looks, not what it flies.
 *
 * <p>A rocket is priced in blocks of progress ({@code rocketCost}): in survival every rocket is paper
 * and gunpowder, so there it is dear and the solver glides whenever gliding still gets there.
 */
public final class ElytraSolver {

    public static final int HORIZON = 40;            // ticks flown per candidate
    private static final float[] YAW_OFFSETS = {0, -10, 10, -25, 25, -50, 50};
    // Coarse pitches first, then a finer pass around the best one: ~250 flights of 40 ticks, a few
    // milliseconds on the client thread.
    private static final float PITCH_MIN = -50, PITCH_MAX = 40, PITCH_STEP = 6, FINE_STEP = 2;

    /** What the solver wants, and the candidates it looked at (for drawing). */
    public record Plan(float yaw, float pitch, boolean fire, double score, Vec3d[] path,
                       List<Vec3d[]> others, boolean cornered) {
    }

    /** Goal and prices for one decision. */
    public static final class Goal {
        public Vec3d point;              // where to be heading for: a point ahead at cruise height
        public double rocketCost = 25;   // blocks of progress one rocket is worth
        public double heightWeight = 0.4;
        public double clearance = 1.5;   // blocks kept between the body and any block
        public double cruiseSpeed = 1.5; // blocks a tick it should still have at the goal height
        /** A point the path has to pass close to on the way (a ring's centre), or null. */
        public Vec3d through;
        public double throughWeight = 8;
        public boolean mayFire = true;   // no rocket pulling now and some in the inventory
    }

    private ElytraSolver() {
    }

    /**
     * @param rocketLeft ticks the rocket already fired still pulls (0 when none)
     */
    public static Plan solve(BlockView world, Vec3d pos, Vec3d vel, int rocketLeft, Goal g) {
        Search s = new Search(world, pos, vel, rocketLeft, g);
        int fires = g.mayFire && rocketLeft == 0 ? 1 : 0;
        for (int fire = 0; fire <= fires; fire++) {
            for (float dy : YAW_OFFSETS) {
                for (float pitch = PITCH_MIN; pitch <= PITCH_MAX; pitch += PITCH_STEP) {
                    s.fly(s.direct + dy, pitch, fire == 1, dy);
                }
            }
        }
        if (s.best == null) return s.latest; // boxed in everywhere: the longest way before a hit
        // Finer around the winner: pitch in steps of 2, yaw 5 either side.
        Plan w = s.best;
        float wdy = MathHelper.wrapDegrees(w.yaw() - s.direct);
        for (float dp = -2 * FINE_STEP; dp <= 2 * FINE_STEP; dp += FINE_STEP) {
            for (float dy = -5; dy <= 5; dy += 5) {
                if (dp != 0 || dy != 0) s.fly(w.yaw() + dy, w.pitch() + dp, w.fire(), wdy + dy);
            }
        }
        s.all.sort((x, y) -> Double.compare(y.score(), x.score()));
        List<Vec3d[]> others = new ArrayList<>();
        for (int i = 1; i < s.all.size() && others.size() < 6; i += Math.max(1, s.all.size() / 12)) {
            others.add(s.all.get(i).path());
        }
        Plan b = s.best;
        return new Plan(b.yaw(), b.pitch(), b.fire(), b.score(), b.path(), others, false);
    }

    /** One decision's candidates, flown and scored. */
    private static final class Search {
        final BlockView world;
        final Vec3d pos, vel;
        final int rocketLeft;
        final Goal g;
        final float direct;
        final double d0, eWant;
        Plan best, latest;
        int latestLen = -1;
        final List<Plan> all = new ArrayList<>();

        Search(BlockView world, Vec3d pos, Vec3d vel, int rocketLeft, Goal g) {
            this.world = world;
            this.pos = pos;
            this.vel = vel;
            this.rocketLeft = rocketLeft;
            this.g = g;
            Vec3d to = g.point.subtract(pos);
            direct = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
            d0 = Math.hypot(to.x, to.z);
            eWant = g.point.y + g.cruiseSpeed * g.cruiseSpeed / 0.16;
        }

        void fly(float yawIn, float pitchIn, boolean fire, float dy) {
            float yaw = MathHelper.wrapDegrees(yawIn);
            float pitch = MathHelper.clamp(pitchIn, -89, 89);
            var path = ElytraPhysics.predict(world, pos, vel, yaw, pitch, HORIZON,
                    fire ? ElytraPhysics.ROCKET_MIN_TICKS : rocketLeft);
            Vec3d[] pts = path.points();
            if (path.hit()) {
                // Kept only as a last resort: the one that hits latest, preferring a rocket.
                if (pts.length > latestLen || pts.length == latestLen && fire) {
                    latestLen = pts.length;
                    latest = new Plan(yaw, pitch, fire, -1e9, pts, List.of(), true);
                }
                return;
            }
            Vec3d end = pts[pts.length - 1];
            Vec3d endVel = pts.length > 1 ? end.subtract(pts[pts.length - 2]) : vel;
            double progress = d0 - Math.hypot(g.point.x - end.x, g.point.z - end.z);
            double heightErr = Math.abs(g.point.y - end.y);
            // Short of the height and speed a cruise wants: what a glide cannot make up. More than
            // that earns nothing, or it buys height with rockets nobody needs (it climbed 74 blocks
            // over a flat world on the first try).
            double deficit = Math.max(0, eWant - energy(endVel, end.y));
            double score = progress - g.heightWeight * heightErr - 0.8 * deficit
                    - (g.through == null ? 0 : g.throughWeight * closest(pts, g.through))
                    - (fire ? g.rocketCost : 0) - crowding(world, pts, g.clearance)
                    - Math.abs(dy) * 0.02; // straight on when it is all the same
            Plan p = new Plan(yaw, pitch, fire, score, pts, List.of(), false);
            all.add(p);
            if (best == null || score > best.score()) best = p;
        }
    }

    /** How close the path comes to the point (segments, not just the ticks: 1.7 blocks apart). */
    private static double closest(Vec3d[] pts, Vec3d c) {
        double best = pts[0].distanceTo(c);
        for (int i = 1; i < pts.length; i++) {
            Vec3d a = pts[i - 1].add(0, 0.3, 0), ab = pts[i].subtract(pts[i - 1]);
            double l2 = ab.lengthSquared();
            double t = l2 < 1e-9 ? 0 : MathHelper.clamp(c.subtract(a).dotProduct(ab) / l2, 0, 1);
            best = Math.min(best, a.add(ab.multiply(t)).distanceTo(c));
        }
        return best;
    }

    /** Height plus the height the speed would climb (v^2 / 2g, g = 0.08 blocks/tick^2): what a
     *  glide can still turn into distance, in blocks. */
    private static double energy(Vec3d v, double y) {
        return y + v.lengthSquared() / 0.16;
    }

    /** Penalty for flying within the clearance of blocks: every fourth point is checked with a
     *  grown box. */
    private static double crowding(BlockView world, Vec3d[] pts, double clearance) {
        double pen = 0;
        for (int i = 3; i < pts.length; i += 4) {
            Vec3d p = pts[i];
            if (ElytraPhysics.blocked(world, p.add(clearance, 0, 0)) || ElytraPhysics.blocked(world, p.add(-clearance, 0, 0))
                    || ElytraPhysics.blocked(world, p.add(0, 0, clearance)) || ElytraPhysics.blocked(world, p.add(0, 0, -clearance))
                    || ElytraPhysics.blocked(world, p.add(0, -clearance, 0))) {
                pen += 6.0 * (pts.length - i) / pts.length; // near ones cost more
            }
        }
        return pen;
    }
}
