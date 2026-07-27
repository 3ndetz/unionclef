package kaptainwutax.tungsten.path.blockSpaceSearchAssist;

import kaptainwutax.tungsten.path.calculators.ActionCosts;
import net.minecraft.util.math.BlockPos;

public class Goal {
	/**
     * The X block position of this goal
     */
    public final int x;

    /**
     * The Y block position of this goal
     */
    public final int y;

    /**
     * The Z block position of this goal
     */
    public final int z;

    public Goal(BlockPos pos) {
        this(pos.getX(), pos.getY(), pos.getZ());
    }

    public Goal(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }


    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && y == this.y && z == this.z;
    }


    public double heuristic(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return calculate(xDiff, yDiff, zDiff);
    }

    @Override
    public String toString() {
        return String.format(
                "GoalBlock{x=%s,y=%s,z=%s}",
                Integer.toString(x),
                Integer.toString(y),
                Integer.toString(z)
        );
    }

    /**
     * @return The position of this goal as a {@link BlockPos}
     */
    public BlockPos getGoalPos() {
        return new BlockPos(x, y, z);
    }

    /**
     * Straight-line distance to the goal, expressed in the SAME UNIT as the move costs
     * (ticks) via {@link ActionCosts#HEURISTIC_PER_BLOCK}.
     *
     * <p>This used to return raw block distance while costs accumulated in ticks
     * (~4.6/block). Mixing the two makes h ~4.6x too small, and an A* whose heuristic is
     * far below the true remaining cost degenerates into Dijkstra: it expands almost
     * uniformly in every direction instead of pushing toward the goal, and burns its node
     * budget long before it arrives. (Audit 2026-07-27, C2.2.)
     */
    public static double calculate(double xDiff, int yDiff, double zDiff) {
        double blocks = Math.sqrt(xDiff * xDiff + yDiff * yDiff + zDiff * zDiff);
        return blocks * ActionCosts.HEURISTIC_PER_BLOCK;
    }
}
