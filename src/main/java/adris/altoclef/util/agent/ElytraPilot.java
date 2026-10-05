package adris.altoclef.util.agent;

/**
 * Flies an elytra the way a player (or a plane) does: it holds a flight-path angle rather than
 * snapping the nose to fixed pitches, turns and pitches at a limited rate, uses fireworks as a
 * throttle, climbs ahead of terrain and lands on a glide slope with a flare.
 *
 * <p>Pure math, no Minecraft classes, so the same code runs against a copy of the vanilla glide
 * physics offline (see ElytraPilotSim in the agent repo's tools) and in the client every tick.
 * Angles in degrees, Minecraft convention: pitch &lt; 0 is nose up; flight-path angle &gt; 0 is
 * climbing.
 */
public final class ElytraPilot {

    public enum Mode { CLIMB, CRUISE, DESCENT, FINAL, FLARE, SINK, AVOID, FOLLOW }

    public static final class Out {
        public float yaw, pitch;
        public boolean boost;
        public boolean arrived;
        public Mode mode;
        public double gamma, gammaCmd, speed;
    }

    // Tuning, all found against the vanilla physics (ElytraPilotSim).
    static final double CLEARANCE = 20;          // cruise this high above the terrain ahead
    static final double GLIDE_SLOPE = 14;        // degrees of a steep approach (obstacles before the spot)
    static final double PLANE_SLOPE = 7;         // degrees of a plane-like approach over clear ground
    static final double MAX_YAW_RATE = 7;        // degrees per tick
    static final double MAX_PITCH_RATE = 4.5;
    static final double K_GAMMA = 1.3;           // pitch per degree of flight-path error
    static final double K_INT = 0.03;
    static final double ROCKET_TICKS = 22;       // a flight-1 rocket pushes for 20..31 ticks

    private float yaw, pitch;
    private double integral;
    private int boostCooldown;
    private Mode mode = Mode.CLIMB;
    private int climbOut;
    private int weaveTicks;

    public void reset(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        integral = 0;
        boostCooldown = 0;
        mode = Mode.CLIMB;
        climbOut = 30;
    }

    /** Take over a glide already in the air (a fall, a stopped flight): no climb-out. */
    public void resetForLanding(float yaw, float pitch) {
        reset(yaw, pitch);
        climbOut = 0;
        mode = Mode.DESCENT;
    }

    public Mode mode() { return mode; }

    /**
     * One tick.
     * @param terrainAhead highest ground on the next ~80 blocks of the course (and at the target)
     * @param groundBelow  ground height right under the bot
     * @param runwayClear  nothing sticks up into a shallow approach over the last ~50 blocks: land
     *                     like a plane (long glide, round-out, touch down rolling); else steep
     *                     approach and a pull-up over the spot
     * @param obstacleDist distance to the first solid block along the flight direction (a ray
     *                     through real block shapes: towers, bridges, trees), not counting the
     *                     landing ground; infinity when clear
     */
    public Out step(double x, double y, double z, double vx, double vy, double vz,
                    double tx, double ty, double tz, double terrainAhead, double groundBelow,
                    boolean runwayClear, double obstacleDist) {
        Out o = new Out();
        double dx = tx - x, dz = tz - z;
        double dist = Math.hypot(dx, dz);
        double hs = Math.hypot(vx, vz);
        double speed = Math.sqrt(hs * hs + vy * vy);
        double gamma = Math.toDegrees(Math.atan2(vy, Math.max(hs, 1e-6)));
        double above = y - ty;
        double cruise = Math.max(terrainAhead, ty) + CLEARANCE;
        if (boostCooldown > 0) boostCooldown--;
        if (climbOut > 0) climbOut--;

        // Mode: a plane's profile -- climb out, cruise, glide slope -- then a player's landing:
        // over the spot pull up to bleed the speed (FLARE), then sink onto it slowly (SINK).
        // Elytra barely slow down on a shallow glide; only a pull-up takes the speed off.
        double slope = runwayClear ? PLANE_SLOPE : GLIDE_SLOPE;
        double slopeDist = Math.max(0, above - 1) / Math.tan(Math.toRadians(slope));
        boolean nearEnd = climbOut == 0 && dist < 40; // never "land" right after take-off
        boolean landing = mode == Mode.FLARE || mode == Mode.SINK;
        if (landing && dist > 45) {
            landing = false; // drifted off: fly a new approach
        }
        if (landing) {
            if (mode == Mode.FLARE && speed < 0.5) mode = Mode.SINK;
            else if (mode == Mode.SINK && speed > 0.95 && above > 4) mode = Mode.FLARE;
        } else if (runwayClear && (mode == Mode.FINAL || nearEnd && above < 2.2 && dist < 35)) {
            mode = Mode.FINAL; // round-out: a few blocks over the ground, ease the sink to nothing
        } else if (nearEnd && dist < 6 + speed * 12 && (!runwayClear && above < 18 || above > 6)) {
            mode = Mode.FLARE; // steep approach, or a plane approach that came in far too high
        } else if (dist < slopeDist + 6 + (runwayClear ? speed * 10 : 0) && climbOut == 0) {
            mode = Mode.DESCENT;
        } else if (climbOut > 0 || y < cruise - 8) {
            mode = Mode.CLIMB;
        } else {
            mode = Mode.CRUISE;
        }
        // Higher ground than the landing spot still ahead and not far below: climb over it first.
        boolean ridge = dist > 50 && terrainAhead > ty + 2 && y - terrainAhead < 12
                && mode != Mode.FLARE && mode != Mode.SINK && mode != Mode.FINAL;
        if (ridge) mode = Mode.CLIMB;
        // Something solid straight ahead, closer than ~1.4 s of flight: pull up hard now, with
        // power. On an approach this is a go-around: the next ticks plan a new one.
        // Slow (a landing sink, a stall) a bump does no harm -- elytra hurt only on a sharp loss of
        // horizontal speed -- and pulling up there just starts go-around after go-around.
        boolean avoid = speed > 0.45 && obstacleDist < Math.max(10, speed * 28)
                && mode != Mode.FLARE && mode != Mode.SINK;
        if (avoid) mode = Mode.AVOID;
        // Terrain right under the wings beats any plan.
        boolean low = !avoid && y - groundBelow < 6 && dist > 16 && mode != Mode.FLARE && mode != Mode.SINK
                && mode != Mode.FINAL && !(mode == Mode.DESCENT && runwayClear && dist < 50);
        if (low) mode = Mode.CLIMB;

        double gammaCmd;
        boolean wantBoost;
        switch (mode) {
            case CLIMB -> {
                gammaCmd = Math.max(8, Math.min(30, (cruise - y) * 0.9));
                if (speed < 0.9 && boostCooldown == 0) gammaCmd = Math.min(gammaCmd, 4); // no stall
                // Close to the descent a rocket only adds speed the landing must bleed off.
                wantBoost = low || ridge || speed < 0.8 || speed < 1.3 && (climbOut > 0 || dist > slopeDist + 30);
            }
            case CRUISE -> {
                // Soar: glide down gently through an 8-block band, a rocket only at its bottom
                // or when the speed sags -- long quiet glides between short pushes.
                gammaCmd = Math.max(-4, Math.min(6, (cruise - y) * 0.5));
                wantBoost = dist > slopeDist + 45 && (speed < 0.95 || (cruise - y > 6 && speed < 1.25));
            }
            case DESCENT -> {
                double path = Math.toDegrees(Math.atan2(Math.max(above - 1, 0), Math.max(dist - 3, 1)));
                gammaCmd = -Math.max(2, Math.min(runwayClear ? 12 : 30, path));
                // Steep approach: too fast means shallower, and the pull-up takes the rest. A plane
                // approach just keeps its slope -- touching down fast at 7 degrees is harmless.
                if (speed > 1.35 && !runwayClear) gammaCmd = Math.max(gammaCmd, -8);
                // Below the slope (a long, shallow glide ran out of height): add power.
                wantBoost = path < slope - (runwayClear ? 4 : 7) && above < 12 && dist > 25
                        || speed < 0.7 && dist > 40; // a long descent from high up ran out of speed
            }
            case AVOID -> {
                gammaCmd = 35;
                wantBoost = speed < 1.5;
            }
            case FINAL -> {
                gammaCmd = above > 1.2 ? -4 : -1.5; // flatten out and let it settle
                wantBoost = false;
            }
            case SINK -> {
                // Slow, straight at the spot: steep enough to come down, never a dive.
                double path = Math.toDegrees(Math.atan2(Math.max(above, 0), Math.max(dist, 1)));
                gammaCmd = -Math.max(8, Math.min(35, path));
                wantBoost = false;
            }
            default -> { // FLARE
                gammaCmd = 0;
                wantBoost = false;
            }
        }

        float pitchCmd;
        if (mode == Mode.FLARE) {
            // A hard pull-up -- unless a rocket still pushes, then it would be a zoom climb.
            pitchCmd = boostCooldown > 0 ? 5 : -35;
            integral = 0;
        } else if (mode == Mode.SINK && above < 2) {
            pitchCmd = -8; // round out over the ground
            integral = 0;
        } else {
            pitchCmd = holdGamma(gammaCmd, gamma, speed, mode != Mode.SINK);
        }
        o.arrived = (mode == Mode.FLARE || mode == Mode.SINK) && dist < 4 && above < 3;
        return steer(o, pitchCmd, (float) Math.toDegrees(Math.atan2(-dx, dz)), wantBoost, gamma, gammaCmd, speed);
    }

    /**
     * One tick in formation with a leader who is flying: a wingman slot beside and a little behind
     * the leader, led by half a second of the leader's motion, speed matched with rockets and
     * S-turns. Terrain and obstacles still come first.
     */
    public Out stepFollow(double x, double y, double z, double vx, double vy, double vz,
                          double lx, double ly, double lz, double lvx, double lvy, double lvz,
                          double terrainAhead, double groundBelow, double obstacleDist) {
        Out o = new Out();
        if (boostCooldown > 0) boostCooldown--;
        double hs = Math.hypot(vx, vz);
        double speed = Math.sqrt(hs * hs + vy * vy);
        double gamma = Math.toDegrees(Math.atan2(vy, Math.max(hs, 1e-6)));
        double lhs = Math.hypot(lvx, lvz);
        double lspeed = Math.sqrt(lhs * lhs + lvy * lvy);
        double ux, uz;
        if (lhs > 0.2) {
            ux = lvx / lhs;
            uz = lvz / lhs;
        } else { // the leader hangs still: just close in
            double d = Math.max(Math.hypot(lx - x, lz - z), 1e-6);
            ux = (lx - x) / d;
            uz = (lz - z) / d;
        }
        // Wingman slot: 5 to the leader's right, 2 back, 1 up -- where the leader can see the bot.
        // (Right of a heading (ux, uz) in Minecraft's x/z is (-uz, ux).)
        double sx = lx - ux * 2 - uz * 5, sz = lz - uz * 2 + ux * 5, sy = ly + lvy * 10 + 1;
        // Along-track error against the slot itself: > 0 the slot is ahead of the bot.
        double along = (sx - x) * ux + (sz - z) * uz;
        // Aim at a point well ahead of the slot on the leader's line (half a second of the
        // leader's motion plus 10): the bot converges from behind or from the side and never
        // turns back towards a slot it overshot.
        double ax = sx + lvx * 10 + ux * 10, az = sz + lvz * 10 + uz * 10;
        double dx = ax - x, dz = az - z;
        double gammaCmd = Math.max(-30, Math.min(30, Math.toDegrees(Math.atan2(sy - y, Math.max(Math.hypot(dx, dz), 8)))));
        // Speed: the leader's, plus a little to close a gap, minus a little when ahead.
        double want = lspeed + Math.max(-0.4, Math.min(0.6, along * 0.04));
        if (speed > want + 0.15) gammaCmd += Math.min(15, (speed - want) * 25); // trade speed for height
        if (y - Math.max(terrainAhead, groundBelow) < 8) gammaCmd = Math.max(gammaCmd, 15); // not into the ground
        boolean avoid = speed > 0.45 && obstacleDist < Math.max(10, speed * 28);
        mode = avoid ? Mode.AVOID : Mode.FOLLOW;
        if (avoid) gammaCmd = 35;
        boolean wantBoost = avoid ? speed < 1.5 : speed < 0.6 || along > 6 && speed < want - 0.1;
        // Ahead of the slot: S-turns either side of the leader's course, as formation pilots do --
        // the longer path lets the leader catch up; a rocket cannot be throttled back.
        float yawCmd = (float) Math.toDegrees(Math.atan2(-dx, dz));
        weaveTicks++;
        if (along < -3 && !avoid) {
            float course = (float) Math.toDegrees(Math.atan2(-ux, uz));
            yawCmd = course + ((weaveTicks / 40) % 2 == 0 ? 40 : -40);
        }
        float pitchCmd = holdGamma(gammaCmd, gamma, speed, true);
        o.arrived = false;
        return steer(o, pitchCmd, yawCmd, wantBoost, gamma, gammaCmd, speed);
    }

    /** Pitch that holds a flight-path angle: proportional + integral on the angle error. */
    private float holdGamma(double gammaCmd, double gamma, double speed, boolean stallGuard) {
        double err = gammaCmd - gamma;
        integral = Math.max(-12, Math.min(12, integral + err * K_INT));
        float pitchCmd = (float) -(gammaCmd + K_GAMMA * err + integral);
        // Stall guard: without thrust and slow, the nose goes down to win speed back.
        if (stallGuard && speed < 0.5 && boostCooldown == 0) pitchCmd = Math.max(pitchCmd, 12);
        return Math.max(-40, Math.min(40, pitchCmd));
    }

    /** Rate-limited controls, like a hand on a mouse: arcs and smooth pitch changes. */
    private Out steer(Out o, float pitchCmd, float yawCmd, boolean wantBoost, double gamma, double gammaCmd, double speed) {
        float dyaw = wrap(yawCmd - yaw);
        yaw = wrap(yaw + (float) clamp(dyaw * 0.35, MAX_YAW_RATE));
        pitch += (float) clamp(pitchCmd - pitch, mode == Mode.AVOID ? MAX_PITCH_RATE * 2 : MAX_PITCH_RATE);
        if (wantBoost && boostCooldown == 0) {
            o.boost = true;
            boostCooldown = (int) ROCKET_TICKS;
        }
        o.yaw = yaw;
        o.pitch = pitch;
        o.mode = mode;
        o.gamma = gamma;
        o.gammaCmd = gammaCmd;
        o.speed = speed;
        return o;
    }

    /** The caller could not fire (rocket not in hand yet): try again next tick. */
    public void boostFailed() { boostCooldown = 0; }

    private static double clamp(double v, double lim) { return Math.max(-lim, Math.min(lim, v)); }

    private static float wrap(float a) {
        a %= 360;
        if (a >= 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }
}
