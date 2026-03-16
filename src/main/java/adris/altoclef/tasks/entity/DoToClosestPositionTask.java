package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Position;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Finds the closest position and runs a task on that position
 */
@SuppressWarnings("ALL")
public class DoToClosestPositionTask extends AbstractDoToClosestObjectTask<Vec3d> {

    private Class[] _targetEntities = null;

    private final Supplier<Vec3d> _getOriginPos;

    private final Function<Position, Task> _getTargetTask;

    private final Predicate<Entity> _shouldInteractWith;
    private HashMap<Enum, Vec3d> _positionMap = new HashMap<>();

    public DoToClosestPositionTask(Function<Position, Task> getTargetTask, HashMap<Enum, Vec3d> positionMap) {
        _getOriginPos = null;
        _getTargetTask = getTargetTask;
        _shouldInteractWith = entity -> true;
        _targetEntities = null;
        _positionMap = positionMap;
    }

    public DoToClosestPositionTask(Supplier<Vec3d> getOriginSupplier, Function<Position, Task> getTargetTask, Predicate<Entity> shouldInteractWith, Class... entities) {
        _getOriginPos = getOriginSupplier;
        _getTargetTask = getTargetTask;
        _shouldInteractWith = shouldInteractWith;
        _targetEntities = entities;
    }

    public DoToClosestPositionTask(Supplier<Vec3d> getOriginSupplier, Function<Position, Task> getTargetTask, Class... entities) {
        this(getOriginSupplier, getTargetTask, entity -> true, entities);
    }

    public DoToClosestPositionTask(Function<Position, Task> getTargetTask, Predicate<Entity> shouldInteractWith, Class... entities) {
        this(null, getTargetTask, shouldInteractWith, entities);
    }

    public DoToClosestPositionTask(Function<Position, Task> getTargetTask, Class... entities) {
        this(null, getTargetTask, entity -> true, entities);
    }

    @Override
    protected Vec3d getPos(AltoClef mod, Vec3d pos) {
        return pos;
    }

    @Override
    protected Optional<Vec3d> getClosestTo(AltoClef mod, Vec3d pos) {
        if (_targetEntities != null && mod.getEntityTracker().entityFound(_targetEntities)) {
            Optional<Entity> entity = mod.getEntityTracker().getClosestEntity(pos, _shouldInteractWith, _targetEntities);
            if (entity.isPresent()) {
                if (entity.get().getName() != null) {
                    setDebugState("Сущность: " + entity.get().getName().getString());
                }
            } else {
                setDebugState("Сущность не в поле зрения");
            }
        }
        getOriginPos(mod);
        return Optional.empty();
    }

    @Override
    protected Vec3d getOriginPos(AltoClef mod) {
        if (_getOriginPos != null) {
            return _getOriginPos.get();
        }
        return mod.getPlayer().getPos();
    }

    @Override
    protected Task getGoalTask(Vec3d pos) {
        return _getTargetTask.apply(pos);
    }

    @Override
    protected boolean isValid(AltoClef mod, Vec3d pos) {
        return pos != null && !pos.equals(Vec3d.ZERO);
    }

    @Override
    protected void onStart() {
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DoToClosestPositionTask task) {
            return Arrays.equals(task._targetEntities, _targetEntities);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Манипуляция с ближайшей сущностью...";
    }
}
