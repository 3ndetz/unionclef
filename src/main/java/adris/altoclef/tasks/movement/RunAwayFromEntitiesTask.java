package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.util.goals.AltoGoal;
import net.minecraft.entity.Entity;

import java.util.List;
import java.util.function.Supplier;

public abstract class RunAwayFromEntitiesTask extends CustomBaritoneGoalTask {

    private final Supplier<List<Entity>> _runAwaySupplier;

    private final double _distanceToRun;
    private final boolean _xz;
    // See GoalrunAwayFromEntities penalty value
    private final double _penalty;

    public RunAwayFromEntitiesTask(Supplier<List<Entity>> toRunAwayFrom, double distanceToRun, boolean xz, double penalty) {
        _runAwaySupplier = toRunAwayFrom;
        _distanceToRun = distanceToRun;
        _xz = xz;
        _penalty = penalty;
    }

    public RunAwayFromEntitiesTask(Supplier<List<Entity>> toRunAwayFrom, double distanceToRun, double penalty) {
        this(toRunAwayFrom, distanceToRun, false, penalty);
    }


    /**
     * OFF BARITONE'S GOAL TYPE — the last of the three flee tasks.
     *
     * <p>{@code _penalty} and {@code _xz} are not carried over, and neither is load-bearing under
     * the live engine. Both only ever reached {@code Goal.heuristic()} / {@code isInGoal}'s XZ
     * flattening, and heuristic() is read exclusively by shredder's A*; the tungsten drive asks a
     * goal for target() and reached(). The constructors keep both parameters so the callers and
     * their tuning history stay readable, and so this is one honest edit rather than a rename
     * cascade across every subclass.
     */
    @Override
    protected AltoGoal newAltoGoal(AltoClef mod) {
        return new AltoGoal.FleeLive(
                () -> {
                    List<Entity> from = _runAwaySupplier.get();
                    if (from == null) {
                        return java.util.List.of();
                    }
                    return from.stream()
                            .map(e -> new net.minecraft.util.math.Vec3d(e.getX(), e.getY(), e.getZ()))
                            .collect(java.util.stream.Collectors.toList());
                },
                () -> mod.getPlayer() == null ? null : mod.getPlayer().getPos(),
                _distanceToRun);
    }


}
