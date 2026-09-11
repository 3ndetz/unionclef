package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import net.minecraft.util.math.Vec3d;

/**
 * The unstuck task: a PLANNED escape first, a (non-digging) shimmy only if no plan can be made.
 *
 * <p>Runs under UnstuckChain at priority 55, so the user task is paused while it drives -- that is
 * the point: FastNavigator owns the movement keys alone. Finishes when the escape moved the body
 * (the plan worked), when the navigator gave up AND the shimmy fallback ran its course, or on the
 * hard cap. See {@link PlannedEscape}.
 */
public class PlannedEscapeTask extends Task {

    /** Escapes that moved the body, escapes that fell through to the shimmy. */
    public static volatile int escapeMoved, escapeFellBack;

    private static final long ESCAPE_CAP_MS = 20_000;
    private static final long SHIMMY_MS = 2_500;
    private static final double MOVED_SQ = 1.5 * 1.5;

    private long startedMs;
    private Vec3d from;
    private boolean escaping;
    private long shimmyStartMs = 0L;
    private final SafeRandomShimmyTask shimmy = new SafeRandomShimmyTask();

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        startedMs = System.currentTimeMillis();
        from = mod.getPlayer().getPos();
        escaping = PlannedEscape.tryStart(mod, "unstuck");
        if (!escaping) {
            escapeFellBack++;
            shimmyStartMs = startedMs;
        }
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (escaping) {
            if (kaptainwutax.tungsten.task.FastNavigator.isActive()
                    && System.currentTimeMillis() - startedMs < ESCAPE_CAP_MS) {
                setDebugState("Planned escape via FastPlanner (dig/build allowed)");
                return null;   // the navigator drives from the client tick
            }
            // The navigator finished or gave up. Did it get us anywhere?
            if (mod.getPlayer().getPos().squaredDistanceTo(from) >= MOVED_SQ) {
                escapeMoved++;
                escaping = false;
                shimmyStartMs = -1L;    // done, no shimmy needed
                return null;
            }
            Debug.logMessage("Planned escape went nowhere — falling back to a (non-digging) shimmy");
            escapeFellBack++;
            escaping = false;
            shimmyStartMs = System.currentTimeMillis();
        }
        if (shimmyStartMs > 0 && System.currentTimeMillis() - shimmyStartMs < SHIMMY_MS) {
            setDebugState("Shimmying (no dig)");
            return shimmy;
        }
        return null;
    }

    @Override
    public boolean isFinished() {
        if (escaping) return false;
        return shimmyStartMs < 0 || System.currentTimeMillis() - shimmyStartMs >= SHIMMY_MS;
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (escaping && kaptainwutax.tungsten.task.FastNavigator.isActive()
                && PlannedEscape.armedFrom() != null) {
            kaptainwutax.tungsten.task.FastNavigator.stop();
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PlannedEscapeTask;
    }

    @Override
    protected String toDebugString() {
        return escaping ? "Planned escape" : "Shimmying (no dig)";
    }
}
