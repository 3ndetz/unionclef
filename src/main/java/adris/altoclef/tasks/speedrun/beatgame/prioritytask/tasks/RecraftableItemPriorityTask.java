package adris.altoclef.tasks.speedrun.beatgame.prioritytask.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.util.RecipeTarget;

import java.util.function.Function;

public class RecraftableItemPriorityTask extends CraftItemPriorityTask{


    private final double recraftPriority;

    public RecraftableItemPriorityTask(double priority, double recraftPriority, RecipeTarget toCraft, Function<AltoClef, Boolean> canCall ) {
        super(priority, toCraft, canCall);
        this.recraftPriority = recraftPriority;
    }


    @Override
    protected double getPriority(AltoClef mod) {
        if (isSatisfied()) return recraftPriority;

        return super.getPriority(mod);
    }
}
