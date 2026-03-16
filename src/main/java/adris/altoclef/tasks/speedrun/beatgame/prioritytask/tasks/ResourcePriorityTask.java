package adris.altoclef.tasks.speedrun.beatgame.prioritytask.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.speedrun.beatgame.prioritytask.prioritycalculators.ItemPriorityCalculator;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;

import java.util.Arrays;
import java.util.function.Function;

/**
 * represents a wrapper for TaskCatalogue.getItemTask given a priority calculator and a ItemTarget
 */
public class ResourcePriorityTask extends PriorityTask {


    private final ItemPriorityCalculator priorityCalculator;
    private final ItemTarget[] collect;
    private boolean collected = false;
    private Task task = null;

    public ResourcePriorityTask(ItemPriorityCalculator priorityCalculator, Function<AltoClef, Boolean> canCall,Task task, ItemTarget... collect) {
        this(priorityCalculator, canCall, false, true, false, collect);
        this.task = task;

    }

    public ResourcePriorityTask(ItemPriorityCalculator priorityCalculator, Function<AltoClef, Boolean> canCall, ItemTarget... collect) {
        this(priorityCalculator, canCall, false, true, false, collect);
    }


    public ResourcePriorityTask(ItemPriorityCalculator priorityCalculator, Function<AltoClef, Boolean> canCall, boolean shouldForce, boolean canCache, boolean bypassForceCooldown, ItemTarget... collect) {
        super(canCall, shouldForce, canCache, bypassForceCooldown);

        this.collect = collect;
        this.priorityCalculator = priorityCalculator;
    }

    @Override
    public Task getTask(AltoClef mod) {
        if (this.task != null) return task;

        return TaskCatalogue.getSquashedItemTask(collect);
    }

    @Override
    public String getDebugString() {
        return "Collecting resource: "+ Arrays.toString(collect);
    }

    @Override
    public double getPriority(AltoClef mod) {
        if (collected) return Double.NEGATIVE_INFINITY;

        int count = 0;
        for (ItemTarget target : collect) {
            count += mod.getItemStorage().getItemCount(target.getMatches());
        }

        if (count >= priorityCalculator.maxCount) {
            collected= true;
        }

        return priorityCalculator.getPriority(count);
    }


    public boolean isCollected() {
        return collected;
    }
}
