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
 * baritone's ExploreProcess (baritone/src/main/java/baritone/process/ExploreProcess.java) makes the
 * same choice for the same reason: its goal is the nearest chunk not yet seen, which from any start
 * moves outward, never back over explored ground.
 */
public class ExploreOutwardTask extends Task {

    private static final double LEG = 48;
    /** A leg that has not closed this much of its distance in LEG_PATIENCE_MS is abandoned. */
    private static final double MIN_GAIN = 4;
    private static final long LEG_PATIENCE_MS = 25_000;

    private double heading = Double.NaN;   // radians, 0 = +x
    private int legX, legZ;
    private boolean hasLeg;
    private double legBestDist;
    private long legProgressMs;
    public static volatile int legsStarted, legsTurned;

    @Override
    protected void onStart() {
        hasLeg = false;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        Vec3d pos = mod.getPlayer().getPos();
        long now = System.currentTimeMillis();
        if (Double.isNaN(heading)) {
            heading = Math.toRadians(mod.getPlayer().getYaw() + 90);   // where it faces
        }
        if (hasLeg) {
            double d = Math.hypot(legX + 0.5 - pos.x, legZ + 0.5 - pos.z);
            if (d < 3) {
                hasLeg = false;                       // reached: next leg, same heading
            } else if (d < legBestDist - MIN_GAIN) {
                legBestDist = d;
                legProgressMs = now;
            } else if (now - legProgressMs > LEG_PATIENCE_MS) {
                heading += Math.PI / 2;               // this way is blocked: turn
                legsTurned++;
                hasLeg = false;
            }
        }
        if (!hasLeg) {
            legX = (int) Math.floor(pos.x + Math.cos(heading) * LEG);
            legZ = (int) Math.floor(pos.z + Math.sin(heading) * LEG);
            legBestDist = LEG;
            legProgressMs = now;
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
