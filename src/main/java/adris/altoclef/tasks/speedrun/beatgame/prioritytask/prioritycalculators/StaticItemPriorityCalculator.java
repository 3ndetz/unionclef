package adris.altoclef.tasks.speedrun.beatgame.prioritytask.prioritycalculators;

public class StaticItemPriorityCalculator extends ItemPriorityCalculator{

    public static StaticItemPriorityCalculator of(int priority) {
        return new StaticItemPriorityCalculator(priority,1,1);
    }

    private final int priority;

    public StaticItemPriorityCalculator(int priority, int minCount, int maxCount) {
        super(minCount, maxCount);
        this.priority = priority;
    }

    @Override
    double calculatePriority(int count) {
        return priority;
    }
}
