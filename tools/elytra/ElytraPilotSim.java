import adris.altoclef.util.agent.ElytraPilot;

/**
 * Offline test bench for ElytraPilot: vanilla 1.21 glide physics (LivingEntity
 * calcGlidingVelocity) and firework boost (FireworkRocketEntity), over a few terrains.
 *
 *   javac -d out ../../src/main/java/adris/altoclef/util/agent/ElytraPilot.java ElytraPilotSim.java
 *   java -cp out ElytraPilotSim [trace]
 */
public class ElytraPilotSim {

    interface Terrain { double h(double x, double z); }

    record Scene(String name, double tx, double tz, double ty, Terrain t) {}

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
            new Scene("long 900", 0, 900, 70, (x, z) -> 70 + 15 * Math.sin(z / 60)),
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
            double sumT = 0, maxT = 0, worstClear = 1e9, sumR = 0, maxApp = 0;
            String firstFail = "";
            for (long seed = 0; seed < 20; seed++) {
                Result r = run(s, false, seed);
                if (r.ok) ok++; else if (firstFail.isEmpty()) firstFail = "seed " + seed + ": " + r.verdict;
                sumT += r.seconds; maxT = Math.max(maxT, r.seconds); sumR += r.rockets;
                worstClear = Math.min(worstClear, r.minClear);
                maxApp = Math.max(maxApp, r.approach);
            }
            if (ok < 20) fails++;
            System.out.printf("%-22s ok %2d/20  time avg %4.1fs max %4.1fs  last 25 blocks max %4.1fs  rockets %4.1f  clearance %5.1f  %s%n",
                    s.name, ok, sumT / 20, maxT, maxApp, sumR / 20, worstClear, firstFail);
        }
        System.out.println(fails == 0 ? "ALL OK" : fails + " scene(s) failed");
    }

    record Result(boolean ok, String verdict, double seconds, int rockets, double minClear, double end, double approach) {}

    static Result run(Scene s, boolean trace, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        ElytraPilot pilot = new ElytraPilot();
        double x = 0, z = 0, y = s.t.h(0, 0) + 1.6;
        double vx = 0, vy = -0.05, vz = 0;
        float yaw0 = (float) Math.toDegrees(Math.atan2(-(s.tx - x), s.tz - z));
        pilot.reset(yaw0 + 30, 0); // a player rarely faces the target exactly
        int rocketLeft = 0, rockets = 0, ticks = 0;
        double minClear = 1e9, maxDPitch = 0, maxDYaw = 0;
        float lastPitch = 0, lastYaw = yaw0 + 30;
        String verdict = "timeout";
        java.util.Set<String> modes = new java.util.LinkedHashSet<>();
        int near = -1; // first tick within 25 blocks: the approach should not drag on from there
        boolean ok = false;
        for (; ticks < 20 * 180; ticks++) {
            double ahead = terrainAhead(s, x, z);
            double ground = s.t.h(x, z);
            // The landing height is the ground at the target, as the mod reads it from the heightmap.
            ElytraPilot.Out o = pilot.step(x, y, z, vx, vy, vz, s.tx, s.t.h(s.tx, s.tz), s.tz, ahead, ground,
                    runwayClear(s, x, z));
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
            double clear = y - s.t.h(x, z);
            if (near < 0 && dist < 25) near = ticks;
            if (dist > 20 && ticks > 30) minClear = Math.min(minClear, clear);
            if (trace && ticks % 10 == 0) {
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
                if (rolled < 30 && vy > -0.5 && s.t.h(rx, rz) - s.t.h(x, z) < 1) {
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
        return new Result(ok, verdict, ticks / 20.0, rockets, minClear, dist, near < 0 ? 0 : (ticks - near) / 20.0);
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

    /** What the mod samples: max ground height every 8 blocks along the course, up to 80 ahead, and the target. */
    static double terrainAhead(Scene s, double x, double z) {
        double dx = s.tx - x, dz = s.tz - z, dist = Math.hypot(dx, dz);
        double m = s.t.h(s.tx, s.tz);
        for (double k = 0; k <= Math.min(120, dist); k += 8) {
            m = Math.max(m, s.t.h(x + dx / dist * k, z + dz / dist * k));
        }
        return m;
    }

    static float wrap(float a) {
        a %= 360;
        if (a >= 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }
}
