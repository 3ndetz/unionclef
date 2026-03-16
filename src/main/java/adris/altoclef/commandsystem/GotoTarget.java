package adris.altoclef.commandsystem;

import adris.altoclef.util.Dimension;

import java.util.ArrayList;
import java.util.List;

public class GotoTarget {
    private final int x;
    private final int y;
    private final int z;
    private final Dimension dimension;
    private final GotoTargetCoordType type;

    public GotoTarget(int x, int y, int z, Dimension dimension, GotoTargetCoordType type) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.type = type;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public Dimension getDimension() {
        return dimension;
    }

    public boolean hasDimension() {
        return dimension != null;
    }

    public GotoTargetCoordType getType() {
        return type;
    }

    // Combination of types we can have
    public enum GotoTargetCoordType {
        XYZ, // [x, y, z]
        XZ,  // [x, z]
        Y,   // [y]
        NONE // []
    }
}
