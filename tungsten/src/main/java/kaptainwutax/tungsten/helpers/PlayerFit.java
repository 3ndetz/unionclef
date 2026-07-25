package kaptainwutax.tungsten.helpers;

import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.WorldView;

/**
 * The single source of truth for "can a player BE here", computed from real
 * collision shapes for the real body (0.6 wide, 1.8 tall).
 *
 * WHY THIS EXISTS. The block-space search used to answer that question with
 * {@code BlockShapeChecker.getShapeVolume}, which is the XZ AREA of a collision
 * shape with the Y extent thrown away, plus a list of block-class exceptions.
 * Height was therefore never checked, so a gap capped by a slab — 1.5 blocks of
 * real clearance — was planned straight through; the physics engine (which DOES
 * model 0.6x1.8 against real shapes) then could not execute the plan and the bot
 * stalled in front of it. The disabled line in MovementHelper.isObscured even
 * carried a TODO admitting the bot "thinks it can go through a wall made of
 * slabs". Block-space and physics now answer from the same geometry.
 *
 * Everything here is pure world queries: no state, safe to call from the search
 * threads exactly like the old helpers were.
 */
public final class PlayerFit {

    public static final double WIDTH = 0.6;
    public static final double HEIGHT = 1.8;
    private static final double HALF = WIDTH / 2.0;
    private static final double EPS = 1.0E-7;
    /** Vanilla step-up without jumping. */
    public static final double STEP_HEIGHT = 0.6;
    /** A sprint-jump apex clears about this much; above it a plain jump fails. */
    public static final double JUMP_HEIGHT = 1.25;

    private PlayerFit() {}

    /** Collision shape of one block, moved into world coordinates. */
    private static VoxelShape shapeAt(WorldView world, BlockPos pos) {
        return world.getBlockState(pos).getCollisionShape(world, pos)
                .offset(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * FAST CLASSIFICATION — the reason this class is affordable inside a search.
     * The exact test builds shapes and runs a boolean shape operation; doing that
     * for every candidate cell of every expansion drove the client to ~1 fps on
     * generated terrain. The overwhelming majority of cells are either empty air
     * or a plain full cube, and both answers are exact without touching a
     * VoxelShape operation. Only genuinely partial blocks (slabs, stairs, fences,
     * trapdoors, carpets, snow) fall through to the precise path.
     * 0 = empty, 1 = full cube, 2 = partial (must be measured).
     */
    private static int classify(WorldView world, BlockPos pos) {
        net.minecraft.block.BlockState state = world.getBlockState(pos);
        if (state.isAir()) return 0;
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) return 0;
        if (shape == net.minecraft.util.shape.VoxelShapes.fullCube()) return 1;
        net.minecraft.util.math.Box bb = shape.getBoundingBox();
        if (bb.minX <= 0.0001 && bb.minY <= 0.0001 && bb.minZ <= 0.0001
                && bb.maxX >= 0.9999 && bb.maxY >= 0.9999 && bb.maxZ >= 0.9999) return 1;
        return 2;
    }

    /**
     * Does the player body fit with its feet at (x, feetY, z)?
     * Tests the real 0.6x1.8 box against the real shapes of every block it
     * overlaps — this is what makes slabs, stairs, trapdoors, fences, carpets
     * and snow layers count for exactly as much as they physically are.
     */
    public static boolean bodyFits(WorldView world, double x, double feetY, double z) {
        Box box = new Box(x - HALF, feetY, z - HALF, x + HALF, feetY + HEIGHT, z + HALF)
                .contract(EPS);
        VoxelShape boxShape = VoxelShapes.cuboid(box);
        int minX = MathHelper.floor(box.minX), maxX = MathHelper.floor(box.maxX);
        int minY = MathHelper.floor(box.minY), maxY = MathHelper.floor(box.maxY);
        int minZ = MathHelper.floor(box.minZ), maxZ = MathHelper.floor(box.maxZ);
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    p.set(bx, by, bz);
                    int kind = classify(world, p);
                    if (kind == 0) continue;             // air: cheap, exact
                    if (kind == 1) return false;         // full cube overlapping the body
                    // partial block: measure it properly
                    if (VoxelShapes.matchesAnywhere(shapeAt(world, p), boxShape,
                            BooleanBiFunction.AND)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Surface height the player would stand on inside this cell, or NaN when
     * there is nothing to stand on. A bottom slab / snow layer / carpet INSIDE
     * the cell is a floor at its own top; otherwise the floor is the top of the
     * block below (y of the cell for a full block).
     */
    public static double supportTop(WorldView world, BlockPos cell) {
        int self = classify(world, cell);
        if (self == 2) {   // partial block IN the cell (slab/snow/carpet) = the floor
            double top = world.getBlockState(cell).getCollisionShape(world, cell)
                    .getMax(Direction.Axis.Y);
            if (top < 1.0) return cell.getY() + top;
        }
        BlockPos below = cell.down();
        int under = classify(world, below);
        if (under == 0) return Double.NaN;
        if (under == 1) return below.getY() + 1.0;          // full cube: exact, no shape math
        return below.getY() + world.getBlockState(below).getCollisionShape(world, below)
                .getMax(Direction.Axis.Y);
    }

    /**
     * Can the player STAND in this cell: something to stand on, and the body
     * fits above that surface. This is the test that rejects the slab-capped
     * 1.5-block passage.
     */
    public static boolean standable(WorldView world, BlockPos cell) {
        double top = supportTop(world, cell);
        if (Double.isNaN(top)) return false;
        return bodyFits(world, cell.getX() + 0.5, top, cell.getZ() + 0.5);
    }

    /** Can the player pass THROUGH this cell with feet at the given height. */
    public static boolean passableAt(WorldView world, BlockPos cell, double feetY) {
        return bodyFits(world, cell.getX() + 0.5, feetY, cell.getZ() + 0.5);
    }

    /**
     * Can the player move from a surface at {@code fromTop} into {@code to}
     * without jumping (step-up) — and does the body fit there.
     */
    public static boolean canStepTo(WorldView world, double fromTop, BlockPos to) {
        double top = supportTop(world, to);
        if (Double.isNaN(top)) return false;
        if (top - fromTop > STEP_HEIGHT) return false;
        return bodyFits(world, to.getX() + 0.5, top, to.getZ() + 0.5);
    }

    /** Same, but allowing a jump (used by the ascend/parkour move generators). */
    public static boolean canJumpTo(WorldView world, double fromTop, BlockPos to) {
        double top = supportTop(world, to);
        if (Double.isNaN(top)) return false;
        if (top - fromTop > JUMP_HEIGHT) return false;
        return bodyFits(world, to.getX() + 0.5, top, to.getZ() + 0.5);
    }

    /**
     * Head-room along a straight move between two cells: every cell the body
     * sweeps through must accept it at the walking height. Cheap sampling at
     * half-block steps — enough to catch a slab/fence cap mid-corridor, which
     * the class-based predicate it replaces could not.
     */
    public static boolean corridorClear(WorldView world, BlockPos from, BlockPos to) {
        double fromTop = supportTop(world, from);
        double toTop = supportTop(world, to);
        if (Double.isNaN(fromTop) || Double.isNaN(toTop)) return false;
        double x0 = from.getX() + 0.5, z0 = from.getZ() + 0.5;
        double x1 = to.getX() + 0.5, z1 = to.getZ() + 0.5;
        double dist = Math.sqrt((x1 - x0) * (x1 - x0) + (z1 - z0) * (z1 - z0));
        int steps = Math.max(1, (int) Math.ceil(dist / 0.5));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double feet = Math.max(fromTop, toTop);   // the higher surface is the constraint
            if (!bodyFits(world, x0 + (x1 - x0) * t, feet, z0 + (z1 - z0) * t)) return false;
        }
        return true;
    }
}
