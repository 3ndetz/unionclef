package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import net.minecraft.entity.Entity;

import java.util.Objects;
import java.util.Optional;

/**
 * Kill a specific entity
 */
public class KillEntityTask extends AbstractKillEntityTask {

    private final Entity target;

    public KillEntityTask(Entity entity) {
        target = entity;
    }

    public KillEntityTask(Entity entity, double maintainDistance, double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        super(maintainDistance, combatGuardLowerRange, combatGuardLowerFieldRadius);
        target = entity;
    }

    @Override
    protected Optional<Entity> getEntityTarget(AltoClef mod) {
        return Optional.of(target);
    }

    @Override
    protected boolean isSubEqual(AbstractDoToEntityTask other) {
        if (other instanceof KillEntityTask task) {
            return Objects.equals(task.target, target);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Killing " + target.getType().getTranslationKey();
    }
}
