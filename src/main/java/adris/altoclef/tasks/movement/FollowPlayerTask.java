package adris.altoclef.tasks.movement;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;

/**
 * Follow a player by name.
 *
 * Routes to the TUNGSTEN follow engine
 * ({@link kaptainwutax.tungsten.task.FollowPlayerTask} -> FollowEntityTask),
 * which is purpose-built for MOVING targets:
 *   - a block-path BFS walker gives IMMEDIATE movement while the physics A*
 *     computes (no "stand still waiting for a path"),
 *   - re-plan uses hysteresis (RECALC_TICKS) so a sprinting target does not
 *     restart the search every tick and starve it forever,
 *   - the executor keeps running the current path DURING a re-plan, so the bot
 *     keeps moving instead of freezing.
 *
 * This replaces the old baritone route (GetToEntityTask -> CustomBaritoneGoalTask
 * with TungstenHelper.primary defaulting false), whose failure mode on a moving
 * player was exactly "forever rebuilds the route and stands still". Tungsten-first
 * per the project directive; tungsten also handles player re-discovery
 * (disconnect / teleport / chunk unload) internally.
 */
public class FollowPlayerTask extends Task {

    private static final double DEFAULT_RADIUS = 2.0;

    private final String _playerName;
    private final double _radius;

    public FollowPlayerTask(String playerName) {
        this(playerName, DEFAULT_RADIUS);
    }

    /** @param radius stop-distance in blocks (bot holds position within this). */
    public FollowPlayerTask(String playerName, double radius) {
        _playerName = playerName;
        _radius = radius;
    }

    @Override
    protected void onStart() {
        kaptainwutax.tungsten.task.FollowPlayerTask.start(_playerName, _radius);
    }

    @Override
    protected Task onTick() {
        // The tungsten follow engine drives movement from MixinClientPlayerEntity
        // every tick (routing, re-discovery, radius-hold all live there). We only
        // keep this task alive, and re-arm if it was stopped externally or points
        // at a different target.
        if (!kaptainwutax.tungsten.task.FollowPlayerTask.isActive()
                || !_playerName.equalsIgnoreCase(
                        String.valueOf(kaptainwutax.tungsten.task.FollowPlayerTask.getTargetName()))) {
            kaptainwutax.tungsten.task.FollowPlayerTask.start(_playerName, _radius);
        }
        // Keep baritone off — tungsten owns movement here; a residual baritone goal
        // would fight the tungsten walker/executor for the input keys.
        Nav.cancel();
        setDebugState("Following " + _playerName + " (tungsten)");
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        kaptainwutax.tungsten.task.FollowPlayerTask.stop();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof FollowPlayerTask task) {
            return task._playerName.equals(_playerName);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Following player " + _playerName + " (tungsten)";
    }
}
