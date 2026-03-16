package adris.altoclef.tasks.stupid;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasks.entity.DoToClosestEntityTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;

/**
 * Attacks closest player, but stands still when anyone has direct line of sight on the bot.
 */
public class SCP173Task extends Task {

    private static final double MAX_RANGE = 300;
    private static final double LOOK_CLOSENESS_THRESHOLD = 0.2;
    private static final double HIT_RANGE = 2.5;
    private static final double WALK_THRESHOLD = 0.1;
    private final HashMap<PlayerEntity, Double> _lastLookCloseness = new HashMap<>();
    private PlayerEntity _lastTarget = null;
    private Vec3d _lastWalkVelocity = Vec3d.ZERO;

    @Override
    protected void onStart() {
        _lastLookCloseness.clear();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        boolean seen = isSeenByPlayer(mod);

        Vec3d currentVelocity = mod.getPlayer().getVelocity();
        if (currentVelocity.lengthSquared() > WALK_THRESHOLD * WALK_THRESHOLD) {
            _lastWalkVelocity = currentVelocity;
        }

        if (seen) {
            setDebugState("Standing still and being menacing");
        } else {
            setDebugState("Scrape Scrape Scrape");
        }

        if (seen) {
            if (_lastTarget != null) {
                LookHelper.lookAt(mod, LookHelper.getCameraPos(_lastTarget));
            }
            return null;
        }

        if (_lastTarget != null && mod.getPlayer().isInRange(_lastTarget, HIT_RANGE)) {
            if (LookHelper.seesPlayer(mod.getPlayer(), _lastTarget, HIT_RANGE)) {
                AbstractKillEntityTask.equipWeapon(mod);
                if (mod.getPlayer().getAttackCooldownProgress(0) >= 0.99) {
                    mod.getControllerExtras().attack(_lastTarget);
                }
            }
        }

        return new DoToClosestEntityTask(
                target -> {
                    _lastTarget = (PlayerEntity) target;
                    return new GetToEntityTask(target);
                },
                PlayerEntity.class
        );
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SCP173Task;
    }

    @Override
    protected String toDebugString() {
        return "Acting like SCP 173";
    }

    private boolean isSeenByPlayer(AltoClef mod) {
        if (mod.getEntityTracker().entityFound(PlayerEntity.class)) {
            for (PlayerEntity player : mod.getEntityTracker().getTrackedEntities(PlayerEntity.class)) {
                if (entityIsLookingInOurGeneralDirection(mod, player) && entityHasLineOfSightToUs(mod, player)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean entityIsLookingInOurGeneralDirection(AltoClef mod, PlayerEntity other) {
        double lookCloseness = LookHelper.getLookCloseness(other, mod.getPlayer().getPos());
        double last = _lastLookCloseness.getOrDefault(other, lookCloseness);
        double delta = lookCloseness - last;
        double predicted = lookCloseness + delta * 6;
        _lastLookCloseness.put(other, lookCloseness);
        return lookCloseness > LOOK_CLOSENESS_THRESHOLD || predicted > LOOK_CLOSENESS_THRESHOLD;
    }

    private boolean entityHasLineOfSightToUs(AltoClef mod, PlayerEntity other) {
        if (LookHelper.seesPlayer(mod.getPlayer(), other, MAX_RANGE)) {
            return true;
        }
        double playerVelMul = 5;
        double entityVelMul = 10;
        Vec3d lastVelocityOffs = _lastWalkVelocity.multiply(playerVelMul);
        if (!_lastWalkVelocity.equals(Vec3d.ZERO)) {
            double minLength = 1.3;
            if (lastVelocityOffs.lengthSquared() < minLength * minLength) {
                lastVelocityOffs = lastVelocityOffs.normalize().multiply(minLength);
            }
        }
        return LookHelper.seesPlayer(mod.getPlayer(), other, MAX_RANGE, mod.getPlayer().getVelocity().multiply(playerVelMul), other.getVelocity().multiply(entityVelMul))
                || LookHelper.seesPlayer(mod.getPlayer(), other, MAX_RANGE, lastVelocityOffs, Vec3d.ZERO);
    }
}
