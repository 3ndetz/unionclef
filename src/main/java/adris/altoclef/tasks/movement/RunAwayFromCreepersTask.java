package adris.altoclef.tasks.movement;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.goals.AltoGoal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class RunAwayFromCreepersTask extends CustomBaritoneGoalTask {

    private final double _distanceToRun;

    public RunAwayFromCreepersTask(double distance) {
        _distanceToRun = distance;
    }

    @SuppressWarnings("RedundantIfStatement")
    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof RunAwayFromCreepersTask task) {
            //if (task._mob.getPos().squaredDistanceTo(_mob.getPos()) > 0.5) return false;
            if (Math.abs(task._distanceToRun - _distanceToRun) > 1) return false;
            return true;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Run " + _distanceToRun + " blocks away from creepers";
    }

    /**
     * OFF BARITONE'S GOAL TYPE. See {@link AltoGoal.FleeLive} for why a record snapshot could not
     * be used: the task's goal object is cached for its whole life, so a fixed point would send
     * the bot to where the creepers were when it started running.
     *
     * <p>⛔ WHAT THIS DOES NOT PORT, AND IT WAS ALREADY GONE: the old inner goal overrode
     * {@code getCostOfEntity} to weight cells by {@link MobDefenseChain#getCreeperSafety}, i.e. to
     * respect a creeper's FUSE. That override is only ever read through {@code Goal.heuristic()},
     * and heuristic() is consumed exclusively inside shredder's A* (PathNode, PathingBehavior) —
     * the fallback engine, not the tungsten drive, which asks a goal only for target() and
     * reached(). So fuse-aware creeper avoidance has not been happening on the live engine for as
     * long as tungsten has been primary. This port does not remove it; it removes the appearance
     * of it. Written down as a debt rather than quietly dropped: if fleeing creepers should weigh
     * the fuse, that belongs in the flee POINT, not in a heuristic nothing calls.
     */
    @Override
    protected AltoGoal newAltoGoal(AltoClef mod) {
        // We want to run away NOW
        Nav.cancel();
        return new AltoGoal.FleeLive(
                () -> mod.getEntityTracker().getTrackedEntities(CreeperEntity.class).stream()
                        .map(e -> new Vec3d(e.getX(), e.getY(), e.getZ()))
                        .collect(java.util.stream.Collectors.toList()),
                () -> mod.getPlayer() == null ? null : mod.getPlayer().getPos(),
                _distanceToRun);
    }

}
