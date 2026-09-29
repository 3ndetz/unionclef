package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import net.minecraft.util.math.Vec3d;

/**
 * Walk away from here in a straight line, leg after leg, turning only when a leg stops making
 * progress. For searches whose target is simply not around -- food with no animal in sight.
 *
 * <p>WHY NOT TimeoutWanderTask. It walks a golden-angle spiral around where it started, 32 to 56
 * blocks out, so it keeps searching the same ground. On the full52 resume that ground was a
 * snowy mountain with no animals: five minutes of wandering, ninety seconds of it stuck in a gap,
 * then a cave and a zombie. Animals live by biome, and a straight line leaves a barren biome fastest.
 *
 * <p>⛔ NOT A PORT OF baritone's ExploreProcess, ON A MEASUREMENT (2026-09-29). ExploreProcess
 * (baritone/src/main/java/baritone/process/ExploreProcess.java, closestUncachedChunks) covers the
 * map: Manhattan rings of unseen chunks around the origin, nearest member first. A faithful port was
 * written and run on the same no-food checkpoint (death1950-0929-0827-t1613): its goals swung
 * between chunks on opposite sides of the origin ((-408,-200), (-664,..), (-680,..), (-552,..)), it
 * found no food in eight minutes and health went 15.6 -> 3.6. These straight legs found pigs there
 * in 166 s. Coverage around a point is the right shape for a map and the wrong one for leaving a
 * barren biome. One run each: re-measure before trading one for the other.
 */
public class ExploreOutwardTask extends Task {

    private static final double LEG = 48;
    /** A leg that has not closed this much of its distance in LEG_PATIENCE_MS is abandoned. */
    private static final double MIN_GAIN = 4;
    private static final long LEG_PATIENCE_MS = 25_000;

    // ⛔ THE SEARCH OUTLIVES THE TASK OBJECT (full55, 2026-09-29). Parents re-create this task
    // whenever they restart, and with the state per instance every new one took the same heading
    // from the same yaw and reset its patience: fifteen minutes on one unreachable leg,
    // "heading -271, Getting to (85,-568)", never turning. One search, kept across instances,
    // forgotten only after SEARCH_FORGET_MS without a tick.
    private static final long SEARCH_FORGET_MS = 60_000;
    private static double heading = Double.NaN;   // radians, 0 = +x
    private static int legX, legZ;
    private static boolean hasLeg;
    private static double legBestDist;
    /** Time this search has actually been ticking (ms): patience counts only that, not the minutes
     *  a bed, a fight or a meal took in between. */
    private static long activeMs, legProgressMs, lastTickMs;
    /** Where the body was when the current leg started, and whether the build engine has had a go. */
    private static Vec3d legStartPos;
    private static boolean legEscapeTried;
    public static volatile int legsStarted, legsTurned, escapes;

    @Override
    protected void onStart() {
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        Vec3d pos = mod.getPlayer().getPos();
        long now = System.currentTimeMillis();
        if (now - lastTickMs > SEARCH_FORGET_MS) {
            heading = Double.NaN;
            hasLeg = false;
        } else {
            activeMs += Math.min(now - lastTickMs, 1000);
        }
        lastTickMs = now;
        // WALLED IN? THEN THIS IS NOT A SEARCH, IT IS AN ESCAPE -- the same answer TimeoutWanderTask
        // gives (G29). full55: in a 1x1 shaft left by the night, every leg answered "NO ROUTE" and
        // the build engine was never armed for long enough to dig out; PlannedEscape arms it now.
        if (kaptainwutax.tungsten.task.FastNavigator.isActive() && PlannedEscape.armedFrom() != null) {
            setDebugState("Enclosed -- escaping via FastPlanner (dig/build allowed)");
            return null;
        }
        if (PlannedEscape.enclosed(mod) && PlannedEscape.tryStart(mod, "explore enclosed")) {
            escapes++;
            setDebugState("Enclosed -- escaping via FastPlanner (dig/build allowed)");
            return null;
        }
        if (Double.isNaN(heading)) {
            heading = Math.toRadians(mod.getPlayer().getYaw() + 90);   // where it faces
        }
        if (hasLeg) {
            double d = Math.hypot(legX + 0.5 - pos.x, legZ + 0.5 - pos.z);
            if (d < 3) {
                hasLeg = false;                       // reached: next leg, same heading
            } else if (d < legBestDist - MIN_GAIN) {
                legBestDist = d;
                legProgressMs = activeMs;
            } else if (activeMs - legProgressMs > LEG_PATIENCE_MS) {
                // NOT MOVED AT ALL? THE WALKER IS STUCK, NOT THE HEADING (full55, t617 resume).
                // In a pocket left by the night the grid route kept flickering between "none" and a
                // step into a one-high niche, so the drive's own escalation to the build engine
                // (2.5 s of NO ROUTE in a row) never fired and every heading was refused alike.
                // Give the build engine this leg once before turning.
                if (!legEscapeTried && legStartPos != null && pos.distanceTo(legStartPos) < 4
                        && PlannedEscape.tryStart(mod, "explore stalled")) {
                    legEscapeTried = true;
                    escapes++;
                    legProgressMs = activeMs;
                    setDebugState("Stalled -- escaping via FastPlanner (dig/build allowed)");
                    return null;
                }
                heading += Math.PI / 2;               // this way is blocked: turn
                legsTurned++;
                hasLeg = false;
            }
        }
        if (!hasLeg) {
            legX = (int) Math.floor(pos.x + Math.cos(heading) * LEG);
            legZ = (int) Math.floor(pos.z + Math.sin(heading) * LEG);
            legBestDist = LEG;
            legProgressMs = activeMs;
            legStartPos = pos;
            legEscapeTried = false;
            hasLeg = true;
            legsStarted++;
        }
        setDebugState("Exploring outward, heading " + Math.round(Math.toDegrees(heading)) % 360 + " deg");
        return new GetToXZTask(legX, legZ);
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ExploreOutwardTask;
    }

    @Override
    protected String toDebugString() {
        return "Exploring outward";
    }

    @Override
    public boolean isFinished() {
        return false;
    }
}
