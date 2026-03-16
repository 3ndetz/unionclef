package adris.altoclef.tasks.speedrun.beatgame.prioritytask.prioritycalculators;

public class DistanceItemPriorityCalculator extends DistancePriorityCalculator {

    private final double multiplier;
    private final double unneededMultiplier;
    private final double unneededDistanceThreshold;


    public DistanceItemPriorityCalculator(double multiplier, double unneededMultiplier, double unneededDistanceThreshold, int minCount, int maxCount) {
        super(minCount, maxCount);
        this.multiplier = multiplier;
        this.unneededMultiplier = unneededMultiplier;
        this.unneededDistanceThreshold = unneededDistanceThreshold;
    }

    protected double calculatePriority(double distance) {
        double priority = 1 / distance;

        if (super.minCountSatisfied) {
            if (distance < unneededDistanceThreshold) {
                return priority * unneededMultiplier;
            }
            return Double.NEGATIVE_INFINITY;
        }

        return priority * multiplier;
    }

}
