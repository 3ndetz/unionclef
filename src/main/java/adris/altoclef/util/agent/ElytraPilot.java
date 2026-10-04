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

    public enum Mode { CLIMB, CRUISE, DESCENT, FLARE }

    public static final class Out {
        public float yaw, pitch;
        public boolean boost;
        public boolean arrived;
        public Mode mode;
        public double gamma, gammaCmd, speed;
    }

    // Tuning, all found against the vanilla physics (ElytraPilotSim).
    static final double CLEARANCE = 20;          // cruise this high above the terrain ahead
    static final double GLIDE_SLOPE = 14;        // degrees of the final descent
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

    public void reset(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        integral = 0;
        boostCooldown = 0;
        mode = Mode.CLIMB;
        climbOut = 30;
    }

    public Mode mode() { return mode; }

    /**
     * One tick.
     * @param terrainAhead highest ground on the next ~80 blocks of the course (and at the target)
     * @param groundBelow  ground height right under the bot
     */
    public Out step(double x, double y, double z, double vx, double vy, double vz,
                    double tx, double ty, double tz, double terrainAhead, double groundBelow) {
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

        // Mode: a plane's profile -- climb out, cruise, start down on the glide slope, flare.
        double slopeDist = Math.max(0, above - 2) / Math.tan(Math.toRadians(GLIDE_SLOPE));
        // Flare early enough to bleed the speed: a fast glide floats a long way nose-up.
        if (mode == Mode.FLARE && above > 12) {
            mode = Mode.DESCENT; // flared too high (a rocket was still burning): go down again
        }
        if (mode == Mode.FLARE || (above < 5 && dist < 16) || (above < 9 && dist < 8 + speed * 10)) {
            mode = Mode.FLARE;
        } else if (dist < slopeDist + 6 && climbOut == 0) {
            mode = Mode.DESCENT;
        } else if (climbOut > 0 || y < cruise - 4) {
            mode = Mode.CLIMB;
        } else {
            mode = Mode.CRUISE;
        }
        // Terrain right under the wings beats any plan.
        boolean low = y - groundBelow < 6 && mode != Mode.FLARE && dist > 16;
        if (low) mode = Mode.CLIMB;

        double gammaCmd;
        boolean wantBoost;
        switch (mode) {
            case CLIMB -> {
                gammaCmd = Math.max(8, Math.min(30, (cruise - y) * 0.9));
                if (speed < 0.9 && boostCooldown == 0) gammaCmd = Math.min(gammaCmd, 4); // no stall
                wantBoost = speed < 1.3 || low;
            }
            case CRUISE -> {
                gammaCmd = Math.max(-6, Math.min(6, (cruise - y) * 0.5));
                // No rocket close to the descent: it would still burn on the approach.
                wantBoost = dist > slopeDist + 45 && (speed < 1.1 || (cruise - y > 2 && speed < 1.25));
            }
            case DESCENT -> {
                double path = Math.toDegrees(Math.atan2(Math.max(above - 1, 0), Math.max(dist - 3, 1)));
                gammaCmd = -Math.max(3, Math.min(30, path));
                // Too fast for the approach: shallower, and let the flare take the height off.
                if (speed > 1.35) gammaCmd = Math.max(gammaCmd, -8);
                // A hill between here and the target: stay above it before going down.
                if (dist > 40 && y - terrainAhead < 12) gammaCmd = Math.max(gammaCmd, 4);
                // Below the slope (a long, shallow glide ran out of height): add power.
                wantBoost = path < GLIDE_SLOPE - 7 && above < 12 && dist > 25;
            }
            default -> { // FLARE: nose up, bleed speed, sink onto the spot
                gammaCmd = 0;
                wantBoost = false;
            }
        }

        float pitchCmd;
        if (mode == Mode.FLARE) {
            // Nose up bleeds speed -- unless a rocket still pushes, then it is a zoom climb.
            pitchCmd = boostCooldown > 0 ? 5 : speed > 0.6 ? -25 : -8;
            integral = 0;
        } else {
            double err = gammaCmd - gamma;
            integral = Math.max(-12, Math.min(12, integral + err * K_INT));
            pitchCmd = (float) -(gammaCmd + K_GAMMA * err + integral);
            // Stall guard: without thrust and slow, the nose goes down to win speed back.
            if (speed < 0.5 && boostCooldown == 0) pitchCmd = Math.max(pitchCmd, 12);
            pitchCmd = Math.max(-40, Math.min(40, pitchCmd));
        }

        // Rate-limited controls, like a hand on a mouse: arcs and smooth pitch changes.
        float yawCmd = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float dyaw = wrap(yawCmd - yaw);
        yaw = wrap(yaw + (float) clamp(dyaw * 0.35, MAX_YAW_RATE));
        pitch += (float) clamp(pitchCmd - pitch, MAX_PITCH_RATE);

        if (wantBoost && boostCooldown == 0) {
            o.boost = true;
            boostCooldown = (int) ROCKET_TICKS;
        }
        o.arrived = mode == Mode.FLARE && dist < 4 && above < 3;
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
