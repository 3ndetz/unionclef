package adris.altoclef.util.agent;

import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.elytra.ElytraPhysics;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import kaptainwutax.tungsten.render.Line;
import kaptainwutax.tungsten.render.Renderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws what a flight is doing, in tungsten's FLIGHT layer (";settings renderFlight false" hides it):
 * <ul>
 *   <li>the target (green box) and a route's waypoints (cyan, the current one larger);</li>
 *   <li>the flown trail, coloured by the pilot's mode at that moment;</li>
 *   <li>where the glide goes in the next two seconds with the controls as they are, a rocket
 *       counted while it still pulls (yellow, orange under a rocket, a red box where it would hit);</li>
 *   <li>a mark where each rocket was fired (orange), so the spend is seen;</li>
 *   <li>following: a line to the leader and the slot it keeps beside them (magenta).</li>
 * </ul>
 * The trail and the rocket marks stay half a minute after the flight for screenshots.
 */
public final class FlightViz {

    private static final int TRAIL_MAX = 200, ROCKETS_MAX = 64, KEEP_TICKS = 600, PREDICT_TICKS = 40;

    private record Pt(Vec3d at, Color color) {
    }

    private static final ArrayDeque<Pt> trail = new ArrayDeque<>();
    private static final ArrayDeque<Vec3d> rockets = new ArrayDeque<>();
    private static int sinceFlight = KEEP_TICKS + 1, tick;

    private static final Color TARGET = new Color(60, 230, 90), ROUTE = new Color(60, 210, 230),
            PREDICT = new Color(250, 220, 60), BOOSTED = new Color(255, 140, 30), HIT = new Color(255, 50, 50),
            LEADER = new Color(230, 70, 230), HOVER = new Color(120, 170, 255), OTHER = new Color(150, 150, 150);

    private FlightViz() {
    }

    /** A new flight starts: the old trail and rocket marks go. */
    static synchronized void newFlight() {
        trail.clear();
        rockets.clear();
        sinceFlight = 0;
    }

    static synchronized void rocket(double x, double y, double z) {
        rockets.addLast(new Vec3d(x, y, z));
        while (rockets.size() > ROCKETS_MAX) rockets.removeFirst();
    }

    static Color modeColor(ElytraPilot.Mode m) {
        if (m == null) return Color.WHITE;
        return switch (m) {
            case CLIMB -> new Color(80, 230, 80);
            case CRUISE -> Color.WHITE;
            case DESCENT, FINAL -> new Color(250, 220, 60);
            case FLARE, SINK -> new Color(255, 140, 30);
            case AVOID -> new Color(255, 50, 50);
            case FOLLOW -> LEADER;
            case TRICK -> ROUTE;
        };
    }

    static synchronized void tick(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        if (p == null || c.world == null) return;
        ElytraFlight.View f = ElytraFlight.view();
        boolean hover = CreativeWingman.active();
        boolean flying = f.on() || hover;
        if (flying) sinceFlight = 0;
        else if (++sinceFlight > KEEP_TICKS) {
            if (!trail.isEmpty() || !rockets.isEmpty()) newFlight();
            sinceFlight = KEEP_TICKS + 1;
            synchronized (TungstenModRenderContainer.FLIGHT) {
                TungstenModRenderContainer.FLIGHT.clear();
            }
            return;
        }
        if (++tick % 2 != 0) return; // ten frames of drawing a second are plenty
        Vec3d here = new Vec3d(p.getX(), p.getY() + 0.3, p.getZ());
        if (flying) {
            Color col = hover ? HOVER : modeColor(f.mode());
            Pt last = trail.peekLast();
            if (last == null || last.at().squaredDistanceTo(here) > 0.25) trail.addLast(new Pt(here, col));
            while (trail.size() > TRAIL_MAX) trail.removeFirst();
        }

        List<Renderer> out = new ArrayList<>();
        Pt prev = null;
        for (Pt t : trail) {
            if (prev != null) out.add(new Line(prev.at(), t.at(), t.color()));
            prev = t;
        }
        for (Vec3d r : rockets) out.add(box(r, 0.5, BOOSTED));

        if (f.on()) {
            double[][] route = f.route();
            if (route != null) {
                for (int i = 0; i < route.length; i++) {
                    Vec3d w = new Vec3d(route[i][0], route[i][1], route[i][2]);
                    out.add(box(w, i == f.routeIdx() ? 1.6 : 0.8, i < f.routeIdx() ? Color.WHITE : ROUTE));
                    if (i > 0) out.add(new Line(new Vec3d(route[i - 1][0], route[i - 1][1], route[i - 1][2]), w, ROUTE));
                }
            }
            var ap = f.airPlan();
            if (ap != null) { // the planned flight ahead, and where it fires its rockets
                int from = Math.max(0, f.airPlanIdx());
                for (int i = from + 2; i < ap.size(); i += 2) {
                    out.add(new Line(ap.get(i - 2).pos().add(0, 0.3, 0), ap.get(i).pos().add(0, 0.3, 0), ROUTE));
                }
                for (int i = from; i < ap.size(); i++) {
                    if (ap.get(i).fire()) out.add(box(ap.get(i).pos().add(0, 0.3, 0), 0.9, BOOSTED));
                }
            }
            if (f.leader() == null) {
                out.add(new Cuboid(new Vec3d(f.tx() - 0.5, f.ty(), f.tz() - 0.5), new Vec3d(1, 2, 1), TARGET));
            }
            if (adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p)) {
                int pull = Math.max(0, ElytraPhysics.ROCKET_MIN_TICKS - f.ticksSinceRocket());
                var path = ElytraPhysics.predict(c.world, new Vec3d(p.getX(), p.getY(), p.getZ()), p.getVelocity(),
                        p.getYaw(), p.getPitch(), PREDICT_TICKS, pull);
                Vec3d[] pts = path.points();
                for (int i = 1; i < pts.length; i++) {
                    out.add(new Line(pts[i - 1].add(0, 0.3, 0), pts[i].add(0, 0.3, 0), i <= pull ? BOOSTED : PREDICT));
                }
                if (path.hit()) out.add(box(pts[pts.length - 1].add(0, 0.3, 0), 0.8, HIT));
                var plan = f.plan();
                if (plan != null) { // the solver's runners-up, grey: what it weighed against
                    for (Vec3d[] alt : plan.others()) {
                        for (int i = 2; i < alt.length; i += 2) {
                            out.add(new Line(alt[i - 2].add(0, 0.3, 0), alt[i].add(0, 0.3, 0), OTHER));
                        }
                    }
                }
            }
        }
        String lead = f.on() && f.leader() != null ? f.leader() : hover ? CreativeWingman.leaderName() : null;
        if (lead != null) {
            for (var pl : c.world.getPlayers()) {
                if (pl != p && pl.getName().getString().equalsIgnoreCase(lead)) {
                    out.add(new Line(here, new Vec3d(pl.getX(), pl.getY() + 0.9, pl.getZ()), LEADER));
                    break;
                }
            }
            Vec3d slot = CreativeWingman.slot();
            if (hover && slot != null) out.add(new Cuboid(slot.add(-0.3, 0, -0.3), new Vec3d(0.6, 1.8, 0.6), LEADER));
        }
        if (hover) {
            double[] pt = CreativeWingman.pointTo();
            if (pt != null) {
                double y = c.world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING,
                        (int) Math.floor(pt[0]), (int) Math.floor(pt[1]));
                out.add(new Cuboid(new Vec3d(pt[0] - 0.5, y, pt[1] - 0.5), new Vec3d(1, 2, 1), TARGET));
            }
        }
        synchronized (TungstenModRenderContainer.FLIGHT) {
            TungstenModRenderContainer.FLIGHT.clear();
            TungstenModRenderContainer.FLIGHT.addAll(out);
        }
    }

    /** A cube of side s centred on the point. */
    private static Cuboid box(Vec3d at, double s, Color color) {
        return new Cuboid(at.add(-s / 2, -s / 2, -s / 2), new Vec3d(s, s, s), color);
    }
}
