package kaptainwutax.tungsten.path.blockSpaceSearchAssist;

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

    public static double calculate(double xDiff, int yDiff, double zDiff) {
	    
	    return (Math.sqrt(xDiff * xDiff + yDiff * yDiff + zDiff * zDiff));
    }
}
