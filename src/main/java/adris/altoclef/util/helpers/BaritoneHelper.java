package adris.altoclef.util.helpers;

import kaptainwutax.tungsten.path.calculators.ActionCosts;
import net.minecraft.util.math.Vec3d;

public class BaritoneHelper {

    /**
     * Use whenever accessing Minecraft data (from ClientWorld or ClientPlayerEntity) in baritone
     */
    public static final Object MINECRAFT_LOCK = new Object();

    private static final double SQRT_2 = Math.sqrt(2);

    /**
     * The horizontal half of the estimate, scaled the way the search scales it.
     *
     * <p>{@code costHeuristic} (baritone Settings.java:447) is 3.563 and nothing in this repo ever
     * writes it, so it is a constant in practice. It is not cosmetic even for a pure RANKING use
     * like the block scanner's: it sets what horizontal distance is worth against the climb term
     * below, which is what decides between a trunk twelve blocks away and a canopy log three blocks
     * away and seven up.
     */
    private static final double COST_HEURISTIC = 3.563;

    public static double calculateGenericHeuristic(Vec3d start, Vec3d target) {
        return calculateGenericHeuristic(start.x, start.y, start.z, target.x, target.y, target.z);
    }

    /**
     * How far away a place is, in the search's own units — ticks, not blocks.
     *
     * <p>Ported from {@code GoalBlock.calculate} / {@code GoalXZ.calculate} / {@code
     * GoalYLevel.calculate} rather than called through them (G-0: this file is used in 27 places,
     * and it was pulling in a pathfinder to do arithmetic). The numbers are unchanged: the same
     * pythagorean-plus-manhattan mixture horizontally, the same jump-per-block price for climbing,
     * the same half-of-a-two-block-fall price for descending, and the same {@code yDiff - 1}
     * adjustment that treats "one block below" as level.
     *
     * <p>{@link ActionCosts} is tungsten's copy of upstream's table and carries identical values
     * (JUMP_ONE_BLOCK_COST = FALL_1_25 - FALL_0_25 = 3.163), so the ranking this produces is the
     * one it produced before.
     */
    public static double calculateGenericHeuristic(double xStart, double yStart, double zStart, double xTarget, double yTarget, double zTarget) {
        double xDiff = xTarget - xStart;
        int yDiff = (int) yTarget - (int) yStart;
        double zDiff = zTarget - zStart;
        return calculate(xDiff, yDiff < 0 ? yDiff - 1 : yDiff, zDiff);
    }

    /** {@code GoalBlock.calculate}: the climb term plus the horizontal term. */
    private static double calculate(double xDiff, int yDiff, double zDiff) {
        return climbCost(yDiff) + horizontalCost(xDiff, zDiff);
    }

    /**
     * {@code GoalYLevel.calculate(0, yDiff)}: yDiff is how far ABOVE the goal we are, so a positive
     * value means descending and a negative one means climbing.
     */
    private static double climbCost(int yDiff) {
        if (yDiff > 0) {
            return ActionCosts.distanceToTicks(2) / 2 * yDiff;
        }
        if (yDiff < 0) {
            return -yDiff * ActionCosts.JUMP_ONE_BLOCK_COST;
        }
        return 0;
    }

    /**
     * {@code GoalXZ.calculate}: a mixture of pythagorean and manhattan, because pathing walks
     * diagonally OR forwards — one forward and two right is not sqrt(5) time, it is 1 + sqrt(2).
     */
    private static double horizontalCost(double xDiff, double zDiff) {
        double x = Math.abs(xDiff);
        double z = Math.abs(zDiff);
        double straight;
        double diagonal;
        if (x < z) {
            straight = z - x;
            diagonal = x;
        } else {
            straight = x - z;
            diagonal = z;
        }
        return (diagonal * SQRT_2 + straight) * COST_HEURISTIC;
    }
}
