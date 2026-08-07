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
        // THE SIGN HERE WAS INVERTED, AND IT PRICED EVERY CLIMB AS A FALL.
        //
        // GoalYLevel.calculate(goalY, currentY) reads currentY > goalY as DESCENDING (cheap, half a
        // two-block fall) and currentY < goalY as ASCENDING, and GoalBlock hands it
        // yDiff = CURRENT - GOAL. This port computed target - start, the other way round, so a
        // block ABOVE the bot took the descending branch: seven blocks up was costed as seven
        // blocks down.
        //
        // That is not academic. It is exactly the ranking the block scanner uses to choose what to
        // walk to, and it is why chop_canopy still failed after the climb repricing: a canopy log
        // three blocks away and seven up kept beating a reachable trunk twelve blocks off, because
        // the climb term it was supposed to be charged never applied to a climb at all. The
        // repricing below was correct and had simply never been reached by an ascent.
        int yDiff = (int) yStart - (int) yTarget;
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
     *
     * <h2>Why climbing is priced higher here than upstream prices it</h2>
     *
     * Upstream charges one JUMP_ONE_BLOCK_COST per block of ascent, which is the price of a STAIR
     * STEP — it assumes the terrain provides the climb, and as an A* heuristic it MUST underestimate
     * or the search stops being admissible. This function is not a search heuristic. It is the
     * comparison the block scanner uses to pick which block to walk to, and for a comparison an
     * underestimate is simply wrong.
     *
     * <p>What it cost, measured on the @gamer playthrough: a dark oak canopy log three blocks away
     * and seven up scored 10.7 + 22.1 = 32.8 against a trunk twelve blocks away at 42.8, so the bot
     * chose the canopy every time. It cannot get there — the block-space search has no way up seven
     * blocks of air — so it walked at it until the move checker gave up, blacklisted that log,
     * and picked the next canopy log one block over. The counters say the same thing: 29
     * "unreachable" verdicts in fifteen minutes, every one of them from more than four blocks away,
     * averaging 162 blocks of distance. Wood took 21 seconds when a log happened to be at eye
     * level, and 219 seconds or never when it did not.
     *
     * <p>So price a climb at what a climb actually costs this bot when nothing is there to walk up:
     * a jump plus the block it has to place under itself. Both numbers are tungsten's own — it is
     * the engine that would do the placing. Where terrain DOES provide a staircase this overprices
     * slightly and the bot prefers a flatter route to a further block, which is the right
     * preference anyway. Descending is unchanged: falling is as cheap as upstream says it is.
     */
    private static double climbCost(int yDiff) {
        if (yDiff > 0) {
            return ActionCosts.distanceToTicks(2) / 2 * yDiff;
        }
        if (yDiff < 0) {
            return -yDiff * (ActionCosts.JUMP_ONE_BLOCK_COST + ActionCosts.PLACE_ONE_BLOCK_COST);
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
