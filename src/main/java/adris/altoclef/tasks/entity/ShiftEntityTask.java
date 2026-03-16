package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GetCloseToBlockTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;
import java.util.Random;

public class ShiftEntityTask extends AbstractDoToEntityTask {

    private Entity _target;
    private String _targetName;
    private int _phase = 0;
    private double _interactDistance = 2.5d;
    private double _shiftDistance = 0.7d;
    private double _stopDistance = 0.2d;

    public enum ShiftType {
        Back,
        Forward,
        Any
    }

    public ShiftType _shiftType;
    private final TimerGame _shiftTimer = new TimerGame(0.1);

    public ShiftEntityTask(Entity target, ShiftType type) {
        super(2, -1, -1);
        _target = target;
        _shiftType = type;
    }

    public ShiftEntityTask(String target, ShiftType type) {
        super(2, -1, -1);
        _targetName = target;
        _shiftType = type;
    }

    public ShiftEntityTask(String target) {
        this(target, ShiftType.values()[new Random().nextInt(ShiftType.values().length - 1)]);
    }

    public ShiftEntityTask(Entity target) {
        this(target, ShiftType.values()[new Random().nextInt(ShiftType.values().length - 1)]);
    }

    @Override
    protected Optional<Entity> getEntityTarget(AltoClef mod) {
        if (_target == null) {
            if (_targetName != null && mod.getEntityTracker().isPlayerLoaded(_targetName)) {
                return mod.getEntityTracker().getPlayerEntity(_targetName).map(Entity.class::cast);
            }
            return Optional.empty();
        }
        // if we constructed via entity
        if (_target instanceof PlayerEntity && _targetName == null) {
            _targetName = _target.getName().getString();
        }
        return Optional.of(_target);
    }

    @Override
    protected boolean isSubEqual(AbstractDoToEntityTask other) {
        if (other instanceof ShiftEntityTask task) {
            return Objects.equals(task._target, _target);
        }
        return false;
    }

    @Override
    protected Task onEntityInteract(AltoClef mod, Entity entity) {
        if (entity != null && entity.getName() != null)
            setDebugState("target = " + entity.getName().getString());

        double yDiff = entity.getPos().getY() - mod.getPlayer().getPos().getY();
        boolean canShift = LookHelper.canHitEntity(mod, entity, (float) _interactDistance) && yDiff <= 1d;
        boolean tooClose;
        boolean shifting;
        double yBorder = 0.9f;

        if (!canShift) {
            return new GetToEntityTask(entity);
        }
        if (_shiftType.equals(ShiftType.Any)) {
            LookHelper.smoothLook(mod, entity);
            tooClose = mod.getPlayer().getPos().isInRange(entity.getPos(), _stopDistance);
            shifting = mod.getPlayer().getPos().isInRange(entity.getPos(), _shiftDistance);
        } else {
            Vec3d targetPos = entity.getEyePos();
            Vec3d originPos = mod.getPlayer().getEyePos();
            // Calculate position behind the entity
            float entityYaw = entity.getBodyYaw();
            double offsetX = -Math.sin(Math.toRadians(entityYaw));
            double offsetZ = Math.cos(Math.toRadians(entityYaw));

            if (_shiftType.equals(ShiftType.Forward)) {
                targetPos = targetPos.add(
                        offsetX * _stopDistance,
                        0d,
                        offsetZ * _stopDistance
                );

                yDiff = (entity.getEyePos().getY() - mod.getPlayer().getPos().getY());
                yBorder = 0.8d;
                originPos = mod.getPlayer().getPos().add(new Vec3d(0d, 0.5d, 0d));
                BlockPos targetBlockPos = WorldHelper.toBlockPos(targetPos);
                if (WorldHelper.isSolidBlock(targetBlockPos) && !originPos.isInRange(targetPos, 2d)) {
                    return new GetCloseToBlockTask(targetBlockPos);
                }
            } else if (_shiftType.equals(ShiftType.Back)) {
                targetPos = targetPos.add(
                        -offsetX * _stopDistance,
                        0d,
                        -offsetZ * _stopDistance
                );
            }
            // Look at the position behind the entity
            tooClose = originPos.isWithinRangeOf(targetPos, _stopDistance, 1d);
            shifting = originPos.isWithinRangeOf(targetPos, _shiftDistance, 1d);
            if (tooClose) {
                LookHelper.smoothLook(mod, new Vec3d(entity.getEyePos().getX(), mod.getPlayer().getEyePos().getY() - 0.3, entity.getEyePos().getZ()));
            } else {
                LookHelper.smoothLook(mod, new Vec3d(targetPos.getX(), mod.getPlayer().getEyePos().getY() - 0.3, targetPos.getZ()));
            }
        }

        if (yDiff >= yBorder) {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        } else {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }

        if (shifting) {
            if (_phase == 0) {
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            } else {
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
            }
        } else {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        }

        if (tooClose) {
            if (mod.getPlayer().getVelocity().horizontalLengthSquared() > 0.0025) {
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            } else {
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
            }
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        } else {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        }

        if (_shiftTimer.elapsed()) {
            if (_phase > 0) {
                _phase = 0;
            } else {
                _phase += 1;
            }
            _shiftTimer.reset();
        }
        return null;
    }

    public boolean equipShiftItem(AltoClef mod) {
        if (!ItemHelper.hasItems(mod, ItemHelper.FunnyShiftItems)) {
            return false;
        }
        return mod.getSlotHandler().forceEquipItem(ItemHelper.FunnyShiftItems, true);
    }

    @Override
    protected String toDebugString() {
        return "Ducking " + _shiftType.toString() + " of target";
    }
}
