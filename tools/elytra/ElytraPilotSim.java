import adris.altoclef.util.agent.ElytraPilot;

/**
 * Offline test bench for ElytraPilot: vanilla 1.21 glide physics (LivingEntity
 * calcGlidingVelocity) and firework boost (FireworkRocketEntity), over a few terrains.
 *
 *   javac -d out ../../src/main/java/adris/altoclef/util/agent/ElytraPilot.java ElytraPilotSim.java
 *   java -cp out ElytraPilotSim [trace]
 */
public class ElytraPilotSim {
    static final int TRACE_EVERY = Integer.getInteger("every", 10);

    interface Terrain { double h(double x, double z); }

    /** tricks: figures to fly on the way; via: waypoints to fly through before the target. */
    record Scene(String name, double tx, double tz, double ty, Terrain t, boolean low, int tricks, double[][] via) {
        Scene(String name, double tx, double tz, double ty, Terrain t) { this(name, tx, tz, ty, t, false, 0, new double[0][]); }
        Scene(String name, double tx, double tz, double ty, Terrain t, boolean low) { this(name, tx, tz, ty, t, low, 0, new double[0][]); }
        Scene at(double[] p) { return new Scene(name, p[0], p[1], t.h(p[0], p[1]), t, low, tricks, via); }
    }

    public static void main(String[] args) {
        String only = args.length > 0 ? args[0] : null;
        long traceSeed = args.length > 1 ? Long.parseLong(args[1]) : -1;
        Scene[] scenes = {
            new Scene("flat 300", 300, 0, 70, (x, z) -> 70),
            new Scene("short 70", 50, 50, 70, (x, z) -> 70),
            // The first live flight: ~125 blocks over a town (roofs up to 12 high).
            new Scene("medium 125 town", -85, -64, 70, (x, z) -> 70 + ((((int) Math.floor(x / 9)) * 31 + ((int) Math.floor(z / 9)) * 17) & 7) * 1.5),
            new Scene("hill 55 high mid-way", 400, 0, 70, (x, z) -> 70 + 55 * Math.max(0, 1 - Math.abs(x - 200) / 40)),
            new Scene("target 40 lower", 250, -100, 30, (x, z) -> 70 - 40 * Math.min(1, Math.max(0, (x - 120) / 60))),
            new Scene("target 30 higher", 200, 150, 100, (x, z) -> 70 + 30 * Math.min(1, Math.max(0, (x - 60) / 60))),
            new Scene("behind, u-turn", -180, 20, 70, (x, z) -> 70),
            // A 3x3 tower 60 high right on the course: thinner than the old 8-block sampling step.
            new Scene("thin tower", 300, 3, 70, (x, z) -> Math.abs(x - 150) <= 1 && Math.abs(z - 1.5) <= 1 ? 130 : 70),
            // A bridge deck at y 84..86 across the course, open below: invisible to a heightmap
            // under it, only its top shows -- the climb must start before the deck, not under it.
            new Scene("bridge deck", 260, 0, 70, (x, z) -> Math.abs(x - 130) <= 3 ? 86 : 70),
            new Scene("long 900", 0, 900, 70, (x, z) -> 70 + 15 * Math.sin(z / 60)),
            // Low-level flight (бреющий): rolling hills, a town, a 25-block cliff up and down.
            new Scene("low hills 500", 500, 0, 70, (x, z) -> x < 40 || x > 460 ? 70 : 70 + 10 * Math.sin((x - 40) / 35) + 6 * Math.sin((x - 40) / 13 + z / 20), true),
            new Scene("low town 300", 300, 40, 70, (x, z) -> x < 40 || x > 270 ? 70 : 70 + ((((int) Math.floor(x / 9)) * 31 + ((int) Math.floor(z / 9)) * 17) & 7) * 1.5, true),
            new Scene("low cliff 400", 400, 0, 70, (x, z) -> x > 150 && x < 260 ? 95 : 70, true),
            // Aerobatics on a long flight over rolling ground; a route round a square and back.
            new Scene("figures 1600", 1600, 0, 70, (x, z) -> 70 + 12 * Math.sin(x / 90), false, 4, new double[0][]),
            new Scene("route square", 0, 0, 70, (x, z) -> 70 + 8 * Math.sin(x / 70) + 6 * Math.cos(z / 55), false, 0,
                    new double[][] {{260, 0}, {260, 260}, {0, 260}}),
        };
        int fails = 0;
        for (Scene s : scenes) {
            if (only != null && !s.name.startsWith(only)) continue;
            if (traceSeed >= 0) {
                System.out.println(run(s, true, traceSeed));
                continue;
            }
            // Rocket burn time is random (20..31 ticks): judge the worst of many runs, not one.
            int ok = 0;
            double sumT = 0, maxT = 0, worstClear = 1e9, sumR = 0, maxApp = 0, worstMean = 0;
            int fewestFigures = Integer.MAX_VALUE;
            String firstFail = "";
            for (long seed = 0; seed < 20; seed++) {
                Result r = run(s, false, seed);
                if (r.ok) ok++; else if (firstFail.isEmpty()) firstFail = "seed " + seed + ": " + r.verdict;
                sumT += r.seconds; maxT = Math.max(maxT, r.seconds); sumR += r.rockets;
                worstClear = Math.min(worstClear, r.minClear);
                maxApp = Math.max(maxApp, r.approach);
                worstMean = Math.max(worstMean, r.meanClear);
                fewestFigures = Math.min(fewestFigures, r.figures);
            }
            // Figures: at least three of four flown, and every flight still lands.
            if (s.tricks > 0 && fewestFigures < s.tricks - 1) {
                ok = Math.min(ok, 19);
                firstFail = "only " + fewestFigures + " figures";
            }
            // Low-level: well under a normal cruise over the same ground (20-27 on average there).
            if (s.low && worstMean > 13) {
                ok = Math.min(ok, 19);
                firstFail = String.format("not low-level: mean %.1f min %.1f", worstMean, worstClear);
            }
            if (ok < 20) fails++;
            System.out.printf("%-22s ok %2d/20  time avg %4.1fs max %4.1fs  last 25 blocks max %4.1fs  rockets %4.1f  clearance %5.1f%s  %s%n",
                    s.name, ok, sumT / 20, maxT, maxApp, sumR / 20, worstClear,
                    s.low ? String.format(" (mean %.1f)", worstMean) : s.tricks > 0 ? " figures >= " + fewestFigures : "",
                    firstFail);
        }
        if (only == null || "follow".startsWith(only)) {
            int ok = 0;
            double worstMean = 0, worstMax = 0, sumR = 0;
            for (long seed = 0; seed < 20; seed++) {
                double[] r = runFollow(seed, traceSeed == seed);
                if (r[0] < 12 && r[1] < 45 && r[3] > 0) ok++;
                worstMean = Math.max(worstMean, r[0]);
                worstMax = Math.max(worstMax, r[1]);
                sumR += r[2];
            }
            if (ok < 20) fails++;
            System.out.printf("%-22s ok %2d/20  gap to slot: worst mean %4.1f, worst max %4.1f  rockets %4.1f%n",
                    "follow a leader", ok, worstMean, worstMax, sumR / 20);
        }
        System.out.println(fails == 0 ? "ALL OK" : fails + " scene(s) failed");
    }

    /** Leader flight: 10 s straight, a left turn, a climb, a glide down; flat ground at 70.
     *  Returns {mean gap to the slot, max gap, rockets, 1 if never touched the ground else 0}. */
    static double[] runFollow(long seed, boolean trace) {
        java.util.Random rnd = new java.util.Random(seed);
        ElytraPilot pilot = new ElytraPilot();
        double x = -12, y = 100, z = 3, vx = 1.2, vy = 0, vz = 0;
        double lx = 0, ly = 100, lz = 0, heading = 0;
        pilot.resetForLanding(-90, 0);
        int rocketLeft = 0, rockets = 0;
        double sumGap = 0, maxGap = 0;
        int n = 0;
        boolean clean = true;
        for (int t = 0; t < 20 * 40; t++) {
            // Leader script.
            double sp = 1.4, lvy = 0;
            if (t >= 200 && t < 300) heading += Math.PI / 2 / 100;    // quarter turn left in 5 s
            if (t >= 300 && t < 450) lvy = 0.35;                       // climb
            if (t >= 550) { lvy = -0.25; sp = 1.2; }                   // glide down
            if (ly + lvy < 80) lvy = 0;                                // levels off 10 over the ground
            double lvx = Math.cos(heading) * sp, lvz = Math.sin(heading) * sp;
            lx += lvx; ly += lvy; lz += lvz;

            double obstacle = y + vy * 30 < 70 ? Math.max(1, (y - 70) / Math.max(-vy, 0.01)) : Double.POSITIVE_INFINITY;
            ElytraPilot.Out o = pilot.stepFollow(x, y, z, vx, vy, vz, lx, ly, lz, lvx, lvy, lvz, 70, 70, obstacle);
            if (o.boost) { rocketLeft = 20 + rnd.nextInt(6) + rnd.nextInt(7); rockets++; }
            double p = Math.toRadians(o.pitch), w = Math.toRadians(o.yaw);
            double ex = -Math.sin(w) * Math.cos(p), ey = -Math.sin(p), ez = Math.cos(w) * Math.cos(p);
            if (rocketLeft > 0) {
                rocketLeft--;
                vx += ex * 0.1 + (ex * 1.5 - vx) * 0.5;
                vy += ey * 0.1 + (ey * 1.5 - vy) * 0.5;
                vz += ez * 0.1 + (ez * 1.5 - vz) * 0.5;
            }
            double d = Math.sqrt(ex * ex + ez * ez), e = Math.hypot(vx, vz), h = Math.cos(p) * Math.cos(p);
            vy += 0.08 * (-1.0 + h * 0.75);
            if (vy < 0 && d > 0) { double i = vy * -0.1 * h; vx += ex * i / d; vy += i; vz += ez * i / d; }
            if (p < 0 && d > 0) { double i = e * -Math.sin(p) * 0.04; vx += -ex * i / d; vy += i * 3.2; vz += -ez * i / d; }
            if (d > 0) { vx += (ex / d * e - vx) * 0.1; vz += (ez / d * e - vz) * 0.1; }
            vx *= 0.99; vy *= 0.98; vz *= 0.99;
            x += vx; y += vy; z += vz;
            if (y <= 70) clean = false;

            double lh = Math.max(Math.hypot(lvx, lvz), 1e-6);
            double ux = lvx / lh, uz = lvz / lh;
            double sx = lx - ux * 2 - uz * 5, sz = lz - uz * 2 + ux * 5, sy = ly + 1;
            double gap = Math.sqrt((sx - x) * (sx - x) + (sy - y) * (sy - y) + (sz - z) * (sz - z));
            if (t > 100) { sumGap += gap; n++; maxGap = Math.max(maxGap, gap); } // after joining up
            if (trace && t % 20 == 0) {
                double al = (x - sx) * ux + (z - sz) * uz, cr = (x - sx) * -uz + (z - sz) * ux;
                System.out.printf("  t=%3d ahead=%6.1f right=%6.1f up=%5.1f  gap=%5.1f v=%.2f lead v=%.2f pitch=%5.1f yaw-course=%5.0f %s%s%n",
                        t, al, cr, y - sy, gap, o.speed, Math.hypot(lvx, lvz), o.pitch,
                        wrap((float) (o.yaw - Math.toDegrees(Math.atan2(-ux, uz)))), o.mode, o.boost ? " ROCKET" : "");
            }
        }
        return new double[] {sumGap / n, maxGap, rockets, clean ? 1 : 0};
    }

    record Result(boolean ok, String verdict, double seconds, int rockets, double minClear, double end, double approach,
                  double meanClear, int figures) {}

    static Result run(Scene scene, boolean trace, long seed) {
        Scene s = scene;
        int leg = 0, legs = scene.via.length; // waypoints passed
        java.util.Random rnd = new java.util.Random(seed);
        ElytraPilot pilot = new ElytraPilot();
        double sumClear = 0;
        int nClear = 0;
        double x = 0, z = 0, y = s.t.h(0, 0) + 1.6;
        double vx = 0, vy = -0.05, vz = 0;
        float yaw0 = (float) Math.toDegrees(Math.atan2(-(s.tx - x), s.tz - z));
        pilot.reset(yaw0 + 30, 0); // a player rarely faces the target exactly
        pilot.setLowLevel(s.low && !Boolean.getBoolean("nolow"));
        pilot.setTricks(s.tricks);
        int rocketLeft = 0, rockets = 0, ticks = 0;
        double minClear = 1e9, maxDPitch = 0, maxDYaw = 0;
        float lastPitch = 0, lastYaw = yaw0 + 30;
        String verdict = "timeout";
        java.util.Set<String> modes = new java.util.LinkedHashSet<>();
        int near = -1; // first tick within 25 blocks: the approach should not drag on from there
        boolean ok = false;
        for (; ticks < 20 * 180; ticks++) {
            s = leg < legs ? scene.at(scene.via[leg]) : scene; // as the mod: the next waypoint, then the spot
            pilot.setPassThrough(leg < legs);
            double ahead = terrainAhead(s, x, z, vx, vz);
            if (s.low && !Boolean.getBoolean("nolow")) pilot.setLowGuide(lowGuide(s, x, y, z, vx, vz));
            double ground = s.t.h(x, z);
            // The landing height is the ground at the target, as the mod reads it from the heightmap.
            ElytraPilot.Out o = pilot.step(x, y, z, vx, vy, vz, s.tx, s.t.h(s.tx, s.tz), s.tz, ahead, ground,
                    runwayClear(s, x, z), obstacle(s, x, y, z, vx, vy, vz));
            if (o.mode != null) modes.add(o.mode.name());
            maxDPitch = Math.max(maxDPitch, Math.abs(o.pitch - lastPitch));
            maxDYaw = Math.max(maxDYaw, Math.abs(wrap(o.yaw - lastYaw)));
            lastPitch = o.pitch;
            lastYaw = o.yaw;
            if (o.boost) {
                rocketLeft = 20 + rnd.nextInt(6) + rnd.nextInt(7);
                rockets++;
            }
            double p = Math.toRadians(o.pitch), w = Math.toRadians(o.yaw);
            double lx = -Math.sin(w) * Math.cos(p), ly = -Math.sin(p), lz = Math.cos(w) * Math.cos(p);
            if (rocketLeft > 0) {
                rocketLeft--;
                vx += lx * 0.1 + (lx * 1.5 - vx) * 0.5;
                vy += ly * 0.1 + (ly * 1.5 - vy) * 0.5;
                vz += lz * 0.1 + (lz * 1.5 - vz) * 0.5;
            }
            // LivingEntity.calcGlidingVelocity (1.21.2+), gravity 0.08.
            double d = Math.sqrt(lx * lx + lz * lz);
            double e = Math.hypot(vx, vz);
            double h = Math.cos(p) * Math.cos(p);
            vy += 0.08 * (-1.0 + h * 0.75);
            if (vy < 0 && d > 0) {
                double i = vy * -0.1 * h;
                vx += lx * i / d; vy += i; vz += lz * i / d;
            }
            if (p < 0 && d > 0) {
                double i = e * -Math.sin(p) * 0.04;
                vx += -lx * i / d; vy += i * 3.2; vz += -lz * i / d;
            }
            if (d > 0) {
                vx += (lx / d * e - vx) * 0.1;
                vz += (lz / d * e - vz) * 0.1;
            }
            vx *= 0.99; vy *= 0.98; vz *= 0.99;
            x += vx; y += vy; z += vz;

            double dist = Math.hypot(s.tx - x, s.tz - z);
            if (leg < legs && dist < 20) leg++; // waypoint passed
            double clear = y - s.t.h(x, z);
            if (near < 0 && dist < 25) near = ticks;
            if (dist > 20 && ticks > 30) minClear = Math.min(minClear, clear);
            if (o.mode == ElytraPilot.Mode.CRUISE) { sumClear += clear; nClear++; } // the cruise, not climb-out or landing
            if (trace && ticks % TRACE_EVERY == 0) {
                System.out.printf("  t=%4d %-7s pos=%6.0f %5.1f %6.0f dist=%5.0f clear=%5.1f v=%.2f gam=%5.1f cmd=%5.1f pitch=%5.1f%s%n",
                        ticks, o.mode, x, y, z, dist, clear, o.speed, o.gamma, o.gammaCmd, o.pitch, o.boost ? " ROCKET" : "");
            }
            if (o.arrived) { verdict = "arrived"; ok = true; break; }
            if (clear <= 0) {
                double vh = Math.hypot(vx, vz);
                // On the ground the wings close; walking friction (0.6 * 0.91) rolls it out.
                double rx = x, rz = z, rvx = vx, rvz = vz;
                while (Math.hypot(rvx, rvz) > 0.01) { rx += rvx; rz += rvz; rvx *= 0.546; rvz *= 0.546; }
                double rolled = Math.hypot(s.tx - rx, s.tz - rz);
                // A step on the roll-out hurts only above ~0.3 of horizontal speed (elytra wall damage).
                if (leg >= legs && rolled < 30 && vy > -0.5 && (s.t.h(rx, rz) - s.t.h(x, z) < 1 || vh < 0.4)) {
                    verdict = String.format("touchdown vh=%.2f vy=%.2f", vh, vy);
                    ok = true;
                    dist = rolled;
                    x = rx; z = rz;
                } else verdict = String.format("hit ground dist=%.0f vy=%.2f vh=%.2f", dist, vy, vh);
                break;
            }
        }
        double dist = Math.hypot(s.tx - x, s.tz - z);
        if (trace) System.out.println("  modes: " + modes);
        return new Result(ok && leg >= legs, verdict, ticks / 20.0, rockets, minClear, dist, near < 0 ? 0 : (ticks - near) / 20.0,
                nClear == 0 ? 0 : sumClear / nClear, pilot.tricksDone());
    }

    /** Nothing standing into a 7-degree slope (1.5 blocks margin) on the last 50 blocks before the target. */
    static boolean runwayClear(Scene s, double x, double z) {
        double dx = x - s.tx, dz = z - s.tz, d = Math.max(Math.hypot(dx, dz), 1e-6);
        double ty = s.t.h(s.tx, s.tz), tan = Math.tan(Math.toRadians(7));
        for (double k = 2; k <= Math.min(50, d); k += 2) {
            double h = s.t.h(s.tx + dx / d * k, s.tz + dz / d * k);
            // Level ground is the runway itself; anything a block up, or into the slope, is not.
            if (h > ty + 0.9 && h > ty + k * tan - 1.5) return false;
        }
        return true;
    }

    /** As the mod: highest ground in a 5-block corridor every 2 blocks, 120 along the line to the
     *  target and 60 along the current direction of flight (turns fly arcs), and the target. */
    static double terrainAhead(Scene s, double x, double z, double vx, double vz) {
        if (s.low && !Boolean.getBoolean("nolow")) { // as the mod in low-level flight: only the next second or so of the course
            double dist = Math.hypot(s.tx - x, s.tz - z), reach = Math.max(16, Math.hypot(vx, vz) * 25);
            double m = corridor(s, x, z, s.tx - x, s.tz - z, Math.min(reach, dist));
            if (Math.hypot(vx, vz) > 0.2) m = Math.max(m, corridor(s, x, z, vx, vz, reach));
            return dist < 60 ? Math.max(m, s.t.h(s.tx, s.tz)) : m;
        }
        double m = s.t.h(s.tx, s.tz);
        m = Math.max(m, corridor(s, x, z, s.tx - x, s.tz - z, Math.min(120, Math.hypot(s.tx - x, s.tz - z))));
        if (Math.hypot(vx, vz) > 0.2) m = Math.max(m, corridor(s, x, z, vx, vz, 60));
        return m;
    }

    /** As the mod: the steepest angle passing LOW_CLEARANCE (4) over the ground just ahead. */
    static double lowGuide(Scene s, double x, double y, double z, double vx, double vz) {
        double hs = Math.hypot(vx, vz), dx = hs > 0.2 ? vx : s.tx - x, dz = hs > 0.2 ? vz : s.tz - z;
        double d = Math.max(Math.hypot(dx, dz), 1e-6), ux = dx / d, uz = dz / d;
        double reach = Math.max(16, hs * 25), best = -90;
        for (double k = 2; k <= reach; k += 2) {
            double g = -1e9;
            for (double w = -2; w <= 2; w += 2) g = Math.max(g, s.t.h(x + ux * k - uz * w, z + uz * k + ux * w));
            best = Math.max(best, Math.toDegrees(Math.atan2(g + 4 - y, k)));
        }
        return best;
    }

    static double corridor(Scene s, double x, double z, double dx, double dz, double len) {
        double d = Math.max(Math.hypot(dx, dz), 1e-6), ux = dx / d, uz = dz / d, m = -1e9;
        for (double k = 0; k <= len; k += 2) {
            for (double w = -2; w <= 2; w += 2) {
                m = Math.max(m, s.t.h(x + ux * k - uz * w, z + uz * k + ux * w));
            }
        }
        return m;
    }

    /** As the mod's ray along the velocity: distance to the first block, landing ground excluded. */
    static double obstacle(Scene s, double x, double y, double z, double vx, double vy, double vz) {
        double sp = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (sp < 0.05) return Double.POSITIVE_INFINITY;
        double ty = s.t.h(s.tx, s.tz), look = Math.max(12, sp * 30);
        for (double k = 0.5; k <= look; k += 0.5) {
            double px = x + vx / sp * k, py = y + vy / sp * k, pz = z + vz / sp * k;
            if (py <= s.t.h(px, pz)) {
                boolean landingGround = py <= ty + 1.5 && Math.hypot(s.tx - px, s.tz - pz) < 30;
                return landingGround ? Double.POSITIVE_INFINITY : k;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    static float wrap(float a) {
        a %= 360;
        if (a >= 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }
}
